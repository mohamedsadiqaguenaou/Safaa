// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

// NoMusic page engine.
//
// NoMusicTabHelper injects this into Brave's internal isolated world of every
// frame that starts playing media while NoMusic is on. The browser wraps the
// file as
//   (<this file>)("<base64 of fastenhancer_t.wasm>", "<base64 of rnnoise.wasm>");
// so it must stay a single function expression without a trailing semicolon.
//
// The processing is the NoMusic extension's: FastEnhancer-T (one stream per
// channel), then RNNoise (two chained passes per channel and a voice gate),
// and a one-second fade-in:
//   <video>/<audio> -> MediaElementAudioSourceNode -> NoMusic processor
//   -> fade-in gain -> AudioContext.destination
// The models run in two dedicated Workers chained like the extension's
// stages, AudioWorklet -> FastEnhancer -> RNNoise -> AudioWorklet, so the
// real-time audio thread only moves audio and keeps a jitter buffer, and the
// context can use Chrome's low-latency output.
// Fallbacks: both models inside the AudioWorklet when Workers cannot start,
// and a ScriptProcessorNode on the page's main thread
// when the worklet cannot load. Pages' CSPs do not block either from Brave's
// internal world (see chromium_src/.../core/workers/). The WebAssembly modules
// are compiled here, under the isolated world's CSP, and handed over already
// compiled. Media is only rerouted once the engine is running, so a failure
// anywhere leaves playback untouched.
//
// Diagnostics: __braveNoMusic.stats() and .setRnnoisePasses() in this
// isolated world, e.g. from chrome://inspect.
(function (wasmBase64, rnnoiseBase64) {
  'use strict'

  const API_KEY = '__braveNoMusic'
  if (globalThis[API_KEY]) {
    globalThis[API_KEY].setEnabled(true)
    return 'engine'
  }

  // FastEnhancer runs at 48 kHz with 512-sample hops.
  const SAMPLE_RATE = 48000
  // RNNoise post-filter after FastEnhancer, on like in the extension.
  const RNNOISE_ENABLED = true
  // Picked on phones: on Android 'balanced' renders 20 ms chunks on the
  // low-latency output, smooth without the extra delay of the power-saving
  // output ('playback').
  const LATENCY_HINT = 'balanced'
  // RNNoise passes: two like the extension; setRnnoisePasses() also takes
  // one, or none (FastEnhancer alone).
  const RNNOISE_PASS_OPTIONS = [2, 1, 0]
  // Like the extension's STARTUP_FADE_MS.
  const STARTUP_FADE_S = 1
  const PROCESSOR_NAME = 'brave-nomusic-processor'
  const READY_TIMEOUT_MS = 5000
  // A media reload in CORS mode that has not loaded by then counts as refused.
  const CORS_LOAD_TIMEOUT_MS = 15000
  const SCRIPT_PROCESSOR_BUFFER = 4096
  const RESUME_GESTURES = ['pointerdown', 'keydown', 'touchend', 'click']

  // The NoMusic DSP: streaming FastEnhancer on top of the C ABI exported by
  // fastenhancer_t.wasm (fe_hop_size, fe_stream_*), and RNNoise. It must not
  // reference anything outside its own body: the Worker and AudioWorklet
  // modules are built from its source text.
  function createDspClass() {
    // |sample| below this counts as silence.
    const SILENCE = 1e-6
    // About 85 ms of silence (e.g. paused media) parks the model.
    const IDLE_AFTER_SILENT_HOPS = 8
    // About 1 s of dual-mono input lets a diverged right stream re-join the
    // left one.
    const MONO_RESYNC_HOPS = 100
    const FIFO_CAPACITY = 16384
    const LEFT = 1
    const RIGHT = 2
    const QUANTUM = 128

    // RNNoise is set up like rnnoise-worklet.js of the NoMusic extension: two
    // chained passes per channel on 10 ms frames, then a gate driven by the
    // voice activity of the last pass.
    const RNN_FRAME = 480
    const RNN_PASSES = 2
    const RNN_SCALE = 32768
    const VAD_GATE_LO = 0.3
    const VAD_GATE_HI = 0.6
    const GATE_ATTACK = 0.05
    const GATE_RELEASE = 0.02
    const RNN_IDLE_AFTER_SILENT_FRAMES = 8

    class StereoFifo {
      constructor(capacity) {
        this.capacity = capacity
        this.left = new Float32Array(capacity)
        this.right = new Float32Array(capacity)
        this.read = 0
        this.size = 0
      }

      // Appends |n| samples per channel; null channels append silence.
      push(left, right, n) {
        this.pushAt(left, 0, right, 0, n)
      }

      // Appends the two halves of a planar [left | right] block.
      pushPlanar(block, n) {
        this.pushAt(block, 0, block, n, n)
      }

      pushAt(left, leftOffset, right, rightOffset, n) {
        const overflow = this.size + n - this.capacity
        if (overflow > 0) {
          // Drop the oldest samples rather than the newest.
          this.read = (this.read + overflow) % this.capacity
          this.size -= overflow
        }
        let write = (this.read + this.size) % this.capacity
        for (let i = 0; i < n; i++) {
          this.left[write] = left ? left[leftOffset + i] : 0
          this.right[write] = right ? right[rightOffset + i] : 0
          if (++write === this.capacity) {
            write = 0
          }
        }
        this.size += n
      }

      // Drops the oldest |n| samples per channel.
      skip(n) {
        const count = Math.min(n, this.size)
        this.read = (this.read + count) % this.capacity
        this.size -= count
      }

      // Removes |n| samples per channel, padding with silence on underflow.
      pull(outL, outR, n) {
        const available = Math.min(n, this.size)
        let read = this.read
        for (let i = 0; i < available; i++) {
          outL[i] = this.left[read]
          outR[i] = this.right[read]
          if (++read === this.capacity) {
            read = 0
          }
        }
        for (let i = available; i < n; i++) {
          outL[i] = 0
          outR[i] = 0
        }
        this.read = read
        this.size -= available
      }
    }

    // RNNoise on top of the Emscripten exports of rnnoise.wasm:
    //   c memory, d ctors, f rnnoise_create, g malloc,
    //   j rnnoise_process_frame
    // and its imports a.a (emscripten_resize_heap) and a.b (memcpy).
    class RnnoiseStage {
      constructor(module, maxJobsPerRender, prefill) {
        let memory = null
        let heapU8 = null
        const imports = {
          a: {
            a: (requestedSize) => {
              requestedSize >>>= 0
              const oldSize = heapU8.length
              const maxSize = 2147483648
              if (requestedSize > maxSize) {
                return false
              }
              for (let cutDown = 1; cutDown <= 4; cutDown *= 2) {
                let overGrown = oldSize * (1 + 0.2 / cutDown)
                overGrown = Math.min(overGrown, requestedSize + 100663296)
                const newSize = Math.min(
                  maxSize,
                  (Math.max(requestedSize, overGrown) + 65535) & ~65535)
                try {
                  memory.grow((newSize - heapU8.byteLength + 65535) >>> 16)
                  heapU8 = new Uint8Array(memory.buffer)
                  return 1
                } catch (error) {}
              }
              return 0
            },
            b: (dest, src, num) => {
              heapU8.copyWithin(dest, src, src + num)
            }
          }
        }
        const exports = new WebAssembly.Instance(module, imports).exports
        memory = exports.c
        heapU8 = new Uint8Array(memory.buffer)
        exports.d()

        this.memory = memory
        this.heapF32 = null
        this.processFrame = exports.j
        this.channels = []
        for (let c = 0; c < 2; c++) {
          const states = []
          for (let pass = 0; pass < RNN_PASSES; pass++) {
            const state = exports.f(0)
            if (!state) {
              throw new Error('rnnoise_create failed')
            }
            states.push(state)
          }
          this.channels.push({ states, gate: 0 })
        }
        this.inPtr = exports.g(RNN_FRAME * 4)
        this.outPtr = exports.g(RNN_FRAME * 4)
        if (!this.inPtr || !this.outPtr) {
          throw new Error('rnnoise malloc failed')
        }

        this.maxJobs = maxJobsPerRender
        this.enabled = true
        this.passes = RNN_PASSES
        // Frame being filled, and the frame whose channels are being run.
        this.fillFrame = [new Float32Array(RNN_FRAME),
                          new Float32Array(RNN_FRAME)]
        this.workFrame = [new Float32Array(RNN_FRAME),
                          new Float32Array(RNN_FRAME)]
        this.outFrame = [new Float32Array(RNN_FRAME),
                         new Float32Array(RNN_FRAME)]
        this.fill = 0
        this.framePending = false
        this.jobs = 0
        this.bypassFrame = false
        this.silentFrames = 0
        this.fifo = new StereoFifo(FIFO_CAPACITY)
        this.fifo.push(null, null, prefill)
      }

      // Chained passes per frame: two like the extension, fewer to save CPU.
      setPasses(passes) {
        this.passes = Math.max(1, Math.min(RNN_PASSES, passes))
      }

      heap() {
        if (!this.heapF32 || this.heapF32.buffer !== this.memory.buffer) {
          this.heapF32 = new Float32Array(this.memory.buffer)
        }
        return this.heapF32
      }

      // Feeds |n| samples per channel and writes |n| samples. The input is
      // fully consumed before any output is written, so they may alias.
      render(inL, inR, outL, outR, n) {
        let offset = 0
        while (offset < n) {
          const take = Math.min(n - offset, RNN_FRAME - this.fill)
          const fillL = this.fillFrame[0]
          const fillR = this.fillFrame[1]
          for (let i = 0; i < take; i++) {
            fillL[this.fill + i] = inL[offset + i]
            fillR[this.fill + i] = inR[offset + i]
          }
          this.fill += take
          offset += take
          if (this.fill === RNN_FRAME) {
            if (this.framePending) {
              this.finishFrame()
            }
            this.startFrame()
            this.fill = 0
          }
        }

        for (let budget = this.maxJobs; this.jobs && budget > 0; budget--) {
          this.runJob()
        }
        if (this.framePending && !this.jobs) {
          this.finishFrame()
        }
        this.fifo.pull(outL, outR, n)
      }

      startFrame() {
        const filled = this.fillFrame
        this.fillFrame = this.workFrame
        this.workFrame = filled
        this.framePending = true
        this.jobs = 0
        this.bypassFrame = !this.enabled
        if (this.bypassFrame) {
          return
        }

        let silent = true
        for (const samples of filled) {
          for (let i = 0; i < RNN_FRAME; i++) {
            if (samples[i] !== 0) {
              silent = false
              break
            }
          }
        }
        if (silent) {
          this.silentFrames++
          if (this.silentFrames > RNN_IDLE_AFTER_SILENT_FRAMES) {
            // What RNNoise would do on digital silence: no voice, so the gate
            // keeps closing and the output stays silent.
            for (let c = 0; c < 2; c++) {
              const channel = this.channels[c]
              channel.gate -= channel.gate * GATE_RELEASE
              this.outFrame[c].fill(0)
            }
            return
          }
        } else {
          this.silentFrames = 0
        }
        this.jobs = LEFT | RIGHT
      }

      runJob() {
        const c = this.jobs & LEFT ? 0 : 1
        const channel = this.channels[c]
        const input = this.workFrame[c]
        const inOff = this.inPtr >>> 2
        const outOff = this.outPtr >>> 2
        let heap = this.heap()
        for (let i = 0; i < RNN_FRAME; i++) {
          heap[inOff + i] = input[i] * RNN_SCALE
        }
        let vad = 0
        for (let pass = 0; pass < this.passes; pass++) {
          vad = this.processFrame(channel.states[pass], this.outPtr, this.inPtr)
          heap = this.heap()
          if (pass < this.passes - 1) {
            heap.copyWithin(inOff, outOff, outOff + RNN_FRAME)
          }
        }

        let target
        if (vad >= VAD_GATE_HI) {
          target = 1
        } else if (vad <= VAD_GATE_LO) {
          target = 0
        } else {
          target = (vad - VAD_GATE_LO) / (VAD_GATE_HI - VAD_GATE_LO)
        }
        const alpha = target > channel.gate ? GATE_ATTACK : GATE_RELEASE
        channel.gate += (target - channel.gate) * alpha

        const output = this.outFrame[c]
        for (let i = 0; i < RNN_FRAME; i++) {
          output[i] = (heap[outOff + i] / RNN_SCALE) * channel.gate
        }
        this.jobs &= c === 0 ? ~LEFT : ~RIGHT
      }

      finishFrame() {
        while (this.jobs) {
          this.runJob()
        }
        const frame = this.bypassFrame ? this.workFrame : this.outFrame
        this.fifo.push(frame[0], frame[1], RNN_FRAME)
        this.framePending = false
      }
    }

    class NoMusicDsp {
      // |rnnoiseModule| may be null to run FastEnhancer alone. Options:
      //   maxJobsPerRender: model runs per render() call and stage. The
      //     AudioWorklet passes 1 so the left and right channels of a stereo
      //     source run in consecutive render quanta instead of the same one.
      //   fePrefill, rnnPrefill: silence each stage starts with so that
      //     render() never runs dry. Streaming 128-sample quanta needs a hop,
      //     and a frame plus a quantum; hop-aligned blocks need none, and a
      //     frame.
      constructor(module, rnnoiseModule, options) {
        const fe = new WebAssembly.Instance(module, {}).exports
        this.fe = fe
        this.hop = fe.fe_hop_size()
        const {
          maxJobsPerRender = 1,
          fePrefill = this.hop,
          rnnPrefill = RNN_FRAME + QUANTUM
        } = options || {}
        this.maxJobs = maxJobsPerRender
        this.wet = 1
        this.enabled = true
        this.rnnoiseOn = true
        this.streams = [fe.fe_stream_new(), fe.fe_stream_new()]
        this.views = null
        this.viewsBuffer = null

        const hop = this.hop
        this.hopIn = [new Float32Array(hop), new Float32Array(hop)]
        this.hopFill = 0
        this.hopOut = [new Float32Array(hop), new Float32Array(hop)]
        this.hopPending = false
        // Channels of the pending hop that still need a model run.
        this.jobs = 0
        // Dual-mono hop: the left result is used for both channels.
        this.copyLeft = false

        this.fifo = new StereoFifo(FIFO_CAPACITY)
        this.fifo.push(null, null, fePrefill)

        this.statesEqual = true
        this.rightStale = false
        this.monoRun = 0
        this.silentHops = 0
        this.idle = false

        // Like the extension, a failing RNNoise only drops the post-filter.
        this.rnnoise = null
        if (rnnoiseModule) {
          try {
            this.rnnoise =
              new RnnoiseStage(rnnoiseModule, maxJobsPerRender, rnnPrefill)
          } catch (error) {}
        }
        this.mid = [new Float32Array(QUANTUM), new Float32Array(QUANTUM)]
      }

      get rnnoiseActive() {
        return !!this.rnnoise && this.rnnoiseOn
      }

      setEnabled(enabled) {
        if (this.enabled === enabled) {
          return
        }
        this.enabled = enabled
        this.updateRnnoise()
        // The model state is stale after running in bypass.
        if (enabled) {
          this.resetModel()
        }
      }

      // Turns the RNNoise post-filter into a pass-through, keeping latency.
      setRnnoiseEnabled(on) {
        this.rnnoiseOn = on
        this.updateRnnoise()
      }

      updateRnnoise() {
        if (this.rnnoise) {
          this.rnnoise.enabled = this.enabled && this.rnnoiseOn
        }
      }

      resetModel() {
        for (const stream of this.streams) {
          this.fe.fe_stream_reset(stream)
        }
        this.statesEqual = true
        this.rightStale = false
        this.monoRun = 0
      }

      refreshViews() {
        const buffer = this.fe.memory.buffer
        if (buffer === this.viewsBuffer) {
          return
        }
        this.viewsBuffer = buffer
        this.views = this.streams.map((stream) => ({
          input: new Float32Array(buffer, this.fe.fe_stream_input(stream),
                                  this.hop),
          output: new Float32Array(buffer, this.fe.fe_stream_output(stream),
                                   this.hop)
        }))
      }

      // Feeds |n| input samples per channel and writes |n| output samples.
      // The input is fully consumed before any output is written, so the
      // output may alias the input.
      render(inL, inR, outL, outR, n) {
        const hop = this.hop
        let offset = 0
        while (offset < n) {
          const take = Math.min(n - offset, hop - this.hopFill)
          const hopL = this.hopIn[0]
          const hopR = this.hopIn[1]
          for (let i = 0; i < take; i++) {
            hopL[this.hopFill + i] = inL[offset + i]
            hopR[this.hopFill + i] = inR[offset + i]
          }
          this.hopFill += take
          offset += take
          if (this.hopFill === hop) {
            if (this.hopPending) {
              this.finishHop()
            }
            this.startHop()
            this.hopFill = 0
          }
        }

        for (let budget = this.maxJobs; this.jobs && budget > 0; budget--) {
          this.runJob()
        }
        if (this.hopPending && !this.jobs) {
          this.finishHop()
        }

        if (!this.rnnoise) {
          this.fifo.pull(outL, outR, n)
          return
        }
        if (this.mid[0].length !== n) {
          this.mid = [new Float32Array(n), new Float32Array(n)]
        }
        this.fifo.pull(this.mid[0], this.mid[1], n)
        this.rnnoise.render(this.mid[0], this.mid[1], outL, outR, n)
      }

      startHop() {
        const hop = this.hop
        const inL = this.hopIn[0]
        const inR = this.hopIn[1]
        this.hopPending = true
        this.jobs = 0

        if (!this.enabled) {
          this.hopOut[0].set(inL)
          this.hopOut[1].set(inR)
          return
        }

        let mono = true
        let silent = true
        for (let i = 0; i < hop; i++) {
          const l = inL[i]
          const r = inR[i]
          if (l !== r) {
            mono = false
          }
          if (l > SILENCE || l < -SILENCE || r > SILENCE || r < -SILENCE) {
            silent = false
          }
        }

        if (silent) {
          this.silentHops++
          if (this.silentHops > IDLE_AFTER_SILENT_HOPS) {
            this.idle = true
            this.hopOut[0].fill(0)
            this.hopOut[1].fill(0)
            return
          }
        } else {
          this.silentHops = 0
          if (this.idle) {
            this.idle = false
            this.resetModel()
          }
        }

        if (mono) {
          this.monoRun++
          if (!this.statesEqual && this.monoRun >= MONO_RESYNC_HOPS) {
            // Both streams have seen the same audio long enough to converge.
            this.statesEqual = true
            this.rightStale = true
          }
        } else {
          this.monoRun = 0
        }

        this.refreshViews()
        this.views[0].input.set(inL)
        if (mono && this.statesEqual) {
          // Only the left stream runs; the right one lags until stereo
          // returns.
          this.rightStale = true
          this.copyLeft = true
          this.jobs = LEFT
          return
        }

        if (this.rightStale) {
          this.fe.fe_stream_copy_state(this.streams[1], this.streams[0])
          this.rightStale = false
        }
        this.statesEqual = false
        this.views[1].input.set(inR)
        this.copyLeft = false
        this.jobs = LEFT | RIGHT
      }

      runJob() {
        const channel = this.jobs & LEFT ? 0 : 1
        this.fe.fe_stream_process(this.streams[channel], this.wet)
        this.refreshViews()
        const output = this.views[channel].output
        this.hopOut[channel].set(output)
        if (channel === 0 && this.copyLeft) {
          this.hopOut[1].set(output)
        }
        this.jobs &= channel === 0 ? ~LEFT : ~RIGHT
      }

      finishHop() {
        while (this.jobs) {
          this.runJob()
        }
        this.fifo.push(this.hopOut[0], this.hopOut[1], this.hop)
        this.hopPending = false
      }
    }

    NoMusicDsp.StereoFifo = StereoFifo
    NoMusicDsp.RnnoiseStage = RnnoiseStage
    return NoMusicDsp
  }

  // Body of the DSP Workers. Like createDspClass() it only uses its argument
  // and the worker global scope. Two of them form a pipeline:
  //   AudioWorklet -> FastEnhancer Worker -> RNNoise Worker -> AudioWorklet
  // Audio travels as planar [left | right] blocks of one hop; each stage
  // processes a block in place and passes on the same (transferred) buffer.
  // The FastEnhancer stage is the counterpart of the extension's
  // fastenhancer-worker.js, the RNNoise stage does what its rnnoise-worklet.js
  // does, off the audio thread.
  function workerMain(NoMusicDsp) {
    // Sustained RNNoise processing above this fraction of real time costs it
    // a pass (two -> one -> none), so that the audio stays smooth.
    const OVERLOAD = 0.85
    const OVERLOADED_WINDOWS = 2
    // About 2 s of 512-sample blocks.
    const STATS_EVERY_BLOCKS = 188

    let role = null
    let fe = null
    let rnnoise = null
    let outPort = null
    let enabled = true
    // RNNoise passes to run (2, 1 or 0), and how many of them
    // the overload ladder has shed since.
    let chosenPasses = 2
    let shedPasses = 0
    let samplesPerMs = 48
    let blocks = 0
    let busyMs = 0
    let audioMs = 0
    let maxMs = 0
    let overloaded = 0

    function activePasses() {
      return Math.max(0, chosenPasses - shedPasses)
    }

    function updateRnnoise() {
      if (rnnoise) {
        const passes = activePasses()
        if (passes > 0) {
          rnnoise.setPasses(passes)
        }
        rnnoise.enabled = enabled && passes > 0
      }
    }

    function onBlock(event) {
      const buffer = event.data
      const block = new Float32Array(buffer)
      const n = block.length >> 1
      const left = block.subarray(0, n)
      const right = block.subarray(n)
      const start = performance.now()
      if (fe) {
        fe.render(left, right, left, right, n)
      } else {
        rnnoise.render(left, right, left, right, n)
      }
      const elapsed = performance.now() - start
      outPort.postMessage(buffer, [buffer])

      busyMs += elapsed
      audioMs += n / samplesPerMs
      maxMs = Math.max(maxMs, elapsed)
      if (++blocks < STATS_EVERY_BLOCKS) {
        return
      }
      const load = busyMs / audioMs
      if (rnnoise && activePasses() > 0 && load > OVERLOAD) {
        if (++overloaded >= OVERLOADED_WINDOWS) {
          overloaded = 0
          shedPasses++
          updateRnnoise()
        }
      } else {
        overloaded = 0
      }
      self.postMessage({
        type: 'stats',
        role,
        load,
        maxMs,
        rnnoise: !!rnnoise && activePasses() > 0,
        passes: rnnoise ? activePasses() : 0,
        chosenPasses
      })
      blocks = 0
      busyMs = 0
      audioMs = 0
      maxMs = 0
    }

    self.onmessage = (event) => {
      const data = event.data || {}
      if (data.type === 'init') {
        try {
          role = data.role
          if (role === 'fastenhancer') {
            fe = new NoMusicDsp(data.module, null, {
              maxJobsPerRender: Infinity,
              fePrefill: 0
            })
          } else {
            rnnoise = new NoMusicDsp.RnnoiseStage(data.module, Infinity, 480)
          }
          if (typeof data.rnnoisePasses === 'number') {
            chosenPasses = data.rnnoisePasses
          }
          updateRnnoise()
          samplesPerMs = data.sampleRate / 1000
          outPort = data.outPort
          data.inPort.onmessage = onBlock
          self.postMessage({ type: 'ready', role })
        } catch (error) {
          self.postMessage({
            type: 'error',
            message: String((error && error.message) || error)
          })
        }
      } else if (data.type === 'enabled') {
        enabled = !!data.value
        if (fe) {
          fe.setEnabled(enabled)
        }
        updateRnnoise()
      } else if (data.type === 'rnnoise') {
        chosenPasses = data.passes
        shedPasses = 0
        overloaded = 0
        updateRnnoise()
      }
    }
  }

  // Body of the AudioWorklet module. Like createDspClass() it only uses its
  // arguments and the AudioWorkletGlobalScope.
  //
  // In 'worker' mode the processor only moves audio: it sends the input to the
  // Worker pipeline in one-hop blocks and plays the processed blocks from a
  // jitter buffer. Playback starts once ~64 ms are buffered; every underrun
  // adds ~11 ms, up to ~128 ms. In 'worklet' mode (no Workers) it runs both
  // models itself and, when the audio thread spends more than 85% of real
  // time on them for ~4 s, drops RNNoise passes (two -> one -> none).
  function workletMain(NoMusicDsp, processorName) {
    const BLOCK = 512
    const START_TARGET = 3072
    const TARGET_STEP = 512
    const MAX_TARGET = 6144
    const FIFO_CAPACITY = 16384
    const OVERLOAD = 0.85
    const OVERLOADED_WINDOWS = 2
    // About 2 s of render quanta.
    const STATS_EVERY_QUANTA = 750
    const StereoFifo = NoMusicDsp.StereoFifo

    class NoMusicProcessor extends AudioWorkletProcessor {
      constructor(options) {
        super()
        const opts = options.processorOptions || {}
        this.silence = new Float32Array(128)
        this.dsp = null
        this.io = null
        // RNNoise passes to run (2, 1 or 0), and how many of
        // them the overload ladder has shed since.
        this.chosenPasses = 2
        this.shedPasses = 0
        this.passThrough = false
        this.quanta = 0
        this.busyMs = 0
        this.windowStart = Date.now()
        this.overloaded = 0
        // Render callbacks arrive as bursts of quanta; their size and period
        // show the output buffering the browser picked.
        this.lastCall = 0
        this.burstStart = 0
        this.burstQuanta = 0
        this.maxBurst = 0
        this.periodMs = 0
        this.periods = 0
        this.port.onmessage = (event) => this.onMessage(event.data || {})

        if (opts.mode === 'worker') {
          this.io = {
            sendPort: null,
            block: new Float32Array(2 * BLOCK),
            fill: 0,
            pool: [],
            fifo: new StereoFifo(FIFO_CAPACITY),
            target: START_TARGET,
            buffering: true,
            underruns: 0
          }
          // Ready once the Worker pipeline's ports are attached.
          return
        }
        try {
          this.dsp = new NoMusicDsp(opts.module, opts.rnnoiseModule || null, {
            maxJobsPerRender: 1
          })
          if (typeof opts.rnnoisePasses === 'number') {
            this.chosenPasses = opts.rnnoisePasses
          }
          this.updateRnnoise()
          this.port.postMessage({
            type: 'ready',
            mode: 'worklet',
            rnnoise: !!this.dsp.rnnoise
          })
        } catch (error) {
          this.port.postMessage({
            type: 'error',
            message: String((error && error.message) || error)
          })
        }
      }

      onMessage(data) {
        if (data.type === 'attach' && this.io) {
          this.io.sendPort = data.sendPort
          data.receivePort.onmessage = (event) => this.onProcessed(event.data)
          this.port.postMessage({ type: 'ready', mode: 'worker' })
        } else if (data.type === 'enabled' && this.dsp) {
          this.dsp.setEnabled(!!data.value)
        } else if (data.type === 'rnnoise') {
          this.chosenPasses = data.passes
          this.shedPasses = 0
          this.overloaded = 0
          this.updateRnnoise()
        } else if (data.type === 'passthrough') {
          this.passThrough = true
        }
      }

      activePasses() {
        return Math.max(0, this.chosenPasses - this.shedPasses)
      }

      updateRnnoise() {
        if (!this.dsp || !this.dsp.rnnoise) {
          return
        }
        const passes = this.activePasses()
        if (passes > 0) {
          this.dsp.rnnoise.setPasses(passes)
        }
        this.dsp.setRnnoiseEnabled(passes > 0)
      }

      onProcessed(buffer) {
        const block = new Float32Array(buffer)
        this.io.fifo.pushPlanar(block, BLOCK)
        this.io.pool.push(block)
      }

      process(inputs, outputs) {
        const start = Date.now()
        this.trackCallbacks(start)
        const output = outputs[0]
        if (!output || !output.length) {
          return true
        }
        const n = output[0].length
        if (this.silence.length !== n) {
          this.silence = new Float32Array(n)
        }
        const input = inputs[0] || []
        const inL = input[0] || this.silence
        const inR = input[1] || inL
        const outL = output[0]
        const outR = output[1] || outL

        if (this.passThrough || (!this.dsp && !this.io)) {
          outL.set(inL)
          if (outR !== outL) {
            outR.set(inR)
          }
        } else if (this.dsp) {
          this.dsp.render(inL, inR, outL, outR, n)
        } else {
          this.renderThroughWorkers(inL, inR, outL, outR, n)
        }

        // Date.now() only ticks in milliseconds, but summed over thousands of
        // sub-millisecond calls the differences estimate the time spent here.
        const end = Date.now()
        this.busyMs += end - start
        if (++this.quanta % STATS_EVERY_QUANTA === 0) {
          this.report(end)
        }
        return true
      }

      trackCallbacks(now) {
        if (now - this.lastCall > 1) {
          if (this.burstStart) {
            this.periodMs += now - this.burstStart
            this.periods++
          }
          this.maxBurst = Math.max(this.maxBurst, this.burstQuanta)
          this.burstStart = now
          this.burstQuanta = 0
        }
        this.burstQuanta++
        this.lastCall = now
      }

      report(now) {
        const wallMs = now - this.windowStart
        this.windowStart = now
        const load = wallMs > 0 ? this.busyMs / wallMs : 0
        this.busyMs = 0
        const rnnoise = this.dsp && this.dsp.rnnoise
        if (rnnoise && this.activePasses() > 0 && load > OVERLOAD) {
          if (++this.overloaded >= OVERLOADED_WINDOWS) {
            this.overloaded = 0
            this.shedPasses++
            this.updateRnnoise()
          }
        } else {
          this.overloaded = 0
        }
        const stats = {
          type: 'stats',
          audioLoad: load,
          callbackQuanta: this.maxBurst,
          callbackMs: this.periods ? this.periodMs / this.periods : 0
        }
        if (this.io) {
          stats.underruns = this.io.underruns
          stats.target = this.io.target
          stats.buffered = this.io.fifo.size
        }
        if (rnnoise) {
          stats.rnnoise = this.activePasses() > 0
          stats.rnnoisePasses = this.activePasses()
          stats.chosenPasses = this.chosenPasses
        }
        this.port.postMessage(stats)
        this.maxBurst = 0
        this.periodMs = 0
        this.periods = 0
      }

      renderThroughWorkers(inL, inR, outL, outR, n) {
        const io = this.io
        let offset = 0
        while (offset < n) {
          const take = Math.min(n - offset, BLOCK - io.fill)
          const block = io.block
          for (let i = 0; i < take; i++) {
            block[io.fill + i] = inL[offset + i]
            block[BLOCK + io.fill + i] = inR[offset + i]
          }
          io.fill += take
          offset += take
          if (io.fill === BLOCK) {
            io.fill = 0
            if (io.sendPort) {
              io.sendPort.postMessage(block.buffer, [block.buffer])
              io.block = io.pool.pop() || new Float32Array(2 * BLOCK)
            }
          }
        }

        if (io.buffering) {
          if (io.fifo.size < io.target) {
            outL.fill(0)
            outR.fill(0)
            return
          }
          io.buffering = false
          // After a stall the backlog arrives at once; drop what is now late
          // so that the sound stays in sync with the video.
          if (io.fifo.size > io.target + BLOCK) {
            io.fifo.skip(io.fifo.size - io.target)
          }
        }
        if (io.fifo.size < n) {
          // The Workers fell behind: rebuild a deeper buffer before resuming.
          io.underruns++
          io.target = Math.min(io.target + TARGET_STEP, MAX_TARGET)
          io.buffering = true
        }
        io.fifo.pull(outL, outR, n)
      }
    }
    registerProcessor(processorName, NoMusicProcessor)
  }

  const state = {
    enabled: true,
    // Promise<boolean> for the engine start-up; false once it has failed.
    engine: null,
    ctx: null,
    node: null,
    gain: null,
    faded: false,
    // { workers, sendPort, receivePort, rnnoise } of the Worker pipeline.
    pipeline: null,
    // RNNoise passes to run (see setRnnoisePasses()), and whether RNNoise
    // could start.
    rnnoisePasses: RNNOISE_PASS_OPTIONS[0],
    rnnoiseAvailable: false,
    // Main-thread DSP of the ScriptProcessorNode fallback.
    dsp: null,
    // Set when the processor dies after media was rerouted through it.
    bypassed: false,
    sources: new Set(),
    routed: new WeakSet(),
    // Cross-origin media, see readAccess(): whether each media site (last two
    // labels of the host) served CORS, the elements being reloaded in CORS
    // mode, and the URL each element was last switched to CORS mode for.
    corsSites: new Map(),
    corsPending: new WeakSet(),
    corsTried: new WeakMap(),
    trustedTypesPolicy: null,
    // Diagnostics, see stats() below.
    stats: { mode: null }
  }

  function errorText(error) {
    return String((error && error.message) || error)
  }

  function decodeBase64(base64) {
    if (typeof Uint8Array.fromBase64 === 'function') {
      return Uint8Array.fromBase64(base64)
    }
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i)
    }
    return bytes
  }

  // Resolves once |ctx| runs. Autoplay rules may keep it suspended until the
  // next user gesture in this frame; media keeps playing directly meanwhile.
  function waitUntilRunning(ctx) {
    if (ctx.state === 'running') {
      return Promise.resolve()
    }
    return new Promise((resolve) => {
      const tryResume = () => {
        ctx.resume().catch(() => {})
      }
      const onStateChange = () => {
        if (ctx.state !== 'running') {
          return
        }
        ctx.removeEventListener('statechange', onStateChange)
        for (const type of RESUME_GESTURES) {
          window.removeEventListener(type, tryResume, true)
        }
        resolve()
      }
      ctx.addEventListener('statechange', onStateChange)
      for (const type of RESUME_GESTURES) {
        window.addEventListener(type, tryResume, true)
      }
      tryResume()
    })
  }

  function blobUrl(source) {
    return URL.createObjectURL(new Blob([source], { type: 'text/javascript' }))
  }

  async function loadWorkletModule(ctx) {
    const url = blobUrl(
      `(${workletMain.toString()})(` +
      `(${createDspClass.toString()})(), ${JSON.stringify(PROCESSOR_NAME)});`)
    try {
      await ctx.audioWorklet.addModule(url)
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  function newWorker(url) {
    try {
      return new Worker(url)
    } catch (error) {
      // Pages enforcing Trusted Types (require-trusted-types-for 'script')
      // refuse plain string URLs for workers.
      if (typeof trustedTypes === 'undefined') {
        throw error
      }
      if (!state.trustedTypesPolicy) {
        state.trustedTypesPolicy = trustedTypes.createPolicy('brave-nomusic', {
          createScriptURL: (value) => value
        })
      }
      return new Worker(state.trustedTypesPolicy.createScriptURL(url))
    }
  }

  // Starts one pipeline stage reading blocks from |inPort| and writing them
  // to |outPort|.
  async function startWorker(role, module, inPort, outPort) {
    const url = blobUrl(
      `(${workerMain.toString()})((${createDspClass.toString()})());`)
    let worker = null
    try {
      worker = newWorker(url)
      await new Promise((resolve, reject) => {
        const timer = setTimeout(
          () => reject(new Error(`NoMusic ${role} worker timed out`)),
          READY_TIMEOUT_MS)
        worker.onmessage = (event) => {
          const data = event.data || {}
          if (data.type === 'ready') {
            clearTimeout(timer)
            resolve()
          } else if (data.type === 'error') {
            clearTimeout(timer)
            reject(new Error(data.message))
          }
        }
        worker.onerror = (event) => {
          clearTimeout(timer)
          reject(new Error((event && event.message) ||
                           `NoMusic ${role} worker failed`))
        }
        worker.postMessage({
          type: 'init',
          role,
          module,
          sampleRate: SAMPLE_RATE,
          rnnoisePasses: state.rnnoisePasses,
          inPort,
          outPort
        }, [inPort, outPort])
      })
      return worker
    } catch (error) {
      if (worker) {
        worker.terminate()
      }
      throw error
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  // The FastEnhancer -> RNNoise Worker pipeline. Throws when the page does not
  // allow blob: Workers; runs FastEnhancer alone if only RNNoise fails.
  async function createPipeline(module, rnnoiseModule) {
    let toWorklet = new MessageChannel()
    let feOutPort = toWorklet.port1
    let rnnoiseWorker = null
    if (rnnoiseModule) {
      const toRnnoise = new MessageChannel()
      try {
        rnnoiseWorker = await startWorker('rnnoise', rnnoiseModule,
                                          toRnnoise.port2, toWorklet.port1)
        feOutPort = toRnnoise.port1
      } catch (error) {
        console.debug('[NoMusic] RNNoise worker unavailable:', error)
        state.stats.rnnoiseError = errorText(error)
        // The failed attempt may have taken the port along.
        toWorklet = new MessageChannel()
        feOutPort = toWorklet.port1
      }
    }
    const toFe = new MessageChannel()
    let feWorker
    try {
      feWorker = await startWorker('fastenhancer', module, toFe.port2,
                                   feOutPort)
    } catch (error) {
      if (rnnoiseWorker) {
        rnnoiseWorker.terminate()
      }
      throw error
    }
    return {
      workers: rnnoiseWorker ? [feWorker, rnnoiseWorker] : [feWorker],
      sendPort: toFe.port1,
      receivePort: toWorklet.port2,
      rnnoise: !!rnnoiseWorker
    }
  }

  async function createProcessorNode(ctx, module, rnnoiseModule, pipeline) {
    const node = new AudioWorkletNode(ctx, PROCESSOR_NAME, {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      channelCount: 2,
      channelCountMode: 'explicit',
      channelInterpretation: 'speakers',
      outputChannelCount: [2],
      processorOptions: pipeline
        ? { mode: 'worker' }
        : {
            mode: 'worklet',
            module,
            rnnoiseModule,
            rnnoisePasses: state.rnnoisePasses
          }
    })
    const ready = new Promise((resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error('NoMusic processor timed out')),
        READY_TIMEOUT_MS)
      node.port.onmessage = (event) => {
        const data = event.data || {}
        if (data.type === 'ready') {
          clearTimeout(timer)
          state.stats.mode = data.mode
          state.rnnoiseAvailable = pipeline ? pipeline.rnnoise : !!data.rnnoise
          state.stats.rnnoise =
            state.rnnoiseAvailable && state.rnnoisePasses > 0
          state.stats.rnnoisePasses =
            state.rnnoiseAvailable ? state.rnnoisePasses : 0
          resolve()
        } else if (data.type === 'error') {
          clearTimeout(timer)
          reject(new Error(data.message))
        }
      }
      node.onprocessorerror = () => {
        clearTimeout(timer)
        reject(new Error('NoMusic processor failed'))
      }
    })
    if (pipeline) {
      node.port.postMessage({
        type: 'attach',
        sendPort: pipeline.sendPort,
        receivePort: pipeline.receivePort
      }, [pipeline.sendPort, pipeline.receivePort])
    }
    await ready
    node.port.onmessage = (event) => {
      const data = event.data || {}
      if (data.type !== 'stats') {
        return
      }
      Object.assign(state.stats, {
        audioLoad: data.audioLoad,
        callbackQuanta: data.callbackQuanta,
        callbackMs: data.callbackMs,
        underruns: data.underruns,
        bufferTarget: data.target,
        buffered: data.buffered
      })
      // Reports from before the latest setRnnoisePasses() are stale.
      if (data.rnnoise !== undefined &&
          data.chosenPasses === state.rnnoisePasses) {
        state.stats.rnnoise = data.rnnoise
        state.stats.rnnoisePasses = data.rnnoisePasses
      }
    }
    node.onprocessorerror = bypassProcessor
    return node
  }

  function createScriptProcessorNode(ctx, module, rnnoiseModule) {
    const NoMusicDsp = createDspClass()
    const dsp = new NoMusicDsp(module, rnnoiseModule, {
      maxJobsPerRender: Infinity,
      fePrefill: 0,
      rnnPrefill: 480
    })
    const node = ctx.createScriptProcessor(SCRIPT_PROCESSOR_BUFFER, 2, 2)
    node.onaudioprocess = (event) => {
      const input = event.inputBuffer
      const output = event.outputBuffer
      const inL = input.getChannelData(0)
      const inR = input.numberOfChannels > 1 ? input.getChannelData(1) : inL
      dsp.render(inL, inR, output.getChannelData(0),
                 output.getChannelData(1), output.length)
    }
    state.dsp = dsp
    state.stats.mode = 'script'
    state.rnnoiseAvailable = !!dsp.rnnoise
    applyRnnoisePasses()
    return node
  }

  async function compileRnnoise() {
    if (!RNNOISE_ENABLED || !rnnoiseBase64) {
      return null
    }
    try {
      return await WebAssembly.compile(decodeBase64(rnnoiseBase64))
    } catch (error) {
      console.debug('[NoMusic] RNNoise unavailable:', error)
      return null
    }
  }

  function watchPipeline(pipeline, node) {
    for (const worker of pipeline.workers) {
      worker.onmessage = (event) => {
        const data = event.data || {}
        if (data.type !== 'stats') {
          return
        }
        if (data.role === 'fastenhancer') {
          state.stats.feLoad = data.load
          state.stats.feMaxMs = data.maxMs
        } else {
          state.stats.rnnoiseLoad = data.load
          state.stats.rnnoiseMaxMs = data.maxMs
          // Reports from before the latest setRnnoisePasses() are stale.
          if (data.chosenPasses === state.rnnoisePasses) {
            state.stats.rnnoise = data.rnnoise
            state.stats.rnnoisePasses = data.passes
          }
        }
      }
      // A dead Worker would leave the processor silent: play the input as is.
      worker.onerror = () => {
        node.port.postMessage({ type: 'passthrough' })
        state.stats.mode = 'passthrough'
      }
    }
  }

  async function startEngine() {
    const [module, rnnoiseModule] = await Promise.all([
      WebAssembly.compile(decodeBase64(wasmBase64)),
      compileRnnoise()
    ])
    let pipeline = null
    try {
      pipeline = await createPipeline(module, rnnoiseModule)
    } catch (error) {
      // Typically the page CSP refusing blob: Workers.
      console.debug('[NoMusic] Worker pipeline unavailable:', error)
      state.stats.workerError = errorText(error)
    }
    const latencyHint = LATENCY_HINT
    state.stats.latencyHint = latencyHint
    const ctx = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint })
    state.ctx = ctx
    await waitUntilRunning(ctx)

    let node
    try {
      await loadWorkletModule(ctx)
      node = await createProcessorNode(ctx, module, rnnoiseModule, pipeline)
    } catch (error) {
      // Typically the page CSP refusing the blob: worklet module.
      console.debug('[NoMusic] AudioWorklet unavailable:', error)
      state.stats.workletError = errorText(error)
      if (pipeline) {
        for (const worker of pipeline.workers) {
          worker.terminate()
        }
        pipeline = null
      }
      node = createScriptProcessorNode(ctx, module, rnnoiseModule)
    }
    if (pipeline) {
      watchPipeline(pipeline, node)
    }
    // Silent until the first media element is routed, then faded in.
    const gain = ctx.createGain()
    gain.gain.value = 0
    node.connect(gain)
    gain.connect(ctx.destination)
    state.node = node
    state.gain = gain
    state.pipeline = pipeline
    applyEnabled()
    console.debug('[NoMusic] running, mode:', state.stats.mode)
    return true
  }

  function ensureEngine() {
    if (!state.engine) {
      state.engine = startEngine().catch((error) => {
        console.debug('[NoMusic] not available in this frame:', error)
        state.stats.error = errorText(error)
        return false
      })
    }
    return state.engine
  }

  // The processor died after media was routed through it: send the media
  // straight to the speakers so it is not silenced.
  function bypassProcessor() {
    if (state.bypassed) {
      return
    }
    state.bypassed = true
    state.stats.mode = 'bypassed'
    for (const source of state.sources) {
      try {
        source.disconnect()
        source.connect(state.ctx.destination)
      } catch (error) {}
    }
  }

  function mediaUrl(src) {
    if (!src) {
      return null
    }
    try {
      return new URL(src, document.baseURI)
    } catch (error) {
      return null
    }
  }

  function mediaSite(url) {
    return url.hostname.split('.').slice(-2).join('.')
  }

  // Web Audio can only read media whose origin it may see; anything else
  // comes out of a MediaElementAudioSourceNode as silence. Returns 'yes' for
  // media it can read: Media Source Extensions players (YouTube, Vimeo,
  // hls.js, ...) use blob: URLs, which belong to this document's origin, and
  // media fetched with CORS. Returns 'cors' for cross-origin media fetched
  // without CORS, which a reload in CORS mode can make readable when its
  // server allows it (Facebook's video CDN does), and 'no' otherwise.
  function readAccess(element) {
    if (element.srcObject) {
      return 'no'
    }
    const url = mediaUrl(element.currentSrc || element.src)
    if (!url) {
      return 'no'
    }
    if (url.protocol === 'blob:' || url.origin === location.origin ||
        element.crossOrigin !== null) {
      return 'yes'
    }
    if ((url.protocol !== 'https:' && url.protocol !== 'http:') ||
        state.corsSites.get(mediaSite(url)) === false) {
      return 'no'
    }
    return 'cors'
  }

  // Watches |element|, just switched to CORS mode for |url| and (re)loading:
  // resolves true once it loads, false (back without CORS, so that it plays
  // as before) if its server refuses CORS.
  function watchCorsLoad(element, url, onLoaded) {
    state.corsTried.set(element, url.href)
    state.corsPending.add(element)
    const site = mediaSite(url)
    return new Promise((resolve) => {
      const done = (loaded) => {
        clearTimeout(timer)
        element.removeEventListener('loadedmetadata', onMetadata)
        element.removeEventListener('error', onError)
        state.corsPending.delete(element)
        resolve(loaded)
      }
      const onMetadata = () => {
        state.corsSites.set(site, true)
        onLoaded()
        done(true)
      }
      const onError = () => {
        state.corsSites.set(site, false)
        state.stats.corsRefused = (state.stats.corsRefused || 0) + 1
        element.removeAttribute('crossorigin')
        element.addEventListener('loadedmetadata', onLoaded, { once: true })
        element.load()
        done(false)
      }
      const timer = setTimeout(onError, CORS_LOAD_TIMEOUT_MS)
      element.addEventListener('loadedmetadata', onMetadata)
      element.addEventListener('error', onError)
    })
  }

  // Reloads |element| in CORS mode where it was; costs a moment of playback.
  function reloadWithCors(element) {
    const url = mediaUrl(element.currentSrc || element.src)
    if (state.corsTried.get(element) === url.href) {
      return Promise.resolve(false)
    }
    const resume = {
      time: element.currentTime,
      playing: !element.paused,
      rate: element.playbackRate
    }
    const restore = () => {
      try {
        element.currentTime = resume.time
      } catch (error) {}
      element.playbackRate = resume.rate
      if (resume.playing) {
        element.play().catch(() => {})
      }
    }
    const loaded = watchCorsLoad(element, url, restore)
    state.stats.corsReloads = (state.stats.corsReloads || 0) + 1
    element.crossOrigin = 'anonymous'
    element.load()
    return loaded
  }

  // Once a media site has served CORS, the page's next sources from it are
  // switched to CORS mode before they load, so they need no reload.
  function preferCors(element) {
    if (!state.enabled || element.crossOrigin !== null || element.srcObject) {
      return
    }
    const url = mediaUrl(element.getAttribute('src'))
    if (!url || url.origin === location.origin ||
        (url.protocol !== 'https:' && url.protocol !== 'http:') ||
        state.corsSites.get(mediaSite(url)) !== true) {
      return
    }
    element.crossOrigin = 'anonymous'
    watchCorsLoad(element, url, () => {})
  }

  function watchMediaSources() {
    if (typeof MutationObserver === 'undefined') {
      return
    }
    // Runs before the media element starts loading the new source.
    new MutationObserver((records) => {
      for (const record of records) {
        if (record.target instanceof HTMLMediaElement) {
          preferCors(record.target)
        }
      }
    }).observe(document, {
      subtree: true,
      attributes: true,
      attributeFilter: ['src']
    })
  }

  function route(element) {
    if (!state.enabled || state.bypassed || state.routed.has(element) ||
        readAccess(element) !== 'yes') {
      return
    }
    let source
    try {
      source = state.ctx.createMediaElementSource(element)
    } catch (error) {
      // Already captured by the page or by another AudioContext.
      state.routed.add(element)
      return
    }
    state.routed.add(element)
    source.connect(state.node)
    state.sources.add(source)
    state.stats.routed = state.sources.size
    if (!state.faded) {
      state.faded = true
      const param = state.gain.gain
      const now = state.ctx.currentTime
      param.cancelScheduledValues(now)
      param.setValueAtTime(0, now)
      param.linearRampToValueAtTime(1, now + STARTUP_FADE_S)
    }
  }

  function handleMedia(element) {
    if (!state.enabled || state.routed.has(element) ||
        state.corsPending.has(element)) {
      return
    }
    const access = readAccess(element)
    if (access === 'no') {
      return
    }
    ensureEngine().then((ok) => {
      if (!ok || !state.enabled) {
        return
      }
      if (access === 'yes') {
        route(element)
      } else {
        reloadWithCors(element).then((loaded) => {
          if (loaded) {
            route(element)
          }
        })
      }
    })
  }

  function onMediaEvent(event) {
    const element = event.target
    if (!(element instanceof HTMLMediaElement)) {
      return
    }
    if (state.ctx && state.ctx.state === 'suspended') {
      state.ctx.resume().catch(() => {})
    }
    handleMedia(element)
  }

  function scanPlayingMedia() {
    for (const element of document.querySelectorAll('video, audio')) {
      if (!element.paused) {
        handleMedia(element)
      }
    }
  }

  function applyEnabled() {
    if (state.pipeline) {
      for (const worker of state.pipeline.workers) {
        worker.postMessage({ type: 'enabled', value: state.enabled })
      }
    } else if (state.dsp) {
      state.dsp.setEnabled(state.enabled)
    } else if (state.node && state.node.port) {
      state.node.port.postMessage({ type: 'enabled', value: state.enabled })
    }
  }

  function setEnabled(enabled) {
    state.enabled = !!enabled
    applyEnabled()
    if (state.enabled) {
      scanPlayingMedia()
    }
  }

  // Hands the chosen number of RNNoise passes to wherever RNNoise runs.
  function applyRnnoisePasses() {
    const passes = state.rnnoisePasses
    if (state.pipeline) {
      for (const worker of state.pipeline.workers) {
        worker.postMessage({ type: 'rnnoise', passes })
      }
    } else if (state.dsp) {
      if (state.dsp.rnnoise) {
        if (passes > 0) {
          state.dsp.rnnoise.setPasses(passes)
        }
        state.dsp.setRnnoiseEnabled(passes > 0)
      }
    } else if (state.node && state.node.port) {
      state.node.port.postMessage({ type: 'rnnoise', passes })
    }
    if (state.rnnoiseAvailable) {
      state.stats.rnnoise = passes > 0
      state.stats.rnnoisePasses = passes
    }
  }

  // 2 (like the extension), 1, or 0 (FastEnhancer alone); applies at once.
  function setRnnoisePasses(passes) {
    if (!RNNOISE_PASS_OPTIONS.includes(passes)) {
      return
    }
    state.rnnoisePasses = passes
    applyRnnoisePasses()
  }

  // __braveNoMusic.stats() -> { mode: 'worker' | 'worklet' | 'script',
  //   latencyHint, rnnoise, rnnoisePasses (in use), rnnoiseChosen,
  //   rnnoiseAvailable, feLoad, rnnoiseLoad, audioLoad, callbackQuanta,
  //   callbackMs, underruns, bufferTarget, buffered, baseLatency,
  //   outputLatency, routed (media elements), corsReloads, corsRefused, ... }
  function stats() {
    const result = Object.assign({}, state.stats)
    result.rnnoiseChosen = state.rnnoisePasses
    result.rnnoiseAvailable = state.rnnoiseAvailable
    if (state.ctx) {
      result.sampleRate = state.ctx.sampleRate
      result.baseLatency = state.ctx.baseLatency
      result.outputLatency = state.ctx.outputLatency
    }
    return result
  }

  Object.defineProperty(globalThis, API_KEY, {
    value: Object.freeze({ setEnabled, setRnnoisePasses, stats })
  })
  document.addEventListener('play', onMediaEvent, true)
  document.addEventListener('playing', onMediaEvent, true)
  watchMediaSources()
  scanPlayingMedia()
  return 'engine'
})

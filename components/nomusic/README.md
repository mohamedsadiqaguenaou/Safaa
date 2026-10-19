# NoMusic (Android)

NoMusic removes background music from videos on web pages and keeps the
voices. It is a port of the [NoMusic](https://github.com/mohamedsadiqaguenaou/NoMusic)
browser extension and runs the same pipeline: the FastEnhancer-T model
(`resources/fastenhancer_t.wasm`) followed by an RNNoise post-filter
(`resources/rnnoise.wasm`). Both modules are copied verbatim from the
extension; the Standard (FastEnhancer-S) model is not shipped.

The toolbar button next to Brave Shields switches the `brave.nomusic.enabled`
profile pref (`kNoMusicEnabled`). The switch is shared by regular and private
tabs and is off by default.

## How it works

1. `NoMusicTabHelper` (`brave/browser/android/nomusic/`) observes the pref.
   While it is on, every http(s) frame of the tab gets a small watcher script
   in Brave's internal isolated world through the `script_injector` mojo
   interface.
2. The watcher resolves once the frame plays media. Only then does the browser
   send that frame the engine: `resources/nomusic_engine.js` applied to the
   base64 of both WebAssembly modules, all loaded from the resource bundle.
3. The engine compiles the modules, creates a 48 kHz `AudioContext` and
   routes each `<video>`/`<audio>` element through
   `MediaElementAudioSourceNode -> NoMusic processor -> gain -> destination`.
   Per channel, the processing is:
   - FastEnhancer-T on 512-sample hops.
   - RNNoise on 480-sample frames, two chained passes as in the extension,
     whose voice activity drives a gate (attack 0.05, release 0.02 per
     frame), so stretches without speech fade out.

   The output is the same as the extension's FastEnhancer -> RNNoise
   pipeline, sample for sample, and fades in over the first second like the
   extension's.
4. The models run off the real-time audio thread, in two dedicated Workers
   chained like the extension's stages:
   `AudioWorklet -> FastEnhancer Worker -> RNNoise Worker -> AudioWorklet`.
   - The AudioWorklet only moves audio: it sends the input in 512-sample
     blocks over `MessageChannel`s and plays the processed blocks from a
     jitter buffer. Playback starts once ~64 ms are buffered; every underrun
     adds ~11 ms, up to ~128 ms, and the stale backlog is skipped so the
     sound stays in sync with the video.
   - Because the audio thread has almost nothing to do, the context uses
     Chrome's low-latency output, with `latencyHint: 'balanced'` (picked on
     phones): on Android that renders 20 ms chunks, smoother than the native
     buffer size of `'interactive'` and without the extra delay of the
     power-saving output of `'playback'`.
   - If the RNNoise Worker spends more than 85% of real time on its passes
     for ~4 s, it drops one pass at a time, down to off, rather than falling
     behind.
   - Total delay is typically 70-80 ms, below what viewers notice for lip
     sync.
   - Fallbacks: both models run inside the AudioWorklet (with the same pass
     shedding) if `blob:` Workers cannot start (Trusted Types are handled with
     a policy), and in a `ScriptProcessorNode` if the `blob:` worklet cannot
     load.
   - Dual-mono input only runs FastEnhancer once, and after a few frames of
     silence (e.g. paused media) both models are parked.
   - If RNNoise cannot start, FastEnhancer runs alone, as in the extension.
5. Turning NoMusic off puts running engines into bypass: media keeps flowing
   through the same graph unchanged, because Web Audio cannot hand an element
   back to direct playback.

Media is only rerouted after the engine is fully running, so any failure
(compile error, autoplay rules, CSP) leaves playback untouched.

## Page CSP

Pages such as m.youtube.com forbid `blob:` scripts in their Content Security
Policy. Blink checks new Workers and the module scripts of worklets against
the page's CSP even when an isolated world starts them, which left NoMusic on
the main-thread `ScriptProcessorNode` fallback there; that fallback replays
the previous 170 ms (two 4096-sample buffers) whenever the page's main thread
is late.

Brave's internal world, where the engine runs, has an empty CSP of its own
(see `cosmetic_filters_js_render_frame_observer.cc`), so its scripts are not
subject to the page's CSP. Overrides in
`chromium_src/third_party/blink/renderer/core/workers/` extend that to the
Workers and AudioWorklets those scripts start:

- `abstract_worker.{h,cc}`: the Worker creation check uses that world's CSP.
- `worklet.{h,cc}`: `addModule()` from such a world creates the worklet global
  scope right away, while the world's script runs.
- `threaded_worklet_messaging_proxy.cc`: a worklet global scope takes the CSP
  of the world whose script creates it (the page's when created from a task,
  as before).

Page scripts and isolated worlds with a non-empty CSP (extensions) are
unaffected, and the Workers' own global scopes keep the page's CSP.

## Debugging

In Brave's internal isolated world of a frame that plays media (e.g. from
`chrome://inspect` with USB debugging):

- `__braveNoMusic.stats()` reports the mode (`worker`, `worklet` or
  `script`), the output mode, each Worker's load (fraction of real time spent
  processing) and worst block time, RNNoise and its passes, the audio
  thread's load and render callback pattern, the jitter buffer and its
  underruns, the output latency and any start-up error.
- `__braveNoMusic.setRnnoisePasses(n)` switches RNNoise between 2 passes
  (like the extension), 1, and 0 (FastEnhancer alone) while playing; it also
  undoes passes the overload ladder dropped.

Build NoMusic APKs with `dcheck_always_on=false`, as official builds are:
Chromium enables fatal DCHECKs in non-official builds, which slows the whole
browser down and turns any failed internal assertion into a page crash
("Aw, Snap!").

## Cross-origin media

Web Audio reads cross-origin media as silence unless it was fetched with CORS.
Media Source Extensions players (YouTube, Vimeo, hls.js, ...) use `blob:`
URLs and are fine, but some sites play plain files from a CDN, like Facebook's
mobile site (`video.*.fbcdn.net/...mp4`, about two loads out of three). The
engine reloads such an element once in CORS mode where it was (position,
speed and playback restored; about a second and a half of interruption) and
routes it once it loads. A server that refuses CORS gets the element reloaded
as it was, unrouted, and the engine remembers that media site. Once a media
site (last two labels of the host) has served CORS, the page's next sources
from it are switched to CORS mode before they load, so they need no reload.

## Limitations

- Cross-origin media from servers that refuse CORS cannot be read by Web
  Audio, so such elements are left alone.
- Media inside closed shadow roots, or `Audio` objects that are never attached
  to the document, are not detected.
- If a page already feeds an element into its own `AudioContext`, that element
  is left alone.

## Licenses

See `resources/LICENSE` for the NoMusic (ISC), FastEnhancer (MIT) and RNNoise
(BSD) notices that cover the two WebAssembly modules.

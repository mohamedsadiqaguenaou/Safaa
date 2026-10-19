<p align="center">
  <img src="safaa_logo.svg" width="96" alt="Safaa Browser Logo" />
</p>

# Safaa Browser (صفاء)

**Safaa** (`com.safaa.browser`) is a privacy-focused, clean Android web browser built on Chromium and Brave Core, featuring built-in real-time background music removal (**NoMusic**), full built-in ad & tracker blocking (**Shields**), and always-on family-safe DNS filtering.

---

## Key Features

- **Built-in NoMusic (`components/nomusic`)**
  - Automatically removes background music from web videos and audio streams in real time while keeping speech and voices clear.
  - Powered by on-device WebAssembly neural audio processing (`fastenhancer_t.wasm` and `rnnoise.wasm`) running inside an AudioWorklet/WebAudio pipeline.
  - Enabled by default with a one-tap **NoMusic** toggle right in the browser toolbar.
- **Built-in Shields Ad & Tracker Blocking**
  - Compiles 73 filter list sources (EasyList, EasyPrivacy, uBlock filters, regional lists) and the full scriptlet library (`resources.json`) directly into the browser (`brave_shields_lists_from_sources = true`), providing full ad and YouTube ad blocking out of the box without requiring external API keys.
- **Always-On Family & Adult Content Protection**
  - Enforces Secure DNS (DoH) via CleanBrowsing Adult Filter and Cloudflare Family (`1.1.1.3`), with SafeSearch enabled by default.
- **Lightweight, Distraction-Free Design**
  - **Removed bloat:** Crypto Wallet, Brave Rewards/Ads, Brave VPN, Brave News, Leo AI Chat, Web Discovery Project, Brave Sync, and retention/reminder notifications have all been stripped out.
  - **Privacy by default:** Telemetry, P3A product analytics, and crash reporting are disabled by default.
  - **Safaa Identity:** Custom green theme (`#1AE382` in dark mode, `#006D3B` in light mode), `safaa://` internal pages scheme (with `brave://` legacy support), full Arabic localization (**صفاء**), Google as the default search engine, and a streamlined 2-step welcome flow introducing Ad Blocking and NoMusic.

---

## System Requirements (Building for Android `arm64`)

Building Chromium + Safaa for Android requires a Linux environment (Ubuntu 22.04 / 24.04 LTS, or **WSL2** on Windows):

- **OS:** Ubuntu 22.04+ (x86_64) or Windows 11 with WSL2 (Ubuntu)
- **RAM:** 16 GB+ recommended (12 GB minimum with 16 GB swap and `JOBS=6`)
- **Disk Space:** ~120 GB free ext4 disk space
- **Tools:**
  - Node.js 20+ / 24+ and `pnpm`
  - Python 3, Git, Curl, Zstandard (`zstd`), Ninja, and standard Chromium Linux/Android build dependencies

---

## 1. Initial Checkout & Setup

In your Linux / WSL2 terminal, create the workspace directory and clone this repository into `src/brave` (Chromium's build files reference `//brave/...` paths relative to `src/`):

```bash
mkdir -p ~/Safaa/src
cd ~/Safaa/src
git clone https://github.com/mohamedsadiqaguenaou/Safaa.git brave
cd ~/Safaa/src/brave
```

### Environment Setup (`~/Safaa/env.sh`)

If building inside WSL2, ensure Windows `PATH` entries (`/mnt/...`) are excluded so Windows `node.exe` or `python.exe` do not interfere with the Linux build:

```bash
cat << 'EOF' > ~/Safaa/env.sh
# Keep Windows programs out of the Linux build in WSL2
PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -v '^/mnt/' | paste -sd: -)"
PATH="$HOME/.local/node/bin:$PATH"
export PATH
EOF
source ~/Safaa/env.sh
```

### Fetch Chromium & Android Dependencies

Install Node dependencies and run `init` to fetch the matching Chromium tree, Android SDK/NDK, and apply all patches in `patches/`:

```bash
source ~/Safaa/env.sh
cd ~/Safaa/src/brave
pnpm install
pnpm run init --target_os=android --target_arch=arm64 --no-history
```

> **Note:** `pnpm run init` is safe to re-run if interrupted; `gclient` will resume downloading where it left off. After pulling new changes to `patches/`, re-apply them with:
> ```bash
> pnpm run apply_patches
> ```

---

## 2. Building the Android APK (`Safaa-arm64.apk`)

Run the `Static` build for Android `arm64` (optimized build without ThinLTO so linking fits comfortably in 12–16 GB RAM, and with `dcheck_always_on:false` for release speed and stability):

```bash
source ~/Safaa/env.sh
cd ~/Safaa/src/brave

# Adjust JOBS based on available RAM (use JOBS=6 for 12 GB RAM, JOBS=10+ for 24+ GB RAM)
JOBS="${JOBS:-6}"
export SISO_LIMITS="local=$JOBS"
export JAVA_OPTS="-Xmx4G -Xms1G"
export NODE_OPTIONS="--max-old-space-size=8192"

pnpm run build Static \
  --target_os=android \
  --target_arch=arm64 \
  --target_android_output_format=apk \
  --gn=symbol_level:0 \
  --gn=dcheck_always_on:false \
  --ninja=j:"$JOBS"
```

### Output APK Location

Once the build finishes, the compiled APK is located at:

```text
~/Safaa/src/out/android_Static_arm64/apks/BraveMonoarm64.apk
```

Copy it out as `Safaa-arm64.apk`:

```bash
cp ~/Safaa/src/out/android_Static_arm64/apks/BraveMonoarm64.apk ~/Safaa/Safaa-arm64.apk
```

---

## 3. Useful Development Commands

- **Regenerate & verify GN build files without a full build:**
  ```bash
  source ~/Safaa/env.sh
  cd ~/Safaa/src/brave
  pnpm run gn gen -C gncheck --target_os=android --target_arch=arm64
  pnpm run gn check -C gncheck '//brave/*'
  ```
- **Regenerate Chromium rewrite patches (`rewrite/**/*.yaml`):**
  ```bash
  PATH=~/Safaa/src/brave/vendor/depot_tools:$PATH vpython3 tools/cr/plaster.py apply <path-to-yaml>
  ```
- **Sign a release APK / AAB for Google Play:**
  Generate a keystore with `keytool -genkeypair -v -keystore safaa-release.keystore -alias safaa -keyalg RSA -keysize 2048 -validity 10000` and sign the output APK with Android SDK's `apksigner`, or build with `--target_android_output_format=aab` for Play Store App Bundles.

---

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).

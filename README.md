# Comet — Android TV Browser

A feature-rich, remote-first web browser for **Google TV / Android TV**, distributed as a
sideloaded APK (via the **Downloader** app) with configurable ad/popup blocking and reliable
fullscreen HTML5 video playback.

> General-purpose browser. It renders sites the way Chrome/Firefox do and contains **no**
> DRM/anti-circumvention bypass, stream extraction, or paywall-bypass logic.

## 📺 Install on your TV (Downloader)

Open the **Downloader** app on your Fire TV / Google TV and enter this code:

> # Downloader code: `2453488`

| | |
|---|---|
| **Downloader code** | `2453488` |
| **Short URL** | `aftv.news/2453488` |
| **Direct (always latest)** | `https://github.com/SamWylde/comet-tv/releases/latest/download/app-webview-universal-release.apk` |
| **Info page** | `aftv.news/2453488+` |

**Steps:**
1. Install the **Downloader** app (by AFTVnews) from your TV's app store, and open it.
2. In the URL/Home box, type **`2453488`** and press **Go** (or enter `aftv.news/2453488`).
3. Let it download, then choose **Install**. First time only, Android will ask to **allow Downloader
   to install unknown apps** — enable it, go back, and Install.
4. Delete the APK when prompted (saves space). **Comet** now appears in your apps row.

Notes:
- This is the **universal** WebView build (~3 MB) — installs on any CPU, including 32-bit devices
  like the Chromecast with Google TV HD as well as all 64-bit Fire TV / Google TV hardware.
- The code/URL are **permanent**: they always point to the newest release, so the same `2453488`
  keeps working after every update (no need to re-issue it).
- Prefer the GeckoView build? Use the `full` APK from the
  [latest release](https://github.com/SamWylde/comet-tv/releases/latest) (~185 MB).

## Engines (two build flavors)
The app abstracts the renderer behind `BrowserEngine` and ships **two engines**, switchable in
Settings (default: WebView):

| Flavor    | Engines bundled            | Notes |
|-----------|----------------------------|-------|
| `webview` | System WebView (Chromium)  | Slim APK (~3 MB). Best video compatibility. |
| `full`    | System WebView **+ GeckoView** | Adds Gecko's built-in Enhanced Tracking Protection; large APK (~185 MB, GeckoView bundles `libxul.so`). |

### Ad / tracker blocking (what's actually implemented)
- **WebView:** request interception against a bundled hostname blocklist (auto-refreshed from a remote
  hosts list), curated cosmetic CSS hiding, and popup/redirect suppression. This is a hostname-level
  blocker — **not** an EasyList/uBlock Origin engine (no scriptlets or full cosmetic-filter syntax).
- **GeckoView:** Gecko's built-in **Enhanced Tracking Protection** (strict) — not a uBlock Origin
  WebExtension.
- All three layers (network / cosmetic / popup) are individually toggleable, with a per-site allowlist.
- A future full-fidelity path (EasyList parser or `adblock-rust`, or uBO under GeckoView) would need a
  filter-list **license review** first.

## Requirements
- **JDK 17** (Android Gradle Plugin 8.7 requires it).
- Android SDK with `platform-tools`, `platforms;android-35`, `build-tools;35.0.0`
  (+ `emulator` and `system-images;android-36;android-tv;x86_64` to run the TV emulator).
- The Gradle wrapper downloads Gradle 8.9 automatically.

`local.properties` must point `sdk.dir` at your SDK. If your default `java` isn't 17, set
`JAVA_HOME` to a JDK 17 before building (or uncomment `org.gradle.java.home` in `gradle.properties`).

## Build
```bash
# Slim WebView build
./gradlew :app:assembleWebviewDebug

# Full build (WebView + GeckoView)
./gradlew :app:assembleFullDebug
```
Outputs land in `app/build/outputs/apk/<flavor>/debug/`.

## Run on the TV emulator
```bash
# Start the Google TV AVD
$ANDROID_HOME/emulator/emulator -avd googletv

# Install + launch
adb install -r app/build/outputs/apk/webview/debug/app-webview-debug.apk
adb shell monkey -p com.tdarby.comet -c android.intent.category.LEANBACK_LAUNCHER 1
```

## Run on a real Google/Android TV device
- **Easiest:** use the Downloader app with code **`2453488`** (see [Install on your TV](#-install-on-your-tv-downloader)).
- **Developer:** enable Developer options + ADB debugging on the TV, then
  `adb connect <tv-ip>:5555` and `adb install -r <apk>`.

## Release build & distribution
```bash
# Signed release APKs (R8 minify + per-ABI splits: armeabi-v7a, arm64-v8a, x86_64 + a universal APK)
./gradlew :app:assembleWebviewRelease :app:assembleFullRelease
```
Signing reads `keystore.properties` (gitignored). Outputs:
`app/build/outputs/apk/<flavor>/release/app-<flavor>-<abi>-release.apk`.

> **Signing key:** the repo ships **no** keystore. The local `keystore.properties` / `*.keystore`
> are gitignored dev-only credentials. For a real release, generate a keystore **outside** the repo,
> reference it via `keystore.properties`, and never commit it. Rotate any dev cert before publishing.

**Publish a new version (the Downloader code `2453488` stays the same forever):**
1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, build, and create a GitHub Release —
   **keep the asset filenames identical** (no version in the name), and upload every ABI plus the
   universal APK:
   ```bash
   gh release create vX.Y.Z --latest \
     app/build/outputs/apk/webview/release/app-webview-universal-release.apk \
     app/build/outputs/apk/webview/release/app-webview-armeabi-v7a-release.apk \
     app/build/outputs/apk/webview/release/app-webview-arm64-v8a-release.apk \
     app/build/outputs/apk/webview/release/app-webview-x86_64-release.apk \
     app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk \
     app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk \
     app/build/outputs/apk/full/release/app-full-x86_64-release.apk
   ```
   Because the filename is constant, `…/releases/latest/download/app-webview-universal-release.apk`
   always serves the newest build — which is exactly what the permanent `aftv.news/2453488` code points
   at. The **universal** APK installs on any CPU (incl. 32-bit boxes like the Chromecast with Google TV
   HD), so it's the right default. Don't mark releases as *pre-release* (`/latest/` skips those).
2. (For the in-app updater) Update [dist/latest.json](dist/latest.json) with the new `versionCode`,
   `versionName`, per-flavor `apkUrl`, and `sha256` (`sha256sum app-...-release.apk`). **`sha256` is
   mandatory** — the updater refuses to install an APK without a matching hash. Point
   `UpdateChecker.MANIFEST_URL` at the hosted manifest.
3. After first install, **Menu → Check for updates** handles upgrades in place (compares `versionCode`,
   downloads, verifies SHA-256, launches the installer) — so Downloader is only needed once per device.

## Status (by milestone)
- [x] M0 — toolchain + SDK + TV AVD
- [x] M1 — project scaffold, Leanback launcher, single-engine WebView browser
- [x] M2 — both engines behind `BrowserEngine` + Settings engine switch
- [x] M3 — on-screen cursor + D-pad/remote input (long-press OK toggles cursor)
- [x] M4 — popup/redirect blocking (`PopupGuard` + Gecko `onNewSession`)
- [x] M5 — network + cosmetic ad blocking + per-layer Settings toggles + per-site allowlist + filter updater
- [x] M6 — bookmarks, history, downloads, search engines, overflow menu, home, desktop-UA toggle
- [x] M7 — signed release (R8 + ABI splits), release manifest + in-app updater (SHA-256 verified)

> Multi-tab and a graphical home-tile grid are the main not-yet-implemented extras; the browser is
> currently single-tab. Everything above is implemented and builds.

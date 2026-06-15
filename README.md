# Comet — Android TV Browser

A feature-rich, remote-first web browser for **Google TV / Android TV**, distributed as a
sideloaded APK (via the **Downloader** app) with configurable ad/popup blocking and reliable
fullscreen HTML5 video playback.

> General-purpose browser. It renders sites the way Chrome/Firefox do and contains **no**
> DRM/anti-circumvention bypass, stream extraction, or paywall-bypass logic.

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
1. Enable Developer options + ADB debugging on the TV.
2. `adb connect <tv-ip>:5555` then `adb install -r <apk>`, **or** sideload the APK via the
   Downloader app using its hosted URL (see distribution, Milestone 7).

## Release build & distribution
```bash
# Signed release APKs (R8 minify + per-ABI splits: arm64-v8a, x86_64)
./gradlew :app:assembleWebviewRelease :app:assembleFullRelease
```
Signing reads `keystore.properties` (gitignored). Outputs:
`app/build/outputs/apk/<flavor>/release/app-<flavor>-<abi>-release.apk`.

> **Signing key:** the repo ships **no** keystore. The local `keystore.properties` / `*.keystore`
> are gitignored dev-only credentials. For a real release, generate a keystore **outside** the repo,
> reference it via `keystore.properties`, and never commit it. Rotate any dev cert before publishing.

**Publish + sideload:**
1. Upload the per-ABI APKs to a **GitHub Release** (`vX.Y.Z`).
2. Update [dist/latest.json](dist/latest.json) with the new `versionCode`, `versionName`, per-flavor
   `apkUrl`, and `sha256` (`sha256sum app-...-release.apk`). **`sha256` is mandatory** — the in-app
   updater refuses to install an APK without a matching hash. Host the manifest (GitHub Pages or raw),
   and point `UpdateChecker.MANIFEST_URL` at it.
3. For first install, give users a **short-link** that 302-redirects to the arm64 APK so it's typeable
   in **Downloader**. After that, the in-app updater (**Menu → Check for updates**) handles upgrades:
   it compares `versionCode`, downloads, **verifies SHA-256**, and launches the installer (user-confirmed).

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

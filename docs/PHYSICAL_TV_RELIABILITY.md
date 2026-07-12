# Physical TV reliability suite

This suite exercises Comet through Android's real input dispatch on a physical Android TV or
Google TV. It is deliberately opt-in: every test skips unless `physicalTv=true` is passed and the
device reports the Leanback feature. It is not intended for an emulator.

Use a dedicated QA device/profile. The tests temporarily change browser settings and restore the
values they read, but browser activity, cookies, history, and restored tabs can still change.

## Build and connect

```powershell
./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb devices -l
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

If split output names differ, use the universal debug APK reported by `assembleDebug` and the test
APK under `app/build/outputs/apk/androidTest/debug/`.

The instrumentation component is:

```text
com.tdarby.comet.test/androidx.test.runner.AndroidJUnitRunner
```

## Deterministic five-button and hostile-page checks

These tests use a loopback HTTP origin on the TV, so they require no public site and do not depend
on live ad inventory.

```powershell
adb shell am instrument -w `
  -e physicalTv true `
  -e class com.tdarby.comet.reliability.PhysicalTvReliabilityTest#fiveButtonRemote_canEnterAndLeavePageWithoutClosingActivity `
  com.tdarby.comet.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument -w `
  -e physicalTv true `
  -e class com.tdarby.comet.reliability.PhysicalTvReliabilityTest#hostilePage_programmaticPopupAndCrossSiteRedirectStayBlocked `
  com.tdarby.comet.test/androidx.test.runner.AndroidJUnitRunner
```

The remote test injects Up, Down, Left, Right, Back, DPAD Center, and Enter, verifies deterministic
chrome/page transitions, and asserts that one Back from the page does not finish the activity. The
hostile fixture attempts both a programmatic popup and a cross-host top-level redirect and verifies
that neither target is requested and no tab is created.

The companion `rootBack_requiresFourPressesBeforeActivityCloses` case verifies the guarded exit
sequence through Android's real input dispatch: the first three root-level Back presses keep Comet
alive and the fourth closes it.

`enterOnUrlBar_opensTvKeyboardWithoutLeavingComet` focuses the address bar, sends DPAD Center, and
requires Android to report the TV IME as visible while Comet remains alive.

## Observe a real hostile streaming page

This test watches the top-level address for unexpected host changes. Include every legitimate
canonical host in `allowedHosts` as a comma-separated list. Strict redirects default to enabled.

```powershell
adb shell am instrument -w `
  -e physicalTv true `
  -e class com.tdarby.comet.reliability.PhysicalTvReliabilityTest#externalHostileUrl_observeAllowedTopLevelHosts `
  -e hostileUrl "https://example.test/watch" `
  -e allowedHosts "example.test,www.example.test" `
  -e hostileObserveSeconds 120 `
  -e strictRedirects true `
  com.tdarby.comet.test/androidx.test.runner.AndroidJUnitRunner
```

This is an observation guard, not proof that every ad network is blocked. Run it when the target
page is expected to be available, and preserve `adb logcat` for any failure.

For the currently tracked BuffSports page, use the same command with:

```text
-e hostileUrl "https://buffsports.io/mma-live/ufc-329-prelims-stream-2" -e allowedHosts "buffsports.io,www.buffsports.io"
```

## Video and fullscreen soak

`videoStartKeys` is a comma-separated remote macro applied after load. Supported names are `UP`,
`DOWN`, `LEFT`, `RIGHT`, `ENTER`, `CENTER`, and `BACK`. Tailor the macro to the chosen stable test
page. When `requireFullscreen=true`, the test waits up to 60 seconds for WebView custom fullscreen
and then asserts that it remains active throughout the soak.

```powershell
adb logcat -c
adb shell am instrument -w `
  -e physicalTv true `
  -e class com.tdarby.comet.reliability.PhysicalTvReliabilityTest#videoFullscreenSoak_activityAndFullscreenRemainStable `
  -e videoUrl "https://example.test/video-fixture" `
  -e videoStartKeys "BACK,DOWN,ENTER" `
  -e soakMinutes 120 `
  -e soakProbeSeconds 15 `
  -e requireFullscreen true `
  -e strictRedirects false `
  com.tdarby.comet.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -v threadtime > physical-tv-video-soak.log
```

Use a licensed/stable fixture under your control for repeatable playback assertions. A live sports
page is useful as an additional smoke test but is not deterministic enough to be the only soak.

## Trim-memory and renderer recovery

Keep Comet visible on a loaded page, then run progressively stronger pressure levels. After every
command, use the physical remote to confirm that focus still moves, Back returns to chrome, and the
page can reload. `send-trim-memory` is advisory; Android may reclaim different resources by build.

```powershell
adb shell dumpsys meminfo com.tdarby.comet > mem-before.txt
adb shell am send-trim-memory com.tdarby.comet RUNNING_LOW
adb shell am send-trim-memory com.tdarby.comet RUNNING_CRITICAL
adb shell am send-trim-memory com.tdarby.comet COMPLETE
adb shell dumpsys meminfo com.tdarby.comet > mem-after.txt
adb logcat -d -v threadtime | Select-String "CometNav|RenderProcessGone|chromium|crash"
```

Do not kill or clear the system WebView package on a shared TV. That affects every WebView app and
does not model normal app-level memory pressure. For renderer-crash validation, use a dedicated QA
device and capture the exact WebView provider/version first:

```powershell
adb shell dumpsys webviewupdate
adb shell dumpsys activity processes | Select-String "com.tdarby.comet|webview"
```

## Cold-start timing and restored-tab stress

Measure with Android's `-S -W` cold-start output. Repeat at least ten times and compare `TotalTime`
and `WaitTime`; the first run after install may include WebView/provider initialization and should
be reported separately.

```powershell
1..10 | ForEach-Object {
  adb shell am force-stop com.tdarby.comet
  adb shell am start -S -W -n com.tdarby.comet/.ui.BrowserActivity
} | Tee-Object cold-start.txt
```

For the many-tab case, create the desired tab set through Comet, leave the heaviest tab active,
press Home, and force-stop without clearing data. This preserves the supported session format and
avoids a synthetic fixture drifting from `TabsStore`. Confirm the case before timing:

```powershell
adb shell run-as com.tdarby.comet ls -l files/tabs.json
adb shell run-as com.tdarby.comet cat files/tabs.json
adb shell run-as com.tdarby.comet ls -lh files shared_prefs datastore
```

The packaged blocklist is an app asset and filter initialization is part of the normal cold start.
If testing a separately downloaded large filter cache, populate it through the app's supported
update path, record its byte size with `run-as`, and keep that exact cache constant across builds.
Never time a hand-edited production profile.

## Capture with every failure

```powershell
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell dumpsys webviewupdate
adb shell dumpsys meminfo com.tdarby.comet
adb logcat -d -v threadtime > physical-tv-failure.log
```

Record the remote model, TV resolution/refresh rate, WebView provider/version, URL or fixture
revision, instrumentation arguments, and whether direct-navigation mode was enabled.

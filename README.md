# iOS Glass Player (Android)

Android/Kotlin app that recreates the expanded iOS 26 lock-screen music player, with a native
home-screen widget. Target: Galaxy A56 5G, Android 16, One UI 8. No root, no Knox changes.

## Status (v0.2.2 - recovery of v0.2 after the lost v0.3)

`IosGlassPlayer-v0.3.zip` from the previous session was never delivered; this is v0.2 plus the
small fixes below. Nothing from the planned v0.3 (Kyant0 backdrop, `NOTICE`, glass components) is in
here: it is still pending.

Evidence level, kept separate on purpose:

| Part | Written | Compiled | Run on device |
|---|---|---|---|
| `media/*`, `widget/*` (Kotlin) | yes | **yes**, kotlinc 2.2.20 vs. the real API 36 `android.jar`* | no |
| `SessionSelectorTest`, `TrackInfoTest` (14 tests) | yes | yes* | no (ran on a minimal JUnit stand-in, not real JUnit) |
| `ui/*`, `MainActivity` (Compose) | yes | **no** (needs Compose artifacts) | no |
| Gradle / AGP build, APK | yes | **no** | no |

\* Harness only: `androidx.annotation.MainThread`, `NotificationManagerCompat`, `R` and
`MainActivity` were replaced by stubs. This does not replace the real Gradle build.
Dependency versions (BOM 2025.10.00, AGP 8.13.2 + Gradle >= 8.13 + JDK 17) were checked against the
official Android docs; activity-compose 1.11.0, core-ktx 1.17.0, lifecycle 2.9.4 and coroutines
1.10.2 were not.

Changes vs v0.2.0:
- `TrackInfo.positionAt`: no extrapolation when the app never stamps `lastPositionUpdateTime` (was
  able to pin the progress bar at the end of the track). Tested.
- `MediaSessionRepository`: a metadata change on any session now re-runs the selection, not only a
  re-publish (needed for the "abandon a session without metadata" rule). Needs a device check.
- CI: runs on a push to any branch (it was `main` only), uploads the full log + reports on failure,
  30 min timeout. Gradle wrapper (8.13) added so `gradlew.bat assembleDebug` works locally. `versionName 0.2.2`.
- New tests: `TrackInfoTest`.

Still pending (original plan): iOS 26 reference screenshots and pixel layout, Liquid Glass wiring
(Kyant0 backdrop, Apache-2.0, keep its headers + `NOTICE`), palette from artwork, artwork-by-URI,
app icon, widget polish, pt-BR strings.

## Build

CI (no local Android toolchain needed): push the CONTENTS of this folder (the `.github` folder must be at the
repository root) to GitHub; the `Build debug APK` workflow runs unit
tests and uploads `app-debug.apk` as an artifact.

Local: JDK 17 + Android SDK platform 36 (set `sdk.dir` in `local.properties` or `ANDROID_HOME`):

    gradlew.bat :app:testDebugUnitTest :app:assembleDebug   (Windows)
    ./gradlew :app:testDebugUnitTest :app:assembleDebug     (Linux/macOS)

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Setup on the phone

1. Install the APK, open the app, tap **Enable media access**.
2. Turn on **iOS Glass Player** in Settings > Notification access.
3. Play something in Spotify / BitChord / YouTube Music, then add the widget from the widget picker.

Notification access is the Android-sanctioned way to read other apps' media sessions; nothing is bypassed.

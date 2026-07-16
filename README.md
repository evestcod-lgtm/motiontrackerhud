# Motion Tracker HUD

An Android app that opens the front camera, detects motion in the live preview
entirely on-device, and lets you tap a moving object to lock onto and track it
with a tactical-style HUD overlay (bounding box, motion trail, status labels,
FPS/confidence telemetry).

Everything runs locally. No cloud APIs, no analytics, no ads, no network
calls of any kind.

## Features

- Front camera preview (CameraX) with pinch-to-zoom and +/- zoom buttons, live zoom ratio readout
- On-device motion detection: frame-differencing on a downscaled grayscale grid, with noise filtering (majority filter) and a sensitivity slider
- Tap-to-lock tracking: tap a moving object to lock on; the tracker follows it frame to frame, predicts through brief occlusion ("coasting"), and reports TARGET LOST if it can't recover
- Motion trail: fading dot/line trail of the last few seconds of target movement
- Tactical HUD look: dark background, green telemetry, corner brackets, crosshair, status chips
- FPS counter, tracking confidence readout
- Pause/resume analysis, reset lock, save a screenshot of the live HUD to the gallery
- Local event log (lock acquired/lost/reset, pause/resume, screenshot) written to app storage — no network involved

## Architecture

```
MainActivity            – wires everything together, handles gestures & buttons
permissions/             PermissionManager       – camera/mic request flow, rationale, settings redirect
camera/                   CameraController        – CameraX preview + analysis + zoom
vision/                   MotionAnalyzer          – frame diff -> threshold -> denoise -> blob extraction
                          ObjectTracker           – tap-to-lock, frame matching, loss recovery, trail
                          Blob / TrackedTarget    – data models
overlay/                  HudOverlayView          – custom View, draws crosshair/box/trail/labels
ui/                        HudController          – glues analyzer + tracker to the overlay/telemetry
                          FpsCounter
utils/                     ImageUtils              – YUV_420_888 -> grayscale grid conversion
                          EventLogger             – local plain-text event log
                          ScreenshotUtil          – saves the HUD view to the gallery
```

### How motion detection works

Each analyzed frame's Y (luma) plane is downsampled to a small 96x60 grid
(no color conversion needed — the Y plane is already grayscale). The grid is
diffed against the previous grid; cells above a sensitivity-controlled
threshold are marked as "motion". A 3x3 majority filter removes isolated
single-cell noise. An iterative flood fill groups adjacent motion cells into
blobs, and blobs below a minimum pixel count are discarded.

Analysis is throttled to ~12fps independent of the camera's preview
framerate, so the UI stays smooth while CPU/battery usage stays low.

### How tracking works

Tapping the screen finds the nearest blob to the tap location and locks onto
it. On each subsequent analyzed frame, the tracker predicts the target's
expected position from its last known velocity, then matches the nearest
compatible blob (distance-gated, size-gated). If no match is found the
target "coasts" using velocity extrapolation for up to ~1 second before being
marked LOST. Every accepted position update appends a point to a trail buffer
(capped in both count and age) that the overlay renders as a fading dot/line
trail.

## Requirements

- Android Studio Koala or newer (or just a JDK 17 + Android SDK for CLI builds)
- Android SDK Platform 34, Build-Tools matching AGP 8.5.x
- A device or emulator running Android 7.0 (API 24) or newer, with a front camera

No OpenCV, no native code, no paid APIs. Pure Kotlin + CameraX.

## Building locally

```bash
git clone <your-repo-url>
cd MotionTrackerHUD

# Debug build (installable directly, no signing needed)
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Release build (unsigned unless you configure signing, see below)
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release-unsigned.apk
```

Install directly to a connected device/emulator:

```bash
./gradlew installDebug
```

> This repository does not include the Gradle wrapper JAR binary (`gradle/wrapper/gradle-wrapper.jar`),
> since it's a binary file. If `./gradlew` fails locally with a missing wrapper jar, run:
> `gradle wrapper --gradle-version 8.7` once (with a local Gradle install) to generate it,
> or open the project in Android Studio, which will regenerate it automatically on first sync.
> The GitHub Actions workflow does not depend on the wrapper jar — it uses `gradle/actions/setup-gradle`
> to provision Gradle directly.

## Building via GitHub Actions (recommended — no local Android SDK needed)

1. Push this repository to GitHub.
2. The workflow at `.github/workflows/build-apk.yml` runs automatically on every push to `main`.
3. Go to the **Actions** tab → select the latest run → scroll to **Artifacts**.
4. Download `motiontrackerhud-debug-apk` — this is a fully signed, installable APK (signed with the Android debug key). This is the one to sideload for personal/self-hosted use.
5. `motiontrackerhud-release-apk` is also built. If you haven't configured release signing (see below), it will be **unsigned** and cannot be installed directly until signed — treat it as a build-verification artifact, not something to install.

### Installing the debug APK on your phone

```bash
adb install app-debug.apk
```

Or transfer the APK to the device and open it directly (you'll need to allow
"install unknown apps" for whichever app you use to open it — Files, browser, etc).

### Optional: signing the release build

If you want a properly signed release APK (not required for personal use —
the debug APK is fine for that), do this once:

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias motiontracker -keyalg RSA -keysize 2048 -validity 10000
```

Then in your GitHub repo, go to **Settings → Secrets and variables → Actions**
and add these repository secrets:

| Secret name                  | Value                                      |
|-------------------------------|---------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`     | Output of `base64 -w0 release.keystore`     |
| `RELEASE_KEYSTORE_PASSWORD`   | The keystore password you set               |
| `RELEASE_KEY_ALIAS`           | `motiontracker` (or whatever alias you used)|
| `RELEASE_KEY_PASSWORD`        | The key password you set                    |

Once these secrets exist, the next workflow run will produce a signed,
installable `motiontrackerhud-release-apk` artifact automatically — no
workflow file changes needed.

**Keep `release.keystore` somewhere safe outside the repo.** If you lose it,
you won't be able to publish updates under the same app signature.

## Permissions

- `CAMERA` — required, used for the live preview and motion analysis.
- `RECORD_AUDIO` — requested up front for future audio-event features; the
  app does not currently record or process audio, and functions normally if
  this permission is later revoked.

If permissions are denied, the app shows a clear in-app explanation and a
button to open system Settings — it never crashes without permissions.

## Notes on scope

This app is built for tracking motion in your own recordings — sports,
wildlife, pets, kids at a playground, monitoring your own property, and
similar personal/creative use cases. It intentionally avoids
surveillance-style framing (e.g. no facial recognition, no identity
matching, no cloud upload) and should be used in ways that respect the
privacy of anyone who might appear in frame.

# virtual-stomp-box

The Stomp Box Android app, plus the WitMotion WT901 BLE sensor it uses as a foot trigger.
The sensor straps to the shoe or the leg.

## Install on the phone

Every push builds a debug APK and publishes it as a pre-release named `apk-<branch>`, for
example `apk-main`.

1. On the Pixel, open the repo's **Releases** page on github.com, signed in. The repo is private.
2. Tap `stompbox-<run>-<date>-<commit>.apk` under the newest `apk-…` release.
   The run number and commit also appear at the top of the app's status, so test results can be
   matched to a build. The same file is also there as `stompbox-latest.apk`, so this link always
   gets the newest build of a branch: `releases/download/apk-<branch>/stompbox-latest.apk`.
3. Tap **Install**. The first time, allow Chrome to "install unknown apps".
4. Open **Stomp Box** and tap **Connect sensor**. Allow Bluetooth when asked, with the WT901
   switched on.

Later builds install over the old one and keep your tuning and calibration.

To get data off the phone, use **Tune & debug**:

- **Copy status** puts the link figures, latency and settings on the clipboard; paste them into a message.
- **Record**, then **Stop & save**, writes a CSV to `Download/StompBox/` (open the Files app, or plug in a
  USB cable and pick File transfer) and opens the share sheet (Drive, Gmail, Nearby Share…).
- **Start latency test** measures stomp → sound live with the mic. Put the phone on the floor and use its speaker.

## What's here

- **[docs/wt901-design.md](docs/wt901-design.md)**: the design. It covers how orientation and
  acceleration are used, the shoe and leg modes, calibration, the latency/jitter trade-off,
  and debugging and tuning.
- **[docs/research-journal.md](docs/research-journal.md)**: log of hardware tests, results and
  what changed because of them.
- **[docs/code-review-handoff.md](docs/code-review-handoff.md)**: where to start a code review.
- **[app](app)**: the Android app.
  - The home screen shows and plays each stomp.
  - The **Tune & debug** screen has a live plot, sliders, calibration, and recording with sharing.
  - It also contains the WT901 BLE source and a small low-latency drum synth.
- **[core](core)**: plain-Kotlin code with 37 unit tests (`./gradlew :core:test`).
  - WT901 protocol, sample clock, stomp detector, recording format.
  - The desktop replay tool (`./gradlew :core:run --args="/path/to/log.csv"`).
- **[tools](tools)**:
  - latency model;
  - synthetic log generator;
  - plot script for replay traces.
- **[.github/workflows/build.yml](.github/workflows/build.yml)**: runs the tests, builds the APK
  and publishes the release.

Building locally: `./gradlew :core:test` works anywhere. The app module is only included when an
Android SDK is found, through `ANDROID_HOME` or `local.properties`. Open the folder in Android
Studio, or run `./gradlew :app:installDebug` with the phone connected.

## Earlier work (superseded)

- [docs/leg-sensor-research.md](docs/leg-sensor-research.md) compared sensors and recommended a
  XIAO nRF52840 board with custom firmware ([firmware/xiao-tap-midi](firmware/xiao-tap-midi)).
- The related Android MIDI sources are in [legacy/xiao-android](legacy/xiao-android), which isn't
  built. Their notes are in [docs/android-integration-old.md](docs/android-integration-old.md).

No latency figure here has been measured yet; they all come from the model. A recording made
with the tuning screen's "with mic" option measures the real numbers.

# Code review handoff

**PR #1** (`ccr-485f9f1f-a1qz3d` → `main`, draft): an Android test app that turns a WitMotion
WT901 BLE sensor on the shoe into a stomp trigger that plays a drum. `main` is an empty initial
commit, so the whole PR is new code (~60 files).

## Read first

- [README.md](../README.md): layout, how to build, how the APK is published.
- [research-journal.md](research-journal.md): hardware tests and why things changed. Its
  newest entries cover the code that changed most recently.
- [wt901-design.md](wt901-design.md): the design and the measured latency budget.

## Build and test

- `./gradlew :core:test` runs the plain-Kotlin unit tests anywhere.
- `:app` needs an Android SDK. It isn't in the cloud container, so **the app module has only
  ever been compiled by CI** (`.github/workflows/build.yml`). Push and check the run.
- Hardware results come from the tester (Pixel 8a, Android 17), pasted from **Copy status**.

## Where to look hardest

Recently rewritten, or concurrency-sensitive. Each one has been compiled by CI but not
exercised on hardware yet:

1. **`app/.../input/WitBleSource.kt`**: BLE connection, a single GATT write queue
   (`enqueue`/`pump`, with a 1 s timeout), the reconnect path (it must cancel every timer and
   queued write), the hidden `onConnectionUpdated` callback, and the stall counters. Threads:
   GATT binder callbacks, plus the `wit-ble` handler thread.
2. **`app/.../audio/DrumSynth.kt`**: AudioTrack render thread; the track is rebuilt when the
   output device changes (USB / wired preferred); buffer grows on underrun. Check the rebuild
   race between `pickDevice` (main thread) and the render loop, and the stereo write path.
3. **`app/.../stomp/StompEngine.kt`**: the latency path. `onSample` runs on the GATT thread and
   must not block. Recording now goes through a single-thread executor; check shutdown/close
   ordering in `stopRecording`.
4. **`core/.../latency/LatencyMeter.kt`**: pairs mic onsets with stomps. An earlier bug paired a
   missed impact with the previous drum (see the journal). The `floorNanos` fix and its tests are
   worth a second look.
5. **`app/.../debug/StompTuningActivity.kt`** and **`MainActivity.kt`**: UI refresh, the pacer,
   the `UiStallWatch` start/stop in onResume/onPause.

Lower priority: `core/.../stomp/` (detector, pipeline, SampleClock) is unit-tested and stable.
`legacy/` and `firmware/` are earlier work and aren't built.

## Known open issues

- The tester reports intermittent stalls. Fixes based on reading the code are in; the root cause
  isn't confirmed. The `stalls:` status line should show whether it's BLE gaps, the UI or audio.
- Audio output is ~100 ms on the Pixel speaker. USB/amp output isn't measured yet.
- `LinkStats` is written on the GATT thread and read on the UI thread without a lock. The worst
  case is a torn snapshot in the debug text.

## House rules

- Develop on `ccr-485f9f1f-a1qz3d` only. Every push builds and publishes an APK
  (`stompbox-latest.apk` on the `apk-ccr-485f9f1f-a1qz3d` release).
- Log every user-visible change or finding in `docs/research-journal.md`.
- Don't merge or approve the PR; the owner decides.

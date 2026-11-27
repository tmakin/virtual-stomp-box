> **Superseded:** these notes are for the earlier XIAO/MIDI sensor and the bitxo app. The current app is in `app/` on the WT901 branch (PR #1); paths below are out of date.

# Android side: foot-sensor input and latency probe

Code for the Stomp Box app (`tmakin/bitxo`, `stompbox/`) to read the WT901 BLE stomp sensor,
tune it, and measure stomp-to-app latency on the Pixel 8. The design is in
`docs/wt901-design.md` (PR #1).

```
src/main/kotlin/...   platform-independent, unit-tested here (gradle test, 37 tests)
  wit/WitProtocol.kt             WT901 UUIDs, commands, 0x61/0x71 frame parser
  wit/SampleClock.kt             arrival times → estimated sample times (removes BLE jitter)
  stomp/Orientation.kt           gravity from roll/pitch, two-pose calibration, tilt
  stomp/StompConfig.kt           every tunable, shoe/leg defaults, key=value form
  stomp/StompDetector.kt         impact detection, velocity, heel/toe zone
  stomp/StompTiming.kt           IMMEDIATE / STEADY fire time
  stomp/StompPipeline.kt         clock + link stats + detector + timing (phone and replay)
  debug/StompLog.kt              recording format (write/read)
  debug/LinkStats.kt             connection interval, frames per event, lag
  debug/Replay.kt                desktop replay / sweep (gradle run)
  midi/MidiTapParser.kt          MIDI bytes → note-on taps (running status, real-time, SysEx)
  midi/BleMidiPacketParser.kt    raw BLE-MIDI packets → taps + sender timestamp
  latency/OnsetDetector.kt       impact onsets in mic audio
  latency/TapPairer.kt           pairs mic impacts with sensor events
  latency/LatencyStats.kt        median, p95, spread
android-only/...      needs the Android SDK; not compiled here
  input/WitBleSource.kt          WT901 over GATT: 200 Hz, 188 Hz bandwidth, battery
  stomp/StompEngine.kt           source → pipeline → sound; calibration, recording, settings
  debug/StompTuningActivity.kt   live plot, sliders, calibrate, record & share
  debug/StompPlotView.kt         scrolling trace
  input/MidiTapSource.kt         android.media.midi: BLE MIDI or USB MIDI → TapEvent
  input/GattMidiTapSource.kt     same sensor over raw GATT, to measure the MIDI service's cost
  latency/LatencyProbe.kt        mic-referenced latency test harness
```

`gradle test` runs the unit tests on a plain JVM. The `android-only` files have **not been
compiled**, because there is no Android SDK in the session that wrote them.

## Adding to the app

For the WT901, follow "Adding it to the app" in the design doc. The rest of this section is
about the earlier MIDI sensor.

Copy both trees into `stompbox/app/src/main/java/uk/neatlines/stompbox/`. The packages already
match.

Manifest:

```xml
<uses-feature android:name="android.software.midi" android:required="false" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.RECORD_AUDIO" /> <!-- latency probe only -->
```

`MainActivity`: add a third mode next to `CAMERA` and `ACCEL`:

```kotlin
private enum class Mode { CAMERA, ACCEL, MIDI }
private lateinit var midiSource: MidiTapSource

// onCreate
midiSource = MidiTapSource(this, ::onTap)

// startInput()
Mode.MIDI -> {
    // Request BLUETOOTH_SCAN + BLUETOOTH_CONNECT first on API 31+.
    midiSource.start()
}

// stopInput()
midiSource.stop()

// updateStatus()
Mode.MIDI -> sb.append(fmt("midi    %s  link %.1f ms\n", midiSource.status, midiSource.linkLatencyMs))
```

Add a "Foot sensor" radio button to `activity_main.xml` next to the other modes.

## Running the latency test

```kotlin
val probe = LatencyProbe()
midiSource.onArrival = probe::onEvent     // or gattSource.onArrival
probe.start()
// tap the sensor on the floor next to the phone 60 times, ~0.5 s apart
val r = probe.stop()
Log.i("latency", "${r.stats?.format()} missed=${r.missed} false=${r.falseTriggers}")
probe.writeCsv(File(getExternalFilesDir(null), "latency-ble-midi.csv"))
```

For the phone-accelerometer baseline, wrap `AccelTapSource`'s `onTap` so it also calls
`probe.onEvent(System.nanoTime())`.

Keep the phone muted, or use headphones, so the bass note is not heard as a second impact. The
detector ignores sounds for 150 ms after each hit, which covers this in most cases. Tap at least
0.3 s apart.

The probe reports the delay from impact to the event **arriving in the app**, which is what the
handoff's ≤ 10 ms target is about. Audio output latency (~10 ms) comes on top of that.

# WT901 BLE stomp sensor: design

Nov 2026 · replaces the XIAO plan in [leg-sensor-research.md](leg-sensor-research.md)

## Summary

The WitMotion WT901 BLE (BWT901BLE5.0) streams raw motion data at up to 200 Hz:
acceleration, gyro and fused roll/pitch/yaw. It does no tap detection itself, so **stomp
detection runs on the phone**. It uses the acceleration to find the impact and the orientation
for two things:

1. Measuring acceleration along gravity, whatever angle the sensor sits at on the strap.
2. Telling a heel stomp from a toe tap, from the tilt of the foot just before the impact.

There are two modes. **Shoe** mode (the default) puts the sensor on top of the shoe. **Leg**
mode puts it on the shin. Each mode has its own defaults and its own saved tuning. A two-pose
calibration ("stand still", then "toes up") works out how the sensor is mounted, so it does not
matter which way round it is strapped.

For debugging and tuning there are four tools:

- A live tuning screen on the phone: plot, sliders and link statistics.
- `adb logcat -s Stomp` for live output without the UI.
- A **recording export**: raw samples, optional microphone impacts, and the stomps that were played.
- A **desktop replay tool** that re-runs the same detector code on a recording with different
  settings, including threshold sweeps.

### The trade-off to know before playing

The WT901 frame has no timestamp and no sequence number. BLE delivers frames in bursts, one
burst per connection event, and Android grants at best an 11.25 ms interval. So you have to
choose between low delay and low jitter. The figures below are modeled, not measured:

| Timing mode | Impact → play decision, median | p95 | Jitter (p5–p95) | Meets ±4 ms? |
|---|---|---|---|---|
| IMMEDIATE (play when the frame arrives), 11.25 ms interval | 13.8 ms | 20.0 ms | ±6.0 ms | no |
| IMMEDIATE, 15 ms interval | 15.7 ms | 23.4 ms | ±7.6 ms | no |
| STEADY, 20 ms delay, 11.25 ms interval | 26.0 ms | 28.3 ms | ±2.3 ms | yes |
| STEADY, 25 ms delay, 15 ms interval | 31.0 ms | 33.3 ms | ±2.3 ms | yes |

The figures come from `python3 tools/ble_latency_model.py`. The WT901's internal delay (filter,
fusion, UART to the BLE chip) is assumed to be about 3.5 ms. Audio output latency comes on top.

**STEADY** timing plays each stomp at a fixed delay after the *estimated impact time*.
`SampleClock` rebuilds each sample's measurement time from the steady 200 Hz rate, so the BLE
jitter drops out. What remains is the 5 ms sample period (±2.3 ms). The cost is a constant
delay of about 20–25 ms. **IMMEDIATE** is about 10 ms quicker but jitters by a whole connection
interval.

**Neither mode meets the original ≤10 ms target with this sensor.** Both modes are a toggle on
the tuning screen, so try both while playing. The replay tool prints both figures from a single
recording made with the microphone on.

## Sensor facts

These come from WitMotion's SDKs ([WitBluetooth_BWT901BLE5_0](https://github.com/WITMOTION/WitBluetooth_BWT901BLE5_0):
Android `DeviceModel.java`, Unity `WT901BLE.cs`, Python `device_model.py`).

| | |
|---|---|
| Service / notify / write UUIDs | `FFE5` / `FFE4` / `FFE9` (16-bit base UUIDs) |
| Data frame | 20 bytes: `55 61` + 9 × int16 LE: acc ±16 g, gyro ±2000 °/s, angle ±180° (X = roll, Y = pitch, Z = yaw). No time or sequence field. |
| Register read | write `FF AA 27 RR 00`; reply `55 71 RR 00` + 8 registers from RR |
| Unlock / save | `FF AA 69 88 B5` / `FF AA 00 00 00` |
| Return rate (reg 0x03) | 1, 5, 10, 50, 100, 200 Hz → 0x03, 0x05, 0x06, 0x08, 0x09, **0x0B**. Default is 10 Hz, far too slow; the app sets 200 Hz. |
| Bandwidth (reg 0x1F) | SDK mapping: 188 → 0x02 … 5 → 0x07. The app sets **188 Hz**, so the impact spike isn't smeared out. One public table is offset by one, so the app reads the value back and shows it. |
| Battery (reg 0x64) | volts × 100 |

## Architecture

```
WT901 ──BLE notify (FFE4)──▶ WitBleSource        takes System.nanoTime() first, on the GATT thread
                              │  WitPacketParser   bytes → WitSample (g, °/s, °)
                              ▼
                         StompPipeline            same code on the phone and in the replay tool
                           SampleClock            arrival times → estimated sample times
                           LinkStats              frames per event, connection interval, lag
                           StompDetector          orientation + accel → StompEvent(zone, velocity)
                           StompTiming            IMMEDIATE / STEADY fire time
                              ▼
                         StompEngine ──▶ StompOutput.play(note, velocity)   (app's sound engine)
                              ├─▶ StompLogWriter  recording (CSV) ──share──▶ laptop: Replay, plot
                              └─▶ StompTuningActivity (live plot, sliders) and logcat "Stomp"
```

| File | Where | Tested |
|---|---|---|
| `wit/WitProtocol.kt`: UUIDs, commands, frame parser | `core` | unit tests |
| `wit/SampleClock.kt` | `core` | unit tests (jitter, dropped frames, crystal error) |
| `stomp/Orientation.kt`: gravity from angles, calibration, tilt | `core` | unit tests |
| `stomp/StompConfig.kt`, `StompDetector.kt`, `StompTiming.kt`, `StompPipeline.kt` | `core` | unit tests on simulated stomps |
| `debug/StompLog.kt`, `LinkStats.kt`, `Replay.kt` | `core` | round-trip test; replay run on a synthetic log |
| `input/WitBleSource.kt`, `stomp/StompEngine.kt`, `debug/StompTuningActivity.kt`, `debug/StompPlotView.kt` | `app` | compiled by CI only; not run on a phone yet |

## How the detector uses the data

At every sample (200 Hz):

1. **Gravity direction** comes from roll and pitch, taken 2 samples back (`orientationLagSamples`),
   because the impact itself can disturb the fused angles. The sign convention of WitMotion's
   angles isn't documented, so calibration tries all four sign combinations and keeps the one
   that matches the measured gravity. If none matches within 10°, the detector ignores the angles
   and uses the gravity vector measured at rest.
2. **Features**, both computed every sample:
   - `VERTICAL` = |high-passed (acc · gravity − 1 g)|. This is the impact along the leg, with slow
     leg motion removed (`highPassHz`).
   - `JERK` = |acc[n] − acc[n−1]|. It ignores orientation and picks up the weaker, rounder impact
     felt on the shin.
3. **Threshold** = max(`minThresholdG`, `noiseFactor` × running noise level). The noise level
   adapts over about 1 s while the detector is armed.
4. **Trigger** on the first sample above the threshold. It then stays disarmed for `deadTimeMs`,
   and until the feature falls below `releaseRatio` × the threshold. An optional `gyroGateDps`
   only accepts impacts that follow real leg rotation, which rejects bumps to a standing leg.
5. **Velocity** (1–127) comes from how far the peak exceeds the threshold, scaled to
   `velocityFullScaleG` with a `velocityCurve`. `peakWaitSamples` waits 0–3 samples for the true
   peak, which gives better dynamics at 5 ms per sample.
6. **Zone** comes from the tilt at impact, measured about the calibrated axis. Toes up by at least
   `heelTiltDeg` is **HEEL**; toes down by at least `toeTiltDeg` is **TOE**; anything else is
   FLAT. Each zone has its own note (default: heel and flat = 36 kick, toe = 38 snare).

## Two modes

| | Shoe (default) | Leg |
|---|---|---|
| Mount | Top of the shoe, on the laces, under a strap or lace clip. Keep it rigid: any wobble blurs the spike. | Front of the shin just above the ankle, with an elastic strap. Tight but comfortable. |
| Feature | `VERTICAL` | `JERK` |
| Min threshold | 1.2 g | 0.8 g (the impact is weaker after passing through the ankle) |
| Velocity full scale | 6 g | 3 g |
| Heel/toe zones | on | off (the shin barely tilts on a toe tap; turn it on if calibration shows it works) |
| Calibration pose 2 | toes up, heel on the floor | swing the lower leg forward |

The mode is guessed from calibration: flat on a shoe, gravity lies along the sensor's Z axis;
on edge on a shin, it doesn't. The tuning screen shows this guess. You choose the mode, and each
mode keeps its own tuning.

**Calibration takes about 5 s, once per strap-on.** Press "stand still" and hold for 1 s, then
"toes up" and hold for 1 s. The result is saved.

## Debugging and tuning

### On the phone: `StompTuningActivity`

- **Status:**
  - connection and battery;
  - rate and bandwidth register read-back, which should be 0x0B and 0x02;
  - the measured link: sample rate, frames per connection event, the connection interval (gap
    between bursts) and the arrival lag;
  - calibration quality.
  - a warning when fewer frames arrive than the sensor is set to send (frames queuing in the sensor).
- **Latency test:** listens with the mic and splits each stomp's delay into detect (impact →
  detected), output (play → drum heard) and total (impact → drum heard). See "Latency" below.
- **Live plot of the last 4 s:** the feature (white), the threshold (red dashed) and a dot on each stomp.
- **Last stomp:** velocity and peak.
- **Toggles:** mode, feature, timing (IMMEDIATE / STEADY) and sensor rate (200 / 100 / 50 Hz).
  STEADY also shows a count of frames that arrived too late for the delay; raise "Steady delay" if
  it climbs.
- **Heel/toe zones are off** for the first iteration: one stomp, one sound. The code is still in
  the detector (`zonesEnabled`), with no UI.
- **Sliders** for every setting. Changes apply immediately and are saved for the current mode.
- **Record:** writes `stomp-YYYYMMDD-HHMMSS.csv`. On stop it copies the file to
  `Download/StompBox/` on the phone and opens the share sheet. Tick "with mic" to also record impact
  sounds, which gives latency figures in the replay tool.
- **Copy status:** copies the status, latency summary and config to the clipboard, to paste into a
  message.
- **Without the UI:** run `adb logcat -s Stomp` for one line per stomp.

### On the laptop: replay

```bash
# use absolute paths: the tool runs from core/
./gradlew :core:run --args="$PWD/stomp-log.csv"                                 # reproduce the live run
./gradlew :core:run --args="$PWD/stomp-log.csv feature=JERK minThresholdG=0.9"  # try other settings
./gradlew :core:run --args="$PWD/stomp-log.csv --sweep minThresholdG=0.6:2.0:0.2"
./gradlew :core:run --args="$PWD/stomp-log.csv --frames $PWD/frames.csv" && python3 tools/plot_stomp_frames.py frames.csv
```

The tool reports:

- link statistics and the stomp count by zone;
- the velocity range;
- if the log has microphone onsets: latency and jitter for both timing modes, missed stomps and
  false triggers.

The sweep prints one row per value. `tools/synthetic_stomp_log.py` writes a fake log so you can
try all this before the sensor is strapped on. Here is the output on that synthetic log:

```
link: rate 200.1 Hz, 2.29 frames/event, interval median 11.20 ms p95 11.41, lag median 5.0 p95 10.1 max 28.6 ms
stomps: 40  zones {FLAT=14, TOE=13, HEEL=13}
IMMEDIATE impact→fire: median 8.1 ms  p95 16.8 ms  spread(p5–p95) 13.8 ms (±6.9)  missed=0 false=0
STEADY (25.0 ms) impact→fire: median 28.0 ms  p95 28.2 ms  spread 0.3 ms (±0.1)  late=0
```

The synthetic log has no sensor-internal delay and no sample-phase error, so these figures are
better than the model's.

### Suggested tuning session

1. Strap the sensor on, connect, and check that the status shows rate reg 0x0B, bandwidth 0x02 and
   about 200 Hz. If frames per event × (1000 / interval in ms) comes out well below 200, the link
   can't carry 200 Hz; try 100 Hz.
2. Calibrate. The angle error should be under about 5°.
3. Stand, shift your weight, and walk on the spot. The white trace should stay under the red line.
4. Do 20 soft and 20 hard stomps in each zone. Every stomp should get exactly one dot of the
   right colour.
5. Record a minute of real playing with the mic on, share it, and run the sweep on the laptop.
   Copy the winning values into the sliders.
6. Play with IMMEDIATE and then STEADY, and keep whichever feels better.

## The test app

The `app` module is a fresh, minimal Stomp Box app built around this sensor. The README explains
how to install it from the CI release.

- `StompApp` owns the `StompEngine` and a `DrumSynth`: kick on note 36, snare on 38, click for
  anything else. Each synth voice is generated in code and played on a low-latency `AudioTrack`
  with a two-burst buffer. The home screen shows the reported buffer size in ms.
- The home screen has Connect, a large flash per stomp with the velocity, Sound on/off, and
  **Tune & debug**. Both screens pad for the system bars (Android 15 draws edge to edge).
- The engine plays IMMEDIATE stomps on the GATT thread. STEADY stomps go through a high-priority
  scheduler thread that wakes at the target time. That is usually well under 1 ms off target,
  but it hasn't been measured. The synth starts a voice at its next burst, which adds up to one
  burst (about 2–4 ms) of jitter. Starting voices at a given frame inside the synth would remove
  that.
- Settings, calibration and the mode are kept in SharedPreferences. Recordings are saved to
  `Android/data/uk.neatlines.stompbox/files/stomp-logs/`, copied to `Download/StompBox/` and shared
  through a `FileProvider`.
- Builds are signed with `app/debug.keystore`, which is committed so every CI build installs over
  the previous one. It's for test builds only.

## Latency

The first phone test heard about 400 ms from stomp to sound, which is far above the ~15–30 ms
budget worked out above. The latency test splits it:

| Part | Expected | If it is large |
|---|---|---|
| detect: impact → detected | 10–25 ms | The sensor sends more frames than the link carries, so they queue in its UART-to-BLE buffer (the status line warns when the received rate is below the set rate). Lower the sensor rate. Also check the connection interval in the status line: the sensor may have negotiated a long one. |
| output: play → heard | 20–40 ms on a Pixel speaker | Bluetooth headphones or speakers add 150–300 ms. Use the phone speaker or a wired output. |
| total | sum of the two | Total is exact (both ends are mic times). Detect and output each carry the mic's own input delay, with opposite signs. |

Other levers once the large term is gone: STEADY → IMMEDIATE timing; peak wait 0; Feature
JERK reacts a sample earlier than VERTICAL; 2M PHY / connection priority (already requested).

## Risks and open questions

- **Can the link carry 200 Hz?** Each frame is a 20-byte notification, so 2–3 are needed per
  11.25 ms connection event. The WitMotion parser expects 20-byte frames, and nothing documents how
  many the module sends per event. The status line measures this.
- **Sensor-internal delay** is assumed (about 3.5 ms) and cannot be read from the data. The
  microphone recording measures the whole chain.
- **The bandwidth register mapping** may be off by one; the read-back shows which.
- **Walking triggers notes.** The detector can't tell a stomp from a step. A mute switch in the
  app, or `gyroGateDps`, are the mitigations to try.
- **Clipping:** a hard shoe stomp can exceed 16 g. Detection still works, but velocity saturates;
  raise `velocityFullScaleG` towards 16.
- **Sleep:** if the sensor powers down after a period of inactivity, the source reconnects
  automatically, but the first stomp after waking will be lost.
- **WitMotion docs:** the gitbook and robotshop PDFs were blocked from this session, so everything
  above comes from WitMotion's SDK source on GitHub.

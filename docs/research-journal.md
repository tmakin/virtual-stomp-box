# Research journal: WT901 stomp sensor

Tests on the real hardware, what they showed and what changed. The summary below is the current
state; the log under it is newest at the bottom. Design background is in
[wt901-design.md](wt901-design.md); the next sensor is discussed in issue #4.

**Setup:** WitMotion BWT901BLE5.0 (advertises as `WT901BLE68`) on the shoe, Google Pixel 8a,
Android 17, test app from the `apk-ccr-485f9f1f-a1qz3d` release.

## Current state

Latency, measured with the live latency test (mic hears the impact and the drum), 200 Hz, single
stomps 2 s apart, phone speaker (build #13):

| Part | Measured | Made of |
|---|---|---|
| detect: impact → detected | **86 ms** | ~55 ms until the impact shows in the data, then ~30 ms (about one more notification) until the detector fires |
| output: play → heard | **100 ms** | AudioTrack reports 79 ms with the fast path granted; the rest is the Pixel speaker path |
| total | **188 ms** | exact: both ends are mic times |

What we know about the sensor and link:

- **The sensor packs frames on its own ~44 ms timer:** about 8 frames per notification at 200 Hz
  (about 5 at 100 Hz, 3 at 50 Hz), one notification every ~44 ms. This sets the ~55 ms and can't be
  changed from the phone. Other users see the same (~40 ms, 4 frames at 100 Hz). WitMotion's SDK
  and protocol notes have no register for it: only rate (0x03), filter bandwidth (0x1F), output
  content (0x96), algorithm (0x24) and orientation (0x23). Below 50 Hz frames come one per
  notification, but no faster than 1 / rate (50 ms at 20 Hz), so that doesn't help either.
- **The link is not the limit:** every frame arrives; Android reports a 15 ms connection interval
  with peripheral latency 1. Re-requesting connection priority changes nothing.
- **Android 14+ negotiates a large MTU on connect**, so the app can't keep the 23-byte default
  (the frame packing stays the same either way).
- **Register-read round trip ("ping"):** about 80–110 ms median.
- **The WitMotion app** shows no perceptible lag on its live graph, consistent with the above.

## 2026-11-23: First install

- The app installs, connects and plays a sound on each stomp.
- **Feedback:** bottom buttons under the navigation bar; keep to a single stomp (no heel / toe);
  no obvious way to export a recording.
- **Changes:**
  - Both screens pad for the system bars (edge to edge on Android 15+).
  - Heel/toe zones off (`zonesEnabled = false`), their UI removed.
  - **Live latency test** (`LatencyMeter`): splits each stomp into *detect*, *output* and *total*.
  - Sensor-rate setting (200 / 100 / 50 Hz), with a warning when the link drops frames.
  - Recordings copied to `Download/StompBox/`; **Copy status** and **Share last recording**.
  - The kick got a click attack, so it's audible on a phone speaker and the mic catches it.

## 2026-11-24: Link and audio measurements

Measured across 200, 100 and 50 Hz, with several link settings (connection priority, MTU, PHY):

- All frames arrive at every rate. The sensor packs frames into one notification every ~44 ms,
  whatever the connection interval, MTU or PHY.
- The audio fast path is granted, but output is still ~100 ms by mic (74–79 ms by AudioTrack
  timestamp): the Pixel speaker path adds the rest.
- **Changes:** the status shows the connection parameters the stack reports (via the hidden
  `onConnectionUpdated` callback), frames per notification, the ping, the measured audio output
  latency and whether the fast path was granted. The latency test also reports *first jolt*: when
  the impact first shows in the sensor data.

## 2026-11-24: Latency meter fix

- **Bug:** when the mic missed an impact, `LatencyMeter` paired the stomp with the previous stomp's
  drum, so *detect* read as the stomp period minus the output delay (~400 ms at the tester's ~2
  stomps a second). All latency figures before this fix were wrong and have been dropped.
- **Fix:** an impact must come after the previous stomp's drum (plus 80 ms of ringing). A stomp
  with no impact heard is counted, not scored, and the summary shows the count. Unit tests cover a
  500 ms rhythm with and without the impacts heard.
- **Silent pacer:** the tuning screen flashes **STOMP** every 2 s during the latency test (visual
  only, so the mic isn't confused).

## 2026-11-24: Latency test (build #13 `276c684`)

```
BLE (MTU 247, 2M PHY, MTU 247): interval 15.00 ms, peripheral latency 1, 8.00 frames/notification
ping (register read round trip): median 112 ms, min 41, max 185 (n=18)
rate 199.4 Hz, 8.85 frames/event, interval median 44.72 ms p95 77.90, lag median 40.4 p95 81.7 max 108.6 ms
audio: buffer 10.0 ms, output latency 79 ms, fast path yes
latency: median of 11: detect 86 ms · output 100 ms · total 188 ms
impact in sensor data: first jolt at 55 ms, max 2.59 g
```

- Every stomp was paired (none unheard). The breakdown is in **Current state** above.
- The ~30 ms from first jolt to detection is about one notification: the first sample over 0.5 g is
  in one burst, and the 1.2 g threshold is crossed in the next. A detector lever.

## 2026-11-24: Cleanup, USB audio, stall hunt

Feedback: remove the experiment code; optimise the audio output for USB-C (next test is through
an amp); the app stalls intermittently; cap the threshold slider at 2.0 g; default to the
lowest-latency settings.

- **Removed** the experiment code (sensor-queue flush, link-mode setting, 10 Hz rate, repeated
  connection-priority requests). The ping stays, every 5 s.
- **Possible causes of stalls, found in the code and fixed:**
  - The automatic flush stopped the data for 2.5 s after every connect, including reconnects.
  - **Timers piled up on reconnect:** the battery poll and ping were never cancelled when the link
    dropped.
  - **Colliding BLE writes:** Android takes one GATT write at a time and refuses the rest. All
    writes now go through one queue, each waiting for the previous one's reply.
  - **Recording wrote to disk on the Bluetooth callback thread.** It now runs on its own thread.
- **Stall diagnostics:** a `stalls:` status line with BLE gaps over 250 ms (and the longest),
  reconnects, failed writes and UI freezes over 200 ms. The `audio:` line counts underruns.
- **USB / wired audio:** the drum synth uses a USB interface, USB-C headphones or a wired / line
  output as soon as one is plugged in. 48 kHz stereo; the buffer starts at two bursts and grows by
  one burst on each underrun. The `audio:` line names the device.
- **Threshold slider:** 0.2–2.0 g in 0.02 g steps.
- **Defaults:** VERTICAL feature, IMMEDIATE timing, no peak wait, no gyro gate; 200 Hz, 2M PHY,
  MTU 247, connection priority HIGH.

## 2026-11-25: Code tidy-up (no change to the latency path)

- **UI freeze watch:** a 10 Hz main-thread heartbeat (was a per-frame callback), started and
  stopped automatically as screens come and go.
- **One gap counter** (`GapCounter`, unit-tested) for BLE gaps and UI freezes.
- **Audio:** the device name follows Android's routing callback, and names wherever the sound
  actually goes, including the speaker.
- **Recording:** the log writer and its thread are one object (fixes a race with stop).
- **Settings:** a saved threshold over 2.0 g is capped on load; a saved rate no longer offered
  falls back to 200 Hz.

## 2026-11-25: Review fixes (BLE writes, one connection, latency pairing)

Found in code review; not yet tried on hardware.

- **Refused BLE writes are retried**, not dropped: back to the front of the queue, every 50 ms for
  up to ~1 s. A dropped UNLOCK or SAVE could leave the sensor half-configured. A reply that comes
  after its write timed out, or from an old connection, is ignored (it used to cancel the next
  write's timeout). `failed writes` now counts only timeouts and writes finally given up.
- **One connection at a time:** the scan connects to the first result only; `start()` does nothing
  while running; the Connect button asks the engine, so a recreated home screen (dark-mode switch,
  font size, split screen) can't start a second connection. Callbacks and timers from a replaced
  connection are ignored.
- **Latency meter, no drum heard:** the next stomp's impact was taken for this stomp's drum, so the
  next stomp went unpaired (sound off, fast stomping: 1 of 6 measured). Between play() and the
  next detection, two sounds are now drum then impact; a lone sound is whichever it's nearer.
  Unit-tested. The 2 s pacer hid this in the tests so far.

## Open questions

- **USB-C into an amp:** how much of the 100 ms output goes? Then AAudio/Oboe exclusive mode, if
  worth it (it may not be available on USB).
- **First-burst detection:** can the detector fire on the first burst (cut ~30 ms)? Needs a
  recording with the mic around real stomps.
- **Stalls:** what does the `stalls:` line show when one happens?
- **Custom firmware:** ask WitMotion whether the packing timer can be shortened (they sell
  customisation); nothing public covers it.
- **A faster sensor:** the WT901's ~44 ms packing is the floor for this sensor. On-sensor detection
  over BLE (the XIAO firmware, PR #5) should give ~10–15 ms sensor → phone; see issue #4.

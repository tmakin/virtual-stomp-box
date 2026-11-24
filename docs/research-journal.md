# Research journal: WT901 stomp sensor

A running log of tests on the real hardware, what they showed and what was changed. Newest
entries at the bottom. Design background is in [wt901-design.md](wt901-design.md).

**Setup:** WitMotion BWT901BLE5.0 (advertises as `WT901BLE68`) on the shoe, Google Pixel 8a,
Android 17, test app from the `apk-ccr-485f9f1f-a1qz3d` release, sound on the phone speaker.

---

## 2026-11-23: First install

- **Result:** the app installs, connects and plays a sound on each stomp.
- **Feedback:**
  1. The bottom buttons sit under the Android navigation bar.
  2. Keep the first iteration to a single stomp (no heel / toe / flat).
  3. Stomp → sound feels like about **400 ms**.
  4. It wasn't clear how to export a recording.
- **Changes (commit `452f137`):**
  - Both screens now pad for the system bars. At targetSdk 35, Android 15+ draws apps edge to edge.
  - Heel/toe zones off (`zonesEnabled = false`), and their UI removed.
  - **Live latency test** (`LatencyMeter`): the mic hears the impact and the drum, and each stomp
    is split into *detect* (impact → detected), *output* (play → heard) and *total*. *Total* is
    exact because both ends are mic times.
  - Sensor-rate setting (200 / 100 / 50 Hz), and a warning when fewer frames arrive than the
    sensor sends.
  - Recordings are copied to `Download/StompBox/`. Added **Copy status** and **Share last recording**.
  - The kick got a click attack, so it's audible on a phone speaker and the mic can catch it.

## 2026-11-24: Latency test 1 (200 Hz)

```
rate 200.0 Hz, 8.89 frames/event, interval median 43.53 ms p95 78.54, lag median 37.3 p95 78.6 max 106.0 ms
audio buffer 10.0 ms
latency: median of 9: detect 468 ms · output 99 ms · total 570 ms
```

- **Total is about 570 ms:** about 470 ms *detect* and about 100 ms *output*.
- **All 200 frames/s arrive**, so nothing is lost on the link.
- **The connection interval is about 44 ms**, not the 11.25–15 ms the app requests. The sensor
  appears to impose its own interval. That costs 20–80 ms of extra delay and jitter.
- **Hypothesis A: frames queue in the sensor.** A queue builds up at connect and never drains,
  because the link carries exactly the production rate. Prediction: at a lower rate the link has
  spare room, the queue drains, and *detect* drops.
- **Output of 100 ms** is far above the 10 ms app buffer. The suspicion was that the app wasn't
  getting the low-latency audio path.
- **Changes (commit `af60950`):**
  - Re-request `CONNECTION_PRIORITY_HIGH` 2.5 s and 6 s after setup.
  - The status shows the measured audio output latency (AudioTrack timestamps) and whether the
    fast path was granted.

## 2026-11-24: Latency test 2 (200 Hz, after `af60950`)

```
rate 200.1 Hz, 9.13 frames/event, interval median 44.30 ms p95 77.34, lag median 40.7 p95 82.0 max 102.8 ms
audio: buffer 10.0 ms, output latency 74 ms, fast path yes
latency: median of 10: detect 490 ms · output 101 ms · total 590 ms
```

- **Re-requesting connection priority made no difference** (interval still about 44 ms). The
  sensor sets the interval, and the phone can't override it.
- **The fast audio path *is* granted**, yet output latency is still about 74 ms by timestamp and
  about 100 ms by mic. The extra delay comes after the app's buffer: the Pixel speaker path
  (speaker protection DSP). Levers for later: wired or USB-C output, AAudio/Oboe.

## 2026-11-24: Latency test 3 (100 Hz)

```
sensor set to 100 Hz (rate reg 9)
rate 103.9 Hz, 5.39 frames/event, interval median 45.47 ms p95 78.95, lag median 64.1 p95 181.2 max 223.1 ms
latency: median of 9: detect 464 ms · output 100 ms · total 564 ms
```

- **Hypothesis A rejected.** Frames per event fell from about 9 to about 5, so the link had
  spare room, and *detect* didn't change.
- **Link jitter is worse at 100 Hz** (lag p95 181 ms).

## 2026-11-24: Latency test 4 (50 Hz)

```
sensor set to 50 Hz (rate reg 8)
rate 58.6 Hz (window still holds some 100 Hz samples), 3.03 frames/event, interval median 45.15 ms, lag median 82.3 p95 174.8 ms
latency: median of 9: detect 469 ms · output 100 ms · total 570 ms
```

- ***Detect* is about 470 ms at 200, 100 and 50 Hz.** A delay that stays fixed in *time*
  across a four-fold change in rate isn't a frame queue. Two hypotheses remain:
  - **B: the detector fires on the wrong movement.** If the sensor smooths its acceleration
    output, the impact spike may not reach the 1.2 g threshold. The detector then triggers on
    the next big movement, the foot lifting about 0.5 s later. That would explain how steady the
    delay is (human rhythm) and why it doesn't depend on the rate. This is now the most likely
    explanation, and if it's right the app can fix it.
  - **C: a fixed delay in the sensor firmware.** Less likely, but not ruled out.
- **Changes (commit `1c3c98d`):** the latency test also reports *first jolt*. That's when the
  first sample with a change in acceleration over 0.5 g arrived after the impact, plus the
  biggest jolt in the next 150 ms.
  - First jolt ≪ detect supports **B**.
  - First jolt ≈ detect supports **C**.
- **Also asked for:** a "stomp and hold" test (does the drum sound only on lift?), and a mic
  recording for desktop replay.

## Open questions

- B or C? Waiting on the first-jolt figure and the stomp-and-hold test.
- Can the sensor's connection interval be changed? Possibly through WitMotion's own tools or settings.
- Audio output: measure wired/USB-C output; consider AAudio/Oboe.

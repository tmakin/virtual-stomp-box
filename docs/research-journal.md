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

## 2026-11-24: Latency test 5 (200 Hz, first-jolt figure)

```
rate 199.2 Hz, 8.70 frames/event, interval median 43.36 ms p95 78.04, lag median 38.3 p95 78.9 max 93.2 ms
audio: buffer 10.0 ms, output latency 79 ms, fast path yes
latency: median of 9: detect 427 ms · output 101 ms · total 529 ms
impact in sensor data: first jolt at 421 ms, max 2.54 g
```

- **Hypothesis B rejected.** The first jolt over 0.5 g arrives at 421 ms, against detect at
  427 ms, and it's a sharp 2.5 g change. Lifting the foot wouldn't produce that. The impact *is*
  in the data, and the detector catches it within about one burst. **The data reaches the phone
  about 420 ms after the impact.** The delay is upstream of the GATT callback.
- **What other people have found** (web search):
  - [ehartmann/witmotion-ble](https://github.com/ehartmann/witmotion-ble) (WT9011DCL): about 4 s
    after connecting, the sensor sets the **connection interval to 30 ms with peripheral latency
    10**, overriding `CONNECTION_PRIORITY_HIGH`. From 50 Hz up, it **packs** the frames sampled
    meanwhile into one notification about every 40 ms (2 frames at 50 Hz, 4 at 100 Hz). No
    latency figures published.
  - The WT901BLECL manual warns of "data lag" when several connections are open (up to 4 are allowed).
- **Hypothesis D: a notification queue in the sensor.** Notifications are produced about every
  40 ms *whatever the rate* (packing), so a queue counted in notifications gives a delay that is
  constant *in time*: about 10 × 42 ms ≈ 420 ms. That matches tests 1–5 at all three rates. The
  queue would fill during connection setup and never drain, because about one notification goes
  out per connection event, the same rate they're produced. Peripheral latency 10 may be related.
  - Prediction: drop the rate to 1 Hz for a couple of seconds (production far below link
    capacity), and the queue empties; back at 200 Hz, the delay should be close to the link delay
    (about 40–80 ms) unless something refills it.
- **Changes (this commit):**
  - **Flush:** set the rate to 1 Hz without saving, wait 2.5 s, then set the configured rate
    again. This runs automatically 7 s after connecting, and there's a button for it. Samples
    during the flush are ignored.
  - The status shows the **connection parameters the stack reports** (interval, peripheral
    latency, supervision timeout, via the hidden `onConnectionUpdated` callback), and **frames
    per notification**.

## 2026-11-24: Latency test 6 (200 Hz, flush experiment, build #9 `54ee068`)

Automatic flush 7 s after connecting:

```
BLE: interval 15.00 ms, peripheral latency 1, timeout 5000 ms, 7.99 frames/notification, flushes 1
rate 199.2 Hz, 9.13 frames/event, interval median 43.55 ms p95 80.01, lag median 35.6 p95 76.5 max 99.4 ms
latency: median of 10: detect 421 ms · output 100 ms · total 521 ms
impact in sensor data: first jolt at 384 ms, max 1.25 g
```

After a second, manual flush:

```
BLE: interval 15.00 ms, peripheral latency 1, timeout 5000 ms, 7.98 frames/notification, flushes 2
latency: median of 9: detect 432 ms · output 100 ms · total 530 ms
impact in sensor data: first jolt at 420 ms, max 1.09 g
```

- **Hypothesis D rejected, in its drainable form.** Two flushes (2.5 s at 1 Hz each) left
  *detect* at 420–430 ms. A queue that the link could empty would have emptied.
- **The connection parameters aren't the problem.** The stack reports a **15 ms interval with
  peripheral latency 1**, so this phone and this sensor *did* get the fast interval, unlike the
  WT9011DCL report (30 ms, latency 10). Even so, notifications arrive only every **~44 ms, 8 frames
  each**: the sensor packs frames on its own ~40 ms timer, whatever the link allows. That timer
  explains ~20–40 ms of delay and jitter, not 420 ms.
- **Ruled out so far:** the phone (the impact is in the data when it arrives), the link
  (all frames arrive, 15 ms interval), the detector (first jolt ≈ detect), the sample rate (same
  delay at 200 / 100 / 50 Hz) and a drainable queue. **What's left is inside the sensor:** a
  structural delay (a fixed-depth delay line in the send path, or processing before output).
- **Next test: register-read round trip ("ping").** A register read is answered on the same
  notify path as the data. Every 2 s the app reads register 0x40 and times write → `55 71` reply.
  - Round trip ≈ 420 ms or more: the delay is in the **send path** (a fixed-depth buffer).
    Then, at 10 Hz (now in the rate cycler), the round trip shows whether the depth is counted
    in notifications (it would grow to seconds) or in time (it would stay ~420 ms).
  - Round trip ≈ 50–100 ms: the send path is fast and the **data itself is old** when it's
    queued (sampling or filtering in the firmware). That would point at the sensor's settings
    (bandwidth / output mode) or at a different sensor.

## Open questions

- Is the register-read round trip ~420 ms (send path) or short (old data)?
- If it doesn't: the delay is inside the sensor firmware. Check WitMotion's own app for the
  same lag (tap the sensor and watch the graph). Ask WitMotion support, or consider another sensor.
- Make sure the WitMotion app (or anything else) isn't also connected: the manual warns of lag
  with several connections.
- Audio output: about 100 ms on the Pixel 8a speaker even with the fast path granted. Measure
  wired/USB-C output; consider AAudio/Oboe.

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

## 2026-11-24: WitMotion app shows no lag

- With Stomp Box disconnected, the WitMotion app's live graph follows a sharp tap on the sensor
  with **no perceptible lag**. A 420 ms lag would be easy to see, so **the sensor can deliver
  fresh data**, and something about how *this app* sets up the link brings out the delay.
- **Hypothesis E: the large MTU.** This app requests MTU 247 and the 2M PHY, and the sensor then
  packs ~8 frames into each notification. WitMotion's SDKs (as far as we can see) keep the default
  23-byte MTU, so each notification carries one 20-byte frame. The firmware's packing path, used
  only when the MTU allows it, may hold data in a deep buffer.
- **Changes (this commit):**
  - A **BLE link** setting: *MTU 23, 1M PHY* (new default, like the WitMotion app), *MTU 23, 2M PHY*
    and *MTU 247, 2M PHY* (the old behaviour). Changing it reconnects. The BLE status line names the
    mode and the MTU granted; frames per notification and pings restart on each connection.
  - Releases also carry `stompbox-latest.apk`, a fixed link to the newest build.
- **Prediction:** at MTU 23, frames/notification ≈ 1 and *detect* drops to roughly 30–80 ms. If it
  doesn't, compare the ping round trip between the modes.

## 2026-11-24: Latency test 7 (build #11 `26adf38`, link modes and ping)

```
BLE (MTU 23, 1M PHY, MTU 23): interval 15.00 ms, peripheral latency 1, 7.97 frames/notification
ping (register read round trip): median 99 ms, min 51, max 163 (n=16)
latency: median of 10: detect 377 ms · output 102 ms · total 481 ms
impact in sensor data: first jolt at 372 ms, max 3.08 g

BLE (MTU 247, 2M PHY, MTU 247): interval 15.00 ms, peripheral latency 1, 7.95 frames/notification
ping (register read round trip): median 80 ms, min 34, max 175 (n=15)
latency: median of 10: detect 375 ms · output 98 ms · total 470 ms
impact in sensor data: first jolt at 354 ms, max 4.44 g
```

- **The MTU setting didn't take effect:** still ~8 frames per notification in "MTU 23" mode, which
  20-byte packets can't carry. Android 14+ negotiates a large MTU itself on connect. The WitMotion
  app gets the same, so **hypothesis E is out**: the MTU isn't the difference.
- **Ping ≈ 80–100 ms.** A command goes phone → sensor → phone in about 100 ms, through the same
  notify path as the data. The link and the Android stack are fast.
- **Suspected measurement flaw.** `LatencyMeter` took the impact as the *last sound before
  detection*. If the mic misses the impact, that's the **previous stomp's drum**, and *detect*
  reads as *stomp period − output*. At ~125 bpm that is ≈ 480 − 100 ≈ 380 ms, and *total* reads as
  the stomp period (470–590 ms across tests). That would explain:
  - why *detect* never moved with rate, flush, connection or MTU;
  - why the WitMotion app shows no lag;
  - the 100 ms ping.
  The *first jolt* figure is measured from the same (wrong) impact, so it agreed with *detect*
  and didn't catch this.
- **Fix (this commit):** an impact must come after the previous stomp's drum (plus 80 ms of
  ringing). A stomp with no impact heard in that window is counted, not scored, and the summary
  shows the count. Two new tests cover a 500 ms rhythm with and without the impacts heard.
- **Earlier latency figures (tests 1–7) are suspect** until re-measured with this build.

## 2026-11-24: Stomp pace

- The tester stomps at about **2 per second** (500 ms apart). With the old meter's flaw that gives
  *detect* ≈ 500 − 100 = 400 ms, close to every figure in tests 1–7.
- **Change:** a silent pacer on the tuning screen flashes **STOMP** every 2 s during the latency
  test, with a countdown in between. It's visual only, so the mic isn't confused.

## 2026-11-24: Latency test 8 (build #13 `276c684`, fixed meter, 2 s pacer)

```
BLE (MTU 247, 2M PHY, MTU 247): interval 15.00 ms, peripheral latency 1, 8.00 frames/notification
ping (register read round trip): median 112 ms, min 41, max 185 (n=18)
rate 199.4 Hz, 8.85 frames/event, interval median 44.72 ms p95 77.90, lag median 40.4 p95 81.7 max 108.6 ms
audio: buffer 10.0 ms, output latency 79 ms, fast path yes
latency: median of 11: detect 86 ms · output 100 ms · total 188 ms
impact in sensor data: first jolt at 55 ms, max 2.59 g
```

- **Confirmed: the ~420 ms was the meter's flaw.** With single stomps 2 s apart and the fixed
  meter, every stomp was paired (none unheard): **detect 86 ms, output 100 ms, total 188 ms**.
  Tests 1–7 measured the stomp period minus the output delay, so their *detect* figures, and the
  hypotheses A–E built on them, are void. The link observations still stand: 15 ms interval, ~8
  frames per notification every ~44 ms, all frames arriving.
- **Where the 188 ms goes now:**
  - **~55 ms, impact → first jolt in the data:** the sensor packs frames on a ~44 ms timer, so
    data waits up to one packing period, plus link time.
  - **~30 ms, first jolt → detection:** about one more notification. The first sample over 0.5 g
    is in one burst, and the 1.2 g threshold is crossed in the next. A detector lever.
  - **~100 ms output:** the biggest part. Pixel speaker path; AudioTrack says 79 ms.
- **Cleanup due:** the flush, the link-mode setting (the MTU can't be kept at 23 on Android 14+)
  and the 10 Hz rate were for the void hypotheses.

## 2026-11-24: Cleanup, USB audio, stall hunt

Feedback: remove the experiment code; optimise the audio output for USB-C (next test is through
an amp); the app stalls intermittently; cap the threshold slider at 2.0 g.

- **Removed:** the queue flush (it stopped the data for 2.5 s about 7 s after *every* connect,
  including reconnects), the BLE link setting, the 10 Hz rate and the repeated connection-priority
  requests. The ping stays, every 5 s.
- **Possible causes of stalls, found in the code and fixed:**
  - The automatic flush, as above.
  - **Timers piled up on reconnect.** The battery poll and ping were never cancelled when the link
    dropped, so every reconnect added another set.
  - **Colliding BLE writes.** Android takes one GATT write at a time and refuses the rest. Setup
    commands, battery reads and pings went out on independent timers, so some were dropped
    silently. All writes now go through one queue, each waiting for the previous one's reply.
  - **Recording wrote to disk on the Bluetooth callback thread.** It now runs on its own thread.
- **Stall diagnostics:** the status now has a `stalls:` line with the BLE gaps over 250 ms (and the
  longest), reconnects, failed writes, and UI freezes over 200 ms. The `audio:` line counts underruns.
  The next stall report will show which part stalled.
- **USB / wired audio:** the drum synth picks a USB interface, USB-C headphones or a wired / line
  output as soon as one is plugged in, and rebuilds its track for that device. The track is 48 kHz
  stereo (some USB interfaces take only two channels). The buffer starts at two bursts and grows by
  one burst on each underrun, as Oboe's latency tuner does. The `audio:` line names the device.
- **Threshold slider:** 0.2–2.0 g in 0.02 g steps.

## Open questions

- Can the detector fire on the first burst (cut ~30 ms)? Look at a recording of the samples
  around an impact.
- Output through USB-C into an amp: how much of the 100 ms goes? Then AAudio MMAP if worth it.
- Stalls: what does the `stalls:` line show when one happens?
- If it doesn't: the delay is inside the sensor firmware. Check WitMotion's own app for the
  same lag (tap the sensor and watch the graph). Ask WitMotion support, or consider another sensor.
- Make sure the WitMotion app (or anything else) isn't also connected: the manual warns of lag
  with several connections.
- Audio output: about 100 ms on the Pixel 8a speaker even with the fast path granted. Measure
  wired/USB-C output; consider AAudio/Oboe.

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
- **The packing happens only on the Bluetooth side:** over the USB-C cable the same sensor sends
  one frame per sample at 200, 100 and 50 Hz, within ±0.3 ms (2026-11-25 tests below).
- **Firmware:** no image is public. WitMotion's Android app can update sensors over Bluetooth
  (Nordic DFU, from a file the user picks), so a WitMotion build could go on without opening the
  sensor; our own patched build almost certainly can't (signed updates).
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

## 2026-11-25: USB-C probe (`tools/wit_usb_probe.py`, MacBook)

```
Opened /dev/cu.usbserial-110 at 115200
Frames seen: {'ble': 2048, 'reply': 1}
Firmware version registers: 0x2E = 0x3337, 0x2F = 0x000A
Samples: 2048 in 10.0 s = 204.8 Hz
Bursts: 1987, samples per burst median 1 (max 62), gap median 5.1 ms, p95 5.3 ms
```

- **The USB-C port carries data** through a CH340 USB-serial chip (macOS's built-in driver), using
  the same 20-byte `55 61` frames as Bluetooth. It accepts commands: the rate write and a register
  read both worked.
- **Frames arrive one at a time, ~5 ms apart.** The ~44 ms batching happens only on the Bluetooth
  side, so patched firmware (or a wired connection) could save most of the ~55 ms.
- One burst of 62 frames, probably the read loop starting late or a pause on the Mac. The 204.8 Hz
  is measured against the Mac's clock (Bluetooth gave 199.4 Hz).
- **Firmware version registers:** 0x2E = 0x3337, 0x2F = 0x000A. How to decode them isn't known.

## 2026-11-25: USB timing, register dump, firmware search (MacBook)

**USB timing, redone.** The probe now flushes input and drops the first 0.5 s, so the start-up
backlog no longer counts. 10 s per rate, `uv run tools/wit_usb_probe.py --rate N --csv ...`:

```
200 Hz: Samples: 1986 in 10.0 s = 198.7 Hz
        Bursts: 1985, samples per burst median 1 (max 2), gap median 5.0 ms, p95 5.3 ms
        Single-frame arrival interval (n=1982): median 5.03 ms, sd 0.17, p5 4.74, p95 5.29, max 5.92
100 Hz: Samples: 993 in 10.0 s = 99.4 Hz
        Bursts: 993, samples per burst median 1 (max 1), gap median 10.1 ms, p95 10.3 ms
        Single-frame arrival interval (n=992): median 10.07 ms, sd 0.20, p5 9.81, p95 10.33, max 12.20
 50 Hz: Samples: 497 in 10.0 s = 49.8 Hz
        Bursts: 497, samples per burst median 1 (max 1), gap median 20.1 ms, p95 20.4 ms
        Single-frame arrival interval (n=496): median 20.14 ms, sd 0.15, p5 19.91, p95 20.38, max 20.77
```

- **One frame per sample at every rate, ±0.3 ms (p95).** The 62-frame burst and the 204.8 Hz in the
  first run were the start-up backlog; the real rate is 198.7 Hz on the Mac's clock (Bluetooth on
  the Pixel: 199.4 Hz).
- **USB-serial latency is small:** a 20-byte frame takes 1.7 ms on the wire at 115200 baud, plus
  ~1 ms of USB polling. So USB is a good reference clock for the sensor's own timing.

**Register dump (reads only).** `--dump-registers` sends `FF AA 27 <reg> 00` for 0x00–0xFF, 100 ms
apart; table in [wt901-register-dump-2026-11-25.csv](wt901-register-dump-2026-11-25.csv). Nothing
was written apart from the rate.

- **0x00–0x8F answer; 0x90–0xFF don't** (no reply at all). That includes 0x96, the documented
  output-content register, so it is either Bluetooth-only or handled elsewhere.
- **Known settings:** rate 0x03 (0x0008 = 50 Hz, left from the last run, not saved), gyro offsets
  0x08–0x0A, mag ranges 0x1C–0x1E = 500, bandwidth 0x1F = 2, gyro range 0x20 = 3, accel range 0x21 = 3,
  0x25 = 30, 0x2A = 500, 0x2D = 1, version 0x2E/0x2F.
- **The Bluetooth name is in registers:** 0x12–0x16 read `WT 90 1B LE 68` as ASCII ("WT901BLE68").
  These are the D0–D3 port registers on the wired WT901, so this firmware reuses them.
- **Live data:** time 0x30–0x33, accel/gyro/mag/angle 0x34–0x3F, temperature 0x40, quaternion
  0x51–0x54.
- **Undocumented, 0x63–0x75:** 0x63 = 1000, 0x64 ≈ 515 (moves slightly; maybe a voltage),
  0x66–0x68 fixed, **0x6A changes (1, 2, 3, 5)**, 0x6B = 200, **0x6C counts up ~100 per read** (a
  ms timer), 0x6D/0x6E = 500, 0x6F = 300, 0x70 = 16, 0x72 = 5, 0x75 = 0x7000. 0x6A could be a queue
  count; nothing here is clearly a Bluetooth packing setting, and none of it has been written.

**Bluetooth from the Mac: not run.** macOS refuses Bluetooth to the editor running the tools
("access denied by the user"). Two scripts are ready for when it's allowed (or from Terminal):
`tools/wit_ble_gatt.py` (lists services, reads values, flags FE59 / legacy DFU / SMP; read-only) and
`tools/wit_ble_vs_usb.py` (both links at once, frames matched by their 18 data bytes, reports
Bluetooth minus USB delay per frame and per position in the notification). Expected from the
numbers above: 0–44 ms per sample, sawtooth, ~20 ms on average plus the link.

**Firmware search.** No BWT901BLE firmware image is public. Checked WitMotion's downloads page,
GitHub (incl. the BLE 5.0 SDK repo), and the contents of both official bundles (listed and
searched, nothing run):

- **Android app 5.1.3** includes Nordic's DFU library (`no.nordicsemi.android.dfu`, via the
  `nordic_dfu` Flutter plugin) and a "DFU firmware upgrade" screen ("Entered OTA mode", looks for
  `DfuTarg`, the nRF52 bootloader's name). No image is bundled and no download URL is built in: it
  loads a file the user picks. The App Store history adds this in 5.0.3 (Dec 2023) for the
  WT9011DCL-BT50; whether it covers our model isn't known.
- **PC software V2025.11.26.0:** "Firmware Upgrade" and "OTA Upgrade" windows also load a chosen
  file. The bundled images are for other products: STM32 .hex files (load address 0x08000000) for
  an evaluation board, and ESP8266 .bin files for the WiFi model. Its self-updater points at
  `upgrade.wit-motion.cn:7676` (app updates; not contacted).
- **So:** WitMotion hands firmware out per customer. A WitMotion build could likely go on over
  Bluetooth with their app, no case opening. With Nordic Secure DFU it must be signed by them, so
  our own patched build almost certainly can't. Reading the image over USB isn't possible: the
  cable only reaches the register protocol, and reading flash needs SWD, which production nRF52s
  usually lock.

## 2026-11-25: USB-C to 3.5 mm adapter (build #24 `422448d`)

The Pixel's audio went through a USB-C to 3.5 mm adapter (`USB-Audio - KM_B2 AUX`) into an amp.
Recording with the mic, 14 stomps:

```
audio: USB audio 'USB-Audio - KM_B2 AUX', buffer 10.0 ms, output latency 69 ms, fast path yes, underruns 0
sensor: 199.7 Hz, 8 frames in every notification, gap median 41.9 ms, p95 78.2 ms
output (drum fired -> heard): median ~103 ms, range 98-113 ms (14 of 14)
detect: only 1 of 14 impacts heard by the mic (63 ms; total 167 ms)
```

- **The USB-C adapter is no faster than the speaker:** ~103 ms by mic against 100 ms on the
  speaker, though Android reports 69 ms (79 ms on the speaker). Android's audio path on this phone
  sets the floor, not the speaker.
- **Bluetooth batching unchanged:** 8 frames per notification, ~42 ms apart.
- **The mic missed most impacts:** put the phone nearer the foot to measure detect.

## Open questions

- **Phone audio output (~100 ms on both speaker and USB-C):** try AAudio/Oboe in exclusive (MMAP)
  mode on the built-in speaker. Pixels support it there (not on USB), and it skips Android's
  mixer. Otherwise a separate box (Raspberry Pi or Teensy reading the sensor over USB) avoids
  both the phone's audio delay and the Bluetooth batching.
- **First-burst detection:** can the detector fire on the first burst (cut ~30 ms)? Needs a
  recording with the mic around real stomps.
- **Stalls:** what does the `stalls:` line show when one happens?
- **Custom firmware:** the WT901BLECL datasheet names one nRF52832 as the Bluetooth chip, with an
  MPU9250 motion sensor and a fixed 115200-baud wired output on the USB-C port. Options:
  - **Ask WitMotion** for a firmware build that sends every frame at once. They advertise OEM
    customisation, but nothing public says whether they'd do it, or whether the sensor can be
    updated over the air.
  - **Reflash it ourselves:** possible over SWD after a full erase (the original firmware is lost).
    It means writing all the firmware and tracing the board's pins: the same job as the XIAO
    firmware on worse hardware, so not worth it.
  - **Done:** the USB-C output is one frame per sample, so the packing is Bluetooth-only. No
    firmware image is public; WitMotion's app updates over Bluetooth from a file they supply.
  - **Next:** does our sensor list the FE59 update service (app tuning screen, or
    `tools/wit_ble_gatt.py`)? Then ask WitMotion for a build without the ~44 ms packing.
  - **Measure the exact Bluetooth delay** with `tools/wit_ble_vs_usb.py` once the Mac allows
    Bluetooth (or from Terminal).
- **A faster sensor:** the WT901's ~44 ms packing is the floor for this sensor. On-sensor detection
  over BLE (the XIAO firmware, PR #5) should give ~10–15 ms sensor → phone; see issue #4.

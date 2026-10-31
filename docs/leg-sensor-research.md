# Leg-strapped tap sensor for Stomp Box: findings

Oct 31, 2026 · answer to the "Leg-Strapped Accelerometer for Stomp Box" handoff

## Short answer

**Buy and test first: Seeed XIAO nRF52840 Sense (about £16) with our own firmware**
([`firmware/xiao-tap-midi`](../firmware/xiao-tap-midi)). It detects the tap on the board and sends one
standard BLE MIDI note, so the app reads it through `android.media.midi` with no vendor SDK. The
same firmware also sends USB MIDI over the board's USB-C port.

**Fallback: the same board on a USB-C cable.** It costs nothing extra and is the only option
here that clearly meets the jitter limit. If we would rather not build anything, the off-the-shelf
fallback is the **Freedrum 2 foot sensor**, tested with the same probe.

**Nothing has been measured yet.** There is no hardware in this session, so the "Measured" column
is empty. The figures below come from a timing model ([`tools/ble_latency_model.py`](../tools/ble_latency_model.py))
and from what vendors claim. The test code to fill the column is ready
([`android/`](../android)).

## The finding that matters most: jitter, not average delay

With BLE, a tap waits for the next connection event, which is anywhere from 0 to one connection
interval away. So the interval sets both the extra delay and the jitter. Android's stack has
clamped the minimum interval to **11.25 ms** since Android 6, rather than the 7.5 ms the BLE spec
allows ([AOSP change](https://gitlab.e.foundation/e/os/android_packages_modules_bluetooth/-/commit/3ef673fc06a45ffb3f6d6d9fc58c917e8d91709a),
[Nordic DevZone](https://devzone.nordicsemi.com/f/nordic-q-a/12702/nexus-5x-connection-interval)).
`CONNECTION_PRIORITY_HIGH` typically gives 11.25–15 ms. I found no Pixel 8 measurement, so the
first job on hardware is to read the granted interval from a Bluetooth HCI snoop log.

Modeled impact → event-in-app delay, with on-board detection at 1.66 kHz, 2 % missed connection
events and an assumed 1–2.5 ms Android stack delay:

| Link | Interval | Median | p95 | Spread p5–p95 | Meets ≤10 ms target / 15 ms limit? | Meets ±4 ms jitter limit? |
| --- | --- | --- | --- | --- | --- | --- |
| USB MIDI (cable) | 1 ms poll | 2.6 ms | 3.5 ms | ±0.8 ms | Yes | **Yes** |
| BLE, raw GATT | 7.5 ms | 7.0 ms | 10.7 ms | ±3.6 ms | Yes | Just |
| BLE MIDI (`android.media.midi`) | 7.5 ms | 8.1 ms | 12.1 ms | ±3.9 ms | Yes | Just |
| BLE, raw GATT | 11.25 ms | 8.9 ms | 14.2 ms | ±5.3 ms | Median yes, p95 near limit | **No** |
| BLE MIDI | 11.25 ms | 10.0 ms | 15.5 ms | ±5.5 ms | Median on target, p95 just over | **No** |
| BLE MIDI | 15 ms | 11.9 ms | 19.1 ms | ±7.1 ms | No | No |

So if the Pixel 8 grants 11.25 ms, which is likely, **any BLE sensor, bought or built, misses the
±4 ms jitter limit** when the note plays the moment the event arrives. No product choice can fix
this; it is the link itself. Three ways round it, in order of preference:

1. **Schedule from the sensor's timestamp (recommended next app change).** Every BLE MIDI packet
   carries the sender's millisecond timestamp, and our firmware stamps it with the moment of
   impact. AOSP's Bluetooth MIDI service turns that into the timestamp passed to
   `MidiReceiver.onSend`. That still needs confirming on the Pixel 8, and `MidiTapSource` logs the
   difference so we can check. If the app played each note at *impact + a fixed delay* (≈ p95,
   about 14–16 ms) instead of immediately, the jitter would fall to roughly ±1 ms, at the cost of a
   steady ~15 ms. A steady 15 ms + 10 ms audio feels better than 10 ± 5.5 ms. This needs a
   "play at frame N" call in the native engine; today `NativeAudio.noteOn` plays at once.
2. **Get a 7.5 ms interval.** The firmware asks for 7.5–11.25 ms. Some newer stacks may allow
   7.5 ms; check it in the snoop log.
3. **Use the cable.** USB MIDI is tight (±1 ms). But the Pixel 8 has no headphone jack, so audio
   out to an amp also needs USB-C, which means a USB-C hub with audio or a powered hub plus a USB
   audio interface.

## Comparison table

✓ = checked against a current source (see Sources). ~ = estimate or from memory, not verified.
"Model" latency comes from `tools/ble_latency_model.py`, not from a vendor.

| Device | Link | On-sensor tap detection? | Sample rate (Hz) | Range (±g) | Weight (g) | Battery (h) | Android access | Price (£) | Claimed latency (ms) | Measured latency (ms) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Seeed XIAO nRF52840 Sense + our firmware** (pick) | BLE MIDI + USB MIDI | Yes (our firmware; same algorithm as `AccelTapDetector`) | 1,660 used (IMU up to 6,660 ~) | 16 ✓ (LSM6DS3TR-C) | 4 board ✓; ~10–15 with 100 mAh LiPo, case and strap ~ | ~10+ with 100 mAh ~ (not measured) | Standard BLE MIDI (`android.media.midi`) | 15.90 board ✓; ~£30 with battery, case and strap ~ | None (model: BLE MIDI median 10 / p95 15.5 at 11.25 ms; USB 2.6 / 3.5) | Not yet | 21 × 17.5 mm. Has a LiPo charger on board ✓ (battery is soldered to pads). Firmware written, not compiled yet. Sensitivity settable over MIDI (CC 1). |
| Adafruit Feather nRF52840 Sense | BLE MIDI + USB MIDI | Yes (same firmware with small pin changes) | Same IMU family ~ | 16 ~ | ~6 board ~ | Same as XIAO ~ | Standard BLE MIDI | ~35 ~ | None | Not yet | Larger (51 × 23 mm ~) but has a JST battery plug, so no soldering. Good choice if the XIAO battery pads are a pain. |
| M5StickC Plus2 / AtomS3R (ESP32-S3) | BLE MIDI | Yes (our firmware, ESP32 port) | IMU up to ~1,600 ~ | 16 ~ (MPU6886 / BMI270 ✓ on AtomS3R) | AtomS3R 6.8 ✓; StickC with battery ~17 ~ | StickC: built-in battery, BLE use not measured ~ | Standard BLE MIDI | AtomS3 ~12–19 ✓ | None | Not yet | Comes with a case, and the StickC has a battery and button. ESP32 BLE draws more power than nRF52. Original AtomS3 discontinued ✓. Backup if the XIAO stalls. |
| Arduino Nano 33 BLE Sense Rev2 | BLE MIDI | Yes (our firmware) | BMI270 up to 1,600 ~ | 16 ~ | ~5 ~ | Needs external charger ~ | Standard BLE MIDI | ~40 ~ | None | Not yet | Same nRF52840 chip as the XIAO for twice the price, and no battery charger. No reason to pick it over the XIAO. |
| **Freedrum 2 foot sensor** (off-the-shelf fallback) | BLE MIDI ✓ | Yes ✓ (sends MIDI notes) | Not published | Not published | Not published (small puck) | 14 claimed ✓ (older sources 7–12) | BLE MIDI; Android listed ✓, but Freedrum earlier said it would not support Android because of latency ✓ | Sticks + feet kit about $189 (older US) ✓; AU$206 for a 2-sensor kit ✓ | None found | Not yet | Made for this job. Heel-down playing; foot angle picks kick or hi-hat ✓, which we can ignore since the app sets the pitch. Check that the foot sensor can be bought without sticks. Closed firmware, so we can't change the connection interval or detection. |
| Senstroke (Redison) | BLE (to its own app; MIDI support not confirmed) | Yes | Not published | Not published | Not published | Not published | Senstroke app on iOS and Android (2020) ✓ | Unknown | None found | Not yet | Unclear whether it is still on sale: Amazon says "not discontinued" ✓, and a UK "Redison Limited" was dissolved in Jan 2026 ✓ (may not be the French maker). Senstroke.com was not reachable from this session. Low priority. |
| Movesense HR2 / Flash | BLE GATT (open API) | Possible with custom device firmware (Movesense C++ device library ✓) | Up to ~1,600 ~ | 16 ✓ | 9.4 ✓ | Coin cell (CR2032 ~), **not rechargeable** | Android SDK (MDS library) ✓ | 99 (HR2) / 249 (Flash) store prices ✓ (currency not stated, probably €) | None | Not yet | Good finished hardware with an open stack, but a coin cell fails the "rechargeable" requirement and custom firmware means the same work as the XIAO at 5× the price. |
| MbientLab MetaMotionS | BLE GATT | Streams; no tap event ~ | Up to 800 ~ | 16 ~ | ~9 ~ | Rechargeable ~ | Android SDK | Out of stock ✓ | None | — | **Dropped.** Company looks inactive since 2025; out of stock ✓. |
| Movella / Xsens DOT | BLE GATT | No (streams) | 60 live over BLE ~ | 16 ~ | ~11 ~ | ~6 ~ | Android SDK | Sold in packs ~ | None | — | **Dropped.** Streaming rate too low for the ≥400 Hz fallback. Movella renamed itself Xsens again in Mar 2026 ✓. |
| Piezo contact pickup → USB-C audio input | Wired audio | Yes (in app, at 48 kHz) | 48,000 | n/a | ~3 piezo + cable | None needed | Oboe input stream (same engine as audio out) | ~10–30 ~ | None | Not yet | No radio. Delay is the Pixel's audio *input* latency (unmeasured, guess 5–10 ms ~). Needs a USB-C interface with input *and* output. Picks up the stage floor, so false triggers are likely. |
| XIAO on a USB-C cable (USB MIDI) | USB MIDI | Yes | 1,660 | 16 | 4 + cable | None needed | `android.media.midi`, USB transport | Same board | None (model: median 2.6, ±0.8) | Not yet | **Wired fallback.** Same board and firmware. Needs a hub for audio out. |
| Pixel Watch / Wear OS on the ankle | Bluetooth (Data Layer) | App on watch | Not verified | Not verified | ~30+ | ~24 | Wear OS Data Layer | Already owned | None | — | **Dropped as expected.** I found no published accelerometer rate or Data Layer latency. Watch-to-phone messages go through Play Services rather than a dedicated low-latency link, and the watch is heavy for an ankle. Not worth testing before the options above. |

## Latency test result for the top pick

**Not measured.** This session has no XIAO board and no Pixel 8. What's ready:

- **Firmware** for the XIAO: [`firmware/xiao-tap-midi/xiao-tap-midi.ino`](../firmware/xiao-tap-midi/xiao-tap-midi.ino).
  Not compiled: the Arduino toolchain download was blocked in this session.
- **Test harness** for the Pixel 8: [`android/android-only/.../latency/LatencyProbe.kt`](../android/android-only/uk/neatlines/stompbox/latency/LatencyProbe.kt).
  It follows steps 1–3 of the handoff. It detects the impact on the mic and maps it to
  `System.nanoTime` with `AudioRecord.getTimestamp`, so mic buffering does not skew the result. It
  pairs each impact with the sensor event and reports median, p95, min, max, standard deviation,
  p5–p95 spread, misses and false triggers, and writes a CSV.
- **Two BLE input paths**, so we can see whether Android's MIDI service adds delay:
  [`MidiTapSource`](../android/android-only/uk/neatlines/stompbox/input/MidiTapSource.kt)
  (`android.media.midi`, also handles USB) and
  [`GattMidiTapSource`](../android/android-only/uk/neatlines/stompbox/input/GattMidiTapSource.kt)
  (reads the same characteristic over raw GATT).
- The platform-independent parts (MIDI and BLE-MIDI parsing, onset detection, pairing,
  statistics) have 19 JVM unit tests, all passing (`cd android && gradle test`).

What to expect, from the model: BLE MIDI at 11.25 ms gives median ~10 ms, p95 ~15.5 ms,
±5.5 ms. USB gives median ~3 ms, ±1 ms.

### Test plan (half a day once the board arrives)

1. Flash the firmware. Check in a BLE MIDI monitor app that one tap gives one note.
2. Turn on *Bluetooth HCI snoop log* in Developer options. Connect, then pull the log with
   `adb bugreport` and find the **LE Connection Update Complete** event: that is the interval the
   Pixel 8 granted.
3. With the phone muted, run `LatencyProbe` for 60 taps on each path: `MidiTapSource` over BLE,
   `GattMidiTapSource`, and `MidiTapSource` over USB. Also run the phone's own accelerometer
   (`AccelTapSource.onTap`) as a baseline, which tests the 15–25 ms estimate in the README.
4. Repeat the BLE runs near a busy Wi-Fi access point and a few other BLE devices. Note misses and
   any events over 30 ms.
5. Cross-check 5 taps with 240 fps slow-motion video (4.2 ms per frame). A constant offset
   between video and probe means a timestamp error, not link delay.
6. Decide: if BLE p95 ≤ 15 ms **and** spread ≤ ±4 ms, use BLE as is. If not, build timestamp
   scheduling (option 1 above) and re-test, or go wired.

## Answers to the open questions

- **Connection interval on the Pixel 8:** probably 11.25 ms (Android's floor since 6.0); 7.5 ms is
  unlikely but the firmware asks for it. Needs the snoop log to confirm. Android 14 added
  `CONNECTION_PRIORITY_DCK`, but it is for car keys and its interval is no shorter.
- **Does BLE MIDI on Android add delay?** It adds a process hop (Bluetooth stack → MIDI service →
  app), which I estimated at about 1 ms. The two input paths in `android/` measure this directly.
  The best published figures for BLE MIDI are under 10 ms with dedicated hardware
  ([NIME 2019](https://nime.org/proc/wang2019)). One informal Android test reported 10–45 ms, but
  that included the synth app ([KVR forum](https://www.kvraudio.com/forum/viewtopic.php?p=6884028)).
- **Radio interference:** a missed connection event costs one whole extra interval (+11.25 ms).
  The model assumes 2 %. Step 4 of the test plan measures the real rate. The cable avoids it.
- **Placement:** toe or shoe gives a sharper spike. The firmware uses the same adaptive threshold
  as `AccelTapDetector`, and sensitivity can be set over MIDI (CC 1), so heel and toe can be tuned
  without reflashing.
- **False triggers:** the firmware has a 90 ms dead time after each tap and re-arms only once the
  signal falls below half the threshold, the same as the phone detector. Walking will still
  trigger it; a mute button or "only while the app is playing" is an app-side question.
- **Product lifespan:** MbientLab looks gone and Xsens DOT is streaming-only, so this supports
  building. The XIAO design uses only standard BLE MIDI, so any nRF52840 or ESP32 board can run it.
- **Buy or build?** Build. Off-the-shelf options either can't be checked (Freedrum publishes no
  latency or sensor specs and won't let us change the interval), lack a rechargeable battery
  (Movesense), or look discontinued. Building gives us control of the timestamp, which is what fixes
  jitter.

## How it plugs into the app

The Android files go into `stompbox/app/src/main/java/uk/neatlines/stompbox/` in the bitxo repo,
and the pure-Kotlin files go in alongside them. See [`android/README.md`](../android/README.md) for
the manifest permissions and the few lines to add to `MainActivity`.

- `MidiTapSource` turns each note-on into a `TapEvent(time, velocity)` and calls the same
  `onTap` handler as `AccelTapSource`.
- `TapEvent.sensorTimeNanos` is filled from the MIDI timestamp, converted from
  `System.nanoTime` to `elapsedRealtimeNanos`. The status line's "detect … ms" then shows the link
  delay.

## Sources

- Android BLE interval floor: [AOSP commit (mirror)](https://gitlab.e.foundation/e/os/android_packages_modules_bluetooth/-/commit/3ef673fc06a45ffb3f6d6d9fc58c917e8d91709a), [Nordic DevZone: Nexus 5X interval](https://devzone.nordicsemi.com/f/nordic-q-a/12702/nexus-5x-connection-interval), [Stack Overflow (archive): Android 6 connection parameters](https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/34617061/android-6-0-marshmallow-ble-connection-parameters), [flutter_splendid_ble: priority → interval](https://pub.dev/documentation/flutter_splendid_ble/1.2.0/central_models_ble_connection_priority/BleConnectionPriority.html)
- `CONNECTION_PRIORITY_DCK`: [.NET Android binding docs](https://learn.microsoft.com/en-us/dotnet/api/android.bluetooth.bluetoothgatt.connectionprioritydck?view=net-android-37.0), [AOSP commit (mirror)](https://gitlab.e.foundation/e/os/android_packages_modules_bluetooth/-/commit/bdf6b002db3ce5ae6b37cb380450752f68c2b720)
- BLE MIDI latency: [NIME 2019, Wang et al.](https://nime.org/proc/wang2019), [CME: the truth about Bluetooth MIDI](https://www.cme-pro.com/the-truth-about-bluetooth-midi/), [KVR forum: Seaboard over BLE on Android](https://www.kvraudio.com/forum/viewtopic.php?p=6884028)
- XIAO nRF52840 Sense: [The Pi Hut](https://thepihut.com/products/seeed-xiao-ble-nrf52840-sense), [Pimoroni (weight)](https://wholesale.pimoroni.com/products/seeed-xiao-ble-nrf52840-sense), [Seeed forum: LSM6DS3 tap interrupt](https://forum.seeedstudio.com/t/xiao-ble-sense-lsm6ds3-int1-single-tap-interrupt/264206/6), [Nordic DevZone: XIAO IMU interrupt issues](https://devzone.nordicsemi.com/f/nordic-q-a/115921/unable-to-get-gpio-interrupt-from-lsm6ds3tr_c-to-nrf52840-in-the-seeed-studio-xiao-ble-sense/508341)
- M5Stack: [Switch Science AtomS3 (discontinued)](https://www.switch-science.com/collections/m5stack/products/8670), [ATOMS3R listing](https://evelta.com/m5stack-atoms3r-esp32-s3-development-kit-with-0-85-inch-screen-8mb-psram/), [AtomS3 price history](https://elecena.pl/product/27081403/rss)
- Freedrum: [Kickstarter FAQ](https://www.kickstarter.com/projects/freedrum/freedrum-2-our-next-virtual-drum-kit/faqs), [Freedrum AU](https://www.freedrum.com.au/), [Electronic Drum Advisor review](https://www.electronicdrumadvisor.com/?p=1064), [Freedrum vs Senstroke vs Aeroband](https://www.electronicdrumadvisor.com/?p=6254), [Harvey Norman listing](https://www.harveynorman.com.au/freedrum-starter-kit-2-sensors-sticks-natural-finish.html), [The Gadgeteer review](https://the-gadgeteer.com/?p=220747), [Eureka: Android latency](https://eurekamagazine.co.uk/content/fun/rock-and-a-hard-place)
- Senstroke: [Tech Buzz Ireland: Senstroke 2.0](https://techbuzzireland.com/2020/03/06/redison-announces-senstroke-app-2-0-redison-apps-music), [Amazon listing](https://www.amazon.com/Senstroke-Redison-Bluetooth-Drumsticks-Protective/dp/B07PVHF74S), [Companies House: Redison Limited](https://find-and-update.company-information.service.gov.uk/company/11779111), [Redison terms](https://www.redison.com/pages/senstroke-terms)
- Movesense: [HR2 spec sheet](https://www.movesense.com/wp-content/uploads/2024/09/Movesense-Sensor-HR2-Spec-Sheet.pdf), [HR+ product page](https://www.movesense.com/product/movesense-sensor-hr/), [Flash](https://www.movesense.com/product/movesense-flash/), [Device library sample apps](https://movesense.com/docs/esw_1.9/sample_applications), [Custom firmware service](https://www.movesense.com/news/2024/02/boost-your-wearable-sensor-project-with-movesense-custom-firmware-service/)
- MbientLab: [Reddit r/embedded thread](https://reddit.birdcat.cafe/r/embedded/comments/1l5kl10/anyone_know_whats_going_on_with_mbientlab_are), [Store](https://mbientlab.com/store)
- Xsens: [Wikipedia](https://en.wikipedia.org/wiki/Xsens)

Not reachable from this session (blocked by its network policy): freedrum.rocks and senstroke.com.
Check those for current prices and specs before ordering a Freedrum.

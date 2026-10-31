# XIAO tap → MIDI firmware

Runs on a **Seeed XIAO nRF52840 Sense** strapped to the shoe or ankle. It detects each foot tap on
the board and sends one MIDI note-on per tap:

- over **BLE MIDI** (standard service, so Android's `android.media.midi` opens it), and
- over **USB MIDI** whenever the USB-C cable is plugged in (wired, ~1 ms polling).

> **Status:** written but **not yet compiled or run on hardware**. The Arduino toolchain download
> was blocked in the session that wrote it. Expect to fix small API mismatches on the first build.

## Build

1. Arduino IDE → Preferences → Additional boards URLs:
   `https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`
2. Boards Manager: install **Seeed nRF52 Boards**. Use the plain package, *not*
   "Seeed nRF52 mbed-enabled Boards": the firmware uses the Adafruit Bluefruit and TinyUSB APIs.
3. Library Manager: install **Seeed Arduino LSM6DS3**.
4. Board: *Seeed XIAO nRF52840 Sense*. USB stack: *TinyUSB*. Upload.

## What it does

| Stage | Setting | Why |
| --- | --- | --- |
| IMU | LSM6DS3TR-C, ±16 g, 1.66 kHz, gyro off | ±16 g so heel strikes don't saturate; 0.6 ms per sample |
| Detection | Port of the app's `AccelTapDetector`: gravity high-pass, adaptive threshold, fires on the first sample over it, 90 ms dead time | Same feel as phone mode; adds ≤ 0.6 ms |
| BLE | Asks for a 7.5–11.25 ms connection interval with no peripheral latency, and 2M PHY | Android usually grants 11.25 ms and has rejected requests whose max is below 11.25 ms |
| BLE MIDI packet | `header, timestamp, 0x99, 36, velocity`. The timestamp is `millis()` at the impact sample | A receiver can use it to cancel connection-interval jitter |
| USB MIDI | Same note, cable 0 | Wired fallback |
| Sensitivity | MIDI CC 1 (0–127) written to the device sets it; default 64 | Tune heel or toe placement without reflashing |
| LED | Blue flashes for 30 ms per tap | Quick check without the phone |

No note-off is sent: the app plays one-shot notes, and a second packet would only queue up behind
the next tap.

## Hardware: parts list

| Part | Example | Approx. £ | Notes |
| --- | --- | --- | --- |
| Board | [Seeed XIAO nRF52840 Sense](https://thepihut.com/products/seeed-xiao-ble-nrf52840-sense) ×2 | 32 | Buy a spare. Get the plain Sense, not the "Plus" (it's bigger and has pins we don't need). |
| Battery | [150 mAh 3.7 V LiPo, JST-PH](https://thepihut.com/products/150mah-3-7v-lipo-battery) | 6 | Must have a protection circuit (PCM), as Adafruit-type cells do. 150 mAh should last well past 4 h (not measured). Charges at 50 or 100 mA through USB-C. |
| Power switch | [Mini SPDT slide switch](https://thepihut.com/products/breadboard-friendly-spdt-slide-switch) | 1 | In the battery + lead. The XIAO has no off switch, so without one it drains the cell in the bag. |
| Battery connector (optional) | JST-PH 2-pin socket pigtail | 1 | Lets you swap the battery without desoldering. **Check polarity:** JST leads are not standardised between brands. |
| Wire and insulation | 26–28 AWG silicone wire, heat-shrink, Kapton tape | 3 | Silicone wire survives flexing. Kapton between board and battery. |
| Case | 3D-printed, or a small ABS box (~35 × 25 × 12 mm) | 0–5 | Must hold the board **rigidly**: loose mounting blurs the tap spike. Foam only around the battery. |
| Strap | 20–25 mm elastic hook-and-loop strap, or a shoe-lace clip | 3–5 | Goes on top of the shoe or the ankle, never under the foot. |
| Cable | USB-C data cable | — | For flashing, charging and the wired USB MIDI fallback. |

Total about £45 for two sensors, plus a soldering iron if you don't have one.

Wiring: battery − → BAT− pad. Battery + → switch → BAT+ pad. The pads are on the underside of
the board, next to the USB-C socket. Check the + and − markings before soldering: reversing a
LiPo can kill the charger.

Robustness:

- Fix the board to the case with hot glue or a screw boss, not foam tape. Pad the battery so a
  stomp can't crush or puncture it.
- Put strain relief on the battery wires (a dab of hot glue where they meet the pads).
- Leave the USB-C socket reachable through the case wall, and make the switch reachable too.
- A conformal coat or a snug case lip keeps out sweat and stage dust.
- **Don't drive P0.14 (VBAT_ENABLE) HIGH while charging.** Seeed warns this can damage P0.31.
  The firmware doesn't touch it today. Keep that in mind if battery reporting is added.

## Checking the connection interval

On the Pixel: Developer options → *Enable Bluetooth HCI snoop log*, toggle Bluetooth, connect,
tap a few times, then `adb bugreport`. Open `FS/data/misc/bluetooth/logs/btsnoop_hci.log` in
Wireshark and filter for `bthci_evt.le_meta_subevent == 0x03` (LE Connection Update Complete).
The interval is shown in 1.25 ms units: 6 is 7.5 ms and 9 is 11.25 ms.

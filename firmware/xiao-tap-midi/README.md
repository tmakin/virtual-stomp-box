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

## Hardware

- XIAO nRF52840 Sense (£15.90 at The Pi Hut, 4 g).
- 3.7 V LiPo, 100–150 mAh, soldered to the BAT+/BAT− pads on the underside. The board charges it
  over USB-C.
- A 3D-printed or heat-shrink case, and a velcro strap or shoe-lace clip. Mount the board flat
  on the top of the shoe for the sharpest spike.

Battery life is not measured yet. Expect several mA while connected and polling the IMU, so a
100 mAh cell should last well over the 4 h target. Measure before a gig.

## Checking the connection interval

On the Pixel: Developer options → *Enable Bluetooth HCI snoop log*, toggle Bluetooth, connect,
tap a few times, then `adb bugreport`. Open `FS/data/misc/bluetooth/logs/btsnoop_hci.log` in
Wireshark and filter for `bthci_evt.le_meta_subevent == 0x03` (LE Connection Update Complete).
The interval is shown in 1.25 ms units: 6 is 7.5 ms and 9 is 11.25 ms.

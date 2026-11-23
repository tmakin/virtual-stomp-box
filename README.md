# virtual-stomp-box

Research and prototype code for a foot-worn stomp sensor for the
[Stomp Box](https://github.com/tmakin/bitxo/tree/ccr-de2fcc85-4242zp/stompbox) Android app.

**Current plan: WitMotion WT901 BLE sensor, strapped to the shoe or the leg.**

- **[docs/wt901-design.md](docs/wt901-design.md)**: the design. It covers how orientation and
  acceleration are used, the shoe and leg modes, calibration, the latency/jitter trade-off,
  debugging and tuning, and how to add it to the app.
- **[android](android)**: Kotlin code to drop into the app.
  - WT901 protocol parser, sample clock, stomp detector, recording format and replay tool.
    All unit-tested (`gradle test`).
  - BLE source, engine and tuning screen in `android-only/`. Not compiled yet.
- **[tools](tools)**:
  - latency model;
  - synthetic log generator;
  - plot script for replay traces.

Earlier work (superseded, kept for reference):
[docs/leg-sensor-research.md](docs/leg-sensor-research.md) compared sensors and recommended a
XIAO nRF52840 board with custom firmware ([firmware/xiao-tap-midi](firmware/xiao-tap-midi)).
The MIDI parsers and `LatencyProbe` in `android/` come from that work. `LatencyProbe` is still
used, for the microphone latency measurement.

Nothing here has been run on hardware yet, and every latency figure is modeled. A recording made
with the tuning screen's "with mic" option measures the real numbers.

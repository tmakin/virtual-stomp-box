# virtual-stomp-box

Research and prototype code for a leg- or shoe-worn tap sensor for the
[Stomp Box](https://github.com/tmakin/bitxo/tree/ccr-de2fcc85-4242zp/stompbox) Android app.

- **[docs/leg-sensor-research.md](docs/leg-sensor-research.md)**: the answer: comparison table,
  latency model, recommendation (XIAO nRF52840 Sense with our own firmware), test plan.
- **[firmware/xiao-tap-midi](firmware/xiao-tap-midi)**: on-board tap detection, sends BLE MIDI
  and USB MIDI.
- **[android](android)**: `MidiTapSource` / `GattMidiTapSource` input sources and the
  `LatencyProbe` mic-referenced latency test, ready to drop into the app.
- **[tools/ble_latency_model.py](tools/ble_latency_model.py)**: Monte Carlo model of
  impact → app delay for different links and connection intervals.

Nothing here has been run on hardware yet. All latency figures are modeled until the probe has
been run on a Pixel 8.

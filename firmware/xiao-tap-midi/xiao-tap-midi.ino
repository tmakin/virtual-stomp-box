// Stomp Box foot sensor: Seeed XIAO nRF52840 Sense
//
// Detects foot taps on the board and sends ONE MIDI note-on per tap, over
//   * BLE MIDI (standard service, so Android's android.media.midi opens it with no vendor SDK), and
//   * USB MIDI at the same time when the USB-C cable is plugged in (wired fallback, ~1 ms polling).
//
// Board package : "Seeed nRF52 Boards" (Adafruit Bluefruit based, NOT the "mbed-enabled" package)
//                 board = "Seeed XIAO nRF52840 Sense"
// Libraries     : Seeed Arduino LSM6DS3 (Seeed_Arduino_LSM6DS3), Adafruit TinyUSB (bundled with the core)
//
// Not yet compiled or run on hardware. See firmware/xiao-tap-midi/README.md.
//
// Latency path: IMU at 1.66 kHz -> software threshold on the first sample over the line
// (same algorithm as the app's AccelTapDetector) -> BLE notification queued immediately.
// The BLE MIDI timestamp carries the sample time of the impact, so a receiver that honours
// BLE MIDI timestamps can remove the connection-interval jitter by scheduling the note.

#include <Arduino.h>
#include <Wire.h>
#include <bluefruit.h>
#include <Adafruit_TinyUSB.h>
#include "LSM6DS3.h"

// ----------------------------------------------------------------------------------------------
// Settings
// ----------------------------------------------------------------------------------------------
static const char*   DEVICE_NAME     = "StompSensor";
static const uint8_t MIDI_CHANNEL    = 9;      // 0-based; 9 = channel 10 (drums)
static const uint8_t MIDI_NOTE       = 36;     // C1 / GM kick. The app ignores the pitch and plays its own pattern.
static const uint32_t REFRACTORY_US  = 90000;  // dead time after a tap (matches AccelTapDetector)
static const float   GRAVITY_TAU_S   = 0.15f;  // high-pass time constant for gravity / slow motion
static float         sensitivity     = 0.5f;   // 0 = only hard stomps, 1 = lightest tap. CC 1 over MIDI sets it.
#define ENABLE_USB_MIDI 1

// BLE connection interval request, in 1.25 ms units. 6 = 7.5 ms (BLE minimum).
// Android's stack usually clamps to 9 (11.25 ms) and has been reported to REJECT requests whose
// max is below 11.25 ms, so ask for 7.5..11.25 ms: we get 7.5 if the phone allows it, else 11.25.
static const uint16_t CONN_INT_MIN = 6;
static const uint16_t CONN_INT_MAX = 9;

// ----------------------------------------------------------------------------------------------
// BLE MIDI service (UUIDs from the MIDI Association BLE-MIDI spec, little-endian byte order)
// 03B80E5A-EDE8-4B33-A751-6CE34EC4C700 / 7772E5DB-3868-4112-A1A9-F2669D106BF3
// ----------------------------------------------------------------------------------------------
static const uint8_t UUID_MIDI_SVC[16] = {0x00, 0xC7, 0xC4, 0x4E, 0xE3, 0x6C, 0x51, 0xA7,
                                          0x33, 0x4B, 0xE8, 0xED, 0x5A, 0x0E, 0xB8, 0x03};
static const uint8_t UUID_MIDI_IO[16]  = {0xF3, 0x6B, 0x10, 0x9D, 0x66, 0xF2, 0xA9, 0xA1,
                                          0x12, 0x41, 0x68, 0x38, 0xDB, 0xE5, 0x72, 0x77};

BLEService        midiSvc(UUID_MIDI_SVC);
BLECharacteristic midiIo(UUID_MIDI_IO);
BLEDis            bledis;
BLEBas            blebas;

#if ENABLE_USB_MIDI
Adafruit_USBD_MIDI usbMidi;
#endif

// ----------------------------------------------------------------------------------------------
// IMU: LSM6DS3TR-C at 0x6A, ±16 g, 1.66 kHz, gyro off
// ----------------------------------------------------------------------------------------------
LSM6DS3 imu(I2C_MODE, 0x6A);
static const uint8_t REG_STATUS   = 0x1E;  // bit 0 = XLDA (new accel sample)
static const uint8_t REG_OUTX_L_XL = 0x28;
static const float   LSB_TO_MS2   = 0.488e-3f * 9.80665f;  // ±16 g full scale

// ----------------------------------------------------------------------------------------------
// Tap detector: port of app/.../detect/AccelTapDetector.kt
// ----------------------------------------------------------------------------------------------
struct TapDetector {
  float g[3] = {0, 0, 0};
  bool initialised = false;
  bool armed = true;
  uint32_t lastUs = 0;
  uint32_t lastTapUs = 0;
  bool anyTap = false;
  float noiseFloor = 0.05f;

  // Returns velocity 0.05..1 on a new tap, or a negative number if none.
  float process(float x, float y, float z, uint32_t tUs) {
    if (!initialised) {
      g[0] = x; g[1] = y; g[2] = z;
      lastUs = tUs;
      initialised = true;
      return -1;
    }
    float dt = (float)(uint32_t)(tUs - lastUs) * 1e-6f;
    if (dt > 0.1f) dt = 0.1f;
    lastUs = tUs;

    float hx = x - g[0], hy = y - g[1], hz = z - g[2];
    float mag = sqrtf(hx * hx + hy * hy + hz * hz);
    float alpha = 1.0f - expf(-dt / GRAVITY_TAU_S);
    g[0] += alpha * hx; g[1] += alpha * hy; g[2] += alpha * hz;

    float s = constrain(sensitivity, 0.0f, 1.0f);
    float minThr = 6.0f * expf(logf(0.25f / 6.0f) * s);  // 6 m/s² .. 0.25 m/s²
    float thr = max(minThr, noiseFloor * 6.0f);
    bool outOfDeadTime = !anyTap || (uint32_t)(tUs - lastTapUs) > REFRACTORY_US;

    if (armed && mag > thr && outOfDeadTime) {
      armed = false;
      anyTap = true;
      lastTapUs = tUs;
      float v = 0.35f + 0.65f * (logf(mag / thr) / logf(8.0f));
      return constrain(v, 0.05f, 1.0f);
    }
    if (!armed) {
      if (mag < thr * 0.5f && outOfDeadTime) armed = true;
    } else {
      noiseFloor += 0.002f * (mag - noiseFloor);
    }
    return -1;
  }
};
TapDetector detector;

// ----------------------------------------------------------------------------------------------
// MIDI output
// ----------------------------------------------------------------------------------------------
static void sendNote(uint8_t velocity, uint32_t impactMs) {
  const uint8_t status = 0x90 | MIDI_CHANNEL;

  // BLE MIDI packet: header (bit7 + ts[12:7]), timestamp byte (bit7 + ts[6:0]), then the message.
  if (Bluefruit.connected() && midiIo.notifyEnabled()) {
    uint16_t ts = impactMs & 0x1FFF;
    uint8_t pkt[5] = {(uint8_t)(0x80 | ((ts >> 7) & 0x3F)), (uint8_t)(0x80 | (ts & 0x7F)),
                      status, MIDI_NOTE, velocity};
    midiIo.notify(pkt, sizeof(pkt));
  }
#if ENABLE_USB_MIDI
  if (TinyUSBDevice.mounted()) {
    uint8_t usb[4] = {0x09, status, MIDI_NOTE, velocity};  // cable 0, CIN 0x9 = note-on
    usbMidi.writePacket(usb);
  }
#endif
  // Note-off is not needed for a one-shot sound, and sending it would cost a second
  // BLE packet right behind the note-on. Receivers that want it can ignore its absence.
}

// A host may write MIDI to us: CC 1 (mod wheel) sets the sensitivity 0..127.
static void onMidiWrite(uint16_t, BLECharacteristic*, uint8_t* data, uint16_t len) {
  // Skip header + timestamp bytes; look for B0..BF 01 vv anywhere in the packet.
  for (uint16_t i = 1; i + 2 < len; i++) {
    if ((data[i] & 0xF0) == 0xB0 && data[i + 1] == 1 && data[i + 2] < 0x80) {
      sensitivity = data[i + 2] / 127.0f;
      return;
    }
  }
}

static void onConnect(uint16_t conn) {
  // Ask for a 7.5..11.25 ms interval with no peripheral latency (we must answer every event).
  // Bluefruit's requestConnectionParameter() sets min == max, which Android may reject, so call
  // the SoftDevice directly with a range.
  ble_gap_conn_params_t p;
  p.min_conn_interval = CONN_INT_MIN;
  p.max_conn_interval = CONN_INT_MAX;
  p.slave_latency = 0;
  p.conn_sup_timeout = 400;  // 4 s, units of 10 ms
  sd_ble_gap_conn_param_update(conn, &p);
  Bluefruit.Connection(conn)->requestPHY();  // 2M PHY if the phone supports it: less airtime
}

// ----------------------------------------------------------------------------------------------
static bool readAccel(float& x, float& y, float& z) {
  uint8_t st = 0;
  if (imu.readRegister(&st, REG_STATUS) != 0 || !(st & 0x01)) return false;
  uint8_t b[6];
  if (imu.readRegisterRegion(b, REG_OUTX_L_XL, 6) != 0) return false;
  x = (int16_t)(b[0] | (b[1] << 8)) * LSB_TO_MS2;
  y = (int16_t)(b[2] | (b[3] << 8)) * LSB_TO_MS2;
  z = (int16_t)(b[4] | (b[5] << 8)) * LSB_TO_MS2;
  return true;
}

void setup() {
#if ENABLE_USB_MIDI
  usbMidi.setStringDescriptor("StompSensor USB");
  usbMidi.begin();
#endif

#ifdef PIN_LSM6DS3TR_C_POWER
  pinMode(PIN_LSM6DS3TR_C_POWER, OUTPUT);
  digitalWrite(PIN_LSM6DS3TR_C_POWER, HIGH);
  delay(10);
#endif
  imu.settings.gyroEnabled = 0;
  imu.settings.accelEnabled = 1;
  imu.settings.accelRange = 16;        // ±16 g
  imu.settings.accelSampleRate = 1660; // Hz
  imu.settings.accelBandWidth = 400;   // anti-alias filter
  imu.begin();
  Wire.setClock(400000);

  Bluefruit.configPrphBandwidth(BANDWIDTH_MAX);
  Bluefruit.begin();
  Bluefruit.setName(DEVICE_NAME);
  Bluefruit.setTxPower(4);
  Bluefruit.Periph.setConnInterval(CONN_INT_MIN, CONN_INT_MAX);
  Bluefruit.Periph.setConnectCallback(onConnect);

  bledis.setManufacturer("neatlines");
  bledis.setModel("StompSensor XIAO");
  bledis.begin();
  blebas.begin();
  blebas.write(100);

  midiSvc.begin();
  midiIo.setProperties(CHR_PROPS_READ | CHR_PROPS_WRITE_WO_RESP | CHR_PROPS_NOTIFY);
  midiIo.setPermission(SECMODE_OPEN, SECMODE_OPEN);
  midiIo.setMaxLen(20);
  midiIo.setWriteCallback(onMidiWrite);
  midiIo.begin();  // value starts empty: BLE-MIDI requires reads to return no payload

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(midiSvc);  // Android filters scans on the MIDI service UUID
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);   // fast then slow, units of 0.625 ms
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  pinMode(LED_BLUE, OUTPUT);
  digitalWrite(LED_BLUE, HIGH);  // active low: off
}

void loop() {
  float x, y, z;
  if (!readAccel(x, y, z)) return;  // poll until the next 1.66 kHz sample is ready
  uint32_t tUs = micros();
  float v = detector.process(x, y, z, tUs);
  if (v > 0) {
    uint8_t vel = (uint8_t)constrain((int)(v * 127.0f + 0.5f), 1, 127);
    sendNote(vel, millis());
    digitalWrite(LED_BLUE, LOW);
  } else if ((uint32_t)(tUs - detector.lastTapUs) > 30000) {
    digitalWrite(LED_BLUE, HIGH);
  }
}

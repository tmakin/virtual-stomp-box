#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["pyserial"]
# ///
"""Read the WT901 over its USB-C cable and show whether its frames come one at a time or in
bursts, plus its firmware version. Works on macOS (and Linux / Windows).

Over Bluetooth the sensor sends ~8 frames every ~44 ms at 200 Hz. If the cable delivers them one
at a time (~5 ms apart), the bursts are made only on the Bluetooth side, so a firmware change
there could save ~40 ms.

    uv run tools/wit_usb_probe.py              # finds the port, sets 200 Hz (not saved), reads 10 s
    uv run tools/wit_usb_probe.py --port /dev/cu.usbserial-1410 --seconds 20 --rate 100
    uv run tools/wit_usb_probe.py --no-rate    # leave the rate as it is

Turn the sensor on and plug it in with a data cable. macOS has a built-in driver for the CH340
USB-serial chip: the port shows up as /dev/cu.usbserial-* (or /dev/cu.wchusbserial-* with WCH's
driver). If nothing shows up, the cable may be charge-only, or the port may not carry data.
The rate change is not saved; disconnecting or the phone app's next connect resets it.
"""
import argparse, glob, statistics, sys, time

RATE_CODES = {10: 0x06, 20: 0x07, 50: 0x08, 100: 0x09, 200: 0x0B}


def cmd(reg, value=0):
    return bytes([0xFF, 0xAA, reg, value & 0xFF, (value >> 8) & 0xFF])


UNLOCK = cmd(0x69, 0xB588)
READ = lambda reg: cmd(0x27, reg)


def parse(buf):
    """Split buf into frames. Returns (frames, leftover). A frame is (kind, bytes):
    'ble' = 20-byte 55 61 data frame, 'reply' = 20-byte 55 71 register reply,
    'serial' = 11-byte 55 5x frame with a valid checksum."""
    out, i = [], 0
    while i + 1 < len(buf):
        if buf[i] != 0x55:
            i += 1; continue
        t = buf[i + 1]
        if t in (0x61, 0x71):
            if i + 20 > len(buf): break
            out.append(("ble" if t == 0x61 else "reply", bytes(buf[i:i + 20]))); i += 20
        elif 0x50 <= t <= 0x5F:
            if i + 11 > len(buf): break
            f = buf[i:i + 11]
            if sum(f[:10]) & 0xFF == f[10]:
                out.append(("serial", bytes(f))); i += 11
            else:
                i += 1
        else:
            i += 1
    return out, buf[i:]


def find_port():
    ports = sorted(glob.glob("/dev/cu.usbserial*") + glob.glob("/dev/cu.wchusbserial*")
                   + glob.glob("/dev/ttyUSB*"))
    if not ports:
        try:
            from serial.tools import list_ports
            ports = [p.device for p in list_ports.comports() if p.vid == 0x1A86]  # WCH (CH340)
        except Exception:
            pass
    return ports


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port"); ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--rate", type=int, default=200, choices=sorted(RATE_CODES))
    ap.add_argument("--no-rate", action="store_true", help="don't change the output rate")
    ap.add_argument("--gap-ms", type=float, default=2.0,
                    help="reads closer than this count as one burst (USB delivery jitter)")
    a = ap.parse_args()
    import serial

    port = a.port
    if not port:
        ports = find_port()
        if not ports:
            sys.exit("No USB serial port found. Is the sensor on, and is the cable a data cable?")
        port = ports[0]
        if len(ports) > 1: print("Ports found:", ", ".join(ports), "- using", port)
    s = serial.Serial(port, 115200, timeout=0)
    print("Opened", port, "at 115200")

    def send(b):
        s.write(b); s.flush(); time.sleep(0.1)

    buf = bytearray()
    version = {}
    send(READ(0x2E))
    if not a.no_rate:
        send(UNLOCK); send(cmd(0x03, RATE_CODES[a.rate]))
        print(f"Set output rate to {a.rate} Hz (not saved)")

    reads = []  # (time, frames in this read)
    kinds = {}
    t0 = time.perf_counter(); end = t0 + a.seconds
    while time.perf_counter() < end:
        chunk = s.read(4096)
        now = time.perf_counter()
        if not chunk:
            time.sleep(0.0005); continue
        buf += chunk
        frames, rest = parse(buf); buf = bytearray(rest)
        n = 0
        for kind, f in frames:
            kinds[kind] = kinds.get(kind, 0) + 1
            if kind == "reply":
                start = f[2] | (f[3] << 8)
                if start == 0x2E and not version:
                    version = {"0x2E": f[4] | (f[5] << 8), "0x2F": f[6] | (f[7] << 8)}
            elif kind == "ble" or (kind == "serial" and f[1] == 0x51):  # one per sample
                n += 1
        if n: reads.append((now, n))
    s.close()

    print("Frames seen:", kinds or "none")
    if version:
        print("Firmware version registers: 0x2E = 0x%04X, 0x2F = 0x%04X (%d.%d)" % (
            version["0x2E"], version["0x2F"], version["0x2E"], version["0x2F"]))
    else:
        print("No reply to the version read (the port may not accept commands).")
    if len(reads) < 3:
        sys.exit("Too few data frames to judge. Is the sensor streaming over the cable?")

    # Group reads that arrive within gap-ms of each other into one burst.
    bursts = []
    for t, n in reads:
        if bursts and (t - bursts[-1][0]) * 1000 < a.gap_ms:
            bursts[-1][1] += n; bursts[-1][0] = t
        else:
            bursts.append([t, n])
    span = reads[-1][0] - reads[0][0]
    samples = sum(n for _, n in reads)
    gaps = [(b[0] - a_[0]) * 1000 for a_, b in zip(bursts, bursts[1:])]
    per = [n for _, n in bursts]
    gaps.sort()
    p95 = gaps[int(0.95 * (len(gaps) - 1))]
    print(f"Samples: {samples} in {span:.1f} s = {samples / span:.1f} Hz")
    print(f"Bursts: {len(bursts)}, samples per burst median {statistics.median(per):g} "
          f"(max {max(per)}), gap median {statistics.median(gaps):.1f} ms, p95 {p95:.1f} ms")
    med = statistics.median(per)
    if med <= 2:
        print("=> Frames arrive (nearly) one at a time over USB: the ~44 ms bursts are made on the "
              "Bluetooth side.")
    elif statistics.median(gaps) > 30:
        print("=> Frames arrive in bursts over USB too: the batching happens before the Bluetooth "
              "side, so patching the Bluetooth send path alone wouldn't help.")
    else:
        print("=> Small bursts: probably USB-serial buffering; try --gap-ms 1 and compare.")


if __name__ == "__main__":
    main()

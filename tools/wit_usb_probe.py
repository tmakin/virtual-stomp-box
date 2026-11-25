#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["pyserial"]
# ///
"""Read the WT901 over its USB-C cable and show whether its frames come one at a time or in
bursts, how evenly they arrive, and its firmware version. Works on macOS (and Linux / Windows).

Over Bluetooth the sensor sends ~8 frames every ~44 ms at 200 Hz. If the cable delivers them one
at a time (~5 ms apart), the bursts are made only on the Bluetooth side, so a firmware change
there could save ~40 ms.

    uv run tools/wit_usb_probe.py              # finds the port, sets 200 Hz (not saved), reads 10 s
    uv run tools/wit_usb_probe.py --port /dev/cu.usbserial-1410 --seconds 20 --rate 100
    uv run tools/wit_usb_probe.py --no-rate    # leave the rate as it is
    uv run tools/wit_usb_probe.py --csv frames.csv          # also log every frame
    uv run tools/wit_usb_probe.py --dump-registers regs.csv # read-only dump of 0x00-0xFF

Turn the sensor on and plug it in with a data cable. macOS has a built-in driver for the CH340
USB-serial chip: the port shows up as /dev/cu.usbserial-* (or /dev/cu.wchusbserial-* with WCH's
driver). If nothing shows up, the cable may be charge-only, or the port may not carry data.
The rate change is not saved; disconnecting or the phone app's next connect resets it.

The only writes this script sends are UNLOCK and the rate (0x03). It never sends SAVE.
"""
import argparse, csv, glob, statistics, sys, time

RATE_CODES = {10: 0x06, 20: 0x07, 50: 0x08, 100: 0x09, 200: 0x0B}
WARMUP_S = 0.5  # the sensor dumps a backlog of frames at start; ignore this much


def cmd(reg, value=0):
    return bytes([0xFF, 0xAA, reg, value & 0xFF, (value >> 8) & 0xFF])


UNLOCK = cmd(0x69, 0xB588)


def read_cmd(reg):
    return cmd(0x27, reg)


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
            continue
        if not 0x50 <= t <= 0x5F:
            i += 1; continue
        if i + 11 > len(buf): break
        f = buf[i:i + 11]
        if sum(f[:10]) & 0xFF == f[10]:
            out.append(("serial", bytes(f))); i += 11
        else:
            i += 1
    return out, buf[i:]


def is_sample(kind, f):
    """One frame per sample: the 20-byte data frame, or the 11-byte acceleration frame."""
    return kind == "ble" or (kind == "serial" and f[1] == 0x51)


def reply_words(f):
    """A 55 71 reply: (start register, [8 unsigned 16-bit values])."""
    start = f[2] | (f[3] << 8)
    return start, [f[4 + 2 * k] | (f[5 + 2 * k] << 8) for k in range(8)]


def find_port():
    ports = sorted(glob.glob("/dev/cu.usbserial*") + glob.glob("/dev/cu.wchusbserial*")
                   + glob.glob("/dev/ttyUSB*"))
    if ports:
        return ports
    try:
        from serial.tools import list_ports
        return [p.device for p in list_ports.comports() if p.vid == 0x1A86]  # WCH (CH340)
    except Exception:
        return []


def open_port(port):
    import serial
    if not port:
        ports = find_port()
        if not ports:
            sys.exit("No USB serial port found. Is the sensor on, and is the cable a data cable?")
        port = ports[0]
        if len(ports) > 1: print("Ports found:", ", ".join(ports), "- using", port)
    s = serial.Serial(port, 115200, timeout=0)
    print("Opened", port, "at 115200")
    return s


def send(s, b, wait=0.1):
    s.write(b); s.flush(); time.sleep(wait)


def set_rate(s, rate):
    send(s, UNLOCK); send(s, cmd(0x03, RATE_CODES[rate]))
    print(f"Set output rate to {rate} Hz (not saved)")


def usb_frames(s, seconds, stop=None):
    """Yield (read time, kind, frame) for `seconds`. Frames from one read share its time.
    Input is flushed first and the first WARMUP_S seconds are dropped (start-up backlog)."""
    s.reset_input_buffer()
    buf = bytearray()
    t0 = time.perf_counter(); end = t0 + WARMUP_S + seconds
    while time.perf_counter() < end and not (stop and stop.is_set()):
        chunk = s.read(4096)
        now = time.perf_counter()
        if not chunk:
            time.sleep(0.0002); continue
        buf += chunk
        frames, rest = parse(buf); buf = bytearray(rest)
        if now - t0 < WARMUP_S:
            continue
        for kind, f in frames:
            yield now, kind, f


def pct(xs, p):
    xs = sorted(xs)
    return xs[int(p * (len(xs) - 1))]


def group_reads(frames):
    """[(t, n samples)] per serial read, from (t, kind, frame) records."""
    reads = []
    for t, kind, f in frames:
        if not is_sample(kind, f): continue
        if reads and reads[-1][0] == t:
            reads[-1][1] += 1
        else:
            reads.append([t, 1])
    return reads


def group_bursts(reads, gap_ms):
    """Merge reads closer than gap_ms into one burst."""
    bursts = []
    for t, n in reads:
        if bursts and (t - bursts[-1][0]) * 1000 < gap_ms:
            bursts[-1][1] += n; bursts[-1][0] = t
        else:
            bursts.append([t, n])
    return bursts


def report_bursts(reads, gap_ms):
    bursts = group_bursts(reads, gap_ms)
    span = reads[-1][0] - reads[0][0]
    samples = sum(n for _, n in reads)
    gaps = [(b[0] - a[0]) * 1000 for a, b in zip(bursts, bursts[1:])]
    per = [n for _, n in bursts]
    print(f"Samples: {samples} in {span:.1f} s = {samples / span:.1f} Hz")
    print(f"Bursts: {len(bursts)}, samples per burst median {statistics.median(per):g} "
          f"(max {max(per)}), gap median {statistics.median(gaps):.1f} ms, "
          f"p95 {pct(gaps, 0.95):.1f} ms")
    if statistics.median(per) <= 2:
        print("=> Frames arrive (nearly) one at a time over USB: the ~44 ms bursts are made on the "
              "Bluetooth side.")
    elif statistics.median(gaps) > 30:
        print("=> Frames arrive in bursts over USB too: the batching happens before the Bluetooth "
              "side, so patching the Bluetooth send path alone wouldn't help.")
    else:
        print("=> Small bursts: probably USB-serial buffering; try --gap-ms 1 and compare.")


def report_jitter(reads, rate):
    """Spread of the interval between consecutive single-frame reads, against 1 / rate."""
    iv = [(b[0] - a[0]) * 1000 for a, b in zip(reads, reads[1:]) if a[1] == 1 and b[1] == 1]
    if len(iv) < 10:
        print("Jitter: too few single-frame reads to judge"); return
    nominal = 1000 / rate if rate else statistics.median(iv)
    dev = [abs(x - nominal) for x in iv]
    print(f"Single-frame arrival interval (n={len(iv)}): median {statistics.median(iv):.2f} ms, "
          f"sd {statistics.pstdev(iv):.2f}, p5 {pct(iv, 0.05):.2f}, p95 {pct(iv, 0.95):.2f}, "
          f"max {max(iv):.2f}; |dev from {nominal:.2f}| p95 {pct(dev, 0.95):.2f} ms")


def write_csv(path, frames):
    t0 = frames[0][0]
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["t_ms", "kind", "hex"])
        for t, kind, f in frames:
            w.writerow([f"{(t - t0) * 1000:.3f}", kind, f.hex()])
    print(f"Wrote {len(frames)} frames to {path}")


def report_version(frames):
    for _, kind, f in frames:
        if kind != "reply": continue
        start, words = reply_words(f)
        if start != 0x2E: continue
        print("Firmware version registers: 0x2E = 0x%04X, 0x2F = 0x%04X" % (words[0], words[1]))
        return
    print("No reply to the version read (the port may not accept commands).")


def probe(s, a):
    if not a.no_rate:
        set_rate(s, a.rate)
    frames = []
    started = False
    for rec in usb_frames(s, a.seconds):
        frames.append(rec)
        if not started:  # ask for the version once the warm-up is over
            s.write(read_cmd(0x2E)); started = True
    kinds = {}
    for _, kind, _f in frames:
        kinds[kind] = kinds.get(kind, 0) + 1
    print(f"Frames seen (after flushing and dropping the first {WARMUP_S} s):", kinds or "none")
    report_version(frames)
    if a.csv:
        write_csv(a.csv, frames)
    reads = group_reads(frames)
    if len(reads) < 3:
        sys.exit("Too few data frames to judge. Is the sensor streaming over the cable?")
    report_bursts(reads, a.gap_ms)
    report_jitter(reads, None if a.no_rate else a.rate)


def collect_replies(s, seconds):
    """All register replies seen in the next `seconds` (data frames are dropped)."""
    buf = bytearray(); out = []
    end = time.perf_counter() + seconds
    while time.perf_counter() < end:
        chunk = s.read(4096)
        if not chunk:
            time.sleep(0.001); continue
        buf += chunk
        frames, rest = parse(buf); buf = bytearray(rest)
        out += [reply_words(f) for kind, f in frames if kind == "reply"]
    return out


def dump_registers(s, path, gap):
    """Read-only: send FF AA 27 <reg> 00 for every register and record the replies."""
    s.reset_input_buffer()
    first = {}    # reg -> value from the reply that starts at reg
    seen = {}     # reg -> set of values seen in any reply (each covers 8 registers)
    echo_bad = []
    for reg in range(0x100):
        s.write(read_cmd(reg)); s.flush()
        replies = collect_replies(s, gap)
        for start, words in replies:
            for k, v in enumerate(words):
                seen.setdefault(start + k, set()).add(v)
        match = [w for st, w in replies if st == reg]
        if match:
            first[reg] = match[0][0]
        else:
            echo_bad.append((reg, [st for st, _ in replies]))
        print(f"\r0x{reg:02X}", end="", flush=True)
    print()
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["reg", "hex", "int16", "other_values_seen"])
        for reg in range(0x100):
            v = first.get(reg)
            others = sorted(seen.get(reg, set()) - {v})
            w.writerow([f"0x{reg:02X}", "" if v is None else f"0x{v:04X}",
                        "" if v is None else (v - 0x10000 if v & 0x8000 else v),
                        " ".join(f"0x{o:04X}" for o in others)])
    print(f"Wrote {path}: {len(first)} of 256 registers answered")
    if echo_bad:
        print("No reply starting at the asked register for:",
              ", ".join(f"0x{r:02X}(got {['0x%02X' % x for x in got]})" for r, got in echo_bad[:20]),
              "..." if len(echo_bad) > 20 else "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port"); ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--rate", type=int, default=200, choices=sorted(RATE_CODES))
    ap.add_argument("--no-rate", action="store_true", help="don't change the output rate")
    ap.add_argument("--gap-ms", type=float, default=2.0,
                    help="reads closer than this count as one burst (USB delivery jitter)")
    ap.add_argument("--csv", help="write every frame (time, kind, hex) to this file")
    ap.add_argument("--dump-registers", metavar="CSV",
                    help="read every register 0x00-0xFF (reads only) and save a table")
    ap.add_argument("--read-gap", type=float, default=0.1,
                    help="seconds to wait for each register reply in --dump-registers")
    a = ap.parse_args()

    s = open_port(a.port)
    try:
        if a.dump_registers:
            dump_registers(s, a.dump_registers, a.read_gap)
        else:
            probe(s, a)
    finally:
        s.close()


if __name__ == "__main__":
    main()

#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["bleak", "pyserial"]
# ///
"""Measure the delay Bluetooth adds to each WT901 sample, against the USB-C cable.

The sensor is read over USB and Bluetooth at the same time. Each Bluetooth frame is matched to the
USB frame with the same 18 data bytes, and the script reports Bluetooth arrival minus USB arrival
(same Mac clock). Frames whose content repeats within 0.3 s are left out, because they can't be
matched for sure: move the sensor around a little during the run.

    uv run tools/wit_ble_vs_usb.py                     # 200 Hz, 20 s
    uv run tools/wit_ble_vs_usb.py --rate 100 --csv ble100.csv

The rate is set over USB (UNLOCK + 0x03, not saved). Nothing else is written.

macOS: the app running this needs Bluetooth access (System Settings > Privacy & Security >
Bluetooth). Disconnect the phone app first.
"""
import argparse, asyncio, csv, statistics, threading

from wit_ble_gatt import find_sensor
from wit_usb_probe import RATE_CODES, is_sample, open_port, parse, pct, set_rate, usb_frames

NOTIFY = "0000ffe4-0000-1000-8000-00805f9a34fb"
UNIQUE_WINDOW_S = 0.3


def usb_reader(s, stop, out):
    """Thread: append (time, 18 data bytes) for every USB data frame until stop is set."""
    for t, kind, f in usb_frames(s, 3600, stop):
        if is_sample(kind, f):
            out.append((t, f[2:20]))


async def read_ble(name, seconds):
    """[(time, [18-byte payloads])] per notification."""
    from bleak import BleakClient
    notes, buf = [], bytearray()

    def on_notify(_sender, data):
        nonlocal buf
        import time
        t = time.perf_counter()
        buf += data
        frames, rest = parse(buf); buf = bytearray(rest)
        notes.append((t, [f[2:20] for kind, f in frames if kind == "ble"]))

    dev = await find_sensor(name)
    async with BleakClient(dev) as client:
        await client.start_notify(NOTIFY, on_notify)
        await asyncio.sleep(1.0 + seconds)  # first second dropped below as warm-up
        await client.stop_notify(NOTIFY)
    t_start = notes[0][0] + 1.0 if notes else 0
    return [n for n in notes if n[0] >= t_start]


def unique_times(usb):
    """content -> [USB times], keeping only content that doesn't repeat within the window."""
    by = {}
    for t, d in usb:
        by.setdefault(d, []).append(t)
    out = {}
    for d, ts in by.items():
        if all(b - a > UNIQUE_WINDOW_S for a, b in zip(ts, ts[1:])):
            out[d] = ts
    return out


def match(notes, usb):
    """Rows: (t_ble, t_usb or None, notification index, position, frames in notification)."""
    times = unique_times(usb)
    rows = []
    for ni, (tb, frames) in enumerate(notes):
        for pos, d in enumerate(frames):
            near = [tu for tu in times.get(d, []) if abs(tb - tu) < UNIQUE_WINDOW_S]
            rows.append((tb, near[0] if len(near) == 1 else None, ni, pos, len(frames)))
    return rows


def summary(label, xs):
    if not xs: return f"{label}: none"
    return (f"{label}: median {statistics.median(xs):.1f} ms, p5 {pct(xs, 0.05):.1f}, "
            f"p95 {pct(xs, 0.95):.1f}, min {min(xs):.1f}, max {max(xs):.1f} (n={len(xs)})")


def report(notes, usb, rows, seconds):
    per = [len(f) for _, f in notes if f]
    gaps = [(b[0] - a[0]) * 1000 for a, b in zip(notes, notes[1:])]
    print(f"USB: {len(usb)} frames ({len(usb) / seconds:.1f} Hz over the run, incl. warm-up)")
    print(f"BLE: {sum(per)} frames in {len(notes)} notifications; frames per notification "
          f"median {statistics.median(per):g}, mean {statistics.mean(per):.2f}, max {max(per)}")
    print(summary("Notification interval", gaps))
    matched = [r for r in rows if r[1] is not None]
    print(f"Matched {len(matched)} of {len(rows)} BLE frames to a unique USB frame")
    delay = [(tb - tu) * 1000 for tb, tu, *_ in matched]
    print(summary("Delay BLE - USB, all frames", delay))
    print(summary("  first frame of each notification", [(r[0] - r[1]) * 1000 for r in matched if r[3] == 0]))
    print(summary("  last frame of each notification", [(r[0] - r[1]) * 1000 for r in matched if r[3] == r[4] - 1]))


def write_csv(path, rows):
    t0 = rows[0][0]
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["t_ble_ms", "t_usb_ms", "delay_ms", "notification", "pos", "frames_in_notification"])
        for tb, tu, ni, pos, n in rows:
            w.writerow([f"{(tb - t0) * 1000:.3f}", "" if tu is None else f"{(tu - t0) * 1000:.3f}",
                        "" if tu is None else f"{(tb - tu) * 1000:.3f}", ni, pos, n])
    print(f"Wrote {len(rows)} rows to {path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port"); ap.add_argument("--name", default="WT901")
    ap.add_argument("--seconds", type=float, default=20)
    ap.add_argument("--rate", type=int, default=200, choices=sorted(RATE_CODES))
    ap.add_argument("--csv", help="per BLE frame: arrival times, delay, place in its notification")
    a = ap.parse_args()

    s = open_port(a.port)
    usb, stop = [], threading.Event()
    reader = threading.Thread(target=usb_reader, args=(s, stop, usb), daemon=True)
    try:
        set_rate(s, a.rate)
        reader.start()
        notes = asyncio.run(read_ble(a.name, a.seconds))
    finally:
        stop.set(); reader.join(2); s.close()
    if not notes:
        raise SystemExit("No Bluetooth notifications arrived.")
    t_lo, t_hi = notes[0][0] - 1, notes[-1][0] + 1
    usb_during = [u for u in usb if t_lo <= u[0] <= t_hi]
    if len(usb_during) < 10:
        raise SystemExit(f"USB stopped while Bluetooth was connected ({len(usb_during)} frames): "
                         "the two links can't be timed together; time them separately.")
    rows = match(notes, usb_during)
    report(notes, usb_during, rows, t_hi - t_lo)
    if a.csv: write_csv(a.csv, rows)


if __name__ == "__main__":
    main()

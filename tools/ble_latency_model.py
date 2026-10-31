#!/usr/bin/env python3
"""Monte Carlo model of impact -> event-in-app latency for a foot sensor.

This is a MODEL, not a measurement. It exists to show which part of the delay
budget dominates (the BLE connection interval) and what to expect when the real
test is run. Every constant below is an assumption; change them once the
Pixel 8 numbers are measured.

Usage: python3 tools/ble_latency_model.py [--n 200000] [--seed 1]
"""
import argparse
import random
import statistics

# --- Assumptions (ms) -------------------------------------------------------
ODR_HZ = 1666            # LSM6DS3TR-C output data rate used by the firmware
FILTER_DELAY = 0.6       # IMU digital low-pass group delay, ~1 sample at 1.66 kHz
FW_DELAY = 0.2           # I2C read + detector + queue the notification
AIR_AND_CONTROLLER = 0.5 # packet on air + phone controller -> host (HCI)
# Android host stack -> app callback. Lognormal-ish: usually ~1.5 ms, long tail.
HOST_MEDIAN = {"gatt": 1.5, "midi": 2.5, "usb": 1.0}
HOST_TAIL_SIGMA = 0.35
PACKET_LOSS = 0.02       # chance a connection event is missed (interference): retry next event
USB_POLL_MS = 1.0        # full-speed USB MIDI endpoint, polled every 1 ms


def sample(link: str, ci_ms: float, rnd: random.Random) -> float:
    t = rnd.uniform(0, 1000 / ODR_HZ) + FILTER_DELAY + FW_DELAY
    if link == "usb":
        t += rnd.uniform(0, USB_POLL_MS)
    else:
        t += rnd.uniform(0, ci_ms)          # wait for the next connection event
        while rnd.random() < PACKET_LOSS:   # retransmit on the following event
            t += ci_ms
        t += AIR_AND_CONTROLLER
    t += rnd.lognormvariate(0, HOST_TAIL_SIGMA) * HOST_MEDIAN[link]
    return t


def pct(xs, p):
    return xs[min(len(xs) - 1, int(p / 100 * len(xs)))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=200_000)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    rows = [("usb", 0)] + [(l, ci) for l in ("gatt", "midi") for ci in (7.5, 11.25, 15, 30)]
    print(f"{'link':5} {'CI ms':>6} {'median':>7} {'p95':>6} {'p99':>6} {'p5-p95 spread':>14}")
    for link, ci in rows:
        xs = sorted(sample(link, ci, rnd) for _ in range(a.n))
        med, p5, p95, p99 = statistics.median(xs), pct(xs, 5), pct(xs, 95), pct(xs, 99)
        print(f"{link:5} {ci or '-':>6} {med:7.1f} {p95:6.1f} {p99:6.1f} {p95 - p5:9.1f} (±{(p95 - p5) / 2:.1f})")


if __name__ == "__main__":
    main()

#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = []
# ///
"""Monte Carlo model of impact -> event-in-app latency for a foot sensor.

This is a MODEL, not a measurement. It exists to show which part of the delay
budget dominates (the BLE connection interval) and what to expect when the real
test is run. Every constant below is an assumption; change them once the
Pixel 8 numbers are measured.

Usage: uv run tools/ble_latency_model.py [--n 200000] [--seed 1]
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


# --- WitMotion WT901 BLE (BWT901BLE5.0), detection on the phone -----------------
WT_ODR_HZ = 200          # fastest return rate
WT_FILTER = 1.5          # 188 Hz bandwidth setting: ~1-2 ms group delay (assumed)
WT_INTERNAL = 2.0        # MCU fusion + UART to the BLE module (20 bytes at 115200 baud ~1.7 ms), assumed
CLOCK_ERR_SD = 0.3       # SampleClock estimate error, from the unit-test simulation


def sample_wt901(ci_ms: float, rnd: random.Random, steady_ms: float = 0.0) -> float:
    """Impact -> sound decision. steady_ms > 0 models STEADY timing at that delay."""
    phase = rnd.uniform(0, 1000 / WT_ODR_HZ)  # impact lands somewhere inside a sample period
    t = phase + WT_FILTER + WT_INTERNAL
    if steady_ms:
        # Fires at estimated sample time + delay; the BLE wait drops out, only phase and clock error remain.
        return t + steady_ms + rnd.gauss(0, CLOCK_ERR_SD)
    t += rnd.uniform(0, ci_ms)
    while rnd.random() < PACKET_LOSS:
        t += ci_ms
    return t + AIR_AND_CONTROLLER + rnd.lognormvariate(0, HOST_TAIL_SIGMA) * HOST_MEDIAN["gatt"]


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
    print("\nWT901 BLE, 200 Hz, detection on the phone")
    for label, ci, steady in (("immediate", 11.25, 0), ("immediate", 15, 0), ("steady 20ms", 11.25, 20), ("steady 25ms", 15, 25)):
        xs = sorted(sample_wt901(ci, rnd, steady) for _ in range(a.n))
        med, p5, p95, p99 = statistics.median(xs), pct(xs, 5), pct(xs, 95), pct(xs, 99)
        print(f"{label:11} CI {ci:5} {med:7.1f} {p95:6.1f} {p99:6.1f} {p95 - p5:9.1f} (±{(p95 - p5) / 2:.1f})")


if __name__ == "__main__":
    main()

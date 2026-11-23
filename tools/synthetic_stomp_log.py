#!/usr/bin/env python3
"""Write a fake WT901 stomp log (the phone's export format) to try the replay tool without hardware.

Sensor flat on a shoe at 200 Hz, stomps every 0.5 s alternating flat / heel / toe, BLE delivery
at an 11.25 ms connection interval, and a mic onset at each true impact.

Usage: python3 tools/synthetic_stomp_log.py out.csv [--ci 11.25] [--seed 1]
"""
import argparse, math, random

ap = argparse.ArgumentParser()
ap.add_argument("out"); ap.add_argument("--ci", type=float, default=11.25); ap.add_argument("--seed", type=int, default=1)
a = ap.parse_args()
rnd = random.Random(a.seed)
period = 5_000_000
ci = int(a.ci * 1e6)
phase = rnd.randrange(ci)
t0 = 1_000_000_000_000
stomps = [(200 + i * 100, 2.0 + i % 4, (0.0, 14.0, -14.0)[i % 3]) for i in range(40)]
total = 200 + 40 * 100 + 200

with open(a.out, "w") as f:
    f.write("# stomp-log 1\n# config mode=SHOE feature=VERTICAL\n")
    f.write("# placement rest=0,0,1 conv=1,1 convErr=0.5 tilt=0,1,0\n# info rateHz=200 source=synthetic\n")
    f.write("type,t_ns,a,b,c,d,e,f,g,h,i\n")
    last = 0
    for n in range(total):
        pitch, spike = 0.0, 0.0
        for at, peak, p in stomps:
            k = n - at
            if -30 <= k <= 5: pitch = p
            if 0 <= k <= 15: spike += peak * math.exp(-k / 2) * (1 if k % 2 == 0 else -0.6)
            if k == 0: f.write(f"o,{t0 + n * period + rnd.randrange(300_000)}\n")
        r = math.radians(pitch)
        g = (-math.sin(r), 0.0, math.cos(r))
        acc = [x * (1 + spike) + rnd.uniform(-0.02, 0.02) for x in g]
        t = t0 + n * period
        ev = ((t - t0 - phase) // ci + 1) * ci + phase + t0
        while rnd.random() < 0.02: ev += ci
        arr = max(last, ev + 2_000_000 + rnd.randrange(300_000)); last = arr
        gyro = [rnd.uniform(-2, 2) for _ in range(3)]
        f.write("s,%d,%.4f,%.4f,%.4f,%.2f,%.2f,%.2f,0,%.2f,0\n" % (arr, *acc, *gyro, pitch))
print("wrote", a.out)

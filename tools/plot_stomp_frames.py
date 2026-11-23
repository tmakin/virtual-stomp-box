#!/usr/bin/env python3
"""Plot the per-sample trace written by the replay tool (--frames), to see why a stomp was
missed or double-triggered.

    ./gradlew :core:run --args="$PWD/stomp-log.csv --frames $PWD/frames.csv"
    python3 tools/plot_stomp_frames.py frames.csv [--from 10 --to 20] [--out plot.png]

Top: the feature and threshold, with triggers. Middle: vertical acceleration and jerk.
Bottom: tilt and gyro, plus the delay from sample to arrival (BLE jitter). Needs matplotlib.
"""
import argparse, csv
import matplotlib.pyplot as plt

ap = argparse.ArgumentParser()
ap.add_argument("frames"); ap.add_argument("--from", dest="t0", type=float, default=0)
ap.add_argument("--to", dest="t1", type=float, default=1e9); ap.add_argument("--out")
a = ap.parse_args()

rows = [r for r in csv.DictReader(open(a.frames)) if a.t0 <= float(r["sample_ms"]) / 1000 <= a.t1]
col = lambda k: [float(r[k]) for r in rows]
t = [x / 1000 for x in col("sample_ms")]
trig = [i for i, r in enumerate(rows) if r["triggered"] == "1"]

fig, ax = plt.subplots(3, 1, sharex=True, figsize=(12, 8))
ax[0].plot(t, col("feature"), lw=0.8, label="feature")
ax[0].plot(t, col("threshold"), "r--", lw=0.8, label="threshold")
ax[0].plot([t[i] for i in trig], [col("feature")[i] for i in trig], "o", color="orange", label="stomp")
ax[0].set_ylabel("g"); ax[0].legend(loc="upper right")
ax[1].plot(t, col("vertical"), lw=0.8, label="vertical − 1 g")
ax[1].plot(t, col("jerk"), lw=0.8, label="jerk")
ax[1].set_ylabel("g"); ax[1].legend(loc="upper right")
ax[2].plot(t, col("tilt"), lw=0.8, label="tilt °")
ax[2].plot(t, [g / 10 for g in col("gyro")], lw=0.8, label="gyro /10 °/s")
ax[2].plot(t, [b - s for s, b in zip(col("sample_ms"), col("arrival_ms"))], lw=0.8, label="arrival lag ms")
ax[2].set_xlabel("s"); ax[2].legend(loc="upper right")
fig.tight_layout()
plt.savefig(a.out, dpi=120) if a.out else plt.show()

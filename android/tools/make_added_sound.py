"""The sound of a recorded food: one dull, short knock (a knuckle on a wooden door), about 0.2 s.
Made from a damped, falling sine and a muffled click, so it is ours and licence-free.

Run it to make `app/src/main/res/raw/added.wav` again: python tools/make_added_sound.py (from the android folder)."""
import math
import os
import random
import struct
import wave

SR = 44100
random.seed(7)  # the same file every time

N = int(SR * 0.22)
buf = [0.0] * N

# the body: a low tone that drops a little as the wood settles, dying fast. A phone speaker has nothing below ~150 Hz,
# so it starts at 330 and settles at 190: low enough to be dull, high enough to be heard.
phase = 0.0
for i in range(N):
    t = i / SR
    f = 190 + 140 * math.exp(-t / 0.018)
    phase += 2 * math.pi * f / SR
    attack = min(1.0, t / 0.0015)
    body = math.sin(phase) + 0.30 * math.sin(2.3 * phase) * math.exp(-t / 0.02)  # a hollow overtone
    buf[i] += 0.9 * attack * math.exp(-t / 0.032) * body

# the contact: a few milliseconds of noise with everything bright taken out (one-pole low-pass at ~900 Hz)
a = 1 - math.exp(-2 * math.pi * 900 / SR)
lp = 0.0
for i in range(int(SR * 0.012)):
    t = i / SR
    lp += a * (random.uniform(-1, 1) - lp)
    buf[i] += 1.6 * lp * math.exp(-t / 0.003)

# fade the last 10 ms to zero, then peak at -5 dBFS
fade = int(SR * 0.010)
for i in range(fade):
    buf[-1 - i] *= i / fade
peak = max(abs(v) for v in buf)
scale = 0.56 / peak
data = b"".join(struct.pack("<h", int(max(-1.0, min(1.0, v * scale)) * 32767)) for v in buf)

path = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw", "added.wav"))
os.makedirs(os.path.dirname(path), exist_ok=True)
with wave.open(path, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes(data)

# a look at what was made: where its energy is, how fast it dies
s = [v * scale for v in buf]
dc = sum(s) / len(s)
rms_early = math.sqrt(sum(v * v for v in s[: int(SR * 0.03)]) / int(SR * 0.03))
rms_late = math.sqrt(sum(v * v for v in s[int(SR * 0.12):]) / (len(s) - int(SR * 0.12)))
# spectral centroid by a plain DFT on the first 60 ms at a few hundred bins
M = int(SR * 0.06)
seg = s[:M]
bins = []
for k in range(1, 120):
    f = k * 50
    re = sum(seg[n] * math.cos(2 * math.pi * f * n / SR) for n in range(0, M, 2))
    im = sum(seg[n] * math.sin(2 * math.pi * f * n / SR) for n in range(0, M, 2))
    bins.append((f, math.hypot(re, im)))
total = sum(m for _, m in bins)
centroid = sum(f * m for f, m in bins) / total
top = max(bins, key=lambda b: b[1])[0]
above2k = sum(m for f, m in bins if f > 2000) / total
print("wrote", path, round(N / SR, 3), "s,", len(data) + 44, "bytes, peak", round(peak * scale, 3), "dc", round(dc, 5))
print("loudest near", top, "Hz, centroid", round(centroid), "Hz, share above 2 kHz", round(above2k * 100, 1), "%")
print("rms first 30 ms %.3f, after 120 ms %.5f" % (rms_early, rms_late))

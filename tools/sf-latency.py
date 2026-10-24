# Frame pacing from `dumpsys SurfaceFlinger --latency <layer>` samples (one file or several).
# Usage: python3 tools/sf-latency.py samples.txt
import sys, collections
period = None; frames = set()
for path in sys.argv[1:]:
    for line in open(path, errors="replace"):
        p = line.split()
        if len(p) == 1 and p[0].isdigit() and period is None: period = int(p[0])
        if len(p) == 3 and all(x.isdigit() for x in p):
            a = int(p[1])
            if 0 < a < 2**62: frames.add(a)
t = sorted(frames)
if len(t) < 3: print("too few frames:", len(t)); sys.exit()
iv = [(b - a) for a, b in zip(t, t[1:])]
span = (t[-1] - t[0]) / 1e9
# gaps longer than 1 s are sampling holes (128-frame buffer overflowed between samples), skip them
real = [x for x in iv if x < 1e9]
holes = len(iv) - len(real)
vs = collections.Counter(round(x / period) for x in real)
print(f"refresh period {period/1e6:.2f} ms; frames {len(t)}; span {span:.1f}s; sampling holes {holes}")
print(f"mean fps over covered time {len(real)/(sum(real)/1e9):.1f}")
print("interval (vsyncs): count  ->", dict(sorted(vs.items())))
mode = vs.most_common(1)[0][0]
late = sum(c for k, c in vs.items() if k > mode)
print(f"typical interval {mode} vsync(s) = {1000*mode*period/1e9:.1f} ms -> content ~{1e9/(mode*period):.0f} fps")
print(f"intervals longer than typical (stutter): {late} of {len(real)} = {100*late/len(real):.1f}%")
big = sorted(real, reverse=True)[:8]
print("longest gaps ms:", [round(x/1e6) for x in big])

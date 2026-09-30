"""Real Slughorn preparation checks. No stand-in for a GPU rendering test."""
import math
import statistics
import struct
import subprocess
import sys
import time

exe = sys.argv[1]
subprocess.run([exe, "--self-test"], check=True)

def compile_path(source):
    start = time.perf_counter()
    result = subprocess.run([exe], input=source.encode(), capture_output=True, check=True)
    duration = time.perf_counter() - start
    raw = result.stdout
    assert len(raw) >= 96 and len(raw) % 4 == 0
    h = struct.unpack_from("<24I", raw)
    assert h[:2] == (0x534C5547, 1)
    assert h[2] == h[4] == 512
    assert h[6] == 24 and h[7] == 24 + h[21] // 4
    assert len(raw) == 96 + h[21] + h[22]
    assert h[21] == h[2] * h[3] * 16
    assert h[22] == h[4] * h[5] * 4
    assert all(math.isfinite(x) for x in struct.unpack_from("<8f", raw, 32))
    return raw, h, duration

rect = "M -2 -3 L 4 -3 L 4 5 L -2 5 Z\n"
a, h, _ = compile_path(rect)
assert struct.unpack_from("<4f", a, 32) == (-2.0, -3.0, 4.0, 5.0)
b, _, _ = compile_path(rect.replace("4 5", "6 5"))
assert a != b, "Fresh atlas must reflect geometry changes"
assert a == compile_path(rect)[0], "Preparation must be deterministic"
assert a == compile_path(rect.replace(" Z", ""))[0], "Fill closure must be implicit"
hole = rect + "M 0 0 L 0 1 L 1 1 L 1 0 Z\n"
assert compile_path(hole)[1][20] > h[20]
coarse = compile_path("T 0.1 M 0 0 C 0 2 2 2 2 0 Z")[1][20]
fine = compile_path("T 0.0001 M 0 0 C 0 2 2 2 2 0 Z")[1][20]
assert fine > coarse, "Explicit tolerance must control cubic subdivision"
for source in ["", "L 1 1", "T -1 M 0 0 L 1 1 Z", "M nan 0", "M 0 0 X 1", "M 0 0 L 1"]:
    bad = subprocess.run([exe], input=source.encode(), capture_output=True)
    assert bad.returncode != 0 and not bad.stdout
times = [compile_path(hole)[2] * 1000 for _ in range(40)]
print("PASS bounds, fresh geometry, determinism, implicit closure, subpaths, tolerance, malformed inputs")
print(f"Local two-contour preparation INCLUDING process startup: median={statistics.median(times):.2f} ms, max={max(times):.2f} ms")

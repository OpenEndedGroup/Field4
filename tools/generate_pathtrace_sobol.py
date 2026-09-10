"""Regenerate the GPU tracer's 100 x 32 Joe-Kuo Sobol direction words.

Run: uv run --with scipy==1.17.1 python tools/generate_pathtrace_sobol.py
SciPy is used only for generation, never by the renderer. See the adjacent provenance file.
"""
from pathlib import Path
import hashlib
import numpy as np
import scipy
from scipy.stats import qmc

root = Path(__file__).resolve().parents[1]
output = root / "src/jvmMain/resources/afield/four/pathtracing/sobol-100x32.bin"
# SciPy exposes the initialized direction matrix on its Sobol engine. Its ordering is
# dimension-major, bit-minor; sample indices use Gray coding in both SciPy and our shader.
words = np.asarray(qmc.Sobol(d=100, scramble=False, bits=32)._sv, dtype="<u4")
assert words.shape == (100, 32)
output.write_bytes(words.tobytes())
print(f"SciPy {scipy.__version__}: {output.name}, sha256={hashlib.sha256(output.read_bytes()).hexdigest()}")

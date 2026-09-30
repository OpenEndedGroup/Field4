# Prototype verification record

Date: 2026-09-30. Slughorn: 409c4a2e68cafcb49e11ea0edd0145d4e255cbea.

The original prototype session compiled main.cpp plus upstream slughorn.cpp with
g++ -std=c++20 -O2 -Wall -Wextra and ran check_prepare.py against the resulting helper.
Compilation succeeded without diagnostics and native preparation checks passed:

- Canvas layer transform/color edits and composite registration leave curve/band bytes unchanged.
- Adding Canvas geometry after Atlas::build() does not register the new shape.
- Analytic CPU coverage samples distinguish a rectangle's interior and exterior.
- Packed data preserves source-space bounds, closes open fill contours, includes
  multiple contours, changes with edited geometry and is deterministic for identical input.
- Explicit cubic tolerance changes the generated quadratic count.
- Empty, malformed, nonfinite, negative-tolerance and incomplete-command inputs fail
  without binary output.

A 40-run two-contour probe measured 4.39 ms median and 17.39 ms maximum INCLUDING
process startup and capture. This is a small synthetic preparation workload,
not a Field/GPU benchmark and not a claim about per-frame geometry throughput.

The PR preparation turn had no execution environment. The earlier prototype sources
were carried into repository paths, with documentation links adjusted and an ignore
rule for the helper build directory. No fresh compile/test run occurred in that turn.

Outstanding: Kotlin compilation, SPIR-V compilation, Field resource lifetime,
blending setup, perspective edge quality, VR multiview and actual Vulkan rendering.
No simulated GPU captures were produced. The CPU lifecycle check is not a render test.

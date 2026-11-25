# SolidSL folds: measurements

`Profile.Fold(child, degrees)` (`src/jvmMain/kotlin/afield/lang/solidsl/SolidExpr.kt`) reflects the
left side of a line through the origin onto the right, `p - 2 max(p·n, 0) n` with
`n = (-sin a, cos a)`, and evaluates the child once. `hexFolded()` is folds at 90°, 180°, 240°, 90°
(applied innermost-last: the child sees the point after all four), which maps the plane into the D6
wedge 60°–90°. The Koch builders are in `SolidFolds.kt`; method after Matt Keeter
(mattkeeter.com/blog/2026-11-23-snowflake) and Alexa Schor (alexaschor.com/into-the-portal).

Measured by `src/jvmTest/kotlin/afield/lang/solidsl/SolidSLFoldTest.kt` (2026-11-25):

| level | fold nodes | fold GLSL chars | naive nodes | naive GLSL chars |
| --- | --- | --- | --- | --- |
| 1 | 1 | 8,110 | 1 | 8,110 |
| 2 | 9 | 17,530 | 20 | 60,246 |
| 3 | 17 | 26,906 | 134 | 389,440 |
| 4 | 25 | 36,282 | 818 | 2,450,600 |
| 5 | 33 | 45,658 | 4,922 | not emitted |

- Fold nodes are `1 + 8(N-1)`; naive nodes satisfy `n(N) = 6 n(N-1) + 14`.
- Region equality, levels 2–4, 20,000 samples each in [-1.3, 1.3]²: 0 inside/outside disagreements,
  worst outside |fold − naive| 1.5e-15 (the union of exact fields is exact outside).
- `toGlsl2` read back by `GlslInterpreter` (SolidSLGlslTest): worst |Kotlin − GLSL| 0.0 at levels
  1–5; shaderc compiles each as a fragment shader.
- Level 4, 20,000 samples in [-1.5, 1.5]², central differences h = 1e-5: |∇d| within 1e-3 of 1 at
  99.99%, median error 4.8e-11.

Not measured: on-GPU values read back against the CPU (the agreement is via the interpreter and a
visual capture); Keeter's domain-repetition variant (Profile has no `mod`).

The heavy constant per fold level is the hexagram polygon (12 unrolled edges, ~8 kB of GLSL); the
emitter does not share it across levels because each level's copy sees different coordinates.

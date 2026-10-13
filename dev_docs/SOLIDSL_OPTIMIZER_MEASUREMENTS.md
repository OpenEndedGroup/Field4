# SolidSL: Adam vs LBFGS, and what `band` does — measurements (2026-10-11)

Source of every number here: `SolidSLOptimizerComparisonTest` and `SolidSLFitBandTest`
(`src/jvmTest/kotlin/afield/solidsl/`), run on an Apple-silicon Mac, after `adBox3`'s interior `NaN`
fix. Wall times are one run each and only meaningful as ratios.

Protocol: each problem from four starts — run 0 is the problem's own seed, runs 1–3 jitter it by up
to ±15% per parameter (`Random(1000 + run)`); sampled targets also change sampling seed per run.
LBFGS gets `iterations = 500`; Adam gets the settings its own test or docs page uses.

## Conclusion

On all seven problem variants, 28 paired runs, LBFGS reached the same minimum Adam reached (the
exact recovery: a lower one), in 6–38 iterations against Adam's 340–6000. Defaults now follow:
`fitParameters` stays LBFGS and `fitToPointCloud` moved from Adam to LBFGS. The earlier "Adam for
rough landscapes" advice was measured while the gradient was `NaN` inside every box.

## Field fits

| problem | method | run | iterations | stop | improvement | rms / target rms | worst param error | ms |
|---|---|---|---|---|---|---|---|---|
| plate (exact recovery) | LBFGS | 0 | 13 | gradient | 5.42e+20 | 0.0000 | 2.41e-11 | 250 |
| plate (exact recovery) | ADAM | 0 | 339 | gradient | 2.29e+18 | 0.0000 | 6.75e-10 | 983 |
| plate (exact recovery) | LBFGS | 1 | 12 | gradient | 1.96e+19 | 0.0000 | 2.38e-10 | 41 |
| plate (exact recovery) | ADAM | 1 | 377 | gradient | 1.98e+18 | 0.0000 | 3.60e-10 | 967 |
| plate (exact recovery) | LBFGS | 2 | 12 | gradient | 4.29e+18 | 0.0000 | 5.53e-10 | 37 |
| plate (exact recovery) | ADAM | 2 | 392 | gradient | 1.54e+18 | 0.0000 | 9.41e-10 | 908 |
| plate (exact recovery) | LBFGS | 3 | 12 | gradient | 2.67e+18 | 0.0000 | 7.55e-10 | 58 |
| plate (exact recovery) | ADAM | 3 | 380 | gradient | 6.49e+17 | 0.0000 | 1.09e-09 | 894 |
| three spheres ~ rod (approximation) | LBFGS | 0 | 18 | gradient | 12.0 | 0.0802 | n/a | 67 |
| three spheres ~ rod (approximation) | ADAM | 0 | 374 | gradient | 12.0 | 0.0802 | n/a | 967 |
| three spheres ~ rod (approximation) | LBFGS | 1 | 18 | step | 7.26 | 0.0800 | n/a | 113 |
| three spheres ~ rod (approximation) | ADAM | 1 | 369 | gradient | 7.26 | 0.0800 | n/a | 926 |
| three spheres ~ rod (approximation) | LBFGS | 2 | 16 | gradient | 15.4 | 0.0796 | n/a | 50 |
| three spheres ~ rod (approximation) | ADAM | 2 | 380 | gradient | 15.4 | 0.0796 | n/a | 924 |
| three spheres ~ rod (approximation) | LBFGS | 3 | 19 | step | 8.29 | 0.0786 | n/a | 143 |
| three spheres ~ rod (approximation) | ADAM | 3 | 368 | gradient | 8.29 | 0.0786 | n/a | 873 |
| cube+bar ~ sphere+bar (partial) | LBFGS | 0 | 36 | gradient | 18.7 | 0.0967 | n/a | 137 |
| cube+bar ~ sphere+bar (partial) | ADAM | 0 | 4176 | gradient | 18.7 | 0.0967 | n/a | 12243 |
| cube+bar ~ sphere+bar (partial) | LBFGS | 1 | 34 | gradient | 24.3 | 0.0984 | n/a | 131 |
| cube+bar ~ sphere+bar (partial) | ADAM | 1 | 5777 | gradient | 24.3 | 0.0984 | n/a | 16012 |
| cube+bar ~ sphere+bar (partial) | LBFGS | 2 | 38 | gradient | 12.3 | 0.0980 | n/a | 131 |
| cube+bar ~ sphere+bar (partial) | ADAM | 2 | 6000 | max iterations | 12.3 | 0.0980 | n/a | 17032 |
| cube+bar ~ sphere+bar (partial) | LBFGS | 3 | 36 | gradient | 21.0 | 0.0956 | n/a | 127 |
| cube+bar ~ sphere+bar (partial) | ADAM | 3 | 6000 | max iterations | 21.0 | 0.0956 | n/a | 17136 |

## Point-cloud fits (rms column = absolute rms field value; worst error excludes the unseen `right` on one-sided)

| problem | method | run | iterations | stop | improvement | rms / target rms | worst param error | ms |
|---|---|---|---|---|---|---|---|---|
| cloud full | LBFGS | 0 | 9 | gradient | 1.01e+06 | 0.0002 | 3.92e-04 | 47 |
| cloud full | ADAM | 0 | 403 | gradient | 1.01e+06 | 0.0002 | 3.92e-04 | 985 |
| cloud noisy | LBFGS | 0 | 9 | gradient | 2.66e+03 | 0.0038 | 2.91e-04 | 25 |
| cloud noisy | ADAM | 0 | 398 | gradient | 2.66e+03 | 0.0038 | 2.91e-04 | 826 |
| cloud one-sided | LBFGS | 0 | 6 | gradient | 7.49e+05 | 0.0002 | 3.92e-04 | 14 |
| cloud one-sided | ADAM | 0 | 392 | gradient | 7.49e+05 | 0.0002 | 3.92e-04 | 686 |
| cloud full | LBFGS | 1 | 10 | gradient | 1.20e+06 | 0.0002 | 3.87e-04 | 27 |
| cloud full | ADAM | 1 | 400 | gradient | 1.20e+06 | 0.0002 | 3.87e-04 | 850 |
| cloud noisy | LBFGS | 1 | 9 | gradient | 3.17e+03 | 0.0038 | 3.82e-04 | 27 |
| cloud noisy | ADAM | 1 | 399 | gradient | 3.17e+03 | 0.0038 | 3.82e-04 | 914 |
| cloud one-sided | LBFGS | 1 | 8 | gradient | 1.10e+06 | 0.0002 | 3.83e-04 | 14 |
| cloud one-sided | ADAM | 1 | 384 | gradient | 1.10e+06 | 0.0002 | 3.83e-04 | 513 |
| cloud full | LBFGS | 2 | 10 | gradient | 1.12e+06 | 0.0002 | 3.88e-04 | 28 |
| cloud full | ADAM | 2 | 405 | gradient | 1.12e+06 | 0.0002 | 3.88e-04 | 881 |
| cloud noisy | LBFGS | 2 | 11 | gradient | 3.04e+03 | 0.0038 | 3.11e-04 | 29 |
| cloud noisy | ADAM | 2 | 399 | gradient | 3.04e+03 | 0.0038 | 3.11e-04 | 871 |
| cloud one-sided | LBFGS | 2 | 8 | gradient | 9.39e+05 | 0.0002 | 3.85e-04 | 14 |
| cloud one-sided | ADAM | 2 | 396 | gradient | 9.39e+05 | 0.0002 | 3.85e-04 | 523 |
| cloud full | LBFGS | 3 | 10 | gradient | 1.04e+06 | 0.0002 | 3.94e-04 | 28 |
| cloud full | ADAM | 3 | 403 | gradient | 1.04e+06 | 0.0002 | 3.94e-04 | 883 |
| cloud noisy | LBFGS | 3 | 10 | gradient | 2.63e+03 | 0.0038 | 4.18e-04 | 29 |
| cloud noisy | ADAM | 3 | 396 | gradient | 2.63e+03 | 0.0038 | 4.18e-04 | 851 |
| cloud one-sided | LBFGS | 3 | 8 | gradient | 9.86e+05 | 0.0002 | 3.97e-04 | 15 |
| cloud one-sided | ADAM | 3 | 391 | gradient | 9.86e+05 | 0.0002 | 3.97e-04 | 521 |

## `band`

- `band = 1e6` is bit-identical to `null` (loss, values, iteration count).
- Samples whose *prediction* is outside the band contribute no gradient, even where the target is
  inside it: a unit-ball model against samples at radius 1.5–2.0 with `band = 0.1` reports `r` dead,
  for both a target ball of 0.95 (both clamp; loss 0) and 1.45 (target in band; loss > 0).
- A sphere seeded at r = 0.5 with `band = 0.1` still recovers r = 1.0 in 10 iterations, pulled by
  samples near its own surface.
- Three spheres vs. a rounded rod, LBFGS, `band = 0.05`: near-surface rms (samples with
  |target| < 0.05) 0.0595 → 0.0484; whole-field rms 0.0506 → 0.0620. The banded fit also loses the
  Z symmetry the unbanded one finds (topZ 0.695 vs bottomZ −0.778), since far fewer samples steer it.

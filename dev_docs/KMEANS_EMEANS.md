# k-means in `clustering.kt`: Lloyd and e-means

Added 2026-11-11 (nightly, scout card 4f9270c0ec3d). Marc: "don't replace Lloyd just add to it and
switch over". `KMeansOptions.algorithm` is the switch; e-means is the default.

## Source and attribution

e-means: Bessa et al., "E-Means: Scaling Laws for Mini-Batch K-Means", Meta FAIR,
[arXiv:2611.09501](https://arxiv.org/abs/2611.09501), <https://e-means.github.io>. Ported from the
MIT-licensed reference `faiss/contrib/e_means` (`base.py`, `e_means.py` — `NumpyEmeans`), read on
2026-11-11. Port is in `src/jvmMain/kotlin/afield/math/statistics/clustering.kt` (`runEmeans`,
`emeansRecipe`, `reviveUnused`).

## Constants (all from Faiss `base.py`, which notes they are refits that differ from paper Table 2)

| what | value |
| --- | --- |
| batch law B = C·k^bk·n^bn·T^bT, "quality" (d ≤ 16) | C = 0.00439175, bk = 0.134749, bn = 0.876473, bT = 0.813344; rounded to a power of two, capped at n/2 |
| batch law, "throughput" (d > 16) | C = 0.035134, same exponents; capped at pow2(n/2) |
| step law α0 = C·k^ak·B^aB·n^an·T^aT | C = 0.8244, ak = −0.4617, aB = 0.9003, an = −0.4498, aT = −0.1057; clamped to [1e-4, 1] |
| cosine schedule | α = α0·(0.1 + 0.9·½(1+cos(π·step/total))) |
| initial EMA mass | 2^-16 (paper: 1e-10) |
| revival | every 5 updates; clusters with mass < 0.1 × mean mass; donors sampled ∝ mass without replacement (Gumbel top-k); split offset ±1e-4 relative, alternating sign per coordinate; mass halved |
| revival default | off at d ≤ 2 (Faiss measured it costing 7-10% held-out error on 2-D geodata), 0.1 above |
| close | centroids := exact mean of the samples assigned during the last epoch |

Not ported: the paper's "novel initialisation" (Faiss defaults to a random sample, our
`KMeansSeeding.RANDOM`); fractional epochs; spherical mode; `max_points_per_centroid`. The default
seeding here is k-means++ (`PLUS_PLUS`) for both algorithms, via the D² helper shared with k-medians.
`labels` are the exact assignment to the final centroids (Faiss returns last-pass labels).

## Measured (KMeansTest, seeded, Apple-silicon laptop)

100 000 points, 200 isotropic blobs (σ 0.15–0.45) in a 10-cube, k = 200, d = 3. Recipe chose
B = 512, α0 = 0.0985.

| seeding | Lloyd 25 epochs MSE | e-means 3 epochs MSE | ratio | e-means after 1 epoch |
| --- | --- | --- | --- | --- |
| k-means++ | 0.269454 (2.1 s) | 0.266124 (0.8 s) | 0.988 | 0.27263 |
| random | 0.299893 (1.7 s) | 0.293156 (0.5 s) | 0.978 | 0.29827 (already below Lloyd 25) |

Neither Lloyd run converged in 25 epochs. Timings include the per-epoch history pass.

Revival: 30 000 points in 50 blobs plus 20 strays 40 units out, k = 50, k-means++ seeds (which
spend seeds on the strays):

| run | MSE | clusters < 10 members | planted blobs with no centroid within 0.5 |
| --- | --- | --- | --- |
| e-means, revival off | 0.5469 | 10 | 14 |
| e-means, revival on (16 revivals) | 1.2131 | 0 | 5 |
| Lloyd 25 | 0.5506 | 9 | 12 |

Revival frees the dead clusters and recovers blobs, and MSE *rises*: the strays rejoin real clusters
and their squared distances dominate. That is the outlier argument the docs page makes about
centroids, measured.

## Traps

- On a plain skewed mixture (half the mass in 10 of 200 blobs; also quantised to a 0.25 grid) no
  cluster went dead with or without revival: a twin centroid separates from its sibling after the
  first update. Dead clusters needed a structural cause (seeds on strays).
- `StudyEvidence.kt`'s private palette k-means (k = 5, farthest-point seeded, ≤ 4096 pixels) was
  left alone: switching it would change committed palette outputs. Backlog.

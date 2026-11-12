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

## Second pass (2026-11-11, emeans-next)

Worked through the first landing's "open questions and next builds", then Marc's follow-up: "is
there fun to be had plotting the paths that centroids take? can we make a curve family out of
e-means? ... run a few rounds of N/2 e-means, gather those paths, then run a few rounds of N/2/2
e-means on those centers and so on? N/3? etc. a tree structure like src/personal/**/pointGame.kt".

Added, all in `clustering.kt`, tests in `KMeansTest`, docs in the k-means section of
`spatial_geometry_examples.md`, boxes in `kmeans_emeans.yaml`:

| what | API |
| --- | --- |
| more than four dimensions | `vectorKMeans(vectors: List<DoubleArray>, k, options, seeds)` |
| cluster by direction | `KMeansOptions.spherical` (samples and centroids on the unit sphere) |
| better default seeding | `KMeansSeeding.GREEDY_PLUS_PLUS`, now the default |
| restarts | `KMeansOptions.runs` (default 1) |
| per-epoch centroids | `KMeansOptions.recordCentroids` → `KMeansResult.centroidHistory` |
| per-update paths | `KMeansOptions.recordPaths` → `KMeansResult.centroidPaths` |
| art knobs | `KMeansOptions.stepScale`, `KMeansOptions.closeOnMean`, `seeds =` on every `…KMeans` |
| k-means tree | `vec2KMeansTree(points, shrink)` → `KMeansTree`, `KMeansTreeLevel`, `KMeansTree.toFLines` |

### Greedy k-means++ is the default now, and why

`vectorKMeans` at d = 32 (20 000 points, 40 blobs, σ 0.3–0.7, centres in a 10-cube; planted MSE
8.63) exposed it. Plain k-means++ left two blobs sharing a centroid from every one of four seeds,
for both algorithms, and four `runs` did not help:

| seeding | Lloyd 25 MSE, seeds 1–4 | e-means 3 MSE, seeds 1–4 | blobs merged |
| --- | --- | --- | --- |
| `PLUS_PLUS` | 19.38, 13.35, 26.57, 16.25 | 15.94, 13.35, 26.58, 16.26 | 1–4 per run |
| `RANDOM` | 49.6, 58.7, 43.6, 33.7 | 52.4, 41.7, 34.2, 29.4 | 5–9 |
| `GREEDY_PLUS_PLUS` | 8.616 × 4 | 8.616 × 4 | 0 |

The cause is D² mass: with 38 blobs seeded, the spread inside them (≈ 2·d·σ² per point) outweighs
the two blobs still missing, so the last draws land in covered blobs about half the time. Greedy
(sklearn's `n_local_trials = 2 + ln k`, keep the candidate that lowers the potential most) fixes it.
In 2-D it ends 3–18% lower than plain k-means++ for both algorithms (sweep below). Cost: on the
100k / k = 200 / d = 3 claim test, e-means went from 0.75 s to 2.3 s, nearly all of it seeding.

The revival docs example and `revivalRescuesDeadClusters` name `PLUS_PLUS` explicitly: their point
is plain k-means++ spending seeds on strays.

### 2-D sweep: no 2-D correction needed

The first pass reported Lloyd winning by up to 6% in 2-D and asked whether the batch law needs a 2-D
correction. Sweep: 2-D blob mixtures (widths 0.08–0.30 in a 10-square), n ∈ {5k, 20k, 80k}, k = blob
count ∈ {10, 40, 160}, three clouds each, `PLUS_PLUS` and `RANDOM` seeds. Ratios are e-means MSE /
Lloyd-25 MSE, averaged over the three clouds:

| n | k | pp: em3 | pp: em3 B×4 | pp: em3 B×16 | pp: em10 | random: em3 | t Lloyd / t em3 (pp) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5k | 10 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 0.003 / 0.002 s |
| 5k | 40 | 0.980 | 0.986 | 0.997 | 0.979 | 0.799 | 0.014 / 0.005 |
| 5k | 160 | 0.988 | 0.979 | 0.995 | 0.954 | 1.012 | 0.065 / 0.027 |
| 20k | 10 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 0.021 / 0.009 |
| 20k | 40 | 1.003 | 0.998 | 1.014 | 0.998 | 1.014 | 0.073 / 0.020 |
| 20k | 160 | 0.997 | 1.003 | 1.015 | 0.992 | 0.987 | 0.344 / 0.074 |
| 80k | 10 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 0.063 / 0.031 |
| 80k | 40 | 0.996 | 1.000 | 0.999 | 1.000 | 0.972 | 0.384 / 0.111 |
| 80k | 160 | 0.987 | 0.992 | 1.008 | 0.979 | 0.992 | 1.279 / 0.507 |

e-means at 3 epochs is never more than 1.4% above Lloyd at 25 on average, and is 2–5× faster.
Larger batches than the law's are no better (B×16 is the worst column), so the law is fine in
2-D. The 6% cases are individual clouds. Default stays e-means. `planeSweepGreedySeedingAndEmeans`
keeps a cut-down version (5k and 20k, two clouds) and pins: greedy/plain mean < 0.97 (measured
0.910), e-means/Lloyd mean < 1.02 (measured 0.994).

### Spherical

8 directions in 16-D, lengths 0.2–5: plain k-means purity 0.63; spherical 1.00 (greedy seeds).
Plain k-means++ seeding on the unit vectors merged one direction pair from every seed tried, the
same failure as above. A cluster whose members cancel exactly (mean of length 0) keeps its previous
centroid (Lloyd: its first member; e-means: the direction of the batch mean).

### Centroid paths as art: what one run does, and what the tree does

One e-means run draws nearly straight lines. Each update moves a centroid a fraction of the way to
the mean of its batch members, so it pursues a target that only moves while other centroids steal
points. Tried in boxes (not committed): 90 seeds on a ring outside a rose curve; a clump at the
centre; a clump in a corner; `stepScale` 1, 0.05 and 0.01 with 3–30 epochs. Ring seeds gave a
starburst of straight spokes, and the seeds facing a gap never moved. A corner clump went
winner-take-all: a few centroids took everything. Lowering `stepScale` makes paths shorter and less
jittery, not curvier. Mini-batch noise is jitter, not curvature.

The tree is where the curves are. Each level's targets are the previous level's centroids, so
coarse centroids start on one input and travel across the plane; on the docs figure's 1 000 points
the levels go 500 → 250 → … → 1 at `shrink = 2` (9 levels) and 333 → 111 → … → 1 at 3 (6). `toFLines` draws paths
as Catmull-Rom splines (`cubicSplineFrom`); on sharp reversals the spline overshoots into small
loops, which reads as part of the drawing. `stride` thins the path first.

Differences from `pointgame.kt` (Marc's `AggregationSimulator`): there, nodes step toward the mean
of their M nearest neighbours with momentum and merge in pairs within a distance, so the tree is
binary and the paths come from a hand-built walk. Here the grouping is k-means' (any branching
factor ≈ shrink), and the walk is e-means' EMA update. No momentum.

### Not done, and why

- **Revival that keeps stray clusters as outlier detectors.** Reviving only clusters that are dead
  *and* far from every sample, or reporting a trimmed MSE. Skipped: the docs already state the
  trade-off (revival gives `k` useful clusters, not lower error), revival is off by default in 2-D
  where most Field use is, and the right outlier tool is a separate API (a distance-to-centroid
  quantile per cluster, readable from `labels` and `centroids` today), not a revival variant.
- **Move `StudyEvidence.kt`'s palette k-means and `stippling.kt`'s Lloyd relaxation onto
  `vec3KMeans`/`vec2KMeans`.** Skipped: both change committed outputs and need a before/after
  comparison by their owners. `stippling.kt` is weighted Lloyd over a density, which needs sample
  weights this API does not take.
- **Weighted Voronoi from clusters** (centroids + sizes into `CircularDirichlet`). Skipped this
  pass in favour of the tree. It is a docs example, not an API, so it is still a good next item.
- **Saddles automatically** (page list). Contour trees, not clustering; left to that page's owner.
- **The paper's novel initialisation, fractional epochs, `max_points_per_centroid`.** Still not
  ported. Greedy k-means++ covers what initialisation was for here.

### Publication budget

The `kmeans_emeans` starting point is also published as an offline document, and
`publication-capture.js` copies every computed style of every output node: about 8 ms per node
here, against a 240 s limit. The cost is the element count, not the path length (a path's points
are one attribute). With every new box in it the document had ~24 000 nodes, and the capture timed
out, then took 195 s. So the spherical box is only on the docs page, the slider's cloud is every
sixteenth point, and the docs figures use 2 400 (spherical) and 1 000 (tree) points. The original
two boxes alone are ~12 400 nodes. After the cut the publish took 145 s. Keep this transcript
under ~17 000 nodes.

## Where to go next

1. **Sample weights.** `weights: DoubleArray?` on the `…KMeans` functions (Lloyd: weighted means;
   e-means: weight the batch counts and sums). Unlocks `stippling.kt`'s weighted Lloyd, a tree whose
   coarse levels weigh each centroid by its cluster size (`vec2KMeansTree` weighs centroids equally
   today, so coarse strokes find the middle of the centroids, not of the points), and k-means on
   histograms.
2. **Trees in 3-D and on embeddings.** `vec3KMeansTree` is a few lines over the same core; a
   `threeLines` figure of a 3-D tree would show it. A `vectorKMeansTree` gives a topic hierarchy
   over embeddings with `spherical = true`.
3. **Momentum on the e-means walk, for drawing.** `pointgame.kt` gets its curves from momentum
   (`alpha`, `beta`). A `KMeansOptions.momentum` applied to the EMA update would bend single-run
   paths without the tree. It changes the algorithm, so it belongs next to `stepScale` with the
   same "for drawing" caveat.
4. **Animate the tree.** `centroidPaths` are time-aligned within a level; a box that draws every
   level up to time t (levels played one after another, or all at once on a normalised clock)
   with a `slider` or `frames {}`.
5. **Weighted Voronoi from clusters** (see above).
6. **Seeding cost.** Greedy seeding is now most of e-means' time at k = 200. Seeding on a random
   subsample (what Faiss does with `max_points_per_centroid`) or k-means|| would bring it back down.
7. **The private k-means migrations** (above), each with a before/after.

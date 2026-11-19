# Tile-binned splats (`SplatTiles`)

`src/jvmMain/kotlin/afield/graphics/render/SplatTiles.kt` is a CPU tile-binned Gaussian splat
rasterizer: project (shared with `SplatRaster` through `SplatRaster.project`), bin into 16×16 tiles,
sort each tile front to back, blend per tile on a thread pool. Two schedules, after arXiv:2611.12319
("Mitigating SM-Level Load Imbalance in 3D Gaussian Splatting on GPUs"): `Naive` (one item per tile,
raster order, dynamic pull) and `Balanced` (heaviest first; tiles longer than `chunk` cut into
chunks blended separately and merged with `C = C0 + T0*C1`, `T = T0*T1`). Public page section:
`public/docs/geometry_from_disk.md`, "Splats by tile, and what a tile costs".

## What is verified

`SplatTilesTest` (`four.splat-tiles.smoke`): binned vs `SplatRaster.rasterize` on
`SplatTiles.clusteredCloud`, max per-channel 8-bit delta ≤ 1 for Naive and Balanced(64), 1 and 4
threads, transmittance cutoff 0 and 1e-4, a 250×190 image (not a tile multiple), and BufferOrder.
Negative control: binning in buffer order against the depth-sorted reference is 69 levels off.
Tile counts are checked against a recount from the stored bounds.

## Measurement (2026-11-19, Apple M1 Max, 10 threads)

`run-jvm-main afield.graphics.render.SplatTilesBenchKt`. 200,000 splats of `clusteredCloud`
(60% in a core a tenth the halo's radius). Median of 5 runs after one warm-up per schedule; naive
measured twice (beside each balanced variant), which shows ±10% run-to-run drift.

| Population | pairs | max tile | cutoff | naive ms | balanced auto | balanced 256 |
| --- | --- | --- | --- | --- | --- | --- |
| 1280×720, eye z 2.6, 3600 tiles | 2,531,987 | 37,018 | 1e-4 | 342–396 | 380 | 854 |
| same | | | 0 | 877–1060 | 911 | 1087 |
| 1280×720, eye z 0.6, 3600 tiles | 8,706,497 | 17,998 | 1e-4 | 738–764 | 718 | 4556 |
| same | | | 0 | 3602–4260 | 4138 | 3870 |
| 96×96, 36 tiles | 396,298 | 64,534 | 1e-4 | 9.7–11.7 | 15.2 | 34.0 |
| same | | | 0 | 42.2–49.2 | 31.9 | 37.5 |

Worker imbalance (busiest / mean busy time) is 1.00–1.02 for every 3600-tile case under both
schedules; 1.30–1.56 naive vs 1.08 balanced on the 36-tile case.

## Traps

- Chunking defeats early termination: a chunk starts at T = 1 and cannot know the chunks in front
  already saturated its pixels. With 256-splat chunks the close-up is 6× slower. Hence the auto
  chunk (`max(256, pairs / (2 * threads))`), which only cuts frame-dominating tiles. A GPU port
  would need the same care (or a per-tile saturation mask published by the front chunk).
- On a CPU with 10 workers and thousands of tiles, dynamic raster-order pulling is already balanced;
  the paper's imbalance is a GPU effect of few SMs × long-running heavy blocks. Only the 36-tile
  case reproduces it here.
- `SplatRaster.rasterize` counts a splat whose every sample misses as offscreen; `bin` cannot know
  that before blending and bins it, so `Binning.drawable` ≥ `RasterStats.drawn`.

## GPU blend (`SplatTilesGpuProbe`, `four.splat-tiles.gpu`, 2026-11-19, M1 Max GPU)

CPU bins; GPU blends one 16×16 workgroup per item, front to back, cutoff 1e-4. Naive: one group per
tile (gl_WorkGroupID). Balanced: persistent kernel, 256 groups, atomic queue over
`workItems(Balanced(), threads = 32)`. The queue reset is a `resetOnly` dispatch of the SAME stage:
GpuProbe runs stages in creation order, so a separate reset stage ran all its resets before all the
blends and every repeat after the first exited immediately (first run measured negative ms). The
probe now checks the counter ends at items + 256.

Pixels: worst delta 1/255 vs CPU tiles in all 6 runs (e.g. 16 of 921,600 pixels differ, close-up).
Timing: (frame ms at 17 blends - frame ms at 1) / 16, 40 frames each, vsync off, single run.

| Population | items naive / balanced | naive ms | balanced ms |
| --- | --- | --- | --- |
| 1280×720 eye z 2.6 | 3600 / 3600 | 1.600 | 3.058 |
| 1280×720 eye z 0.6 | 3600 / 3600 | 4.125 | 4.055 |
| 96×96 | 36 / 88 | 2.318 | 2.930 |

Balanced does not win on Apple silicon. Next: chunk for GPU occupancy (hundreds of resident
groups, not 32), keep early termination across chunks (a per-tile saturation mask), and GPU
timestamps rather than frame differencing. Binning on the GPU (per-splat bounds → (tile, depth)
keys through `GpuRadixSort`) is not built.

## Not done

GPU binning and sorting; GPU timestamp timing; a GPU-sized chunk policy.

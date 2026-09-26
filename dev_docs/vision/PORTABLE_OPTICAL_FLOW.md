# Portable dense optical flow (`PyramidOpticalFlow`)

Landed 2026-09-26 (nightly). The durable half of that night's worklog: the method, the measured
numbers and where they came from, and the traps found on the way.

## Why

`afield.four.vision.OpticalFlow` (2026-09-25) is `VK_NV_optical_flow` only. Its constructor
`check`s `device.supportsOpticalFlow`, which `Device.kt` sets from the NV feature struct. On macOS
(MoltenVK), and on any GPU older than Ada, both optical-flow starting points refused to run.
The only other flow in the tree was CPU and sparse (`opticalFlowLK` / `opticalFlowBlockMatch` in
`stdlib/imaging_geometry.kt`).

## Shape

- `OpticalFlowOperation` — the contract both backends honour (`OpticalFlowOperation.kt`): RGBA32F
  `output`, `.xy` displacement first → second in input pixels (X right, Y down), `.z` cost (lower
  is better; **scale is backend-specific**), `.w = 1`, `ceil(w/grid) × ceil(h/grid)`;
  `evaluate(reset)` between frames, blocking on a fence.
- `opticalFlow(renderer, a, b, grid, backend = AUTO)` — HARDWARE when `supportsOpticalFlow`,
  PYRAMID otherwise. `OpticalFlow` itself only gained `: OpticalFlowOperation` and `override`s.
  **The NVIDIA path has not been re-run since**: no NVIDIA device was available.
- `PyramidOpticalFlow` — fragment passes into `RenderToTexture`s, the same idiom the NV class
  uses for its conversion/unpack passes. It needs no storage images and no extension.

Passes per `evaluate`, all in one graphics-queue command buffer:

1. convert: NV tone map + gamma, then luma and a red–blue opponent channel, for both frames
2. `[1 3 3 1]²` downsample × (levels − 1), then per-level central-difference gradients
3. coarsest level: integer SAD search over ±`search`, as **its own pass** (one texel per sample ×
   offset). Inside the tracking pass the coarsest level has about 2k fragments, each doing about
   100k fetches serially, and the GPU sat idle: 25 ms total became 14 ms.
4. per level, coarse → fine: Gauss–Newton from the upsampled coarser field, with the structure
   tensor computed once per sample (the inverse-compositional simplification). A refinement that
   ends with a larger window residual than its start is discarded.
5. 3 × 3 vector median per level; the finest level's median *is* `output`.

The optional `consistency = true` adds a reverse chain and a pass writing `consistencyMap.x` =
|f(p) + b(p + f(p))|.

## Numbers

All numbers are from an Apple M1 Max through MoltenVK, 960 × 540, grid 4, defaults (levels 5,
radius 4, iterations 8, finestIterations 2, search 4). Errors are endpoint errors in input pixels
over interior samples at least 16 flow texels (64 px) from the border. The source is
`PortableOpticalFlowSmoke` (FIELD_TEST `four.vision.portable-optical-flow`) and its
`build/portable-optical-flow/measurements.json`, copied to
`public/docs/assets/optical_flow/portable_measurements.json`.

| Case | Samples | Median | p95 | Notes |
| --- | --- | --- | --- | --- |
| (+12, −8) translation | 21,424 | 6.7e-6 | 1.7e-5 | whole-pixel shift: easiest case |
| (+40, −24) translation | 21,424 | 6.9e-6 | 1.6e-5 | 1.25% over 1 px (64-px checker alias) |
| wiggle, 4 phases | 85,696 | 0.080 | 0.259 | zero field would score median 1.98 |
| identical frames | 32,400 | 0 | 0 | max 0 |
| FB error, translation interior | 21,424 | 1e-5 | 2.9e-5 | leaving columns median 43 px (206) |
| FB error, wiggle one phase | 21,424 | 0.060 | 0.19 | |

Timing: 11.9 ms median wall time per pair (100 warmed pairs). GPU time is 0.42 ms for the
pyramid; 1.38 / 0.71 / 0.66 / 1.58 / 5.25 ms for levels 4 to 0. A consistency-checked pair takes
24.6 ms. The pyramid backend allocates 30.3 MB. The page's NVIDIA numbers (1.20 ms per pair; wiggle
0.15 / 0.35 px) are an RTX 4090 running a different algorithm. They are not a head-to-head
comparison, and the clean procedural source flatters Lucas–Kanade.

Live starting points on the same Mac, replayed into fresh documents: 0 `VUID-` and
0 `[error,execution]` lines. In `optical_flow_pingpong`, the moving pair's median error against
`expectedMotion` was 0.031, 0.067 and 0.127 px in the three checks run against the final code
(29,464 samples each), and exactly 0 while paused.

## How each change moved the numbers (same smoke, same pair)

| Change | Wiggle median / p95 | (+40, −24) over 1 px | GPU track |
| --- | --- | --- | --- |
| LK only, coarse medians | 0.179 / 1.59 | ~100% (median 45 px) | 11 ms |
| + finest median, + coarse search (luma SAD) | 0.121 / 0.74 | 11.5% | 25 ms |
| + search as own pass, + refine-or-revert | 0.080 / 0.275 | 11.5% | 14 ms |
| + chroma in the search SAD | 0.080 / 0.275 | 1.0% | 14 ms |
| + one-gather bilinear, finestIterations 2 | 0.080 / 0.259 | 1.25% | 11 ms (wall 12) |

The first row doubles as a negative control: the current floors (wiggle p95 < 0.28, large-shift
outliers < 2%) would have failed it.

## Traps

- **`prop<SomeInterface>(x)` emits no import.** `importHelp.kt` builds its simple-name index from
  ClassGraph's `allStandardClasses`, which excludes interfaces. The box then fails with
  "Unresolved reference" in its preamble, and no Gradle gate sees it, because the library compiles.
  The pingpong box spells `prop<afield.four.vision.OpticalFlowOperation>(flow)` in full for this
  reason. Probable fix, not made (importHelp.kt was off-limits): scan `allClasses` with
  interfaces included, then re-run the corpus gate.
- **Simple-name collisions also emit no import.** The first name, `DenseOpticalFlow`, collides
  with three OpenCV classes on Field's classpath. Hence `OpticalFlowOperation`.
- **Cost direction.** Flow is first → second. With dx = +12, the samples with no correct match are
  the rightmost 12 px, whose content leaves the frame. The leftmost columns only show clamp-to-edge
  window artifacts. An early version of the smoke checked the wrong side, and passed.
- **Fewer finest-level iterations were better.** With 1 to 2 steps the wiggle error drops (p95
  0.231 at 1 step, 0.259 at 2, 0.275 at 8). The finest level starts sub-pixel close. Extra steps on
  a non-translational window mostly fit the warp's residual. Radius 2 at the finest level was faster
  but ruined the cost signal: leaving content was no longer distinguishable.
- **`native-screenshot` is the capture path for the renderer window.** A backdoor-managed Field's
  `screenshot-box` shows the box UI, not the native window.

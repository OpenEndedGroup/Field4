# Gezira GPU tiger: where the frame goes

`GeziraGpuScene` draws the Ghostscript tiger (304 paths, 13,344 curves) in five compute stages
from `src/jvmMain/resources/afield/graphics/nile/gezira_scene.comp`. This page records how each
stage was timed, what the bottleneck was, and what moved it. Every number here was printed by
`four.gezira-gpu-tiger.probe` (`GeziraGpuTigerProbe`), run with
`uv run --project . --group backdoor python tools/run_field_tests.py --id four.gezira-gpu-tiger.probe`,
on one M-series Mac on 2026-11-23, macOS 27.2, Vulkan SDK 1.4.363, vsync off, display awake.

## Method

- **Wall**: 60 frames pumped in the probe's window, divided by 60, after 5 warm-up frames.
  An empty frame (no compute) in the same window takes 8.3 ms, so wall cannot go below that.
- **Pipeline on the GPU**: (wall with the stage(s) dispatched 5 times − wall with them once) / 4.
  Each stage is idempotent, so repeats change the cost and not the picture. Done for all five
  stages together and for each alone.
- **Parts of resolve**: wall with bits of `debug` switching off the band walk, the area adds,
  the boundaries and the blend.
- **Parity**: the GPU frame read back against the CPU's `GeziraSvg.render`, largest and mean
  absolute difference per channel of premultiplied RGBA.

### Why not GPU timestamps

The renderer already writes a timestamp pair around every module when `Options.profiling` is
on (`afield/graphics/vulkan/device/timing.kt`, logged as `MOD '<probe>.gezira-scene-<stage>.<n>'`),
and the probe turns it on for 12 frames per size. Those are the per-pass timestamps; no new
query pool was added. On the default driver, KosmicKrisp, they are unusable: most read 0.000 ms
and the rest about ±294,534,800 ms (an unrelated counter subtracted). Under MoltenVK
(`AFIELD_VULKAN_DRIVER=moltenvk`) they are finite but land on the wrong stage at 900 × 900: `bin`
reads 7.2 ms on average (up to 10.0) and `resolve` 2.5 ms (often 0.011), where the repeat method
gives bin 1.3 and resolve 8.4. Metal samples timestamps at encoder boundaries, and consecutive
dispatches share an encoder, which fits. So the repeat difference is the measurement.

## Before: the resolve stage is the frame

Commit `fe80e8384c`, KosmicKrisp, same machine state as the "after" run below:

| size | wall | pipeline | count | scan | emit | bin | resolve |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 450 × 450 | 15.97 ms | 13.48 ms | 0.06 | −0.08 | 0.06 | 0.54 | 12.44 |
| 900 × 900 | 17.15 ms | 16.74 ms | 0.01 | 0.02 | 0.02 | 1.30 | 15.63 |
| 1800 × 1800 | 24.09 ms | 23.67 ms | 0.01 | 0.04 | 0.06 | 1.80 | 21.79 |

Stage times are ms; values within ±0.3 ms of zero are noise. Inside resolve at 900 × 900, wall
was 17.27 ms with everything, 10.02 without the band walk, 16.49 without the area adds.

## What did not help

- **Sweeping bands instead of rescanning edges.** Each invocation took a contiguous stretch of
  bands, gathered the active edges once and then kept them by the top/bottom events between bands
  (a `perm` array from the boundary sort). The output was identical, and 900 × 900 got slower:
  wall 19.4–19.8 ms. Most runs have under 128 boundaries, so each invocation still had one band
  and still scanned every edge; the extra state only cost registers.
- **Raising `MAX_ACTIVE` to 32**: wall 18.8 ms, no gain.
- **64 invocations per row**: wall 21.2 ms at 900 × 900. (A 256 trial also changed the count and
  emit groups by mistake, so it is not a clean result: 18.4 ms.)

## What did: shared memory, that is occupancy

The resolve workgroup held 30.5 KiB of shared memory: 512 edges, 1,536 boundaries, and 2,048
coverage accumulators whatever the width. That decides how many rows a core holds at once.
`NE`, `NY` and `MAXW` are now defines `GeziraGpuScene` sets per scene: `MAXW` the width rounded up
to 32, `NE` the new `rowEdges` parameter (default 256) and `NY` three times it.

Sizing `MAXW` to the width alone (NE 512) changed nothing: 900 × 900 wall 17.62 ms. Halving the
edge arrays did it. 900 × 900, one run, back to back:

| `rowEdges` | shared memory (900 wide) | wall | pipeline |
| --- | --- | --- | --- |
| 512 | 26.1 KiB | 16.97 ms | 16.76 ms |
| 384 | 20.6 KiB | 17.05 ms | 16.69 ms |
| 320 | 17.9 KiB | 16.22 ms | 13.08 ms |
| 256 | 15.1 KiB | 15.62 ms | 11.32 ms |

## After (default `rowEdges = 256`)

| size | wall | pipeline | count | scan | emit | bin | resolve | largest / mean difference from the CPU |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 450 × 450 | 15.81 ms | 10.19 ms | −0.31 | −0.20 | −0.30 | 0.21 | 9.18 | 0.0212 / 1.51e-4 |
| 900 × 900 | 15.99 ms | 11.21 ms | −0.07 | −0.16 | −0.05 | 0.41 | 9.86 | 0.0253 / 8.23e-5 |
| 1800 × 1800 | 18.39 ms | 17.84 ms | −0.01 | 0.03 | 0.01 | 1.78 | 16.02 | 0.0322 / 4.95e-5 |

The differences from the CPU are the same to every printed digit as before: the change moves no
pixel. The 900 × 900 wall is under 16 ms, just: a second run measured 15.86 ms. The pipeline at
11.2 ms is below the window's own frame cost plus compute, so wall is now presentation as much as
compute.

The same probe under MoltenVK (`AFIELD_VULKAN_DRIVER=moltenvk`), same build:

| size | wall | pipeline | bin | resolve |
| --- | --- | --- | --- | --- |
| 450 × 450 | 10.17 ms | 9.10 ms | 0.91 | 8.04 |
| 900 × 900 | 10.81 ms | 9.90 ms | 1.26 | 8.42 |
| 1800 × 1800 | 15.30 ms | 14.89 ms | 1.91 | 12.84 |

and there `rowEdges` 512 / 384 / 320 / 256 gives 900 × 900 wall 14.24 / 14.27 / 12.17 / 10.81 ms.

## The limit `rowEdges` sets

An edge past `rowEdges` in one path's row is dropped and counted (`overflow()[2]`); the probe
fails on any. The tiger's widest row, as `widestRun()`: 242 edges at 150 px, 217 at 225, 220 at
300, 221 at 450, 186 at 900, 153 at 1800. Smaller pictures put more of a path in a row, so a scene
drawn tiny, or a busier document, may need `rowEdges = 512` and the old speed. Choosing it from the
scene automatically (a first frame at 512 that reads `widestRun()`, then a rebuild) is the next step.

## Next

Resolve is still 90% of the pipeline, and occupancy is the lever, so the remaining shared arrays are
the target: `dirs` could ride in the sign of an edge field, and `ys` is dead once `sorted` is written.

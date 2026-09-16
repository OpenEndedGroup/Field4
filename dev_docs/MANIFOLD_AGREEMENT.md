# What `afield.manifold` returns, against the closed form

The agreement census that used to be the second section of `public/docs/manifold.md`, where it sat
between the opening example and the Booleans a reader came for. Moved here 2026-09-15.

Every row is checked by `afield.manifold.ManifoldSmokeTest` (`./gradlew smokeManifold`, which skips
with exit code 0 when `libmanifoldc` is absent) and by `ManifoldTest` in `jvmTest`. Re-run those
rather than trusting the table: the smoke prints its own check count, and nothing asserts the count
itself, so a number quoted from a past run has no guard behind it.

## Primitives and Booleans

| what | Field says | closed form |
|---|---|---|
| `Solid.cube(2,3,4).volume` | `24.0` | `24` |
| `.surfaceArea` | `52.0` | `52` |
| `.triangleCount` | `12` | the minimal closed mesh, not 24 |
| a 2×2×2 cube ∪ the same shifted by 1 | `12.0` | `12` |
| ∩ of the two | `4.0` | `4` |
| `\|a\| + \|b\| − \|a ∩ b\|` | `= \|a ∪ b\|` | inclusion–exclusion |

A cube slices to exactly `4.0`, and a 2×2×2 cube split at `z = 0.5` gives halves of exactly `2.0`
and `6.0`.

## 2D sections, offsets and revolution

| what | Field says | closed form |
|---|---|---|
| an L of area 3, extruded by 2 | `6.0` | `6` |
| a 2×2 square `offset(0.5, MITER)` | `9.0` | `8 + 4(0.5²)` |
| the same with `BEVEL` | `8.5` | `8 + 4(0.5²/2)` |
| the same with `ROUND` | `8.78540` | `8 + π(0.5²)` |
| a square profile revolved off-axis | `4.71192` | Pappus: `2πRA = 4.71239` |

The three join types are the reason the offset table is worth having rather than a single row: they
differ only at a corner, and the closed forms differ only in the corner term, so a wrong join type
shows up as a wrong area rather than as a wrong picture.

Winding, measured on an annulus read back through `toPolygons()`: `+3.1413` for the outer contour
(counter-clockwise, positive signed area) and `−0.7853` for the hole.

## Why closed forms rather than tolerances

Two mistakes in this binding produce a plausible wrong number rather than an error, and neither is
catchable by a tolerance:

- `manifold_slice` and `manifold_project` return `ManifoldPolygons*`, not `ManifoldCrossSection*`.
  Every constructor here builds into memory the caller allocated, so handing `slice` a
  cross-section-sized buffer constructs the wrong object in it and the first read aborts the process
  inside libc++ with `mutex lock failed` and no Java stack.
- `manifold_ray_cast` reports its hit distance as a **fraction of the origin-to-end segment**, so a
  hit 4 units away on a ray of length `1e4` arrives as `4.0e-4` — a number that looks like "at the
  origin" and is not wrong enough to notice.

## The level-set upcall, parallel against sequential

A unit sphere at `edgeLength = 0.04`: 465k samples, 40536 triangles out.

| | wall clock | threads that entered the JVM |
|---|---|---|
| `parallel = true` | 82 ms | 10 |
| `parallel = false` | 151 ms | 1 |

One machine, one run, printed and not asserted. What `ManifoldTest` does assert is that the two
paths agree bit for bit on triangle count and volume, which is the part a race would break.

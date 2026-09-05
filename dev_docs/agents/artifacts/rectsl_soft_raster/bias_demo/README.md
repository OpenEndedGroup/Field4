# A loadable demo: RectSL soft rasterization and its coverage bias

`metadata.edn` here is a complete, one-box Field document. It lowers a RectSL scene a **third** time —
past the CPU sampler and past the GLSL SDF — into a *differentiable* raster, and then measures how far
that raster is from real area coverage.

## Open it

Point Field at this folder as its workspace:

```
./field.sh dev_docs/agents/artifacts/rectsl_soft_raster/bias_demo --read-only
```

or, with a Field already running,

```
open 'http://localhost:<port>/offline.html?file=dev_docs/agents/artifacts/rectsl_soft_raster/bias_demo/metadata.edn'
```

Then run the box. It draws three output panels and prints nine numbers.

## What to look at

**Panel `bias`** — soft raster, exact area coverage, and `|soft - exact|` on a heat ramp with its peak
in the caption. The difference is a thin fringe along each edge, **brightest at the corners**: a corner
is two edges inside one pixel, and no function of a single signed distance can be right there. Corner
bias (0.09–0.25) is larger than the worst oblique-edge bias (0.043), which was not the expected result.

**Panel `seam`** — the left panel looks like one solid rectangle. It is two rects that abut. The middle
panel is the difference under `UNION_SDF` and shows a **bright bar straight down the join**, because
`min(sdf_a, sdf_b)` is the union's distance *outside* the union and an under-estimate inside it. The
right panel is the same thing under the over-composite: dimmer, 0.233 against 0.370. Neither composite
wins in general — the ranking flips where rects *overlap*.

**Panel `fit`** — target, seed, solved. Three `free-param`s recovered from the target **raster alone**,
which is the derivative the 2026-08-06 attempt correctly said a per-track lowering could not produce.
Target and solved should be indistinguishable; the seed is visibly narrower and centred.

**The printed band-width sweep** is the finding worth carrying away: the clamped ramp's bias has a
**sharp optimum at β = exactly one pixel** — around 1e-13 on an axis-aligned edge there, and ~2e-01 at
half a pixel or at two. It is not a trend. Below one pixel the band clips coverage that is really
there; above one it spreads coverage that is not.

## Provenance, honestly

- The **library code** this box calls is verified: see `dev_docs/RECTSL_SOFT_RASTER.md` and
  `src/jvmTest/kotlin/afield/rectsl/RectSLSoftRasterTest.kt` for the oracles and the numbers.
- The three PNGs in `dev_docs/assets/rectsl_soft_raster/` were produced by the same calls this box
  makes, and were opened and read.
- **This box body itself was compile-checked but never executed in a live Field.** The night it was
  written ended under a disk-space constraint that made starting a JVM unsafe. Everything but the box
  preamble (`display`) was compiled as ordinary Kotlin in `jvmTest` and passed; the numbers it will
  print are the ones the test and the survey observed. If it does fail on load, the likely cause is the
  preamble, not the arithmetic.

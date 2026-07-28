# Nightly worklog — wfc-worked-example

**Branch:** `nightly/2026-07-28/3-wfc-worked-example` (based on `1a347dc8`)
**Kind:** C — documentation / explainer
**Mission:** a public worked example for Wave Function Collapse — `public/docs/wfc_examples.md`,
with real captured output at every claim, wired into the Zensical nav, plus a replayable
operation transcript.

## What is already there

`src/jvmMain/kotlin/afield/stdlib/wfc.kt` (1218 lines, `@file:CompletionCategory("Data")`):

- `WFC_NORTH/EAST/SOUTH/WEST` (0..3), `WfcOffset(dx, dy)`.
- `WfcNeighborhood(offsets)` — symmetric offset set; `directionCount`, `offsets`, `offset(d)`,
  `opposite(d)`, `directionOf(dx, dy)`. Companion: `VonNeumann`, `Moore`, `of(vararg)`,
  `chebyshev(radius)`, `manhattan(radius)`. `typealias WfcNeighbourhood` + four
  `WFC_*_NEIGHBO(U)RHOOD` top-level vals.
- `WfcRules` — **private constructor**; `tileCount`, `weights`, `neighborhood`,
  `allowed[direction][tile]: LongArray` bitset, `wordCount`. Factories:
  `fromSampleGrid(sampleTileIds, sampleWidth, sampleHeight, tileCount, wrapX, wrapY, neighborhood)`,
  `fromOverlappingIntPatterns(patterns, pw, ph, samplePatternIds, neighborhood)`,
  `fromOverlappingFloatPatterns(...)`.
- `WfcStatus { Running, Solved, Contradiction }`, `WfcObservation(x, y, tile)`.
- `WfcSolver(width, height, rules, seed = 1L, noise = 1e-6)` — `status`, `lastObservation`,
  `reset()`, `possibilitiesAt(x, y, out): Int`, `possibilityCountAt(x, y)`, `resolvedTileAt(x, y)`
  (-1 if undecided), `forceTile(x, y, tile): Boolean`, `step(): WfcStatus`,
  `run(maxSteps): WfcStatus` (reports to `afield.progressReporter("wfc")`),
  `resolvedTileGridOrNull(): IntArray?`.
- `PngTileSet` / `FloatTileSet` (simple-tiled) and `PngOverlappingTileSet` /
  `FloatOverlappingTileSet` (overlapping), each with `tileCount`, `sampleIds`, `toRules(...)`,
  `render(...)`.
- `BufferedImage.sliceIntoUniqueTiles`, `.sliceIntoOverlappingPatterns`, `loadPngTiles`,
  `loadPngOverlappingPatterns`, `savePng`, `FloatArray.sliceIntoUniqueTiles2D`,
  `.sliceIntoOverlappingPatterns2D`.
- `fun main(args)` — tiny CLI demo.

Public coverage today: **none**. `grep` over `public/docs` finds WFC only as auto-generated rows in
`reference/data.md` (`## wfc.kt`) and `reference/index.md`. No worked example, no picture. The
sibling generative corner (`boolean_networks_examples.md`, `interaction_nets_examples.md`,
`sorting_networks_examples.md`, `graph_connectivity_examples.md`) *is* documented, so WFC is the
conspicuous hole.

Existing test: `stdlib.wfc.smoke` (`WfcSmokeTest.kt`) — two cases only (Moore forced propagation on
a 2x2, overlapping float patterns round-trip on a 4x1). Nothing exercises simple-tiled PNG mode,
nothing renders, nothing steps. Baseline re-run at start of this session: **PASS** (2587 ms,
port 9740).

## Gaps found while reading, that an honest worked example runs into

1. **You cannot author adjacency constraints by hand.** `WfcRules`' constructor is private and every
   factory *learns* from a sample. The mission's "core solver on integer tile IDs + explicit
   constraints" section is literally unwriteable against today's API. → add an additive builder
   (`wfcRules { allow(...) }`) inside `wfc.kt`, so the reader can see the model before any image.
2. **No visualization bridge.** `wfc.kt` is deliberately dependency-free (only `java.awt.image` +
   `ImageIO`), while Field's display path wants `Img` / `Box.showImg` / `montage` from
   `imaging.kt` + `imaging_field.kt`. Rendering a *partial* wave (the money shot) needs
   per-cell blending of the still-possible tiles, which nothing exposes. → new file
   `wfc_field.kt`, mirroring how `imaging_field.kt` bridges `imaging.kt` to Field.
3. **No nearest-neighbour upscale.** `Img.resize`/`scaleBy` area-average or bilinear; both blur
   pixel-art WFC output into mush. A blocky integer upscale is needed for any legible capture.
4. **`reset()` deliberately does not reset the RNG** (`random` is a `val` built in the constructor).
   That is what makes a retry-on-contradiction loop actually work — and it is undocumented.

## Plan

1. Additive code (small, in service of the page — the page is the deliverable):
   - `wfc.kt`: a `wfcRules(tileCount, neighborhood) { … }` builder with symmetric `allow`, tile
     weights, and a documented `WfcSolver.solveWithRetries(maxAttempts)`; KDoc the RNG/`reset()`
     contract.
   - `wfc_field.kt`: WFC → `Img` bridge — render a solved grid, render a *partial* wave by blending
     remaining possibilities, a possibility-count (entropy proxy) field, and an integer
     nearest-neighbour upscale.
2. `public/docs/wfc_examples.md` in the house style of `interaction_nets_examples.md` /
   `imaging_examples.md`: `diffstatic_runner` fences with a preceding `FIELD_TEST` yaml comment,
   `capture="box"` screenshots, `:::fold` for boilerplate. Sections per the mission: what WFC is /
   what this does and does not do; hand-built core; simple-tiled; overlapping; neighborhoods;
   the `step()` animation; contradictions + seeds; recipes.
3. Nav entry in `public/zensical.yml` under **Networks and Dynamics** (next to the other
   generative pages).
4. Regenerate `public/docs/reference/*.md` — `docs.codebase-reference.generate` drift-checks the
   committed pages, so new public API must be regenerated (pass `public/docs/reference` as argv[0]
   to write; no args = check).
5. A replayable operation transcript under `dev_docs/agents/transcripts/`, added to the
   `examples.demo-transcript.exec` harness set, and actually replayed.
6. Extend `WfcSmokeTest.kt` to cover whatever new API lands, plus a real simple-tiled render.

Ports: 9740–9759 mine; clear 9477 before any docs build.

## Execution log

- Baseline `stdlib.wfc.smoke` PASS on warm build, port 9740.

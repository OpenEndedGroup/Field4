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

## What shipped

**The deliverable:** `public/docs/wfc_examples.md` — 979 lines, eight
`diffstatic_runner` fences, all captured through the real docs runner with
`capture="box"`, so the code shown on the page *is* the code that produced the
picture beside it. Nav entry added under **Networks and Dynamics**.

Sections: what WFC is (two honest paragraphs) and what this implementation does and
does not do; §1 the model authored by hand; §2 learning from an image (simple-tiled
at 1×1 tiles, then an 8-tile edge-socket circuit set); §3 the overlapping model with
the classic ground constraint; §4 neighborhoods; §5 the collapse as stills + an mp4;
§6 WFC on a scalar `FloatArray` ending in iso-contour `FLine`s; §7 contradictions,
seeds and reproducibility; then Recipes.

**Files changed** (36 files, +4683/-371 across 8 commits):

| File | Why |
| --- | --- |
| `public/docs/wfc_examples.md` | new — the deliverable |
| `public/docs/assets/wfc_examples/*` | 8 committed captures (7 PNG + 1 MP4) |
| `public/zensical.yml` | nav entry |
| `src/jvmMain/kotlin/afield/stdlib/wfc.kt` | **bug fix** + additive API (+337) |
| `src/jvmMain/kotlin/afield/stdlib/wfc_field.kt` | new — WFC → `Img` bridge |
| `src/jvmMain/kotlin/afield/stdlib/imaging.kt` | `Img.upscaleNearest` |
| `src/jvmMain/kotlin/afield/examples/stdlib/WfcGallery.kt` | new — renders *and verifies* the figures |
| `src/jvmMain/kotlin/afield/examples/stdlib/WfcSmokeTest.kt` | 2 cases → 7 |
| `dev_docs/agents/transcripts/wfc_worked_example.yaml` | new — the loadable demo |
| `public/docs/assets/agent_transcripts/wfc_worked_example/` | materialized starting point |
| `.../DemoTranscriptExecSmokeTest.kt` | EXECUTE registration |
| `public/docs/agent_transcripts.md`, `transcripts/README.md` | gallery + corpus entries |
| `public/docs/reference/{data,imaging,index}.md` | regenerated for the new public API |

## The bug found (bonus)

`WfcRules.fromSampleGrid` has a defensive fallback: a `(direction, tile)` mask it
never observed gets widened to "any tile may sit here". It widened **one direction
only**, which silently breaks the invariant propagation depends on.

WFC pushes constraints outward from a cell that changed, one direction per arc. That
is sufficient only if adjacency is symmetric — `allowed[d][a] ∋ b` exactly when
`allowed[opposite(d)][b] ∋ a`. Setting `allowed[EAST][t]` to everything without also
telling each of those tiles that `t` may sit to *their* west creates arcs the solver
structurally cannot check, so it can emit a pair its own rules forbid when read the
other way round, and nothing notices.

Fixed by `widenEmptyMasksSymmetrically`, which widens both directions at once and
collects the empty masks up front (filling one can un-empty another). Found by
writing `WfcRules.findAsymmetry()` as an assertable diagnostic.

**Proof the regression test catches it:** with the one-sided widening restored,
`stdlib.wfc.smoke` fails with `learned rules are not symmetric: allows(1, 0, 0)=false
but allows(3, 0, 0)=true` — tile 0 may sit west of tile 0 but not east of it. And the
over-constrained-then-widened rule set went from unsolvable in 16 attempts to solved
in 1 after the fix.

## Additive API (all in service of the page)

`wfc.kt`: `wfcRules { }` / `WfcRules.build` + `WfcRulesBuilder` (hand-authored
adjacency — the constructor is private and every existing factory *learns*, so §1 was
literally unwriteable before); `WfcRules.allows` / `allowedCount` / `findAsymmetry`;
`WfcSolver.solveWithRetries(maxAttempts, prepare)` + `WfcOutcome`;
`resolvedCellCount` made public; `reset()`'s RNG contract documented.

`wfc_field.kt` (new): `toImg` for all four tile-set types, `sampleToImg`,
`waveToImg` (the mid-collapse blend — the money shot), `possibilityCountGrid` /
`possibilityCountImg`, `resolvedFraction`.

`imaging.kt`: `Img.upscaleNearest(factor)` — `resize`/`scaleBy` filter, and filtering
is the one thing you must not do to a picture whose pixels are the point.

## Verification — what I actually ran and saw

**Every figure eyeballed.** Circuit traces all connect with no dangling ends; the
sand coastline in §1 is traceable and sea never touches grass; §2's boosted panel has
lakes with deep cores ringed by shallow then sand, exactly as the terrain chain
requires; the overlapping panel's flowers are new but all rooted; the collapse
nucleates and propagates; the mp4 animates (sampled frames at 19%/87%/100%).

**Claims turned into assertions.** `stdlib.wfc.gallery` asserts, for every grid on the
page: every adjacent pair is an allowed pair; the adjacency relation is symmetric; the
output is not degenerate; and the set of terrain edges in the output is a subset of the
set in the source. Plus the page's specific numbers — 29% source water becoming 0%
under learned weights, sand ×10 restoring all five bands at the exact percentages the
histogram prints, 473 diagonal breaks against 0, and the scalar field's anisotropy.

**Three independent implementations agree.** The page's fences, `WfcGallery`, and the
demo transcript each compute the same things and print the same numbers (132/74/102
cells; the 9/19/12/30/27 → 0/0/4/50/44 → 3/15/45/21/12 histograms; 13 crossings of 748;
22 patterns; 473 vs 0; 1069 steps; roughness 0.100/0.112 vs 0.008/0.112).

**Green at the end:**

- `stdlib` tag — 11/11 PASS (includes `stdlib.wfc.smoke`, `stdlib.wfc.gallery`,
  `docs.codebase-reference.generate`)
- `agents` tag — 7/7 PASS (includes `examples.demo-transcript.exec` over all 27
  transcripts, and `examples.agent-transcripts.materialize`)
- `imaging.gallery` PASS; `./gradlew jvmTest --tests "afield.stdlib.Imaging*"` PASS
  (I touched `imaging.kt`)
- `tools/check_doc_assets.py` — OK, 553 refs across 67 nav pages
- `build_zensical_docs.py --page wfc_examples --page agent_transcripts --strict` —
  "No issues found"; rendered page has 8 images, 1 `<video>`, 7 fold regions

**Corrections I made after being wrong.** Three claims I wrote and then disproved by
running them, rather than shipping: that quantising a smooth field can only put
neighbouring bands adjacent (false at coarse sampling — the source really does contain
band-skipping edges, so the check now derives the source's edge set); that the
unpinned overlapping model would produce "all soil" (it produced rootless flowers with
this seed); and that the 1×1 scalar result was rough above 0.15 (it measures 0.100 —
the real property is isotropy vs anisotropy). The page and the assertions say the
measured thing.

## Not verified

- **Only the two pages I touched were built.** I did not run the full 149-page docs
  build, so I have not verified that no *other* page's capture regressed. Risk is low:
  my only shared-code change is the additive `Img.upscaleNearest`, and
  `imaging.gallery` plus the imaging unit tests pass.
- **No visual diff of the regenerated reference pages** beyond confirming the drift
  guard passes and that the four anchors the new page links to resolve.
- **The mp4 has a static tail.** The collapse finishes at roughly 4.5 s of a 7 s clip;
  the box's display loop runs far faster than the 20 fps recorder samples, so there is
  no way to stretch it without a larger grid. It loops, so this reads as a beat rather
  than a fault.
- `docs.codebase-reference.generate` also rewrote `dev_docs/kdoc_worklist.md`; that is
  generated output committed alongside, not reviewed line by line.

## Field-box gotchas found (now documented on the page)

None are WFC-specific and all cost real time: a local `fun` nested inside a lambda
trips a K2 codegen assertion (`has no callableId`); a named function that touches the
box's implicit `Box` receiver — and `println` does — also cannot be code-generated
(`No mapping for symbol: SCRIPT_IMPLICIT_RECEIVER`, which is what the transcript
harness caught); `h`, `sky` and `error` all resolve to Field DSL names ahead of a
script-local `val` inside a lambda with its own receiver; and `display(channel, x)`
**replaces** that channel rather than appending, which silently ate two text panels and
put a one-frame caption flicker in the video.

## Risks

One-line: the only change outside new files and the WFC family is the additive
`Img.upscaleNearest` in `imaging.kt`, and the `wfc.kt` symmetry fix makes previously
under-constrained learned models *more* constrained, so a caller relying on the old
lax behaviour could newly hit a contradiction — no such caller exists in-tree, and
`solveWithRetries` is the documented remedy.

## Recommendation

**Merge.** The mission's deliverable is complete and every claim on it is either
eyeballed or asserted by a test that runs in the build. It also carries a real
correctness fix to `wfc.kt` with a regression test demonstrated to fail without it,
first public coverage of the entire `FloatArray` half of the API, and a replayable
demo that was actually replayed (and which caught a real defect in doing so). The
follow-up worth doing separately is a full-corpus docs build to confirm no other
page regressed.
## Execution log

- Baseline `stdlib.wfc.smoke` PASS on warm build, port 9740.
- Ports used: 9740 (test JVM), 9741 (docs runner). 9477 left alone.
- Disk at start 48 GiB free, 46 GiB at the end — no ENOSPC risk to the captures.

---

# Second pass — making the state live-codeable

Picking the branch up at `a16859ed`. The first pass shipped the page; this pass closes its
named follow-up and then goes after the thing the page could only *describe*: that WFC
state is worth looking at. Everything below is mine unless it says otherwise; the first
pass's claims are carried over as written and were not re-derived, except where a suite
listed here re-ran them.

## 1. The full docs build — the first pass's own follow-up (done, twice)

`tools/build_zensical_docs.py --strict`, site-wide, port 9477 confirmed free first.

**Run A, at `a16859ed` (the branch as the first pass left it):** `No issues found`,
`checked 67 page(s), 553 asset ref(s)`, runner summary
`regenerated=13 skipped=283 (cache_hits=9 reused_existing=274 missing=0 failures=0)`,
elapsed 174 s, whole build ~8 min. All nine `wfc_examples` assets were **cache hits**, so
the page's committed captures still match the code that made them. No other page broke.

That is the first pass's stated gap closed: nothing else regressed.

Eleven *other* pages' assets came back modified — the force-recapture and `ui drive`
figures, which have no cache entry and so regenerate every build. That churn is not mine
and I reverted it rather than committing it (`git checkout -- public/docs/assets`).

**Run B, after all of tonight's work:** see the Verification section below.

## 2. `WfcSolver` and `WfcRules` draw themselves

The luxurious-`.h` theme, applied to a solver whose whole interest is its intermediate
state. Both types now implement `ToHTML`. **`ValueVisualizer.kt` was not touched** — the
sibling branch `nightly/2026-07-27/2-imaging-inline-visualizers` is rewriting it, and this
branch must merge cleanly against that.

Where the code lives matters: `wfc.kt` gains exactly two one-line `override fun toHTML() =
…` bodies and keeps its "integer tile IDs and nothing else" footprint; every pixel is in
`wfc_field.kt`, which was already the bridge file.

**The solver view** (`wfcSolverHtml`) — three panels, one pixel per solver cell, so screen
pixel `(x·f, y·f)` really is cell `(x, y)`:

| panel | colour | scale |
| --- | --- | --- |
| possibilities left | tiles still possible in that cell | viridis, fixed `1` (decided) … `tileCount` |
| entropy | weighted Shannon entropy, nats — what the solver actually sorts on | magma, fixed `0` … `fullSetEntropy()` |
| decided tiles | the tile a cell settled on, stable golden-angle hue per id | dark grey = undecided |

plus a header (status, `368/816 cells decided (45.1%)`, untouched count, contradiction
count, last observation) and a labelled gradient swatch under each heatmap.

**The rules view** (`wfcRulesHtml`) — one adjacency matrix per direction (row = tile,
column = the neighbour that may sit at that offset, teal = allowed) plus the weights strip,
organised around the three failures a `println` cannot show and an
`Array<Array<LongArray>>` actively hides: an **empty mask** (whole row magenta — that is
unsatisfiable, not strict), an **asymmetry** (header, via `findAsymmetry()` — the check
that caught the first pass's real bug), and a **zero weight** (off the ramp, because a tile
that can never be chosen is not the same as a rare one; `fromSampleGrid` hands one out
silently to any tile ID its sample never contained).

**Range policy**, following the discipline the 2026-07-27 branch established:

- every scale is **named in the caption** with both endpoints in units;
- domains are **fixed by meaning, not by the frame** — auto-ranging makes an animated
  collapse appear to stand still, because the ends of the ramp move with the data;
- non-finite and off-scale values are **excluded from the statistics and marked**, never
  clamped onto an end where they read as a legitimate extreme. Two real cases, not
  hypothetical ones: a cell with **zero** possibilities (clamped onto `1..N` it lands at the
  *decided* end, so the one failure you must see reads as success) and **NaN** entropy,
  which is reachable because `fromSampleGrid` gives weight 0 to unseen tiles and a cell
  whose only survivor has weight 0 has genuinely undefined entropy;
- a **degenerate domain** (no finite values, or zero width) is drawn mid-ramp and declared
  flat rather than divided by zero.

New public API in `wfc_field.kt`: `WfcHeatmap`, `possibilityCountHeatmap`/`Img`,
`entropyGrid`, `WfcRules.fullSetEntropy`, `entropyHeatmap`/`Img`, `resolvedTileImg`,
`wfcTileColor`, `wfcSolverHtml`, `WfcRules.adjacencyImg`, `WfcRules.weightsImg`,
`wfcRulesHtml`, `WFC_CONTRADICTION_COLOR`, `WFC_UNDEFINED_COLOR`, `WFC_UNDECIDED_COLOR`,
`WFC_ALLOWED_COLOR`, `WFC_DISALLOWED_COLOR`; `toString()` on both `WfcSolver` and
`WfcRules`.

## 3. Verified to pixels, not to compilation

**Decode-and-sample.** `WfcSmokeTest` grew 7 → 12 cases. Every visual assertion pulls the
base64 out of the HTML the view *actually emits*, decodes it with `ImageIO`, and reads the
pixel for a named cell:

- a decided cell reads as the decided end of **both** ramps; an untouched cell as the open
  end; and a decided cell is asserted *not* to be within 24/255 of the untouched colour,
  which is what an inverted or auto-ranged scale would produce;
- a contradicted cell reads as `WFC_CONTRADICTION_COLOR` in the count panel and
  `WFC_UNDEFINED_COLOR` in the entropy panel, and *not* as the decided end;
- NaN entropy from a zero-weight tile is the marker, not the ramp floor, and the domain is
  still `0..ln 2` — i.e. the NaN was excluded from the stats;
- a degenerate `1..1` domain is `flat` and mid-ramp;
- in the rules view, an allowed pair is teal at `(b, a)`, an empty mask is magenta across
  the **whole row** rather than three ordinary dark cells, a zero weight is the marker, and
  the header's density line (`22.2% of pairs allowed`) is checked against the pixels' own
  model;
- panels are asserted to be exactly `width × height` pixels, and `toHTML()` is asserted
  equal to the free function, so the docs page and a live box cannot drift apart.

**The assertions bite.** Replacing the non-finite guard with `if (false)` (and dropping the
flat branch) makes `stdlib.wfc.smoke` fail — `IllegalArgumentException: Cannot round NaN
value` from `heatmapOf`, caught at the contradiction case. Kotlin's `roundToInt()` throws on
NaN rather than returning 0, so without the guard the visualizer does not merely mislead, it
takes the panel out; `wfcSolverHtml` also catches per-panel and degrades to the header, so a
box's whole output survives either way. Guard restored, suite green.

**Eyeballed, and it changed the design.** The first captured figure showed the count panel in
`magma`, whose mid-ramp pink sits a shade from the contradiction magenta — a perfectly
healthy collapse frontier read as *a field of contradictions*. A marker colour is only a
marker if it is outside its own ramp, so counts moved to viridis (no pink, magenta marker)
and entropy to magma (no cyan, cyan marker). The rule, the reason, and the mistake are all
recorded in the code and on the page. I also narrowed the panels once so the triptych fits
one row. Nothing about that was visible from a passing test.

**Docs.** Two new captures, both looked at: `inline-view.png` (a 34×24 solver at 45% beside
a contradicted 6×6) in a new subsection of §5, and `inline-rules.png` (strict → widened →
learned) in §7 where rule debugging belongs. Each fence prints a read-back channel so the
picture can be checked against the numbers it was drawn from, and I did: `368/816 decided`,
entropy `0..1.318` nats with 0 undefined, one cell with no possibilities left; `22.2%` /
`77.8%` / `88.9%` of pairs allowed, 4 empty masks then 0, 1 tile of weight 0. The strict
set's four magenta rows are one per direction, which is what its authored pairs imply.

## 4. The demo transcript, replayed and extended

`dev_docs/agents/transcripts/wfc_worked_example.yaml` gains an eighth box, "8 — The
solver's own inline view": a solver stepped to 45% and an over-constrained 6×6 that
contradicts, each handed to `display()` as `solver.h` and nothing else — no colormap call,
no montage, no channel plumbing. That contrast is the point of the box.

Replayed through the real engine, not type-checked by inspection:
`examples.demo-transcript.exec` PASSes over all 27 transcripts (`applied=18` for this one,
no diagnostics), and with `AFIELD_DEMO_TRANSCRIPT_VERBOSE=1` the new box's own output reads

```
WfcSolver(34x24, 5 tiles, Running, 368/816 decided)
  entropy range drawn: 0 .. 1.318 nats, 0 cell(s) undefined
WfcSolver(6x6, 3 tiles, Contradiction, 5/36 decided)
  cells with no possibilities left: 1
```

— cell-for-cell what the docs capture of the same code shows. Committed starting point
regenerated (8 boxes) and the materializer drift guard passes; the gallery page and the
corpus README say eight and say what the box is for.

## 5. Also done

- Removed a dead `WfcSolver.resolvedCellCount()` extension in `wfc_field.kt`: the class has
  a member of the same name, a member always wins, so nothing could reach it. It was the
  branch's only Kotlin warning, which the 6am AI-CI loop would otherwise have triaged.
- `public/docs/reference/{data,index,visualization}.md` and `dev_docs/kdoc_worklist.md`
  regenerated for the new API; `docs.codebase-reference.generate` passes.

## Verification — this pass

Every line below I ran tonight, after the changes:

- **`--tag stdlib` — 11/11 PASS**, including `stdlib.wfc.smoke` (12 cases),
  `stdlib.wfc.gallery` and `docs.codebase-reference.generate`.
- **`--tag agents` — 7/7 PASS**, including `examples.demo-transcript.exec` over all 27
  transcripts and `examples.agent-transcripts.materialize`.
- **`stdlib.wfc.smoke` fails as designed** when the non-finite guard is removed (above).
- **`tools/check_doc_assets.py` — OK, 557 asset refs across 67 nav pages.**
- **Site-wide `build_zensical_docs.py --strict`, twice.** Run A at `a16859ed`: `No issues
  found`, 553 refs, `failures=0 missing=0`, all nine wfc assets cache hits. Run B after all
  of tonight's changes: `No issues found`, `checked 67 page(s), 557 asset ref(s)`,
  `OK — every referenced asset is committed`, runner summary
  `regenerated=4 skipped=294 (cache_hits=294 reused_existing=0 missing=0 failures=0)`,
  elapsed 66.7 s — and **all eleven** `wfc_examples` assets, both new captures included,
  came back as cache hits. Neither run broke any other page; both left the same handful of
  never-cached force/`ui drive` figures on other pages dirty, and I reverted that churn
  rather than committing it.
- Two figures and one re-captured figure read by eye against the page's own prose.
- Ports: 9742–9749 for test JVMs, 9477 for docs builds. Disk 45 GiB free throughout.

## Not verified / carried over

- The first pass's "Not verified" list stands except for its first item, which this pass
  closed. Its mp4 static tail and its un-reviewed `kdoc_worklist.md` diff are unchanged.
- **`scalar-field.png` and `contradiction.png` were re-captured** — inserting a fence shifts
  the block index their cache key includes. `contradiction.png` is byte-identical bar 154
  edge-antialiasing pixels (max delta 7, measured). `scalar-field.png` differs by a vertical
  layout shift; I read the new one and it shows exactly what the page claims (6 levels, 180
  patterns, 1×1 mean 0.26 speckle, 3×3 mean 0.61 horizontal bands, iso-contour `FLine`s) but
  I did not diff it region by region against the old one.
- **The views were never opened in a live editor by hand.** They were exercised through the
  docs runner (a real browser rendering a real box's output) and through the transcript
  harness, which is the same path, but nobody clicked anything.
- **No dark/light-theme check.** The panels hardcode dark-mode greys, like `Img.toHTML` does.
- **The `h`/`h2` distinction is untested here.** Only `.h` is used.

## Risks

One line: the only edits outside the WFC family are generated reference pages, and the two
`ToHTML` implementations are additive — but they do change what an *existing* box shows when
it evaluates a `WfcSolver` or `WfcRules`, from a property tree to a picture, and `wfc.kt`
now names one symbol in `wfc_field.kt` (so the two files must ship together, which they
always have).

## Recommendation

**Merge.** The first pass's own named follow-up is closed with a clean site-wide build, and
the extension is the branch's thesis carried one step further: the page argues WFC state is
worth looking at, and now looking at it costs one expression. Both views are verified the
strong way — decoded PNGs, sampled pixels, and a mutation demonstrated to break the suite —
and the design changed *because* I looked at the output, which is the evidence that the
looking was real. The demo was replayed and its output matches the figures cell for cell.

Nothing here is half-finished: I stopped after the rules view rather than starting a third
generative mode or backtracking, because either would have been code without an eyeballed
picture by the end of the night, and that is worse than stopping.

## Execution log — second pass

- Started from `a16859ed`; commits `a54c1a82` (solver view + tests), `bbbf1cf9` (docs
  section + captures + reference), `73e83b70` (transcript box), `e99efda9` (rules view + §7
  + warning cleanup).
- Ports: 9742/9743/9744/9745/9746/9747/9748/9749 test JVMs, 9477 docs. No sibling worktree
  touched; every command was worktree-scoped.
- `df -h /` reported 45 GiB free at the start and 44 GiB at the end — the two full docs
  builds account for the difference; no ENOSPC risk to any capture.

# fline-close-node-robustness — 2026-08-01

Branch `nightly/2026-08-01/2-fline-close-node-robustness`, based on `7abe8736`.

## Plan

`FLine.Close` is `Node(0)`: it stores no point, and `Node.to` is `storage[0]`, so `.to` on a
`Close` throws `IndexOutOfBoundsException` (FLine.kt:174-179). Last night (0954ebfb, 673e7d5c)
closed the *trailing*-`Close` cases — every `nodes.last().to` — and added the accessors that make
the right answer expressible: `Node.toOrNull` (FLine.kt:188), `FLine.endPointOf(i)` (FLine.kt:219),
`FLine.currentPoint` (FLine.kt:237). What it explicitly did **not** fix is the *interior* case:
`nodes[i - 1].to` inside a loop, which throws whenever a `Close` is followed by more geometry.

### Refinements against the real code (pass 0, before writing anything)

1. **The orchestrator's site list is incomplete.** Grepping the whole graphics package turns up
   more than the eight sites cited. Confirmed extra candidates already:
   - `FLineGeometry.kt:1068` `windingNumber` — `node.to` on *every* node with no `Close` branch,
     unlike its two siblings `signedArea2D` (:1114) and `centroid2D` (:1161) which *do* skip
     `Close`. `windingNumber` is what `FLine.contains(point)` (:1100) calls — a point-in-polygon
     test, i.e. a function whose entire domain is closed shapes.
   - `FLineGeometry.kt:1149` — `centroid2D`'s *degenerate* branch iterates `it.to` with no `Close`
     guard, even though the main loop three lines later has one.
   - `FLinesAndJavaShapes.kt:2571/2579/2602/2610` — `intersectX`/`intersectY` carry the exact same
     `get(i - 1)` shape as `intersect`, including the same `i = 0` off-by-one.
   So: audit first, then fix; report the real count, not the briefed one.

2. **Who actually produces an interior `Close`?** This is the blast-radius question and it decides
   whether this work is load-bearing or decorative. Verified so far:
   - **AWT does not.** `javaShapeToFLine` (FLinesAndJavaShapes.kt:2247-2251) and
     `javaShapeToFLineFlat` (:2183-2187) both turn `SEG_CLOSE` into a `lineTo(lastMoveTo)`. The
     07-31 finding is correct — text and glyph outlines arriving through AWT carry no `Close` at
     all. So the `sentenced` workspace's glyph geometry is *not* on this path, and I should say so.
   - **Field's own stdlib does.** `afield.stdlib.arrow(...)` (StandardDrawing.kt:279) calls
     `appendHead` (:309) twice when `startHead && endHead`, and `appendHead` ends with
     `target.close()` (:322). A double-headed arrow is therefore
     `MoveTo LineTo | MoveTo LineTo LineTo Close | MoveTo LineTo LineTo Close` — an interior
     `Close` from a shipped drawing primitive with no user cleverness at all.
   - **The path mini-DSL does.** `FLine.kt:1430` maps `'z'` to `f.close()`, so any `"…z…m…"`
     program produces one.
   - **`+=` does.** `FLine.plusAssign(other: FLine)` (FLine.kt:1151) copies nodes verbatim, so
     appending anything to a closed line produces one.
   Plan: turn the double-headed `arrow()` into a *test fixture* and drive it through the fixed
   functions. A real shipped primitive beats a synthetic corpus as evidence.

3. **The dashing regression test.** Confirmed: `673e7d5c` both added the multi-subpath fix
   (`pieces()`, FLineGeometry.kt:2101-2105) and switched `dashesHaveTheLengthTheyWereAskedFor`
   (ArcLengthSpacingTest.kt:759) from `all()` to `allSubpaths()`. The switch was right for the
   three tests that measure a *Euclidean* gap between consecutive samples (only meaningful within
   a subpath) and wrong for this one, which measures each dash's *own* drawn length — a
   within-dash quantity that is perfectly well defined on a whole multi-subpath line. So the
   published "1428% → 0.0%" number is not reproducible from the tree. Restore whole-line
   measurement for dashing specifically, and demonstrate it goes red against a reverted fix.

### Steps

1. **Audit.** Enumerate every interior-index node read in `afield.graphics`; classify each as
   Close-unsafe / already-guarded / unreachable. Write the table into the commit.
2. **Corpus.** A shared set of interior-`Close` FLines: the real `arrow(startHead, endHead)`, a
   two-contour glyph-ish outline (`close()` then `moveTo`), a closed contour followed by an open
   tail, a leading `Close`, and repeated `Close`es.
3. **Red then green, per site**, with the real exception text quoted in the commit message.
4. **Restore the dashing regression test**, whole-line, and prove it red on a reverted fix.
5. **Fix `intersect`'s `i - 1` off-by-one** (and the same bug in `intersectX`/`intersectY` if the
   audit confirms it).
6. **Pixels.** A vector before/after figure via `drawToSVG` (someSkiko.kt:238) — `outputTo` (:323)
   is already `Close`-safe, so the render path itself will not be the thing under test. Look at
   it, do not just generate it.
7. **Docs + demo + worklog.**

## Execution

Six commits. The plan held; the audit found more than the brief listed, and two of the "landmines"
turned out not to be armed.

| commit | what |
|---|---|
| `63aa8897` | the plan above |
| `84855fc6` | the interior-`Close` fixes — eleven sites, `byReversing` and `windingNumber` rewritten, the `intersect` off-by-one |
| `e81e3e63` | the restored multi-subpath dashing regression + a guard on its corpus |
| `5171a852` | the three before/after vector figures |
| `25fd4cd8` | the blast radius, measured |
| `5cf1db57` | dev doc, replayable demo, citation-drift guard |

### Files

| file | why |
|---|---|
| `src/jvmMain/kotlin/afield/graphics/FLineGeometry.kt` | ten `.to` reads through `endPointOf`; `byReversing` rewritten subpath-wise; `windingNumber` rewritten to walk subpaths; `bySubdividing` no longer drops a `Close` |
| `src/jvmMain/kotlin/afield/graphics/FLine.kt` | `byFiltering`, `fast2DBounds` |
| `src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt` | `intersect`/`intersectX`/`intersectY` `get(-1)` off-by-one; `_flineToJavaShape` emits `closePath` and tolerates a leading `LineTo`; `CubicSegment3(FLine, index)`; `copyDepthFrom` |
| `src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt` | register the new demo transcript (it fails the build as UNCLASSIFIED otherwise) |
| `src/jvmTest/.../InteriorCloseNodeTest.kt` | new — 10-curve corpus, 24-operation audit sweep, per-site tests, blast radius |
| `src/jvmTest/.../InteriorCloseFigureTest.kt` | new — the figure generator, plus assertions that run every build |
| `src/jvmTest/.../CloseNodeCitationDriftTest.kt` | new — pins 46 `file:line` citations |
| `src/jvmTest/.../ArcLengthSpacingTest.kt` | dashing regression back on `all()`, plus a corpus guard |
| `src/jvmTest/.../ClosedLineNodeAccessTest.kt` | one assertion updated — my `byReversing` change refutes it |
| `dev_docs/fline_close_nodes.md` | new — the whole `Close` contract |
| `dev_docs/arc_length_spacing.md` | its caveat struck through, with two corrections to it |
| `dev_docs/assets/interior_close/` | six vector figures + one `showPlaneGrid` grid |
| `dev_docs/agents/transcripts/close_node_demo.yaml` + `public/docs/assets/agent_transcripts/close_node_demo/` | the replayable demo and its materialized starting point |

### Verification

Everything below was run, not reasoned about.

- **Red first, quoted.** The audit sweep found **49 operation/curve pairs across 10 operations**
  throwing `IndexOutOfBoundsException`. Green after: `afield.graphics.*` **74/74**, full
  **`jvmTest` 957/0**.
- **The dashing regression, red on a reverted fix.** `pieces()` block commented out →
  `byDashing produces wrong-length dashes on: twoSubpaths 1428.2%`, matching `673e7d5c`'s published
  1428% to a decimal. Restored → `worst relative error=0.000`.
- **The blast-radius test, red on a reverted fix.** `_flineToJavaShape`'s `closePath` removed →
  `the thickened outline is a single region, so it is a U rather than a closed band`.
- **The figures, looked at.** Rasterised with `rsvg-convert` and viewed. Not black, not garbled:
  the reversal "before" is visibly a triangle with a diagonal across the square; the subdivision
  "before" is visibly missing both contours' closing runs; the dashing "before" has one enormous
  dash running diagonally across the whole figure. All three "after"s are correct.
- **The demo, replayed.** `applied=4 skipped=0 errors=0` in a fresh scratch document, with the box
  outputs read back (`683 of 800 units retraced (85%)` etc.).
- **`examples.demo-transcript.exec` PASS**, **`examples.agent-transcripts.materialize` PASS**, both
  through `python3 tools/run_field_tests.py --id …`.
- **The citation guard, proved non-vacuous** by perturbing one entry.

Not verified: nothing in the GPU/renderer path was touched or exercised — I read every render path
(`outputTo`, `planeFigureHtml`, `ThreeJsViewer`, `StandardDrawing`'s point writer,
`FLineInstructionTrajectories`) and all of them already had a `Close` branch, which is why the
figures render identically before and after except where the *geometry* changed. No docs site build
was run (disk).

### Refuted along the way

Four claims I or the brief started with that did not survive:

1. **"a before/after dashing figure at the base tree".** `byDashing`'s multi-subpath fix already
   landed in `673e7d5c`, so at `7abe8736` that figure is byte-identical to today's — md5-checked.
   What was missing there was the test. The panel's "before" is a surgical revert instead, said so
   in the commit and the doc.
2. **"`Dyn4jScene.flattenClosedFLineLoops` needs the `Close`".** Its name says so and it switches on
   `SEG_CLOSE`, but its final `flush(requireClosed = false)` accepts the subpath anyway and a dyn4j
   polygon is implicitly closed. Passes either way. Recorded rather than fixed decoratively.
3. **"the boolean ops were broken by the dropped `Close`".** `java.awt.geom.Area` fills an open path
   by implicitly closing it, so a union of two open squares is a union of two squares. Also passes
   either way; now pinned at three points regardless.
4. **"the reversal of a closed square starts at its `moveTo`"** — `0954ebfb`'s own assertion. It
   does not: that convention puts the `close` immediately after the `moveTo`, which is not a closed
   square, and it does not round-trip. Assertion and its comment updated in the same commit as the
   change.

### Risks

- **`byReversing`'s output convention changed.** Node count, subpath count and `Close` count are all
  preserved and it is now an involution, but the reversal of a closed contour starts at a different
  corner than it used to. Only two production callers, both in `FLineGeometryExamples` on open
  lines, where the output is unchanged; `GlyphArcLengthTest`'s node-count assertion still holds.
- **`windingNumber` changed semantics** (subpath-aware, implicit closure). The three existing
  callers close their squares with an explicit `lineTo`, so their numbers do not move — but a user
  relying on the old cross-subpath behaviour would see a different answer. It was wrong before.
- **46 pinned line numbers** are a maintenance surface. That is the trade for citations that cannot
  silently rot; the failure message says exactly what to do.
- **Disk** ran from 11 GiB to ~7.8 GiB free over the pass. No docs-site build was attempted.

### Recommendation

**Merge.** The core is a real bug class closed with red-then-green evidence at every site, one
genuine production defect found and fixed (`byJavaOffsetting` losing an edge), a regression test
restored and proved to bite, and the two behaviour changes are argued, tested and documented where
a reader lands. The blast radius is honestly small and says so.

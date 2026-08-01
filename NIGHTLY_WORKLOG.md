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

(filled in below as the pass proceeds)

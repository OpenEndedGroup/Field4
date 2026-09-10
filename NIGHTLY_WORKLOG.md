# nightly/2026-09-10/2 — Chart: clip to the plot, wrap the note

Base `c08b19003`. Worktree `/Users/marc/field4-worktrees/2026-09-10-2-chart-clip-and-note`.
Port 19102.

## Step 0 — falsification verdicts (measured, not inherited)

Both claims reached me through two summaries. I reproduced each in the real render path
(`Chart.toFLines()` → `FLine.fast2DBounds()` union, which is exactly the rectangle
`linesToHTML` stamps onto the wrapper as `data-afield-bounds-*` and the box output panel
then fits). Measured at `c08b19003`, `ChartGallery` on `run-jvm-main`:

- **(i) `Chart` does not clip `MarkKind.LINE` to `plotRect` — CONFIRMED.**
  A closed 2 × 3 outline charted into `xDomain = 0.82..1.06`, `yDomain = 1.32..1.56`
  on a 780 × 440 frame: the drawn series reaches **5137 px outside `plotRect`**
  (`plotRect` = 73,40 677×346). Whole-figure bounds `-5063,14 5815×4437`, so a panel that
  fits those bounds draws the figure at **10% of its declared size**. The second-hand
  claim said "some five thousand pixels" — that number was right.

- **(ii) `Chart.note` is a single unwrapped line — CONFIRMED, with a correction.**
  The tetgen corner figure's own 142-character note measures **1197 px on one line**
  against **711 px of room** on a 780 px frame. Whole-figure bounds `10,14 1240×423`,
  so the figure is drawn at **63%** of its declared size. The brief that reached me said
  "~70% was measured on that page". The real number is 63%. Recorded because this loop's
  own record says a brief written because a number was wrong has itself carried a wrong
  number — this is that again, at 7 points.

- **(iii) `plotRect()`'s KDoc (`charts.kt:904-911`) asserts the opposite of the behaviour
  — CONFIRMED.** It reads "the rectangle the axes bound and *the data is clipped to*".
  Nothing in the file clipped anything to it. Verified by reading every mark builder, see
  the table below.

- **Corroboration checked myself:** `9f7ffc4f2` does contain a working Liang–Barsky clip,
  in `public/docs/tetgen.md`'s fence, lines 386–440 of the post-image. It clips *segments*
  and emits `Double.NaN` between disjoint runs so one outline stays one series. It is
  sound, and it is what I lifted. It did **not** touch the note, so the tetgen corner
  figure is still published at 63% today.

- **Not confirmed and not asserted:** the five existing gallery figures. All five are
  inside their own frames — see the population section for the number and its predicate.

## Which mark kinds share the defect (read, not guessed)

| kind | before | after |
| --- | --- | --- |
| `LINE` | no clip at all (`lineLines`) | Liang–Barsky per segment, runs split into subpaths |
| `POINTS` | no clip at all (`pointLines`) | a dot whose centre is outside `plotRect` is dropped |
| `AREA` | no clip at all (`areaLines`) | fill Sutherland–Hodgman, outline as `LINE` |
| `BARS` / `HISTOGRAM` | y **was** clamped (`coerceIn`), x was not | x clamped too, wholly-outside bars dropped |
| `HRULE` / `VRULE` | spans `plot` on one axis, unclamped on the other | dropped when the value is off the axis |
| `BAND` | spans `plot.x..plot.x+plot.w`, y unclamped | y clamped to `plot`, dropped when disjoint |
| expectation band | same as `BAND` | same as `BAND` |

## Plan

1. A pixel-clip layer in `charts.kt` — `clipSegment` (Liang–Barsky), `clipPolyline`,
   `clipPolygon` (Sutherland–Hodgman) — applied in *pixel* space against `plotRect`,
   because that is the exact box the KDoc names. One `Chart.clip` escape hatch,
   defaulting `true`, so the KDoc stays true by default and says so.
2. Wrap `note` (and `title`, which is the identical defect with the identical fix) to the
   figure width, cap the wrap, and account for the line count in the layout insets.
3. Make `plotRect()`'s KDoc true.
4. **The sixth gate**: `ChartGallery` now asserts, for every figure it renders, that the
   drawn geometry is inside the declared frame — plus a counterfactual per regression case,
   so a refactor that disables the clip fails loudly instead of going tautological.
5. Survey the population with a stated predicate over `git ls-files`, re-capture only what
   changed, open the images.

## Files changed

(filled in as the work lands — see the commits on this branch)

## Verification

(see below)

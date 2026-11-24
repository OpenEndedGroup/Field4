# Addressable paint strokes (`PaintStrokes`)

`src/jvmMain/kotlin/afield/graphics/paint/PaintStrokes.kt` keeps a painting as an ordered list of
`StrokeRecord`s (path, pressure, brush kind, width, colour, hiding, group, hidden) with stable
addresses `s<id>`, edits them by JSON op batches (one undo step per batch, all-or-nothing), and
paints the list onto a fresh `PaintCanvas` on every `render()`. User docs:
`public/docs/paint_canvas.md#strokes-kept-as-data`; demo transcript
`dev_docs/agents/transcripts/addressable_paint_strokes.yaml`. Method after Klad
(https://github.com/klad-app/klad, source-available non-commercial); no Klad code or spec text
was copied.

## Why re-painting is exact, and where an edit is not local

Each stroke is painted with a fresh brush whose bristles are seeded from `seed * 1_000_003 + id`,
and its mark noise keyed with the same seed (`PaintCanvas.stroke(seed = ...)`). Nothing carries
from one stroke's brush to the next, so:

- the same records paint bit-identical pixels (`PaintStrokesTest.theSameRecordsPaintTheSamePixels`);
- a stroke's pixels depend only on the canvas under it when it is laid, i.e. on the strokes
  before it in the list that it touches.

Consequences, measured on Sprout, the demo creature (700 x 500 px, 24 strokes), recolouring the leaves,
bending the mouth and moving the smudge (box 2 of the transcript): 14,493 of 350,000 pixels change,
and 1 of them lies more than 20 canvas units outside the old and new bounds of the edited strokes
(bounds grown by half the brush width). The pixel-locality test pins the cleaner case: recolouring
a stroke that nothing else overlaps leaves every pixel of the other half of the canvas bit-equal.

What is *not* local: any stroke laid later and overlapping an edited one re-picks up the new wet
paint, so it changes too. A moved stroke re-blends where it lands (the smudge). This is the
physical simulation, not a defect, and the docs say so.

What is lost versus painting live: a brush that carries dirt from stroke to stroke (`PaintToolbox`,
"Brushes that stay dirty") is not modelled — each record gets a clean brush. A `carry` flag on
records would bring it back at the cost of locality (every later stroke of that brush would
depend on every earlier one).

## Not done

- AI4 Studio tools (`paint.strokes.read` / `paint.strokes.apply`) wrapping `describe()` and
  `apply(json)`; the Studio lives in `dev_docs/agents/transcripts/ai4_paint_studio.yaml`.
- Reading pinned notes from the drawing layer (`notation`) rather than `pin(...)`; that needs a
  mapping from world coordinates to the canvas output's canvas units.
- Klad's MIT drawing-craft skill as a prompt for the Studio's painter.

## Fill rule of area regions

`PaintCanvas` area methods (`work`, `stipple`, `glaze`, `hatch`, `erase`) pass the region through
`FLine.resolveFillRule()` before handing rings to the crate, whose mask is even-odd. A region with an
explicit `fillRule` is therefore filled under that rule (exactly as Gezira does, pinned by
`PaintFillRuleTest`); an unset rule keeps the crate's even-odd fill. Fixing it on the JVM side keeps
the crate untouched and shares the Area resolution `FLine.tessellate` already uses.

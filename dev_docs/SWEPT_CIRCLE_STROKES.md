# Swept-circle strokes: measurements and traps

Code: `bySweptCircleEnveloping`, `sweptCircleStroke`, `sweptCircleDegenerateSpans`,
`thinPenSamples`, `penCorners`, `outlineDistanceTo` in `src/jvmMain/kotlin/afield/geometry/path/FLineThickening.kt`; tests in
`src/jvmTest/kotlin/afield/geometry/path/SweptCircleEnvelopeTest.kt`; page section
`public/docs/fline_examples.md#variable-width-strokes-the-swept-circle-envelope`; starting point
`dev_docs/agents/transcripts/variable_width_strokes.yaml`. `Mark.penKnots` is in
`src/jvmMain/kotlin/afield/ui/notation/Notation.kt` (test in `NotationTest`). Method from
https://luthert.web.illinois.edu/blog/posts/775.html (CC BY-NC-SA; method only, no figures or text).

## Measurements (2026-11-11)

- Cubic `(0,0)…(240,40)`, radius `2 + 104 t²(1−t)`, against a union of 1500 discs drawn with 64 segments
  per quadrant: envelope 0.025 (flatness 0.02), `byVariableRibboning` 2.0 (its square ends; the end
  radius is 2).
- On the docs page the reference is `byJtsVariableBuffering(1500)`, whose discs are 8-segment
  quadrants, so the envelope reads 0.22 there: that number is the reference's polygon error.

## Traps

- Default `flatness = 0.25` makes disc/cap polygons inscribed: at r = 30 the shell area is 0.4 %
  low. Pass a smaller flatness when measuring.
- `bySweptCircleEnveloping` takes derivatives by central differences over its samples, so
  degenerate detection is at sample resolution there; `sweptCircleDegenerateSpans` (knot input) is
  exact to ~1e-9.
- The shell is a JTS union of strip polygons (runs of up to 32 plain intervals), one hull per
  interval that is not plain (cusp, missing flank, invalid strip) and discs. Before the strips it
  was one hull per interval. A union of touching pieces leaves zero-area rings; `withoutSlivers`
  drops them, or `outlineDistanceTo` reports the sliver as a 10-unit error.
- A union of noisy discs is the envelope of the noise *peaks*: comparing a smoothed stroke with
  the raw stroke's outline makes smoothing look like a 5 % loss of area. Compare against the
  noise-free stroke (as `thinningKeepsTheStrokeAndDropsTheWobble` does).
- In box code, a box `val stroke` clashes with `NotationBuilder.stroke` inside `notation { }`.

## Follow-up pass (2026-11-11, branch `claude/strokes-next-b6efee`)

The first landing recorded no limitations list; these were collected from the code, the page and
the traps above, and ranked by what a person drawing in Field meets first.

Done:

1. **Tablet-density input.** A 1000-sample stroke with 3 % pressure noise had 337–432 spans with no
   envelope (the wobble outruns a slow pen) and took 1.4–2.4 s. `thinPenSamples` averages the
   radius over ±3 units of path (positions untouched, so corners stay sharp), then RDP in
   `(x, y, r)` at 0.5: 61 knots, no degenerate span, 0.94 from the noise-free stroke against 1.65
   for the raw one.
2. **Speed.** Strip polygons instead of per-interval hulls (same 1000/300/50-knot strokes, second
   call): 2429 → 1410 ms, 2053 → 849 ms, 100 → 20 ms. Thinned, the 300-knot stroke takes 226 ms.
3. **Uneven knot spacing.** The uniform Catmull-Rom tangent made a short span next to a long one
   overshoot by ~8 units (found on thinned knots; it happens on raw input wherever the pen slows).
   Interior tangents are now the neighbours' difference scaled by `len[span] / (len[i-1] + len[i])`,
   identical for even spacing. This changed the existing figures, which were recaptured.
4. **Corners (joins).** `corners` on `sweptCircleStroke` / `sweptCircleBeziers` /
   `sweptCircleDegenerateSpans` (indices, as in `clothoidSplineFrom`): one-sided tangents, both
   stations kept, the corner's disc as the join, flanks broken there. `penCorners(knots, degrees)`
   finds them. A kept "v" corner is within 0.025 of the round-joined JTS buffer; smoothed it is 5.3
   out.
5. **Pen-pressure input.** `Mark.penKnots(width, exponent)`; a mouse stroke reads as full pressure.
   The `onStroke` example on the page is not captured: `onStroke` fires only for strokes drawn by
   hand ("changes this code makes itself do not call it back"), and a scripted drawing-layer drive
   with real pen pressure does not exist. The box was run through the backdoor (it compiles and
   registers).
6. **Vulkan versus SVG.** Vulkan fills an `FLine` through `FLine.tessellate` (earcut, holes kept),
   which is CPU code: `shellTessellatesWithItsHole` checks a ring stroke's triangles cover the
   annulus to 1 % and not the centre. Confirmed on the GPU afterwards (2026-11-11, backdoor,
   `graphics_renderer_launch` parent): the "o" and a cornered "z" tessellated into one `Mesh`
   (`primitiveType = triangles`, passthrough shaders from lesson 0), 804 vertices / 800 triangles.
   The native-window capture shows the hole empty, the corners sharp and the "z" upright, with the
   clip mapping `(x, -y)` from page coordinates. There is no automatic Vulkan fill for a filled
   `FLine`: the route is `tessellate` / `trianglesOut` into a mesh. No docs figure yet: a
   `capture="native-window"` fence on the page would be the next step.

Also pinned: a turn tighter than the radius (inner flank folds) is still the union of discs (< 0.3).

Not done, and why:

- **Other caps (butt, square, tapered).** A swept circle has round ends by construction; a taper
  is the radius going to zero, which the pen (or `radius`) already gives. Butt ends belong to
  `byVariableRibboning` / `byJtsBuffering`, which have them.
- **Exact degenerate detection in `bySweptCircleEnveloping`.** It works from a `ParameterFunction`
  radius, which has no derivative, so detection stays at sample resolution; the knot API is exact.
  Low value: pass more `samples`.
- **Curves out instead of polygons, adaptive stations, width from mouse speed, ink written back to
  the drawing layer, elliptical nibs, 3D tubes.** Each is a build of its own; they are on the page's
  "Where to go next" list as the next plan.

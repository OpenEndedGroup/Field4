# Swept-circle strokes: measurements and traps

Code: `bySweptCircleEnveloping`, `sweptCircleStroke`, `sweptCircleDegenerateSpans`,
`outlineDistanceTo` in `src/jvmMain/kotlin/afield/geometry/path/FLineThickening.kt`; tests in
`src/jvmTest/kotlin/afield/geometry/path/SweptCircleEnvelopeTest.kt`; page section
`public/docs/fline_examples.md#variable-width-strokes-the-swept-circle-envelope`; starting point
`dev_docs/agents/transcripts/variable_width_strokes.yaml`. Method from
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
- The shell is a JTS union of one hull per sample interval plus discs: cost grows with samples
  (hundreds is fine, tens of thousands is slow).
- In box code, a box `val stroke` clashes with `NotationBuilder.stroke` inside `notation { }`.

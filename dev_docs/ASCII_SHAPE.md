# Shape-aware ASCII (`ShapeAscii`)

Source: `src/jvmMain/kotlin/afield/ui/output/ShapeAscii.kt`; test `ShapeAsciiTest`; page
`public/docs/ascii_shape.md`; demo `dev_docs/agents/transcripts/ascii_shape.yaml`. Method from Alex
Harri, https://alexharri.com/blog/ascii-rendering (no code taken; essay credited on the page).

Decisions that differ from the essay:
- Glyph vectors are normalised by one global scalar, not per component. Per-component
  normalisation made every mid-grey cell match `Z`.
- No k-d tree: brute force over 95 glyphs plus a 5-bit-per-component cache is fast enough for a
  60 x 26 live `charGrid`.
- Outer (directional) circles are the inner circles pushed 2.6x away from the cell centre.

Known limits: large even grey areas (photos, UI screenshots) fill with one even glyph; matching
uses Java2D `Monospaced`, which differs from the charGrid panel's font. A `charGrid` demo must be
launched (`execution="launch"` in a docs fence), not just run. No GPU pass yet.

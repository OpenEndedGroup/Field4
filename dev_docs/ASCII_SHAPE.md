# Shape-aware ASCII (`ShapeAscii`, `ShapeAsciiGpu`)

Source: `src/jvmMain/kotlin/afield/ui/output/ShapeAscii.kt` and `ShapeAsciiGpu.kt`; tests
`ShapeAsciiTest` (headless, compiles the GPU shaders) and the smoke `ascii.shape-gpu.smoke`
(`afield.examples.four.ShapeAsciiGpuSmoke`); page `public/docs/ascii_shape.md`; demo
`dev_docs/agents/transcripts/ascii_shape.yaml`; GPU capture transcript
`dev_docs/agents/transcripts/docs_capture/ascii_shape_gpu.yaml`. Method from Alex Harri,
https://alexharri.com/blog/ascii-rendering (no code taken; essay credited on the page).

Decisions that differ from the essay:
- Glyph vectors are normalised by one global scalar, not per component. Per-component
  normalisation made every mid-grey cell match `Z`.
- No k-d tree: brute force over 95 glyphs plus a 5-bit-per-component cache is fast enough for a
  60 x 26 live `charGrid`. The cache answers every vector that rounds the same way with the glyph
  nearest the first one it met, so it differs from the exhaustive search on about 1 % of cells of
  a turning 3-D scene (98.9-99.4 % agreement in the smoke). `convert(..., exact = true)` skips it.
- Outer (directional) circles are the inner circles pushed 2.6x away from the cell centre.

GPU pass (`ShapeAsciiGpu`):
- Three passes: the user's scene into an RGBA8 UNORM `RenderToTexture` exactly `cols x rows` cells
  of the font; a full-screen fragment pass into a `cols x rows` target (one fragment per cell, the
  glyph index in red as index/255); a draw pass through a glyph atlas (`LoadedTexture.fromRawRGBA`).
  Fragment passes rather than a compute module, so it composes with the RTT lessons; the circle
  pixel lists (`circlePixels(k)`) and glyph vectors are baked into the matching shader as `const`
  arrays, so GPU and CPU compare identical numbers. UNORM, not sRGB, so the readback bytes are the
  values the shader read.
- `read()` queues both readbacks inside the pass's own `render`, before either target draws, so the
  scene and the characters come from the same frame.
- Orientation: Field's viewports are flipped (negative height) so clip-space +y is up. That puts the
  picture's top row in memory row 0 of an RTT, but a full-screen window pass sees `uv.y = 0` at the
  window's bottom; the draw and scene-view passes flip `v`. The first cut drew rows and glyphs upside
  down; the smoke's "upright" check pins it.
- Measured (smoke, Apple silicon, GPU shared with other sessions): 100.00 % agreement with the exact
  CPU search on every frame; matching pass ~1.2 ms fixed cost, 3.2 ms (10th pct) at 512 x 192 cells,
  where the CPU takes ~120 ms.

Known limits:
- Large even grey areas (photos, UI screenshots, the shaded side of a lit object) fill with one
  even glyph (`Z`, `Q`, `S`).
- Matching uses Java2D `Monospaced`, which differs from the charGrid panel's font. The GPU pass
  draws with its own atlas of the matching font, so it has no such mismatch.
- A `charGrid` demo must be launched (`execution="launch"` in a docs fence), not just run.
- The GPU pass is shape mode only (no brightness ramp), stores the glyph index in one byte (at most
  256 glyphs), and its default `ink`/`background` assume an sRGB window.
- `pos.add`/`normal.add` inside `m.set { }` in a shader box draws a `FIELD_SHADOWED_BY_RECEIVER`
  warning ("'pos' here is MappedStructure.pos, not the 'pos' this box declares") on the knot box;
  the box runs correctly. It comes from the moduleHere preamble, not from this code.
- Not done: other alphabets (box drawing, Braille, single-stroke Hershey for pen plotting) with a
  measured edge-fidelity comparison, and a contrast x directional sweep.

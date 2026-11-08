# Shape-aware ASCII (`ShapeAscii`, and on the GPU)

Source: `src/jvmMain/kotlin/afield/ui/output/ShapeAscii.kt` and `ShapeAsciiGpu.kt` (GPU helpers
only); tests `ShapeAsciiTest` (headless, compiles the transcript's GPU shaders) and the smoke
`ascii.shape-gpu.smoke` (`afield.examples.four.ShapeAsciiGpuSmoke`); page
`public/docs/ascii_shape.md`; demo `dev_docs/agents/transcripts/ascii_shape.yaml`, whose
`ascii_shape_gpu_scene`/`_match`/`_draw` boxes hold the GPU shaders; GPU capture transcript
`dev_docs/agents/transcripts/docs_capture/ascii_shape_gpu.yaml` (same boxes, renamed). Method from Alex Harri,
https://alexharri.com/blog/ascii-rendering (no code taken; essay credited on the page).

Decisions that differ from the essay:
- Glyph vectors are normalised by one global scalar, not per component. Per-component
  normalisation made every mid-grey cell match `Z`.
- No k-d tree: brute force over 95 glyphs plus a 5-bit-per-component cache is fast enough for a
  60 x 26 live `charGrid`. The cache answers every vector that rounds the same way with the glyph
  nearest the first one it met, so it differs from the exhaustive search on about 1 % of cells of
  a turning 3-D scene (99.6-99.7 % agreement in the smoke; 97.6-98.4 % in a live box on a larger knot). `convert(..., exact = true)` skips it.
- Outer (directional) circles are the inner circles pushed 2.6x away from the cell centre.

GPU version (boxes, not a library class):
- The shaders live in box `fragment`/`vertex` properties so they can be edited and re-run (alt-r);
  Marc rejected a first cut that generated the GLSL inside a Kotlin class. The scene box renders into
  an RGBA8 UNORM `RenderToTexture` exactly 128 x 48 font cells; the match box's fragment shader runs
  once per texel of a 128 x 48 `cells` target (one triangle covering it) and writes the glyph index
  as index/255; the draw box's fragment shader turns indices back into glyph pixels from an atlas.
- The library supplies only what the CPU has: `ShapeAscii.glyphVectorTexture()` (RGBA32F, 2 texels
  per glyph), `glyphAtlasTexture()` (RGBA8), `readCells(scene, cells)` (both readbacks queued from
  one renderer pre-target so they come from one frame), and `Target.after(first)` (a `RendersAfter`
  wrapper; re-running a box releases its render-list entry and re-adds it at the end, which would
  put `scene` after `cells`).
- The match shader computes circle pixels itself (centre-in-circle test over a bounding box), with
  the same centres, radius and outer push as `ShapeAscii`. Float32 vs the CPU's doubles cannot flip
  a pixel at a 10 x 19 cell (closest pixel centre is 0.02 px^2 from an edge); another font size could.
  Moving the circles in the shader desyncs it from the glyph vectors, which were measured on the CPU.
- Orientation: Field's viewports are flipped (negative height) so clip-space +y is up. That puts the
  picture's top row in memory row 0 of an RTT, but a full-screen window pass sees `uv.y = 0` at the
  window's bottom; the draw shader flips `v`. The first cut drew rows and glyphs upside down; the
  smoke's "upright" check pins it.
- The smoke reads the shaders out of the transcript, so it tests what a reader edits.
- Measured (smoke, Apple silicon, GPU shared with other sessions, medians of three runs): 100.00 %
  agreement with the exact CPU search on every frame; match pass 2.4 / 2.5 / 2.6 / 5.2 ms at
  64x24 / 128x48 / 256x96 / 512x192 cells against the CPU's 2.1 / 7.6 / 31 / 120 ms. Baking the
  circle pixels and glyph vectors into the shader as constants (the first cut) took about half the
  GPU time at small grids (1.2 ms at 64 x 24).

Known limits:
- Large even grey areas (photos, UI screenshots, the shaded side of a lit object) fill with one
  even glyph (`Z`, `Q`, `S`).
- Matching uses Java2D `Monospaced`, which differs from the charGrid panel's font. The GPU draw box
  draws with an atlas of the matching font, so it has no such mismatch.
- A `charGrid` demo must be launched (`execution="launch"` in a docs fence), not just run.
- The GPU boxes are shape mode only (no brightness ramp), store the glyph index in one byte (at most
  256 glyphs), and their `ink`/`background` assume an sRGB window.
- `pos.add`/`normal.add` inside `m.set { }` in a shader box draws a `FIELD_SHADOWED_BY_RECEIVER`
  warning ("'pos' here is MappedStructure.pos, not the 'pos' this box declares") on all three GPU boxes;
  the box runs correctly. It comes from the moduleHere preamble, not from this code.
- Not done: other alphabets (box drawing, Braille, single-stroke Hershey for pen plotting) with a
  measured edge-fidelity comparison, and a contrast x directional sweep.

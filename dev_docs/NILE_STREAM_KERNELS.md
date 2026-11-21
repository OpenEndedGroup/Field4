# Nile-style stream kernels and the Gezira rasteriser

Code: `src/jvmMain/kotlin/afield/graphics/nile/` (`Nile.kt` the DSL, `Gezira.kt` the rasteriser and
drawing helpers, `GeziraPaint.kt` StrokeBezierPath, Texture, Composite, `Canvas`). Tests: `src/jvmTest/kotlin/afield/graphics/nile/GeziraTest.kt`, `GeziraPaintTest.kt`. Demo:
`dev_docs/agents/transcripts/nile_gezira_kernels.yaml`. User docs:
`public/docs/afield_four_graphics.md#stream-kernels`. Source: Dan Amelang's Nile/Gezira as described
in https://whitebeard.blog/posts/pixels-on-a-screen/ (scout card ca17ee14e65c).

## Method

- `kernel<A, B>(name) { var state; each { emit(..); push(..) }; end { } }`. The builder block runs per
  run, so its `var`s are per-run state. `push` puts a token on the front of the kernel's own queue
  (Nile's recursion). `maxSteps` guards a push-back that never terminates.
- `then` composes, `sortBy` is stable, `groupBy` groups consecutive keys, `trace` keeps every stage's
  output by stage name plus per-kernel step counts.
- Rasteriser: `DecomposeBeziers` (cut until the control hull fits one pixel) → `EdgeSamples` →
  `SortBy x` → `SortBy y` → `CombineEdgeSamples` → `Span`s. Gezira fuses the first two; they are
  split here so the pieces are a drawable stream.
- Edge sample: `height = y1 - y0`, `area = ∫ (px + 1 - x(t)) y'(t) dt` over the piece, by 3-point
  Gauss–Legendre, exact for cubics (degree-5 integrand). So curves are not flattened to chords.
  Coverage = `min(1, |carry + area|)`, carry = sum of heights to the left in the row (non-zero fill,
  but with the winding area clamped rather than the winding number thresholded).

## Numbers (GeziraTest, 2026-11-21)

- Polygons vs `TriangleCoverage` (signed fan): max 2.1e-13, mean 7.7e-15 over 9,720 pixels, 40 shapes.
- Curves vs the same curves flattened to 2,048 chords and covered exactly: max 3.6e-6 (flattening).
- vs Skia AA raster: star max 0.10 / mean 0.009; circle max 0.19 / mean 0.008; Skia vs exact on the
  star is the same 0.10, so the difference is Skia's (supersampled, 8-bit). Overlapping star+circle in
  the demo: max 0.43, mean 0.012 ; the x10 difference figure puts it along the edges facing down-right, not at the outline crossings, i.e. Skia's edge bias.
- Pieces: a radius-15.3 circle → 124 pieces.

## Traps

- Halving alone (`t = 0.5`) homes in on every pixel-edge crossing for ~30 levels until the `TINY` hull
  fallback: 1,344 pieces for a 16 × 12 shape. `Gezira.cut` cuts at the pixel edge the endpoints
  straddle (bisection on `t`, cut point snapped exactly onto the edge); this took polygon error from
  1.3e-9 to 2e-13 and the same shape to 47 pieces.
- Fit test must be `max <= floor(min) + 1`, not `floor(max) == floor(min)`, or a piece ending exactly
  on a grid line splits forever.
- Summed |winding area| != non-zero union where two outlines cross inside one pixel (bounded above).
  The x10 difference figure saturates at 0.1, so it cannot localise this: probe per pixel instead.
- Skia's `Bitmap.getColor` un-premultiplies; `skiaRaster` premultiplies again before comparing.
- Do not judge the stroke against Skia: Skia's AA stroke is the less accurate one; use a true-stroke sample.

## Not done

Round joins/caps, miter, other blend modes, image textures, a compute-shader backend, the tiger (no tiger SVG in the repo; text is the stand-in large input).

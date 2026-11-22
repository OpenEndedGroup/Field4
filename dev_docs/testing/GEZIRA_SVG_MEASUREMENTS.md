# GeziraSvg: the tiger through Gezira vs Skia (2026-11-22)

Measured by the `tiger beside skia` box of `dev_docs/agents/transcripts/nile_gezira_svg.yaml`
(captured in `public/docs/assets/afield_four_graphics/gezira-svg-tiger.png`), 300 × 300 px, CPU:

- Document: 239 shapes (240 paths, one `M-65.4,9z` paints nothing), 2,184 segments, nothing skipped.
- Kernels: 226 fills, 78 strokes; 7,724 Béziers -> 464,795 flattened lines -> 47,328 spans.
- Time: stroke 9 ms, rasterise 547 ms, composite 6 ms.
- Against Skia `SVGDOM`, per premultiplied channel over painted pixels: max 0.294, mean 0.0073.
  Largest differences are on sub-pixel whisker strokes; StrokeBezierPath bevels where SVG mitres.

At 200 px (`GeziraSvgTest.tigerAgainstSkia`): max 0.270, mean 0.0095.

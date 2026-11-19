# Exact triangle coverage — measurement notes

Code: `src/jvmMain/kotlin/afield/graphics/render/TriangleCoverage.kt` (Kotlin + GLSL include
`coverage/triangle.glsl`, registered in `builtInShaderInclude`, `shaders.kt`). Tests:
`src/jvmTest/kotlin/afield/graphics/render/TriangleCoverageTest.kt`. Page:
`public/docs/afield_four_graphics.md#exact-triangle-coverage`. Demo: `dev_docs/agents/transcripts/exact_triangle_coverage.yaml`.
Method after ATS (arXiv 2611.22512, CC BY-NC-ND): written from the description only.

Window: `(d/r)^sigma`, d = distance to nearest edge, r = inradius. This normalisation is our
reading of the method; the paper's exact parameterisation may differ.

## Numbers (2026-11-19)

| check | population | result |
|---|---|---|
| sigma=0 window vs box coverage | 582 partially covered pixels of 2000 random triangles | worst 5.0e-16 |
| sum of pixel integrals vs 2A/((s+1)(s+2)) | 200 triangles x sigma {0,0.5,1,2,4} | worst rel. 4.3e-13 |
| exact vs 256x256 supersampling, sigma 0 | 300 pixels, 245 partial | mean 2.3e-5, worst 3.4e-4 (supersampler edge resolution) |
| same, sigma 1 / 2 / 4 | 300 px, 258 / 255 / 261 partial | worst 5.4e-6 / 1.5e-5 / 3.2e-6 |
| point sample vs supersampling | same pixels | worst 0.87 / 0.81 / 0.64 / 0.65 |
| culling (threshold 0.5/255) | 4000 tiny triangles, 689 culled, sigma 1, 64 px | worst pixel change 2.55e-3 (overlaps add; per-triangle bound holds) |

## Traps
- `RectSLCoverage.clipConvexToBox` is the shared clipper; do not add a second.
- Without gradients: vertex derivatives the paper derives are not implemented.
- The GPU capture is brighter than the CPU figure: the window target is sRGB, the CPU Img is shown
  as stored values. Same geometry, same coverage pattern.
- Fat-pixel fit uses `min(halfW/34, h/26)`, so a tall window leaves empty space below the scene.

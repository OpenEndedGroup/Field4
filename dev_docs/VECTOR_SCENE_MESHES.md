# vectorScene: meshes, curves on them, and ordering at scale

`VectorSceneBuilder.mesh(TriMesh, color?, solid)` and `polyline(List<Vec3>, width, color, lift)`
(`src/jvmMain/kotlin/afield/geometry/path/VectorScene.kt`) let any `TriMesh` producer and any 3D
curve (isolines, `spiralFromRings`, `fieldSpiral`) draw as depth-ordered vector `FLine`s.
Docs: `public/docs/fline_examples.md` "Meshes and curves on them"; demo boxes `vs_meshes`,
`vs_mesh_timing` in `dev_docs/agents/transcripts/vector_scenes.yaml`.

## Method

- One flat facet per triangle, colour = mean of the three vertex colours. `solid = true` culls
  triangles facing away (meshes here wind counter-clockwise from outside); open sheets need `false`.
- One round-capped stroke facet per polyline segment. `Facet.bias` (`lift`, default = width) moves
  the stroke toward the viewer for every depth query; without it a curve lying exactly on a surface
  ties with the face beneath and loses about half its segments.
- `pairwiseOrder` used to compare all n² pairs and pick each next face with a linear scan (also n²).
  Now: a uniform screen grid of about √n × √n cells (capped at 512²); each face is put in every cell
  its bounds touch; a pair is examined only in the cell holding the low corner of the two bounds'
  intersection, so it is seen once with no hash set. The topological sort uses a priority queue
  keyed on centroid-depth rank, and the cycle break walks a pointer down the same ranking; both
  give the same order as before.
- `wrongOrderShare` buckets faces into 8×8-pixel tiles instead of testing every face per pixel.

## Numbers (M-series Mac, jvmTest `VectorSceneMeshTest`, icosphere + torus, yaw 0.3 pitch -0.6)

| triangles | facing camera | pairwise order | cycle breaks |
| --- | --- | --- | --- |
| 2,304 | 1,152 | 5 ms | 0 |
| 9,216 | 4,608 | 23 ms | 0 |
| 36,864 | 18,432 | 92 ms | 0 |
| 282,624 | 141,311 | 526 ms | 0 |

Wrong-order pixel share against the per-pixel reference: torus 0.000% (both orders), ring on a
sphere 0.029% pairwise, lighthouse unchanged at 0.00% pairwise / 2.74% centroid. The docs figure
(relief 12.8k + torus 7.7k triangles, plus strokes, 26.9k FLines) renders in ~0.5–0.6 s.

## Traps

- `VectorScene.facets` is `internal`: box code cannot read it (the K2 REPL rejects it; no Gradle
  gate sees that). Count triangles from the meshes instead.
- Fine meshes are easy for a painter: small faces rarely overlap ambiguously, so centroid order is
  already near-perfect on them. The pairwise order matters for mixed scales (a floor and a card,
  long strokes over small faces).
- `lift` must stay small next to the gap between the surface and anything that should hide the
  curve; a large lift lets a curve show through a thin fold.
- Output size: every triangle is an FLine; 20k-face scenes give 20k+ SVG paths. Fine for `lines()`,
  heavy for a plotter. Not done: merging coplanar same-colour neighbours.

# Split ThreeCameraSpec.distance into distance and viewer padding

- **Handoff:** `1ec6bc83-359f-4b03-aa81-359a87679066` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/; also affects polytope_examples.md, solid_notation.md
- **Filed by:** human, 2026-10-16 09:14
- **Close with:** `python tools/docs_notes.py handoff-close 1ec6bc83 "what happened"`

Decided 2026-10-16 (Marc, handoff 4ee69c76; see `dev_docs/handoffs/2026-10-16-decide-what-threecameraspec-distance-means-in-the-three-js-v.md`): split `ThreeCameraSpec.distance` into the rasterizer's `distance` and a viewer-only `padding`.

Today `threejs_viewer.html`'s `applyCameraSpec(..., fit = true)` puts the eye at radius × distance, then `fitCameraToBounds` re-fits with margin `max(1.02, distance / 2.7 × 1.06)`. Every caller passes `fit = true`.

Work:

1. Add `padding` to `ThreeCameraSpec` (a Kotlin field and its serialised form). The viewer's fit margin comes from `padding` alone; `distance` no longer affects viewer framing. Pick the default so a spec with neither field frames as today (the margin that today's default `distance` produces).
2. KDoc both fields: `distance` is what the rasterizer uses; `padding` is the viewer's fit margin around the shape's outline.
3. Migrate the ten figures that pass an explicit `distance` (polytope_examples.md 2.3, 2.4, 2.5, 3.2 ×3; solid_notation.md 3.6). Keep each one's rasterizer `distance`, and give each viewer figure the `padding` that reproduces today's framing (1.02 for the low values). Recapture and compare.
4. On three_output.md, delete the aside "In the viewer, `distance` is a padding factor" and reduce the "Framing differs" bullet to naming the two fields.

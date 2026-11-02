# Cull the Three.js wireframe overlay with its surface

- **Handoff:** `55f5e643-f9e1-4d6b-880a-bd718b5a54db` (project)
- **Filed from:** https://openendedgroup.com/field4/three_output/
- **Filed by:** human, 2026-11-02 10:32
- **Close with:** `python tools/docs_notes.py handoff-close 55f5e643 "what happened"`

Filed from public/docs/three_output.md, "Back-face culling, on both renderers". The page carries this paragraph only to explain a renderer disagreement:

> The overlays differ: in the Three.js panel the **wireframe overlay is not back-face culled**, so you can still see the reversed triangles' edges drawn across the hole. The rasterizer's depth-tested wireframe is culled with its surface, so its hole is bare. The surfaces agree.

Fix in code: make the Three.js viewer's wireframe overlay (afield/graphics/three/ThreeJsViewer.kt and the /threejs viewer JS) follow the surface's face culling, as MeshRenderer.CPU's wireframe already does, or expose that as a choice with matching defaults. Recapture assets/three_output/winding.png, update its alt text, and delete the paragraph in the same change.

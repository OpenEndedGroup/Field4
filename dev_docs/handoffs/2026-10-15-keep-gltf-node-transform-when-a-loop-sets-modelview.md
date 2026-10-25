# Keep glTF node transform when a loop sets modelView

- **Handoff:** `e35b1433-f3af-443c-9049-77b4da07e6f3` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close e35b1433 "what happened"`

`public/docs/afield_four_graphics.md`, section "Loading a glTF model", carries this paragraph:

> There is one thing to watch in that loop. `applyToMesh` composes `modelView` as `view * model`, defaulting `model` to the primitive's own node transform. ... Assigning `module.VertexData.modelView = camera.m()` each frame overwrites that with the view alone, which is fine for `Box.gltf` (one node, no transform) and wrong for anything positioned by its nodes. If you drive the camera from a loop like this, compose the model matrix in there too, or pass `model = Matrix4f()` ...

A trap the page has to warn about (rule 25). Fix in code/lessons: have the per-frame camera update keep the node transform (e.g. a separate `view` member or an `applyCamera(camera)` helper that recomposes view*model), and update the canonical_graphics glTF lessons (and the page fence, via its recapture flow) to use it. Delete the paragraph in the same change.

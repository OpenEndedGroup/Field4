# Sync StandardRenderer lights and transparent sort automatically

- **Handoff:** `ee6717ed-8d21-478a-acc3-1bbb0968677b` (project)
- **Filed from:** https://openendedgroup.com/field4/fabricate_renderer/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close ee6717ed "what happened"`

`public/docs/fabricate_renderer.md`, "Lights and skybox":

> Lights are pure scene state; `bundle.syncLights()` uploads them to the lighting pass (call it after changing lights, or once per frame if they move — the same explicit-sync idiom as `sortTransparentMeshes()`).

Also "For moving transparent objects, call: ... sortTransparentMeshes()". If you forget either call, nothing errors, and lights or transparency are silently stale. That is a sharp edge (rule 25). Fix in `standardRenderer.kt`: sync lights and re-sort transparent meshes automatically each frame (or when the scene graph is dirty) from the frame graph's pre-frame hook. Keep the explicit calls as no-op-compatible. Delete the "call it after changing lights / once per frame" guidance from the page in the same change.

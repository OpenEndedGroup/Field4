# Make PathTraceScene.triangle tolerate zero-area triangles

- **Handoff:** `9eab0139-cf91-46c0-b744-0a0821e295f6` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_polytope_render/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 9eab0139 "what happened"`

Rule 25 sharp edge on chain_polytope_render.md:

> The dropped count is not zero. Manifold's output of a Boolean followed by a plane trim contains a few triangles of zero area, and `PathTraceScene.triangle` rejects those outright, which would abort the whole scene. `importTriMesh` skips them instead and reports how many it skipped, so use it rather than feeding triangles to `PathTraceScene` yourself.

Fix: `PathTraceScene.triangle` (afield/graphics/scene/fabricated) should skip and count a degenerate triangle rather than throw and abort the scene, so both routes behave the same. Delete the paragraph (keep, at most, the println of the counts) in the same change.

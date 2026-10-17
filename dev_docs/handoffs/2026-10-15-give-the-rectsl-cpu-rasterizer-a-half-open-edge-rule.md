# Give the RectSL CPU rasterizer a half-open edge rule

- **Handoff:** `8c2eec2e-8193-443a-994d-9ef16d563f6a` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_scene_to_light/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 8c2eec2e "what happened"`

`public/docs/chain_scene_to_light.md` carries this note:

> **Why the line moves with `linear`** — A quoted track such as `'( 0=-8  4=8 ) ->x` eases in and out by default. The CPU rasterizer takes one sample per pixel centre and counts a centre exactly on an edge as inside, so an eased edge that lands on a centre draws a line one pixel wider than it is. `linear` keeps the edges off the centres in this scene.

This is a rasterizer sharp edge (rule 25): the CPU reference rasterizer used by `RectSLImager` (`src/jvmMain/kotlin/afield/lang/rectsl/`) treats a pixel centre exactly on an edge as inside on both sides, so a 4 px rectangle can cover 5 px. Fix: adopt a top-left fill rule (half-open interval `[x0, x1)`) so coverage equals width regardless of where edges land, and check it matches the GPU path. In the same change, delete the note from the page (and the `linear` in the scene may then stay or go; the scene fence is byte-pinned, so recapture if changed).

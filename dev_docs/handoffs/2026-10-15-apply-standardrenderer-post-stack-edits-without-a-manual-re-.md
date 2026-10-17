# Apply StandardRenderer post-stack edits without a manual re-attach

- **Handoff:** `3c6505c8-edb9-496b-8eed-7d2fe56116e2` (project)
- **Filed from:** https://openendedgroup.com/field4/fabricate_renderer/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 3c6505c8 "what happened"`

`public/docs/fabricate_renderer.md`, "Tone mapping and bloom":

> `bundle.setToneMapping(...)` ... `bundle.addBloom(...)` ... Both modify the post stack, so re-attach the frame graph afterwards.

and in the intro: "Tone mapping is the one thing you have to ask for".

This is an ordering trap (rule 25). Fix in `src/jvmMain/kotlin/afield/graphics/scene/fabricated/standardRenderer.kt`: make an attached frame graph observe post-stack edits (rebuild or re-attach itself), so `setToneMapping`/`addBloom`/`addShaderEffect` take effect without a manual re-attach. Also consider defaulting tone mapping on, so a new bundle gives a display-ready image. Delete the "re-attach afterwards" clause (and, if the default changes, the intro sentence) in the same change.

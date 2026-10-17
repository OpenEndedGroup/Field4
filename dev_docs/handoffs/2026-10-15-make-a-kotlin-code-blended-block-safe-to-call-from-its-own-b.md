# Make a Kotlin code-blended block safe to call from its own box

- **Handoff:** `73b7cbd0-4a23-4d55-81cf-46f10ba22d3f` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 73b7cbd0 "what happened"`

Rule 25 sharp edge on keyframer_examples.md, section "The push-constant convention":

> One thing to know before you call the result: a blended block compiles its generated source in the box the two keyframes came from, so invoke it from a frame loop, a control callback or another box — not from inside that box's own running code, which re-enters.

Fix in the Kotlin code-blend path (`afield/animation/keyframes/`): defer/queue the compile when called from inside the owning box's run, or fail with a clear error, rather than re-entering. Delete the sentence in the same change.

# Let a blended sourceBlock run from its own box

- **Handoff:** `6a182206-7066-42af-9b4c-d9c636df6dc1` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 6a182206 "what happened"`

`public/docs/keyframer_examples.md` ("The push-constant convention", Kotlin paragraph) says:

> One thing to know before you call the result: a blended block compiles its generated source in the box the two keyframes came from, so invoke it from a frame loop, a control callback or another box, not from inside that box's own running code, which stops with an error saying so.

This is an ordering trap. Fix in code: let a blended `sourceBlock` invoked from its own box's running code defer the compile (queue it to the next frame, or compile in a separate context) rather than erroring. Delete the sentence in the same change.

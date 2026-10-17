# Let a saved sourceBlock be called from its own box

- **Handoff:** `43488706-d0c1-4d2a-af45-b28270d31124` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 43488706 "what happened"`

`public/docs/running_code.md` (section "`sourceBlock` and `interpolant`") carries this warning:

> !!! warning "Don't call a saved block from its own box's code"
>     Compiling a saved block in its box runs that box's code again — which calls the block again. Call a block that came from a file from another box, a frame loop or a control callback. A block made by `sourceBlock` in this run already has its lambda and is safe to call at once. (Field stops the loop after one repeat, with an error saying so.)

This is a re-entrancy trap (rule 25). Fix it in code: when a saved `sourceBlock` is called during its own box's run, compile it without re-running the box (or defer the call until the run finishes, or use the lambda the current run is about to create). Delete the warning in the same change.

## Also filed from other pages (merged duplicates)

The same fix removes these paragraphs too; delete each in the same change.

### 73b7cbd0 — Make a Kotlin code-blended block safe to call from its own box

Filed from https://openendedgroup.com/field4/keyframer_examples/.

Rule 25 sharp edge on keyframer_examples.md, section "The push-constant convention":

> One thing to know before you call the result: a blended block compiles its generated source in the box the two keyframes came from, so invoke it from a frame loop, a control callback or another box — not from inside that box's own running code, which re-enters.

Fix in the Kotlin code-blend path (`afield/animation/keyframes/`): defer/queue the compile when called from inside the owning box's run, or fail with a clear error, rather than re-entering. Delete the sentence in the same change.

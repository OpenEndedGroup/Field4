# Let a saved sourceBlock be called from its own box

- **Handoff:** `43488706-d0c1-4d2a-af45-b28270d31124` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 43488706 "what happened"`

`public/docs/running_code.md` (section "`sourceBlock` and `interpolant`") carries this warning:

> !!! warning "Don't call a saved block from its own box's code"
>     Compiling a saved block in its box runs that box's code again — which calls the block again. Call a block that came from a file from another box, a frame loop or a control callback. A block made by `sourceBlock` in this run already has its lambda and is safe to call at once. (Field stops the loop after one repeat, with an error saying so.)

This is a re-entrancy trap (rule 25). Fix it in code: when a saved `sourceBlock` is called during its own box's run, compile it without re-running the box (or defer the call until the run finishes, or use the lambda the current run is about to create). Delete the warning in the same change.

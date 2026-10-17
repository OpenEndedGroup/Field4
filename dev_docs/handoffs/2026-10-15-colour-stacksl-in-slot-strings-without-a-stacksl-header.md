# Colour StackSL in slot strings without a #stacksl header

- **Handoff:** `0932e83a-3d0f-4a00-a993-73da16b86dcb` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_examples/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close 0932e83a "what happened"`

Rule 25 sharp edge on `public/docs/stacksl_examples.md` ("In the editor"):

> Only a `#stacksl` string is coloured as StackSL; a slot's string without the header is still coloured as a Kotlin string.

The editor already recognises a string assigned to a box slot (`module.program = "..."`) for completion and the per-line stage note, but the syntax colouring only keys on the `#stacksl` header. Fix: have the Monaco colouring use the same slot detection as completion, so slot strings colour as StackSL. Delete the sentence above in the same change.

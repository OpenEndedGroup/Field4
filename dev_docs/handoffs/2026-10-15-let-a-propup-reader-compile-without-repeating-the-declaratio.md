# Let a propUp reader compile without repeating the declaration

- **Handoff:** `b0b46c2a-1e81-46db-bfbe-c87c07643b4c` (project)
- **Filed from:** https://openendedgroup.com/field4/reactive_properties/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close b0b46c2a "what happened"`

`public/docs/reactive_properties.md` says:

> **The reader declares it.** Up is decided by the reading box's declaration. Plot needs its own `propUp<Double>(scale)` here for a second reason too: until Scale has run, Plot has no `scale` to compile against, and without the declaration the read fails with *Function invocation 'scale(...)' expected*.

Rule 25: a child's preamble should gather a parent's `propUp` declaration from the parent's *code* (not only from a parent that has run), so the reader compiles without repeating it, and the error should at least name the missing declaration. Delete the "second reason" sentence in the same change.

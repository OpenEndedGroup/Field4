# Make cached work for classes declared in the box

- **Handoff:** `65126dbb-9031-43ae-943a-553ce7470703` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 65126dbb "what happened"`

`public/docs/running_code.md` (section "`cached`") says:

> `T` should be a library type. A class declared in the box's own code is a new class each time the box compiles, so a `cached` of it never finds the previous value.

A silent miss every run (rule 25). Fix in code: key `cached` by the type's qualified name rather than the `KClass`, or at least report an error/warning when `T` is a box-local class. Delete the paragraph in the same change.

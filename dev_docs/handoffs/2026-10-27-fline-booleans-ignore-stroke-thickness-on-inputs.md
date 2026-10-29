# FLine booleans: ignore stroke thickness on inputs

- **Handoff:** `949ce16c-92a9-468f-a96e-4b715a463b39` (project)
- **Filed from:** https://openendedgroup.com/field4/fline_examples/
- **Filed by:** human, 2026-10-27 18:20
- **Close with:** `python tools/docs_notes.py handoff-close 949ce16c "what happened"`

Sharp edge on `public/docs/fline_examples.md` (2D booleans section, the `!!! note` after "By default these use Java2D `Area`"):

> An input with `thickness` (or `thicken`) set is read as the outline of its stroke, not as the filled shape, so a union of two drawn squares comes back as a union of two square rings. Build the shapes without style and style the result.

Fix in code: the boolean ops (`byUnionWith`, `byIntersectionWith`, `bySubtractingFrom`, `byExclusiveOrWith`, `booleanExact`) should read the filled geometry and ignore stroke style (`thickness`/`thicken`), or take an explicit option to use the stroke outline. Delete the note (and the matching comment in the example is in a byte-pinned fence; update it with a recapture) in the same change.

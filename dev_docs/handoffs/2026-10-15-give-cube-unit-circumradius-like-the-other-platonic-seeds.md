# Give cube() unit circumradius like the other Platonic seeds

- **Handoff:** `604a47d7-1cd8-4b07-80f9-b320234621e1` (project)
- **Filed from:** https://openendedgroup.com/field4/polytope_examples/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 604a47d7 "what happened"`

Sharp edge (rule 25) on `public/docs/polytope_examples.md`, section "The seeds":

> `cube()` is the odd one out: the other four place their vertices on the unit
> sphere, while `cube()` has side length 2, so its vertices sit at radius √3. That matters later,
> because the realization solver's default targets are derived from the *incoming* mean edge length
> and mean radius.

Fix in `Solids.kt`: normalise `cube()` onto the unit sphere (or add a documented size parameter shared by
all five seeds), check dependants and captures that assume side 2, recapture the polytope pages, and
delete this paragraph in the same change. If side 2 is deliberate, this is a decision for Marc instead.

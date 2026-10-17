# Auto-ranged colour map renders a constant field as black

- **Handoff:** `f167d8eb-b180-4c57-ac00-aeeb21155968` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close f167d8eb "what happened"`

`public/docs/alife_examples.md` (section 5, Lenia) carries this aside:

> ??? aside "One rendering trap"
>     A saturated field is *constant* at 1, so an auto-ranged colour map draws it at the dark end of the
>     ramp, so a `SATURATION` panel renders as black, indistinguishable from `DEATH`. The panels above
>     use a fixed `0..1` range for exactly that reason.

This is a sharp edge (DOCS_WRITING_RULES rule 25): the auto-ranged colour mapping used by the alife
image bridges (`Img`/`toImg` colour mapping in `afield/simulation/agents/alife.kt` and the imaging
colour-map helpers it calls) maps a degenerate range (min == max) to the bottom of the ramp, so any
constant field, whatever its value, renders as black.

Fix: when the auto range collapses, map a constant field by its absolute value (e.g. fall back to a
range containing 0 and the value, or the default 0..1), so a field constant at 1 does not render like
a field constant at 0. Add a test for a constant-1 and a constant-0 field. Delete the aside in the
same change.

# Add reactive variants of rootProp

- **Handoff:** `8b2ece1b-934c-41ef-9956-3c83116714d1` (project)
- **Filed from:** https://openendedgroup.com/field4/reactive_properties/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 8b2ece1b "what happened"`

`public/docs/reactive_properties.md` (section "A missing value runs its supplier") says:

> `rootProp` has no reactive variant yet.

Rule 25: add `rootPropDown` / `rootPropUp` / `rootPropUpDown` (or make `rootProp` accept the reactive behaviour), then delete the sentence in the same change.

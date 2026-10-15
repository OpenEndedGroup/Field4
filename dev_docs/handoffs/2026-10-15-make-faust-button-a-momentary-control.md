# Make Faust button a momentary control

- **Handoff:** `543111b7-7cfa-444b-9ab5-414cfa49f6c7` (project)
- **Filed from:** https://openendedgroup.com/field4/faust/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 543111b7 "what happened"`

Rule 25 sharp edge on `public/docs/faust.md`:

> A Faust `button` is momentary, but Field's checkbox stays on until you click it again.

Fix in code: render a Faust `button` as a momentary (press-and-hold) control rather than a latching checkbox marked `(hold)`, wired to the existing `launch.press` path. Delete the bullet and the `(hold)` table row wording in the same change.

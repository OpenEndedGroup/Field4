# Give Box.lines editable mode an edit-committed callback

- **Handoff:** `c6fe43b7-53d9-4454-a3c5-b2a4e9309065` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/lp_curves/
- **Filed by:** human, 2026-10-27 18:21
- **Close with:** `python tools/docs_notes.py handoff-close c6fe43b7 "what happened"`

`public/docs/lp_curves.md` (section "Drag the polygon") says:

> Only `threeLines` has this callback. A `three { }` scene and a plane figure (`showPlane`) can be panned and zoomed but not edited, and `Box.lines(..., editable = true)` lets you drag points but has no callback: read its lines with `currentLines()` on the value it returns.

This is a sharp edge (rule 25): the editable-geometry surfaces are inconsistent. Fix in code: give `Box.lines(..., editable = true)` an `onEditCommitted` callback matching `threeLines` (and, if wanted, editing in `showPlane`), so all editable viewers share one contract. Delete this paragraph's list of what lacks the callback in the same change.

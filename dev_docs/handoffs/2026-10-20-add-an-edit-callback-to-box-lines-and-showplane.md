# Add an edit callback to Box.lines and showPlane

- **Handoff:** `f1587167-28b7-432c-bbd8-f749f4bee824` (project)
- **Filed from:** https://openendedgroup.com/field4/lp_curves/
- **Filed by:** human, 2026-10-20 11:09
- **Close with:** `python tools/docs_notes.py handoff-close f1587167 "what happened"`

Sharp edge (rule 25) on public/docs/lp_curves.md, end of "Drag the polygon":

> Only `threeLines` has this callback. A `three { }` scene and a plane figure (`showPlane`) can be panned and zoomed but not edited, and `Box.lines(..., editable = true)` lets you drag points but has no callback: read its lines with `currentLines()` on the value it returns.

Fix in code: give `Box.lines(..., editable = true)` (and ideally `showPlane`) the same `onEditCommitted` callback as `threeLines`. Delete these sentences in the same change.

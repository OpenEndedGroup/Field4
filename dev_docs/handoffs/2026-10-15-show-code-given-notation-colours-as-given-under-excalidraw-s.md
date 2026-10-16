# Show code-given notation colours as given under Excalidraw's dark theme

- **Handoff:** `3a7096f3-0bdf-4ad2-858b-7ce2d882c2fe` (project)
- **Filed from:** https://openendedgroup.com/field4/drawing_and_notation/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 3a7096f3 "what happened"`

`public/docs/drawing_and_notation.md` carries this note:

> !!! note "Colours"
>     Field shows the drawing in Excalidraw's dark theme, which draws every colour through an inversion. A colour given from code is shown transformed: pale yellow becomes dark brown. The strong colours of Excalidraw's own palette, the ones used on this page, come through well.

This is a sharp edge (DOCS_WRITING_RULES rule 25): `notation { stroke = "#ffec99" }` does not show the colour asked for. Fix in code: when the drawing layer is in dark theme, have the `notation` writer (src/jvmMain/kotlin/afield/notation/) or the drawing layer (`src/jsMain/kotlin/afield/drawingLayer.kt`) pre-apply the inverse of Excalidraw's dark-mode filter to code-given colours (or render code-owned marks outside the filter), so a colour from code appears as given. Reading `mark.stroke`/`fill` back should still return the colour the code wrote. Delete the note from the page in the same change.

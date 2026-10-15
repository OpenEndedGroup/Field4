# Make viewport and onViewportChange pane-aware on a split canvas

- **Handoff:** `b616721c-31c8-4ccb-b148-ab47fdfdb082` (project)
- **Filed from:** https://openendedgroup.com/field4/moving_around_the_canvas/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close b616721c "what happened"`

`public/docs/moving_around_the_canvas.md`, section "Limits", lists:

> - box code that reads `viewport`, or reacts to it with `onViewportChange`, sees the bottom-right
>   pane of a split canvas only

This is a sharp edge (DOCS_WRITING_RULES rule 25). The server's `viewport` rect is still
translation + window size (AGENTS.md: "Not yet zoom-aware: ... the server's `viewport` rect"), and on
a split canvas it reports only the bottom-right pane. Fix in code: make `viewport` /
`onViewportChange` pane-aware and zoom-aware (e.g. expose the main pane plus a per-pane list), in
`src/jsMain/kotlin/afield/app/client/` (canvasCamera / `set-viewport`) and the server side that
holds it. Delete the bullet from the page (and the AGENTS.md "not yet zoom-aware" note for viewport)
in the same change.

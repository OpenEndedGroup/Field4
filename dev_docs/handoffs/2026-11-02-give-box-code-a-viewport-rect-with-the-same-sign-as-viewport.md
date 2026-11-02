# Give box code a viewport rect with the same sign as viewports

- **Handoff:** `efef363f-9719-4b23-bbd8-7404ecafb147` (project)
- **Filed from:** https://openendedgroup.com/field4/moving_around_the_canvas/
- **Filed by:** human, 2026-11-02 10:31
- **Close with:** `python tools/docs_notes.py handoff-close efef363f "what happened"`

`public/docs/moving_around_the_canvas.md`, section "What box code sees", warns about a sign mismatch between two properties:

> `viewport` is the main (bottom-right) pane, as the rect `(-x, -y, w, h)`: its first two numbers are the negated world position of the pane's top-left corner. `onViewportChange` callbacks fire when it moves. Note the different sign from `viewports`, which lists every pane's visible world rect as `(x, y, w, h)`, main pane last.

`Box.viewport` stores the negated pan offset while `Box.viewports` stores real world rects, so every reader has to remember to flip the sign. Fix in code: give box code a non-negated accessor for the main pane (e.g. `viewportWorld`, or make `viewports.last()` the documented way to get it) and deprecate the negated reading, or migrate `viewport` with a loader/version gate. In the same change, delete the "Note the different sign" sentence and the negation explanation from the page.

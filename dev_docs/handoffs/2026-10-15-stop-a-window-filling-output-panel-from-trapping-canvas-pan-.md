# Stop a window-filling output panel from trapping canvas pan and zoom

- **Handoff:** `b4dbd9c6-0db0-4095-a939-b0bcfda54d98` (project)
- **Filed from:** https://openendedgroup.com/field4/moving_around_the_canvas/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close b4dbd9c6 "what happened"`

`public/docs/moving_around_the_canvas.md`, "Panning and zooming", says:

> **`alt/option-H`** (**Reset View** ...) also returns to 100%, and closes every output panel. Use it
> when a panel has grown to fill the window: the wheel and pinch then go to the panel, so you cannot
> zoom or scroll away from it.

This is a workaround for a trap (rule 25): an output panel that fills the window captures every
wheel/pinch event, so the canvas can no longer be panned or zoomed. Fix in the frontend wheel routing
(e.g. Ctrl/pinch-zoom always goes to the canvas, or a panel that does not scroll passes the wheel
through, or a modifier forces canvas panning). Then delete the "Use it when a panel has grown..."
sentence in the same change.

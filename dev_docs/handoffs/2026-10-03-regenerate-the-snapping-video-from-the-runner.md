# Regenerate the snapping video from the runner

- **Handoff:** `01905758-a52e-48ca-afb6-2d0d3d8aa408` (project)
- **Filed from:** https://openendedgroup.com/field4/snapping/
- **Filed by:** human, 2026-10-03 00:04
- **Close with:** `python tools/docs_notes.py handoff-close 01905758 "what happened"`

`public/docs/snapping.md` embeds `assets/snapping-guide.mp4` with a raw `<video>` tag. The file was
committed by hand in a9e987e14d ("Expand interactive docs and tooling") and no fence, transcript or
runner hook regenerates it, so it goes stale silently (DOCS_WRITING_RULES rule 14).

Replace it with a `capture="ui"` fence or an operation transcript that turns Caps Lock snapping on,
drags a box into edge alignment and then into even spacing, and resizes one edge to a neighbour,
so the cyan guides and spacing ticks are visible. Use `hide-toasts=true` if notifications cover it.
Check the result at column width and trim empty canvas.

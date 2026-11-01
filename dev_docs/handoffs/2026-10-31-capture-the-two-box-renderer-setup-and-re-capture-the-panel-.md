# Capture the two-box renderer setup and re-capture the panel video without overlap

- **Handoff:** `65c6d2c7-3c5e-4a43-b0f7-a44b39704f3b` (project)
- **Filed from:** https://openendedgroup.com/field4/tour_gpu_graphics/
- **Filed by:** agent:claude, 2026-10-31 14:09
- **Close with:** `python tools/docs_notes.py handoff-close 65c6d2c7 "what happened"`

## The notes that led here

- `93cf5124` (public/docs/tour_gpu_graphics.md:30): show a screenshot of the two box setup!
  > once.renderer { } keeps the renderer across re-runs, so running this box again does not open a second window. Every box you connect beneath it (select the renderer box, then the new box, and press g) can use renderer directly, the same way a child box sees any property its parent publishes. The canonical_graphics starting point (Welcome → Starting points → canonical_graphics) already has this box, named window; launch it first.
- `eea7dee7` (public/docs/tour_gpu_graphics.md:226): this video is great, but it is overlapping another box
  > +view inside moduleHere { } sends the module's drawing into the view instead of the window, view += ... attaches the module to it, and display puts the view in the box's main output. A web browser, including Field's HTTP mirror, shows a message in place of the frames.

Two capture notes on `public/docs/tour_gpu_graphics.md`:

1. Note 93cf5124 (the section "A renderer, and a box beneath it", about lines 30-34) asks for a screenshot of the two-box setup: a renderer box with the box connected beneath it. The page has no such figure. `public/docs/first_steps.md` (the fence `first-graphics-document`, about line 152) already captures a two-box `first-graphics` document through the macro `dev_docs/agents/transcripts/docs_capture/first_graphics.yaml`. A similar `capture="ui"` fence could show the canonical_graphics **window** box with its child connected.
2. Note eea7dee7 (the section "Drawing into the box itself", about lines 202-228) says the `tour-gpu-panel` video is good but the box overlaps another box. That fence is `capture-profile="macos-electron"`, so it is only re-captured on macOS Electron with `FIELD_DOCS_CAPTURE_PROFILES=macos-electron`. The overlap comes from where the runner places the box in the capture document (layout, or the `source-box` copy of `assets/tour_gpu_graphics/spiral`), so it is not a prose fix.

Proposed approach: (1) add a `capture="ui"` fence that opens canonical_graphics (or a macro that builds a renderer and a child box and connects them), clipped to the two boxes and the connection. (2) Fix the box placement for the panel capture, for example by offsetting it from the source box or hiding the source box, and then re-capture on macOS Electron.

Questions for a human: for figure 1, the canonical_graphics document or a minimal two-box macro? For figure 2, which box does it overlap: the source-box copy, or the renderer?

Once both captures exist, the page needs one sentence before the new screenshot to introduce it.

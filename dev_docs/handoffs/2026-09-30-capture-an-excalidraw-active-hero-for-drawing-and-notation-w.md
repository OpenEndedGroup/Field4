# Capture an Excalidraw-active hero for drawing_and_notation (with 1c8526f9)

- **Handoff:** `418f45ea-3157-48b5-b10e-3bf61168428c` (project)
- **Filed from:** https://openendedgroup.com/field4/drawing_and_notation/; also affects chain_drawn_score.md
- **Filed by:** agent:claude, 2026-09-30 20:51
- **Close with:** `python tools/docs_notes.py handoff-close 418f45ea "what happened"`

## The notes that led here

- `5bf5290b` (public/docs/drawing_and_notation.md:3): The missing hero image here is Field with Exclidraw's UI on top of it
  > Press alt-d and the canvas becomes a drawing surface: pens, shapes, arrows and text, in Excalidraw's hand-made style, laid over your boxes. Press it again and you are back with the boxes. What you drew stays where you put it, pans and zooms with the canvas, and is saved with the document.

## What was asked

Note `5bf5290b` on `public/docs/drawing_and_notation.md` (the opening paragraph, line 3) says the page is
missing its hero image: Field with Excalidraw's UI on top of it — the drawing layer switched on (`alt-d`),
its toolbar visible over the boxes.

## Why this is more than a docs edit

This is the same capture gap as handoff `1c8526f9` ("Capture an Excalidraw-active hero for the drawn-score
chain page", filed from `chain_drawn_score.md`). Both pages want one kind of figure, and one pipeline fix
serves both, so they should be done together.

- Every figure on this page is a `capture="window"` still (`draw.png`, `fontana.png`, `projection.png`,
  `crescendo.png`; the fences at lines 32, 101, 124, 148). In those captures the drawing layer is inactive:
  marks render, but Excalidraw is in view mode and shows no toolbar (`src/jsMain/kotlin/afield/drawingLayer.kt`,
  the class KDoc, "Modal input"). No runner attribute turns the layer on for a window capture.
- The only mode that can script the page is `capture="ui"` (a `drive` op). As `1c8526f9` records, `clip=`
  is not applied to ui captures (`clip_screenshot` in `public/docs_hooks.py` is called only on the ordinary
  screenshot path, not from `ensure_ui_drive`), so a ui still is the full 2200x2800 CSS px viewport unless it
  crops to one element with `clip-selector`. And a drive fragment cannot run box code, so the marks have to
  come from a `transcript=` setup file.
- A capture must exercise the real code (AGENTS.md section 3), and I can't run Field in this pass. A fence
  whose capture doesn't come out breaks the build, so I have not added one.

## Proposed approach

A. (Recommended, shared with `1c8526f9`.) Make `clip="x,y,w,h"` work for `capture="ui"` window stills. Then
   this page gets a hidden hero fence straight after the opening paragraph:
   - setup transcript (e.g. `public/docs/assets/transcripts/drawing_and_notation_hero_setup.yaml`) that
     creates and launches a box running the `notation-draw` code (the ellipse around the box, "a region",
     the "points here" arrow, the green wave), awaiting `pending`;
   - body: `ui.eval("window.toggleDrawingLayer()")`,
     `ui.waitForSelector("#field-drawing-layer .excalidraw", 15000.0)`, `ui.pause(1500)`;
   - `capture="ui" region="window" clip="0,0,1500,520"` (widened until the toolbar at top centre is inside),
     `screenshot="assets/drawing_and_notation/hero.png" hidden=true embed=true`.

B. A transparent fixed-position `div` injected with `ui.eval` over the wanted rectangle and cropped with
   `shotClip` — works today, but it's a hack that the next ui capture would copy.

C. Toggle the layer, pan so the marks sit under the toolbar, and `clip-selector=".excalidraw"` — the full
   window height is too tall for a hero.

## Questions for a human

- Idle toolbar, or mid-gesture (a tool picked, a mark selected with its handles)? On this page, whose first
  paragraph is about drawing by hand, a hand-drawn stroke next to the box's marks, with the pen selected,
  would show both halves of the page's claim — but a hand stroke has to come from real input (a `ui` mouse
  drag), not be written into the scene as a fake one.
- Light or dark Excalidraw theme? It should match the existing window captures.
- Should this page and `chain_drawn_score.md` share one setup transcript and helper, or have one each?

## What the page needs once this is done

Add the hero fence after line 3's paragraph, with alt text naming the Excalidraw toolbar and the marks
visible, and make the second paragraph's "On its own that is a whiteboard" refer to it plainly (the layer
switched on with `alt-d`).

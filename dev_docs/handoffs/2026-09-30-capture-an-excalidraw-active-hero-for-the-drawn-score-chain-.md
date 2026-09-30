# Capture an Excalidraw-active hero for the drawn-score chain page

- **Handoff:** `1c8526f9-a6ed-4436-8a09-277af0b5af4f` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_drawn_score/
- **Filed by:** agent:claude, 2026-09-30 20:50
- **Close with:** `python tools/docs_notes.py handoff-close 1c8526f9 "what happened"`

## The notes that led here

- `c3238532` (public/docs/chain_drawn_score.md:3): show Excalidraw UI in a hero image here.
  > Three parts of Field, written without each other in mind. The drawing layer holds a score: rectangles, a pen stroke, a level line. notation.space reads it as time across and pitch up. A dozen lines of box code decide what each mark means as music and build a pattern from the notes, and patRenderStereo plays the pattern offline into a .wav. Then the .wav is taken apart again, onsets and pitches, and put next to what was drawn.

## What was asked

Note c3238532 on `public/docs/chain_drawn_score.md` (the opening paragraph, lines 3-7) asks for a hero image that
shows the Excalidraw UI: the drawn score with the drawing layer switched on, so its toolbar is visible
and the reader sees that the score is an Excalidraw scene they can draw into.

## Why this is more than a docs edit

The page's only figure of the drawing is `assets/chain_drawn_score/score.png` (the fence at line 19). It is a
`capture="window"` still with `open-outputs="false"` and `clip="0,0,1080,470"`. In that capture the drawing
layer is **inactive**: marks are drawn, but Excalidraw is in view mode and its toolbar is hidden
(`src/jsMain/kotlin/afield/drawingLayer.kt`, the class KDoc, "Modal input"). No runner option turns the layer on.

The only capture mode that can script the page is `capture="ui"` (a `drive` transcript op). It has two gaps
for this figure:

1. **`clip=` is not applied to `ui` captures.** `clip_screenshot` (`public/docs_hooks.py`, `def clip_screenshot`)
   is called only on the ordinary screenshot path (`public/docs_hooks.py`, the single call site after the
   backdoor run). `ensure_ui_drive` never calls it. The drive op's window still is the full 2200x2800 CSS px
   viewport (`captureWindowScreenshot` in the `drive` branch of `src/jvmMain/kotlin/afield/operation_transcript.kt`).
   The only crop a ui still has is `clip-selector` / `ui.shotClip(out, selector)`, which needs an element.
   The score frame and the Excalidraw toolbar (top centre of the window, about x=1100) do not share one.
2. **A drive fragment cannot draw the score** (conjecture, 2026-10-01: read from the comment, not measured). It runs on the runloop thread with `thisSessionInfo` unset
   (the comment in `docs_hooks.py` above the `options.macro_file` setup). The scene has to come from a
   `transcript=` setup file that creates and launches a box running `notation { drawnScore() }`.

I can't run Field in this pass, and a capture that doesn't come out breaks the page's build, so I haven't added
the fence.

## Proposed approach

A. Make `clip="x,y,w,h"` work for `capture="ui"` window stills: call `clip_screenshot` from `ensure_ui_drive`
   (the drive op would need to report the viewport width in its JSON the way the screenshot command does).
   Then the fence is:

   - setup transcript `public/docs/assets/transcripts/drawn_score_hero_setup.yaml`: `create` a box whose code
     is `notation { drawnScore() }` and `notation.space("score", x = 0.0..8.0, y = 84.0..48.0)`, then `launch`
     and `await: { wait: pending }`;
   - fence body:
     ```
     ui.eval("window.toggleDrawingLayer()")
     ui.waitForSelector("#field-drawing-layer .excalidraw", 15000.0)
     ui.pause(1500)
     ```
   - attributes: `capture="ui" region="window" clip="0,0,1500,520"` (widen until the toolbar is inside),
     `screenshot="assets/chain_drawn_score/hero.png" hidden=true embed=true`.

B. Without a pipeline change, the fence could `ui.eval` a transparent fixed-position `div` covering the
   wanted rectangle and `shotClip` on it. It works today but is a hack in the page, and the next ui capture
   that needs a rectangle would copy it.

C. Keep the view in Field: after toggling, move the camera so the score sits under the toolbar and use
   `clip-selector=".excalidraw"`. That captures the whole window, which is too tall for a hero.

I recommend A: it is small and general.

## Questions for a human

- Should the hero show the layer idle (toolbar only) or mid-gesture, e.g. a tool picked and a rectangle
  selected with its handles showing? A selected mark shows most plainly that the marks can be edited.
- Light or dark Excalidraw theme? Dark inverts code-given colours (only the strong palette survives), so
  the hero and `score.png` should match.
- Should the hero replace `score.png` (the playhead readout would then need its own figure), or be added above it?

## What the page needs once this is done

Add the hero fence right after the opening paragraph (before "The score here is drawn by code"), with alt text
naming the Excalidraw toolbar, the "score" frame, the blue rectangles, the red glissando and the green held note.
Then say in the sentence that follows that this is the drawing layer switched on (`alt-d`), the same layer the
"Drawing your own" section asks the reader to draw into.

# Recapture stale funFeatures media: palette/nesting/multi-editor videos, welcome page, drawing layer, phone controllers

- **Handoff:** `165d6727-2a8e-4906-bdfd-6a1e0a523597` (project)
- **Filed from:** https://openendedgroup.com/field4/funFeatures/; also affects mobile_controllers.md, history_overview.md
- **Filed by:** agent:claude, 2026-09-30 20:35
- **Close with:** `python tools/docs_notes.py handoff-close 165d6727 "what happened"`

## The notes that led here

- `72f4589b` (public/docs/funFeatures.md:402): Out of date — we are command/ctrl-shift-P these days, and this video of interacting with the command pallete shows an out of date (and thus confusing) video.
  > f1 — pressing f1 when you are in the editor brings up the command palette for that box; pressing it again (f1-f1) brings up the command palette for Field as a whole:
- `4f87253e` (public/docs/funFeatures.md:664): command palette in the video is out of date
  > Boxes can be nested inside other boxes (f1 "nest ...")
- `7f222014` (public/docs/funFeatures.md:694): Command palette in video is out of date
  > The document-level command 'Add Editor to Output Area` will allow you to pin a particular property of a box to a boxes output zone:
- `07552817` (public/docs/funFeatures.md:445): "Our desktop app or any web-browser". Also, this screenshot (and our commentary) is out of date, it doesn't have any options tabs on the right hand side or starting places.
  > The first page of the bundled version of Field is accessible from any web-browser via https://localhost:9092/welcome.html:
- `44835497` (public/docs/funFeatures.md:447): We should screenshot this so that we can see the excalidraw UI
  > alt-d turns the canvas into a drawing surface: pens, shapes, arrows and text, in Excalidraw's hand-drawn style, right over your boxes. alt-d again and you're back with the boxes, and the drawing stays put, panning and zooming along with everything else. Pinch to zoom, by the way; that works everywhere now.
- `40180652` (public/docs/funFeatures.md:704): This section needs an image of some sliders from the phone webpage. Now, to be fair so does the "Field from your phone" page!
  > The other browser worth pointing at Field is the one in your pocket. Field serves a page at /controllers that is built for a phone rather than shrunk down to one: a large touch pad that sends X, Y or both into the selected box, the sliders and colour wells that box publishes, its output areas reformatted into a single narrow column, the real AI4 conversation for it, Open Anything, and a full Monaco editor on whatever is selected. There is one selection behind all of it, shared with the desktop — the phone follows Field's, and a pick made on the phone becomes Field's — so a piece can be running on a projector while you hold its controls and walk around in front of it.

## What was asked

Several notes on `public/docs/funFeatures.md` say a hand-made screenshot or screen recording is
out of date or missing:

- 72f4589b ("## Navigating"): the command-palette video
  (`assets/Screen-Recording-2026-03-21-at-4.03.42.mp4`) shows an old palette. The prose is now
  fixed: it says `command/ctrl-shift-P` (or `f1`), press again for document commands, which
  matches `open-command-palette` in `src/jsMain/kotlin/afield/KeybindingsClient.kt` and the hint
  in `src/commonMain/resources/quickpick.js`. The video is still stale.
- 4f87253e ("## Nesting"): `assets/nesting.mp4` shows an old command palette.
- 7f222014 ("## Multiple editors"): `assets/multieditor.mp4` shows an old command palette.
- 07552817 ("## The Welcome page"): `assets/welcome-dashboard.png` lacks the Vulkan panel and the
  Starting points / Templates lists, and it shows the author's personal document list. The prose
  now describes the current page (panels checked against `src/commonMain/resources/welcome.html`).
- 44835497 ("## Drawing on the canvas"): no image of the Excalidraw UI (toolbar, a drawing over
  boxes) after `alt-d`.
- 40180652 ("## From your phone"): no image of the `/controllers` phone page (sliders, touch
  pad). `public/docs/mobile_controllers.md` has none either.

## Why this is bigger than a docs edit

None of these can be fixed by editing Markdown. Each one needs a real capture from a running
Field, and faking one is not allowed. Recordings of UI interaction (palette, nesting, multi-editor)
need `drive`-scripted video captures that do not exist yet. The welcome page, drawing layer and
`/controllers` page need `capture="ui"` recipes against a seeded automation workspace. Some may
need capture-system work, such as a phone-sized viewport or toggling the drawing layer from a
drive script.

## Proposed approach

- Welcome page: use a `capture="ui"` fence with a transcript that seeds a few documents, loaded
  from `/welcome` in the automation workspace so no personal documents appear. Share the asset
  with `history_overview.md`, which also uses `welcome-dashboard.png`.
- Drawing layer: use a transcript with two boxes, then a drive script that toggles the layer
  (`toggle-drawing-layer`) and draws a few elements through `window.drawingLayerState()` or the
  notation API (`notation { rect(); arrow(...) }`, which is real box code). Capture the window.
- `/controllers`: use a Playwright page at phone size on `/controllers` with a box that publishes
  a couple of sliders. Use the same asset on `mobile_controllers.md`.
- The three palette videos: use drive-scripted video captures (`video=` + `ui.*` steps), or
  re-record by hand if the human prefers hand recordings for these interaction demos.

## Questions for a human

- Are hand re-recordings acceptable for the interaction videos, or should they all become
  runner captures?
- Should the phone capture live on `mobile_controllers.md` and be reused here, or the other way
  round?

## What the page needs afterwards

Swap the new assets into the existing `<video>`/image slots, write alt text, and check that the
nearby prose still matches what the new media shows.

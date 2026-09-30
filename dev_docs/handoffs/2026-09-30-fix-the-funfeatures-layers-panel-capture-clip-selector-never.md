# Fix the funFeatures layers-panel capture (clip selector never matched)

- **Handoff:** `d55c1f28-7762-4696-976f-fafcf1ba71b0` (project)
- **Filed from:** https://openendedgroup.com/field4/funFeatures/
- **Filed by:** agent:claude, 2026-09-30 20:35
- **Close with:** `python tools/docs_notes.py handoff-close d55c1f28 "what happened"`

## The notes that led here

- `9404fe8c` (public/docs/funFeatures.md:429): This screenshot is completely wrong --- too big, too empty and doesn't actually show the showLayers() output. We must have a better example than this.
  > Field boxes exist in layers, and boxes visibilities and positions are per layer. showLayers() in a box gives you some UI:

## What was asked

Note 9404fe8c on `public/docs/funFeatures.md` ("### Layers"): the layers screenshot is "completely
wrong — too big, too empty and doesn't actually show the showLayers() output".

## Why this is bigger than a docs edit

The image is a real runner capture that has gone wrong, so fixing it means debugging the capture
(or the palette), not rewriting prose. Faking or hand-editing the PNG is not allowed.

## Evidence

- The fence is `capture="ui" region="window" clip-selector=".layers-palette"
  transcript="assets/transcripts/layers_setup.yaml"` in the "### Layers" section of
  `public/docs/funFeatures.md`; its drive script is `ui.openEditor("layerdemo")`,
  `ui.waitForSelector(".layers-palette", 8000.0)`, `ui.pause(700)`.
- `public/docs/assets/layers-panel.png` (4400×5600 px) is the *whole window*: the `layers` box, an
  editor holding `showLayers()`, a "Reloaded keybindings from /Users/marc/.afield/keybindings.yaml"
  toast, and no palette at all. So the clip selector never matched, and the capture still wrote a
  full-window image instead of failing. The `waitForSelector` should also have timed out.
- The class is added by `renderLayersPalette` in `src/jsMain/kotlin/afield/outputareas.kt`
  (`contentRoot.classList.add("layers-palette")`), which is only reached when the box's output
  area renders the palette. The capture shows the box's output area closed. See the memory
  "open the channel, one per channel" (display-channel-visibility).
- The transcript's three other boxes (`sketch`, `render`, `overlay`) sit on layers other than
  the active one, so they are hidden. The palette would show them, but the image does not.

## Proposed approach

1. Reproduce with the backdoor: replay `layers_setup.yaml`, then check whether `.layers-palette`
   exists in the DOM and whether the output channel is open.
2. Fix whichever part is broken. Either the transcript/drive script opens the output area
   (`open-outputs` or a `displayOpen`), or `showLayers()` opens its own channel.
3. Make a `clip-selector` that matches nothing a capture failure, not a silent full-window
   image. That runner change would stop this from happening again.
4. Put some boxes on the active layer too, so the palette has visible and hidden layers to show.

Alternative: drop the capture and describe the palette in prose until it can be captured.

## Questions for a human

- Should `showLayers()` open its output channel by itself?
- Should a failed clip selector fail the build? That would probably surface other bad captures.

## What the page needs afterwards

Re-run the capture (strict/always for that fence) and check that the PNG shows the palette at a
readable size. Nothing in the prose has to change.

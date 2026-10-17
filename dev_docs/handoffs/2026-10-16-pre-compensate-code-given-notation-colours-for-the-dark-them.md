# Pre-compensate code-given notation colours for the dark-theme filter

- **Handoff:** `21e0b08b-6698-4a3b-915f-2ba7596c0da9` (project)
- **Filed from:** https://openendedgroup.com/field4/drawing_and_notation/
- **Filed by:** human, 2026-10-16 09:09
- **Close with:** `python tools/docs_notes.py handoff-close 21e0b08b "what happened"`

Decided 2026-10-16 (Marc, handoff 64bfb0d1; see `dev_docs/handoffs/2026-10-16-decide-how-code-given-notation-colours-survive-excalidraw-s-.md`): pre-compensate code-given notation colours for Excalidraw's dark-theme filter (`invert(93%) hue-rotate(180deg)`, from `theme="dark"` in `tools/excalidraw-bundle/entry.jsx`).

Work:

1. In the `notation` writer (`src/jvmMain/kotlin/afield/ui/notation/Notation.kt`), store the colour whose filtered output is closest to the requested one: shown = H180 · (0.93 − 0.86 · stored), solved by projected least squares clamped to [0,255].
2. Keep the requested colour in the element's `customData`. `Mark.stroke`/`fill` return it, so a read gives back what the code asked for; hand-drawn marks without it read as today.
3. Add a unit test covering the round trip and the measured cases (#808080 and #2f9e44 exact; #1971c2 within a few levels).
4. Shrink the "Colours" note on drawing_and_notation.md to the remaining limit: warm and pale colours (e.g. #ffec99, #e03131) come out duller or shifted under the dark theme. Recapture any figure that shows code-given colours.

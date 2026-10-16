# Decide how code-given notation colours survive Excalidraw's dark-theme filter

- **Handoff:** `64bfb0d1-206c-47ba-bfe4-929b8a403b76` (project)
- **Filed from:** https://openendedgroup.com/field4/drawing_and_notation/
- **Filed by:** human, 2026-10-16 00:57
- **Close with:** `python tools/docs_notes.py handoff-close 64bfb0d1 "what happened"`

Refiled from 3a7096f3-0bdf-4ad2-858b-7ce2d882c2fe, 2026-10-16. The "Colours" note on `drawing_and_notation.md` still stands.

What was tried: pre-compensating a code-given colour in the `notation` writer (`src/jvmMain/kotlin/afield/ui/notation/Notation.kt`). The idea was to store the inverse of Excalidraw's dark-theme filter, `invert(93%) hue-rotate(180deg)` (`tools/excalidraw-bundle/entry.jsx` sets `theme="dark"`), keep the requested colour in `customData`, and have `Mark.stroke`/`fill` read that back. The filter is affine (shown = H180 · (0.93 − 0.86 · stored)), but its gamut is narrow. Best achievable result after the filter, computed by projected least squares:

| asked for | best the filter can show |
|---|---|
| #ffec99 (255,236,153) | (244,198,176) |
| #e03131 (224,49,49) | (207,90,53) |
| #1971c2 | (27,119,191) — fine |
| #808080 | exact |
| #2f9e44 | exact |

So compensation fixes greys, blues and greens, but not warm or pale colours. Making code colours show as given needs one of:
- override `--theme-filter` to `none` in Field's Excalidraw CSS and theme the canvas some other way (dark background, light default stroke). This changes how every hand-drawn mark looks and which palette entries read well;
- ship compensation anyway and keep a smaller note about warm/pale colours;
- draw code-owned marks on a separate unfiltered layer (needs the vendored bundle changed).

Pick one; then delete or shrink the note.

## Decision, 2026-10-16 (Marc)

**Compensate, and keep a smaller note.** The `notation` writer stores the colour whose dark-theme filter output is closest to the requested one (projected least squares through shown = H180 · (0.93 − 0.86 · stored)). It keeps the requested colour in `customData`, and `Mark.stroke`/`fill` read the requested colour back. The page's "Colours" note shrinks to the warm/pale limit, citing the measured cases (#ffec99, #e03131). Filed as a code handoff.

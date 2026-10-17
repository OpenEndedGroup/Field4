# Fix TextLayout.toFLine garbling filled text

- **Handoff:** `7432f77d-1b63-4260-876e-9fcaf8b21340` (project)
- **Filed from:** https://openendedgroup.com/field4/chart/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 7432f77d "what happened"`

`public/docs/chart.md`, "Down to geometry", says:

> If you add text of your own as FLines, use `TextLayout.toFLines()`, one line per contour, and move it with `FLine.transform`. A single filled `toFLine()`, or moving each `node.to` alone, garbles the letters.

A call that garbles its output is a sharp edge. Fix: make `TextLayout.toFLine()` produce a correctly filled multi-contour line (or deprecate it with a pointer to `toFLines()`), and make per-node moves keep control points with their nodes. Delete the paragraph in the same change.

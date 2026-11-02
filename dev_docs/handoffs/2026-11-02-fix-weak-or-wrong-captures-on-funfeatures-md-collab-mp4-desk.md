# Fix weak or wrong captures on funFeatures.md (collab.mp4, desktop-sequencer.png)

- **Handoff:** `0fb2923b-8576-4902-819f-0a208820f6e7` (project)
- **Filed from:** https://openendedgroup.com/field4/funFeatures/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 0fb2923b "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/collab.mp4` — legibility, medium

**Problem.** The collaboration video shows two near-empty full browser windows side by side, shown at no more than 660 px. The boxes are about 40 px wide and the code ('print("hello world")') is unreadable, so you cannot see the edits passing between the two windows. Most of each frame is empty dark canvas.

**Fix.** Crop each window to the box and editor region and record at a higher zoom, so the typed code and its result can be read in both windows.

## `assets/desktop-sequencer.png` — legibility, medium

**Problem.** The desktop half of the comparison is mostly empty canvas. The sequencer panel and the code editor are small enough that the bars, grid and Kotlin cannot be read at column width. The phone half is fine.

**Fix.** Crop the desktop half to the box's output panel and the editor, or zoom the canvas before capture, so the 'same box' comparison can be read.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page funFeatures.md` and look at each new asset at column width before closing.

# Fix weak or wrong captures on drawing_and_notation.md (hero.png)

- **Handoff:** `0c801056-e2fb-4250-ba0b-161d8dbc7ea4` (project)
- **Filed from:** https://openendedgroup.com/field4/drawing_and_notation/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 0c801056 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/chain_drawn_score/hero.png` — framing, medium

**Problem.** This is the page's hero image. The window is too narrow: the Excalidraw toolbar is cut off on the right after the circle tool, and so is the hint 'To move canvas, hold mouse wheel or space'. The last blue note runs off the edge of the score frame. Three loading toasts sit in the lower left ('Drawing layer: on', 'finished loading', 'Connected — loading document'). The alt text says the toolbar runs across the top of the window, but the image shows only part of it.

**Fix.** Recapture with hide-toasts=true and a viewport wide enough to show the whole toolbar, or clip to the box plus the score, with the toolbar inside the frame.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page drawing_and_notation.md` and look at each new asset at column width before closing.

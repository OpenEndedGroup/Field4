# Fix weak or wrong captures on interactive_guides.md (annotations.png)

- **Handoff:** `ca345a8d-e7e9-4a49-ade5-a3649cd8a557` (project)
- **Filed from:** https://openendedgroup.com/field4/interactive_guides/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close ca345a8d "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/interactive_guides/annotations.png` — legibility, medium

**Problem.** The 4296 px window capture is mostly empty canvas. Scaled to 720 px, the note and code text are unreadable. The green box-targeting line the note describes appears only as a small arrowhead at the box corner, so half of what the figure is meant to show cannot be seen.

**Fix.** Crop to the box, the note and the editor's left gutter, and lay them out so the green annotate() line is a visible stroke and not hidden under the box edge.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page interactive_guides.md` and look at each new asset at column width before closing.

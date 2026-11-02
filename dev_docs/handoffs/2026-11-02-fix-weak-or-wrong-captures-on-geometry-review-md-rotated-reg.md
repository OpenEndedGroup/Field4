# Fix weak or wrong captures on geometry_review.md (rotated-region.png)

- **Handoff:** `6505f8d9-95dd-499b-aa6f-2833b26c18e6` (project)
- **Filed from:** https://openendedgroup.com/field4/geometry_review/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 6505f8d9 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/geometry_review/rotated-region.png` — not-what-it-says, medium

**Problem.** The alt text says 'two stacked meshes', but the left half shows one flat grey square, half covered by the note panel. The right half of the frame is empty except for a stray diagonal line. Nothing shows that a second mesh lies behind the selection.

**Fix.** Frame the camera at an angle where both meshes are visible, keep the panel off the selection, and crop out the empty right half.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page geometry_review.md` and look at each new asset at column width before closing.

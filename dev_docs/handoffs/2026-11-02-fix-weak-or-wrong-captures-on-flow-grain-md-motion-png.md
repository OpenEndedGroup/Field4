# Fix weak or wrong captures on flow_grain.md (motion.png)

- **Handoff:** `5520c33d-6ebf-47f7-b422-611edc1772e3` (project)
- **Filed from:** https://openendedgroup.com/field4/flow_grain/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 5520c33d "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/flow_grain/motion.png` — weak-illustration, medium

**Problem.** The whole frame is an almost flat pink-to-peach gradient. The only feature is a small purple bar off-centre. Nothing in it shows grain, obstacles or a motion vector field, so it does not illustrate what a motion view adds; at column width it looks like a blank capture.

**Fix.** Capture while the camera and marker are moving through structure, so the motion view shows varying displacement. Or show it side by side with the colour view of the same frame, and raise the gain so the differences can be seen.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page flow_grain.md` and look at each new asset at column width before closing.

# Fix weak or wrong captures on ar_reference.md (remote-ar-head.png)

- **Handoff:** `55d10bb8-dc55-4fc9-ba99-13df86621334` (project)
- **Filed from:** https://openendedgroup.com/field4/ar_reference/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 55d10bb8 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/ar_reference/remote-ar-head.png` — framing, medium

**Problem.** The fence asks for w=1000 h=220, but the capture is a tall output pane that is about 95% empty black. The only content is a small status box in the top-left corner (connected / self-test pass / A-Frame 1.8.0 / XR native-webxr). At column width that status text becomes very small, and the reader mostly sees a black rectangle.

**Fix.** Size the output pane (or the capture clip) to the requested 220 px height so the status panel fills the figure. Alternatively, render a minimal scene behind the status panel so the head shows it is drawing something.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page ar_reference.md` and look at each new asset at column width before closing.

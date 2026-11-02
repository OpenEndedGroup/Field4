# Fix weak or wrong captures on ai4_live_iframe.md (hello.png)

- **Handoff:** `6ffee54a-53b4-40c2-943a-bd01cc9ee6f6` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_live_iframe/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 6ffee54a "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/ai4_live_iframe/hello.png` — framing, medium

**Problem.** The capture is 592 px wide but the iframe is styled 640 px wide, so the page is cut off on the right. The subtitle breaks off mid-word ('Ask the agent in the hosting box t...') and the textbox and divider run off the edge.

**Fix.** Widen the capture (w) to fit the 640 px iframe plus padding, or make the iframe width:100%.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page ai4_live_iframe.md` and look at each new asset at column width before closing.

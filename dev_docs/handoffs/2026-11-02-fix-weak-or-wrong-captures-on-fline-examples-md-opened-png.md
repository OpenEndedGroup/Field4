# Fix weak or wrong captures on fline_examples.md (opened.png)

- **Handoff:** `078b93f6-eb41-40f8-8872-89f032b10afe` (project)
- **Filed from:** https://openendedgroup.com/field4/fline_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 078b93f6 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/three_inspector_demo/opened.png` — framing, low

**Problem.** The 'clear 32 mm' dimension label sits under the Parts/Light overlay and is cut off ('clear 32, mm' with the panel over it). The same capture appears twice on the page (runner embed plus a separate markdown image).

**Fix.** Set a camera or label position that keeps dimension labels clear of the inspector overlay, and drop the duplicate markdown image.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page fline_examples.md` and look at each new asset at column width before closing.

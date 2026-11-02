# Fix weak or wrong captures on reconstruction_plumbing.md (gbuffer_strip.png)

- **Handoff:** `7459f260-349a-47f4-aa52-8dfec1f48c3c` (project)
- **Filed from:** https://openendedgroup.com/field4/reconstruction_plumbing/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 7459f260 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/reconstruction_plumbing/gbuffer_strip.png` — framing, medium

**Problem.** About two-thirds of the 4112x2524 image is black padding above and below a thin strip of three panels. At column width each attachment is tiny, and the depth panel's 'brighter when nearer' gradient can barely be seen.

**Fix.** Crop the native-window capture to the strip (or size the window to the strip's aspect) so the three attachments fill the figure.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page reconstruction_plumbing.md` and look at each new asset at column width before closing.

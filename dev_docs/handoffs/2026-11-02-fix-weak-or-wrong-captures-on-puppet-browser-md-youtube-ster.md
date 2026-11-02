# Fix weak or wrong captures on puppet_browser.md (youtube-stereo.png)

- **Handoff:** `f0251fc8-d54f-46fc-b0b0-1eb4a046544a` (project)
- **Filed from:** https://openendedgroup.com/field4/puppet_browser/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close f0251fc8 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/puppet_browser/youtube-stereo.png` — framing, low

**Problem.** The two eye views of the YouTube panel take up about a third of the frame and sit in a large black field. Each page is too small to read at column width.

**Fix.** Crop to the two eye panels, or move the camera closer so each panel fills its half.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page puppet_browser.md` and look at each new asset at column width before closing.

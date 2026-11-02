# Fix weak or wrong captures on field_vr_ide.md (companion.png)

- **Handoff:** `aa5fb236-b8fe-4804-b50a-4f728d32dd59` (project)
- **Filed from:** https://openendedgroup.com/field4/field_vr_ide/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close aa5fb236 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/field_vr_ide/companion.png` — framing, medium

**Problem.** The Vulkan companion capture is about 75% empty black. The content (three boxes, guide text and the VR toolbar) sits in the top-left corner and along the bottom edge, so at 720 px the guide text and toolbar labels are too small to read.

**Fix.** Frame the document content tighter, or zoom the companion's canvas before capturing, so the boxes, guide and toolbar fill the frame.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page field_vr_ide.md` and look at each new asset at column width before closing.

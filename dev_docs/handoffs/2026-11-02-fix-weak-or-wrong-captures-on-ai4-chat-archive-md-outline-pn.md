# Fix weak or wrong captures on ai4_chat_archive.md (outline.png)

- **Handoff:** `82f9256a-6730-48cd-9cf2-2bb64208627c` (project)
- **Filed from:** https://openendedgroup.com/field4/ai4_chat_archive/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 82f9256a "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/ai4_chat_archive/outline.png` — legibility, medium

**Problem.** The image is 3036 px wide and holds about 60 lines of monospace outline. Shrunk to the ~720 px column, the text is about 4 px tall and unreadable, yet the page uses it to show what the outline looks like.

**Fix.** Capture a narrower panel (about 760 CSS px with wrapping) and crop to the first turn, or show the outline text as a code block and keep a small capture only for context.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page ai4_chat_archive.md` and look at each new asset at column width before closing.

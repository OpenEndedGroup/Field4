# Recapture the upside-down post-stack vignette figure

- **Handoff:** `7fbc788f-4d96-4dc3-9f26-9ae96999d69f` (project)
- **Filed from:** https://openendedgroup.com/field4/fabricate_renderer/
- **Filed by:** human, 2026-10-11 12:36
- **Close with:** `python tools/docs_notes.py handoff-close 7fbc788f "what happened"`

`public/docs/fabricate_renderer.md`, section "Post stack": the `fabricate-post-stack-vignette` capture is upside down (see `dev_docs/CAPTURE_ROW_ORDER.md`), and its fence's `alt=` attribute tells the reader so, pointing at dev_docs and "the re-capture it is owed". That is internal commentary in reader-facing alt text, and the alt is in fence bytes, so the prose pass cannot touch it.

Do: fix the row order for this capture (or the post-stack path in `StandardRendererDocsHarness`), recapture the page with `FIELD_DOCS_RUNNER_MODE=always ... --page fabricate_renderer.md`, and rewrite the alt text to describe the image only (drop the upside-down sentence and the CAPTURE_ROW_ORDER pointer). Check `tools/completion-corpus/rank-carets.tsv` is unaffected (it keys `fabricate_renderer#N` by fence index).

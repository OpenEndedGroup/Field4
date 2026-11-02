# Fix weak or wrong captures on graph_query.md (cost.png)

- **Handoff:** `fdaed294-4ead-48d4-9a97-a0ab92c38ff4` (project)
- **Filed from:** https://openendedgroup.com/field4/graph_query/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close fdaed294 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/graph_query/cost.png` — framing, low

**Problem.** The capture is an 832x352 output box holding one tiny line of text ('naming a path: 4 edge reads — walking all 4443 slots: 1666 edge reads') in the top-left corner. The rest is empty, and at column width the text is too small to read.

**Fix.** Shrink the capture to fit the content, or show it as a small two-row table at a readable size.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page graph_query.md` and look at each new asset at column width before closing.

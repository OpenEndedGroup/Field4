# Fix weak or wrong captures on statistics_examples.md (quantile_warp.png)

- **Handoff:** `3cc7b914-ae38-400c-8d49-6999b77dd665` (project)
- **Filed from:** https://openendedgroup.com/field4/statistics_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 3cc7b914 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/statistics_examples/quantile_warp.png` — weak-illustration, medium

**Problem.** The plot titled 'The warp, against the exact answer it is approximating' shows one orange curve. The blue 8-knot PCHIP warp sits exactly underneath it and cannot be seen. The plot also fills only about half the panel width.

**Fix.** Plot the residual (PCHIP minus exact) on its own axis, or draw the knots as markers and the exact curve dashed, so the approximation error can be seen. Widen the plot.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page statistics_examples.md` and look at each new asset at column width before closing.

# Fix weak or wrong captures on projection_examples.md (projection_examples)

- **Handoff:** `fb164f2c-5b90-47f5-8a12-e28aaa7e747d` (project)
- **Filed from:** https://openendedgroup.com/field4/projection_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close fb164f2c "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/projection_examples (classical MDS 'Twenty words placed by edit distance alone')` — legibility, low

**Problem.** In the MDS word plot, labels inside each cluster are drawn on top of each other (render/rendered/renderer, sampler/sampled/sampler, listener/listened), so most of the twenty words can't be read. The plot is meant to show which words land near which.

**Fix.** Offset or repel the labels, or make the plot bigger, so every word in a cluster can be read.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page projection_examples.md` and look at each new asset at column width before closing.

# Fix weak or wrong captures on audio_ai_corpus_microscope.md (neighbours.png)

- **Handoff:** `8883a490-a96c-4a9f-82af-14241a36dc5a` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_corpus_microscope/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 8883a490 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/audio_ai_corpus_microscope/neighbours.png` — weak-illustration, medium

**Problem.** All six waveform plots (the query, two neighbours, two splices) draw quiet audio on a fixed ±1 axis, so each is a near-flat line. The figure is meant to show that the neighbours resemble the query, and you cannot compare their shapes.

**Fix.** Normalise each waveform view to its own peak (or offer a per-view gain), or use an envelope or RMS view, so the query and neighbour shapes can be compared. Then recapture.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page audio_ai_corpus_microscope.md` and look at each new asset at column width before closing.

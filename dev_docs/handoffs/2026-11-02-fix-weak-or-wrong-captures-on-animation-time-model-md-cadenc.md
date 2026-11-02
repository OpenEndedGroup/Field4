# Fix weak or wrong captures on animation_time_model.md (cadence.png)

- **Handoff:** `3a32661d-ee68-423a-b2b3-8c663c9aa9bc` (project)
- **Filed from:** https://openendedgroup.com/field4/animation_time_model/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 3a32661d "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/animation_time_model/cadence.png` — not-what-it-says, high

**Problem.** The alt text describes a different capture: '1,407 frames in 1.95 s', five spikes at 2,500 to 3,500 µs, and a running mean wandering between 1,200 and 1,500. The image shows '1,592 frames in 2.00 s', one spike near 15,000 µs, and that spike stretches the y-axis so far that the whole band of gaps and the orange running mean are squashed flat into the bottom ~10% of the plot. Neither the running mean nor the jitter can be seen.

**Fix.** Clip or log-scale the y-axis (or cap it near the 99th percentile and mark outliers) so the band and running mean are readable. Then rewrite the alt/caption from the numbers in the current capture instead of the old one.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page animation_time_model.md` and look at each new asset at column width before closing.

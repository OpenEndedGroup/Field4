# Fix weak or wrong captures on state_estimation_examples.md (ukf_particle_filter.png)

- **Handoff:** `6d3c35a1-49c7-4540-a81f-fbcf03697f74` (project)
- **Filed from:** https://openendedgroup.com/field4/state_estimation_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 6d3c35a1 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/state_estimation_examples/ukf_particle_filter.png` — weak-illustration, medium

**Problem.** In the top 'state tracking' panel, after step 1000 the measurement, hidden x and PF estimate become a solid green block of fast oscillation from 1.0 to 1.45. The three series cannot be told apart, so the panel does not show tracking. The model-estimate and particle panels below it are fine.

**Fix.** Zoom the state-tracking panel to a short window (for example steps 1000 to 1100), or plot the tracking error, so the estimate can be seen following the hidden state.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page state_estimation_examples.md` and look at each new asset at column width before closing.

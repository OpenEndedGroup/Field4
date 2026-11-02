# Fix weak or wrong captures on matrix_profile.md (anytime.png)

- **Handoff:** `4d958f90-36e6-4c80-9e7e-877c3df072ea` (project)
- **Filed from:** https://openendedgroup.com/field4/matrix_profile/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close 4d958f90 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/matrix_profile/anytime.png` — not-what-it-says, medium

**Problem.** The alt text says the four profiles (fraction 0.01, 0.05, 0.2 and 1.0) each lie on or above the next. In the plot, all four overlap in one tangle of spiky lines, and you cannot tell which is which or check the ordering.

**Fix.** Plot the gap from the exact profile (profile at each fraction minus the fraction-1.0 profile), or use small multiples or a zoomed window, so the 'on or above' ordering can be seen.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page matrix_profile.md` and look at each new asset at column width before closing.

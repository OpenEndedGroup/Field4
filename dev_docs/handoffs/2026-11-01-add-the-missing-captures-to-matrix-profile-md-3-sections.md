# Add the missing captures to matrix_profile.md (3 sections)

- **Handoff:** `7699044c-7966-4100-b7da-892c05c53e14` (project)
- **Filed from:** https://openendedgroup.com/field4/matrix_profile/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 7699044c "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Comparing two signals (`public/docs/matrix_profile.md:147`, high, docs-runner)

- **Point made:** In a join profile, the high stretches mark where signal a does something b never does, and distanceProfile finds where else a shape happens.
- **Capture:** A runner plot of two signals and their join profile, with the high region marked on a, plus a distanceProfile of a query shape with its minima marked.

### Big signals, and answers before the end (`public/docs/matrix_profile.md:158`, high, docs-runner)

- **Point made:** An anytime (fraction=) profile only comes down as fraction grows; motifs settle early and discords settle late.
- **Capture:** A runner figure overlaying matrixProfile(series, m=60, fraction=0.01/0.05/0.2/1.0) on one signal, with motif and discord markers, rendered by the same plotting path the Motifs/Discords sections use.

### Choosing `m` (`public/docs/matrix_profile.md:139`, medium, docs-runner)

- **Point made:** If m is too short every wiggle has a twin, and if it is too long a motif is diluted.
- **Capture:** A small multiple of the same signal's profile and top motif at m/2, m and 2m, from the existing Motifs example signal.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

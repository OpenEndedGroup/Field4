# Recapture the one-sided point-cloud fit with noise

- **Handoff:** `5480c77c-4b3f-47f7-9547-1447cf2ecb11` (project)
- **Filed from:** https://openendedgroup.com/field4/solid_notation/
- **Filed by:** agent:claude, 2026-11-24 19:19
- **Close with:** `python tools/docs_notes.py handoff-close 5480c77c "what happened"`

## The notes that led here

- `126b62cb` (public/docs/solid_notation.md:873): we should try this again with noise
  > The left and middle lobes come back to within 4e-4 of the truth. right is still 0.200000, the seed, after a descent that reports convergence, and unconstrainedParameters named it before the first step. The loss cannot see it: there are no points out there to be off the surface. More iterations, a different optimiser or a restart do not change that; more of the view does.

Asked (note 126b62cb, solid_notation.md "A one-sided view"): "we should try this again with noise".

Why it is more than a prose edit: the fence at public/docs/solid_notation.md:801 samples
`samplePointCloud(count = 4000, seed = 4)` with no `noise`, and the prose under the figure quotes
numbers from that run (`4e-4`, `right = 0.200000`, 2065 points). Adding noise changes the capture
`assets/solid_notation/one_sided.png` and every quoted number, so it needs a real recapture
(`FIELD_DOCS_RUNNER_MODE=always ... --page solid_notation.md`), which this pass may not run.

Proposed approach: add `noise = 0.004` (the value the earlier `fitToPointCloud` example at
solid_notation.md:780 uses) to the cropped cloud, recapture, and rewrite the paragraph from the
new report. Expect `right` to stay at its seed (no points reach it, noise or not); the left and
middle errors should grow to roughly the noise level. Alternative: keep the clean run and add a
second, noisy panel row beside it so the two can be compared.

Questions: one noisy run replacing the clean one, or both side by side? What noise level?

Page needs afterwards: the paragraph under one_sided.png updated to the recaptured numbers.

# Decide whether blendWith should require a shared training seed

- **Handoff:** `05a6b6f6-8edd-428e-8037-ec2e2b3c896d` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-10-16 09:01
- **Close with:** `python tools/docs_notes.py handoff-close 05a6b6f6 "what happened"`

Moved to decisions on 2026-10-16. I implemented the check (seed recorded on FlowMatchField/FlowMatchExport; blendWith refusing two different known seeds) and reverted it. `WeightInterpolationTest.interpolationNeedsASharedInitialisation` blends independently seeded fields on purpose. Its measured finding is that, with PAIRED coupling from a shared source, the independent-seed midpoints stay as tidy as the shared-seed ones. So the refusal would block something the repo has measured to work.

Decision for Marc: enforce anyway (the permutation argument as policy, and change that test to assert the refusal), warn instead of refusing, or rewrite the page warning to state the measurement.


Rule 25 sharp edge on `public/docs/neural_particles.md`, section 3 warning "What `blendWith` needs from the two fields":

> **The same initialisation.** Train both with the same `seed`; nothing checks this for you.

Fix in code: record the training seed (and network shape) in the exported weights (`MlpWeights` / `FlowMatchExport`, src/jvmMain/kotlin/afield/simulation/particles/neuralparticles.kt) and have `blendWith` refuse fields trained from different seeds, as it already refuses different normalisation. Delete the "nothing checks this for you" paragraph in the same change (the warning can then say the API enforces both).

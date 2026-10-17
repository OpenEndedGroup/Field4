# Have blendWith check both fields share a training seed

- **Handoff:** `7abafc0b-4dd6-41bd-bc2f-65cadb6ec254` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 7abafc0b "what happened"`

Rule 25 sharp edge on `public/docs/neural_particles.md`, section 3 warning "What `blendWith` needs from the two fields":

> **The same initialisation.** Train both with the same `seed`; nothing checks this for you.

Fix in code: record the training seed (and network shape) in the exported weights (`MlpWeights` / `FlowMatchExport`, src/jvmMain/kotlin/afield/simulation/particles/neuralparticles.kt) and have `blendWith` refuse fields trained from different seeds, as it already refuses different normalisation. Delete the "nothing checks this for you" paragraph in the same change (the warning can then say the API enforces both).

# Make BoidSettings defaults flock without retuning

- **Handoff:** `3e266b23-ef0c-400d-aba7-c6a7b987bec8` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 3e266b23 "what happened"`

Rule 25 sharp edge on `public/docs/neural_particles.md`, section 5, warning box:

> !!! warning "The textbook weights do not flock, and the defaults are the textbook weights"
> `0.02 / 1.2 / 0.8` is what every boids tutorial gives, and at this density it produces an evenly spaced gas rather than a flock. ... So expect to retune all three whenever you change `radius` or the particle count...

The `BoidSettings` table also says "the defaults alone need not produce distinct flocks".

Fix in code: make `BoidSettings` (src/jvmMain/kotlin/afield/simulation/physics/ShaderBoids.kt) defaults flock out of the box, ideally by normalising the three terms (e.g. separation as a mean, or scaling by neighbour count / radius) so the weights are scale-free and do not need retuning when `radius` or count changes. Then delete the warning box and the table caveat in the same change, and keep the transcripts (`learned_boids`) and the `four.shader-boids.smoke` passing.

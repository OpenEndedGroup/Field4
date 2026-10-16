# Decide BoidSettings defaults that flock without retuning

- **Handoff:** `fd6acfa5-9216-4b12-b69b-34d70c95104c` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-10-16 09:02
- **Close with:** `python tools/docs_notes.py handoff-close fd6acfa5 "what happened"`

Moved to decisions on 2026-10-16. Changing BoidSettings' default weights changes every boid figure and capture that relies on the defaults, and the right defaults depend on density and radius, so which density they should be tuned for is a choice.


Rule 25 sharp edge on `public/docs/neural_particles.md`, section 5, warning box:

> !!! warning "The textbook weights do not flock, and the defaults are the textbook weights"
> `0.02 / 1.2 / 0.8` is what every boids tutorial gives, and at this density it produces an evenly spaced gas rather than a flock. ... So expect to retune all three whenever you change `radius` or the particle count...

The `BoidSettings` table also says "the defaults alone need not produce distinct flocks".

Fix in code: make `BoidSettings` (src/jvmMain/kotlin/afield/simulation/physics/ShaderBoids.kt) defaults flock out of the box, ideally by normalising the three terms (e.g. separation as a mean, or scaling by neighbour count / radius) so the weights are scale-free and do not need retuning when `radius` or count changes. Then delete the warning box and the table caveat in the same change, and keep the transcripts (`learned_boids`) and the `four.shader-boids.smoke` passing.

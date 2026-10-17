# Reject or derive neighbour grid cell size from boid radius

- **Handoff:** `5994ca7d-90c0-4406-91fc-2de22f817f35` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 5994ca7d "what happened"`

Rule 25 sharp edge on `public/docs/neural_particles.md`, section 4 warning and `BoidSettings` table:

> Make the cells smaller than your interaction radius and the query silently misses neighbours: the shader still runs and the flock still flocks, but the result is wrong and nothing reports it. Size the grid from the radius, not from the particle count.

and "`radius` must not exceed the grid's `cellSize`".

Fix in code: `ShaderBoids` / `GpuNeighbourGrid` (src/jvmMain/kotlin/afield/simulation/physics/ShaderBoids.kt, src/jvmMain/kotlin/afield/graphics/vulkan/util/GpuNeighbourGrid.kt) should either derive `cellsPerSide` from `radius` or fail loudly (require/error, or clamp and report) when `radius > cellSize`, including when `radius` is changed live. Then delete the warning box and the table caveats in the same change.

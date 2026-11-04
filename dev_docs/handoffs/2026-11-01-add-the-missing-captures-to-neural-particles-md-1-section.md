# Add the missing captures to neural_particles.md (1 section)

- **Handoff:** `0b054452-dc72-406c-8fc2-42710034c13d` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 0b054452 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### 5. Boids on the grid (`public/docs/neural_particles.md:330`, high, needs-gpu-or-hardware)

- **Point made:** ShaderBoids flocks 12,000 boids, and shrinking separationRadiusFraction turns one even disc into groups, but the reader never sees a flock or the disc-versus-groups contrast.
- **Capture:** Add a native-window runner capture like the section 6 flocks.png: ShaderBoids at the default weights, and a second capture (or a side-by-side) with separationRadiusFraction at 1 versus 0.25 to show the even disc against the clumps. A short MP4 of the motion would be better.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

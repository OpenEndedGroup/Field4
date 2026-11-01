# Add the missing captures to gaussian_processes.md (2 sections)

- **Handoff:** `ec7bed7a-2ba5-4d3c-bda5-b436b03687c8` (project)
- **Filed from:** https://openendedgroup.com/field4/gaussian_processes/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close ec7bed7a "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Keeping a random field coherent in time (`public/docs/gaussian_processes.md:817`, high, docs-runner)

- **Point made:** Rotating between two posterior samples gives a looping animated random field whose every slice has the posterior distribution, an animation the reader never sees.
- **Capture:** video= runner fence (execution=launch, duration ~4s) of a box that draws a 2D slice of the animated(x,y,z,phase) field as phase advances, using the 3D featureModel from the volume section.

### Pins, measurements and uncertainty (`public/docs/gaussian_processes.md:171`, medium, docs-runner)

- **Point made:** Exact pins vs noisy measurements, and that moving measured values changes the mean but not the variance, are visual claims about the ribbon that go unshown.
- **Capture:** Box capture overlaying two posteriors (noiseStd 0 vs 0.2, or same positions with shifted values) as mean lines with ±std ribbons, showing ribbons pinched to zero at exact pins and unchanged width when values move.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

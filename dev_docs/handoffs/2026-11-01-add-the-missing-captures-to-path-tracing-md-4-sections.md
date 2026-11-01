# Add the missing captures to path_tracing.md (4 sections)

- **Handoff:** `b013861d-5830-4da5-83c7-3820a9590535` (project)
- **Filed from:** https://openendedgroup.com/field4/path_tracing/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close b013861d "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Reducing bright specks (`public/docs/path_tracing.md:858`, high, needs-gpu-or-hardware)

- **Point made:** An optional indirect-luminance clamp suppresses fireflies from small bright HDR sources, at a cost to caustics.
- **Capture:** Side-by-side real tracer captures of the same reflective/caustic scene at a fixed interactive sample count, with indirectClamp off and on (crop on the speckled region).

### Keeping convergence during camera motion (`public/docs/path_tracing.md:761`, medium, needs-gpu-or-hardware)

- **Point made:** temporalReuse keeps the image converged while the camera moves instead of resetting to noise.
- **Capture:** Video of a scripted camera orbit (drive script) with temporalReuse off and on, captured from the real tracer.

### More persistence, including artifacts (`public/docs/path_tracing.md:801`, medium, needs-gpu-or-hardware)

- **Point made:** Raising temporalMaxSamples and zeroing sensitivities gives a more persistent moving image but introduces visible artifacts.
- **Capture:** A short video, or two frames mid-camera-move, of the default reuse settings against the high-persistence settings so the ghosting/smearing is visible.

### Replacing the integrator on a running tracer (`public/docs/path_tracing.md:1087`, medium, needs-gpu-or-hardware)

- **Point made:** The integrator source (trace.comp) can be replaced live on a running tracer, e.g. a direct-lighting-only variant.
- **Capture:** Screenshot of the tracer after swapping to the direct-only integrator, next to the packaged integrator's image of the same scene.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

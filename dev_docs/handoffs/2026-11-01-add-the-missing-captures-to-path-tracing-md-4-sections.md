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


Done 2026-11-02: All four sections now have runner captures (linux-nvidia, native window, real tracer). Reducing bright specks: clamp_off.png / clamp_on.png, the material study at 16 samples with indirectClamp 0 and 1f (10f barely changed the frame at this budget, so the comparison uses a lower limit and says so). Keeping convergence during camera motion: motion_no_reuse.png / motion_reuse.png, the last frame of a scripted 24-frame orbit after 256 samples. No video, because the native window has no agent-side recorder (capture_limits.md), so these are stills taken mid-move. More persistence: motion_persistent.png, the same orbit with the high-persistence settings, set against motion_reuse.png. Replacing the integrator: direct_only.png, the material study after reloadIntegrator with the direct-only source, set against the page-top materials.png.

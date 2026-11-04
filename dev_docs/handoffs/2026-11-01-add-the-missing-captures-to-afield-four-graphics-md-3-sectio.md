# Add the missing captures to afield_four_graphics.md (3 sections)

- **Handoff:** `dbe693e6-2dde-442e-b68d-bdcc15a16664` (project)
- **Filed from:** https://openendedgroup.com/field4/afield_four_graphics/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close dbe693e6 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Render to texture (`public/docs/afield_four_graphics.md:825`, high, needs-gpu-or-hardware)

- **Point made:** A producer box draws into an offscreen RenderToTexture and a child box samples it under a renamed property, the smallest complete statement of how Field 4 composes.
- **Capture:** A native-window capture of the consumer's quad showing the producer's random-coloured mesh sampled as a texture, made by the same capture="native-window" runner path the neighbouring sections use, driven from the threebox/canonical_graphics producer+consumer boxes (source-box for both, connected parent to child).

### Debugging: texture probes (`public/docs/afield_four_graphics.md:1319`, high, needs-gpu-or-hardware)

- **Point made:** fieldProbeHere().display("probe") reads a value back out of a running shader and draws it in the box's output area, which is the whole point of the example.
- **Capture:** A box capture (capture="box" open-outputs=true) of the probe readback panel for the 6. debugging texture-probe lesson running under the graphics context, ideally next to the native window, which shows only stacked quads.

### Compute shader (`public/docs/afield_four_graphics.md:341`, medium, needs-gpu-or-hardware)

- **Point made:** A ComputeMesh dispatch transforms an input buffer and the box prints the output trace every frame.
- **Capture:** A box output capture of the printed '<frame>:: v0 v1 ...' trace, which shows the shader's arithmetic on n*13.0. The text says there is no screenshot because nothing is drawn, but the printed output is the punchline and can be captured as inline output.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

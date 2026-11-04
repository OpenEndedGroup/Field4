# Add the missing captures to stacksl_examples.md (5 sections)

- **Handoff:** `5b641ab9-e6f6-43f7-aa18-291cec69faf0` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_examples/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 5b641ab9 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### FBM into a cosine palette (`public/docs/stacksl_examples.md:485`, high, docs-runner)

- **Point made:** Calls this 'the basic creative-coding move' (distort, fbm, palette) and shows default and full-palette variants, but the reader never sees either colour field.
- **Capture:** Two small GPU tiles side by side, one per program, rendered by the same StackSL gallery path as the Rendered gallery section (fabricate-scene / stacksl gallery harness).

### Ping-pong RTT sampling (`public/docs/stacksl_examples.md:573`, high, needs-gpu-or-hardware)

- **Point made:** Shows feedback programs (ema trails, relative sampling, gather) whose whole point is how they evolve over frames, with no frame shown.
- **Capture:** A short MP4 of the explicit-ema ping-pong program running in the RTT ping-pong harness, captured from the real renderer the way the Animated section's hero is.

### Difference of EMA (`public/docs/stacksl_examples.md:636`, medium, needs-gpu-or-hardware)

- **Point made:** Says ema-diff extracts motion or change energy and gives a softer trail, which is a visual claim with no picture.
- **Capture:** A strip of tiles or a short video of the ema-diff program next to the softer-trail program, rendered by the ping-pong harness.

### Pulses, rings, and FM (`public/docs/stacksl_examples.md:658`, medium, docs-runner)

- **Point made:** Four pattern programs (pulses, rings, FM) are given as code only.
- **Capture:** A row of four gallery tiles, one per snippet, from the StackSL gallery renderer.

### Color grading (`public/docs/stacksl_examples.md:812`, medium, docs-runner)

- **Point made:** Colour-grading words change how an image looks, but the section has no before/after.
- **Capture:** Before/after tiles: one base fbm palette and the same field passed through each grading snippet, from the gallery renderer.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

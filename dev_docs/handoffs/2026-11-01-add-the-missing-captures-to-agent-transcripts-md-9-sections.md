# Add the missing captures to agent_transcripts.md (9 sections)

- **Handoff:** `feb37d35-22a9-478c-8e17-ab4cff705f6e` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close feb37d35 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### learned_pen (`public/docs/agent_transcripts.md:98`, high, docs-runner)

- **Point made:** A Kotlin-run handwriting LSTM continues a stroke you give it, with twelve different futures from one stroke.
- **Capture:** A box capture (capture="box" with macro-file learned_pen.yaml) of the twelve-futures panel, or reuse of the existing figure from learned_pen.md.

### stipple_sheet (`public/docs/agent_transcripts.md:133`, high, docs-runner)

- **Point made:** A trained warp deforms 400 particles and a lattice, and you can drag it with sliders.
- **Capture:** A box capture of the deformed particle and lattice output at one slider setting, through the transcript macro, or a short MP4 of a slider sweep.

### evolving_water_bugs (`public/docs/agent_transcripts.md:436`, high, docs-runner)

- **Point made:** A genetic algorithm evolves 2D creatures of points, springs and motorised joints that step on their own.
- **Capture:** A box capture of the best evolved creature's trail or gait, ideally a short MP4 of it walking, made through the transcript.

### studies_rings (`public/docs/agent_transcripts.md:361`, medium, docs-runner)

- **Point made:** A study whose three numbers (ring count, band brightness, warmth) make a ring image you can vary.
- **Capture:** A box capture of the rings output, or a strip of a few variants.

### lp_curves (`public/docs/agent_transcripts.md:1179`, medium, docs-runner)

- **Point made:** Linear-programming algorithms used as curve generators: every route draws one pentagon with its paths.
- **Capture:** A box capture of the 'Every route' pentagon drawing, or reuse of the lp_curves.md figure.

### confluence_grid (`public/docs/agent_transcripts.md:1351`, medium, docs-runner)

- **Point made:** Twenty lambda-term reductions run together, five terms by four schedulers, all reaching the same normal form.
- **Capture:** A box capture of the 5x4 grid at the end of reduction.

### gp_drawing (`public/docs/agent_transcripts.md:1974`, medium, docs-runner)

- **Point made:** Drawing with Gaussian processes: a closed loop through six pins whose mean and draws close without a seam.
- **Capture:** A box capture of the closed loop with its sampled draws.

### marf_demo (`public/docs/agent_transcripts.md:2752`, medium, docs-runner)

- **Point made:** One bank of MARF stages read four ways, three drawn and one heard.
- **Capture:** A box capture of the three drawn readings, plus an audio= clip of the heard one.

### demo_document (`public/docs/agent_transcripts.md:3220`, medium, needs-gpu-or-hardware)

- **Point made:** One canvas showing most of what Field does, with a palette pushing petals to connected boxes.
- **Capture:** Reuse of the demo_document.md window capture, or a window capture of the opened document.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

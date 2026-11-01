# Add the missing captures to csound.md (1 section)

- **Handoff:** `569aa3dd-9b86-491c-9ccb-89e90a561e86` (project)
- **Filed from:** https://openendedgroup.com/field4/csound/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 569aa3dd "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Audio in both directions (`public/docs/csound.md:223`, medium, docs-runner)

- **Point made:** A Field saw goes through a Csound fx channel and comes back as a Field signal; the measured 0.2420 gain is quoted but neither the sound nor the computed output is shown.
- **Capture:** audio= fence rendering dry vs Csound-lowpassed signal offline with renderSignalsOffline, and/or a runner capture of the csound_demo printed RMS ratio.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

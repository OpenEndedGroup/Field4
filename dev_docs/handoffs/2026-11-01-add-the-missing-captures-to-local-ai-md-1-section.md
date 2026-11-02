# Add the missing captures to local_ai.md (1 section)

- **Handoff:** `cb33ab4f-b709-4174-9206-b9ebeb47d255` (project)
- **Filed from:** https://openendedgroup.com/field4/local_ai/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close cb33ab4f "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Live feedback in AI4 (`public/docs/local_ai.md:165`, medium, needs-gpu-or-hardware)

- **Point made:** AI4 and Welcome's Local settings show live GPU temperature, load, VRAM, model readiness, queue and generation speed from the inference machine.
- **Capture:** Screenshot of the AI4 Local panel with the monitor running against a real llama.cpp + tools/local_ai_monitor.py host (window or Electron capture); cannot be produced without a real GPU inference machine, so a committed real capture retained like linux-nvidia profiles.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

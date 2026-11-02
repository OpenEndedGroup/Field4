# Add the missing captures to field_probes.md (1 section)

- **Handoff:** `d5abb503-a5be-4251-a920-0b18ba194bf2` (project)
- **Filed from:** https://openendedgroup.com/field4/field_probes/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close d5abb503 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Reading it back on the CPU (`public/docs/field_probes.md:388`, high, needs-gpu-or-hardware)

- **Point made:** The box runs a compute step under fieldStats and shows channel 0 as a table, but the table never appears on the page.
- **Capture:** diffstatic_runner box capture (capture="box" open-outputs=true) of the stats table display output after a few frames under the particle renderer, same as the fieldprobe-card capture earlier on the page.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

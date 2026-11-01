# Add the missing captures to physics.md (1 section)

- **Handoff:** `55ab99c5-2b62-479f-ac6e-33cf94ec6d47` (project)
- **Filed from:** https://openendedgroup.com/field4/physics/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 55ab99c5 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### `Cloth` (`public/docs/physics.md:588`, high, docs-runner)

- **Point made:** sim.addCloth builds a pinned grid patch, and grabNearest drags it with a PID controller so the sheet follows the hand instead of snapping. This is the headline object of the page, and no picture of it appears anywhere on the page.
- **Capture:** A diffstatic_runner box capture of a pinned cloth drawn via cloth.lines() after N steps, ideally teleport vs grabNearest side by side. An MP4 via a drive script would show the follow-versus-snap difference best.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

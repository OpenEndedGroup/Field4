# Add the missing captures to kotlin_in_the_head.md (1 section)

- **Handoff:** `3792c588-d3bf-497e-b5aa-c774a88ebf2f` (project)
- **Filed from:** https://openendedgroup.com/field4/kotlin_in_the_head/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 3792c588 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### An animated ring of THREE boxes (`public/docs/kotlin_in_the_head.md:174`, medium, needs-capture-system-work)

- **Point made:** A Kotlin/JS box builds and spins a ring of twelve THREE boxes in a head; the reader never sees the ring.
- **Capture:** Browser WebXR/remoteAR head capture (Playwright page acting as the head, like the kith_controls macro) recording a short video of the ring rotating.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

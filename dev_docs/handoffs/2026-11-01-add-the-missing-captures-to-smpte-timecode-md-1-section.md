# Add the missing captures to smpte_timecode.md (1 section)

- **Handoff:** `dbb8c5a1-13ac-47b5-80a4-2a9759ad689a` (project)
- **Filed from:** https://openendedgroup.com/field4/smpte_timecode/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close dbb8c5a1 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A clock a box can read (`public/docs/smpte_timecode.md:176`, low, docs-runner)

- **Point made:** TimecodeClock extrapolates smoothly between LTC frames and freewheels then holds when the source stops, a behavioural claim best shown as a plot of clock.seconds over time.
- **Capture:** Box capture plotting clock.seconds sampled at 60 Hz while following an LTC stream fed in 512-sample blocks that stops partway, showing smooth ramp, half-second freewheel, then flat hold (as in the existing ltc_roundtrip capture style).


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

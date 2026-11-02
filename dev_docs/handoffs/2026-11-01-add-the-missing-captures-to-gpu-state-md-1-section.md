# Add the missing captures to gpu_state.md (1 section)

- **Handoff:** `e4d82043-4d7f-4759-ac87-bc6ce5269197` (project)
- **Filed from:** https://openendedgroup.com/field4/gpu_state/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close e4d82043 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Watching it without reading it back (`public/docs/gpu_state.md:343`, high, needs-gpu-or-hardware)

- **Point made:** fieldProbeHere shows a live card (40,000 calls, none NaN, mean speed and max) without reading back, and the prose quotes the card's numbers instead of showing the card.
- **Capture:** Add a native/box capture to the gpu_state.yaml transcript: after the particle scene settles, screenshot-box the 'probe' output channel of the real fieldProbeHere card.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

# Add the missing captures to dmx_lighting.md (1 section)

- **Handoff:** `e92a3675-b98a-40be-8fd8-3811118aaaa7` (project)
- **Filed from:** https://openendedgroup.com/field4/dmx_lighting/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close e92a3675 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Animating (`public/docs/dmx_lighting.md:132`, high, docs-runner)

- **Point made:** patch.animate(out){ t -> map(...) } drives a moving hue wave on the output's own sender clock, but the reader only sees static frames elsewhere on the page and never sees the animation move.
- **Capture:** A runner video= fence (as motion_recordings uses) of the 200-pixel strip or RGBW matrix preview panel from 'Seeing the rig without the rig', running animate() against 127.0.0.1 with a loopback receiver drawing the received universe, so the frames shown come from real packets. About 4 s at 15 fps.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

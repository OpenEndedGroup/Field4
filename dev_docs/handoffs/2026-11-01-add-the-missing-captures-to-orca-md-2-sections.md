# Add the missing captures to orca.md (2 sections)

- **Handoff:** `a4627771-41ac-4575-8509-c906ffbd7143` (project)
- **Filed from:** https://openendedgroup.com/field4/orca/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close a4627771 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Patching ORCA itself (`public/docs/orca.md:110`, high, docs-runner)

- **Point made:** o.js rewrites ORCA while it plays: it recolours the highlights pink and turns every ':' into a G drone. Neither the colour change nor the drone is shown.
- **Capture:** A screenshot-box of the ORCA output after the go{} block runs, showing the #ff4fa0 highlights and the eval'd 'w x h' println, plus an audio= capture (orca renderer as used for hello.wav) of the drone.

### Playing a part in the grid (`public/docs/orca.md:90`, medium, docs-runner)

- **Point made:** One onCell line makes the bass follow the melody's harmony. You can't hear or see the bass note change.
- **Capture:** An audio= capture of the hello example with the onCell line, or a short record-box video of cell (4,7) flipping between F and C.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

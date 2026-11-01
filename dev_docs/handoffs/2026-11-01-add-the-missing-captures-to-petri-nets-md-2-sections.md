# Add the missing captures to petri_nets.md (2 sections)

- **Handoff:** `8bb5696a-c016-42f1-9ad4-7357123b112e` (project)
- **Filed from:** https://openendedgroup.com/field4/petri_nets/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 8bb5696a "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Token counts as control signals (`public/docs/petri_nets.md:284`, high, docs-runner)

- **Point made:** Tokens piling up in `tension` open a filter on the kit. The section is a sonic and signal claim, but it has no plot and no audio.
- **Capture:** A box capture plotting run.signal, run.level and the SMOOTH ramp over a few cycles, plus an audio= fence via PetriSoundDocs that renders the kit with the cutoff sweep.

### Playing a net live (`public/docs/petri_nets.md:316`, medium, needs-capture-system-work)

- **Point made:** Clicking places and transitions changes a playing net live. This interactive claim has no picture.
- **Capture:** A UI video from a drive transcript that clicks a place and a transition in showPetriNet(live) and shows the tokens moving.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

# Add the missing captures to subharmonic_sound.md (1 section)

- **Handoff:** `2f0b3ce8-6f04-45a1-8f94-d8f3eb34a318` (project)
- **Filed from:** https://openendedgroup.com/field4/subharmonic_sound/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 2f0b3ce8 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Tracking a missing fundamental (`public/docs/subharmonic_sound.md:695`, high, docs-runner)

- **Point made:** HarmonicPhaseTracker finds a 110 Hz pitch from partials at 220/330/550 Hz with no 110 Hz component and adds a 55 Hz undertone.
- **Capture:** Run the sample-by-sample example and show a spectrogram (or spectrum) of input vs output showing the new 55 Hz line beneath partials with nothing at 110 Hz; optionally an audio= clip of the 2 s output.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

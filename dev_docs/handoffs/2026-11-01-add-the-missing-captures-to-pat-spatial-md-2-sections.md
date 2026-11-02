# Add the missing captures to pat_spatial.md (2 sections)

- **Handoff:** `170a880a-1c0b-4d50-ac61-26fbd0df97fa` (project)
- **Filed from:** https://openendedgroup.com/field4/pat_spatial/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 170a880a "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Hearing it in a box (`public/docs/pat_spatial.md:109`, high, docs-runner)

- **Point made:** showPatSpatial plays a four-note line moving between two spatial groups and shows a live waveform and spectrum in the box output, but the page has neither the output panel nor the audio.
- **Capture:** Runner box capture of the showPatSpatial output (waveform and spectrum) plus an audio= render of the same pattern through the PatSpatialSoundDocs renderer, which already makes the page's other wavs (requires steam-audio).

### Placing a source (`public/docs/pat_spatial.md:44`, medium, docs-runner)

- **Point made:** Whole cycles alternate between the kit and hats groups, each sweeping in azimuth, and the reader has to imagine the spatial movement.
- **Capture:** Binaural audio= render of the kit/hats example through PatSpatialSoundDocs (requires steam-audio), made like the head-tracked and source wavs already on the page.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

# Make patRenderStereo frame-exact with patSynth (transcription findings need restating)

- **Handoff:** `244eceb7-d253-45e0-ab09-18c0a549e152` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_pattern_particles/
- **Filed by:** human, 2026-10-15 23:27
- **Close with:** `python tools/docs_notes.py handoff-close 244eceb7 "what happened"`

Refiled from cce95ea4 (the advanceOffline half of that handoff is done: it no-ops while an engine pulls the launch).

`public/docs/chain_pattern_particles.md` still says `patRenderStereo` "works in seconds rather than frames, so a note it plays can start or end one frame away from the same note played by `patSynth`."

What was tried, 2026-10-15: `patRenderStereo` delegated to `patSynthRenderStereo` with a voice per concurrent note (no stealing). That output is bit-identical to the bank path (checked: max diff 0.0). But it differs from the old renderer by one-frame onset shifts (`toInt` vs `Math.round` in `patSynthNotesForArc`), hold rounding (`holdFrames/sr`) and per-sample double summation across voices, and that broke:

- `PatPitchTranscriptionTest` — with round stamps, "detached scale" worst pitch error 100 cents and "merge beats pitch" ties at 0.875; with `patSynthNotesForArc` switched to truncation, only `an incomplete transcription is what fails to re-render` still fails (`legato-scale` closes the loop with 2 wrong names). These tests pin findings measured on the old renderer's exact output; `PatTranscriptionCorpus.referenceOf` mirrors its truncation.
- `DocsAudioProvenanceGuardTest` / `SoundProvenanceGuardTest` — every committed stand-in `.wav` rendered by `patRenderStereo` (and, with truncated stamps, the `chain_pattern_particles/seam-*.wav` assets) must be regenerated.

Needs a decision on whether the transcription findings may be restated against the new renderer, then: switch the renderer, re-measure/restate those tests, regenerate the audio assets, delete the page paragraph.

# Render the CA and pattern-particles audio with Dirt samples

- **Handoff:** `183eeeef-29a2-44bc-bae2-25097f1cdfba` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_ca_sound/
- **Filed by:** human, 2026-10-02 15:11
- **Close with:** `python tools/docs_notes.py handoff-close 183eeeef "what happened"`

`chain_ca_sound.md` and `chain_pattern_particles.md` are pages whose subject is sound, and every clip on
them is rendered by a synth voice (`patVoice` square/noise/triangle in `CaSoundDocs`, Field's own
`PatSynthBank` voices in `PatSynthDocs`). DOCS_WRITING_RULES rule 15 asks for real material: the pinned
Dirt-Samples (`./gradlew fetchDocsDirtSamples`). Re-render the hat/kick clips with Dirt samples (the
rule-90 scale can stay pitched if a sampled instrument is used), keep the audio-spec names, and recapture
through the runner. The pattern-particles page's band plot and still should be recaptured with the same
material so they match what is heard.

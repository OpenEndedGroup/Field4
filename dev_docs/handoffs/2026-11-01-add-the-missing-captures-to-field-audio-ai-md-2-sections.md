# Add the missing captures to field_audio_ai.md (2 sections)

- **Handoff:** `d8dae6e3-658c-446b-a55a-d2e177d11645` (project)
- **Filed from:** https://openendedgroup.com/field4/field_audio_ai/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close d8dae6e3 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Variations of a source (`public/docs/field_audio_ai.md:35`, high, needs-gpu-or-hardware)

- **Point made:** Eight MusicGen-Style variations of a 4.5 s source window arrive as a candidate board.
- **Capture:** A box capture of the candidate board, plus audio= players for the source and two or three candidates, produced by running the box against the real service with weights. Committed media are retained like the other audio_ai pages.

### Gestures and how strongly they are enforced (`public/docs/field_audio_ai.md:153`, medium, docs-runner)

- **Point made:** The gesture board shows timed regions and curves, and how each one reaches the sound (direct, compiled, evaluative or transformative), with per-lane scores next to the candidates.
- **Capture:** Embed the existing real capture public/docs/assets/audio_ai_gesture_board/gesture_board.png, or recapture it from the gesture-board page's runner.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

Done 2026-11-02: "Variations of a source" is now a `capture-profile="linux-nvidia"` runner block that submits the real MusicGen-Style job (AudioCraft worker on the RTX 4090) and captures the source window plus the eight-candidate board: `public/docs/assets/field_audio_ai/style_candidates.png`. The snippet now names `backend = "audiocraft.musicgen_style"`: with the default `null` backend the service resolved `generate.style` to `development.source_granulator`, which is not what the page describes (and failed on the float WAV with "unknown format: 3"). The board's own players are the audition; no separate audio= exports were committed. "Gestures and how strongly they are enforced" already embedded the real `audio_ai_gesture_board/gesture_board.png`; it was recaptured with the gesture-board page.

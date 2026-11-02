# Add the missing captures to audio_ai_gesture_board.md (1 section)

- **Handoff:** `27f67cea-c9e8-43b7-a755-0a8603a66bb1` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_gesture_board/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 27f67cea "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### Generate from the sketch (`public/docs/audio_ai_gesture_board.md:69`, high, needs-gpu-or-hardware)

- **Point made:** The sketch drives MusicGen to produce eight candidate sounds shown in a gesture and audition board, which is the punchline of the page, yet no candidate board or audio appears.
- **Capture:** Box capture of the 'gesture' and 'audition' channels after the candidates return, plus audio= embeds of one or two generated candidates. Needs the audio AI server on an NVIDIA GPU (capture-profile linux-nvidia, as audio_ai_analysis uses).


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.

Done 2026-11-02: "Generate from the sketch" is now a `capture-profile="linux-nvidia"` runner block (sketch folded, `runAsync` instead of the blocking `.get()`, backend pinned to `audiocraft.musicgen_style` for the same reason as the field_audio_ai handoff). It captures the 'gesture' channel with per-candidate fit scores and the 'audition' board from a real eight-seed MusicGen-Style run: `public/docs/assets/audio_ai_gesture_board/gesture_candidates.png`. The board's own players are the audition; no separate audio= exports were committed.

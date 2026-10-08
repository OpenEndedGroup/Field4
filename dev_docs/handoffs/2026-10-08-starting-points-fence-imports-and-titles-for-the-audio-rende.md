# Starting points, fence imports and titles for the audio render/LoRA/codec pages

- **Handoff:** `a968e719-8874-4bc3-a54b-24f0b53bd782` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_render_analysis/
- **Filed by:** human, 2026-10-08 18:10
- **Close with:** `python tools/docs_notes.py handoff-close a968e719 "what happened"`

Found in the 2026-10-08 docs-language sweep of `audio_render_analysis.md`, `audio_ai_underfit_mancini.md` and `audio_ai_codec.md`. Prose-only edits were made; these need more:

1. **Starting points.** `audio_render_analysis` and `audio_ai_underfit_mancini` transcripts are linked only via `agent_transcripts.md`. Make them welcome-screen starting points and link them from the pages.
2. **Fence imports.** The runner fences on all three pages import `afield.stdlib.noir.audioai.*`, `afield.stdlib.AudioVisualizer(Options)`; the main example on `audio_render_analysis` too. Drop or fold per rule 7 (byte-pinned fences, so this is a code + recapture change).
3. **Titles (decision).** "EnCodec reconstruction microscope" and "Experiment in Terror with an Underfit LoRA" don't say what the page is for. Suggest "EnCodec round trip" and "Generating with a LoRA"; nav change, so Marc's call.

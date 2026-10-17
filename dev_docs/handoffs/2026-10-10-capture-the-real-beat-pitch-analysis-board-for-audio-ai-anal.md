# Capture the real beat/pitch analysis board for audio_ai_analysis

- **Handoff:** `27cd487c-5423-4ee2-b346-26d360c6554e` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_analysis/; also affects visualizers.md, agent_transcripts.md
- **Filed by:** human, 2026-10-10 23:52
- **Close with:** `python tools/docs_notes.py handoff-close 27cd487c "what happened"`

`public/docs/audio_ai_analysis.md` has no figure. Its old one, `assets/audio_ai_analysis/synchronized_analysis.png`, drew hand-written sine data as beat, pYIN and Basic Pitch results. It was removed on 2026-10-10, along with the copies on `visualizers.md` (Beat and pitch analysis views) and `agent_transcripts.md` (audio_ai_analysis).

On a machine where the Field Audio worker answers (`FieldAudioClient()` defaults to `http://127.0.0.1:8765`; check `/v1/...` returns JSON, not a 404 from some other server):

1. Turn the "Running all three from one box" fence (Nancy, 18–42 s) into a `diffstatic_runner` and capture the real analysis board. Mark it the way the other Field Audio captures are marked, so other machines retain it.
2. Put the captured board back on `visualizers.md#beat-and-pitch-analysis-views` and the `audio_ai_analysis` entry on `agent_transcripts.md`, with alt text written from the real picture.
3. The transcript's `analysis_visualizer_gallery` box also draws made-up previews. Decide whether it should go.

That fence did not compile before 2026-10-10: `client.watch(id) { event -> … }` bound the lambda to `onError`. `FieldAudioClient.watch` now takes `onEvent` last, so the spectral, speech and corpus-microscope pages' calls compile too. None of them has been run against the worker since.

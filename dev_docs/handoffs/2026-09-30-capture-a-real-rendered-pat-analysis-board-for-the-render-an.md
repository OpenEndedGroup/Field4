# Capture a real rendered-Pat analysis board for the render-analysis page

- **Handoff:** `244eb419-70be-4142-8205-44a552b0868c` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_render_analysis/
- **Filed by:** agent:claude, 2026-09-30 20:48
- **Close with:** `python tools/docs_notes.py handoff-close 244eb419 "what happened"`

## The notes that led here

- `05f1475f` (public/docs/audio_render_analysis.md:3): need a screenshot
  > Write a Pat pattern in a box, render twelve cycles of it, and there is a finite WAV file on disk before you have heard it play. That file is all Field Audio ever sees of the music. Pat, Dirt, Dough and Faust render faster than real time and Sonic Pi records a real-time performance, but every one of them hands back the same RenderedAudioFile, and FieldAudioClient.analyzeRenderedAudio(...) takes it from there. It imports the immutable bytes, submits cached analysis jobs, follows progress and returns durable results.

## What was asked

Note `05f1475f` on `public/docs/audio_render_analysis.md` (the opening paragraph, lines 3-8) says
"need a screenshot". The page's main claim is that a rendered Pat pattern becomes one synchronized
board: the waveform player of the render, with beat, pYIN and Basic Pitch lanes under it, all on
one cursor ("The final view behaves as one instrument", after the "Rendering a Pat pattern and
analysing it" fence). The page has no image of that board.

## Why this is bigger than a docs edit

Every fence on the page is `diffstatic`, so none of them runs. The figure the note wants is the
output of `RenderedAudioAnalysis.visualizationHtml(...)`
(`src/jvmMain/kotlin/afield/stdlib/noir/audioai/RenderedAudioAnalysis.kt`, `data class
RenderedAudioAnalysis`). Producing it means `FieldAudioClient.analyzeRenderedAudio` has to import
the WAV into a live Field Audio API and wait for the librosa beat/pYIN and Basic Pitch workers.
The player half also streams from that service (`serviceBaseUrl = "/field-audio"`). The docs
runner starts none of this. AGENTS.md §3 says a capture must exercise the real code, so an agent
can't add a figure here from prose. The capture system needs a decision, and possibly a service
profile. This agent also could not run the docs build to test a new runner fence.

This is the same blocker as handoff `29e1e289` (MuScriptor piano roll) and handoff `dfbfbca4`
(Field Audio speech/spectral/transcription/corpus views on `visualizers.md`). The difference here
is that the beat/pitch workers need no gated weights.

## Evidence

- The render half is local and real. `renderPatWav` / `renderDirtWav`
  (`RenderedAudioAnalysis.kt`, `fun renderPatWav`, `fun renderDirtWav`) write a WAV with no service.
- The live boxes already exist: `public/docs/assets/agent_transcripts/audio_render_analysis/`
  (`analyse_pat_render_code_.kt`, `analyse_dough_render_code_.kt`, `analyse_faust_render_code_.kt`,
  `analyse_sonic_pi_recording_code_.kt`) calls `completed.visualizationHtml(width = 1040,
  panelHeight = 230)`. Running the Pat box with the service up gives exactly the figure the note
  asks for.
- Precedent for a model-free figure: `public/docs/audio_ai_analysis.md` captures
  `assets/audio_ai_analysis/synchronized_analysis.png` from a `diffstatic_runner` fence built on
  synthetic `AnalysisPreview`s through `analysisPreviewDashboardHtml`. That is the same renderer,
  but with invented data.

## Approaches

1. **Real analysis, committed as a fixture (preferred).** Run the Pat box from the transcript
   once against a live Field Audio service. Commit the rendered WAV (it is small: 12 cycles at
   1.6 cps is 7.5 s) and the three fetched `AnalysisPreview` JSONs under
   `public/docs/assets/audio_render_analysis/`. A runner fence then shows the real WAV through
   `AudioVisualizer` and the real previews through `analysisPreviewDashboardHtml` on one
   `syncGroup`. Every value in it is real model output, and the docs runner needs no service.
2. **Service in the runner behind a `capture-profile`**, like `linux-nvidia`. The capture would
   drive `analyzeRenderedAudio` end to end. That is the most honest option, but also the heaviest:
   the capture machine needs the Python env with librosa and Basic Pitch.
3. **Waveform only, now.** Run a fence that calls `renderDirtWav` and displays the real render
   with its provenance line and no analysis lanes. It is honest but shows only half the loop.
4. Reuse `synchronized_analysis.png` from `audio_ai_analysis.md`. Not recommended: its data is
   synthetic and is not a Pat render.

## Questions for a human

- Is a committed real preview fixture (approach 1) acceptable as a capture, or must the docs
  build run the analysis itself (approach 2)?
- Should this page and `audio_ai_analysis.md` share one real fixture? If they did, the
  synthetic figure there could be retired.
- May Dirt samples appear in a published render? If not, `renderPatWav` is sample-free.

## What the page needs afterwards

Put the image (or the runner fence plus the image) right after the "Rendering a Pat pattern and
analysing it" code block, before "The final view behaves as one instrument". The alt text should
name the Pat render's waveform and transport, the provenance line, and the beat, pYIN and Basic
Pitch lanes. Then resolve `05f1475f`.

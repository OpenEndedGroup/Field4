# Capture a speech-embedding phase-portrait figure for the speech embeddings page

- **Handoff:** `baf524f9-880a-4ff1-bb35-a44f017931f1` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_speech_embeddings/; also affects visualizers.md
- **Filed by:** agent:claude, 2026-09-30 20:47
- **Close with:** `python tools/docs_notes.py handoff-close baf524f9 "what happened"`

## The notes that led here

- `c16e334a` (public/docs/audio_ai_speech_embeddings.md:3): need a screenshot of some kind of visualization
  > A field recording of someone talking gives a beat tracker almost nothing to hold onto. What moves in it is voice activity, vocal timbre, prosody and phonetic motion, and that is what analysis.embed_speech measures. Two speech models sit behind that one operation, and which of them you ask changes what the motion is about.

## What was asked

Note `c16e334a` on `public/docs/audio_ai_speech_embeddings.md:3-6` (the page intro): "need a
screenshot of some kind of visualization". The page has no image at all. Its only Kotlin fence
(`:38`) is a non-running `diffstatic` that needs a live Field Audio speech worker, a
`~/Downloads/near_drowning.wav`, and two neural models.

## Why this is bigger than a docs edit

This is the same capture problem as handoff `dfbfbca4` ("Capture the Field Audio speech, spectral,
transcription, corpus and waveform-endpoint views", filed from `visualizers.md`). That handoff
already asks whether the speech-portrait capture should live on this walkthrough page and be
reused by `visualizers.md#speech-embedding-phase-portraits`. Decide both together. The docs runner
has no Field Audio worker, and AGENTS.md §3 forbids a faked capture. So the capture needs either a
decision on model-free fixtures or runtime and capture-system work to get real output.

## Evidence

- `dev_docs/agents/transcripts/audio_ai_speech_embeddings.yaml:26-83`: box "2 · model-free
  synchronized MDS gallery" already builds `AnalysisPreview` fixtures (labelled
  "synthetic calibration") and renders them with the real `speechEmbeddingPreviewDashboardHtml`
  (`src/jvmMain/kotlin/afield/stdlib/noir/audioai/SpeechEmbeddingVisualization.kt:140`) on one
  shared `syncGroup`. This box could become a `diffstatic_runner` fence with `screenshot=` almost
  unchanged, following the pattern of `public/docs/audio_ai_analysis.md:33`.
- The same transcript's box 3 (`:84` onward) is the real `near_drowning` path. It needs the
  worker.

## Approaches

1. **Model-free calibration figure (cheap).** Lift transcript box 2 into a runner fence on this
   page. Capture `assets/audio_ai_speech_embeddings/calibration_dashboard.png`, and caption it as
   a synthetic trajectory through the real renderer. `visualizers.md` then reuses the PNG.
2. **Real result figure.** Commit a real, small `speechEmbeddingSmileTsnePreview` result (or the
   MessagePack artifact) from one worker run on a licensable recording, and feed it to the
   preview renderer in the fence. This shows what the models actually produce. It needs a fixture
   format and a loader, and a decision on the recording's license.
3. Both: a calibration figure now, with a real one replacing it when route 2 lands.

## Questions for a human

- Is a synthetic-trajectory figure acceptable at the top of this page, given that the intro is
  about what the models measure? Or does this page need real output (route 2)?
- If route 2: which recording, and may a derived preview of it be committed? (`near_drowning.wav`
  is a local file in `~/Downloads`, and its provenance is not recorded.)

## What the page needs once done

Put a figure after the intro paragraph (after line 6, before the backend table). Its alt text
should describe the two phase portraits per backend and the voice/energy/novelty strip, and say
whether the data is a fixture. If the capture fence lives here, put it in the "Complete Field
path" section, or in a new short "Without the worker" section, and keep it runnable.

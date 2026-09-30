# Capture the corpus microscope neighbour board for its walkthrough page

- **Handoff:** `638fa3f1-24ae-44ca-9229-c0c82f864afe` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_corpus_microscope/; also affects visualizers.md
- **Filed by:** agent:claude, 2026-09-30 20:46
- **Close with:** `python tools/docs_notes.py handoff-close 638fa3f1 "what happened"`

## The notes that led here

- `244deec1` (public/docs/audio_ai_corpus_microscope.md:3): need a screenshot
  > Nancy and Final Out at Candlestick Park are two recordings sitting on disk. Pick six seconds of Nancy, and the corpus microscope finds the stretches of either recording that sound most like it, having already cut both into overlapping windows at 1.5, 3, 6 and 12 seconds and measured every one. Those windows are recorded as boundaries and offsets; the audio itself is never rewritten. Closeness is yours to set: the distance is five separate measurements with a weight each, not one similarity number.

## What was asked

Note `244deec1` on `public/docs/audio_ai_corpus_microscope.md:3-8` (the opening paragraph) says
"need a screenshot". The page describes the neighbour board (`corpusMicroscopeHtml`) in words only:
neighbour cards, grouped distance bars, splice players, aligned waveforms.

## Why this is bigger than a docs edit

This is the same capture already asked for on `visualizers.md` in handoff `dfbfbca4` ("Capture the
Field Audio speech, spectral, transcription, corpus and waveform-endpoint views", note `5bf53e81`).
Treat this handoff as a second consumer of that figure and settle both together.

- Both fences on the page are `diffstatic` (`audio_ai_corpus_microscope.md:31`, `:72`). They need a
  live Field Audio worker (`FieldAudioClient`, `client.submit`, `client.corpusPreview`,
  `FieldAudioClient.kt:353`) and the Nancy / Final Out at Candlestick Park recordings. The docs
  runner has neither.
- `corpusMicroscopeHtml` (`src/jvmMain/kotlin/afield/stdlib/noir/audioai/CorpusMicroscope.kt:52`)
  requires an `analysis.find_neighbours` `CorpusPreview` (`Protocol.kt:823`) and a WAV
  `AssetRepresentation`. It also fetches audio and waveform envelopes from `serviceBaseUrl`
  (default `/field-audio`). A hand-built preview fixture would render the cards and distance bars,
  but with no service behind it the waveform lanes and players would come out empty. That picture
  misrepresents the board, and AGENTS.md §3 forbids faking a capture.
- The loadable transcript `dev_docs/agents/transcripts/audio_ai_corpus_microscope.yaml` builds the
  real boxes. Capturing it still needs the worker.

## Proposed approach

1. Decide route 1 or route 2 from handoff `dfbfbca4` (a model-free fixture vs. a real worker or a
   committed real result fed into the preview path). For this view route 1 is weak, because the
   waveforms need the HTTP service. A committed real `find_neighbours` result, plus a stub
   waveform/audio route that serves the real committed WAV excerpts, would give an honest figure.
2. Keep the capture in one place. It could live on this walkthrough page, with `visualizers.md`
   reusing the asset (as it already does for `assets/audio_ai_analysis/synchronized_analysis.png`),
   or the other way round.

## Questions for a human

- Can the docs runner reach a Field Audio worker (maybe under a `capture-profile`), or should the
  figure come from a committed real result?
- Should the asset be owned by this page or by `visualizers.md`?

## What the page needs once done

Add an image or `embed=true` capture of the neighbour board after the query fence
(`audio_ai_corpus_microscope.md:93`), or under the opening paragraph. Give it alt text naming the
query window, the neighbour cards and the distance bars, and say whether the data is a fixture.

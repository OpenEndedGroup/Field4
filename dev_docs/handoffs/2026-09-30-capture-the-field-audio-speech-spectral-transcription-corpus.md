# Capture the Field Audio speech, spectral, transcription, corpus and waveform-endpoint views

- **Handoff:** `dfbfbca4-de2a-4f89-904c-8f7e688814ac` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** agent:claude, 2026-09-30 20:38
- **Close with:** `python tools/docs_notes.py handoff-close dfbfbca4 "what happened"`

## The notes that led here

- `7aebcfe7` (public/docs/visualizers.md:1175): screenshot needed!
  > speechEmbeddingVisualizationHtml(result) turns the worker's cached learned-frame preview into two phase portraits: MDS axes 0×1 and 2×3. The complete 768-dimensional embedding remains in the immutable NPZ; only a bounded preview crosses into the browser. A strip underneath shows WebRTC voice activity, dBFS energy, and embedding novelty.
- `129b79b7` (public/docs/visualizers.md:1220): screenshot needed!
  > spectralEmbeddingPreviewVisualizationHtml(preview) uses the same two phase portraits and shared time bus for a Field-owned MDS or t-SNE projection of mel, log-mel, or MFCC frames. The lower strip shows energy, mel-distribution flux, and spectral centroid; path opacity follows normalized flux. Hovering reports time, energy, flux, centroid, and flatness, and clicking seeks any linked audio visualizer.
- `36f8796b` (public/docs/visualizers.md:1253): screenshot needed!
  > The same analysisVisualizationHtml viewer recognizes analysis.transcribe_music results as a MuScriptor piano roll. Note colour follows the recovered instrument group; the two lower lanes show active polyphony and onset count. It uses the same syncGroup time bus as the waveform, beat, pitch, speech-space, and spectral-space views, so playback, hover, seek, zoom, and pan stay aligned.
- `5bf53e81` (public/docs/visualizers.md:1281): screenshot needed!
  > corpusMicroscopeHtml(preview, queryAudio) composes the same general AudioVisualizer into a source-relative A/B board. Each neighbour retains its real asset and source timestamp, but every player uses syncOffsetSeconds = -segment.startSeconds; shared time zero is therefore the start of the selected window even when two windows come from different recordings. Hover, zoom, seek and playhead callbacks remain ordinary field-time-* events.
- `775a5db0` (public/docs/visualizers.md:1298): screenshot needed!
  > AudioVisualizer is not coupled to Field Audio. Any HTTP service can provide waveformUrl. The visualizer adds these query parameters whenever its visible interval changes:

## What was asked

Five "screenshot needed!" notes on `visualizers.md`, one per Field Audio section that shows code but
no picture:

- Speech embedding phase portraits (`speechEmbeddingVisualizationHtml`)
- Short-time spectral phase portraits (`spectralEmbeddingPreviewVisualizationHtml`)
- Instrument-labelled transcription (`analysisVisualizationHtml` on `analysis.transcribe_music`)
- Corpus neighbour microscope (`corpusMicroscopeHtml`)
- Waveform-summary endpoint contract (`AudioVisualizer` with a custom `waveformUrl`)

A sixth, "Beat and pitch analysis views", is resolved on the page: it now embeds the real
capture `assets/audio_ai_analysis/synchronized_analysis.png` that `audio_ai_analysis.md` makes.

## Why this is bigger than a docs edit

No committed capture exists for any of these five. None of the linked walkthroughs
(`audio_ai_speech_embeddings.md`, `audio_ai_spectral_embeddings.md`, `audio_ai_muscriptor.md`,
`audio_ai_corpus_microscope.md`) has a `screenshot=` fence or an image, so there is nothing to
reuse. Every code sample on this page for these views is a `diffstatic` (non-running) fence that
needs a live Field Audio worker (`FieldAudioClient`, `client.submit(...)`), and the docs runner has
no such worker. Per AGENTS.md §3 a capture must exercise real code. That leaves two honest routes,
and choosing between them is a decision about the capture system:

1. **Model-free preview fixtures, the way `audio_ai_analysis.md:33` does it.** Construct an
   `AnalysisPreview`/`CorpusPreview` in the fence and call the no-fetch renderers:
   `speechEmbeddingPreviewVisualizationHtml` / `spectralEmbeddingPreviewVisualizationHtml`
   (`src/jvmMain/kotlin/afield/stdlib/noir/audioai/SpeechEmbeddingVisualization.kt:58`, `:76`),
   `analysisPreviewVisualizationHtml` with transcription notes (`AnalysisVisualization.kt:60`),
   `corpusMicroscopeHtml` (`CorpusMicroscope.kt:52`). The renderer is real, but the data is
   invented. That is defensible for a layout figure, but the speech/spectral portraits then show
   a made-up trajectory, and the caption must say so.
2. **A Field Audio worker available to the docs runner** (or a committed, real result NPZ /
   MessagePack fixture from a real run fed into the preview path), so the figures show real
   analysis output. This is heavier, and it may need a `capture-profile` like the GPU captures.

The waveform-endpoint section needs a tiny HTTP endpoint that answers the documented query. That
could be a Ktor route in the fence's box or a test fixture server. It is still runtime work, and
the page cannot do it alone.

## Questions for a human

- Are model-free fixtures acceptable for these figures (route 1), or should they wait for real
  analysis output (route 2)?
- If route 1, should the captures live on each walkthrough page (and this page reuse them, as it
  now does for beat/pitch), or on this page?
- Is a waveform-endpoint figure wanted at all? The section is a wire contract, and the picture
  would be a waveform that looks like any other.

## What the page needs once done

Replace each note's section intro with an image line pointing at the new asset (or an
`embed=true` fence), with alt text that says what the figure shows, and say whether the data is a
fixture.

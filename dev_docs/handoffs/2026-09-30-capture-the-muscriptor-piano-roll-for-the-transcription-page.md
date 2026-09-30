# Capture the MuScriptor piano roll for the transcription page

- **Handoff:** `29e1e289-b0c3-4463-9ea5-a24b981e6977` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_muscriptor/; also affects visualizers.md
- **Filed by:** agent:claude, 2026-09-30 20:47
- **Close with:** `python tools/docs_notes.py handoff-close 29e1e289 "what happened"`

## The notes that led here

- `b71185e9` (public/docs/audio_ai_muscriptor.md:3): need a screenshot
  > MuScriptor closes a useful loop in Field Audio: a mixed recording becomes an inspectable, instrument-labelled note sequence and ordinary MIDI. The integration keeps Field in charge of the source window, job, cache key, artifacts, visualization, and subsequent composition. MuScriptor runs in a separate Python environment and can be replaced without changing the Field project model.

## What was asked

Note `b71185e9` on `public/docs/audio_ai_muscriptor.md` (the opening paragraph) says "need a
screenshot". The page describes a waveform above an instrument-coloured piano roll, with
polyphony and onset lanes, all on one time bus (`audio_ai_muscriptor.md`, "Field: source to
synchronized score"), but it has no image.

## Why this is bigger than a docs edit

The only code on the page is `diffstatic` (non-running). It needs a live Field Audio API
(`./scripts/run-api.sh`) plus the MuScriptor worker (`./scripts/run-muscriptor-worker.sh`), and
that worker downloads gated CC BY-NC 4.0 weights after a Hugging Face login. The docs runner has
neither. AGENTS.md §3 says a capture must exercise real code, so the page can't get a figure
without a decision about the capture system.

This is the same figure that handoff `dfbfbca4` ("Capture the Field Audio speech, spectral,
transcription, corpus and waveform-endpoint views", note `36f8796b` on `visualizers.md`) already
asks for. Whatever closes that handoff for the transcription view should also close this one, and
both pages should embed the same asset.

## Evidence

- A model-free renderer exists: `analysisPreviewVisualizationHtml(preview: AnalysisPreview, ...)`
  in `src/jvmMain/kotlin/afield/stdlib/noir/audioai/AnalysisVisualization.kt` ("Render a
  deterministic or already-fetched preview without contacting the service").
- `AnalysisNote` has `instrument: String?` ("MuScriptor instrument group"), and `AnalysisPreview`'s
  KDoc says `analysis.transcribe_music` fills instrument-labelled notes plus active-note and
  onset-count series (`Protocol.kt`, near `data class AnalysisNote` / `data class AnalysisPreview`).
  So a hand-built `AnalysisPreview("analysis.transcribe_music", ...)` would draw the real
  MuScriptor piano-roll path.
- Precedent: `public/docs/audio_ai_analysis.md` captures `assets/audio_ai_analysis/synchronized_analysis.png`
  from a `diffstatic_runner` fence that builds synthetic `AnalysisPreview`s and labels its source
  "model-free synchronized source".
- A real run exists but isn't committed: `dev_docs/MUSCRIPTOR_VERIFICATION.md` records an
  8.192 s Sonic Pi recording transcribed by the small checkpoint (46 notes with worker v2). Its
  artifacts live in the Field Audio cache, not the repo.

## Approaches

1. **Committed real fixture (preferred).** Re-run the small model on a recording we have rights to
   (the Sonic Pi study), then commit the fetched preview JSON (or the MessagePack transcription)
   under `public/docs/assets/audio_ai_muscriptor/`. A runner fence would decode it into an
   `AnalysisPreview` and call `analysisPreviewVisualizationHtml` next to
   `AudioVisualizer.renderSamplesHtml` on the same `syncGroup`. The picture shows real model
   output, and the docs runner needs no worker.
2. **Synthetic preview, as `audio_ai_analysis.md` does it.** This is quick and uses the real
   renderer. But the notes would be invented, on the one page whose subject is what the model
   recovers, so the caption would have to say plainly that it isn't a transcription.
3. **Worker in the docs runner behind a `capture-profile`**, like the GPU captures. This is the
   heaviest option. It also needs gated weights on the capture machine.

## Questions for a human

- Is a synthetic note set acceptable on this page, or only on `visualizers.md`?
- Which recording can we publish a transcription of (rights to the input *and* output are part
  of the weight licence terms)?
- Should the fixture live with this page's assets and be reused by `visualizers.md`?

## What the page needs afterwards

Add a runner fence (or the shared asset) plus an image right after the "Field: source to
synchronized score" code block, before the paragraph "The waveform and piano roll share Field's
general time bus". Its alt text should describe the waveform, the instrument-coloured notes and
the polyphony/onset lane. Then resolve `b71185e9`.

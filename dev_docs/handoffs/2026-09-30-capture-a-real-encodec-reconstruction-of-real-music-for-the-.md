# Capture a real EnCodec reconstruction of real music for the codec microscope

- **Handoff:** `c9bc554a-8229-49c1-9bbd-235c0432889c` (project)
- **Filed from:** https://openendedgroup.com/field4/audio_ai_codec/
- **Filed by:** agent:claude, 2026-09-30 20:46
- **Close with:** `python tools/docs_notes.py handoff-close c9bc554a "what happened"`

## The notes that led here

- `00a37ca1` (public/docs/audio_ai_codec.md:3): we should do this with real music, not (just) a chirp
  > Field keeps codec tokens visible as compositional data. A codec.reconstruct operation produces an immutable model-native input, an NPY token tensor, a model-native reconstruction, a 48 kHz audition proxy, reconstruction metrics, and full model provenance. The renderer below is the same afield.stdlib.noir.audioai.CodecVisualization that draws service results; the fence hands it a synthetic fixture, so you can run it and read the display with no GPU worker attached.

## What was asked

Note on `public/docs/audio_ai_codec.md:3-7`: "we should do this with real music, not (just) a chirp."

## Why this is bigger than a docs edit

The page's only figure comes from a fence (`public/docs/audio_ai_codec.md:9-43`) that builds a
synthetic source (a chirp plus two sines at 8 kHz), then makes a *fake* "reconstruction" out of it
(`source * 0.91 + two small sinusoids`, lines 21-25). It also makes up the token tensor (lines
27-31) and hardcodes the metrics (`ReconstructionMetrics(0.0123, 18.7, 0.143)`, line 37). The
only real code in the capture is the renderer, `CodecVisualization.renderHtml`
(`src/jvmMain/kotlin/afield/stdlib/noir/audioai/CodecVisualization.kt`). The companion transcript
`dev_docs/agents/transcripts/audio_ai_codec.yaml` says the same thing: the fixture exists
"without pretending to run EnCodec when the isolated GPU service is absent."

Swapping the chirp for a music WAV in the fence would not help. The "reconstruction", the tokens
and the metrics would still be invented, and AGENTS.md §3 forbids a capture like that ("A capture
exercises the real code, or it does not exist"). A real reconstruction of real music needs:

- the Field Audio service (`services/field-audio-ai` submodule, `http://127.0.0.1:8765`), with the
  `audiocraft` worker environment installed and the EnCodec 32 kHz model downloaded. Per
  `dev_docs/field_audio_seam_stub.md`, this machine has no worker environment, only
  `README.md`/`adapter.py`/`pyproject.toml`/`uv.lock`;
- a music asset registered with the service (`client.assetByFilename(...)`, as in
  `public/docs/field_audio_ai.md`), and an `AssetRegion` of it;
- `codecReconstruction(region)` (`FieldAudioClient.kt`, "Exact EnCodec encode/decode round trip"),
  then `client.awaitCodec(accepted)`, then `client.codecVisualizationHtml(result, cacheDirectory)`;
- a capture path the docs build can keep: a `capture-profile` for a machine with the worker, or
  committed media with its own regeneration script (like the neural-tool pages,
  `dev_docs/lighting/NEURAL_TOOLS.md`).

## Proposed approach

1. Choose a short music excerpt (10-20 s) that is licensed for redistribution. It could be a Field
   render made from existing docs WAVs (for example `public/docs/assets/faust/poly-chord-eight-voices.wav`
   or a pattern-language beat), or a CC0 recording.
2. On a machine with the audiocraft worker, run the live path above, save the HTML/PNG and the
   48 kHz audition proxy as committed media, and record the model revision and request hash
   alongside them.
3. Put that figure on the page as the lead, captioned with the model provenance. Keep the synthetic
   fixture fence as the "no GPU worker" demo, clearly labelled, or drop it.

Alternatives:
- Add a new `capture-profile` (for example `field-audio-gpu`) that runs the live fence only where
  the service answers and keeps the committed capture everywhere else. This is more engineering, but
  the figure regenerates.
- Keep the fixture's renderer demo, but make its source a *Field-rendered* musical passage rather
  than a chirp. That is honest only if the reconstruction stays labelled as a fixture, so it does
  not really meet the note.

## Questions for the human

- Which music? A Field render, a CC0 clip, or something of yours you are happy to publish?
- Committed media, or a new capture profile tied to a machine with the worker?
- Should the synthetic fixture fence stay on the page next to the real one?

## What the page needs afterwards

Lead with the real-music figure and its provenance (model, codebooks, metrics as measured). Reword
lines 5-7 so the fixture is presented as the offline demo, not the main result. Update
`dev_docs/agents/transcripts/audio_ai_codec.yaml` if the fence changes.

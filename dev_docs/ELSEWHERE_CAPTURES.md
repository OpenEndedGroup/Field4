# Elsewhere documentation captures

The Linux/NVIDIA pass uses the tracked handoffs in `dev_docs/handoffs/` because this
machine has no copy of the Mac's `~/.afield/docs-annotations.json`. No annotation
records were invented. Completion is recorded in the handoff documents; their IDs
can also be closed in the originating annotation store after these changes land.

## Audio

Start the API and AudioCraft, analysis, speech and MuScriptor workers from
`/home/marc/temp/music/scripts/`. The API is `http://127.0.0.1:8765`; its project is
`/home/marc/temp/music/audio-project/live`.

`python tools/capture_elsewhere_audio.py` exports actual completed service results,
previews and immutable artifacts, then renders Pat through the pinned Dirt samples
and submits real beat, pYIN and Basic Pitch jobs. It verifies downloaded bytes
against their content-addressed asset IDs and checks MuScriptor note bounds.
`public/docs/assets/elsewhere_audio/manifest.json` records the exported file hashes.
The Pat source and sample-use assertions are in
`tools/docs_capture_elsewhere/render_pat.kt`.

- **EnCodec:** the committed Faust eight-voice chord, 4.25 seconds; real 32 kHz
  model input, native reconstruction, four codebooks and measured errors. Both
  WAVs are playable from the docs page. AudioCraft and checkpoint revisions remain
  in `audio_ai_codec/result.json`.
- **MuScriptor:** a real 8.192-second Sonic Pi recording, reprocessed by worker v2;
  46 notes, all within the source duration. The small checkpoint revision and
  checksum are in the result's representation metadata. Instrument colours remain
  model hypotheses.
- **Speech:** cached emotion2vec and WavLM Base+ worker-v2 results of
  `near_drowning.wav`; the exported bounded previews retain the workers' real
  landmark MDS coordinates, voice activity, energy and novelty. The figure labels
  these as MDS; the page's separate live workflow fits t-SNE.
- **Spectral:** the real librosa 0.11.0 MessagePack artifact; Field computes three
  fixed-seed Smile t-SNE fits over the first ten seconds, with 200 ms pooling and
  40 ms hops. The companion gallery now uses these actual features.
- **Corpus:** the real six-second Nancy query at 18 seconds and the first two
  Candlestick neighbours. No commercial source audio was copied into the repo.
  The retained screenshot fetches real waveform envelopes and splice players from
  the running service.
- **Rendered Pat:** `pat("{bd ~ sd ~, hh*8}")`, 12 cycles at 1.6 cps. Rendering
  requires `DirtRender.requirePinnedLibrary()` and asserts that all three sample
  names matched. Peak normalization is 0.85. The exported waveform and beat/pitch
  previews replay without a service.
- **Waveform endpoint:** a separate retained capture loads the real Pat asset's
  waveform envelopes from the service. `visualizers.md` reuses all five real views.

The offline preview renderers do not rerun a neural model during the docs build;
they render the exported genuine results. Refresh exports before requesting an
`always` recapture after changing the source or model settings.

## GPU figures

`polytope_layer_stack_path_trace.yaml` makes canonical `gC`, scales its solid to
volume one, slices at 48 layer midpoints, extrudes the sections and imports them
into a real `PathTraceScene`. A small interlayer gap makes the stack visible.
`photo_mount_path_trace.yaml` imports the actual relief and frame returned by
`PhotoToSolidChain.mount()`. Both use the sibling figures' Venice environment and
wait for at least 900 accumulated samples.

The captures use `capture-profile="linux-nvidia"`. Other machines retain their
committed images. On this machine the renderer uses an RTX 4090 and the primary
HDMI-A-1 Xwayland monitor. Its Xauthority path is session-specific; read the
running Xwayland process before reusing the command.

## Rebuild

After `./gradlew fetchDocsDirtSamples`, export audio and recapture the affected pages:

```bash
python tools/capture_elsewhere_audio.py
DISPLAY=:0 XAUTHORITY=<current-Xwayland-auth-file> \
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json \
FIELD_DOCS_CAPTURE_PROFILES=linux-nvidia FIELD_DOCS_RUNNER_MODE=always \
FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR=false \
uv run --project . --group docs python tools/build_zensical_docs.py \
  --page audio_ai_codec --page audio_ai_corpus_microscope \
  --page audio_ai_muscriptor --page audio_ai_spectral_embeddings \
  --page audio_ai_speech_embeddings --page audio_render_analysis \
  --page chain_ceres_manifold_contours --page chain_photo_to_solid
```

A routine portable HTML build uses `FIELD_DOCS_RUNNER_MODE=never`. Rebuild loadable
companion documents with
`./gradlew regenerateAgentTranscriptStartingPoints checkAgentTranscripts`.

## Verification — 2026-10-03

All ten requested hardware/service handoffs are completed, including the
Underfit handoff `4832e1cc` whose title was classified under Decisions. Each capture was inspected as
an image, including the corrected three-view plaque capture and pinned-sample
Pat waveform. Actual model exports passed 35 SHA-256 checks and MuScriptor note
bounds checks. `regenerateAgentTranscriptStartingPoints checkAgentTranscripts`
passed for all 174 documents. The final clean HTML build passed with “No issues
found”; its published-asset check covered 191 pages and 1,527 references with no
missing or placeholder media. The final photo-page capture pass reported four
regenerated screenshots, one cached capture and zero failures.

The music API and four workers were verified listening on loopback ports
8765–8769 and remain running. The Elsewhere publication excludes existing local
transport work, which remains preserved in the original checkout.

## Underfit audio follow-up — 4832e1cc

The existing `result-cfg15-30s` render was recovered from
`/home/marc/temp/music/audio-project/underfit-proof/`; its native WAV SHA-256
matches the original candidate asset ID. No replacement LoRA take was generated.
The Underfit worker was started on port 8770 and its health confirmed RTX 4090
CUDA and the pinned Underfit/Stable Audio 3 revisions. A new baseline was rendered
with strength zero and all other source, seed and sampling controls identical.

The three Opus players preserve the real MJQ source and both audition proxies,
without gain normalization. Both proof records, genuine waveform envelopes and
the export manifest live under `assets/audio_ai_underfit_mancini/`. Export with:

```bash
python tools/export_underfit_docs.py
FIELD_DOCS_CAPTURE_PROFILES=linux-nvidia FIELD_DOCS_RUNNER_MODE=always \
FIELD_DOCS_RUNNER_CONTINUE_ON_ERROR=false \
uv run --project . --group docs python tools/build_zensical_docs.py \
  --page audio_ai_underfit_mancini
```

The exporter checks both native/proxy hashes and exact request equality apart
from strength; ffprobe confirmed stereo 48 kHz Opus copies of 12/30/30 seconds
(with normal Opus container pre-skip). The actual candidate-board capture passed
with one regenerated screenshot and zero failures and was visually inspected.
The board fetches real waveform windows through `/field-audio`, requiring the
API with the exported assets reimported; the three published players are portable.
The archive omits the unavailable old source→candidate splice instead of faking it.

The follow-up clean HTML build passed with no issues; all 2,066 distinct local
references resolved. The only absent fragment was the theme’s registered 404
skip-link exception.

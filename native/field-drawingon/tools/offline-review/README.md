# Offline capture review

Turn a Drawing On `.ndjson` save file into a contact sheet of the canvas the person was looking at, with time-aligned speech candidates and optional local transcription. This is a macOS command-line tool written in Swift. It uses the app's replay model and Field geometry decoder, Core Graphics, and the bundled Iosevka font. No Python package, server, network connection, or external model is needed to render a capture.

From the Drawing On checkout:

```sh
tools/offline-review/review '/path/to/Saved captures/SESSION.ndjson' --output /path/to/review
open /path/to/review/index.html
```

The first run compiles with the installed Xcode command-line tools, then caches the executable in `.build/offline-review/`. Subsequent runs reuse it until a source changes. Input is read-only. Use a new or empty output directory; the tool refuses to overwrite an existing review.

The output contains:

- `contact-sheet-001.png`, further pages as needed, with original viewport aspect ratios, capture-time ranges, and transcript captions when available.
- `frames/frame-0001.png` and subsequent canvas images at 1280 pixels wide. Native menu/tool overlays were not recorded and cannot be reconstructed; these are canvas views, not screenshots of the app chrome.
- `manifest.json`, the agent-facing index: source session/sequence/time, wall-clock anchors, frame cutoffs and view transforms, visible layers/stroke IDs, speech/run ranges, audio files, and transcription provenance.
- `audio/speech-0001.wav`, etc., 16 kHz mono PCM16 clips for candidate speech. Audio gaps and recording restarts remain separate runs; unrelated stretches are never concatenated into invented continuous time.
- `transcripts/` containing unmodified backend JSON/logs when transcription is enabled. Manifest segments have capture-clock `start`/`end`, plus `localStart`/`localEnd` within the clip and the exact `captureOffset`.
- `assets/` containing verified Field buffer assets reconstructed from the journal; no connection to Field or preexisting asset cache is needed.
- `index.html`, a self-contained local review with full-size frame links and playable speech clips. It loads no remote fonts, scripts, media, or services.

## Choosing useful moments

Drawing activity is grouped into short clusters; a pause of 1.5 seconds ends a cluster. Long activity adds a state at least every `--interval` seconds (default 15). The end of each cluster, speech starts/ends, and the final drawing state are candidate frames. Identical sequence cutoffs are merged. Silent drawing remains represented when no microphone was recorded. `--max-frames 120` limits output using even coverage across the candidate timeline; omissions are recorded in the manifest and printed as a warning. Set a larger cap for detailed inspection.

A frame's `range` names the preceding input interval; its image is the snapshot at `time`/`range.end`, through `throughSequence`. The image is **not** a claim that the canvas stayed unchanged throughout that range. `speechIDs` associates overlapping candidate clips, and captions may contain a complete overlapping ASR segment rather than word-accurate text at the instant of the frame.

The visual history advances in append/sequence order. Audio buffers are indexed separately by the time of their first sample; their late arrival cannot truncate visual replay. Visual availability time uses the prefix maximum of visual event timestamps, so a slightly regressed correction timestamp cannot reveal an earlier sequence event from the future. Late corrections take effect where they appear in history. The tool renders measured input and shape intent, leaving temporary predicted ink out. It reproduces layer visibility/solo, cancellation, non-destructive undo/redo, line/circle tools, the recorded affine navigation/crop, theme changes, and Field reference replacements with explicit projection. It does not auto-frame the geometry or brighten translucent ink for the contact sheet. This keeps the images faithful to the captured viewport.

A source file with an incomplete final write is read through its last complete line and reported, without truncating it. A malformed complete event, broken sequence, missing reference asset, or invalid binary reference fails explicitly. Captures lacking viewport information use a reported 1194×834 point fallback. Older captures without an initial appearance anchor assume the app's dark default until their first appearance event; this uncertainty is warned about and marked per frame. A missing initial theme cannot be recovered from a save file alone. A capture is one session; combine multiple sessions as separate reviews.

## Voice activity and transcription

The built-in VAD is an **adaptive energy heuristic**, not a learned speech classifier. It measures RMS in 20 ms windows, compares it to a noise estimate plus a margin, uses 4 dB hysteresis, removes short bursts, adds context, and splits long segments. Café machinery and other people's voices can be mistaken for the architect speaking; quiet speech can be missed. Candidate audio is retained so a later model can make a better decision. No diarization or source separation is implied.

Audio is downmixed and resampled on each continuous run's absolute 16 kHz grid. A stateful fourth-order Butterworth low-pass at 6720 Hz precedes downsampling. Source PCM stays untouched in the original recording. Recording IDs, format/sample-counter discontinuities, clock anchors, and gaps over 2 ms split runs. A fallback to callback clock is preserved and warned about; no guessed microphone-latency correction is applied.

Inspect `--help` for tuning. For example:

```sh
tools/offline-review/review capture.ndjson --output review \
  --vad-threshold -44 --vad-margin 12 --min-speech 0.3 --silence 0.6 \
  --pad 0.25 --max-speech 25 --interval 10 --max-frames 240
```

Transcription is optional. Supply an **existing local executable and model file**. The tool never installs a backend or downloads a model, passes the model by file path, and sets the Hugging Face / Transformers offline switches. With STT omitted, it never starts an external process. Local backend executables remain trusted software you choose; the tool does not sandbox arbitrary executables or attempt to enforce a system-wide network firewall.

OpenAI Whisper's local CLI:

```sh
tools/offline-review/review capture.ndjson --output review \
  --stt openai --stt-executable /absolute/path/to/whisper \
  --stt-model /absolute/path/to/base.pt
```

Use the multilingual `base.pt`, `small.pt`, etc. for automatic language detection, which is the default. `--language fr` or `--language en` constrains recognition. An `.en.pt` model itself only recognizes English. This backend also needs its normal local dependencies, including `ffmpeg` on `PATH`. It runs on CPU with four threads and disables cross-clip text conditioning.

An installed whisper.cpp CLI can be used instead:

```sh
tools/offline-review/review capture.ndjson --output review \
  --stt whisper.cpp --stt-executable /absolute/path/to/whisper-cli \
  --stt-model /absolute/path/to/ggml-base.bin
```

The whisper.cpp adapter reads its JSON transcription offsets and maps them onto capture time. Its flags match the `whisper-cli` `-m/-f/-oj/-of/-l` interface; this path requires that compatible installed CLI. OpenAI Whisper was exercised end-to-end locally; whisper.cpp was not installed on the development Mac.

If transcription fails, the review and audio/manifest are still written, the failure is recorded, and the tool exits with code 2. Other input/render errors exit 1. No speech candidates means no STT process is started. Silence, music, and noise can still cause hallucinated text in an ASR model; keep candidate status and original audio visible to downstream agents.

## Verification

```sh
tools/offline-review/test
```

The test executable checks image orientation/crop and navigation, corrections/prediction exclusion, circle intent, undo/solo/light blending, Field asset reconstruction, PCM resampling/anti-alias attenuation, VAD timing and gaps, early speech before drawing, frame caps, and damaged-tail preservation. It uses temporary fixtures and leaves source captures unchanged.

The full CLI was also run on both a real simulator saved capture and a real current capture containing Field references. Speech was tested separately using a clearly labeled synthetic capture with macOS `say` audio and the existing local OpenAI Whisper multilingual base model; that fixture is test speech, not an architect recording.

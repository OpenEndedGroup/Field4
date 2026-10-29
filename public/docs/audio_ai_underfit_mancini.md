# Generating with a LoRA

This example generates new music from a LoRA trained on one album, using Field Audio. The
checkpoint, a rank-16 Underfit LoRA, trained for 1,000 steps on 165 lossless 12-second chunks
from all 12 tracks on Henry Mancini's *Experiment in Terror*, is applied to Stable Audio 3's
small-music base model.

*To run the examples on this page, open the [audio_ai_underfit_mancini](agent_transcripts.md#audio_ai_underfit_mancini) starting point.*

The source is a 12-second excerpt of the Modern Jazz Quartet’s **“Skating”**, separate from the
Mancini album used for training. The request imports that excerpt and asks for a 30-second
candidate with a modest guidance value (`cfg = 1.5`). `initNoiseLevel = 0.65` sets how far the
result may move from the source; the LoRA applies at strength `0.85` over the denoising interval
`0..0.92`. The candidate board records the source, seed, model revisions, checkpoint and every
sampling control, so a take can be reproduced.

## Listen to the experiment

Listen to the source first, then compare the base model with the LoRA take. Both outputs use
seed 1962, 50 steps, `cfg = 1.5`, `cfgRescale = 0`, `initNoiseLevel = 0.65`, two seconds of
padding and chunked decoding. The only request parameter that changes is checkpoint strength:
zero for the baseline and 0.85 for the LoRA, so the difference you hear is the checkpoint's.

**Source · MJQ, “Skating” · 12 seconds**

<audio controls preload="metadata" src="assets/audio_ai_underfit_mancini/source.opus"></audio>

**Stable Audio 3 Small Music Base · no LoRA · 30 seconds**

<audio controls preload="metadata" src="assets/audio_ai_underfit_mancini/baseline.opus"></audio>

**Mancini step-1000 checkpoint · strength 0.85 · 30 seconds**

<audio controls preload="metadata" src="assets/audio_ai_underfit_mancini/candidate.opus"></audio>

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_underfit_mancini/comparison.png" capture="box" open-outputs="true" wait="4" padding="16" timeout="180" w="1120" h="1200" hidden=true embed=true name="underfit-archive-board" alt="Actual MJQ source waveform and Underfit candidate boards for the matched no-LoRA baseline and original Mancini LoRA take" macro-file="../../dev_docs/agents/transcripts/docs_capture/underfit_mancini_board.yaml" capture-box="underfit_mancini_board" capture-profile="linux-nvidia"}
// The real candidate sets and waveform envelopes are rendered by the archive capture transcript.
```

The board above shows the source waveform and both takes. In a live document you audition and
rate candidates on the board itself.

??? aside "Where these examples came from"
    The LoRA take is the successful 13 September 2026 render retained on the Linux RTX 4090
    machine, not a new replacement. Its native WAV hash is
    `572545c7809e33ad00e642d40c5b3c65ebecfa3abbf233b8924292e26d30a5ad`.
    The matched strength-zero baseline was rendered on 3 October 2026 with the same pinned
    Underfit and Stable Audio 3 revisions and request controls. The checkpoint digest is
    `3769f0e3282ea970a5c953933216f84c847478cfc51213c7adfa3f5a98762364`.
    [Candidate proof](assets/audio_ai_underfit_mancini/candidate-proof.json),
    [baseline proof](assets/audio_ai_underfit_mancini/baseline-proof.json) and the
    [export manifest](assets/audio_ai_underfit_mancini/manifest.json) preserve the original
    asset hashes, model revisions and encoding provenance. Opus copies use the real audition
    proxies without gain normalization. The captured board omits the unavailable archived
    splice preview; the source is provided separately. Refresh these exports before recapture;
    a normal HTML build reuses the genuine results and does not run a model.

## Run it

Start the isolated worker and API from the field-audio-ai checkout:

```diffstatic {data-lang=bash}
export FIELD_AUDIO_UNDERFIT_SOURCE_WAV=/path/to/your-source.wav  # at least 12 seconds
export FIELD_AUDIO_UNDERFIT_LORA_ROOT=/path/to/audio-project/underfit-loras
./scripts/run-underfit-worker.sh
./scripts/run-api.sh
```

The worker also needs the Stable Audio 3 base model, found through
`FIELD_AUDIO_UNDERFIT_MODELS_ROOT`. Then open the [Underfit Mancini example](agent_transcripts.md#audio_ai_underfit_mancini):
its first box explains the setup, and its second imports the source, sends the LoRA request and
shows the candidates on a board you can listen through.

## See also

- [Field Audio AI](field_audio_ai.md)
- [Candidate boards](audio_ai_gesture_board.md)

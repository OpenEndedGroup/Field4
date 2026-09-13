# Experiment in Terror with an Underfit LoRA

This example carries a complete album experiment through Field Audio's durable API. The training
set contains 165 lossless 12-second chunks from all 12 tracks on Henry Mancini's *Experiment in
Terror* album, preserving 1,907.449614 seconds of source material. A pinned Underfit rank-16
DoRA-rows checkpoint was trained for 1,000 steps and is applied to Stable Audio 3's small-music base
model.

The live request imports a held-out source excerpt, keeps a 12-second source window, and asks for a
30-second candidate with a modest guidance value (`cfg = 1.5`). The source distance is controlled by
`initNoiseLevel = 0.65`; the LoRA contributes at strength `0.85` through denoising interval `0..0.92`.
Field hashes the checkpoint before submission, and the worker verifies the same digest. The resulting
candidate board retains the source, seed, model revisions, checkpoint digest, and every sampling
control.

## Run it

Start the isolated worker and API from the field-audio-ai checkout:

```diffstatic {data-lang=bash}
export FIELD_AUDIO_UNDERFIT_LORA_ROOT=/path/to/audio-project/underfit-loras
./scripts/run-underfit-worker.sh
./scripts/run-api.sh
```

Then open the [Underfit Mancini transcript](agent_transcripts.md) or load
`dev_docs/agents/transcripts/audio_ai_underfit_mancini.yaml` into Field. Run the first box for the
explanation and the second for source import → checksummed LoRA request → durable candidate set →
waveform audition. The worker needs the staged Stable Audio 3 base bundle configured by
`FIELD_AUDIO_UNDERFIT_MODELS_ROOT`.

The checked-in code path is model-neutral: Field only knows `underfitCandidates`,
`UnderfitLoraCheckpoint`, and the candidate-board contract. Stable Audio 3 and Underfit remain in the
optional Python worker, so a later checkpoint or backend can use the same Field operation boundary.

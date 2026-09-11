# Two lamps in the dark — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>`).

**Launch box 1, "path tracing renderer", then box 2, "emitter sampling, on and off".**
A window opens on a grey floor, a red wall, a white sphere and two small lamps. The
lamps are the only light in the scene — the environment is black.

Box 2 then flips **one flag** every 256 samples and prints which way it is set:

```kotlin
tracer.emitterSampling = !tracer.emitterSampling
```

Off, the image is a blizzard of black and white speckle and the sphere is barely
legible. On, the same scene at the same sample count is clean, with a soft shadow
under the sphere and a cool tint on its right from the smaller lamp. Changing the flag
resets accumulation, so the two estimators are always being compared at the same
budget, over and over. Comment out the `frames { }` loop at the bottom of the box to
hold one of them.

| in the scene | why it is there |
| --- | --- |
| the **wide lamp**, upper left, 0.8 × 0.8 | two-sided: both faces radiate, and the sampler's power weighting counts that |
| the **small lamp**, upper right, 0.35 × 0.35 and four times brighter | one-sided, facing down. Its back face is culled on a BSDF hit, so it has to be dark to the direct sampler too, or the two strategies disagree about the same point |
| the **sphere's shadow** | the shadow ray. A direct-lighting shadow ray has to stop *before* the light it is aiming at; without that, every one of them would hit the lamp, report occlusion, and the picture would come out correct and no better |
| the **black environment** | there is nowhere else for the light to come from, so nothing here is being carried by the environment sampler |

Two lamps rather than one, and of different size, colour and power, because the
selection table's probability has to stay attached to the right triangle. If it came
loose the image would *tint* rather than get noisier — a failure that noise alone
cannot produce.

`build.yaml` beside this file is the `OperationTranscript` that built the document.
Replay it into a fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --port 9477 \
  --command 'macro write path_tracing_emitters_build --file dev_docs/agents/demos/path_tracing_emitters/build.yaml' \
  --command 'macro run path_tracing_emitters_build'
```

`verify.yaml` is the other transcript: it drives `PathTraceEmitterGpuSmoke` over the
same scene and prints the unbiasedness and variance measurements instead of showing a
picture. Its invocation, and what the numbers mean, are in
[`dev_docs/emitter_sampling.md`](../../../emitter_sampling.md).

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable
inline copies of the same text, and `./gradlew checkAgentDemos` fails if the two ever
disagree.

The published behaviour, with the before/after captures, is under "Sampling the lamps
directly" on [`public/docs/path_tracing.md`](../../../../public/docs/path_tracing.md).

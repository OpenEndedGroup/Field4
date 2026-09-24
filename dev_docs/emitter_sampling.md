# Emissive-geometry light sampling, and how to tell a win from a bug

`PathTracingRenderer.emitterSampling` added a second next-event arm to the GPU path
tracer: emissive triangles are now importance-sampled directly and combined with BSDF
sampling by the power heuristic, where before they were found by BSDF sampling alone.
The published behaviour is on [`public/docs/path_tracing.md`](../public/docs/path_tracing.md).
This page is the part a future change to the same code needs: the method, the numbers
with their populations, and the two traps that cost time.

Code: `src/jvmMain/kotlin/afield/four/fabricated/PathTraceScene.kt` (`Packed`'s
`emitterDistribution` and `emitterLookup`), `src/jvmMain/resources/afield/four/pathtracing/trace.comp`
(`sampleEmitter`, `emitterPdf`, and the two new arms), `PathTracingRenderer.kt`
(the property and the two scene buffers), `PathTraceTestMain.kt` (CPU contracts,
`four.pathtracing.contracts`), `PathTraceEmitterGpuSmoke.kt` (GPU measurement).

## Why three-way MIS is not needed

This is the design decision most likely to be revisited, so the argument is here.
The unbiasedness condition for multiple importance sampling is that for every
direction, the weights of all strategies that can *produce* it sum to one. There are
three strategies now — environment NEE, emitter NEE, BSDF continuation — which looks
like it needs a three-way weight. It does not, because two of the pairings are empty:

- The environment arm never produces emitter radiance. It samples a direction and then
  tests occlusion; if that direction points at an emissive triangle, the shadow ray is
  *blocked by the emitter* and the arm contributes nothing. Read the arm in
  `trace.comp`: its contribution is gated on `intersectScene(…, true).triangle<0` and
  multiplied by `environment(light)`, never by a triangle's emission.
- The emitter arm never produces environment radiance; `sampleEmitter` only ever
  returns a triangle's emission.

The observable consequence — that the two arms therefore compose exactly — is what
`PathTraceEmitterGpuSmoke.enclosureComposition` measures, at 0.00004 whole-image
agreement over 6,912 pixels.

So the strategies pair up disjointly: `{environment NEE, BSDF miss}` and
`{emitter NEE, BSDF hit on an emitter}`. Each pair's two weights are
`powerWeight(a, b)` and `powerWeight(b, a)`, which sum to one. A BSDF miss weights
against `environmentPdf` only; a BSDF hit weights against `emitterPdf` only.

The corollary is the escape hatch used four times: any emitter the *table* cannot see
gets selection probability zero, so `emitterPdf` returns zero, so the BSDF-hit arm's
weight is exactly one and the estimate is exactly the pre-change estimate. That is how
masked emitters, emissive glass, texture-only emission and zero-area triangles are
handled — by exclusion rather than by a special case in the integrator.

## The Sobol dimension budget was already full

Bounce *b* consumes dimensions `8b+2 … 8b+9`, and with `maxBounces = 12` the
100-dimension table in `sobol-100x32.bin` is exactly full. There was no room for a
new 2D sample, and growing the table would have perturbed every existing measurement
on the page (the committed noise-comparison numbers, the frosted-glass variance
floors) for a reason unrelated to this change.

Two observations made it unnecessary:

1. **A sample can serve two estimators.** The emitter arm's point-on-triangle pair is
   the *same* `sample2(domain)` the environment arm uses for its direction. Sharing a
   sample between two estimators of two different integrals biases neither — bias
   depends only on each estimator's own marginal — and each arm keeps full
   stratification.
2. **One dimension per bounce was already unread.** `sample2(domain+3u)` is drawn for
   Russian roulette, which uses `.x` only. `.y` is the emitter-selection variate.

If a future change does need more dimensions, `tools/generate_pathtrace_sobol.py`
regenerates the table — but expect to re-measure the numbers on `path_tracing.md`,
because a different sampler is a different noise floor.

## Trap 1: a shadow ray must stop before its own light

`intersectScene` had no distance limit; the environment arm never needs one, because
the environment is at infinity. An area-light shadow ray does: without a limit it
hits the light it is aiming at, reports occlusion, and discards every direct
contribution. The result is a **correct image with no improvement at all** — which no
unbiasedness check can catch, because nothing is biased. It is the variance floor that
catches it, which is why that floor is asserted at 28× and not at "greater than one".

The limit is `distance*(1.0 - 1e-3)`. A blocker inside that last 0.1% is missed, which
is a bounded and standard approximation.

## Trap 2: next-event estimation reaches one bounce further than `maxBounces`

This one cost the most time and is the most likely to be rediscovered.

The first measurement reported a **1.9% whole-image difference** on the lamp-box
scene between emitter sampling on and off, at 65,536 samples each, and it did **not**
shrink when the reference budget grew — the signature of real bias. Three candidate
causes were wrong. The actual cause:

`maxBounces` is a path-length limit. A shadow ray from the last path vertex to a light
is a scattering event that a BSDF continuation would have needed *another* bounce to
make. So **on at `maxBounces = W` transports what off transports at `W + 1`.**
Comparing equal `maxBounces` reports that extra segment as bias.

Correcting the comparison — on at *W* against off at *W+1* — moved three independent
measurements at once:

| Comparison | Equal `maxBounces` | Matched transport depth |
| --- | ---: | ---: |
| Coloured enclosure, whole image | 0.00813 | 0.00004 |
| Lamp box, whole image, 65,536 samples | 0.01946 | 0.00070 |
| Lamp box, per-pixel median, 65,536 samples | 0.03082 | 0.02446 |

Three measurements agreeing on one explanation is the reason to believe it. The
published page states the asymmetry as user-facing behaviour, because a user doing a
strict flag A/B will see it.

## Method: choosing the scenes, not just the tolerance

The instinct is to test unbiasedness on the scene where the feature helps most. That
scene is the worst possible instrument. A small bright lamp in the dark is exactly the
case where BSDF sampling barely works, so its own converged reference is still noisy:
at 65,536 samples the off reference's per-pixel standard error is **0.00849 absolute**,
about 3% relative. A 2% bias is invisible in that, and a tolerance written to pass is a
tolerance on noise.

So the sharp checks are elsewhere, and they are sharp for structural reasons:

**1. Absolute, cross-mechanism.** A sphere inside a uniformly emissive cube at one
bounce presents the *identical incident radiance field* as the same sphere under a
constant unit environment map, which the tracer solves through a completely separate
next-event path that was already checked. At one bounce neither run applies an MIS
weight, so the only thing under test is the new pdf: its normalisation and its
`dist² / |cos|` Jacobian. Both sides converge fast. The camera has to sit close enough
that every pixel, corners included, lands on the sphere — `evaluate` gives every
dielectric an f0 of 0.04, so a directly visible emissive face carries its own emission
*plus* a few percent of its neighbours', and a constant environment map has no
counterpart for that.

**2. Composition.** The same enclosure, six differently coloured faces, two bounces
against three. Now both MIS weights are load-bearing and a double count shows up. The
differing colours are what test the selection table: if a probability came off its
triangle, the answer tints rather than getting noisier.

**3. Variance, and the noise/bias separation.** The lamp box carries the variance
claim, and its bias residual is reported at *two* reference budgets. Noise decays like
one over the root of the budget; bias does not. Measured 5.49× shrinkage for a 4×
budget ratio — so the residual is the reference's own noise, and the check asserts the
decay rather than a fixed tolerance.

### Measurements, M1 Max, compute-BVH backend, 2026-09-11

| Check | Population | Whole image | Worst of 24 tiles | Per-pixel p95 |
| --- | --- | ---: | ---: | ---: |
| Enclosure against environment, 1 bounce, 16,384 samples | 6,912 px of 96 × 72 | 0.00001 | 0.00011 | 0.00206 |
| Coloured enclosure, on/off, 2 v 3 bounces, 16,384 samples | 6,912 px of 96 × 72 | 0.00004 | 0.00054 | 0.00360 |
| Lamp box, on/off, 3 v 4 bounces, 65,536 samples | 6,746 lit px of 96 × 72 | 0.00070 | 0.01071 | 0.13316 |
| Lamp box, same, 4,096 samples | 6,746 lit px | 0.00014 | 0.04639 | 0.74506 |

White-furnace mean under unit radiance from every direction: **0.99811** through
emissive geometry, **0.99810** through the environment map. (The pre-existing
environment-only white-furnace check measures 0.9985 on a flat plane; the shortfall is
the single-scattering GGX model, not the sampler.)

Variance, equal budget, lamp box, 6,746 lit pixels of 96 × 72, three bounces, RMSE
against a 65,536-sample emitter-sampled reference:

| Emitter sampling | 257 samples per pixel | Ratio |
| --- | ---: | ---: |
| off | 0.13534 | |
| on | 0.00389 | 34.81× |

**The ratio's denominator is a property of the instrument, not of the estimator.** It
is set by the lamp areas (0.64 and 0.12 square units), the black environment, three
bounces, 96 × 72, and the lit-pixel population. A larger lamp or any environment light
moves it down. Quote the scene with the ratio or the ratio means nothing.

The "lit pixel" population is luminance between 0.004 and 6 with alpha 1: it excludes
the black background, which carries no signal, and the directly visible lamps, which
are exact in both configurations and would dilute the comparison.

## Two harness facts

**Equal sample budgets have to be made, not assumed.** `awaitSamples` polls, and the
render thread can fit one more dispatch in between the poll and `paused = true`. A
sixteen-sample overshoot at 256 samples is a one-percent difference that looks exactly
like a shading change — it is what first broke the bit-for-bit inert check.
`PathTraceEmitterGpuSmoke.matched` runs the second configuration against whatever
count the first actually reached and retries until they agree.

**`PathTraceGpuSmoke.awaitSamples` has a 60-second deadline**, which a 65,536-sample
reference does not fit into on a machine that is doing anything else. Two runs died
there having reached 63,168 and 10,496 samples. That reads as a hang and is only load;
the emitter smoke carries its own 900-second deadline.

**The managed server's stdout log loses its last block.** A `println` from inside a
`start { }` coroutine goes to the server's stdout, not to the box's output buffer, so
`.field-backdoor/logs/*.managed-server.log` is where the smoke's numbers are. But the
box output event that `wait-output` matches arrives over the websocket *immediately*,
and the backdoor then stops the server — so the tail of the block-buffered stdout is
never flushed. The full regression sweep read 33 PASS lines with the final one missing,
which looks exactly like a test that silently did not run. Add
`--command 'sleep 8'` after the `wait-output` and it reads 34.

## Running it

CPU contracts, through the canonical runner:

```bash
uv run --project . python tools/run_field_tests.py --id four.pathtracing.contracts
```

GPU measurement and captures, headless, from a fresh scratch workspace on a dedicated
port (this is the exact invocation the numbers above came from):

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 19411 --scratch --graphics-context --timeout 3000 \
  --command 'macro write emitter_verify --file dev_docs/agents/demos/path_tracing_emitters/verify.yaml' \
  --command 'macro run emitter_verify' \
  --command 'wait-output pt_emitter_check --contains "EMITTER CHECK DONE" --timeout 2700'
```

`println` from a box goes to the box's output, not the server's stdout, so the numbers
are in `.field-backdoor/logs/*.managed-server.log` rather than on the terminal.
The original compute-only run took about four minutes on an M1 Max. The suite now runs
`COMPUTE_BVH` and, when enabled, `RAY_QUERY`, writing captures in
`build/emitter-check/<backend>/`. Every statistical assertion runs unchanged on each backend.

For a standalone run with a durable audit verdict:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9730 --timeout 3900 \
  --command 'run-jvm-main afield.four.fabricated.RayTracingGpuAudit --arg=--stage=emitters --timeout 3800'
```

That route writes `build/raytracing-audit/gpu/emitters/<backend>/` and `report.json`.
[NVIDIA audit results](RAY_TRACING_AUDIT.md) distinguish the new measurements from the historical
M1 numbers above.

The loadable demo beside that transcript is
[`dev_docs/agents/demos/path_tracing_emitters/`](agents/demos/path_tracing_emitters/README.md).

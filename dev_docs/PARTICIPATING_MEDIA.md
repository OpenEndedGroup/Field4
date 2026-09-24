# A participating medium in the GPU path tracer

What `mediumDensity` does, how it is verified and against what, and the five things that
went wrong on the way. The user-facing half is under "A participating medium" in
[`public/docs/path_tracing.md`](../public/docs/path_tracing.md); the loadable demo is
[`dev_docs/agents/demos/path_tracing_medium/`](agents/demos/path_tracing_medium/README.md).

## What was there before, and what is new

Before this, the path tracer had two things that look like media and are not.

`fogDensity` / `fogColor` / `fogStartDistance` / `fogBackground` feed `depthFog`
(`trace.comp`), which blends a **finished** per-sample radiance toward `fogColor` using
`primaryDepth` — the primary hit distance and nothing else. There is no distance
sampling, no phase function, no in-scattering, and no attenuation of light on its way
*to* a surface. It is a screen effect with a depth term.

There is also a real Beer–Lambert absorption inside solid glass: `sigma` is set from a
material's `absorption` on entering a refractive solid and `throughput *= exp(-sigma*t)`
applies it. Absorption only — no albedo, no phase function, no distance sampling, and it
never touches a light connection. It is not a medium, but it *is* an independently
written exponential transmittance, which makes it a useful sanity reference.

New: one homogeneous medium with a scalar extinction, an RGB single-scattering albedo, a
Henyey–Greenstein phase function, and an optional axis-aligned bound. Distance is sampled
against extinction on every ray segment before the integrator knows whether this bounce
is a surface or a volume interaction; both light-sampling arms fire at a collision; and
all four pre-existing **surface** light connections now carry `mediumTransmittance` over
their own length, which is what makes the emitter sampling that landed the night before
correct through haze rather than merely present.

## Three decisions that are not obvious

**Bounds are not optional.** An infinite medium has zero transmittance to infinity, so an
environment map becomes unreachable: every connection to the sky returns nothing and
every ray is certain to collide before it escapes. `mediumDensity = 0.1` on an
environment-lit scene renders black. `mediumBounds` defaults to `null` (= infinite) only
because `mediumDensity` defaults to zero.

**MIS takes the phase pdf and the light pdf, and nothing else.** Two strategies estimate
the same solid-angle integral at a collision:

    I = ∫ p_phase(ω) · L_surf(ω) · T(ω) dω

Phase sampling draws ω with density `p_phase` and estimates `T` with the binary
no-collision event, whose expectation is `T`. Light sampling draws ω with density
`p_light` and evaluates `T` analytically. So `T` is a factor of the **integrand in both
arms**, not part of either pdf, and the power heuristic sees `(p_phase, p_light)` only.
The distance-sampling pdf cancels exactly against the transmittance it estimates — weight
`σs/σt = albedo` at a collision, weight `1` at an escape — and contributes nothing to the
weight. This is the analytic-transmittance case and would not hold under ratio tracking,
which is out of scope. Putting `T` into the phase arm's pdf is the single likeliest error
in a change of this shape, which is why the derivation is written next to the code.

**No new Sobol dimension, and the remap is exact.** `sobol-100x32.bin` has 100
dimensions; the existing four-per-bounce layout already reaches dimension 97 at twelve
bounces, with the lens pair at 98/99. There is no room for a fifth decision per bounce.
So the distance sample shares the BSDF-lobe selector's dimension, and the escape branch
remaps it:

    u' = (u - (1 - T)) / T        where T = exp(-σt · span)

Conditional on no collision, `u` is uniform on `[1-T, 1)`, so `u'` is *exactly* uniform on
`[0, 1)` — not approximately. It also means extinction zero returns `u` untouched, so a
medium-disabled render is bit-for-bit what the integrator produced before media existed,
which is assertable and is the first thing the harness asserts.

## How to run the verification

`RayTracingGpuAudit` can run the complete medium suite through the standard JVM launcher:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9730 --timeout 3900 \
  --command 'run-jvm-main afield.four.fabricated.RayTracingGpuAudit --arg=--stage=medium --timeout 3800'
```

It writes `build/raytracing-audit/gpu/medium/<backend>/` and an audit verdict. Add
`--arg=--require-ray-query` when the hardware backend must run. The default catalog audit covers
the shorter core, camera, temporal and ray-pipeline checks; `--all` adds the longer statistical
emitter and medium checks plus benchmarks. [NVIDIA verification](RAY_TRACING_AUDIT.md) records the
current results. The original live-document transcript remains available:


```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --port 19541 --scratch --graphics-context --timeout 7200 \
  --command 'macro write medium_verify --file dev_docs/agents/demos/path_tracing_medium/verify.yaml' \
  --command 'macro run medium_verify' \
  --command 'wait-output pt_medium_check --contains "MEDIUM CHECK DONE" --timeout 6900'
```

About thirty minutes per backend on an M1 Max.

**Every arm runs on every backend the device exposes.** `COMPUTE_BVH` always; `RAY_QUERY` as
well wherever `device.supportsRayQuery`, which is the condition `PathTraceGpuSmoke` already
uses for the base tracer. Artifacts go to `build/medium-check/<backend>/` — one
subdirectory per pass, because these figures are per-backend renders and a figure that does
not say which backend produced it is evidence about neither. The cumulative `report.txt` is
written at `build/medium-check/` as well as inside each pass, so there is still one file
with the whole run in it, and its first line names the backends that ran.

This is worth doing rather than assuming precisely *because* the medium touches no
traversal code: the analytic anchors — `exp(-σt · d)` and the furnace's absolute 1.0 — are
backend-independent, so a backend that misses them while the other does not has a
traversal problem, not an estimator problem, and the arm that fails localises it.

**On a device with no ray queries this is one pass and behaves exactly as it did before**,
and the report says so in its first line rather than leaving you to infer it.

**Read `build/medium-check/report.txt`, not the server log.** A box's `println` reaches
the server log through a buffer that a `--scratch` teardown does not wait for: the first
full run of this harness lost the whole lamp section's numbers that way, and left only the
one line that travels over the backdoor protocol — the exception message. Every number is
therefore also written to a file, and flushed after *every arm* rather than at the end,
because the arm that fails is the arm whose numbers matter.

## The measurements, with their populations

All at 96×72 = **6912 pixels**, pixel jitter off, on an M1 Max — and therefore all on
`COMPUTE_BVH`, because `supportsRayQuery` is false on this device. The harness runs every
arm on every backend the device exposes, so these are the numbers from the only pass this
machine can make. **No number below has been measured on `RAY_QUERY`, and nothing here
should be read as a claim about it** until the suite runs on a device with ray queries
enabled, where it will produce a second, separately labelled set.

| arm | what it pins | measured |
| --- | --- | --- |
| extinction zero is inert | the shared Sobol dimension and its remap | bit-for-bit identical over 6912 px at 97 samples, with albedo, anisotropy and bounds all set to conspicuous values |
| `depthFog` against the closed form | that the harness's reconstructed ray *is* the shader's ray | worst per-channel **0.00000** (< 1e-7) over 6912 px, at σ = 0.25 and 0.90 |
| absorption against `exp(-σt·d)` | transmittance, absolutely | whole-image **< 5e-6**, worst of 24 tiles **0.00001**, per-pixel p95 **0.00024**, at 65536 samples over 6912 px, in three configurations (σ 0.25 unbounded, σ 0.90 unbounded, σ 0.60 through a bounded slab) |
| white furnace | energy conservation, and that `phase` and `samplePhase` are the same distribution | mean within **0.00001** of an analytic **1.0** at each of g = -0.6, -0.3, 0, 0.3, 0.6, 0.85; per-pixel p95 ≤ 0.00154; 16384 samples over 6912 px |
| bounce sweep | that the remaining furnace deficit is truncation | 1 → **0.46337**, 2 → 0.64054, 4 → 0.85178, 8 → 0.97776, 12 → **0.99680**, monotone, never above 1.0, in a 2.4-optical-depth box |
| single scattering against a CPU quadrature of the transport equation | the distance pdf, albedo weight, phase normalisation and connection transmittance, simultaneously and absolutely | whole-image **< 5e-6**, worst tile **0.00001**; mean **0.52483** against the quadrature's **0.52483** (σ 0.8, albedo 1) and **0.17512** against **0.17512** (σ 1.6, albedo 0.5), 65536 samples over 6912 px |
| the emitter connection | an error in the connection from a collision to emissive geometry | bounded at **0.00011** whole-image against an enclosure within 0.00035 of a pure emitter, 16384 samples over 6912 px |
| lamp in fog, two budgets | that the residual is the reference's noise | see below |
| lamp in fog, variance | that light connections through a medium are worth their cost | see below |
| god rays | that the medium puts light where there is no surface, which a depth curve cannot | over the **100921** pixels of a 640×400 frame whose primary ray hits nothing: the medium puts mean **0.96** radiance there with a spatial standard deviation of **2.02**; `depthFog` at the same density puts a *constant* 0.34 (deviation 1.5e-6, which is float arithmetic); with neither, exactly zero |

The **absorption** numbers are two orders tighter than a Bernoulli standard error
predicts, and the reason is worth knowing: transmittance on a camera ray is estimated by
a *binary* event driven by one Owen-scrambled Sobol dimension, and a stratified sequence
integrates a one-dimensional step function far better than random sampling does. The
estimator is noisy per path and nearly exact per pixel.

The **quadrature** reference was itself checked before the GPU ran: `E₂(τ) = e^-τ - τ·E₁(τ)`
against a direct 200001-point numerical integral of `∫₀¹ exp(-τ/μ) dμ` (agreement 2e-14 to
7e-11 over τ from 1e-4 to 5), the 512-interval Simpson rule against an 8192-interval one
(3e-6 relative), and the whole single-scattering formula against a from-scratch
two-million-sample Monte Carlo of the same physics, written in Python from the transport
equation rather than from the shader (agreement within the Monte Carlo's own 3e-4 noise).
A floor much below 1e-4 on this arm would be measuring the reference.

The **lamp in fog** is one 0.16-square-unit lamp at radiance 90 in a black environment,
behind a 0.22-per-unit medium at albedo ~0.9 and g 0.45, six bounces, over the **6907 lit
pixels** of 96×72 (everything but the five pixels of directly visible lamp). Its two
numbers:

- **Bias, at two reference budgets.** The RMS difference between the two references is
  **0.02611** at 4096 samples and **0.00603** at 65536: a **4.33x** decay, *observed*. The
  budget grew **16x**, and a residual that is the reference's own noise decays as the root
  of that, so the *predicted* decay is **4.00x**. Those are two different numbers and the
  harness prints them on one line with both words in capitals, because a previous branch in
  this loop published a 16x budget growth as a 4x one and called `sqrt(16)` the ratio.
  Closing it a second way: the off arm's own standard error at 65536 samples, extrapolated
  from its measured error at 256, predicts **0.00645**, and the references differ by
  **0.00603** — a ratio of **0.93**. The whole-image *relative* residual is **0.00003**.
- **Variance.** RMSE against the converged reference at a matched **256** samples per
  pixel: **0.10327** with phase and BSDF sampling alone, **0.02091** with the light
  connections on. Ratio **4.94**, floor asserted at **4.0**. That is far below the **35x**
  `PathTraceEmitterGpuSmoke` reports for surfaces, and not because the medium's connections
  work less well: a scattering medium spreads one small lamp's light over the whole frame,
  which gives the arm *without* connections far more chance of finding it than the same
  lamp in clear air would. Quoting the surface figure here would be quoting a different
  measurement.

One methodological note that cost a wrong number before it was caught: the lamp arm
compares **on at six bounces against off at seven**. `maxBounces` limits path *length* and
next-event estimation reaches one segment past it, so equal budgets are not an equal
comparison — at equal bounces the whole-image difference is **0.02564** against the matched
pair's **0.00003**, and the harness asserts that matching reduces it.

## The five things that went wrong

**1. The enclosure premise was subtly false, and the sign is what showed it.** The plan
was: a uniformly emissive enclosure is a radiance field in which an albedo-one medium is
exactly invisible, so the same scene with the medium off is the reference. The first full
run measured a **+0.00108** residual that did not shrink when the budget grew eightfold —
the signature of bias. The first guess was bounce truncation, and the `check` written for
that guess asserted the residual was *negative*, because truncation can only lose energy.
It was positive, and the assertion failed, which is the only reason the wrong explanation
did not ship.

The real cause: a medium is invisible in a field uniform in **direction** as well as in
position. An enclosure of emissive *dielectrics* is not one — `evaluate` gives every
dielectric an f0 of 0.04, so each face returns a few percent of what it is lit by, and
that reflected part is not Lambertian. The field has angular structure and a scattering
medium averages over direction. Five variants settled it:

| variant | enclosure equilibrium | signed residual |
| --- | --- | --- |
| emitter NEE on, g 0.35, 12 bounces, bounds inside the enclosure | 1.03954 | +0.00108 |
| **emitter NEE off in both arms** (the mechanism under test removed) | 1.03954 | **+0.00109** |
| emitter NEE on, g 0 (more angular averaging) | 1.03954 | +0.00127 |
| emitter NEE on, six bounces | 1.03954 | +0.00090 |
| bounds enlarged to enclose the walls | 1.03954 | +0.00223 |
| **black-metal faces, f0 exactly zero** | **1.00035** | **+0.00011** |
| rough dielectric faces | 1.01393 | +0.00025 |

Removing the mechanism under test does not remove the residual; removing the faces'
reflectance does. So the arm now runs on black-metal emissive faces — metallic one with a
black base puts f0 at exactly zero, the least reflective surface this BSDF can express,
and its enclosure settles within 0.035% of a pure emitter — and keeps the dielectric and
NEE-off variants as live assertions rather than as a comment. What the arm claims is a
**bound**, 0.00011 whole-image, not an identity; every *component* of the connection is
pinned harder by another arm.

**2. Equal `maxBounces` is not an equal comparison, and a medium makes it worse.** Last
night's emitter smoke already knew this: `maxBounces` limits path *length*, and next-event
estimation reaches one segment past it, so "on at N" transports what "off at N+1" does.
In the lamp-in-fog scene at equal bounces the difference was **2.9% whole-image**, it did
not shrink at all across a sixteenfold budget growth, and its sign was **positive** — more
transport, not double counting. A medium amplifies this because every collision spends a
bounce, so the last vertex arrives sooner and its extra segment is worth more. The arm now
compares on at six against off at seven and reports the equal-bounces figure separately as
a measurement.

**3. An unsigned residual cannot tell "more transport" from "double counting."** Both of
the above were diagnosed by the sign and would have been misdiagnosed without it, so
`PathTraceMediumGpuSmoke.kt:338#Agreement` now carries a signed whole-image difference
alongside the absolute one. Two live assertions read it: `check(glossy.signed >
metal.signed)`, that the enclosure residual grows with the faces' reflectance, and
`check(abs(inert.signed - glossy.signed) < 0.35 * abs(glossy.signed))`, that switching the
mechanism under test off does not change it. Neither statement is expressible without a
sign, and the wrong explanation was refuted by exactly this field.

**4. The obvious statistic for "the medium casts shadows" measured the opposite.** Lateral
variation along a row of hazed pixels came out **2.10** for the clear render, **1.94** for
`fogDensity` and **1.09** for the medium — the intended ranking exactly inverted, and
correctly so: hard-edged pools of light against black air have more contrast than haze
does, and the other two renders still carry the slats' shadows on the wall and the floor.
Shadows on *surfaces* were never the thing at issue. The statistic that works is light
where there is **no surface at all**, and it is rigorous rather than empirical: a
background miss has no depth, so `depthFog` maps every such pixel to exactly `fogColor`
and its spread over that population is zero by construction.

**5. A zero reference pixel makes a percentile infinite, silently.** A low-budget
reference had a handful of pixels that had never received a sample, and the printed
per-pixel p95 came out `Infinity` without any other complaint. Those pixels are now
skipped and counted, so the population a percentile covers is the population it reports,
and the lamp arm's own population requires all four of its renders to be positive.

And three smaller traps, for the next person:

- **`--document <path>` alone loads nothing, and says so only as `boxes: []`.** Opening the
  demo with `field_backdoor.py --document dev_docs/agents/demos/path_tracing_medium`
  returns `ok: true` with an empty box list, because the backdoor prefixes its automation
  workspace root onto the name — the returned `sessionKey` shows it:
  `.../build/field-automation-workspace//Users/.../path_tracing_medium`. The shipped
  emitter demo behaves identically, which is the control worth running before believing
  the document is broken. What works is naming the workspace and the document separately:

  ```bash
  uv run --project . --group backdoor python tools/field_backdoor.py --json --port 19541 \
    --workspace "$PWD/dev_docs/agents/demos" --document path_tracing_medium --read-only \
    --graphics-context \
    --command 'launch pt_renderer --property code' \
    --command 'wait-native-renderer --timeout 180' \
    --command 'launch pt_medium --property code' \
    --command 'wait-output pt_medium --contains "mediumDensity = 0.16" --timeout 600' \
    --command 'sleep 40' --command 'native-screenshot build/demo-proof.png'
  ```

  That is also how the demo was verified rather than assumed: both boxes launch, the
  window comes up, the sweep loop reaches its third density, and the screenshot has shafts
  in it. `sleep` takes a bare positional argument, not `--seconds`.


- `glslangValidator -S comp <file>` writes **`comp.spv` into the current directory**. Run
  it from a scratch directory or pass `-o`, or it lands in the working tree and
  `checkTestTreeWrites` has something to say about it.
- The furnace and quadrature scenes want no geometry at all, which would upload zero-byte
  device buffers. They carry one black-metal triangle 100000 units away and a hundredth of
  a unit across — about 1e-14 steradians — because a black metal's f0 is zero where a
  dielectric's is 0.04, and this is the one surface those scenes would rather not have.

## What is deliberately not here

One homogeneous medium, achromatic extinction, optionally bounded by one axis-aligned box.
No heterogeneous voxel grids, no spectral extinction, no ratio tracking over varying
density, no nested or per-object media. A collision spends one of `maxBounces`, so density
and bounce budget are coupled; the bounce sweep above is how large that coupling is.
`fogDensity` is unchanged and still works, and enabling both attenuates whatever the
camera sees first twice over.

**And the gap that is a matter of hardware rather than scope: `RAY_QUERY` has never
rendered a medium pixel.** The shader's ray-query variant compiles
(`glslangValidator --target-env vulkan1.3`) and the medium code touches no traversal, so
there is a good argument that it works — but an argument is not a measurement, and this is
the largest open item on this page. The harness is now parameterised over backends
specifically so that running it once on a device with ray queries closes it with numbers
instead of reasoning. A medium and `temporalReuse` are also effectively exclusive: a
bounce-0 collision leaves the history guide's `normal.w` unset, so reprojection rejects
that pixel — traced in the code, not measured.

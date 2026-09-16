# Differentiable StackSL

Gradient descent on a StackSL program's own numeric literals: reverse-mode automatic
differentiation through the **emitted GLSL**, an image loss over the wire vector, and a re-emission
step that writes the fitted numbers back into the source so the output of a fit is a program.

Landed 2026-09-16 on `nightly/2026-09-16/1-stacksl-differentiable-fit`, based on `cb4377a6c`.

## Why this works here and was correctly abandoned for RectSL

The backlog's "differentiable image fit" died on a real objection: *a per-track scalar lowering
cannot produce `d(pixel)/d(parameter)`; that needs a differentiable rasterizer whose coverage
approximation has a characterisable bias.* That objection is about **geometry**. RectSL draws
shapes, so a pixel depends on a parameter through *coverage*, and a coverage approximation is a
modelling choice you then have to defend — `RectSLSoftRaster` is that choice.

StackSL is not geometry. It is a **per-pixel field**: `StackSLCpu.renderFragment` runs `main()`
once per pixel with `fragUV` as its only spatial input, and the output is an ordinary composition
of arithmetic and GLSL builtins. So `d(pixel)/d(wire)` is the chain rule. There is no rasteriser in
the pipeline, no coverage term, no approximation and therefore no bias to characterise. The
sentence that kills the idea for RectSL is what makes it exact for StackSL.

## The mechanism, and what it cost

One interpreter, generic in its scalar. `GlslScalars<V>` (`GlslScalars.kt`) is about twenty
operations at **whole-value** granularity — whole values because `normalize`, `dot`, `length` and
the matrix products are not component-wise, and a component-level seam would have forced them to be
restated per scalar. `GlslMachine<V>` (`GlslInterpreter.kt`) holds the statements, the branches, the
loops, the frames and the lvalue writes: exactly one copy of the control flow, shared by every
scalar.

- `GlslFloatScalars` is the binary32 reference. Every body in it is the pre-refactor expression
  **verbatim**, moved rather than rewritten, so nothing is widened, reordered or fused.
- `GlslAdScalars` (`GlslAd.kt`) is reverse mode on `afield.stdlib`'s `AD` tape — reused, not
  rewritten. One tape per pixel: the loss is a sum over pixels, so the gradient is a sum of
  per-pixel backward sweeps and no tape outlives the pixel that built it.

`V` is a reference type in both instances, so the `Float` path pays **no boxing**; one interface
dispatch per operation is the entire cost. `GlslInvocation` remains a public non-generic face on
`GlslMachine<GlslValue>`, so no pre-existing caller or public signature moved.

### The primal is bit-identical to the `Float` path, deliberately

`AD` computes in `Double`, and a `Double` shader is the *wrong* shader: `GlslValue`'s file KDoc
argues that widening an intermediate makes the CPU result more accurate than the GPU's and
therefore a worse reference, and that `fract` of a large product amplifies a last-bit difference to
O(1). A `Double` primal would not be slightly different on a hash-bearing program, it would be a
different image.

So every node `GlslAdScalars` pushes stores its value **rounded to `Float`**, at exactly the points
the `Float` path rounds, with the local partials of the *unrounded* operation. Double rounding is
safe for `+ - * /` and `sqrt` because `Double` has more than twice `Float`'s significand. Measured:
**9 programs, 20736 floats, 0 differing bits** between `StackSLImageLoss.renderPrimal` and
`StackSLImageLoss.render`.

## The wire bridge, which the brief assumed away

`StackSLCompileOptions.externalizeConstants` already lifts numeric literals into an emitted
`float <prefix>_wires[N]` array with an initialiser and records each as a `StackSLDanglingWire`
carrying `index`, `initial`, `line`, `column`, `token` and `note`. That part needed no new syntax.

But **the externalised emitted GLSL cannot be parsed by this backend at all**
(reproducer: `StackSLWireLoweringTest.kt#fun theUnloweredWiredGlslIsRefusedByTheParser`), and no CPU
path had ever tried:

- the wire array first appears in a **parameter list**
  (`void <prefix>_init_wires(out float <prefix>_wires[<prefix>_wire_count])`), and the parameter
  parser has no array branch at all, so it dies as an ordinary syntax error:
  `GlslEvalError: GLSL parse: expected ')', found '['`. That is *not* the message the two
  `GlslUnsupported` sites suggest — `GlslParser.kt:442`'s
  `"GLSL arrays are not in the StackSL subset"` is what a **local** array declaration gets, which is
  what the forwarding wrapper's body hits once the signature is gone. Both refusals are real and
  they fire at different points;
- `StackSLCpu.stackAutoOptions` pins `externalizeConstants = false`;
- every emitted function gains a trailing `, float <prefix>_wires[<prefix>_wire_count]` parameter;
- the entrypoint gains a **second, overloaded** one-argument wrapper that declares the array, calls
  the initialiser and forwards — and `GlslShader.functions` is keyed by name, so the wrapper simply
  replaces the real function.

`StackSLWires.lower` bridges that with one source-to-source rewrite of the emitted artifact:
`<prefix>_wires[i]` becomes a scalar global `<prefix>_wire_i` (a shape the subset supports, and one
a caller can `setGlobal` per evaluation with no recompile); the wire parameter and argument vanish
from every signature and call; and the count, the initialiser and the wrapper are dropped.

**Only some wires can be written back into the source.** A wire noted `"number"` stands for a
literal a human typed, so a fitted value can be substituted at the recorded `line`/`column`. A wire
noted `"optional default palette a"` is a default the *emitter* supplied for a word given no
operand — there is nothing in the source to rewrite, and inventing one would mean pushing four
`vec4`s in front of `palette`. So `StackSLWires.rewrite` fits and rewrites literal-backed wires
only. **A program's wire count is an upper bound on its fittable parameter count, not the count.**

## Measurements, with populations and floors

Every number below is asserted, not printed. Run:

```
./gradlew jvmTest --tests 'afield.stacksl.cpu.*' --no-daemon -Pkotlin.daemon.jvmargs=-Xmx4g
./gradlew jvmMeasurements --tests 'afield.stacksl.cpu.StackSLCpuThroughputMeasurements' --no-daemon -Pkotlin.daemon.jvmargs=-Xmx4g
```

### The `Float` path did not move

`StackSLCpuFloatPathManifestTest` hashes `CpuRaster.rgba`'s **bit pattern** for all **31** gallery
programs at 64×64 against a manifest captured at `cb4377a6c`, before any change. The gallery test
next door measures against the GPU in 8-bit levels, which is the right unit for that question and
the wrong one for a refactor: an intermediate can move by one ulp, leave all 31 quantised images
identical, and still have changed what `GlslValue` promises to be.

Red-first checked: with `GlslMath.mix` widened to compute in `Double` and narrow once — the
smallest realistic refactor accident, and invisible in 8-bit levels — **19 of 31** programs move.

### The wire lowering is the same shader

`StackSLWireLoweringTest` compiles every non-feedback gallery program **both ways** and compares the
rasters bit for bit: **29 programs, 282 wires lifted, 0 mismatched, 0 refused**, at 32×32.

Red-first checked twice, and the two failures differ usefully. Without the `wires[i]` substitution,
**28 of 29** die on `undefined variable 'program_wires'` — the array *parses*, the name is gone,
which is why a second test also asserts the name is absent rather than trusting the render. With the
forwarding wrapper left in, the same 28 die on `GLSL arrays are not in the StackSL subset`. The 29th
is `uv-coordinates` (`uv saturate`), which lifts no wires at all.

### The gradient is right

`StackSLGradientTest`, population **9 programs, 46 literal-backed wires**, 48×48, central
differences at `h = 1e-3 · max(1, |v|)`:

| bucket | wires | worst relative disagreement | floor |
| --- | --- | --- | --- |
| programs that do not clamp | 22 | **1.569e-05** | 5e-5, two-sided |
| programs that do clamp | 15 | **9.392e-03** | 2e-2, and asserted *worse* than the smooth floor |
| exactly zero gradient | 9 | no oracle exists; excluded and counted | 8..11 |
| hash-amplified | 0 | — | asserted 0 |

The kinked/smooth split is **measured from the image** — more than 0.1% of RGB samples pinned
exactly to 0 or 1 in an unclamped render — not grepped from word names, so it cannot go stale.

Counterfactual: the exact gradient scaled by 1.5 with one component's sign flipped reaches
**1.475e-01** and fails both floors. A test that cannot fail is not a guard.

**Two predictions were wrong and both are worth keeping.** First, the gallery template's own
`clamp(c, 0.0, 1.0)` was expected to be the source of the imprecision; removing it changed the worst
case by **nothing to four digits**, because the worst program ends in `saturate` and had already
clamped itself. The clamp that matters is whichever one is *active*.

Second, and more interesting: `fbm`'s lattice runs through `_hash21`, and the chain rule through a
`fract(<large> · …)` is enormous, so a noise frequency was predicted to be hash-amplified past any
oracle. Measured: **0 of 46 wires** are hash-amplified, and `uv 3 * fbm`'s frequency wire agrees
with a central difference to **1.5e-5**. The reason is `floor`: the hash is evaluated at the lattice
**cell index**, which carries exactly zero derivative, so no gradient flows through the hash at all
— only through `fract`'s interpolation weight, which is smooth. **`floor` is what makes a noise
frequency differentiable**, which is the opposite of what it does to a `threshold`.

### How much of the language is differentiable

`StackSLDifferentiabilityTest`, two populations, both discovered from the compiler's own word list
rather than sampled.

Of the GLSL builtins the emitter actually produces — found by compiling all **325** documentable
words and scanning the output — **39** appear, and probing each one *through* `GlslAdScalars`
partitions them:

| verdict | count | members |
| --- | --- | --- |
| true derivative | 25 | `abs acos asin atan clamp cos cross distance dot exp exp2 fract inversesqrt length log max min mix mod normalize pow sin smoothstep sqrt tan` |
| zero by convention | 11 | `ceil equal floor greaterThan greaterThanEqual lessThan lessThanEqual notEqual round sign step` |
| unsupported | 3 | `texture textureGather textureSize` |

The probe is a list of argument **shapes**, not a table of expected verdicts, so it cannot encode
its own conclusion. The sampler family is *refused* rather than answered because the feedback
programs it serves are excluded from every asserted comparison in this backend, so a derivative
through it would have no oracle.

At word level, `input 1.0 * <word>` puts one wire upstream of each word: of 325 documentable words,
**295** accept that shape, and of those **154 pass a derivative, 74 block it, 67 refuse**. Read the
blocked list before the ratio — most of those words do not *lack* a derivative, they **discard the
value**: `uv`, `p`, `time`, `pi`, `tau`, `e`, `input`, `drop`, `swap` and the twelve named colours
replace what was on the stack. The genuinely non-differentiable ones are the shapers and the
predicates: `floor`, `ceil`, `round`, `step`, `threshold`, `posterize`, `hash`, `bit-crush`,
`select`, `if` and the comparisons.

### The fit, and the round trip

`StackSLFitFigureTest`. The recovery problem has a known answer on purpose: the target is the *same*
program with twelve different palette literals. 1 program, **17 wires, 17 literal-backed**, 64×64,
80 Adam steps at `lr = 0.05`, **8.6 s** wall clock:

- loss **1.847e-01 → 1.635e-04**, a **1129×** fall (floor 500×);
- fitted vs target **0.00673** mean absolute error per RGB sample over **12288** samples, p99
  **0.05415**, max **0.12357** (floors 0.02 and 0.08, the mean floor two-sided);
- the **round trip is exactly zero** at 64×64: the fitted literals written back at their own token
  positions, recompiled from scratch through the ordinary non-wired path, render bit-identically to
  the fitted wire vector. At 48×48 in the demo's third box it measured **5.96e-08**, one ulp of a
  `Float` near 1 — `%.8f` is eight significant decimals and binary32 needs **nine** to round-trip in
  general, which is why the floor is 1e-4 rather than 0;
- the four alpha wires correctly did not move: the `w` of a `palette` operand never reaches RGB, so
  its gradient is exactly zero.

The figure is `public/docs/assets/stacksl_fit/stacksl_fit_recovery.png` — target, seed, fitted,
residual ×8, plus the log-loss trajectory, with the population and every floor in its own caption.
**Look at the residual panel, not the loss number.** A sum-of-squares objective rewards colour and
luminance statistics, so a fit can drive the number a long way down while producing an image that
does not resemble the target at all; the residual is the panel that settles it.

### Two traps worth knowing before fitting anything

1. **A clamp is a wall for the gradient.** `clamp` carries zero derivative outside its range, so a
   pixel that is saturated at the seed contributes **nothing**. A seed whose image is mostly blown
   out is a seed with almost no signal. `StackSLFitResult.saturatedFraction` reports it rather than
   hiding it (0.0% for the committed fit).
2. **`.format` binds tighter than `+`.** `"a %f" + "b %f".format(x, y)` formats only the second
   literal, silently, and printed three wrong numbers in three different places during this branch
   before being noticed. Wrap the whole concatenation in parentheses.

## Performance, corrected

`dev_docs/STACKSL_CPU_BACKEND.md` and `StackSLCpu.renderStill`'s KDoc both claimed **10⁵**
pixel-shader invocations per second per core. The measured answer is **1.34 × 10⁴ and 1.56 × 10⁴**
on two runs — about **7×** smaller. Both sites are corrected, and the derived half of the old claim
("512×512 of a five-octave `fbm` is single-digit seconds across ten cores") was **correct** and is
unchanged: the two halves were never consistent with each other, and it was the wall-clock half that
was true.

`StackSLCpuThroughputMeasurements` states the method: one `GlslInvocation` per row with
`parallel = false`, the shader parsed outside the timed region, three untimed warm-up renders, five
timed renders at 96×96, median taken. GraalVM CE 25.0.2, Apple aarch64, 10 cores.

The aggregate is **measured, not multiplied**, and that is the second finding: a `parallel = true`
run at 192×192 reaches **4.4 × 10⁴** invocations/s, which is **3.3×** the single-core figure on ten
cores, not 10×. So 512² is 5.9 s across the machine and 19.5 s on one core. Anyone sizing a fit
resolution, a contact sheet or a docs capture should use 4.4 × 10⁴.

## The loadable demo

`public/docs/assets/stacksl_fit_demo/` — open the directory as a Field document; three boxes, press
run on each, left to right. Frames are 880 wide at x = 60, 1020, 1980, sized against the **measured**
widest output panel (the four-panel strip is 786 px), not a remembered number.

| box | what it does | measured |
| --- | --- | --- |
| `stacksl-wires` | lifts the literals, prints the token/line/column table, shows the lowered GLSL and a 220×220 CPU render | ran; 17 wires, 1.17 s render |
| `stacksl-fit` | 80 Adam steps at 64×64, four panels, a line per literal | ran; loss 1.85e-01 → 1.64e-04 in 7.6 s |
| `stacksl-roundtrip` | fits at 48×48, re-emits the source, recompiles it through the ordinary path, renders at 256 | ran; round trip 5.96e-08 |

All three were run **one at a time** through `tools/field_backdoor.py --port 8821`, and graded on
the presence of a `set-output` event and the absence of `[error,kotlin]` in the log rather than on
the exit status — the backdoor has reported `ok: true` for a box with a hard Kotlin compile error.
Each produced a `set-output` with an inline PNG, and the PNGs were opened and read.

`StackSLFitDemoBundleTest` drives Field's real EDN codec over the bundle with `stacksl_cpu_demo` as
a control, compares each `#afield/file-inline` fallback against its loose `_code_.kt` byte for byte
(Field prefers the loose file), and runs each box's *work* at 24×24 so a change that breaks the
demo's substance reddens even if its imports still resolve. What no Gradle gate can see is a K2-REPL
import failure, which is why the boxes are also run by hand.

## Files

| file | what |
| --- | --- |
| `src/jvmMain/kotlin/afield/stacksl/cpu/GlslScalars.kt` | the scalar seam and the `Float` instance |
| `src/jvmMain/kotlin/afield/stacksl/cpu/GlslInterpreter.kt` | `GlslMachine<V>`, one copy of the control flow |
| `src/jvmMain/kotlin/afield/stacksl/cpu/GlslAd.kt` | `AdValue`, `GlslAdScalars`, the derivative rules |
| `src/jvmMain/kotlin/afield/stacksl/cpu/StackSLWires.kt` | lift, lower, re-emit |
| `src/jvmMain/kotlin/afield/stacksl/cpu/StackSLFit.kt` | the image loss, the exact gradient, the descent |
| `src/jvmTest/resources/stacksl/float_path_manifest.txt` | the 31 pre-refactor raster hashes |

## What is not here

- **No derivative through a sampler**, so the two feedback gallery programs cannot be fitted. It is
  differentiable in principle; it has no oracle here.
- **No derivative through the bit operators**, so `toBits16`/`fromBits16` and the words above them
  (`gray-code`, `lfsr`, `bitxor`) refuse rather than answer.
- **No structural loss term.** The objective is per-pixel sum of squares. A perceptual or
  gradient-domain term would change what the fit rewards, and the residual panel exists because it
  does not have one.
- **No GPU gradient.** The derivative is a CPU-side property of the emitted GLSL; the fitted
  *program* runs on a GPU, the fit itself does not.

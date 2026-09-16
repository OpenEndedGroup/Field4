# Heterogeneous multi-resolution pass chains in `afield.four`

## The gap this closes, and the gap it does not

Field already had pass *composition*, and it is easy to miss because it is named after Three.js'
vocabulary rather than after "render graph":

| file | lines | what it does |
| --- | --- | --- |
| `four/fabricated/FabricatePassGraph.kt` | 84 | the ordered name→pass container: `addLast`, `insertBefore`, `insertAfter`, `replace`, `remove` |
| `four/fabricated/FabricatePostStack.kt` | 65 | "one source texture enters the stack, named passes run in order, and the final pass is the image to present" |
| `four/fabricated/FabricateFrameGraph.kt` | 112 | syncs that ordered list into `RenderTarget.preTargets`, with named built-ins (`std-gbuffer` … `std-aa`) |
| `four/fabricated/FabricatePostEffects.kt` | 262 | five tone-map curves and a **worked bloom**: bright-pass, separable H/V Gaussian, additive composite |

So "an ordered chain of named intermediates, attached to a target" was done, and had been for a
while.

What no caller could express was **a pass that is not the same shape as the pass before it**.
`standardRenderer.kt:532`, in `Bundle.addShaderEffect`:

```kotlin
val target = RenderToTexture(renderer) {
    width = source.width
    height = source.height
    colorAttachments = 1
    format = source.colorFormats.firstOrNull() ?: source.parameters.format
    ...
}
```

and the only hook the function handed a caller was `configure: Parameters.(source: RenderToTexture)
-> Unit` — the *module pipeline* `Parameters`, not `RTTParameters`. There was therefore no argument,
at any call site, that made a pass target smaller than its source or a different format from it. Not
awkward to make smaller. Unreachable.

That is the same one-parameter-block shape as `MultiPingPongRenderToTexture.kt:51`
(`var parameters = RTTParameters().apply(configure)` — one block for an object with three distinct
phases), one layer up.

**And it is why a real multi-resolution effect gets hand-wired as one box per pass.** Not because
composition was missing — because to get a half-resolution target you had to own the
`RenderToTexture` yourself, and once you owned that you owned the module, the sampler and the
schedule too, and you were no longer using the post stack at all. Composition was there; the
*heterogeneity* was the thing the hand-wiring was buying.

## The layer

Four files, and the split between them is the important part.

### `FabricatePassChain.kt` — the declaration, and a pure planner

A chain is a `List<FabricatePassSpec>`. Each spec names its pass, carries its fragment shader, and
says:

- `size: FabricatePassSize` — `MatchSource` (the old, still-default behaviour), `MatchChainInput`,
  `Absolute(w, h)`, `FractionOfSource(n, d)`, `FractionOfChainInput(n, d)`. Fractions floor and clamp
  at one texel, which is what a pyramid needs: half of 1023 is 511, and a pyramid deeper than its
  image bottoms out at 1×1 rather than at a zero-sized attachment.
- `format: Int?` — the pass's own Vulkan format, or null to inherit its source's. Inheritance follows
  the *source*, so setting one format on a bright pass makes everything downstream of it half-float
  until something says otherwise.
- `source: String?` — which earlier pass supplies `sourceTex`, or `FabricatePassChain.CHAIN_INPUT`,
  or null for "the pass before me". This is what lets a chain fork and rejoin instead of only running
  in a line.
- `extraInputs: List<FabricatePassInput>` — additional *named* samplers bound to earlier passes.
- `push`, `mipLevels`.

`FabricatePassChain.plan(...)` resolves the whole thing — sizes, formats, sampler wiring, target
lifetimes, target sharing — **without touching Vulkan**, and returns a `FabricatePassChainPlan` that
can print itself. Two reasons it is a pure function and not a side effect of allocating:

1. **Target sharing is only decidable if you can see the whole chain at once.** An
   append-one-at-a-time API cannot know that pass 1's output is dead by pass 4 and its memory is free
   to take, so it allocates N targets for N passes and keeps every one alive forever. The condition a
   slot is offered under — `lastConsumer[occupant] < i` — is simultaneously "the occupant's output is
   dead" and "pass `i` does not read the occupant", because a pass that read it would have pushed
   `lastConsumer` to at least its own index. One condition, two guarantees.
2. **Arithmetic that is wrong about a resolution renders a picture that looks *fine*.** A slightly
   soft composite, a pyramid one texel off at the bottom, an aperture that quietly ran at full
   resolution — none of those read as bugs in an image. They read as failures in an assertion, which
   is why `FabricatePassChainTest` is 20 CPU-only tests and the GPU builder is left with nothing to
   be clever about.

### `standardRenderer.kt` — `addShaderEffect` grew the arguments

`size`, `format`, `source`, `extraInputs`, `mipLevels`, and an `rtt: RTTParameters.() -> Unit` escape
hatch. The four-argument form is untouched and still defaults to `MatchSource`, so every existing
call site behaves exactly as before.

`extraInputs` is less new capability than a removed tax. `addBloom` already needed a second input and
paid for it with four lines of `textureResolver` surgery at the call site — capture the previous
resolver, build a `TextureSampler` against *that texture's* mip count, string-match `"sceneTex"`,
delegate the rest. Every multi-input pass author had to reproduce it, and getting the sampler's mip
count wrong is not a compile error, not a validation error, just a silently mis-filtered read.
`addBloom` is refactored onto `extraInputs` in the same commit: same pixels, one argument, per-texture
sampler right by construction.

### Persistent passes — the one rule a simulation has to break

Everything above describes a chain that runs once, front to back, over one frame's input, and
`plan` enforces it: *"a chain may only read backwards"*. That is exactly the rule a simulation
cannot live under. A stable fluid's velocity field this frame is a function of the velocity field
**last** frame, and no reordering of passes within a frame produces it.

So a spec may declare itself `persistent`. Its target is materialized as a
`PingPongRenderToTexture` — two images with the roles swapped every frame — and any pass may bind a
sampler to a persistent pass's *previous frame*:

```kotlin
FabricatePassSpec(
    name = "velocity", fragment = …,
    size = FabricatePassSize.Absolute(256, 256),      // required: see below
    format = VK_FORMAT_R16G16B16A16_SFLOAT,           // required: see below
    persistent = true
)
// …and, from a pass that runs *earlier* in the list:
FabricatePassSpec(
    name = "advect", fragment = …,
    source = "velocity", sourcePreviousFrame = true
)
```

A previous-frame reference is the only reference that may point **forwards**, or at the reading
pass itself. That is not a loosening of the backwards-only rule; it is the same rule applied to a
different frame. "The velocity field as of the end of last frame" is a value that already exists
when this frame's first pass runs, whatever index the pass that wrote it happens to have.

Two constraints fall out of that, and the planner checks both with the reason in the message:

1. **A persistent pass's size and format may not depend on its source.** Its output is read a frame
   before the pass that produces it runs, so there is nothing to inherit from yet. `Absolute`,
   `MatchChainInput` or `FractionOfChainInput`, and an explicit format. This is also the honest
   constraint rather than a workaround: a simulation field's resolution and precision are not
   things that should drift with whatever happened to feed it this frame.
2. **A persistent pass's target is never shared.** It has to survive the whole frame *and* the gap
   to the next one, so slot packing neither offers it another pass's slot nor offers its slot to
   anyone, and a persistent slot costs two images rather than one
   (`FabricateTargetSlot.persistent`, `imageCount`, `bytes`).

`FabricateBuiltChain.stateOf(name)` hands back the `PingPongRenderToTexture` for everything outside
the chain that wants the *state* rather than the picture — `latestImageView` to sample the field
from another module, the ordinary readback path to pull it to the CPU. `latestImageView` and not
`thisImageView`: outside the target's own render, "this" is the half about to be overwritten.

#### Three traps this cost, none of them in a shader

- **`extraViews` is not `extraInputs`, and the difference is dynamism.**
  `Module.TexturePointer.pushDescriptorSet` re-reads `texture.imageViewArray` and
  `views.first().imageView` **every frame**, so handing it `otherImageView` — an object with a live
  getter — tracks the ping-pong swap. Handing it `imageViewArray[0]`, which is what
  `addShaderEffect` already did for its primary input, snapshots the *element*, and a ping-pong
  target replaces that element in `beforeRender`. The pass would then read one image forever. It
  plans correctly, allocates correctly, renders the first frame correctly, and never advances.
- **A ping-pong target is only cleared at construction if it carries `TRANSFER_DST` usage.**
  `PingPongRenderToTexture.initializePingImagesNow` falls back to a bare layout transition
  otherwise, leaving the contents undefined — so frame zero of a simulation advects whatever was in
  that memory, which on a warm GPU is often plausible-looking noise rather than an obvious failure,
  and does not reproduce. `addPassChain` sets `colorTransferDst = true` on every persistent target.
- **`clearFirst` must be off, and that is not an optimization.** Clearing a state field every frame
  is clearing the state. Every pass that writes one is fullscreen, so there is nothing a clear would
  have covered.

### `FabricatePassLibrary.kt` — the passes worth having

A 13-tap (Jimenez) downsample pyramid, a separable blur reusing the nine-tap kernel `addBloom`
already ships, a disc/aperture blur, a tent upsample-add, a composite, a bright pass, and two whole
chains: `apertureChain()` and `bloomPyramid()`.

Two of these are new capability rather than new shaders:

- **The aperture disc.** Uniform-weighted taps on a golden-angle spiral with `r = sqrt(t)`, so a
  highlight becomes a flat-topped disc with a hard rim — the shape a camera aperture makes, which a
  Gaussian does not produce at any radius. A disc is also not separable, so it costs O(taps) per
  pixel with no way around it, which is exactly why you want it on a reduced rung and exactly why
  the layer under it had to learn about resolution first.
- **`bloomPyramid()` differs from `addBloom()` in resolution, not code.** `addBloom` blurs at full
  resolution with a nine-tap kernel, so its glow is nine full-resolution texels wide and no wider.
  A pyramid walked down to 1/16 and back up accumulates hundreds of texels for a fraction of one
  full-resolution blur. Its rungs are half-float, because a bright pass into an 8-bit target clips
  exactly the highlights it exists to isolate.

Two more are whole *simulations* rather than effects, and they split neatly along whether they need
state at all.

#### `stableFluid()` — a Stam stable fluid you can paint into

Stam's *Stable Fluids* (1999) is stable because it never integrates advection forward; each frame
runs the operator splitting advect → add force → project. Every one of those steps is a pass, and
the state is two of them:

| pass | what it does | why it is its own pass |
| --- | --- | --- |
| `advect` | semi-Lagrangian backtrace of velocity by itself, from **last frame** | reads *back* along the flow, so it is unconditionally stable at any timestep — the whole method |
| `curl` | ∇×v of the advected field, one channel | confinement needs the curl before the force that uses it; dropped entirely at `vorticity = 0` |
| `force` | vorticity confinement + your splat | the only pass carrying a push constant you turn per frame |
| `divergence` | ∇·v, one channel, so `R16_SFLOAT` | the right-hand side of the pressure equation |
| `pressure-0` | zero | a Jacobi solve needs an initial iterate, and a chain has no way to say "clear this and stop" — every member of `FabricatePassChain.kt:457#FabricatePassSpec` describes a pass that *runs*, so one fullscreen write of a constant is the unambiguous version |
| `pressure-1…N` | one Jacobi iteration each | see below |
| `velocity` | subtract ∇pressure — **persistent** | the projection, and the state |
| `dye` | advect dye by the *new* velocity + your mass splat — **persistent** | what you look at, and the second state field |

**A Jacobi sweep is a pass chain in the most literal sense**, and is the part of a fluid that most
obviously wanted this layer. Iterate *k* reads iterate *k−1* and the fixed divergence, and nothing
else — which is also the only thing a fragment shader can do, and why the solver is Jacobi rather
than Gauss-Seidel or multigrid. Twenty iterations is twenty declarations where the hand-wired
version is twenty boxes or a loop nobody can insert a probe into.

Semi-Lagrangian advection is the one pass in this file that *wants* the `VK_FILTER_LINEAR` sampler
`addShaderEffect` builds: the backtrace lands between texels and the bilinear interpolation is the
reconstruction. `jumpFloodDistance` below cannot use it at all. Those two together are the argument
for a per-pass filter mode eventually, and the reason neither of them needed one yet.

Vorticity confinement is not a more accurate solver, it is a correction for a known error in a cheap
one — first-order advection numerically dissipates curl, and confinement pushes it back along the
gradient of |ω| rotated a quarter turn. It is the single term that separates "a smear that fades"
from "eddies that persist and shed", and the smoke measures it as a 2.7× difference in
high-frequency energy against the same splat with the term off.

It is compiled **in or out** rather than branched on, which is why `fluidForceFragment` is a function
and not a constant: a `sampler2D curlTex` that survives into the SPIR-V has to be bound to
something, and a `vorticity = 0` chain has no `curl` pass to bind it to. Gating on a push constant
at runtime would fail in descriptor setup rather than producing an unforced fluid.

**Painting.** The `force` and `dye` passes deliberately share push-constant names — `splatCenter`
(UV), `splatVelocity`, `splatColor`, `splatRadius`, `splatMass` — so one loop drives both:

```kotlin
val mouse = MouseEvents(renderer)
mouse.onDrag("paint") { ev ->
    listOf("fluid-force", "fluid-dye").forEach { pass ->
        chain.setPush(pass, "splatCenter", Vector2f(ev.pos.x / w, ev.pos.y / h))
        chain.setPush(pass, "splatVelocity", Vector2f(ev.delta.x * 40f, ev.delta.y * 40f))
        chain.setPush(pass, "splatMass", 0.03f)
    }
    true
}
```

…and set `splatMass`/`splatVelocity` back to zero on the frames you are not painting. Nothing in the
library reads a mouse; the splat is push constants, which is the seam that keeps the chain a
declaration.

**The chain ends on the simulation grid**, not the window's. `dye` is the last pass, so
`postStack.output()` is a 256² field. To put it back at full resolution, append the `composite()`
that is already in this file — `composite(blurred = "fluid-dye", sharp = CHAIN_INPUT, blend = …)`.

#### `jumpFloodDistance()` — a distance field in `1 + log2(N)` passes

Every other pass in the library spreads energy. This one propagates *identity*. Each texel carries
the coordinate of the nearest seed it knows about, and each pass offers its eight neighbours at
distance `stride` the chance to improve that answer, with `stride` halving every pass — `N/2, N/4,
… 1`. After `log2(N)` passes every texel holds the coordinate of very nearly the nearest seed in the
whole image, so the distance to it is a subtraction. It is why a GPU can produce a signed distance
field of a shape you are *painting*, at frame rate.

"Very nearly" is honest. The error is concentrated in the handful of texels where three or more
Voronoi cells meet; `plusOne` repeats the final `stride = 1` pass, the standard JFA+1 fix, and
removes most of them for one pass.

Two things it does that nothing else here does:

- **It reads with `texelFetch`, not `texture`.** A bilinear tap between two seed coordinates is the
  average of two positions, which is a position no seed is at — so a filtered read does not blur the
  answer slightly, it *invents* one. `addShaderEffect` builds its samplers `VK_FILTER_LINEAR` and no
  pass spec can change that, so the shaders sidestep the sampler rather than depend on a filter mode
  they cannot set.
- **It indexes by `gl_FragCoord`, not `vUv`.** For a pass the same size as its source those agree
  exactly — the Y-flipped viewport puts framebuffer row 0 at memory row 0, which is the same thing
  the texel-aligned `vUv` encodes — and integer texel arithmetic is what a jump *has* to be. A
  stride expressed in UV lands between texels at odd resolutions.

**Signed, from one flood.** With `signed = true` the seed predicate changes from "every interior
texel" to "every interior texel with an exterior neighbour" — the boundary. One flood then gives the
distance to the nearest *edge*, and the sign comes from the mask, which the final pass samples. The
alternative is two floods and a subtraction, for twice the passes and the same answer.

`resolution` must be a power of two, and the reason is not tidiness: the stride sequence is exactly
its halvings, and at a non-power-of-two the largest stride cannot reach the far corner. The default
format is half-float, which is exact for coordinates up to `MAX_HALF_EXACT_RESOLUTION` (2048) and
refused above it — a coordinate that rounds is a seed that moves, and the result is a field that is
subtly wrong in the far reaches of a large one rather than visibly broken.

`distanceBands()` is the companion that makes a distance field something you can look at: even
bands, a dark isoline at zero, and the two sides of a signed field coloured differently. Even
spacing is also the check — a field whose bands crowd or stretch is not a distance.

### `FabricatePassChainBuilder.kt` — materialize, and decide nothing

`Bundle.addPassChain(passes, renderer, printReport)` plans against the current `postStack.output()`,
then walks the plan calling `addShaderEffect` with sizes already resolved to `Absolute`. It computes
nothing: there must not be a second, subtly different copy of the arithmetic living where only a GPU
can reach it. `FabricateBuiltChain.setPush(pass, name, value)` retunes a live chain with no rebuild
and no reallocation, which is the live-coding half of declaring a chain — an aperture radius is a
number a person turns while watching.

The passes land in the same `FabricatePostStack` as everything else, so `FabricateFrameGraph`
schedules them, `postStack.output()` is the chain's output, `insertBefore`/`remove` still work on
them by name, and a chain composes with `addBloom` and `setToneMapping` rather than replacing them.

## What it costs — the plan report

`apertureChain()` at 1024², from `printReport = true`:

```
pass chain: 5 passes on 4 target(s), input 1024x1024 fmt=97
  #0 reduce-1: 512x512 fmt=97 slot=0 [sourceTex<-@input] (dead after #1)
  #1 reduce-2: 256x256 fmt=97 slot=1 [sourceTex<-reduce-1] (dead after #2)
  #2 aperture-near: 256x256 fmt=97 slot=2 [sourceTex<-reduce-2] (dead after #3)
  #3 aperture-far: 256x256 fmt=97 slot=1 [sourceTex<-aperture-near] (dead after #4)
  #4 composite: 1024x1024 fmt=97 slot=3 [sourceTex<-@input, blurTex<-aperture-far] (output)
  slot 0: 512x512 fmt=97 <- reduce-1
  slot 1: 256x256 fmt=97 <- reduce-2 then aperture-far
  slot 2: 256x256 fmt=97 <- aperture-near
  slot 3: 1024x1024 fmt=97 <- composite
  memory: planned 11.00 MiB, unshared 11.50 MiB, one-full-res-target-per-pass 40.00 MiB
  saving: 3.64x vs one full-resolution target per pass
```

The counterfactual is not a straw man: **one full-resolution target per pass is what
`addShaderEffect` allocates for this chain today**, because that is the only thing the old API could
express. `slot 1: reduce-2 then aperture-far` is the sharing — `reduce-2` is dead once
`aperture-near` has read it, so `aperture-far` takes its target.

The same chain at an 8-bit input plans to 5.75 MiB unshared and 5.50 MiB with sharing against 20.00
MiB naive, i.e. 3.48× on the multi-resolution shapes alone and 3.64× with sharing.
`FabricatePassChainTest` asserts those as **exact byte totals**, not as a ratio: the interesting
failure is a shape drifting by one halving, and a ratio floor set an order of magnitude low would
absorb it silently. (Its first run caught a typo in the expected-value expression, which is the
argument for writing it that way.)

## Verification

### GPU-free — `./gradlew jvmTest --tests 'afield.four.fabricated.*'`, 40 tests

`FabricatePassChainTest` (33) covers sizes, formats, mip cost, input wiring, backwards-only reads,
lifetimes, slot packing and the cost report. The one non-obvious test is
`slotSharingNeverAliasesALiveInput`, which checks the packing invariant *directly* over four
different chains rather than trusting the lifetime it is derived from: no sampler may be bound to a
pass whose slot was retaken between producing and reading.

Thirteen of those cover persistent passes and the two simulation templates: a previous-frame
reference resolving against a pass that has not run yet, a persistent pass reading *itself*, both
rejections (a previous-frame reference to a non-persistent pass, and a persistent pass whose size or
format would have to be inherited), a persistent slot proved exclusive and two images wide, the
fluid's Jacobi ladder checked iterate by iterate, the vorticity switch dropping the curl pass *and*
its binding together, and the jump flood's stride sequence asserted as the exact list
`[128, 64, 32, 16, 8, 4, 2, 1]`. The stride list is worth asserting as a list: one rung short and the
far corner of the image never hears about a seed in the near one, which renders as a distance field
that is right in the middle and wrong at the edges.

`FabricateShaderCompileTest` (7) pushes every library fragment through shaderc, plus a sweep that
asks the *builders* what they emit — so a future library function cannot ship a shader that has never
been compiled. Two string-template seams no Kotlin compiler checks: the aperture fragment
interpolates `MAX_APERTURE_TAPS` into its own loop bound, and `fluidForceFragment` compiles its
vorticity term and the `curlTex` declaration in or out, so *both* halves of that switch are separate
sources and both are compiled.

### On the GPU — `tools/run_field_tests.py --id four.pass-chain.smoke`

Builds a real `StandardRenderer` bundle, renders a 5×5 grid of lit spheres, appends two chains with
`addPassChain`, and reads ten targets back to PNGs in `build/pass-chain/`. Everything measured goes
through the production consumer and is read back from the target the frame graph actually rendered.
Measured 2026-09-07 on an Apple M1 Max, floors just under each number:

| what | measured | floor |
| --- | --- | --- |
| high-frequency collapse, test pattern | 14.07× | 9.0 |
| high-frequency collapse, lit scene | 3.31× | 3.0 |
| aperture rim vs Gaussian rim | 3px vs 6px | 1.6× |
| half-float rung vs its 8-bit twin | 7.1× brighter | 6.0 |
| scene chain targets | 5 passes on 4, 3.64× memory | must share ≥1 |
| live push, radius 12→3 | disc 18px → 10px | 1.5× |

Two of those are **same-run A/Bs rather than thresholds**. The 8-bit twin is the same two reduces and
the same aperture at the same radius, differing only in `format`; the test pattern's dots are 110×
over white, so the 8-bit path clips them before the disc spreads them and its discs come out 7.1×
dimmer. And the Gaussian is the nine-tap separable blur `addBloom` already ships, run on the same
rung — so "an aperture is not a Gaussian" is a comparison and not a number somebody picked.

The PNGs were opened and looked at: `01-scene-source.png` is a sharp lit grid, `02-scene-composite`
is the same grid heavily and correctly blurred, and `09-pattern-composite` has the 16px checkerboard
*collapsed* — dramatically attenuated against the source's hard black-and-white, though a
low-contrast residual is still visible across the frame, which is what a 14.07x reduction looks like
rather than removal — and two flat-topped hard-rimmed octagonal bokeh discs where the dots were,
against the visibly softer rounded-square halos of the same rung blurred by `addBloom`'s nine-tap
separable Gaussian. (Note `08-pattern-gaussian.png` is the quarter-resolution rung and
`09-pattern-composite.png` the full-resolution composite, so those two committed files are *not* the
matched pair the 3px/6px rim figure was measured on; that comparison is made in-test, on one rung.) (The spheres are aspect-stretched; that
is the known `SuperProjection` window-aspect behaviour, not this layer.)

### The simulations, on the GPU — `tools/run_field_tests.py --id four.simulation-chain.smoke`

Ten guards, PNGs in `build/simulation-chain/`. Measured 2026-09-16 on an Apple M1 Max.

`FabricatePassChainTest` checks that `fluid-advect` binds `fluid-velocity`'s previous frame and that
the persistent slots are exclusive. **Every one of those assertions would still hold if the ping-pong
swap never happened.** So the assertion this file exists for is the dullest one in it: a splat, then
the splat switched off, then two captures forty frames apart — *the picture must keep changing*.

| what | measured | floor |
| --- | --- | --- |
| dye centroid drift, no input, 40 frames | 0.0424 UV, along +u | 0.01 UV, and the right direction |
| dye energy decay over the same 40 frames | ×0.586 (dissipation alone predicts ×0.546) | must fall, and not by more than 4× the prediction |
| vorticity confinement vs the same splat without it | 2.69× the high-frequency energy | 5% apart |
| jump flood vs **exact** ground truth, 900 samples | 0.5 of 255 codes, worst case | 2 codes |
| live `dissipation` push, 0.985 → 0.5 for 12 frames | wiped the dye to 0.000005 of itself | 0.05, no reallocation |

The distance figure is the strong one, and it is ground truth rather than an A/B: with a single seed
the answer is `length(p − seed)`, so the comparison is against arithmetic. Agreement to half an
8-bit code across the whole field — corners included — rules out a missing rung, a filtered read and
a wrong stride sequence simultaneously, and none of those three would look wrong in a picture.

The PNGs were opened and looked at. `09-jfa-bands.png` is a set of perfectly concentric, evenly
spaced rings around one texel. `10-jfa-signed-bands.png` has the disc's interior in blue and its
exterior in orange with the dark isoline sitting exactly on the mask's edge, bands evenly spaced on
both sides. `04-fluid-velocity-field.png` shows the red/green quadrupole of a jet's counter-rotating
vortex pair. And the vorticity A/B is visible rather than only numeric: `05-fluid-coasting-late.png`
is filamentary with a ragged leading edge, `06-fluid-coasting-late-no-vorticity.png` is the same
blob as a smooth smear.

#### Three measurement traps, all in the harness and none in a shader

These belong with the six below — same family, found the same way, and all three were a *failing
assertion about a working shader*.

1. **The readback blits float targets to `VK_FORMAT_R8G8B8A8_SRGB`**
   (`FourVisualizers.kt#displayEncodedSource`), so the PNG's bytes are display-encoded and are not
   the numbers the shader wrote. The distance field read 0.3137 where the exact answer was 0.0804 — a
   290% error, and `srgbToLinear(80/255) = 0.0802`, a 0.25% one. Anything that is a *sum* or is
   compared against an *absolute* expectation has to be decoded; ratios of the same statistic between
   two captures do not.
2. **A field driven over 1.0 reads back clamped, and a clamped field's decay is invisible.** At
   `splatMass = 3.5` the dye settled near 72, so forty frames of dissipation took it from 72 to 44
   with the PNG not moving at all — and the measured total *rose* 1.1%, because the saturated region
   was spreading. This is trap 1 of the six below applied to an entire field instead of one
   highlight. The fix is to keep the simulation inside the instrument's range **and assert that it
   is**: `SATURATION_CEILING` is checked before anything draws a conclusion from a sum.
3. **Ground truth for a single seed is that texel's *centre*, not its index.** Comparing against
   `SEED_TEXEL` rather than `SEED_TEXEL + 0.5` is invisible across most of the field and a 4.6-code
   error at the seed itself, where the exact distance is 0.7 texels and the measurement is correctly
   0. The mask radius also has to seed exactly one texel or nearest-seed distance stops being
   distance-to-a-point, so the test *counts* the zeros rather than assuming.

Once the comparison was moved into encoded space — the exact answer put through the same transfer
function the measurement came out of — the residual dropped to half a code and stayed there. A
percentage of a linear value could not have expressed that: one code step near the middle of the
field is 2.6% of the linear value there, so a 2% ceiling on linear error is a ceiling *below the
instrument's own resolution*, and it failed at 2.34% with nothing wrong.

### In a live Field

`dev_docs/agents/transcripts/four_pass_chain.yaml` is in the guarded corpus, classified
`COMPILE_ONLY` in `DemoTranscriptExecSmokeTest` because it wants a GLFW window and a Vulkan device,
and offered as a starting point on the welcome screen. Open it from
`public/docs/agent_transcripts.md`, or replay it yourself:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --scratch \
  --command 'macro write four_pass_chain --file dev_docs/agents/transcripts/four_pass_chain.yaml' \
  --command 'macro run four_pass_chain'
```

It builds a fresh three-box document, opens a Vulkan window titled **Pass chain**, and shows the
sphere grid under a two-disc aperture blur; the chain box prints its whole plan as it runs. Replayed
end to end into a fresh `--scratch` document on 2026-09-07: `applied=12 skipped=0 errors=0`, all
three boxes printing normally, with a `native-screenshot` of the resulting window.

Then open the **pass chain** box, edit the three `setPush` numbers under the `once` block, and hit
run. The blur changes on the next frame with no pipeline rebuild and no target reallocated, because
`setPush` writes the push constant on the pass's existing fullscreen quad. That is the difference
between a chain you *declare* and a chain you *assemble*.

`dev_docs/agents/transcripts/four_simulation_chain.yaml` is the same thing for the two simulations —
four boxes, classified the same way and for the same reason, and offered as a starting point beside
it. Drag in the window to paint velocity and dye into the fluid; the distance field floods a disc and
a bar into a signed field and insets it into a corner. Replayed end to end into a fresh `--scratch`
document on 2026-09-16: `applied=16 skipped=0 errors=0`, all four boxes printing normally, the fluid
reporting `[fluid-velocity, fluid-dye]` and the distance chain `14 passes, output 1024²`, with every
new shader through shaderc and every pipeline created clean in the managed-server log.

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --scratch --timeout 900 \
  --command 'macro write four_simulation_chain --file dev_docs/agents/transcripts/four_simulation_chain.yaml' \
  --command 'macro run four_simulation_chain' \
  --command 'wait-native-renderer'
```

`--timeout 900` is not caution. The default dropped the connection mid-run and reported
`{"error": "TimeoutError"}` while the server was still happily creating pipelines — 41 passes across
two chains is 41 shaderc compiles and 41 pipelines, and on a loaded machine that outlasts the
client's patience. The managed-server log under `.field-backdoor/logs/` is what says which it was.
`wait-native-renderer` writes a PNG of the window there as a side effect, which is the capture to
look at; `native-screenshot` takes no `--out`.

#### Two import traps these transcripts already pay for

Both cost real time when the demo was first written, and neither is specific to this layer.

- **`afield.four.fabricated` is not among a box's automatic imports.** `afield.four`,
  `afield.four.modules`, `afield.four.targets`, `afield.four.buffers` and `afield.four.texture` all
  are (see `intro` in `src/jvmMain/kotlin/afield/Boxes.kt`), but the fabricated renderer is not — so
  `StandardRenderer.build(renderer)`, which several published pages show being typed straight into a
  box, needs an explicit import first.
- **`prop<T>()`'s code rewrite does not carry the snippet's own `import` statements.** With
  `import afield.four.fabricated.StandardRenderer` at the top,
  `prop<StandardRenderer.Bundle>(bundle)` fails — and the error, `Unresolved reference
  'StandardRenderer'`, is reported against the *import* line, which reads like a missing class
  rather than a rewrite dropping it. Every other reference in the same box resolves normally
  through that same line. The workaround in the transcript is to write the `prop<>` type argument
  fully qualified: `prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)`.

And one that is specific to *box* code rather than to this layer:

- **An overload pair a box cannot resolve.** The two candidates are
  `MouseEvents.kt:66#onDown` and `MouseEvents.kt:69#onDown` — overloaded on
  `() -> Boolean` and a `(MouseEvent) -> …` form — and `mouse.onDown("left", "paint") { true }` —
  which compiles anywhere else — is an *overload resolution ambiguity* in a box. Naming the
  parameter's type resolves it: `{ _: MouseEvent -> DownResult.Keep }`. The same box also cannot drop
  the `onDown` even though it does nothing, because `MouseEvents` only polls buttons some handler has
  asked about (`buttonsRequested = down.keys + up.keys`) and `onDrag` only fires while a button is
  down — so without it no drag ever arrives and the fluid sits there looking broken.

## Six measurement traps, all paid for

Every one of these was a *measurement* being wrong while the shaders were right, and each is now a
comment where the mistake was. They are listed because they are traps in measuring any GPU blur, not
just this one.

1. **Peak brightness cannot measure a half-float target.** The readback is an 8-bit sRGB PNG, so any
   value over white returns as exactly 1.0. Total energy in a region survives the conversion; the
   peak does not — which is why the format A/B is asserted on region energy
   (`FabricatePassChainDemo.kt:587#meanLumaRightHalf`) rather than on a peak.
2. **"Brightest pixel" cannot centre a radial profile on a saturated blob.** The scan settles on
   whichever edge pixel it reaches first, and every ring it then averages is half background. That
   read 0.543 of peak and looked exactly like a broken shader. The profile is therefore centred on a
   declared coordinate rather than a searched one (`FabricatePassChainDemo.kt:631#radialProfileAt`).
3. **Flatness cannot distinguish a saturated disc from a saturated Gaussian at all.** Both are flat
   across the top. Only the rim can, and does (`FabricatePassChainDemo.kt:549#rimWidth`).
4. **A radial profile is capped by the nearest neighbouring feature.** A 0.25-UV dot cell capped
   every measurement at 32px of the quarter-resolution rung — under the width of the Gaussian being
   compared to — so the disc and the Gaussian both came back at the *frame's* width, identical.
5. **A profile must be normalised against its pedestal.** 0.012 in linear light is about 0.15 in an
   8-bit sRGB readback, above a 10% "faded out" threshold, so an untruncated profile declared itself
   truncated.
6. **High-frequency energy was expected to *rise* when the aperture narrowed. It fell** — a narrower
   disc spreads less light and the whole image dims. The push constant had been arriving the whole
   time. `Profile.truncated` now exists so that class of failure is loud rather than plausible.

## Risks and what is not done

- **Slot sharing is planned but only partly realised.** The planner computes the full packing and the
  report states it, and the builder allocates one target per pass at the planned *multi-resolution
  shape* — which is where most of the saving is (3.48× of the 3.64× on the aperture chain). Making
  two passes share one physical image needs either per-pass module scheduling inside a
  `RenderToTexture` (it renders *all* its modules, so two passes on one target would both run every
  time) or `RTTParameters.externalColorViews` aliasing, whose blocker is that each aliasing
  `RenderToTexture` tracks its own `colorLayout` independently and would emit wrong barriers. So the
  gap between `plannedBytes` and `unsharedBytes` is currently a statement about what is *possible*,
  and the demo's report is honest about which is which. The planner is where that work would land.
- **Rebuilding a chain leaks.** `postStack.remove(name)` drops an effect from the ordered list but
  disposes neither its `RenderToTexture` nor its module. That is pre-existing behaviour shared with
  `addBloom` (which calls `remove` on four names and rebuilds), and `setPush` exists partly so the
  common live-coding case does not hit it. A `FabricateBuiltChain.dispose()` is the obvious follow-up.
- **`addPassChain` plans against `postStack.output()` at call time**, so appending a chain after
  `addBloom` sees the bloomed image. That is the intended composition, but it means chain A's output
  is chain B's nominal input, and a second chain that wants the *original* input has to say
  `CHAIN_INPUT` explicitly — which is only the first chain's input if there was no first chain. The
  demo's pattern chain sidesteps it by synthesising its own source.
- **`FabricatePassSize.MatchChainInput` means the post stack's input**, i.e. `transparentComposite`,
  not the window. On a bundle whose render size differs from the window's these are not the same
  number.
- **The Jacobi ladder is where unrealised slot sharing costs the most, and it is also where the
  planner's answer is most obviously right.** A twenty-iteration pressure solve plans onto *two*
  slots — the report shows `pressure-0 then pressure-2 then …` on one and the odd iterates on the
  other, which is a ping-pong the planner discovered rather than was told — and allocates
  twenty-one targets. At 128² `R16_SFLOAT` that is 640 KiB of waste rather than a problem, but the
  ratio is the worst in the library, and it is the concrete case to point at if the aliasing work
  above ever gets picked up.
- **There is no per-pass sampler filter mode.** `addShaderEffect` builds every sampler
  `VK_FILTER_LINEAR`. The two templates added here want opposite things — semi-Lagrangian advection
  *needs* bilinear, a jump flood cannot tolerate it — and both got what they needed without the
  knob: the fluid by using `texture`, the flood by using `texelFetch` and bypassing the sampler
  entirely. That works, and it means a pass author has to *know* to reach for `texelFetch`, which is
  exactly the kind of thing this layer exists to stop being knowledge. A `filter` on
  `FabricatePassSpec` threaded through to the `TextureSampler` is the obvious follow-up; nothing here
  is blocked on it.
- **The pressure solve is not warm-started.** `pressure-0` writes zero every frame, so twenty
  iterations are spent re-converging from scratch on a field that barely changed. Making the
  pressure field persistent and starting from last frame's solution is a two-line change to
  `stableFluid` and would buy either a better solution at the same cost or the same one at fewer
  iterations. It is not done because an unconverged warm start *accumulates* rather than resets, and
  that wants its own assertion before it ships.
- **The fluid's boundary is a mask, not a reflection.** Velocity is multiplied by zero in the
  outermost texel ring, which is crude next to Stam's `set_bnd`. It does have to be *something*:
  with pure clamp-to-edge sampling, advection transports velocity through the wall, the pressure
  solve has no boundary condition to satisfy, and the fluid slowly pumps itself into a corner over a
  few hundred frames — long enough to blame something else.
- **Two chains in one post stack run in series, and the last one appended owns the screen.** That is
  the intended composition and it is still a trap: appending the distance chain after the fluid makes
  the fluid vanish, which reads as a broken fluid rather than an ordering you asked for.
  `four_simulation_chain`'s distance box therefore ends in an inset pass that passes the incoming
  frame through and draws the field into one quadrant. A chain has no way to say "show me next to the
  other one": `FabricatePostStack.kt:11#FabricatePostStack` is an ordered name→pass list whose
  `output()` is its last effect's target, so composition is the only layout there is.

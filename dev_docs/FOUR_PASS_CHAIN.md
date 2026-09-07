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

### GPU-free — `./gradlew jvmTest --tests 'afield.four.fabricated.*'`, 25 tests

`FabricatePassChainTest` (20) covers sizes, formats, mip cost, input wiring, backwards-only reads,
lifetimes, slot packing and the cost report. The one non-obvious test is
`slotSharingNeverAliasesALiveInput`, which checks the packing invariant *directly* over four
different chains rather than trusting the lifetime it is derived from: no sampler may be bound to a
pass whose slot was retaken between producing and reading.

`FabricateShaderCompileTest` (5) pushes every library fragment through shaderc, plus a sweep that
asks the *builders* what they emit — so a future library function cannot ship a shader that has never
been compiled. The aperture fragment interpolates `MAX_APERTURE_TAPS` into its own loop bound from
Kotlin, a string-template seam no Kotlin compiler checks.

### On the GPU — `./gradlew runFabricatePassChainDemo` (`FIELD_TEST four.pass-chain.smoke`)

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
gone and two flat-topped hard-rimmed octagonal bokeh discs where the dots were, against
`08-pattern-gaussian`'s visibly softer rounded-square halos. (The spheres are aspect-stretched; that
is the known `SuperProjection` window-aspect behaviour, not this layer.)

### In a live Field

`dev_docs/agents/demos/four_pass_chain/four_pass_chain.yaml` replays into a fresh document —
`applied=12 skipped=0 errors=0`, all three boxes printing normally, verified with a
`native-screenshot` of the resulting window. Its `README.md` has the one-line replay command.

## Six measurement traps, all paid for

Every one of these was a *measurement* being wrong while the shaders were right, and each is now a
comment where the mistake was. They are listed because they are traps in measuring any GPU blur, not
just this one.

1. **Peak brightness cannot measure a half-float target.** The readback is an 8-bit sRGB PNG, so any
   value over white returns as exactly 1.0. Total energy in a region survives the conversion; the
   peak does not.
2. **"Brightest pixel" cannot centre a radial profile on a saturated blob.** The scan settles on
   whichever edge pixel it reaches first, and every ring it then averages is half background. That
   read 0.543 of peak and looked exactly like a broken shader.
3. **Flatness cannot distinguish a saturated disc from a saturated Gaussian at all.** Both are flat
   across the top. Only the rim can, and does.
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

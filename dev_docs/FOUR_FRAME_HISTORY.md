# Frame history and reprojection in `afield.four`

## The gap this closes, and the two thirds of it that already existed

The brief this was built from claimed that `afield.four` had "no camera matrices, no previous
view-projection, no per-pixel velocity". Two of those three were already there, and saying so is the
starting point rather than a footnote — the work here is *narrower* than it looked, and knowing which
part was missing is what kept it from becoming a second, parallel motion-vector path.

What already existed, all of it in `four/fabricated/standardRenderer.kt`:

| what | where | note |
| --- | --- | --- |
| `prevView`, `prevProj` on the live `Camera` uniform | `seedDefaultCameraUniforms`, `:1560-1572` | seeded as *defaults*, so any module declaring them gets them |
| a private frame-stable history | `FrameStableHistory`, `:1422-1465` | the thing this page is about replacing |
| per-object `prevModel` | `ModelMotionState`, `:1467-1493`, a `WeakHashMap<Mesh, _>` | so a moving *object* has a previous pose, not just a moving camera |
| a per-pixel motion vector | `outMotionVectors`, g-buffer colour attachment **3** | `motion = currentUv - previousUv`, computed from `vCurrentClip`/`vPreviousClip` |
| a consumer | `PASS_MOTION_BLUR`, `:989-1194` | reads attachment 3 |
| a debug blit | `gBuffer.blitMotionToScreen(attachment = 3)`, `:367` | in the multi-view debug layout |

So the rasterizer has motion vectors, and better ones than anything on this page: they are
per-object, so they blur a moving object in front of a still camera. What was missing was two things.

### 1. There was no validity rule anywhere

`FrameStableHistory.advanceToCurrentFrame` advances on:

```kotlin
} else if (frame != lastFrameSeen) {
```

**`!=`, not `== lastFrameSeen + 1`.** After a dropped or skipped frame it hands out a previous value
that is two or more frames old and says nothing about it. It also has no size, no scene identity and
no `invalidate()`, and on the first frame it sets `previous = current`, which is a silent "zero
motion" rather than an honest "unavailable".

For its own consumer that is cosmetic: a motion-blur streak of the wrong length is a streak. For an
*accumulating* history buffer it is permanent — history is blended into itself every frame, so a
frame of wrong correspondence does not wash out, it compounds.

And it is the half that a rendered picture cannot check. "Where was this pixel last frame" is a
matrix multiply that is either right or produces an obviously smeared image. "*Is* there a last frame
for this pixel" is a boolean, and when it is wrong the image looks **entirely fine**, because it
blends against a buffer holding a perfectly good picture of the wrong thing.

### 2. A `FabricatePassSpec` could not ask for any of it

`FrameStableHistory` and `ModelMotionState` are both `private` to `standardRenderer.kt`, and
`FabricatePassSpec` carried `name, fragment, size, format, source, extraInputs, push, mipLevels,
persistent, sourcePreviousFrame` — nothing about a camera. `grep -rn prevView src/` hits three files
and none of them is in the pass-chain layer.

## What was added

| file | what |
| --- | --- |
| `four/targets/FrameHistory.kt` | the type and the validity rule |
| `four/fabricated/FabricatePassChain.kt` | `FabricatePassSpec.frameHistory`, `plan.historyPasses`, `+history` in the report |
| `four/fabricated/FabricatePassChainBuilder.kt` | the push binding; `setPushBinding`; `FabricateBuiltChain.history` |
| `four/fabricated/standardRenderer.kt` | `Bundle.currentViewProjection`, `Bundle.frameHistoryEpoch`, `Bundle.invalidateFrameHistory` |
| `four/fabricated/FabricatePassLibrary.kt` | `FRAME_HISTORY_GLSL`, `temporalAccumulate`, `cameraMotionBlur`, `temporalDebug` |

`targets`, not `fabricated`, for `FrameHistory`: the thing it is about is a render target's frame,
size and identity; `PingPongRenderToTexture` — the mechanism `persistent` is built on — already lives
there; and `fabricated` already depends on `targets` rather than the reverse.

### The validity rule

`available` is false unless **all** of:

- this is not the first capture;
- the frame id is exactly one more than the last captured — not merely *different* from it;
- the size is unchanged;
- the epoch token is the **same object** (`===`, not `==`: its job is to be a token somebody swaps,
  not a value somebody compares);
- `invalidate()` has not been called since.

When history is unavailable, `previous` is set equal to `current`, so `reprojection` is the identity.
A shader that ignores `available` therefore degrades to a zero-motion read of the same UV rather than
to whatever was in the matrix — wrong, but wrong in the boring direction.

`capture` for a frame id it already stands at is a **no-op returning the same answer**. That is
load-bearing, not tidiness: every pass in a chain that wants history calls it, and if the second call
advanced, the chain's later passes would reproject against its *earlier passes* rather than against
last frame, and history would never be more than one pass old.

### Why one composed matrix and not three

The shader-facing quantity is `reprojection = previous · inverse(current)`: it takes a current clip
position **straight to** the previous clip position.

That is not only convenience, it is what keeps this inside a push constant. A `mat4` is 64 bytes and
Vulkan's *guaranteed* `maxPushConstantsSize` is 128, so sending current, previous and inverse
separately is 192 bytes before the pass's own constants — off the edge of the guarantee on any device
that only meets it. One composed matrix plus a `vec4` of `(width, height, available, 0)` is 80 bytes
and fits everywhere.

**Measured rather than assumed**, which is what the brief asked for: on this machine (Apple M1 Max,
MoltenVK, Vulkan 1.3.323) `maxPushConstantsSize` is **4096 bytes**, so three matrices would in fact
have fit *here*. The design stays composed anyway, because 128 is what portability means, and the
GPU test floors the measurement at 128 rather than at 4096 so it states the portable claim.

`current`, `previous` and `inverse` are still kept, for the debug views and for any pass that
unprojects, and `packed` carries all four `mat4` plus the four scalars (68 floats, by named
`PACKED_*` offsets) for a caller who would rather use a uniform buffer.

### Why the capture happens inside the push binding

`addPassChain` binds the two constants with `setPushBinding`, and the supplier calls
`FrameHistory.capture` before returning the value.

There is no "start of frame" hook guaranteed to run before the first pass writes its push constants,
and a capture that lands *after* a pass has already read the history is a one-frame-stale
reprojection — which reads as a slightly wrong motion vector and not as a bug. Calling it from the
binding makes the first reader the one that advances it, and idempotence makes every later reader
agree.

One consequence, and it is a fact worth having: from *outside* a render, `history.frameId` is exactly
`renderer.frameCounter - 1`. The push binding runs during frame N while the counter still reads N;
the counter advances after the render completes. A lag of exactly 1 is the evidence that the capture
ran inside the render rather than after it. (The GPU test asserted equality first and failed at 192
against 193.)

### The depth honesty

`FRAME_HISTORY_GLSL`'s `fieldHistoryUv` reprojects at an **assumed NDC depth**, because a post pass
has a colour buffer and no depth buffer.

That makes it **exact for a camera that rotated or changed its field of view** — for a pure rotation
about the camera position, `reprojection` is `P·R·P⁻¹`, and a rotation about the origin maps a point
at distance *d* along direction *u* to distance *d* along *Ru*, so the projected result depends only
on the direction and the assumed depth cancels. It is **approximate for a camera that translated**,
with the error growing with the spread of real depth around the assumed plane.

The honest next step is to thread the g-buffer's depth attachment into a chain as a named sampler.
That is a bigger change than this one and is deliberately not pretended to be done. A pass that *has*
depth should compose `reprojection` with its own unprojection rather than call `fieldHistoryUv`.

### Availability is two tests, not one

`historyParams.z` is the CPU's answer — is there a previous frame at all. It says nothing about *this
pixel*, which may have reprojected to somewhere off the edge of the previous frame.
`fieldHistoryValid` requires both, and the second is per-pixel, which is why it cannot live on the
CPU side. It is also what makes debug mode 3 show a red band on the edge a yawing camera moves away
from.

## Measurements

CPU, `FrameHistoryTest`, **13 tests**. Written red-first against the `FrameStableHistory` rule
(`frame != lastFrameSeen`, no size, no epoch, no invalidate): **7 of 13 failed** — skipped frame,
resize, invalidate, epoch change, backwards time, the packed availability flag, and `params()`. The
other 6 passed, including the premise assertion and a CPU oracle that composes the two
view-projections by hand and agrees to under `1e-3` NDC over 27 points of the unit cube. So the
counterfactual is not vacuous and the arithmetic was never the thing in question. With the rule as
committed, **13 of 13**.

GPU, `four.reprojection.smoke` (`afield/examples/four/FabricateReprojectionDemo.kt`), **11 guards,
23 PNGs, every one opened and looked at**. Seven passes in **one** chain — six of them declaring
`frameHistory` and sharing one `FrameHistory`, which exercises the per-frame idempotence rather than
asserting it. Every population is **262144 texels** (512²):

| what | measured | fence |
| --- | --- | --- |
| `maxPushConstantsSize` | 4096 bytes | floor 128 (the Vulkan guarantee) |
| still camera, available | 1.0000 | floor 0.99 |
| still camera, reported motion | 0.393 texels | tolerance 1.0 |
| still camera, previous-UV ramp vs `vUv` | 2.36 texels | tolerance 4.0 |
| frame after `invalidateFrameHistory()` | 0.0000 available | ceiling 0.001 |
| the frame after that | 1.0000 available | floor 0.99 |
| 0.144 rad yaw, retained | 0.9922 | floor 0.80 **and** ceiling 0.999 |
| 2% FOV change, retained | 0.9461 (from 1.0000) | floor 0.80 |
| noise variance, blend 0.9 vs blend 0 | 0.009507 → 0.000341, **27.92x** | floor 4.0, theory 19.0x |
| **reprojected vs same-UV fidelity to the live scene** | 0.32 vs 0.98 mean 8-bit codes, **3.11x** | floor 2.0 |
| `cameraMotionBlur` at rest vs its source | 0.00 mean codes | ceiling 2.0 |
| `cameraMotionBlur` rotating, softening | 1.418x | floor 1.15 |
| `history.frameId` vs `renderer.frameCounter` | lag exactly 1 | equality |

The yaw retention is floored **and capped** on purpose. A rotation *must* disocclude a band at the
trailing edge, so 100% retention is not a good result — it is an availability flag hard-wired on. The
0.9922 corresponds to the ~4-texel band a 0.006 rad/frame yaw exposes at this field of view, and the
PNG shows exactly that band on the left edge.

## Traps, all of them hit in this run

**High-frequency energy is the wrong metric for reprojection, and it is wrong in the flattering
direction.** The central assertion was written first as a sharpness ratio and came back at **0.648x**
— the *correct* reprojection measuring blurrier than the broken same-UV lookup — with nothing at all
wrong. The neighbourhood clamp is why: a history read at the wrong texel is far outside the 3×3 range
of the pixel in front of it, so it clamps hard to the local min or max, and the result is a
hard-edged, over-contrasted image with *more* energy at the top of the spectrum than the aligned one.
Sharpness rewards ringing, so it cannot tell "sharp because aligned" from "sharp because clamped".
The oracle is now **distance from the live scene**, which under a pure rotation is near-exact for the
reason in "the depth honesty" above.

**Zero motion does not encode to code 128.** Debug mode 1 writes 0.5 in *linear* light and
`TexturePreviewCache` blits to `R8G8B8A8_SRGB`, so zero motion arrives at
`linearToSrgb(0.5) * 255 = 188`. The still-camera check failed by exactly 60 codes against a
perfectly correct shader. It decodes first now, and reports texels.

**A chain appended to a post stack sees the previous chain's output, not the scene.** `addPassChain`
takes `postStack.output()` as its chain input, so six `addPassChain` calls build a *pipeline* and two
accumulations being A/B'd would be accumulating different images. The GPU test uses one chain with
every pass sourcing `CHAIN_INPUT`. (`FabricateSimulationChainDemo`'s `fluid-show` comment records the
same trap from the other direction.)

**A counterfactual must not be built out of the thing it is a counterfactual to.** The same-UV
accumulation shader is its own literal rather than a string substitution on
`TEMPORAL_ACCUMULATE_FRAGMENT`: a library edit that moved the substituted substring would silently
turn the counterfactual into a copy of the real pass, and the measured ratio into 1.0.

**The Y flip has to match the one the fullscreen triangle applied.** `fullscreenVertex` emits
`vUv = vec2(pos.x, -pos.y) * 0.5 + 0.5`, so clip-to-UV is `* vec2(0.5, -0.5) + 0.5`.
`FRAME_HISTORY_GLSL` carries that expression once, written identically to the one `gBufferFragment`
uses for its own motion vectors; a sign difference in `y` gives an image that is stable on a pan and
smears on a tilt. `FabricatePassChainTest.everyHistoryShaderDeclaresTheTwoConstantsTheBuilderBinds`
pins it, along with the fact that the builder's contract with these shaders is **by name** and a name
the shader does not declare is *skipped rather than thrown* — so a typo would otherwise produce an
identity reprojection and no error anywhere.

## What is not done

- **Per-object velocity in a chain.** The g-buffer has it, at attachment 3, per object. Nothing here
  reaches it. A chain pass still cannot blur a moving object in front of a still camera, which
  `PASS_MOTION_BLUR` can.
- **Depth-correct reprojection under translation.** See "the depth honesty".
- **A chain's `FrameHistory` is fresh per build.** A rebuild carries the persistent *target* but not
  the history, so the frame after a rebuild reports unavailable and accumulation restarts once. That
  is the right answer rather than a limitation — whatever was rebuilt may be the shader, the
  resolution, or the camera wiring — but it is a behaviour to know about while live-coding.
- **`standardRenderer.kt`'s `FrameStableHistory` is untouched.** It still advances on `!=`. Migrating
  the standard renderer's camera uniforms onto `FrameHistory` is the obvious follow-on and was left
  out deliberately: it would change the behaviour of the existing motion-blur pass, which has its own
  figures on a published docs page, in a commit whose subject is a new API.

## Running it

```bash
tools/run_field_tests.py --id four.reprojection.smoke        # 23 PNGs in build/reprojection/
tools/run_field_tests.py --id four.reprojection.smoke --args=--keep
./gradlew jvmTest --tests 'afield.four.targets.FrameHistoryTest'
./gradlew jvmTest --tests 'afield.four.fabricated.FabricatePassChainTest'
```

The loadable demo is `dev_docs/agents/demos/frame_history_reprojection/` — see its `README.md`.

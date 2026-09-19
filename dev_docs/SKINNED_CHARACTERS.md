# Skinned characters — skeletal animation for `afield.four`

Bones and per-vertex weights read out of a glTF, a joint-matrix palette evaluated at a time, and
linear blend skinning into a deformed surface. This page is the measurement method, the numbers with
their populations, and the traps.

## What was there before, and how that was established

A grep over **all of `src/jvmMain` + `src/commonMain`, excluding `vendor/`**:

```bash
grep -rnE 'aiBone|mBones|JOINTS_0|WEIGHTS_0|jointMatrix|inverseBind|skinMatrix|boneWeight' \
  src/jvmMain src/commonMain | grep -v '/vendor/'
```

returned **exactly one line**:

```
src/jvmMain/kotlin/afield/four/fabricated/PathTraceGltf.kt:173:
  require(mesh.mNumBones()==0) { "Skinned glTF meshes need skin deformation before path tracing" }
```

The only mention of a bone in the whole JVM source tree was the path tracer refusing to render one
— the codebase naming a missing capability in its own error message. Meanwhile the vendored
`three.module.js` (0.184.0) has full skinning, so this was a named Three.js parity gap and not an
open question.

What *did* exist: `simpleGLTFSupport.kt` loads glTF through Assimp and samples per-**node** rigid TRS
animation (`MeshInstance.animationSamples`, `buildAnimationSamplesForNode`). The node hierarchy and
the clip sampler were there; bones were not.

## The model, and why it indexes nodes rather than bones

`GltfSkinning.Skeleton` holds **every node in the scene**, pre-order, each with a parent index and
its rest-pose local matrix; a bone is the pair `(nodeIndex, inverseBind)` in a separate list, and
per-vertex joint indices address *that* list.

Assimp hands over `aiBone.mName()` and `aiBone.mOffsetMatrix()` and no parent pointer: the hierarchy
lives in the `aiNode` tree, where a bone is just a node some mesh happens to weight vertices to.
Three consequences made node-indexing the cheap answer rather than the clever one:

- A bone's parent chain can run through nodes that are **not** bones. Blender emits an `Armature`
  wrapper by default; `StressFixture` reproduces it as a node called `rig`, and every one of its
  seven bones hangs off it. Indexing by bone would drop that node's transform.
- The skeleton root is routinely **not** the mesh's node. In both fixtures here the mesh node and the
  skeleton root are siblings.
- Pre-order means a parent is always visited before its children, so composing every node's world
  matrix is one left-to-right pass rather than a recursive walk with memoisation.

The formula, for a vertex `p` in mesh space with influences `(bᵢ, wᵢ)`:

```
palette[b] = global(node(b), t) · inverseBind(b)
p'         = Σᵢ wᵢ · palette[bᵢ] · p
```

## Fixtures: generated, not downloaded

There was no rigged model in the repository. `public/docs/assets/fabricate_demo/ToyCar.glb` and
`dev_docs/agents/fixtures/path_tracing/{quad.glb,quad.gltf,transforms.gltf,uv-transform.gltf,
unsupported-required.gltf}` are the complete glTF population and all are rigid.

Both fixtures here are **written from Kotlin source** as glTF 2.0 text with the buffer inline as a
base64 data URI — the technique `AssimpSceneBridgeTest` already used for its adversarial node scene.
`./gradlew --no-daemon runSkinningEvidence` regenerates both and prints every number on this page; a
test in each suite asserts the committed asset still equals its generator's output byte for byte.

Generating rather than downloading buys the thing that matters: the constants can be **chosen** so
the oracles are closed forms rather than golden numbers.

### 1. `bending_cylinder.gltf` — the well-behaved rig

`RiggedFixture`. An open tube along `+y`, radius `0.5`, height `4`, five rings, twelve segments —
**60 vertices, 96 triangles**. Two bones: `armature → boneLower` (origin) `→ boneUpper` (`y = 2`).
Weight to `boneUpper` is `clamp((y-1)/2, 0, 1)`, so the middle ring is an even 50/50 blend. Two
clips, `bend` (upper joint only, `0° → 60° → 0°` about `+z`) and `curl` (both joints, `25°` and
`55°`).

Every constant is picked so **the bind pose is exact in binary**:

- bind translations of `0` and `2`, so the inverse bind matrices are exact and their product with the
  joints' global bind transforms is exactly the identity;
- an identity first rotation key `(0,0,0,1)`, whose rotation matrix is computed as `1 - 2·(0+0)` and
  `2·(0 ± 0)` — exactly `1` and `0`;
- weights drawn from `{0, ¼, ½, ¾, 1}`, summing to exactly `1.0f`, so `Σ wᵢ·I·p` is exactly `p`.

### 2. `shear_and_cap.gltf` — the adversarial rig

`StressFixture`. **7 vertices, 3 triangles.** A tilted plate weighted entirely to a `shearBone` that
animates a non-uniform **scale**, plus a separate triangle carrying **six** influences spread across
`JOINTS_0` and `JOINTS_1`. The `plate` node is translated `(0, 10, 0)` on purpose. It exists because
the cylinder is too well behaved to test three real things — see *What the second fixture catches*.

## The numbers, with their populations

All from `./gradlew --no-daemon runSkinningEvidence` and
`./gradlew --no-daemon jvmTest --tests 'afield.four.CpuSkinnerTest' --tests 'afield.four.GltfSkinning*'`
(22 tests, all passing), on macOS / aarch64, GraalVM 25.

The whole suite was run once as well, because `PathTraceGltf.importGltf` is the only route into the
path tracer and changing its walk is the sort of thing that breaks every path-tracing figure at
once: `./gradlew --no-daemon jvmTest` — **3980 tests over 395 classes, 0 failures, 0 errors**. (The
one red was `checkTestTreeWrites` reporting three files that were being edited *while* the suite
ran, which its own message says it cannot distinguish from a test write.)
`AgentTranscriptLayoutGuardTest` passes 4/4 over the demo's box frames.

### Bind pose — population: all 60 vertices of the cylinder

| | |
|---|---|
| bit-identical to `assimpToTriMesh` | **60 / 60** |
| worst component difference | **0.0** |

Not "close". An inverse-bind mistake — transposed, composed on the wrong side, or missing the parent
chain — lands a fraction of a percent off, which `assertEquals(a, b, 1e-4f)` waves straight through.

### Deformation — population: all 60 vertices, clip `bend`

| `t` | max &#124;displacement&#124; | tip-ring mean `x` (12 vertices) | closed form |
|---|---|---|---|
| 0.00 | 0.000000 | 0.0000000 | `-2 sin 0°` |
| 0.25 | 0.538173 | -0.5176382 | `-2 sin 15°` |
| 0.50 | 1.067138 | -1.0000000 | `-2 sin 30°` |
| 0.75 | 1.577844 | -1.4142135 | `-2 sin 45°` |
| 1.00 | 2.061552 | -1.7320503 | `-2 sin 60°` |

The tip-ring *mean* is an exact statement, not an approximation: the ring's own `x` and `z` sum to
zero and the palette is affine, so the mean of the transformed ring is the transform of the mean.
The max-displacement floor asserted in the test is `> 1.8`; `2.0` is the closed form for the ring's
centre and the outer vertices reach a little further.

At `t = 1`, `curl` puts the tip-ring mean at `(-2.8148518, 2.1599133)` against a hand-computed
`(-2.8148504, 2.1599118)`. A palette that forgets to compose `boneUpper` through `boneLower` still
passes every `bend` assertion and misses this by `1.07`.

### Weights — population: all 60 vertices

Worst `|Σw - 1|` = **0.0**, 0 unweighted vertices, 0 influences past the four-wide cap.

### Throughput

One JVM, 2000 warm-up plus 20000 timed calls over 60 vertices — a number, not a benchmark:

| | ns / vertex | M vertices / s |
|---|---|---|
| exact inverse-transpose normals (the default) | 56.6 | 17.68 |
| `exactNormalMatrix = false` | 53.0 | 18.87 |

**The exact normal matrix costs 6.4 %**, not the 2× a per-vertex 3×3 inversion sounds like. That is
why it is the default. At 17.7 M vertices/s a 50 000-vertex character skins in 2.8 ms, which is
inside a frame.

## What the second fixture catches

### A non-rigid joint, where the usual normal shortcut is measurably wrong

Almost every skinning implementation transforms normals by the blended 3×3 itself. That is **exact**
for a rigid joint, and every joint in the cylinder is a rotation, so the cylinder cannot tell the
two apart except on its one blended ring. `shearBone` scales `(1,1,1) → (1,2,1)` under a tilted
plate, and the plate is flat, so its own face normal is the ground truth:

| | |
|---|---|
| sheared face normal | `(-0.0, 0.44721362, 0.89442724)` — predicted `(0, 0.4472136, 0.8944272)` |
| exact inverse transpose · face | **1.000000**, on all 4 corners |
| plain blended 3×3 · face | **0.800000** — predicted exactly `2 × 0.4472136 × 0.8944272` |

So the shortcut is not "approximately fine here": it is wrong by a stated number, on geometry
nobody would call pathological.

### The four-influence cap, priced

Three vertices carry six influences, `0.30 / 0.25 / 0.20 / 0.15 / 0.06 / 0.04`. Each cap bone `i`
translates `(i, 0, 0)`, so the displacement is `Σ wᵢ·i` and has a closed form both ways.

| | |
|---|---|
| influences discarded | **6** — two on each of three vertices |
| largest discarded weight | **0.06** |
| kept weights | `[0.33333334, 0.2777778, 0.22222224, 0.16666667]` = the four largest ÷ 0.90 |
| worst `\|Σw - 1\|` after renormalisation, over all 7 vertices | **0.0** |
| displacement | **2.222221**, against the capped prediction `2.0/0.9 = 2.222222` |
| what the cap cost | `2.54 - 2.222 = 0.318` units |

`VertexInfluences.discardedInfluences` and `.largestDiscardedWeight` exist so this is *countable*
rather than silent. **Assimp does read `JOINTS_1`** — asserted explicitly, because if it did not the
discard count would be `0` and every other assertion about this fixture would still pass against a
quietly easier mesh.

### A skinned mesh node's own transform, which the spec says to ignore

glTF 2.0 §3.7.3.2: a skinned mesh node's transform is not applied. `plate` is translated
`(0, 10, 0)`:

| path | plate corners, `y` |
|---|---|
| skinned | `[1.2928932, 2.7071068]` — ignores it, per spec |
| `assimpToTriMesh` (rigid) | `[11.292893, 12.707107]` — applies it, correctly |

Exactly `10.000` apart at both ends. This is why `skinnedGltfToTriMesh` is its own function and not
a flag on `assimpToTriMesh`: they disagree, and both are right.

## The path tracer

`PathTraceGltf.kt:173`'s `require(mesh.mNumBones()==0)` is gone. `PathTraceScene.importGltf` now
takes a `pose: GltfSkinPose?` and traces the **deformed** mesh — deliberately not "ignore the skin
and trace the bind pose", which is the plausible-but-silently-wrong picture the original refusal was
protecting against. An unknown clip name fails the import rather than falling back to rest, and the
import reports which pose it traced.

Cross-checked against the CPU bridge, which is a genuinely independent reader of the same
deformation — the tracer walks Assimp and stages world-space triangles, the bridge packs a 12-float
vertex buffer and builds a `TriMesh`:

```
rest pose   96 triangles, x in [-0.5000, 0.5000]
bend t=1    96 triangles, x in [-1.9821, 0.5000]
CPU bridge  x in [-1.9821, 0.5000], y in [0.0000, 3.4330]
max |difference| = 0.0000000
```

## Traps

- **A TRS cannot carry the rig.** `SkeletonNode.local` is a full `Matrix4f`, not a `TRS`, for the
  reason `MeshInstance.bindMatrix` exists: `Matrix4f.getScale` is `sqrt(...)` per column and is
  therefore always positive, so `decomposeToTRS` flattens a mirrored node to a *pure translation*
  without saying so. See `AssimpSceneBridgeTest` for the measured version of that.
- **A slerp at `t = 0` is not the identity.** `sampleAt`/`keyQuat` return the key itself, bit for
  bit, when the time is at or before a key, rather than slerping with `f = 0`. A slerp renormalises,
  and the bind-pose assertion is measured at exactly `t = 0`.
- **`buildAnimationSamplesForNode` reads only `mAnimations()[0]`.** The rigid path silently drops
  every clip after the first. `GltfSkinning.readClips` reads them all; the cylinder has two, and the
  test asserts both names arrive.
- **Camera auto-framing hides motion.** `MeshView.distance` is in multiples of the mesh's own
  bounding-sphere radius and `VisionGeometryPreview` frames the mesh's own bounds, so a sequence of
  frames at different times each re-frames itself and the cylinder appears not to move at all.
  `SkinningEvidence.anchor()` merges two 2 cm triangles at fixed corners into every mesh to pin it.
- **A box compiles against a different import set.** `posedGltfToTriMesh` has a different *name*
  from `skinnedGltfToTriMesh` rather than being an overload, because an overload pair differing only
  in a trailing lambda resolves in `jvmMain` and goes ambiguous in the K2 REPL — the 2026-09-15
  `audio(voice)` failure mode, which no Gradle gate can see.
- **A hand-computed oracle can be the thing that is wrong.** The first version of the `curl` tip
  prediction, worked to five figures, was `0.0100` off and failed the test. Redone to eight figures
  it agrees to `1.5e-6`. That is the oracle working, not failing — a test that had captured whatever
  the code produced would have learnt nothing either way.

## Frames

`dev_docs/agents/evidence/skinning/`. Seven are CPU-rasterised by
`afield.graphics.VisionGeometryPreview` (AWT-free, no GPU, no window) and regenerated by
`./gradlew runSkinningEvidence`: `bend_t000`, `bend_t050`, `bend_t100`, `curl_t100`,
`blend_bend_curl`, `shear_rest`, `shear_t100`. Three are native Vulkan window screenshots of demo
box 4: `gpu_t000`, `gpu_t050`, `gpu_t100`.

## Rendering it on the GPU: `CpuSkinner`, and the one thing in the way

`GltfSupport.MeshInstance.buffers.vertexData` is plain host memory in the fixed 12-float layout
`[pos.xyz][normal.xyz][uv.xy][color.rgba]`, and `applyToMesh` uploads it. So overwriting the
position and normal fields in place before each upload is the whole of GPU-rendered skeletal
animation, with **no shader change at all** — at 17.7 M vertices/s, which is 2.8 ms for a
50 000-vertex character.

`CpuSkinner` is that. The one thing that makes it non-trivial is that skinning in place is **not
idempotent if you read what you last wrote**: frame two would skin an already-skinned mesh, and the
character would drift apart over a few seconds — slowly enough to look like a physics problem rather
than a bookkeeping one. So the bind pose is snapshotted at construction and every `pose` reads that
snapshot. `CpuSkinnerTest.posingTwiceWithTheSamePaletteGivesTheSameAnswer` checks it **bit for bit**
over all 180 position floats, because the wrong version's first frame of drift is small.

Measured, population all 60 vertices of the cylinder: the buffer holds *exactly* what `skin()`
returned (worst difference `0.0`); two identical poses are bit-identical; a different time moves
most of the mesh; `reset()` restores the load bit for bit; the uv and colour fields are untouched
(`0` of 360 floats changed); and the in-place write agrees with the `TriMesh` bridge exactly.

**One thing was in the way of a per-frame upload**, and it is fixed: `applyToMesh` calls
`defaultBuffer { … }.buffer`, and `defaultBuffer` builds a fresh `BufferParameters` every call.
`BufferParameters.result` — the cache that makes `toBuffer()` return the same allocation twice —
lives on that object, so each call was allocating a new `FrameBufferGroup` and a new `VulkanBuffer`.
Invisible for the once-at-setup call the documentation shows; a leak at frame rate for a 60 Hz
re-upload. `applyToMesh` now takes `reuseBuffers: Boolean = false`, **off by default so no existing
caller changes**, which holds the two `BufferParameters` on the `MeshInstance`.

## Pixels from a real GPU

Run on a live Vulkan renderer through `field_backdoor.py --graphics-context` on 2026-09-19, using
Field's **own** glTF module and its **own** bundled shaders with no shader change at all — the
vertex shader has no idea the mesh is skinned, because `CpuSkinner` rewrote the buffer it reads.

`dev_docs/agents/evidence/skinning/gpu_t000.png`, `gpu_t050.png`, `gpu_t100.png` are native window
screenshots of demo box 4 at `poseTime` 0, 0.5 and 1, and the printed numbers agree with the CPU
path to four decimals:

| `poseTime` | printed | picture |
|---|---|---|
| 0.00 | `tip x -0.5000, top y 4.0000` | a straight tube |
| 0.50 | `tip x -1.4330, top y 3.9821` | a ~30° bend |
| 1.00 | `tip x -1.9821, top y 3.4330` | a ~60° bend |

The normal-coloured debug shading turns across the elbow in all three, which is the visible
evidence that the skinned **normals** reached the shader and not only the positions.

### The bisect that decided what box 4 ships

An animating version of box 4 is three more lines and it *runs* — but `native-screenshot` times out
against it after 15 s, and a demo nobody has looked at is a claim. One variable at a time, same
scene, same renderer:

| loop body | capture |
|---|---|
| nothing | OK |
| `skinner.pose()` every frame, **118 fps** | **OK** |
| `getPropertyRaw()` every frame | times out |
| `inst.applyToMesh(…, reuseBuffers = true)` every frame | times out |

So the *deformation* is innocent — posing this mesh every frame costs nothing and photographs
fine. It is the per-frame re-upload, and separately a per-frame box-property read, that the
renderer's direct window capture cannot get past. Recorded as a finding rather than chased:
diagnosing the capture path is a different job from skinning, and box 4 therefore poses once from
an editable `poseTime` rather than animating.

## Not done

- **No GPU *skinning*.** The deformation runs on the CPU and the result is uploaded;
  `standardRenderer.kt` still has no skinned vertex path and no joint-matrix buffer, and the
  bundled glTF vertex shader is untouched. That is the version that would license a CPU/GPU
  per-vertex agreement assertion, which is the real cross-check and is not here.
- **The animated GPU loop is unphotographed.** It runs — see the bisect — but nothing has looked at
  it in motion, so this page does not claim it looks right.
- **No morph targets.** `require(mesh.mNumAnimMeshes()==0)` is still in the path tracer.
- **No dual quaternion skinning.** Linear blend skinning collapses a limb at 90°; the clip blend in
  `Skeleton.blendedPalette` is componentwise for exactly the same reason, so the two are at least
  consistently wrong in the same way.
- **Rigid node animation is still not evaluated by the path tracer.** Skin deformation is; a node
  that merely moves is not.

## The loadable demo

`dev_docs/agents/demos/skinned_characters/` — three boxes, all run. Drag **`time`** in box 2.

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --port 9101 \
  --workspace dev_docs/agents/demos --document skinned_characters
```

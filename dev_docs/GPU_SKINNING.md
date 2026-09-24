# GPU vertex skinning — the palette on the GPU, and a crowd from one draw call

`SKINNED_CHARACTERS.md` is the CPU half: bones, clips, joint palettes, `CpuSkinner`, and eight
closed-form assertions. This is the GPU half — the deformation moved into the vertex shader, and
the thing that unlocks: many characters animating for about the cost of one.

This page is the measurement method, the numbers with their populations, and the traps.

## What was there before, and how that was established

`dev_docs/agents/demos/skinned_characters/gpu_skin_vertex_.glsl` was byte-for-byte
`GltfSupport.gltVertexShader` — a packed 12-float vertex read through `GL_EXT_buffer_reference`,
no joint attributes, no palette. The demo's own comment said so: *"No shader change and no
joint-matrix buffer: CpuSkinner rewrites the position and normal fields of the packed 12-float
vertex buffer that `applyToMesh` already uploads."*

So the GPU drew a CPU-deformed buffer. Correct, and measured at 17.7 M vertices/s — but it means
every character costs another full deformation **and** another full re-upload, which is why the
demo showed one character.

Two things found by reading before building, both of which made the job smaller than expected:

- **`Mesh.instanceCount` already existed** (`modules/Mesh.kt:171`) and was already passed to
  `vkCmdDraw` and `vkCmdDrawIndexed`. A crowd needed **no renderer change at all**;
  `standardRenderer.kt` is untouched. Nothing in the repository had ever set it to anything but
  1, and no shader had ever mentioned `gl_InstanceIndex`.
- **Buffer-reference addresses are just push constants.** `applyToMesh` does
  `m.push["vertexBuffer"] = vertexData`, and `Introspection.kt:142` parses any
  `buffer_reference` block generically. Adding a joint stream and a palette is additive: declare
  them in the GLSL, push them.

## The model

For a vertex `p` with influences `(bᵢ, wᵢ)`, drawn as instance `k`:

```
palette[k][b] = model(k) · global(node(b), tₖ) · inverseBind(b)
p'            = Σᵢ wᵢ · palette[k][bᵢ] · p            evaluated in the vertex shader
```

### The instance transform rides in the palette

There is no per-instance model-matrix buffer, and that is a decision rather than an omission. A
palette entry is already `global(node) · inverseBind`; placing instance *k* in the world is one
more matrix on the left. The CPU is building that palette anyway, so per-instance placement costs
**one `mul` per bone** and nothing at all on the GPU. It also keeps the normal honest: the model
transform ends up inside the blended 3×3 that the shader inverts and transposes, rather than
applied after it.

### What is on the GPU and what is not

Worth being exact about, because "GPU skinning" is often claimed for less.

| | where | cost |
|---|---|---|
| per-vertex blend | **GPU**, vertex shader | `O(instances × vertices)` — the term that grows |
| palette evaluation, including the per-instance clip blend | **CPU** | `O(instances × bones)` |

Per-instance clip blending is evaluated per instance *on the CPU*. `Skeleton.buildGlobals` is a
sequential parent-before-child walk, and bones are units where vertices are tens of thousands:
moving it to a compute shader would buy a rounding error and cost the ability to read it. At 32
instances of a 2-bone rig the whole per-frame CPU cost measured **0.083 ms**.

## One GLSL, two entry points — which is what makes the numbers mean anything

Vulkan has no transform feedback, so a vertex shader cannot be asked what it computed. The
skinning maths therefore lives in `GpuSkinning.glsl()` — **one** source, transcribed statement for
statement from `GltfSkinning.skin` — and is called from two places:

- `GpuSkinning.skinnedVertexShader`, which draws, and
- `GpuSkinning.verifyComputeShader`, which writes its results to a buffer the CPU reads back.

So `GpuSkinningSmoke` grading the compute output against `GltfSkinning.skin` is evidence about
the shader that actually renders, not about a second implementation written to pass a test.

Three parts of that GLSL look like fussiness and are not:

- It accumulates the weighted **matrix**, not the weighted transformed position. Identical for
  positions; it is the only way to get the blended 3×3 the normal needs.
- A vertex with no influence gets the **identity**, not the zero matrix, so an unweighted vertex
  stays where it is instead of collapsing to the origin.
- A singular blended 3×3 falls back to the plain 3×3 rather than inverting it. A slightly wrong
  normal beats a NaN, which takes the whole triangle out of the rasteriser.

## Verification

### Numerical — `tools/run_field_tests.py --id four.gpu-skinning.smoke`

Population: both committed fixtures, whole meshes, every vertex compared — not a sample, because
a spot check is exactly how an off-by-one at a buffer boundary survives.

| case | population | worst \|Δposition\| | worst \|Δnormal\| |
|---|---|---|---|
| bending cylinder, rest + 3 clips × 5 times | 60 vertices, 2 bones | 4.8e-7 | 1.2e-7 |
| shear-and-cap stress rig, rest + 5 times | 7 vertices, 7 bones, non-uniform scale | 2.4e-7 | 6.0e-8 |
| crowd, per-instance blend of two clips | 64 instances × 60 vertices | 9.5e-7 | 1.8e-7 |

Tolerances are 1e-5 and 1e-4. Many poses agree **exactly**. Two assertions that exist because
their absence is invisible:

- The output buffer is prefilled with a sentinel. A `ComputeModule` whose shader has not finished
  compiling **dispatches as a silent no-op** — the "not valid" branch has its `println`
  commented out — so "never written" must not be able to read as "agrees".
- Instance 0 must differ from the other instances by at least 1 unit (measured: 15.4). A
  `paletteBase` hard-wired to zero would pass the parity check above and render a plausible
  crowd.

### Pixels — `./gradlew runGpuSkinningEvidence`

Every pose is rendered **twice through the identical pipeline** — same window, same camera, same
shaders — once with the GPU skinning and once with `CpuSkinner` posing the buffer and the shader
passing it through (`skinEnabled = 0`, the stock path bit for bit). Then the PNGs are differenced.
That is a comparison a pixel difference can answer, because the only variable is *where the
skinning happened*; differencing against the CPU page's frames would not be, because those come
from a software rasteriser with a different camera.

Result: bend at t = 0.00 / 0.50 / 1.00 and a 50/50 blend of bend and curl all come back
**bit-identical**, max per-channel difference 0.

**And that is exactly what a dead comparison would report**, so there is a control: the same
camera with skinning off and the bind pose in the buffer differs by **3.71 mean levels, max 250**.
The pipeline demonstrably does produce a different picture when asked to, so the zero is evidence
rather than a stuck frame. The control is an assertion — if it ever stops differing, the run
fails.

Frames are in `dev_docs/agents/evidence/gpu_skinning/`.

### Throughput

Machine: Apple silicon, MoltenVK, 120 Hz display (8.33 ms presented frame). Population: **50,000
vertices × 32 instances = 1.6 M vertices**, two influences per vertex, a 2-bone rig.

| | time | rate |
|---|---|---|
| CPU, `GltfSkinning.skin` | 58.8 ms | 27.2 M vertices/s |
| GPU, the same GLSL the vertex shader runs | 0.37 ms | 4,320 M vertices/s |
| CPU palette upload (32 × 2 matrices) | 0.083 ms | — |

**159×**, and that GPU figure is deliberately conservative — see the trap below for why it is a
slope and not a subtraction. The CPU figure is the best of three runs: two consecutive runs of
the identical work measured 58.8 ms and 104.8 ms, a 1.9× spread from machine contention, and a
single sample would have quoted either as "the" rate.

The practical reading: at 60 fps a frame is 16.7 ms, so the CPU path fits **about 9 instances** of
a 50,000-vertex character and the GPU path fits about 1,400.

## Traps

**A vsync-locked frame hides the whole measurement.** The first version of the throughput test
timed one frame with the work and one frame without, subtracted, and divided. It measured 8.35 ms
against 8.30 ms and reported **28,455 M vertices/s** — noise with a unit on it. 1.6 M vertices is
far less than one refresh, so the dispatch is simply invisible. The fix is to repeat the same
dispatch up to 128 times until the frame comes off the floor and take the *slope*; the quoted
slope runs from the first point clearly above the floor to the last, so it includes the
saturation rather than quoting the best stretch.

**`vkCmdDispatch` with zero groups is legal, and `ComputeMesh` logs about it.** Switching the
unused repeats off by setting `x = 0` produced `"ComputeMesh has no work to do"` once per mesh per
frame — 42,000 log lines during a three-second warm-up, enough string formatting to bend the very
frame times being measured, and in the direction that flatters the GPU. Rebuild the module's mesh
map instead.

**Backlog Z7 is narrower than it reads.** Z7 records that the native window capture times out
against a per-frame `applyToMesh`. It does **not** generalise to any per-frame buffer write: the
demo box rewrites its 128-matrix palette every frame, animates at ~120 fps, and photographs
without a timeout. The trap is the per-frame *vertex re-upload* specifically.

Both of these limits are published for readers, alongside the other nine the tree states, on
`public/docs/capture_limits.md`. That page also names the technique for telling a capture that
timed out from a subject that is broken — run a **trusted control** in the same invocation.

**`MeshInstance.applyToMesh` cannot be called from a `main`.** Its buffers come from
`defaultBuffer { … }.buffer` (`src/jvmMain/kotlin/afield/four/buffers/magic.kt:237#buffer`), which
allocates through `src/jvmMain/kotlin/afield/four/buffers/VulkanBuffer.kt:102#renderer` — a
constructor parameter defaulting to `fromContext()`. Inside a box that resolves; from a `main` —
which is where every smoke and the evidence renderer live — it throws
`Cannot invoke "afield.Box.getProperties()"`. `GpuSkinner` therefore owns its own vertex and index
buffers with an explicit renderer, which it wants anyway: it uploads geometry **once**.

**A `CpuSkinner` and a `GpuSkinner` on the same `MeshInstance` fight.** `CpuSkinner.pose` rewrites
`instance.buffers` in place, so a GPU skinner that read the buffer later would skin
already-skinned vertices. `GpuSkinner` snapshots the packed buffer at construction and uploads the
snapshot; `restoreBindPose` and `uploadPosedVertices` switch between them, which is what makes the
A-B pixel comparison above possible.

**The bundled glTF fragment shader never reads `vColor`.** It declares it and then outputs
`vNormalVS.rgb * d`. Fine for one object; for a crowd it renders a hundred identically-shaded
characters no matter what you tint them, and "the crowd is varied" becomes unfalsifiable.
`GpuSkinning.skinnedFragmentShader` exists for that reason.

**Push constants are now 228 bytes** (212 before the dual-quaternion buffers). The stock glTF block is 176; this adds five buffer
references and three uints. Vulkan only guarantees 128, so this is a desktop-class assumption —
the machine here reports 4096. If it ever has to shrink, the three uints fold into one packed word
and the joint and weight buffers interleave into one.

**Opening a demo document dirties it, and the diff is not a mistake you made.** Running
`skinned_characters` through the backdoor without an explicit `save:` op leaves
`document.md` and `metadata.edn` modified: Field's own save on exit writes the synthetic `root`
box into `document.md` (`"boxes" 7` becomes `8`) and reshuffles the child ordering, where a
transcript `save:` op excludes root by design. **Both forms pass `checkAgentDemos`** — verified
both ways on 2026-09-20 — and the corpus already contains both (`alife_walls` has root in its
`document.md`, `path_tracing_medium` does not). So after running a demo to check it, `git
checkout --` the two files rather than committing the churn, and do not read the diff as damage.

## Dual quaternion skinning on the GPU (2026-09-23)

Until this date `SkinningMethod.DUAL_QUATERNION` was CPU-only: the shared GLSL had one palette
type (`mat4`) and a weighted matrix sum. It now has a second blend, `afSkinBlendDual`, in the
**same** `GpuSkinning.glsl()` both entry points include, chosen **per instance**.

### What moved and what did not

| | where |
|---|---|
| the split `M = T·R·S` (Gram-Schmidt) | **CPU**, and it is the oracle's own `DualQuaternionSkinning.split`, called from `GpuSkinning.packDualPalette` |
| antipodal pivot, sign flip, weighted sum of dual quaternions and residuals, normalise, apply | **GPU** |

So the only arithmetic the GPU adds is the blend, and a disagreement with
`DualQuaternionSkinning.skin` can only be a disagreement about blending.

### Layout

- `AfSkinDual { vec4 v[]; }` — **5 `vec4` per (instance, bone)**, 80 bytes, slice base
  `(instance·boneCount + bone)·5`: `real.xyzw`, `dual.xyzw`, then the residual 3×3 `S` as three
  columns in JOML order (`m00 m01 m02 _`, `m10 m11 m12 _`, `m20 m21 m22 _`). Rigid joints get the
  identity. Five and not four because a degenerate joint (an axis scaled to nothing) hands its
  whole 3×3 over as the residual; the upper-triangular Gram-Schmidt residual alone would fit in 14
  floats, the degenerate one does not.
- `AfSkinMethods { uint v[]; }` — one word per instance: `0` linear, bit 0 dual quaternion, bit 1
  "this palette has a non-rigid joint, blend `S`". Bit 1 is the oracle's `anyNonRigid`, per
  instance, so a rigid rig skips the residual exactly as the CPU does.
- Push constants: two more buffer references, **212 → 228 bytes** on the draw shader.
- API: `GpuSkinner.method` for every instance, or `uploadPalettes(palettes, methods)` per
  instance; `GpuSkinner.methodWords()` reports what the shader will see.

`afSkinBlendDual` returns the equivalent matrix `[R·S | t]` so `afSkinPosition`/`afSkinNormal` are
shared. That is exact, not convenient: `R` is orthonormal, so `(R·S)⁻ᵀ = R·S⁻ᵀ`, which is the
oracle's residual-inverse-transpose-then-rotate. The conventions transcribed from the oracle, each
of which a test below would catch if changed: pivot = highest weight with a strict `>` (first wins
a tie, which the fixture's 0.5/0.5 ring is), sign by the real-part dot, `S` blended **without** the
sign, `DualQuaternionf.normalize` including `dual -= dot(real, dual)·real` and its identity
fallback, translation `2·(dual·conj(real)).xyz` (Hamilton), rotation `q v q*` as the generated
`transformQuaternionVector3`.

### Verification — `GpuSkinningSmoke`, through the production shader

All numbers from `tools/run_field_tests.py --id four.gpu-skinning.smoke --port 8472`, three passing
runs on 2026-09-23 (Apple silicon, MoltenVK); correctness numbers identical in all three.

| case | population | worst \|Δpos\| | worst \|Δnormal\| |
|---|---|---|---|
| DQ, bending cylinder, rest + 3 clips × 5 times | 16 poses × 60 vertices, 2 bones | 9.5e-7 | 2.0e-7 |
| DQ, shear-and-cap, rest + 5 times (4 took the residual path) | 6 poses × 7 vertices, 7 bones | 2.4e-7 | 6.0e-8 |
| mixed crowd, `twist`, even LBS / odd DQS, one dispatch, read from a `GpuSkinner`'s own buffers | 64 instances × 60 vertices | 2.9e-6 | 3.9e-7 |
| synthetic rig, 1–4 influences, ±170° twins, scale + shear | 3 poses × 480 vertices, 5 joints | 4.8e-7 | 4.2e-7 |

Tolerances unchanged: 1e-5 and 1e-4. The controls, each a floor on a measured number so a shader
quietly doing linear blending under the DQ word cannot pass:

| control | measured | floor |
|---|---|---|
| candy wrapper off the GPU, `twist` 180°, y = 2 ring | LBS min radius **0.0%**, DQS **100.0%** of rest | LBS < 5%, DQS within 5% |
| worst \|LBS − DQS\| at 180°, 60 vertices | 0.500 | ≥ 0.15 |
| largest \|DQS coordinate\| (readback is the tube, not zeros) | 4.0 | ≥ 1.0 |
| shear rig, residual bit cleared on the 4 non-rigid poses: shader must be *wrong* | 2.71 | ≥ 0.01 |
| mixed crowd: DQ half vs the linear oracle / linear half vs the DQ oracle | 0.490 / 0.489 | ≥ 0.15 each |
| synthetic rig: oracle antipodal flips / non-rigid joints per pose | 158 / 2 | > 0 each |

**Which test catches what, measured by mutation.** Replacing the antipodal sign with `1.0` in the
shader left every committed-fixture check green — the cylinder's two joints never sit on opposite
hemispheres, and the shear rig's multi-influence vertices move by translations only, for which DQ
and LBS coincide — and failed only the synthetic rig at its 170° pose (`worst |dpos| 4.25`). That
is why the synthetic rig exists; do not delete it as redundant with the fixtures. (The tie-break
convention was not mutation-tested.)

`GpuDualPalettePackingTest` (jvmTest, no GPU) holds the packing: each packed joint rebuilt the way
the shader rebuilds a single influence gives back the packed matrix to 1.2e-7 over six cases
(identity, translated rotation, 180°, scale on a rotation, shear, reflection).

### Pixels — `./gradlew runGpuSkinningEvidence` (or `--args=--twist-only`)

- `gpu_dq_twist_t100.png` vs `cpu_dq_twist_t100.png` — GPU DQ against a `CpuSkinner` running the DQ
  oracle, same pipeline: **bit-identical**, mean 0.0000 levels, max 0.
- control: the GPU *linear* frame of the same pose against the GPU DQ frame: **1.30 mean levels,
  max 250** (floor 0.1). So the zero is not a stuck frame or an ignored method word.
- `twist_lbs_vs_dqs_t067.png` / `_t100.png` — two instances, one draw call, left linear (pinched
  to a point at 180°), right DQ (keeps its radius). Looked at; it is the candy wrapper.
- The full evidence run was redone as well: every previously committed frame came back
  byte-identical.
- Trap met on the way: a `CpuSkinner` snapshots its bind pose from `instance.buffers` **at
  construction**, and the linear `CpuSkinner` had left its last pose there. The DQ `CpuSkinner`
  built after it skinned an already-posed mesh — 5.67 mean levels of "GPU/CPU disagreement" that
  was the CPU being wrong. `twist()` resets the linear one first.

### Throughput — narrated, not asserted

Population: 50,000 vertices × 32 instances, two influences, the 2-bone rig; the verify compute
shader, not a draw. Measured as a **paired** sweep: three rounds, each timing both methods at 32
and 128 dispatches, slope from the fastest time per depth. Three runs: DQS cost **1.46×**, **1.37×** and
**1.38×** the linear cost per dispatch (0.706 vs 0.482 ms; 0.682 vs 0.494 ms). The first attempt
ran two full sweeps back to back and reported DQS at **0.47×** linear with a non-monotonic sweep —
two moments, not two shaders — which is why it is paired now. The palette upload with both layouts
and the CPU split measured 0.065–0.098 ms per frame for 32 × 2 joints, against 0.044–0.048 ms
linear-only. Printed by the smoke, asserted by nothing.

### What GPU DQS does not carry

- **Clip blending is still a matrix lerp.** `blendedPalette` lerps each joint's matrices before
  skinning sees them, so DQ skinning cannot undo it: `GpuDualPalettePackingTest` measures `twist`
  180° lerped 50/50 with rest and DQ-skinned at a tip ring radius of **0.0000** (rest 0.5),
  against 0.5000 for the unblended twist. The fix is on the clip side — blend joint rotations
  (slerp, or `DualQuaternionf.sclerp`) rather than matrices — and it is not done.
- **Four influences**, as for linear blending.
- **The residual is blended linearly**, as in the oracle. A shear on a twisting joint gets DQ's
  rotation and LBS's shear; the GPU matches the oracle's choice rather than improving on it.
- **Bulge.** DQS trades LBS's shrink for a bulge (the oracle's mean edge ratio 1.051 against 0.950
  over the cylinder's 288 edges under twist); it is the faceted waist in the frames above.

Three.js parity (from memory, not checked tonight): core `SkinnedMesh` is linear blend only, so
this path does something a Three.js port of the demo would have to write itself.

## What this does not do

- **Dual-quaternion skinning on the GPU exists as of 2026-09-23** — see the section above for what
  it does not carry. Box 5 of the demo is the CPU candy wrapper; box 7 is the same twist on the
  GPU, both methods in one draw.
- **No GPU palette evaluation.** See the table above: it is the cheap half.
- **`standardRenderer.kt` still has no skinned path.** The skinned vertex shader is a `Module` you
  build yourself, as the demo box does. Wiring it into Fabricate's g-buffer is a separate job.
- **Nothing culls or sorts the crowd.** 64 instances at 60 vertices is not a workload; the
  throughput number above is the honest one and the crowd frame is a picture.

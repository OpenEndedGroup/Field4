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

**`MeshInstance.applyToMesh` cannot be called from a `main`.** Its buffers come from
`defaultBuffer { … }.buffer`, whose renderer defaults to `fromContext()`. Inside a box that
resolves; from a `main` — which is where every smoke and the evidence renderer live — it throws
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

**Push constants are now 212 bytes.** The stock glTF block is 176; this adds three buffer
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

## What this does not do

- **No dual-quaternion skinning on the GPU.** `GltfSkinning.SkinningMethod.DUAL_QUATERNION` is CPU
  only; `GpuSkinning.glsl()` is linear blend. The candy-wrapper collapse a twisted joint suffers
  under linear blending is therefore still a CPU-only fix, and box 5 of the demo is still the
  place to see it. The oracle for a GPU DQS already exists, which is the whole reason this one
  was verifiable in a night.
- **No GPU palette evaluation.** See the table above: it is the cheap half.
- **`standardRenderer.kt` still has no skinned path.** The skinned vertex shader is a `Module` you
  build yourself, as the demo box does. Wiring it into Fabricate's g-buffer is a separate job.
- **Nothing culls or sorts the crowd.** 64 instances at 60 vertices is not a workload; the
  throughput number above is the honest one and the crowd frame is a picture.

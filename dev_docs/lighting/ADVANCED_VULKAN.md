# Next Vulkan experiments — implementation record

Started 26 September 2026, from `dbcd95703`, on a0 (RTX 4090 / NVIDIA 610.43.02).
The three checkpoints have real Vulkan checks and editable Starting Points. See the recorded
results and explicit limits below.

## Scope and order

1. Extend the existing path tracer's output contract for Ray Reconstruction: unaccumulated
   linear radiance, primary-surface BRDF guides, depth, unjittered motion and specular hit distance.
   Reuse `DlssReconstruction`; retain raw/progressive views and explicit temporal resets.
2. Train a small learned function on the GPU from Field images, keeping training and inference
   allocations GPU-resident and measuring the whole operation. Retain frozen-model inference.
3. Add a general cluster acceleration-structure API for geometry generated in Field. Accept
   caller-owned, addressable GPU vertex/index buffers and explicit bounded cluster descriptions.
   A compute producer may update positions and topology before recording a build. Keep uploads,
   generation, cluster builds, BLAS/TLAS builds and tracing ordered on Field's graphics queue.
   Generated examples must use that public API, not hardcoded native/sample geometry.

Slang 2026.18.3 is the shared compiler pin (official release 25 September), including
its reverse-mode autodifferentiation fix. RTXNS source stays independently pinned; upstream's
sample compiler choice is recorded separately from Field's tested compiler choice.

## Existing implementation seams

| Area | Existing owner | Change |
| --- | --- | --- |
| Path tracing | `fabricated/PathTracingRenderer.kt`, `resources/afield/four/pathtracing/trace.comp` | Optional raw frame and guide output from the actual integrator, synchronized with its camera/jitter |
| NGX | `reconstruction/DlssReconstruction.kt`, `native/field-ngx/field_ngx.cpp` | Optional real specular distance; keep one FFM boundary and typed resources |
| Learning | `neural/LearnedShader.kt`, `ShaderTraining.kt`, `resources/afield/four/neural/learned.slang` | GPU training context and compatible weight ownership |
| Toolchain | `optional/lighting/toolchains.json`, `tools/lighting/build_learned_shader.py`, existing `SlangCompiler` | One checksummed compiler pin; no downloads during startup |
| Ray geometry | `modules/AccelerationStructure.kt`, `device/Device.kt` | Feature negotiation, cluster resource owner and borrowed producer buffers |
| Command flow | `Target`, `ComputeModule`, `Renderer` | Reuse command submission and deferred retirement |

## Acceptance boundaries

- Each feature remains optional and exposes capability/availability failure without breaking
  baseline startup. A compiler platform is not a hardware-capability claim.
- Full-render RR testing starts with diffuse/glossy opaque geometry. Transmission, volume,
  defocus and arbitrary appearance edits require explicit correspondence rules and evidence;
  unavailable guides must not be replaced with invented constants.
- Cluster limits, layouts, addressing, allocation bounds and topology changes are checked.
  Static topology can use templates later, but generic computed triangle input is the core API.
- Validate with real Field Starting points, runtime numerical comparisons, validation layers,
  captures and timings. Ordinary docs builds retain NVIDIA captures.

## Results

- **Full path-traced RR:** `PathTraceFrameOutput` adds seven images from a 96-byte raw per-pixel
  record. `PathTracedReconstruction` orders trace → conversion → existing NGX. Private NGX ABI 4
  adds the optional real specular hit-distance image. Both compute-BVH and ray-query primary guides
  reproduce a known plane and +2.1916122 px camera translation. Raw/RR/reference comparisons,
  moving camera, cut, two resolutions and deferred destruction pass with zero validation errors.
  The ordinary RTXDI `RayReconstructionSmoke` also passes after the ABI change.
- **Computed geometry:** `ClusterGeometry` consumes borrowed GPU positions, local uint indices and
  32-byte descriptions, validates them on the GPU, builds CLAS and one addressable cluster BLAS.
  `RayTracingInstance` now accepts an address-only owner; the ordinary Vk handle interface remains.
  `ClusterGeometryDemo` uses a real editable compute producer. The 768-cluster/98,304-triangle test
  passes motion, topology changes, bad descriptions/indices/nonfinite vertices, native layout and
  deferred cleanup. No template mesh, sample renderer, JNI or extra SDK is used.
- **GPU learning:** `GpuShaderTraining` uses native Slang reverse-mode differentiation, cooperative
  outer products/reductions, FP16 loss-scaled gradients and FP32 Adam master state. Actual teacher
  RGB is sampled on the GPU. The initial smooth teacher's 4,001 updates brought held-out
  time-0.173 RGB MSE from 0.34403 to about 0.00034; current media and measurements use the
  striped teacher recorded below. A numerical first-step Adam regression covers signs, second
  moments and packed weights.
  A checkpoint converts training-optimal matrices into the existing 3,456-byte frozen format;
  reloaded inference differs by at most 0.001465 RGB in the recorded run. Source-skipping and reset
  checks pass. The frozen scalar/cooperative `LearnedShaderSmoke` and shared live Slang compiler
  smoke also pass with the new compiler.

The retained `results/advanced-vulkan.json` and each public page's `measurements.json` contain GPU
percentiles and allocation sizes, not FPS claims. GPU power/clock management and Wayland presentation
remain enabled; no driver, package or clock policy was changed. Capture readback and PNG encoding are
excluded from the timing intervals; full-path RR measures an additional continuous warm interval.
NGX's allocation query returns zero on this runtime: its internal memory is **unknown**, not zero.
Builder memory excludes caller geometry and output; learner memory excludes the teacher and
comparison target. No memory or speed comparison against ordinary BLAS is claimed.

## Environment and dependencies

- Linux/CachyOS, KDE Wayland, NVIDIA RTX 4090, driver **610.43.02**, Vulkan **1.4.341**.
- Java **25.0.2+10-jvmci-b01**, LWJGL **3.4.3**. Existing FFM NGX bridge only; no new native ABI
  for cluster construction or cooperative vectors.
- `VK_NV_cluster_acceleration_structure`, `cooperativeVector` and `cooperativeVectorTraining` are
  separately reported **supported and enabled**. Ray query and ray-tracing pipelines are enabled.
  Graphics queue family 0 offers graphics/compute/transfer; these new adapters use Field's one
  graphics queue, not the separate optical-flow queue.
- Slang **2026.18.3**, official release 25 September 2026; platform checksums in
  `optional/lighting/toolchains.json`. RTXNS source remains
  `f309ae68677a67060cc6ded2d0af925a3931435f`; its sample compiler remains 2026.10. Training uses
  Slang's native autodiff, not the older SDK autodiff extension, whose definitions conflict with it.
- NGX/DLSS **310.9.1**, source `374959484e79a640feaba44c93ac8cfb0a03f5b5`, unchanged.
- Cluster API/layout research: Vulkan NV extension revision 4 and NVIDIA sample source
  `e23df9008456946e94be6009f024747a05f8e86e` (`nvpro-samples/vk_animated_clusters`, source research
  only; no binary or renderer is bundled). Field’s implementation is the Vulkan API adapter in this tree.

The raw capability report is generated by `tools/lighting/capture_advanced_capabilities.kt`, through
a child of the real Starting Point renderer, and included in `results/advanced-vulkan.json`.
The Linux compiler was executed. Other platform archive pins and baseline builds are retained;
Windows/macOS hardware execution was not performed in this session.

## Reproduce

Prepare optional components explicitly, using their existing mechanisms:

```bash
./gradlew buildLearnedShaders
bash tools/lighting/build_ngx.sh
```

Use a fresh agent-owned Field JVM after source changes. On this checkout the private `src/personal`
launch workaround remains `-x compilePersonalKotlinJvm -x personalJar`; omit those exclusions in a
checkout without those tasks. No private source is edited.

```bash
PORT=9816 XDG_RUNTIME_DIR=/run/user/1000 WAYLAND_DISPLAY=wayland-0 DISPLAY=:0 \
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json AFIELD_SKIP_WORKSPACE_PERSIST=1 \
./gradlew runJvm -x compilePersonalKotlinJvm -x personalJar \
  --args='--workspace /home/marc/Field4/build/lighting-experiment' \
  > build/neural-tools/advanced-server-9816.log 2>&1
```

After the server is listening, run each entrypoint **one at a time**, through the backdoor:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --attach-existing --port 9816 --timeout 240 \
  --command 'run-jvm-main afield.four.modules.ClusterGeometrySmoke --arg=--clip' \
  > build/neural-tools/clusters-final.json
```

Use the same invocation for `afield.four.neural.GpuShaderTrainingSmoke --arg=--clip`
(`gpu-learning-final.json`) and `afield.four.reconstruction.PathTracedReconstructionSmoke --arg=--clip`
(`path-rr-final.json`). Markers are respectively `COMPUTED CLUSTERS CHECK DONE`,
`GPU SHADER TRAINING CHECK DONE` and `PATH RR CHECK DONE`; require exit 0 and no VUID errors.
`AdvancedVulkanFallbackSmoke` forces both optional feature families off and verifies useful scalar
inference plus clear feature-gate failures. This is a disabled-feature test on NVIDIA, not an actual
non-NVIDIA hardware result.

```bash
uv run --project . --group backdoor python tools/lighting/check_advanced_starting_points.py \
  --port 9816 --server-log build/neural-tools/advanced-server-9816.log
uv run --project . python tools/lighting/publish_advanced_captures.py
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

The live Starting Points each passed one complete final editor sequence: computed geometry **22**
commands, GPU learning **21**, path RR **19**. These exercise real preambles, shader reload/camera cut,
resize, relaunch and Stop, with zero Vulkan validation errors. They are not repeated-run statistical
claims. `tools/lighting/build_advanced_starting_points.py` regenerates the YAML from editable sources;
`AgentTranscriptMaterializer` writes saved Starting Point documents without executing GPU code.
All three are registered **COMPILE_ONLY** in portable transcript execution checks. Docs build hooks
never run the NVIDIA publisher or prepare optional SDKs, including on macOS.

## Limits and useful next work

- Cluster inputs must already be partitioned into bounded triangle clusters. The core API accepts
  arbitrary GPU-produced buffers; the terrain viewer is one example. Dynamic capacities, template
  reuse, partial rebuilds, LOD/streaming and mesh partitioning are future work. Compute ray-query
  traversal of the cluster BLAS has not been certified here; the example uses an opted-in ray pipeline.
- Full-path RR currently requires an opaque pinhole surface signal: no glass/coverage, volumes, fog
  or defocus. Camera motion is supported; geometry is the immutable scene snapshot. The existing
  progressive tracer remains available for effects outside that contract. Recreate for size changes.
- GPU learning is a fixed small screen-space RGB network, with one normalized scalar parameter.
  Uniform/texture/state changes need explicit resets unless handled by the Starting Point's shader
  digest. FP16 atomic accumulation is not bitwise reproducible. Reject/inspect poor fits; a successful
  checkpoint is not quality validation. No general differentiable renderer or online graph runtime.
- Early development caught a wrong cluster index stride (12 instead of 4 bytes) and a missing square
  in Adam's second moment. Both were fixed; GPU native-record and first-step numerical regressions
  now cover them. Initial failing local logs remain under `build/neural-tools`; only passing output
  is published. A mixed JVM-main/box backdoor session also hit an existing empty-session NPE; an
  explicitly named document was used for the independent Slang box check.


## Portable verification for this change

- `jvmTest` filtered to `ReconstructionTest`, `TriangleClusterLayoutTest` and
  `AgentDemoRegistrationTest`: **11 tests, zero failures/errors**.
- `checkAgentTranscripts`, `checkAgentTranscriptIndex`, `checkAgentTranscriptLayouts`: passed.
- `DemoTranscriptExecSmokeTest` filtered to the three new transcripts: **3 compile-only demos passed**.
- Python optional compiler transaction/cache checks, NVIDIA docs retention, demo registry and
  generated corpus checks: **19 tests passed**.
- Shared Slang runtime check: real vertex/fragment SPIR-V and compute-to-GLSL compilation passed;
  an explicit nonexistent compiler override produced the expected failure.

This is targeted verification, not a claim that the complete repository certification suite was run.
The feature-disabled test was repeated after moving capability guards ahead of resource allocation.

The existing `RayTracingGpuAudit` was also run with `--stage=seams --stage=base --stage=camera
--require-ray-query --output=build/neural-tools/base-path` through `run-jvm-main`. All three stages
passed on both COMPUTE_BVH and RAY_QUERY, with exit 0 and no VUID errors. This covers the ordinary
tracer with frame-guide output disabled: HDR/accumulation, surface energy, glass/absorption/Fresnel,
glTF color/normal/coverage textures, replacement/cleanup, camera optics and shared-edge coverage.
The recorded base stage includes cold textured-material pipeline compilation; its wall time is not
a per-frame rendering cost. Stage results are retained in `results/advanced-vulkan.json`.

## Purple-stripe GPU teacher — 27 September 2026

The GPU learning Starting Point and `teacher.frag` now use the same discontinuous magenta
band as `learned_from_module`: normalized x in `[-0.12, 0.12)`, RGB `(0.95, 0.04, 0.8)`.
The public page displays the complete matching GLSL, with folded declarations, and its
1080p comparison PNG, learned image and 120-frame clip were recaptured in Field.

One fresh `GpuShaderTrainingSmoke --clip` run on the RTX 4090 / 610.43.02 / Wayland
passed the unchanged convergence threshold, first-step Adam check, frozen export/reload,
source-pass skipping, reset and cleanup, with no Vulkan validation errors. The same
4,001 updates reduced held-out t=0.173 MSE from 0.350055999 to 0.001858570. The actual
source stripe and abrupt boundary are asserted in the check. Edge-band MSE was 0.0233128,
away-from-edge MSE 0.000974341, maximum channel error 0.409219. The edge band covers
3.9583% of pixels, within 0.02 normalized x of either edge. The frozen reload's maximum
channel disagreement with the live model was 0.0009765625.

Training eight 256-sample batches measured 0.188352 ms median / 0.199680 ms p95 over
100 samples, with automatic GPU clocks; this is not a controlled performance comparison
with the earlier smooth run. Model capacity, feature encoding and update budget were
unchanged. The live `gpu_shader_learning` Starting Point then passed all 21 editor commands,
including shader reload, resize, relaunch and Stop, with no VUIDs. Materialization refreshed
the downloadable Starting Point from that same transcript. The verified publisher retains
these metrics in `results/advanced-vulkan.json` and public `measurements.json`.

Reproduction used `gpu-stripe-server-9832.log`, `gpu-learning-final.json`,
`gpu-stripe-editor-check.log` and `gpu-stripe-materialize.json` under `build/neural-tools/`.
The earlier geometry/RR editor results were retained when merging this targeted editor check.

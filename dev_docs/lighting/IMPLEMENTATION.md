# Vulkan direct-lighting experiment

This change includes the baseline ray-query shadow/reference pass and the optional pinned RTXDI
initial, temporal and spatial resampling implementation detailed below.
Field remains the renderer. No SDK binary, DXC, JNI shim or second device is required to build or start Field.

## Implementation map

| Facility | Existing owner | Change |
| --- | --- | --- |
| Device and extension chain | `four/device/Device.kt`, `PhysicalDevice.kt`, `Instance.kt` | Separate AS/query/pipeline enablement; supported/enabled JSON report in `RayDeviceSupport.kt`. Feature-based on all OSes. |
| Indexed BLAS / instance TLAS | `four/modules/AccelerationStructure.kt` | AS no longer depends on pipeline support. Compute-compatible build barrier. |
| Scene snapshots and retirement | `four/lighting/OpaqueRayScene.kt`, `Renderer.cleanup` | New immutable indexed geometry and stable instance IDs; static BLAS cache, explicit fresh TLAS on edits. |
| Raster primary visibility | `four/fabricated/standardRenderer.kt` | Existing G-buffer, geometry, camera and source lighting remain the producers. |
| Fabricate adapter | `four/fabricated/FabricateDirectLighting.kt` | Adapt attachments and expand indexed snapshots into existing pulling meshes. |
| Experimental interface | `four/lighting/DirectLighting.kt` | Ordinary surface textures in; FP32 raw lighting buffer and RTT out. Source/direct/shadow modes, diagnostics and transactional same-ABI reload. |
| Descriptors and compilation | `ComputeModule.kt`, `Introspection.kt`, `ShaderAbi.kt` | Reuse GLSL/shaderc/SPIRV-Cross and dynamic AS descriptors. |
| Scheduling | `FabricateFrameGraph`, `RenderTarget.preTargets` | Append after source producers, on the existing graphics queue. No cross-queue ownership. |
| Timings and readback | `LightingGpuTimer.kt`, existing command/fence/buffer APIs | Per-image timestamps, raw FP32 readback and smoke PNGs. |
| RTXDI history | `four/lighting/RtxdiEngine.kt` | Two surface pages and three SDK reservoir slices; camera/revision invalidation, no color filter. |
| Native convention | LWJGL JNI; Java 25 FFM exists for Steam Audio and other optional libraries | No new native bridge for this milestone. |

## Baseline scope and shared contracts

A single one-sided rectangular emitter, uniform area samples, Lambertian RGB, finite opaque shadow rays.
The estimate is `albedo/pi * radiance * area * cos(surface) * cos(emitter) / distanceSquared`, averaged over samples.
The rectangle's full edges determine its area and `cross(edgeU,edgeV)` points toward its emitting side.
The origin offset and shortened endpoint are in world units. BLAS triangles are opaque, indexed uint32,
XYZ float32, stride 12; raster expansion computes flat geometric normals. A mask-zero dummy instance
represents an empty occluder set. No refit is attempted: a transform changes TLAS, topology changes BLAS.
Commit fences complete builds before publishing the new scene; old objects retire through Field's cleanup queue.
Edits are deliberately synchronous and can stall; static frames do not rebuild anything.

All methods/mutations run on the render thread between frames. Shader reload compiles a candidate and
compares reflection before replacing the pipeline. Layout changes require a new DirectLighting object.
Raster and ray scene edits must be committed together; the demo does this for transforms.
Resolution belongs to an instance of the pass; recreate at a new size. Window resizing merely rescales its presentation.
This first patch is mono, intended for the primary render target, with no stereo history semantics.

Position is world XYZ and conventional Vulkan depth in W (0 near, 1 far, clear 1). Normals are world XYZ;
albedo is linear RGB, with no latent pigments or implicit color transform. Inputs are point sampled.
Fabricate motion is currentUV minus previousUV, UV units, Y down, no jitter. The existing G-buffer has
quantitative material parameters, not categorical IDs. This patch does not claim material-ID/emission or
per-pixel effect-weight attachments. `effectWeight` is a separate global scalar (0=source, 1=effect);
it never uses coverage alpha. Raw buffer W is visibility (-1 for background); SOURCE preserves input alpha; presented alpha is opaque.
The baseline has no history. The optional SDK continuation below adds reuse, light mappings and
reservoir diagnostics; neither path implements denoising or PBR.
Black input stays black; HDR RGB is not clamped by the lighting shader. PNG display conversion is explicit
Reinhard plus gamma, while tests read raw FP32 data.

## RTXDI SDK inventory

Inspected sample repository `a6efab966b7c3b272da0461578eb56ac61c7cbff`, library submodule
`f12037fa8e97ebc08e9e3edfd2de528ed1772a4b`. The implementation uses its DI application bridge
and estimator, uint32 light IDs and FP32 weights, stable light maps, and validated reprojection.
Categorical material/object attachments remain a limitation; diffuse compatibility is checked explicitly.

Host helper inventory from this revision: `ReSTIRDIContext` constructs reservoir pitches and runtime
parameters; `SetFrameIndex` and `SetResamplingMode` select buffer indices; getters supply initial,
temporal, spatial and shading parameter structs. `FillNeighborOffsetBuffer` is needed only for spatial
reuse. `RtxdiUtils` contains reservoir layout/size helpers. ReGIR, RIS presampling, GI and PT contexts
are unnecessary for this DI integration. Small Kotlin host calculations replace these host helpers;
there are no native CPU SDK calls. Layout tests check the host/shader contract.

Upstream code is under the NVIDIA RTX SDK license, not assumed MIT. Source notices, object-code
redistribution and other SDK distribution terms must be retained/reviewed before vendoring. All downloaded
SDKs stay under ignored `build/lighting-experiment/upstream`; this patch redistributes none of them.

## Provenance

- Field starting revision after pull: `d72363a9007b5a959d3f0773645f2ff9cc4ff564`.
- Application Java: GraalVM CE 25.0.2+10; wrapper bootstrap chooses Amazon Corretto 17.0.19.
- LWJGL 3.4.3; Gradle 9.5.1; Field's existing shaderc compiler and SPIRV-Cross reflection.
- RTXDI Donut `2c1077673edb0e0d814c202e6ce8e502d245c2da`; NRD `2cd553031abec76bc4e109f20ef40a23ef0645c7`.
- RTXDI assets `1da7b749ef0e3fb606ab46dde8e5bfbacd078a02` (Arcade LFS assets only fetched).
- DXC package `v1.8.2505.1/linux_dxc_2025_07_14.x86_64.tar.gz`; binary identifies itself
  `libdxcompiler.so: 1.9(dev;4950-b106a961); libdxil.so: 1.9`.

The sample builds with CMake/Ninja, `DONUT_WITH_DLSS=OFF`, target `MinimalSample`. This pinned revision
accepts `--vk`, whereas the repository README's `--device.vk` fails with "Unknown command line argument".
Logs live under `build/lighting-experiment/rtxdi-{cmake,build,run}.log`. Sample execution is separate
from Field acceptance and never substitutes for its shadow/readback tests.

## Portability

Ray capability selection does not depend on GPU name or OS. Disabled or unavailable ray queries retain
SOURCE and UNSHADOWED modes and ordinary Field startup. The SDKs are optional research checkouts only.
KosmicKrisp and MoltenVK have not been executed in this session. Their Metal hardware/core-version
claims cannot establish AS/ray-query support: run the same physical/logical report there before enabling.
No new Metal backend or software BVH is part of this experiment.

## Reproduce this machine's checks

The standard smoke catalog entry is `four.direct-lighting.smoke`. On this checkout its automatic
server prewarm encountered existing compile errors in the ignored personal source
`src/personal/kotlin/paths/pointgame.kt` (`mDecayed` and scalar/vector operations). That private file
was left unchanged. To verify core Field, the following isolated server excluded only the personal
compilation and jar tasks; it still built and ran Field's real JVM/JS application:

```bash
mkdir -p build/lighting-experiment
# Run in a graphical session; inherit its DISPLAY/Wayland and XAUTHORITY.
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json PORT=9724 \
  ./gradlew runJvm -x compilePersonalKotlinJvm -x personalJar \
  --args="--workspace $(pwd)/build/lighting-experiment"
```

In a second terminal:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --attach-existing --port 9724 --timeout 420 \
  --command 'run-jvm-main afield.four.lighting.DirectLightingSmoke --timeout 300'
# Same test with the physical GPU retained but AS/query/pipeline disabled:
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --attach-existing --port 9724 --timeout 420 \
  --command 'run-jvm-main afield.four.lighting.DirectLightingSmoke --arg=--fallback --timeout 300'
# Contract and GLSL/SPIR-V layout tests, actually rerun:
./gradlew jvmTest --rerun --tests afield.four.lighting.DirectLightingTest
```

Run verification commands sequentially. `--arg=--clip` adds 120 deterministic rendered frames
under `gpu/clip/`; encode them with:

```bash
ffmpeg -framerate 30 -i build/lighting-experiment/gpu/clip/%04d.png \
  -c:v libx264 -pix_fmt yuv420p -movflags +faststart build/lighting-experiment/moving-light.mp4
```

Reports from the successful checks are preserved in `results/`. The fallback JSON distinguishes
supported physical features from disabled logical features. Timings cover compute visibility/shading
only, with validation enabled, 16 warm-up frames and 100 measured frames at 256×192/32 samples.
No full-frame FPS claim, AS-build GPU timing or allocator-total memory claim is made.
The reported working bytes count only raw/output FP32 storage. Static AS counts and explicit
transform/topology tests establish when builds occur; they are not a build-time benchmark.

The initial nearby-seed XOR construction produced correlated spatial sample sets and understated
ensemble uncertainty. Hashing the seed before combining it with the pixel index corrected that test.
The current test averages 12 independent seeds at 8 and 256 samples and records combined standard
error, with a six-standard-error acceptance threshold. This is a scene-mean estimator regression,
not an exhaustive estimator proof. Dark-surface assertions exclude preserved background pixels.

Live document detachment exposed missing destruction of AS handles and the timestamp query pool.
Both now register handle-only cleanup with Field's device-owned AutoDisposer; explicit close and
owner shutdown share one token. BLAS/TLAS handles register after their storage buffer so they retire
before the backing storage at device shutdown. Failed synchronous AS builds also release their
command buffer and fence. The live demo is checked by launching, capturing distinct animation frames,
relaunching and detaching, in addition to the standalone smoke's explicit-close path.

Remaining validation: actual query-only/unsupported hardware, minimize/restore and repeated resize stress,
long-running topology churn, geometric-normal versus shading-normal offsets for smooth meshes,
per-pixel convergence against an independent quadrature oracle, AS build GPU timestamps, and memory
including all G-buffer/AS/scratch allocations. Ordinary window resize scales the fixed-resolution output;
reconstruction at a different resolution requires recreating the pass and its matching inputs.

## Upstream sample reproduction

```bash
mkdir -p build/lighting-experiment/upstream
git clone https://github.com/NVIDIA-RTX/RTXDI build/lighting-experiment/upstream/RTXDI
cd build/lighting-experiment/upstream/RTXDI
git checkout a6efab966b7c3b272da0461578eb56ac61c7cbff
git submodule update --init --recursive Libraries/Rtxdi External/donut External/NRD External/cxxopts
GIT_LFS_SKIP_SMUDGE=1 git submodule update --init Assets/Media
git -C Assets/Media lfs pull --include='Arcade/**'
cmake -S . -B ../../rtxdi-build -G Ninja -DCMAKE_BUILD_TYPE=Release -DDONUT_WITH_DLSS=OFF
cmake --build ../../rtxdi-build --target MinimalSample -j6
mkdir -p ../../rtxdi-build/Assets
ln -s ../../upstream/RTXDI/Assets/Media ../../rtxdi-build/Assets/Media
../../rtxdi-build/bin/MinimalSample --vk
```

CMake fetches the DXC package recorded above. No driver or system packages were upgraded. The Linux
build completed all 174 build steps. A bounded sample launch with `--vk` produced no console error,
but no sample image or successful-exit verdict was captured; this is **build evidence only** for the
upstream sample. Field's own rendered acceptance tests supply the runtime evidence for this patch.

## Verification scope and unrelated failures

- Twenty focused JVM tests pass (lighting/SDK ABI, Fabricate shader compilation, GLSL completion
  and existing AutoDisposer ownership tests), with the tree-write guard clean.
- The generated starting-point bundle and public transcript index pass their guards. The direct
  lighting demo is classified compile-only in the headless corpus and exercised separately on the GPU.
- `checkAgentTranscriptLayouts` is red for two pre-existing saved documents:
  `function_conversation/metadata.edn#function_tools` and
  `function_conversation_example/metadata.edn#function_tools`, both 1320×150 versus the ordinary
  620×170 ceiling. Neither file was changed. This is not a registered expected failure or a passing
  full certification.
- A combined backdoor invocation beginning with `run-jvm-main` and then using document macros hit
  an existing missing `SessionInformation` context at `Server.kt:2192`. The standalone JVM check
  had completed successfully; the subsequent macro command did not. The verified recipes keep
  standalone mains and document macros in separate backdoor sessions.
- Reload also validates reflected fixed workgroup dimensions (8×8×1). Resource-layout compatibility
  alone cannot validate dispatch coverage; changing the workgroup size is rejected before swapping.
- The non-strict HTML build and built-reference checker pass. The separate strict asset guard
  remains red for two existing capture fences without embedded/referenced images:
  `public/docs/datalog.md` (`assets/datalog/box_why.png`) and
  `public/docs/petri_nets.md` (`assets/petri_nets/language.png`). All new lighting assets are staged
  and accounted for. Those unrelated pages were left unchanged.

## Implemented RTXDI continuation (24 September 2026)

`RtxdiEngine.kt` adds stages to the existing `DirectLighting` output/resolve. `RtxdiSettings.kt`
provides camera suppliers, stable light snapshots and explicit history resets; Fabricate's
`bundle.rtxdiSettings()` supplies matching view/projection. `RtxdiProgram.kt` validates the optional
SPIR-V manifest and reflected layouts. `rtxdi/snapshot.comp` snapshots raster surfaces;
`rtxdi/lighting.hlsl` implements the application bridge and calls the pinned SDK's stream/finalize,
temporal and spatial reservoir functions. `tools/lighting/build_rtxdi.py --prepare` is the only
SDK/toolchain fetch path. Ordinary Gradle and startup remain dependency-free with respect to RTXDI.

Host inventory: reservoir block/array pitches, uniform light candidate count, two light-index maps,
a fixed 256-element disk-offset sequence, frame index and history bounds. All are small Kotlin
operations; no CPU SDK functions or native wrapper are necessary. SDK root/library commits remain
those above. DXC release v1.8.2505.1 (binary reports 1.9-dev 4950-b106a961) and both shipped shared
libraries are SHA-256 pinned in the build script and recorded in results/rtxdi-program.json. Failed
compilation leaves the current manifest unchanged. No SDK binaries/headers are redistributed.

ABI: 112-byte HLSL push constants (explicit offsets), 80-byte SurfaceData and LightInfo records,
24-byte packed SDK reservoirs, 8-byte disk offsets. SPIRV-Cross checks each on load. Binary-only
shader reflection now retains named structures, array strides and 64-bit integer types. The existing
ComputeModule accepts these shaders directly. The SDK SPIR-V uses Vulkan 1.3, scalar layout, physical
buffer addresses and ray queries. Ray tracing pipelines are not needed.

Resources: two surface pages, three tiled reservoir slices (16x16), acquired-image-indexed host light
and matrix buffers. Stages use the graphics queue with explicit read/write barriers. Temporal reads
last frame's final slice before spatial overwrites it. Host buffers are written only after the
renderer waits on that acquired image's fence. Mode/layout/resolution lifecycle remains explicit;
resolution changes create a new pass. The reported memory includes these allocations but excludes
G-buffer/AS/scratch/driver padding. Descriptor range is checked against the device limit.

Sampling: uniform light index and uniform rectangle UV; contribution includes emitter area,
Lambertian albedo/pi, both cosines and inverse squared distance. This consistently uses light/UV
measure, not a mixture of area and solid-angle PDFs. Streaming inverse source PDF is light count.
Initial finalization divides by candidate count before setting M=1 (SDK convention). Initial and
temporal inputs discard invisible selected samples while keeping M; temporal and spatial SDK
bias correction use ray-traced visibility. Final visibility is always freshly evaluated. Previous
emitter parameters and two-way stable-ID maps support moving/reordered/deleted lights. Geometry
commits reset history because only the current BVH is retained.

Reprojection: Field's projection itself does not flip Y; its raster viewport does. Snapshot shader
uses previous clip XY with Y negated, maps to pixel centers, and supplies previousPixel-currentPixel
plus previousLinearDepth-currentLinearDepth. Previous position, albedo, SDK normal and depth tests
reject incompatible surfaces. There are no categorical material/object attachments. Semantic edits
outside this diffuse contract require explicit reset. A view-matrix element change greater than
0.75 triggers a conservative cut; callers should signal cuts explicitly for scale-independent intent.
Projection changes, mode/sampling changes and successful reload also reset. No color denoising.

Verification: `RtxdiLightingSmoke` through the backdoor passed on the RTX 4090 with zero VUIDs,
including all four stage configurations against the 256-sample uniform estimator, stable-ID edits,
empty-light darkness, camera motion/cuts, transform invalidation, bypass and reload. 16 independent
scene-mean trials use 6 combined standard errors plus 0.1% reference mean for quantization/FP
allowance; the observed full-mode difference was 0.00001315 at SE 0.00006276. This is not a per-pixel
convergence proof. The test emits stage distributions for 1/16/128 lights and 1/8 candidates after
24 warm-up frames (96 measured). Real PNGs and a four-second moving-light clip are published.

Run after optional shader preparation, using the private-source launch workaround above if needed:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py \
  --json --attach-existing --port 9724 --timeout 600 \
  --command 'run-jvm-main afield.four.lighting.RtxdiLightingSmoke --arg=--clip --timeout 540'
```

`RTXDI LIGHTING CHECK DONE` is the completion marker. All optional runtime artifacts are under
`build/lighting-experiment/rtxdi-gpu/`; durable numeric evidence is in `results/rtxdi-*.json`.
The initial-slice limitations above still apply to alpha, transmission, GI, VR, AS build stalls and
unmeasured devices. Light presampling/ReGIR, emissive meshes and DLSS evaluation are deferred.

The expanded final GPU check also passed deleted-ID diagnostic validation, tiny bright emitters,
black materials, recreation at non-tiled 173x119 resolution and one 800x600 window resize. The live
640x360 document activated RTXDI, exposed its controls, produced distinct animated captures, and
relaunched/detached without VUIDs. A real-device limit check caught and fixed uint32
maxStorageBufferRange interpretation (4294967295, not signed -1); capability JSON now reports it
correctly. Verification tooling here used uv 0.11.26 and Python 3.14.6.

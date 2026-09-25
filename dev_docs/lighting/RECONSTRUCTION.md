# NVIDIA reconstruction integration — 25 September 2026

Stage 1 evaluates real DLAA and SR frames inside Field. The existing RTXDI implementation remains
the lighting baseline. RR, optical flow and learned shader functions are now described in NEURAL_TOOLS.md.

## Integration map

| Ownership | Existing implementation / change |
|---|---|
| Instance and device negotiation | `four/device/Instance.kt`, `Device.kt`: optional feature-specific NGX discovery before creation |
| Native binding | Existing Ceres/Manifold FFM convention; `native/field-ngx` C ABI, `four/reconstruction/NgxNative.kt` |
| Per-device NGX runtime | `NgxRuntime`, lazy initialization, caps and recommended sizes, shutdown before Vulkan device destruction |
| Per-view reconstruction | `DlssReconstruction`: immutable dimensions/mode, borrowed input images, owned output, explicit reset/revision |
| Raster inputs | `ReconstructionDemo`: ordinary Field `Module`, `Mesh`, MRT `RenderToTexture` |
| Existing lighting | `DirectLighting`, `RtxdiEngine`: raw FP32 lighting plus optional three-pass spatial filter; no NRD |
| History | Existing `FrameHistory`, RTXDI surface/reservoir pages; NGX owns separate per-view internal history |
| Timing / capture | Existing `LightingGpuTimer`; diagnostic transfer readback only, never part of evaluation |
| Retirement | Renderer cleanup queue after in-flight use; SDK calls serialized on an 8 MiB platform thread |

NGX's installed device-extension query reserves >1 MiB on its native stack. The initial attempt on
Field's render thread crashed inside `libnvidia-ngx.so.610.43.02`; the standalone probe succeeded.
Core disassembly showed a 0x100000-byte stack reservation before the fault. The FFM adapter uses one
dedicated 8 MiB host thread and waits for recording to finish. This also enforces the SDK's non-thread-safe
host contract. Commands still enter Field's original graphics queue; no second Vulkan device/renderer.

## Dependency and optional build

- NVIDIA/DLSS `374959484e79a640feaba44c93ac8cfb0a03f5b5`, SDK libraries 310.9.1, NGX API 0x15.
- Existing ignored checkout: `build/lighting-experiment/upstream/DLSS`.
- `bash tools/lighting/build_ngx.sh` checks that commit and builds only the C wrapper.
- `FIELD_DLSS_ROOT` overrides SDK checkout; `FIELD_NGX_LIBRARY` overrides wrapper path.
- `FIELD_NGX_DISABLE=1` tests missing/disabled optional support. No Gradle native build dependency.
- NVIDIA SDK license is proprietary: no SDK binaries or headers are copied into this repository.
- FFM uses Java 25 already selected by Field; CMake builds against installed Vulkan headers.
- Actual toolchain: GraalVM Java 25.0.2+10, LWJGL 3.4.3, GCC 16.1.1 (20260625), CMake 4.3.4,
  Vulkan headers/loader 1.4.350. Physical device API 1.4.341; Field requests Vulkan 1.3.
- Required SR instance extension: `VK_KHR_get_physical_device_properties2`; device extensions:
  `VK_NVX_binary_import`, `VK_NVX_image_view_handle`, `VK_KHR_buffer_device_address`, `VK_KHR_push_descriptor`.
  The report records supported/enabled ray features separately; the SR smoke disables ray tracing.
- Graphics queue family 0 advertises graphics/compute/transfer/sparse and 16 queues; all Stage 1 work
  uses Field's existing graphics queue. Other families provide transfer (1), compute (2), video decode
  (3), video encode (4) and optical-flow/transfer (5). Optical flow and cooperative vectors are advertised
  extensions, but their feature bits, formats and inference/training combinations have not yet been
  enabled or validated by this adapter. Extension presence alone is not a Stage 3/4 result.

## Stage 1 contract

All inputs are opaque, mono, layer 0/mip 0, render-sized. Color is linear HDR RGBA16F or RGBA32F,
depth R32F/D32F in Vulkan [0,1] with far/background=1, motion RG16F/RG32F. Motion maps current pixel
to previous pixel, in **input pixels**, top-left origin, positive right/down, excluding jitter.
Field direct-lighting motion is current-minus-previous UV: multiply by `(-width,-height)`.
Projection displacement is Halton(2,3), in input pixels; the raster projection uses `(2*x/width,-2*y/height)`
with Field's negative-height viewport. The exact displacement is passed to NGX. No jitter in velocity.

DLAA explicitly selects `NVSDK_NGX_PerfQuality_Value_DLAA`. SR sizes come from the SDK optimal-settings
callback, including legal minimum/maximum. Native resolution is not inferred to imply DLAA.
Ultra Performance uses the SDK's explicit mode enum (3). A custom `ReconstructionDemo.inputSize`
is checked against the runtime's inclusive bounds before allocating scene resources; `smallestFor`
preserves the output aspect ratio, rounded up to legal whole-pixel dimensions. Per the pinned guide's
section 3.2.2, native feature creation always receives **optimal** dimensions, while evaluation's render
subrect receives the **actual input** dimensions. Wrapper ABI 2 enforces this contract; rebuild the
optional native library when upgrading from ABI 1. Context dimensions remain fixed until recreation.
The adapter uses HDR + low-resolution motion + automatic exposure, explicit positive pre-exposure,
standard depth, no jittered-motion flag. Alpha is outside this first opaque contract; presentation does
not blend the SDK's output alpha. UI/coverage/latent pigments and layers without valid correspondence
must remain outside reconstruction. The abstract scene deliberately exposes this limitation.

Inputs must be SAMPLED and already in sampled-read layout. Output is RGBA16F with STORAGE/SAMPLED/
TRANSFER_SRC/TRANSFER_DST/COLOR_ATTACHMENT usage (NGX internally clears the output), GENERAL during
NGX, sampled-read afterward. A single graphics
queue supplies producer → SDK → consumer barriers. `Module` and `ComputeModule` bind pipelines,
descriptors and push constants for every draw/dispatch; no cached binding survives SDK evaluation.
No per-pixel JNI, CPU image transfer, or native allocation is used at the FFM boundary per frame.

Cuts/live edits use explicit reset/revision; skipped frame indices reset automatically. Mode/resolution
changes create a new view and retire the previous one. Minimized/absent frames must not evaluate NGX.
Recompiling an upstream shader must retain its last working pipeline on failure and increment revision
only when a new compatible pipeline is committed. Changed layouts require new inputs/view.

## Verified Stage 1 checkpoint

`ReconstructionSmoke` evaluates bypass, DLAA, Quality and Performance at 1920×1080 with ray tracing
disabled, checks a known +4-pixel translation (expected -4 motion), records raw input/motion, timings,
two scenes and optional comparison clips, then exercises reset and 960×540 recreation.
Run through the backdoor's `run-jvm-main afield.four.reconstruction.ReconstructionSmoke --arg=--clip`.

The 1080p GPU run completed with exit 0, `RECONSTRUCTION CHECK DONE`, and **zero VUIDs**. It includes
four modes × two scenes × 120 consecutive frames, valid and failed shader reloads (syntax and changed
layout), camera cuts, mode retirement and resolution recreation. Motion errors in the known translation
were below 0.001 input pixel. Static DLAA MSE against an independent 3840×2160 raster render downsampled
2×2 was 0.0000708173, versus 0.000232932 for bypass (69.6% lower). The four-sample reference is finite,
so this is a measured scene comparison rather than a general quality guarantee.

[`results/reconstruction/measurements.json`](results/reconstruction/measurements.json) contains 100-sample
GPU distributions and SDK/output memory. Timings include SDK evaluation and output barriers, exclude
scene rendering/presentation/readback, and exclude initial feature creation by warming up first. There is
no conversion pass in this scene: MRT writes the required formats directly. Capture readback runs only
after timing and is not part of evaluation. SDK statistics include retained device caches; sequential
mode allocations are not additive per-context memory. Output allocation includes driver padding.

`tools/lighting/capture_reconstruction.py --run <port>` reruns the backdoor smoke, rejects any VUID,
and produces the 4K four-panel comparisons from real 1080p readbacks. Without `--run`, it encodes an
already-verified run. Raw input dumps are little-endian float32 with adjacent `*-inputs.json` describing
dimensions, channel counts, jitter and scene time. Source alpha is one; SDK output alpha is unused.

The host stack repair and the required output TRANSFER_DST usage were found by actual execution and
fixed before these captures. The first exploratory run's timings and validation errors are not acceptance
evidence. No driver/system package upgrades were performed.

The final source rerun also completed the native path and an explicitly disabled-NGX path with zero
VUIDs. Three JVM convention tests pass. The real Starting Point was replayed through Field's backdoor,
including inherited renderer access, a live native image and a Playwright controls capture.

### Smaller-input extension

`ReconstructionSmoke --stress --clip` compares native Source, Performance and Ultra Performance.
At 1920×1080 output, this SDK/runtime returns **optimal=min=max=640×360** for Ultra. Performance
returns optimal=min=960×540 and max=1920×1080. There is no additional 320×180 setting hidden behind
the public mode control. The temporary minimum-mode probe confirmed that it would duplicate Ultra;
the Starting Point exposes only the distinct modes 0–4.

`tools/lighting/capture_reconstruction.py --stress --run <port>` creates four-panel videos: Source,
Performance, Ultra, and the actual same-frame Ultra color input enlarged with nearest-neighbour
scaling. Raw input enlargement is labelled and is not represented as another NGX mode. Measurements
and validation are recorded separately in `results/reconstruction/stress-*.json`. The GPU test also
checks a legal non-optimal Performance input (1280×720 evaluated after 960×540 feature creation).
The final stress capture completed with zero VUIDs: 120 frames per scene, two scenes, three NGX/source
outputs plus the corresponding raw Ultra inputs. Ultra measured 5.250016 ms median / 5.291072 ms p95
over 100 evaluations after 72 warm-up frames. Four JVM convention/bounds tests pass.

Repeated live Starting Point runs exposed another Linux NGX lifecycle issue: querying SR requirements
after `Shutdown1` but before a new `Init` returned `FAIL_NotImplemented` (0xbad00012). Initialization now
precedes that query; extension discovery still happens before Vulkan creation. Two consecutive renderer
create/switch-to-Ultra/destroy sessions in the same Field JVM passed, including assertions on the live
640×360 input target. This is separate from per-view resize tests and the Wayland minimize verification.

Native-window minimize/restore is now verified under KWin Wayland by `tools/lighting/check_wayland.py`.
The compositor confirms three minimized/restored state pairs; Field keeps issuing heartbeats and
captures valid reconstructed output after each restore. See `results/neural-tools/wayland.json`.
GLFW_ICONIFIED remains unsuitable on Wayland, so the test uses KWin's DBus scripting API rather than
inferring state from GLFW. Swapchain acquisition has a bounded wait, failed acquisitions do not
signal semaphores or age GPU retirement, and long presentation gaps invalidate temporal history.

Remaining limitations: mono opaque scenes; explicit reset for semantic edits; fixed per-view dimensions;
Linux x86_64 native build only (other Field builds retain bypass); no NGX-specific coverage or latent-color
path; no claim that temporal reconstruction preserves every procedural discontinuity. A four-sample
static reference does not measure ghosting: inspect the moving and abstract comparisons separately.

Primary references: [pinned SDK](https://github.com/NVIDIA/DLSS/tree/374959484e79a640feaba44c93ac8cfb0a03f5b5),
its `doc/DLSS_Programming_Guide_Release.pdf` sections 2.3.1, 3.3–3.7, 5.2.4–5.2.6 and 5.5–5.7;
[NVIDIA Linux overview](https://docs.nvidia.com/datacenter/tesla/driver-installation-guide/gaming.html),
[RR Vulkan sample](https://github.com/nvpro-samples/vk_denoise_dlssrr).

# Optional OpenDLSS-NR integration plan

Status: implemented; verification and remaining platform limits are recorded in
[OPEN_DLSS_NR_VERIFICATION.md](OPEN_DLSS_NR_VERIFICATION.md). Initial target: Linux x86_64,
NVIDIA Ada or newer with the required Vulkan features. Other systems retain
ordinary Field rendering and report an actionable availability result.

## Product and API

Deliver an optional native, GPU-resident texture-processing pass usable from
Field boxes, and an adapter for Fabricate. Any suitable image texture can be
the source: a drawing, a video frame, an offscreen render, or Fabricate's scene
output. Input and output have the same valid dimensions. This is neural
rendering, not DLSS super-resolution or ray reconstruction.

Proposed public types under `afield.four.neuralrendering`:

- `OpenDlssNrSupport`: native/model/device availability with explicit reasons.
- `OpenDlssNrControls`: seed, network style ID, tone, structure, skin,
  automatic mask, intensity, and temporal enablement.
- `OpenDlssNrPass`: render-thread-owned processor accepting Field image views,
  recording in a caller command buffer, and exposing a sampleable output.
- `OpenDlssNrInputs` / `OpenDlssNrFrame`: explicit color encoding, optional
  motion/history validity, frame index, scene revision, and reset information.

Expose finite tone and structure values without a 0..1 clamp, including the
tested -10..30 range. Validate binary16 representability and finite values;
ordinary UI sliders may suggest 0..1 while numeric fields allow exploration.
Network style IDs are 0, 1, and 2. Style 0 does not disable inference.

Default to the proven single-frame path, fixed seed, style 1, tone/structure 1,
and automatic mask disabled. Skin conditioning is explicitly inactive with
automatic mask disabled; the API/control panel must make this visible.
Enable the actual skin path when automatic mask is enabled, preserving the
upstream -1 sentinel for following the structure value.

Keep optional style/color postprocessing separate from network conditioning.
Demonstrate NR -> Field grading shader -> output. Expose a diagnostic raw RGBA
float32 head where useful. The default composition matches our standalone
runner, including RGB clipping; do not imply grading after that clipping can
recover lost values.

## Dependency preparation

Add `optional/opendlssnr/dependencies.json`, a build/preparation script under
`tools/`, and a CMake wrapper under `native/field-opendlssnr/`.

Pin OpenDLSS-NR to `9d08f4184bbcb9d858e2fb7a7834ec0837a9d2f1` and carry
reviewable patches for the local Linux fixes and required interop changes.
Use a checksummed source archive; retain upstream LICENSE/NOTICE. Exclude
Filament and the Windows demo renderer from this dependency.

Pin Vulkan-Headers v1.4.363, volk vulkan-sdk-1.4.357.0, and a verified glslang
toolchain. Generate the existing 13 SPIR-V modules and 78 PTX variants into the
optional build directory. Keep the standalone runner available as a reference
and diagnostic, but do not launch it from the rendering hot path.

Weight source:
https://github.com/RankFTW/rhi-repo/releases/tag/dlssnr-310.8.Lecram

Archive: `nvngx_dlssnr_310.8.Lecram.zip`.

- ZIP SHA-256: `ddb64e5545ba3f217c32555d2eb8fdfe76b9510d2256f13e8a5ecb1a9650ca54`.
- DLL SHA-256: `f95feb54137ea11979f9b4ec4f00afd84b5c98a5624d3388fbf6a87714a39fcc`.
- Extracted stage SHA-256: `b7b90fa00e54b4aac46acec47aeb24298a06d7b211a48180e9de48f46b5423ec`.
- Expected model: 71 blocks, 153 tensors, 147,683,778 packed bytes.

Reuse `tools/native_download_cache.py`: per-user checksummed downloads survive
`clean` and worktree changes. Download to temporary files, validate before
atomic publication, extract the named DLL safely, and read WEIGHTS_HT without
loading or executing Windows code. Preserve tensor bytes and verify the
extracted stage after disk readback. Publish a complete model directory
atomically with provenance metadata. Reject unknown binaries and corrupt or
incomplete models. Keep all source downloads, binaries, shaders/PTX, and
weights out of Git and default distributions; preserve separate provenance
for MIT implementation code and externally obtained weights.

Proposed explicit Gradle tasks:

- `fetchOpenDlssNrWeights`: fetch/verify/extract the pinned model.
- `buildOpenDlssNrNative`: prepare pinned source/toolchain and build the C ABI library.
- `buildOpenDlssNr`: aggregate both, producing a ready-to-use local installation.

Normal compile/build/check, Field startup, and documentation builds must not
download or build this optional dependency. `--offline` reuses validated
caches or fails with the exact missing artifact. Runtime supports explicit
library/model/kernel overrides and resolves defaults through existing Field
launch conventions, not assumptions about the user's working directory.

## Native boundary and device features

Use Field's existing Java FFM pattern: a versioned flat C ABI, opaque session
handles, fixed-layout input/control structs, explicit status/error reporting,
and ABI/struct-size checks. Catch all C++ exceptions inside the C boundary.
No STL or ownership-bearing objects cross into Kotlin.

Adopt Field's VkInstance, VkPhysicalDevice, VkDevice, and graphics queue.
Native code borrows input/output images and the caller's command buffer.
Field owns submission and image layout tracking; the backend owns weights,
kernel caches, activation scratch, descriptors, and optional history.
No per-frame CPU image transfer or private device/queue submission.

Probe and enable the entire usable feature bundle before device creation.
Merge promoted features into Field's existing Vulkan 1.1/1.2/1.3 structs;
respect its split Vulkan-1.2 OpenXR path and avoid duplicate feature structs.
Requirements include the FP8/cooperative-matrix/CUDA-launch extensions plus
the storage, arithmetic, memory-model, subgroup, and SM-builtin bits actually
used by the selected kernels. Separate advertised support, enabled support,
and native/model readiness. Do not make ordinary rendering depend on them.
Treat profiling-only requirements separately where possible.

The upstream context calls process-global `volkLoadDevice`; replace or isolate
this with per-device dispatch tables before supporting multiple renderers.
Share model/kernel state per device where safe; keep size-specific graph
scratch and temporal history per pass. Report memory use and initial warmup
cost. One-time model uploads obey Field queue synchronization; resizing and
destruction use deferred retirement instead of per-frame queue/device waits.

## Image and execution contracts

Define source encoding explicitly: already tone-mapped sRGB code values,
linear LDR, or linear HDR with paper-white/proxy conversion. Avoid implicit
double sRGB conversion when a sampled SRGB-format image is decoded by Vulkan.
Expose a documented output encoding that composes with Field's grading and
presentation shaders. Preserve orientation, alpha policy, valid/padded
dimensions, subresource limits, and output texture sampling semantics.

Adapt preprocessing/composition to Field image descriptors and buffer views.
Record preprocess -> network -> composite outside dynamic rendering, with
explicit producer/consumer barriers and Field-consistent final layouts.
Controls and descriptor reuse must be safe for every in-flight frame.
Keep a barrier-based reference route and expose failures in counter chaining;
never present a known timed-out frame as a successful render.

`FabricateFrameGraph` already schedules arbitrary Targets, whereas
`FabricatePostStack`/`FabricatePostEffect` assume shader modules and RTTs.
Implement the native Target first, then make the smallest explicit extension
needed for a native post effect with a sampleable output. Rewire downstream
inputs and ordering deliberately. Do not disguise native recording as a
fragment Module or overwrite unrelated passes.

## Temporal path

After single-frame agreement, add the upstream neural-history feedback loop,
including the network blend logit, learned blend scale, and Catmull-Rom history
sampling. It is independently switchable and never assumes correspondence
for arbitrary video/image sources without motion information.

Adapt Fabricate motion carefully: Field stores current-minus-previous UV;
the NR sampler wants current-to-previous displacement. Use depth/history
validity to distinguish uncovered/background pixels; motion attachment alpha
is not a coverage flag. Reuse FrameHistory's lifecycle rules and explicit
camera/background reprojection where appropriate.

Reset on first frame, resize, skipped frame, scene revision/camera cut, input
replacement, presentation resume, explicit reset, and incompatible controls.
Define fixed versus frame-varying noise policies explicitly. Serialize GPU
history dependencies on Field's queue without reusing in-flight descriptor or
uniform storage. Validate temporal behavior with actual moving-frame captures.

## Availability and failure behavior

On missing native files, missing model, unsupported platform/device, or an
explicit disable flag, report the specific reason and use an intentional
bypass at the high-level effect. Explicit construction/evaluation errors
remain visible. Avoid silently claiming neural output when using the source.
Validate hashes/version at initialization; release contexts with their owning
renderer and support live edit, Stop, relaunch, multiple passes, and resize.

## Implementation order and acceptance

1. Explicit preparation tasks, checksummed extractor, source patches, and C ABI.
   Reproduce native FP8 selftests and the standalone benchmark from prepared artifacts.
2. Field capability enablement and native availability/ABI smoke. Missing
   dependency and force-disabled baseline must still launch normally.
3. Single-frame texture pass. With identical seed and proxy input, compare
   the Field GPU head/output with the standalone runner, including both
   charcoal and ink examples and extended controls through 30.
4. Fabricate ordering and NR -> grading example. Verify multiple processors,
   formats, resize, reload, Stop/relaunch, frame completion, and memory retirement.
5. Optional temporal history and moving-scene/video capture checks. Measure
   coherence, motion sign, reset behavior, and chained/reference agreement.
6. Public API docs, completion visibility, editable Starting Points/transcript,
   captured before/after/sweep grids and temporal clip, fallback demo, and a
   verification record.

Use Field's managed backdoor/test entrypoints on an isolated port for JVM and
GPU examples, with the RTX driver selected and a verified display. Run relevant
unit/ABI/build/cache-corruption gates, Kotlin compilation, source/doc guards,
and the docs build. Native Vulkan validation must report zero errors. Measure
the complete texture-to-texture GPU path, CPU recording overhead, warmup, and
VRAM at 512, 768, and 1080p; do not present standalone network timings as Field
end-to-end performance. NVIDIA fixture parity remains a separate unverified
claim unless original captures become available.

## Main integration risks to resolve early

- Vulkan feature-chain changes coexist with existing rendering/OpenXR features.
- Per-device native dispatch and queue ownership with multiple Field renderers.
- sRGB/proxy/output conversion and post-stack rewiring.
- In-flight history/descriptors, teardown, and resource retirement.

No commits, publication, or implementation are included in this planning step.

# GPU-driven multiview rendering

The local `dust_six` experiment exercises Field's shared-eye rendering path with
GPU-generated work lists. Particle-specific physics, layouts and artistic shaders
belong to that workspace document. This note describes the reusable Field APIs.

## Frame order

[`MultiviewStereoTarget`](../../src/jvmMain/kotlin/afield/graphics/vulkan/targets/MultiviewStereoTarget.kt)
uploads the current predicted eye matrices into its shared eye UBO, then records
`beforeScene` targets before the layered scene. Compute that depends on those eyes
can therefore run on the same graphics command buffer before the draw that uses it.
Callers must supply the memory dependencies between their compute stages and draws.

The target uses a two-layer color/depth image with view mask 3. Shaders select the
eye through `gl_ViewIndex`; `configure(parameters)` supplies the shared `StereoViews`
UBO. OpenXR copies each array layer directly to its eye swapchain. The desktop
mirror samples those same layers, with no second scene evaluation.

## Indirect work and mesh pipelines

- [`ComputeMesh`](../../src/jvmMain/kotlin/afield/graphics/render/ComputeMesh.kt)
  accepts an optional `indirect` buffer and aligned byte offset containing a
  `VkDispatchIndirectCommand`. This keeps GPU-generated dispatch counts on the GPU.
- [`MeshMesh`](../../src/jvmMain/kotlin/afield/graphics/render/MeshMesh.kt) accepts
  a `VkDrawMeshTasksIndirectCommandEXT` through the same buffer/offset convention.
  Its resources are prepared before dynamic rendering begins.
- [`RenderToTexture`](../../src/jvmMain/kotlin/afield/graphics/vulkan/targets/RenderToTexture.kt)
  supports `meshModules` alongside conventional modules, including layered float
  targets. Adding a mesh pointer checks color formats, attachment count, view mask
  and viewport count. Transfers and sampled-image preparation run before rendering.
- [`Device`](../../src/jvmMain/kotlin/afield/graphics/vulkan/device/Device.kt)
  queries task/mesh features on supported non-macOS devices, including Linux.
  Mesh multiview is queried separately. `meshShaderLimits` exposes output, payload,
  invocation and view limits so a document can check its shader requirements.

Transfer-to-shader visibility includes pre-rasterization stages. Compute probes
recorded during a graphics frame retire with that frame's graphics fence through
[`ShaderFieldProbe`](../../src/jvmMain/kotlin/afield/graphics/vulkan/util/FieldProbe.kt).
Mesh UBO generation uses the mesh-specific delegate, and allocation during reload
uses the module's renderer explicitly.

## Desktop preview and validation controls

The [VR IDE transcript](../agents/transcripts/field_vr_ide.yaml) creates an interactive
browser companion and a separate best-effort scene preview. The preview can show
one eye or a stereo pair, close without ending the scene, and reopen from box 2.
See the [public guide](../../public/docs/field_vr_ide.md#desktop-headset-preview).

`AFIELD_OPENXR_VALIDATE=false` disables OpenXR API-layer validation independently
of Vulkan validation. This is useful when an installed layer does not understand
runtime vendor extensions. It does not disable Vulkan validation or change pose
acquisition and submission ordering.

## Verification boundary

The core compiled and the focused StackSL card, probe snapshot and stereo-copy JVM
suites passed. The updated VR IDE transcript also passed Field's real macro,
preamble and Kotlin compilation path, with GPU evaluation skipped. The
`DemoTranscriptExecSmokeTest --compile-file <recipe>` entrypoint supports the same
compilation check for a local recipe outside the public demo catalog.

The shared-eye scene, desktop mirror and indirect task/mesh path were exercised
locally on an RTX 4090 with Quest 3/WiVRn, including a 400,000-particle scene and
shader reloads. These hardware checks are separate from CI. They establish neither
a general performance guarantee nor a motion-to-photon latency measurement.

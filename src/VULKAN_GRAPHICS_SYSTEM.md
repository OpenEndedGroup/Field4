# afield.four Vulkan Graphics System

This guide summarizes the layout and responsibilities of the afield.four Vulkan stack (plus the live-coding shader stdlib), with 1–2 sentence notes per file.

## Core Orchestrator
- `afield/four/Renderer.kt`: Main render loop owning the Vulkan instance/device, queues, swapchains, command pools/buffers, and per-window contexts; records compute + graphics passes, submits/presents, and drives profiling/cleanup. Defines the `Target` interface and simple `target {}` helper.

## Device, Swapchain & Sync
- `afield/four/device/Instance.kt`: Builds the Vulkan instance with required extensions (GLFW/OpenXR), optional validation + debug messenger, and layer settings wiring.
- `afield/four/device/PhysicalDevice.kt`: Selects a GPU that supports graphics + swapchain, queries features/queues/memory, and exposes device metadata.
- `afield/four/device/Device.kt`: Creates the logical device, enables extensions/features (dynamic rendering, descriptor indexing, mesh shading, profiling, etc.), and exposes capability flags.
- `afield/four/device/Queue.kt`: Thin wrappers for graphics/present/transfer queues, family selection, and submission helpers.
- `afield/four/device/CommandPool.kt`: Allocates and manages command pools per queue family.
- `afield/four/device/CommandBuffer.kt`: Wrapper for primary/secondary command buffers with begin/end helpers.
- `afield/four/device/SwapChain.kt`: Creates the swapchain, formats/extents, image views, and handles acquire/present/resize.
- `afield/four/device/Surface.kt`: GLFW surface wrapper for window handles.
- `afield/four/device/Attachment.kt`: Depth/stencil attachment creation and cleanup.
- `afield/four/device/PipelineCache.kt`: Vulkan pipeline cache lifetime management.
- `afield/four/device/DescriptorSetLayout.kt`: Helpers for building descriptor set layouts (including push descriptors), pools, and updates.
- `afield/four/device/DescriptorSet.kt`: Placeholder for descriptor set binding metadata (imports only; most logic sits in `DescriptorSetLayout.kt` and modules).
- `afield/four/device/Fence.kt`: Fence creation/reset/wait convenience.
- `afield/four/device/Semaphore.kt`: Binary semaphore wrapper used for frame sync.
- `afield/four/device/VulkanNames.kt`: Debug utilities to name objects and insert command buffer labels when validation is on.
- `afield/four/device/timing.kt`: GPU profiler (timestamps, pipeline stats, perf counters) with per-frame query pool management and result decoding.

## Buffers & Memory
- `afield/four/buffers/VulkanBuffer.kt`: VMA-backed buffer wrapper with mapping, staging, device-address queries, and helpers for barriers/zeroing/writing/reading.
- `afield/four/buffers/VBuffer.kt`: Vertex buffer utility with paired staging/device buffers, transfer helpers, and watermark tracking.
- `afield/four/buffers/DynamicBuffer.kt`: Growable buffer that can append/resize and reupload when content changes.
- `afield/four/buffers/FrameBufferGroup.kt`: Manages per-frame buffer instances for multi-buffered uniform/storage data.
- `afield/four/buffers/Structure.kt`: CPU-side struct abstraction for UBO/SSBO data with binding, reflection-based offsets, and buffer proxying.
- `afield/four/buffers/GpuSchema.kt`: Kotlin-to-GPU ABI layer. Reflects `@GpuStruct` primary-constructor data into one std430-compatible schema used for CPU packing, array stride/address arithmetic, GLSL struct generation, and `GL_EXT_buffer_reference` views. The schema is intentionally independent of Vulkan allocation/binding so the same layout can live in a PC/UBO/SSBO or behind a device address.
- `afield/four/buffers/magic.kt`: DSL for declaring buffer parameters (usage, memory, addressability, per-frame, etc.) and linking to VulkanBuffer/VMA allocations.
- `afield/four/buffers/vma.kt`: Central VMA allocator setup/teardown hooks used by buffers.

The intended structured-memory direction is **schema + allocation + address**, not a distinct Kotlin type for every Vulkan placement. A `GpuSchema<T>` owns layout and serialization; `VulkanBuffer`/`FrameBufferGroup` own memory and lifetime; reflected physical-pointer members move device addresses through push constants. GLSL currently receives typed `buffer_reference` declarations from the schema because that is the ergonomic source-language form available today. The Kotlin ABI deliberately does not bake in typed-pointer identity, leaving the backend free to lower the same schema/address model to `SPV_KHR_untyped_pointers` and later descriptor-heap/device-generated-command machinery.

## Modules, Pipelines & Mesh Work
- `afield/four/modules/Module.kt`: Core graphics pipeline builder (vertex/fragment/task/mesh), shader introspection-driven descriptor + push-constant wiring, mesh registry, and hot-reloadable `ModulePointer`.
- `afield/four/modules/ComputeModule.kt`: Compute pipeline equivalent with descriptor inference, push constants, and `ComputeModulePointer` reload path plus compute-task registry.
- `afield/four/modules/MeshModule.kt`: Mesh-shader pipeline (task/mesh stages) plumbing, descriptor setup, and draw dispatch via mesh shaders.
- `afield/four/modules/Mesh.kt`: Traditional vertex/fragment mesh data holder (vertex buffers, instances, push constants) with context-aware binding utilities.
- `afield/four/modules/MeshMesh.kt`: Mesh-shader “mesh task” implementation with push-constant bindings and dispatch parameters (x/y/z group counts).
- `afield/four/modules/ComputeMesh.kt`: Compute task wrapper that binds push constants/SSBOs and dispatches compute workloads.
- `afield/four/modules/MeshBuilder.kt`: Helpers to construct vertex buffers and mesh attribute layouts programmatically.
- `afield/four/modules/Introspection.kt`: Runs shaderc + SPIRV-Cross (via LWJGL `spvc` JSON backend) to introspect SPIR-V; captures inputs, UBO/SSBO layouts, textures, push constants, and emits Kotlin/GLSL metadata.
- `afield/four/modules/ShaderSPIRVUtils.kt`: Shader compilation utilities (shaderc) for building SPIR-V from GLSL strings/files with proper stage mapping.
- `afield/four/modules/simpleGLTFSupport.kt`: Assimp-powered GLTF loader that builds modules/meshes/textures from GLTF assets and provides macros for live coding hooks.

## Render / Compute Targets
- `afield/four/targets/RenderTarget.kt`: Swapchain-backed render pass using dynamic rendering; transitions swapchain/depth images and executes registered modules/mesh modules per frame.
- `afield/four/targets/RenderToTexture.kt`: Offscreen target with configurable color/depth attachments, mip generation, and viewport control; exposes image views for sampling.
- `afield/four/targets/PingPongRenderToTexture.kt`: Double-buffered variant of render-to-texture for ping-ponging between two offscreen targets each frame.
- `afield/four/targets/ComputeTarget.kt`: Simple container that runs a set of compute modules/targets inside the frame’s compute pass.
- `afield/four/targets/OpenXRTargets.kt`: OpenXR integration managing VR swapchains, views, and rendering to XR targets.
- `afield/four/targets/OpenXRTargets_multiview.kt`: Multiview-optimized XR path leveraging Vulkan multiview for stereo rendering.
- `afield/four/targets/RollingCapture.kt`: Rolling image capture utility that copies strips of a source image into a snapshot image and stages them to CPU without stalling rendering.

## Textures & Sampling
- `afield/four/texture/Image.kt`: Vulkan image wrapper with allocation, layout transitions, and cleanup.
- `afield/four/texture/ImageView.kt`: ImageView creation metadata and helper for referencing image aspects/mips.
- `afield/four/texture/TextureSampler.kt`: Sampler creation with common sampling options and mip handling.
- `afield/four/texture/textures.kt`: Image loading (STB), staging/upload, mip setup, and convenience loader `LoadedTexture` plus stub texture helpers.
- `afield/four/texture/mega/MegaTexture.kt`: GPU-driven virtual texture over an arbitrarily large image set — the fragment shader requests resolutions through a feedback buffer, and this serves them out of one layered atlas, a page table and an LRU.
- `afield/four/texture/mega/MegaTextureLayout.kt`: pure atlas arithmetic and the page-table bit format; no device, so it is directly unit-tested.
- `afield/four/texture/mega/MegaTextureCatalog.kt`: the image catalog (stable indices across runs) and the sparse on-disk page cache.
- `afield/four/texture/mega/MegaTextureDecoder.kt`: prioritized, cancellable parallel decode/downsample workers feeding the atlas.
- `afield/four/texture/mega/megaTextureShader.kt`: the GLSL the box interpolates with `{{ mega.all }}` — declarations, page-table queries and the derivative-based feedback.

## Video Pipeline
- `afield/four/texture/video/VideoDecoder.kt`: FFmpeg-based decoder that opens/seeks/decodes frames into P010 (semi-planar) buffers.
- `afield/four/texture/video/VideoTexture.kt`: Manages dual-plane Vulkan textures for decoded video frames, handles play/pause/seek, and uploads frames each frame via command buffers.
- `afield/four/texture/video/VideoTextureExample.kt`: Sample wiring of the video texture into the renderer to show usage.
- `afield/four/texture/video/README.md`: Notes on the P010 video path, plane layout, and usage tips.

## Utilities, Input & Config
- `afield/four/util/Options.kt`: Global engine toggles (validation, VR, profiling, depth formats, OS detection, Vulkan SDK path).
- `afield/four/util/Window.kt`: GLFW window wrapper with resize flags and basic window lifecycle hooks.
- `afield/four/util/KeyEvents.kt` / `MouseEvents.kt`: Input event listeners and state helpers for GLFW keyboard/mouse.
- `afield/four/util/Camera.kt`: Basic camera pose and view/projection math helpers.
- `afield/four/util/KeyCamera.kt`: WASD + mouse-look style camera controller built on the input helpers.
- `afield/four/util/Projection.kt`: Projection matrix utilities (perspective/orthographic) using Options defaults.
- `afield/four/util/MapOfSequences.kt`: Small scheduler/sequence helper used for per-frame callbacks and update hooks.
- `afield/four/util/AutoDisposer.kt`: Deferred cleanup queue that lets resources register cleanup actions drained on the render thread.
- `afield/four/util/templating.kt`: Lightweight templating engine used to preprocess shader/live-coding code with resolvers and macro expansion. `GpuSchema.template` is designed to be published directly into a box's `shaderTemplate` map so `{{ Particle.all }}` can carry Kotlin layout declarations into GLSL in the same live-coding unit.
- `afield/four/util/superpc.kt`: Push-constant-specific reflection path that remains useful for compact PC-centric data. `GpuSchema` complements it with a placement-independent ABI for values that may instead live in UBO/SSBO/device-addressable storage.
- `afield/four/util/16bits.kt`: Helpers for working with 16-bit/half precision packing/unpacking.
- `afield/four/util/util.kt`: OS detection, memory type selection, math helpers, and generic utility functions.

## Examples
- `afield/four/examples/NewMain6.kt`: End-to-end sample showing renderer setup and module usage.
- `afield/four/examples/NewMain7_dynamicNumberOfVerts.kt`: Example of dynamically sized vertex data and rendering.
- `afield/four/examples/NewMain7_errorChecking.kt`: Demonstrates error handling/reload paths for modules/shaders.
- `afield/four/examples/NewMain8_writeToImage.kt`: Shows writing to images/offscreen targets and sampling results.
- `public/docs/gpu_schema.md`: Worked `@GpuStruct` examples covering nested schemas, template expansion, addressable arrays, push-constant binding, ABI hashes, and the roadmap to untyped pointers.
- `public/docs/assets/canonical_graphics/b42d7b13-680e-4f9b-b789-8e6e7d8c4a21_*`: Additional canonical `5d. GPU schema` box showing a `GpuSchema<Particle>` array in an addressable Field buffer reached through a reflected physical pointer. The existing `5c. some more templating` / `PushConstants<T>` example remains alongside it.

## Live-Coding Stdlib
- `afield/stdlib/shaders.kt`: Live-coding glue that preprocesses/compiles shaders, wires macro-expanded module constructors (`moduleHere`, etc.), manages shader reloads, error surfacing, and generates inserts/bindings for uniforms/push constants within the hot-reload loop.

How it hangs together: `Renderer` owns the device/swapchain/queues and calls into `RenderTarget`/`ComputeTarget` each frame; those targets invoke `Module`/`MeshModule`/`ComputeModule` instances built via shader `Introspection` and buffer/memory helpers. Device wrappers handle lifecycle and sync, buffers feed data (UBO/SSBO/vertices), targets orchestrate image transitions and outputs (swapchain, offscreen, XR), textures/video provide sampled resources, and utilities manage input, templating, push constants, structured GPU schemas, and configuration.

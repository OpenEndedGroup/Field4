# NVIDIA Flow in Field: what a binding would actually involve

*2026-08-20. Reconnaissance only — no code was written, nothing in `src/` refers to Flow.*

[NVIDIA Flow](https://nvidia-omniverse.github.io/PhysX/flow/) is a sparse-voxel fluid simulator with
a combustion model — the fire-and-smoke library from GameWorks, rebuilt as Flow 2 for Omniverse and
released as BSD-3 source in April 2025. It now lives inside
[`NVIDIA-Omniverse/PhysX`](https://github.com/NVIDIA-Omniverse/PhysX) under `flow/`, currently
version 2.2.0 (`flow/CHANGELOG.md`, dated 2025-03-10).

This page is what an afternoon of reading the headers and the sample app found: the shape a Kotlin
binding would take, the two integration paths and the constraint that separates them, and the one
platform fact that decides where any of it can run. It is a decision document, not an
implementation contract — when `afield.flow` exists, this page should be replaced by one describing
what was built.

## The platform fact, first

`flow/premake5.lua` has `filter { "system:linux" }` with platforms `x86_64` and `aarch64`, and
`filter { "system:windows" }` with `x86_64`. There is no macOS filter, and `flow/README.md` says
"Platforms supported: Windows, Linux, Linux ARM64".

So Flow cannot run on the primary Mac, and no amount of binding work changes that. **The target host
is the Linux/NVIDIA machine**, which also means any Flow-touching smoke test is host-gated in the
same way the GPU capture path already is — it cannot run in the macOS working loop, and it cannot
run on a GitHub runner, which has no NVIDIA GPU. That is a real cost and it should be priced in
before, not after.

One hedge exists and is worth knowing about even though it does not remove the blocker. Flow has a
complete **CPU backend** — `eNvFlowContextApi_cpu` in `NvFlowTypes.h`, implemented in
`flow/source/nvflowext/cpu/` (`DeviceCPU.cpp`, `ContextCPU.cpp`, `ComputePipelineCPU.cpp`,
`TextureCPU.cpp`), and `premake5.lua` compiles `source/nvflowext/cpu/*.cpp` into nvflowext on both
supported platforms. Flow's HLSL is run through NVIDIA's own shader tool, which can emit a C++ path
as well as SPIR-V and DXIL. A macOS build of the CPU backend is therefore *conceivable* — but it
would be a port of somebody else's build system to an unsupported platform, with an unknown bill,
and it buys a simulator running at CPU speed. Do not start there.

## The ABI is an unusually good fit for FFM

Better than Ceres, comparable to Manifold. The entire library surface is **six exported symbols**,
each returning a pointer to a struct of function pointers:

| symbol | in | gives |
|---|---|---|
| `NvFlowGetOpList` | `nvflow` | the base op vocabulary |
| `NvFlowGetExtOpList` | `nvflowext` | emitters, ray march, shadow, isosurface |
| `NvFlowGetGridInterface` | `nvflowext` | 14 functions: `createGrid`, `simulate`, `offscreen`, `render`, `getRenderData`, isosurface, block counts |
| `NvFlowGetGridParamsInterface` | `nvflowext` | parameter staging, versioning, snapshots |
| `NvFlowGetContextOptInterface` | `nvflowext` | the optimizing context wrapper |
| `NvFlowGetDeviceInterface(NvFlowContextApi)` | `nvflowext` | device/queue/swapchain for `vulkan`, `d3d12` or `cpu` |

Every parameter in every one of those interfaces is a pointer or an integer. There are no by-value
struct arguments in the calling convention, no C++ templates in the exported surface, no
name-mangled symbols. That is the difference between this and Ceres: **no `native/ken-flow` wrapper
is needed.** The binding would look like `afield.manifold` — Kotlin `java.lang.foreign` talking
straight to a library NVIDIA built — not like `native/ken-ceres`, which exists only because Ceres
exports C++ templates and nothing else.

Two details make it better than a merely adequate C ABI.

**Interfaces describe themselves.** `NV_FLOW_REFLECT_INTERFACE()`, the macro at the head of every
interface struct, expands to exactly one field: `const NvFlowReflectDataType*
interface_NvFlowReflectDataType` (`NvFlowReflect.h`). So each struct is an 8-byte metadata pointer
followed by its function pointers in declaration order, and that metadata carries every member's
name and offset. Flow's own loader uses it to copy interfaces *by name* rather than by layout —
which is what `NvFlowContextInterface_duplicate` does — and a Kotlin binding can do the same. That
matters more here than it usually would: it means the binding is not a hand-maintained mirror of a
struct layout that silently mis-binds when the SDK moves. Resolve by name, fail loudly on a missing
member.

**Parameters need no mirrored Kotlin structs at all.** This is the finding that changes the
character of the work. `gridParamsInterface.enumerateParamTypes` returns the typenames, the display
names, and *the library's own* `NvFlowReflectDataType*` for each of the 23 `NvFlow*Params` POD
structs — `NvFlowGridSimulateLayerParams`, `NvFlowEmitterSphereParams`, `NvFlowRayMarchParams`,
`NvFlowShadowParams` and the rest. A `NvFlowReflectDataType` is
`{ dataType, elementSize, structTypename, childReflectDatas, childReflectDataCount, defaultValue }`,
and each child `NvFlowReflectData` is `{ reflectHints, reflectMode, dataType, name, dataOffset,
arraySizeOffset, versionOffset, metadata }`. Field names, field offsets, nested types, and the
default value of the whole struct, all readable at runtime from the shipped binary.

That is precisely the shape Field wants. A parameter surface *discovered from the library* is one
that completion can see, that a `flow { }` DSL can build from, and that does not rot against an SDK
upgrade — as against 23 hand-transcribed Kotlin data classes, which would rot on the first minor
version and rot silently, since a wrong field offset is a wrong simulation and not a crash.

## What the minimal app looks like

`flow/source/nvfloweditor/StandaloneTest.cpp` is about 500 lines and is the whole thing — it is the
headless integration NVIDIA wrote for the USD/Omniverse path (its params store is literally named
`"flowUsd"`). The sequence, which ports directly:

```
deviceInterface.createDeviceManager(validation, nullptr, 0)
deviceInterface.createDevice(manager, &desc)          // desc: { deviceIndex, enableExternalUsage, logPrint }
deviceInterface.getDeviceQueue(device)
deviceInterface.getContextInterface(queue)            // 39 fn ptrs, duplicated by name
deviceInterface.getContext(queue)
gridInterface.createGrid(&contextInterface, context, opList, extOpList, &gridDesc)
gridParamsInterface.createGridParamsNamed("...")

// per frame
gridParamsInterface.commitParams(params, &snapshot)   // see below
gridParamsInterface.getParamsSnapshot(params, absoluteSimTime, 0)
gridParamsInterface.mapParamsDesc(params, snapshot, &desc)
gridInterface.simulate(context, grid, &desc, globalForceClear)
gridInterface.getRenderData(context, grid, &renderData)
contextInterface.enqueueAcquireBuffer(context, renderData.nanoVdb.smokeNanoVdb)
gridParamsInterface.unmapParamsDesc(params, snapshot)
deviceInterface.flush(queue, &flushedFrameID, waitSemaphore, signalSemaphore)
```

Committing parameters means building the snapshot by hand, and the sample shows it is three plain
structs deep:

- `NvFlowDatabaseTypeSnapshot` = `{ version, dataType, instanceDatas, instanceCount }` — one per
  param type, pointing at an array of pointers to your filled-in POD instances
- `NvFlowDatabaseSnapshot` = `{ version, typeSnapshots, typeSnapshotCount }`
- `NvFlowGridParamsDescSnapshot` = `{ snapshot, absoluteSimTime, deltaTime, globalForceClear,
  userdata, userdataSizeInBytes }`

The sample takes its `dataType` pointers from static tables compiled into the app. A binding takes
them from `enumerateParamTypes` instead, and is version-proof for it.

## Two integration shapes, and the constraint that separates them

### Path 1 — Flow owns its own device, buffers are shared

Flow's Vulkan backend creates its own `VkInstance`/`VkDevice`; `NvFlowDeviceDesc` is only
`{ deviceIndex, enableExternalUsage, logPrint }`, so **you cannot hand Flow an existing
`VkDevice`**. To land on the same GPU as `afield.four`, enumerate with
`deviceInterface.enumerateDevices` and match on `NvFlowPhysicalDeviceDesc`, which carries
`deviceUUID[16]`, `deviceLUID[8]`, `deviceNodeMask` and `deviceLUIDValid` — the same UUID
`VkPhysicalDeviceIDProperties` reports, so the match is exact.

Sharing then goes through external handles: `getBufferExternalHandle` /
`createBufferFromExternalHandle` on the context interface, `getSemaphoreExternalHandle` on the
device interface, and `NvFlowInteropHandle` = `{ type, value, resourceSizeInBytes }` with type
`eNvFlowInteropHandleType_opaqueFd` or `_opaqueWin32`. On the Linux host that is
`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT` and a `VkSemaphore` imported the same way — both
extensions Field's `Device.kt` would need to request.

**The constraint: there is no texture external handle anywhere in the ABI.** Buffers can cross
devices; images cannot. `NvFlowContextInterface` has `getBufferExternalHandle`,
`closeBufferExternalHandle` and `createBufferFromExternalHandle`, and no texture equivalent —
`NvFlowDeviceInterface` likewise. So Flow's own ray-marched image, the thing `gridInterface.render`
produces, **cannot be imported into a Field render target on this path.**

What crosses instead is the NanoVDB export. `getRenderData` fills
`NvFlowGridRenderDataNanoVdb` with `temperatureNanoVdb`, `fuelNanoVdb`, `burnNanoVdb`,
`smokeNanoVdb`, `velocityNanoVdb` and `divergenceNanoVdb` buffer transients (there is a readback
variant too, for the CPU). Field imports those as `VulkanBuffer`s and ray-marches them in its own
GLSL — and the reader is not something anyone has to write, because
`flow/include/nvflow/nanovdb/PNanoVDB.h` ships a `PNANOVDB_GLSL` mode alongside `PNANOVDB_C` and
`PNANOVDB_HLSL`. Turn on `nanoVdbExport.enabled` in `NvFlowGridSimulateLayerParams` and this is the
supported path, not a trick.

Honest summary of Path 1: Flow simulates, Field renders, the seam is a shared voxel buffer plus a
semaphore, and Flow's own renderer — shadows, colormaps, isosurface, the ray-march the artists
tuned — is on the wrong side of the fence.

### Path 2 — Field owns the device, Flow records into it

`NvFlowContextInterface` is not just something Flow hands you; it is an interface Flow *consumes*.
`gridInterface.createGrid` takes a `NvFlowContextInterface*` and an opaque `NvFlowContext*`, and
Flow's ops call through it for every resource and every pass. Implement those 39 function pointers
as FFM upcall stubs over `afield.four` and there is no interop at all: Flow's compute passes record
into Field's command buffers, its resources *are* `VulkanBuffer` and `Image`, and `render` can
composite into a Field `RenderToTexture`.

The 39 members fall into five groups, and each maps onto machinery `afield.four` already has:

| group | members | Field side |
|---|---|---|
| frame/query | `getContextConfig`, `isFeatureSupported`, 4 × frame counters, `getLogPrint`, `executeTasks` | trivial; `executeTasks` is a thread-pool callback |
| buffers | create/destroy/map/unmap, `getBufferTransient`, `registerBufferAsTransient`, `aliasBufferTransient`, `enqueueAcquireBuffer`, `getAcquiredBuffer`, `getBufferTransientById`, 3 × external handle | `VulkanBuffer`, `magic.kt`, VMA |
| textures/samplers | the same shape for textures, plus `createSampler`/`getDefaultSampler`/`destroySampler` | `Image`, `ImageView`, `TextureSampler` |
| pipelines | `createComputePipeline` from `NvFlowBytecode` + `NvFlowBindingDesc[]`, `destroyComputePipeline` | `ComputeModule`, but bypassing `Introspection` — see below |
| passes | `addPassCompute` and four copy variants | `CommandBuffer` + barriers |

Two things make this less alarming than 39 entry points sounds. First, `NvFlowContextOptInterface`
sits *between* Flow and your context — `create(backendContextInterface, backendContext)`, then
`importBackendBufferTransient` / `exportTextureTransient` and friends — and it is where the transient
aliasing and pass batching live. Second, the descriptor model arrives pre-solved:
`NvFlowBindingDesc` and `NvFlowDescriptorWrite` both carry an explicit Vulkan variant
(`{ binding, descriptorCount, set }` and `{ binding, arrayIndex, set }`), and `getContextConfig`
reports which API the context is, so Flow hands over bytecode and bindings already targeted at
Vulkan. Field's usual path — shaderc plus SPIRV-Cross introspection — is not involved and must not
be; these pipelines are built from a bytecode blob and a binding table, which is a *simpler*
construction than `ComputeModule` does today.

The real risks on this path are the ones you would expect and cannot read off a header: transient
resource lifetimes and aliasing (getting `enqueueAcquire*`/`getAcquired*` wrong shows up as
corruption, not as an error), barrier placement between Flow's passes and Field's, and whether
Field's device enables everything Flow's SPIR-V assumes (subgroup operations in particular).

## Recommendation

Path 1 first, as a smoke test, then Path 2 as the integration. The order is not fence-sitting: Path
1 answers "does the library load, does the reflection walk work, does a grid simulate" in something
like a day of work with no Vulkan interop at all — build nvflowext, `dlopen` it, walk
`enumerateParamTypes`, fill a `NvFlowEmitterSphereParams`, run 60 frames with the **CPU** device
interface, and assert a non-zero `smokeNanoVdbReadbackSize`. That is a test that can run without a
display and, notably, that proves the whole binding spine before anyone touches external memory.

Then Path 2, because Path 1's ceiling — buffers only, Flow's renderer unreachable — is exactly the
ceiling the second week would hit anyway.

## Build and packaging notes

`flow/build.sh` runs `buildtools/packman pull dependencies.xml -p linux-$(arch)`, then premake, then
gmake for debug and release. `dependencies.xml` fetches four packages: premake 5.0.0-beta2,
Slang v2025.6.1, and (Windows only) dxc and VsWhere. So the build needs network access to NVIDIA's
packman on first run — the same shape as `buildCeresRedistributable`, and the same
first-run-needs-network caveat. The README notes Linux may need `libxrandr-dev`, which is for the
GLFW-based editor rather than the libraries.

Packaging would follow the Ceres precedent: a Gradle task producing `libnvflow.so` and
`libnvflowext.so`, a `-PflowLibrary=` style override, and a loader that reports "not installed"
cleanly on the two platforms where it never will be. Note that Flow is *two* libraries, and the
loader tolerates finding one without the other — the Kotlin should not.

## Open questions

- **Does Flow's SPIR-V run on Field's device configuration?** Unknown until tried. Flow's HLSL is
  compiled by Slang and certainly uses wave/subgroup intrinsics; `Device.kt` would need the matching
  features enabled.
- **Upcall cost on Path 2.** `addPassCompute` is called per pass per frame, so the order is hundreds
  of upcalls per frame, not millions — almost certainly fine, but it has not been measured.
- **How much of the parameter surface is worth exposing?** 23 structs is a lot of DSL. The
  reflection metadata makes a generic "set any field by name" binding cheap and a curated
  `flow { emitterSphere { … } }` surface a separate, later decision.
- **Where does the output belong in Field?** A `Box` output showing a NanoVDB ray-march is the
  obvious first artifact, but on the Linux host, which is not where docs captures are generated.
  Any documentation of this feature has the same host problem the feature does.

## Pointers

Upstream, all under `flow/` in `NVIDIA-Omniverse/PhysX`:
`include/nvflow/NvFlowContext.h` (the 39-member context interface),
`include/nvflow/NvFlowReflect.h` (reflection, snapshots),
`include/nvflowext/NvFlowExt.h` (grid, grid params, device, params structs — 3941 lines and the bulk
of the API), `include/nvflowext/NvFlowLoader.h` (the loader worth copying, six symbols and two dlopens),
`include/nvflow/nanovdb/PNanoVDB.h` (the GLSL-capable reader),
`source/nvfloweditor/StandaloneTest.cpp` (the minimal app), `source/nvflowext/vulkan/` and
`source/nvflowext/cpu/` (the two backends that matter here).

In Field, the two precedents: `src/jvmMain/kotlin/afield/manifold/ManifoldNative.kt` for the binding
style this would follow, and [`CERES.md`](./CERES.md) for the native-build-and-package story it
would borrow.

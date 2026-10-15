# Field graphics resource model — exploratory sketch

Status: design only, 2026-09-07. Nothing in this document is implemented or enabled.
Kotlin API names below are proposals unless explicitly identified as existing.
Shader fragments illustrate the proposed contract; they have not been compiled or rendered.

Based on Field commit `13e2331fdefc30b5307aab2d5f74a13eba68e56f`, in branch
`codex/four-resource-model-sketch`. NoGraphicsAPI was inspected at
`e025cc12793ce7d77ba35298860f36eb64a62b45`.

The question is whether three separable changes make Field easier to use and maintain:

| Conversation point | Concrete proposal | User-visible benefit | Likely code cost initially |
| --- | --- | --- | --- |
| 2. Texture indices | Shared, typed texture tables with immutable descriptor snapshots | A material or instance selects its texture using a `uint` | Adds table management; replaces binding work only for migrated modules |
| 3. Completion-based retirement | Keep each native allocation alive until its recordings and submissions release it | Resizing and shader replacement have an explicit lifetime rule | Adds submission tracking before any old cleanup can be removed |
| 1. Pointer arguments | Owner-preserving buffer slices and one argument snapshot per draw/dispatch | Share material/simulation arrays without caching raw addresses or re-evaluating suppliers inconsistently | Builds on `Structure`; no new general buffer allocator |

MoltenVK remains the required baseline; KosmicKrisp is a second Vulkan driver to validate.
The proposals do not require descriptor heaps, device-address commands, mesh shaders,
untyped pointers, or a new Metal backend. Existing vertex buffers, index buffers,
named texture bindings, shader reflection, and pipeline caches remain useful.

## 2. Texture tables: the change a user would see

### Existing shape

Within current module parameters:

```kotlin
textures("albedo" to image)
```

The shader declares its own binding:

```glsl
layout(set = 1, binding = 0) uniform sampler2D albedo;
// ...
vec4 colour = texture(albedo, uv);
```

Field already supports named texture arrays through `textureArrays(...)`.
This proposal adds a shared namespace across participating modules; indexing textures
is not a new shader capability. For one image, the existing interface may be preferable.

### Proposed shape

Illustrative Kotlin, with `renderer`, `image`, and `sampler` already available:

```kotlin
// All new API spellings in this block are proposals.
val materials = Sampled2DTable(renderer, requestedCapacity = 64)
val brick = materials.add(image, sampler)  // opaque TextureRef2D owned by this table

// Within proposed module configuration:
sampled2DTable(materials)                  // selects a shared shader/layout contract

// Within proposed per-mesh argument configuration:
arguments {
    textureIndex("albedoIndex", brick)    // shader sees uint; CPU retains the owner
}
```

The generated table declaration and a user push-constant block would look like:

```glsl
// FIELD_TEXTURE_CAPACITY and the reserved set are chosen at module creation.
layout(set = 1, binding = 0) uniform sampler2D fieldTextures[FIELD_TEXTURE_CAPACITY];
layout(push_constant) uniform Draw {
    uint albedoIndex;
} pc;

// Inside main():
vec4 colour = texture(fieldTextures[pc.albedoIndex], uv);
```

`albedoIndex` is a slot, not a pointer. Kotlin can represent it as `UInt`, but the
argument writer must explicitly pack four bytes (`putInt(index.toInt())`); do not
assume current `Structure` already serializes Kotlin `UInt`. The higher-level handle
also carries table identity, so a texture from table A cannot accidentally be passed
to a module using table B.

The useful case is an instance/material array with a texture index per record. The
vertex stage can pass that value as a `flat uint` varying; the fragment stage uses:

```glsl
#extension GL_EXT_nonuniform_qualifier : require
// ... table declaration and flat input ...
vec4 colour = texture(fieldTextures[nonuniformEXT(materialAlbedoIndex)], uv);
```

This requires the sampled-image nonuniform-indexing feature. A `flat` varying can
still differ between primitives in the same draw. Uniform and nonuniform indexing
are different capabilities; query and enable the ones used by the compiled shader.
See the [Vulkan descriptor-indexing guide](https://docs.vulkan.org/guide/latest/extensions/VK_EXT_descriptor_indexing.html).

### A deliberately small compatible backend

Start with float-sampled 2D images and combined image/sampler descriptors. A slot
represents `(image view, sampler)`. Integer textures, cube maps, array textures,
depth comparisons, and storage images require their own typed contracts later.
No promise that one GLSL `sampler2D[]` can contain every kind of texture.

Use an ordinary descriptor pool and descriptor set with a fixed-size array. Query
capacity against sampler, sampled-image, per-stage, and aggregate pipeline limits,
including other resources in the module. Reject an unsupported capacity explicitly;
do not silently change shader constants. The first path does not need variable
descriptor counts, partially-bound slots, or update-after-bind. Fill unused slots
with a valid fallback texture/sampler, and reserve slot zero for that fallback.

Reserve set 1 only for modules explicitly using this contract. Reject collisions
with user declarations. Existing modules keep their current reflected layouts.
Include capacity, table type, set number, and stage visibility in the layout/ABI key;
changing capacity can require a pipeline-layout rebuild. The graphics, compute,
and mesh-stage modules should share this contract rather than duplicate it.

### Table revisions and changing images

Treat each descriptor snapshot as immutable from its first recorded binding until
all referencing recordings and submissions have released it. Mutations are queued
and published at an explicit preparation boundary before workers record commands.
A new revision gets a new or safely recycled descriptor set. Unchanged revisions
can be shared across windows and submissions; a swapchain image index is not a
global version number.

```text
CPU replaces brick's image
    -> queue a table edit
next preparation boundary
    -> build descriptor snapshot B; future arguments/recordings use B
already-recorded work
    -> retains snapshot A and A's native image/view/sampler allocations
all A recordings and submissions finish
    -> A can be recycled
```

The first implementation may rebuild the whole small array on an edit. Measure
that cost before adding dirty-slot copies. Bound outstanding snapshot storage;
when exhausted, wait for an eligible completion or defer publication explicitly.
Do not overwrite a busy set. Even a recorded but not submitted command buffer can
be invalidated by ordinary descriptor updates, as specified by
[`vkUpdateDescriptorSets`](https://docs.vulkan.org/refpages/latest/refpages/source/vkUpdateDescriptorSets.html).

For the first spike, allocate indices monotonically for the table's lifetime.
Removing an entry replaces it with the fallback in future snapshots; do not reuse
its numeric slot. Otherwise a `uint` left in a GPU material buffer could silently
select a different image. Later reuse needs an explicit proof that material data
no longer contains the index, or a generation scheme also checked by shaders.
CPU handle generations alone do not solve that problem. Capacity exhaustion is an
explicit result in the initial version.

Video and ping-pong providers must resolve an exact image/view generation during
preparation and retain it in the snapshot. An immutable descriptor does not freeze
image pixels: producer/consumer synchronization and image layout preparation still
apply. The narrow first rendering experiment should use static uploaded textures;
dynamic providers remain a separate acceptance gate, not implicit support.

### What can eventually be deleted

| Current code | After adopting the table contract |
| --- | --- |
| `Module.TexturePointer` and `TextureArrayPointer` descriptor pushes | Bypassed for table shaders; removable only after all legacy callers migrate |
| Per-module sampled-texture layout synthesis | Replaced by one standard layout contract for table shaders |
| Texture-name resolver and reload transfer | Unneeded for explicit-index shaders; still needed by existing named bindings |
| Duplicated texture linking in graphics, compute and mesh modules | Can share a table binder; other resource linking remains |
| Descriptor/pipeline layouts and descriptor writes | Remain on MoltenVK-compatible path; writes move to table publication |
| Image/view/sampler creation and sampled-image preparation | Remain, with stronger ownership through snapshots |

There is no credible net deletion claim yet. Keeping both interfaces adds code.
Do not transparently rewrite arbitrary existing shader texture names in this spike.

## 3. Lifetime follows recorded uses and submission completion

Current `Renderer` already has fence-based graphics completion callbacks and compute
submission hooks. Its general cleanup queue additionally uses
`frameCounter - maxFramesInFlight * 8`. The proposal targets that general retirement
rule; it does not claim that all existing resource lifetime handling is frame-based,
or that the current code has a demonstrated premature destruction bug.

### Proposed internal contracts

```kotlin
// Signatures only; none of these types exist yet.
data class CompletionPoint(val queue: QueueIdentity, val value: Long)
data class CompletionSet(val points: Map<QueueIdentity, Long>)

interface NativeAllocationLease : AutoCloseable

interface RecordingUses {
    fun retain(allocation: NativeAllocation): NativeAllocationLease
    fun submitted(point: CompletionPoint)  // moves recording pins to in-flight uses
    fun discard()                         // only when the recording cannot execute
}
```

Ownership belongs to a native allocation generation, not merely the mutable Kotlin
wrapper. Resizing `VulkanBuffer` can replace its native handle while an older
command still uses the previous one. Closing an owner requests retirement; it must
not destroy an allocation with recording pins or incomplete submitted uses.

```text
allocation A, open
    -> recording R pins A
    -> resize publishes allocation B; A becomes closed to new acquisitions
    -> R submits with completion point Q:41, transferring its pin
    -> Q's observed completed value reaches 41
    -> destroy A if no other pins/uses remain
```

Retaining an allocation and resolving its address must be atomic with respect to
resize/close. Otherwise a worker can obtain an address just as another thread frees
or replaces it. Cleanup closures capture native handles, not a mutable wrapper's
current fields. Secondary command buffers contribute their uses to the primary;
an executable recording kept for replay retains ownership until reset/discard and
must account for every subsequent submission.

### Completion source

Add a dedicated GPU-signalled retirement timeline per actual `VkQueue`, using the
existing queue-thread/lock discipline. Queues sharing the same native handle share
one sequence. Current window compute and graphics submissions both use `graphQueue`;
they do not need independent counters merely because their stages differ.

Assign increasing values in actual serialized submission order. Append a timeline
signal scoped to `ALL_COMMANDS` to the submit and publish a completion point only
after the submission succeeds. Track failures explicitly; never fabricate GPU
completion with a host signal. Poll `vkGetSemaphoreCounterValue` for observed
progress. Existing `Semaphore.currentValue()` is a CPU-maintained signal value,
not a query of completed work.

The [timeline sample](https://docs.vulkan.org/samples/latest/samples/extensions/timeline_semaphore/README.html)
describes queue counters; the
[`VkSemaphoreSubmitInfo` contract](https://docs.vulkan.org/refpages/latest/refpages/source/VkSemaphoreSubmitInfo.html)
defines the signal scope. This retirement signal does not replace producer/consumer
barriers, existing queue waits, image transitions, or memory flush/invalidate rules.

Keep fences and existing callbacks in the first integration. For a resource used
on several queues, keep the maximum required value per queue and wait for all of
them. One renderer frame or one window's completion is not sufficient. A minimized
window must not prevent polling already-submitted work.

GPU execution completion is not presentation-engine release or external-producer
acknowledgement. Keep swapchain presentation synchronization, OpenXR ownership, and
browser/encoder handoffs under their existing explicit contracts until separately
audited. Device loss is a failure/teardown path, not a successful timeline completion.

### Integration boundary and deletion condition

Start with table snapshots and buffer generations used by the experimental argument
path. Map every command submission that can touch these resources: `Renderer.submit`,
`submitCompute`, `Queue.submit`, synchronous uploads/readback, and OpenXR submission.
Either attach tracking or reject those experimental resources on an untracked path.

Only remove `cleanupStack` and the frame multiplier once every producer of deferred
cleanup has been classified and migrated. The initial source census includes shader
pipeline replacement, `VulkanBuffer`, `VBuffer`, `FrameBufferGroup`, geometry pools,
radix-sort buffers, and imported browser images. `AutoDisposer` remains a way to
request retirement; garbage collection never proves GPU completion.

Shutdown stops new recordings, releases recordings that will never submit, waits
for valid outstanding submissions, and destroys dependents before allocator/device
owners. Do not leave retirement polling dependent on another visible frame arriving.

## 1. What pointer arguments add without removing vertex buffers

Field already writes `HasBuffer` addresses into reflected structures. The additional
contract worth exploring is an owner-preserving slice plus a coherent snapshot:

```kotlin
// Proposed API, with materialBuffer already allocated using existing Field buffers.
val materialRange = BufferSlice(materialBuffer, byteOffset = 0L, byteSize = materialBytes)

arguments {
    bufferReference("materials", materialRange)
    uint("materialCount") { materialCount.toUInt() }
    textureTable(materials)
}
```

Conventional vertex buffers continue to supply position, UV, and optionally material
ID; normal index-buffer draws continue too. A small push-constant root supplies a
pointer to the shared material array and a count. A material record can contain a
colour and an albedo table index. Compute and rendering can use the same backing
material data with explicit synchronization, independent of vertex storage.

The proposed `BufferSlice` keeps the owner, offset, and range. Current
`HasBuffer.plus(offset)` already preserves its owner but provides no byte-range
contract. Extend that idea rather than replacing the buffer hierarchy. Before
encoding, validate nonnegative range, overflow, allocation bounds, addressability,
and shader-required alignment; retain the exact native generation. A shrink that
invalidates a previously valid slice fails explicitly.

Resolve argument suppliers once per draw/dispatch into an immutable prepared packet:

```text
prepared packet = root bytes
                + pinned native buffer generations
                + pinned descriptor snapshot
                + shader ABI identity
```

Address priming, argument serialization, descriptor binding, and use registration
consume the same packet. A stateful supplier must not yield buffer A for ownership
tracking and buffer B when serializing its pointer. The entire argument preparation
needs a defined mutation boundary; concurrent Kotlin edits are queued for a later
packet. This is an explicit per-command snapshot, not a promise of transactional
consistency for all arbitrary script variables.

Preserve `FrameBufferGroup` upload/readback and frame-selection semantics. Pinning
the group object is insufficient: capture the selected native member and any
upload dependency. Retaining memory also does not prevent concurrent overwrites;
existing ring/staging ownership rules or additional versioning must protect bytes.

For GPU-resident records that themselves contain pointers, tracking only the root
buffer cannot discover the allocations reachable from those pointers. The managed
API must retain an explicit transitive owner bundle/arena. Arbitrary raw `Long`
addresses remain an advanced caller-owned path and cannot acquire automatic safety
by being stored in a `Structure`.

Use existing shader reflection to pack offsets, widths, matrix layout and alignment.
Do not add a universal untyped root ABI. Keep `ShaderAbi` checks for reloads; a changed
root layout requires rebuilding the prepared packet/writer. Large roots can already
be represented by a buffer reference, subject to the device's push-constant limit.

This is mainly a consistency and lifetime improvement. It does not require a global
GPU heap, replacing VMA, new vertex/index commands, or rewriting working vertex-input
shaders. The smallest useful part could ship independently of texture tables.

## Adoption sequence and evidence needed

These are proposed future checks, not results of this sketch.

| Step | Bounded experiment | Evidence needed before expanding |
| --- | --- | --- |
| A | Completion tracking for one managed buffer generation | Delayed submission, discard, resize during recording, shared queues, two windows, multiple queues where available, submit failure; destroy exactly once and only after all uses |
| B | Prepared argument packet for an existing buffer-reference lesson | Stateful supplier evaluated once, resize preserves old recording, invalid slice rejected, conventional vertex buffers still draw correctly |
| C | Static sampled-2D table used by two modules | One draw with per-instance texture choice, matching native capture, validation clean, bounded capacity and wrong-table errors |
| D | Replace table entry while old work is pending | Old snapshot retains old view; next snapshot sees replacement; stale material indices resolve to fallback, never an unrelated reused slot |
| E | Dynamic providers and wider lifetime adoption | Video/ping-pong, multiwindow minimize/resize, shutdown, OpenXR and external image handoff verified on their actual paths |

For GPU verification, adapt canonical lessons through the Field backdoor in this
worktree on an isolated port, after initializing the required audio submodule. Use
the real renderer and inspect captures. Run the same cases on pinned MoltenVK and
KosmicKrisp builds; report hardware, OS, features and limits. An unavailable second
driver remains an explicit gap. No synthetic captures or compile-only portability claim.

Measure CPU recording cost, descriptor writes, table publication cost, retained bytes,
GPU time, and retirement lag against named textures and existing texture arrays.
Compare a single-texture sketch with a many-material scene. Keep the experiment
optional if the first case regresses and the second does not show a useful gain.
Report allocations and command counts rather than assuming that fewer API concepts
mean less work inside a Metal translation layer.

Unresolved choices: whether the table should be renderer-wide or explicitly shared
by scene; a practical capacity; whether mutable providers justify snapshot cost;
and whether argument snapshots merit a public builder or an internal repair to
`Structure` preparation. None requires committing to NoGraphicsAPI wholesale.

## Source anchors

Local links refer to the worktree's baseline files; symbols are the durable anchors.

- [Module](../../src/jvmMain/kotlin/afield/graphics/render/Module.kt): `Parameters.textures`, `textureArrays`, `TexturePointer`, `TextureArrayPointer`, `ModulePointer.reload`, reflected layout/linking, and the render-time descriptor loop.
- [ComputeModule](../../src/jvmMain/kotlin/afield/graphics/render/ComputeModule.kt) and [MeshModule](../../src/jvmMain/kotlin/afield/graphics/render/MeshModule.kt): corresponding texture/layout paths.
- [DescriptorSetLayout](../../src/jvmMain/kotlin/afield/graphics/vulkan/device/DescriptorSetLayout.kt): current push-descriptor/count/indexing checks; ordinary table layouts would be a separate configuration.
- [Device](../../src/jvmMain/kotlin/afield/graphics/vulkan/device/Device.kt): enabled address/indexing/timeline features and currently disabled update-after-bind bits.
- [Renderer](../../src/jvmMain/kotlin/afield/graphics/vulkan/Renderer.kt): queue serialization, recording workers, completion callbacks, `submit`, `submitCompute`, `cleanupStack`.
- [Queue](../../src/jvmMain/kotlin/afield/graphics/vulkan/device/Queue.kt), [Semaphore](../../src/jvmMain/kotlin/afield/graphics/vulkan/device/Semaphore.kt), [OpenXRHmdTarget](../../src/jvmMain/kotlin/afield/graphics/vulkan/targets/OpenXRHmdTarget.kt): other submission and ownership boundaries.
- [VulkanBuffer](../../src/jvmMain/kotlin/afield/graphics/vulkan/buffers/VulkanBuffer.kt), [FrameBufferGroup](../../src/jvmMain/kotlin/afield/graphics/vulkan/buffers/FrameBufferGroup.kt), [Structure](../../src/jvmMain/kotlin/afield/graphics/vulkan/buffers/Structure.kt), [AutoDisposer](../../src/jvmMain/kotlin/afield/graphics/vulkan/util/AutoDisposer.kt): native generations, address resolution, transfer dependencies and disposal.
- [ShaderAbi](../../src/jvmMain/kotlin/afield/graphics/shader/ShaderAbi.kt): reflection-based reload compatibility.
- [Canonical lesson index](../../public/docs/assets/canonical_graphics/LESSONS.md): conventional triangle, buffer-reference particles, texturing, multi-texture, and ping-pong lessons.
- [NoGraphicsAPI Vulkan contract](https://github.com/sebbbi/NoGraphicsAPI/blob/e025cc12793ce7d77ba35298860f36eb64a62b45/docs/vulkan-support.md): architectural reference; its new-extension backend is not adopted here.

## Verification of this artifact

This is a source-grounded design document containing proposed APIs, lifecycle rules,
deletion conditions and an experiment plan. No production code was changed, no
shader sketch was executed, and no Vulkan performance or portability result is claimed.

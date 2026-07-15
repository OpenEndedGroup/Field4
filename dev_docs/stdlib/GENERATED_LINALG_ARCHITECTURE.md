# Field generated linear algebra: architecture and extension guide

## Why this exists

`afield.stdlib.linalg` replaces Field's former JOML dependency with a small,
source-compatible-enough API whose mathematical operations and storage backends
are under Field's control.

The immediate result is deliberately ordinary:

```kotlin
val p = Vector3f(1f, 2f, 3f)
p.x += 1f
p.normalize()
```

The architectural result is more important. A vector operation is no longer
tied to three fields in one JVM object. The generator can lower the same
operation into heap objects, frame-arena handles, or mapped memory views.

## Shipped surface

| Area | Current implementation |
| --- | --- |
| Heap values | Mutable `Vector2f`, `Vector3f`, `Vector4f`, quaternions and matrices |
| Read interfaces | `Vector2fc` through `Vector4fc`, `Quaternionfc`, matrix interfaces |
| Scratch storage | `FrameArena`, `ArenaVec2f`, `ArenaVec3f`, `ArenaVec4f` |
| Thread convenience | `FrameArena.current()` and `frameScratch { ... }` |
| Foreign/mapped storage | `SegmentVec3Buffer`, packed-12 and aligned-16 layouts |
| Geometry | Closest point, barycentric, containment, ray/plane/triangle/AABB and sphere/AABB tests |
| Generation | Typed vector-operation AST plus deterministic compatibility templates |

There is no JOML dependency or JOML compatibility package left in the runtime.

## Normative design rules

1. Heap, scratch, persistent and mapped storage are distinct representations.
2. Destination operations do not allocate a result.
3. Operations that document aliasing must work when either input is the destination.
4. Arena lifetime is visible at the call site.
5. Frame handles never silently cross a persistence boundary.
6. A mapped view does not own or extend the lifetime of GPU memory.
7. CPU memory safety and Vulkan host/device synchronization are separate obligations.
8. Generated source is checked into the repository and must be deterministic.

## Heap backend

Heap vectors are the normal Field API. They have mutable public component fields,
read-only interfaces and destination overloads:

```kotlin
val a = Vector3f(1f, 2f, 3f)
val b = Vector3f(4f, 5f, 6f)
val result = Vector3f()
a.cross(b, result)
```

Use heap values for box state, long-lived properties, serialization, debugging and
APIs where object identity is useful. Nothing about the arena work makes this
common case longer.

## Frame arena backend

### Layout and handles

`FrameArena` owns a confined FFM `MemorySegment`. Vec2, Vec3 and Vec4 currently
occupy one 16-byte slot each. The generated handle is an inline `Long`, not an
object containing components.

The handle contains an allocation stamp and byte offset. Debug access verifies:

- the owning platform thread;
- arena lifetime and closed state;
- reset/mark invalidation;
- slot identity and vector kind;
- live cursor bounds.

Growth doubles capacity, copies the live prefix and retains the new high-water
capacity. Reset only rewinds the cursor and advances the invalidation floor.

### Explicit lifetime

```kotlin
FrameArena().use { arena ->
    arena.scoped {
        val a = vec3(1f, 2f, 3f)
        val b = vec3(4f, 5f, 6f)
        val result = vec3()
        a.cross(b, result)
    }
}
```

`scoped` is non-suspending. A handle must not escape the block. Nested scopes and
manual marks are LIFO and invalidate only allocations made after their mark.

### Per-thread convenience

Stable Field threads can use:

```kotlin
frameScratch {
    val normal = vec3()
    edgeA.cross(edgeB, normal).normalize()
}
```

`frameScratch` uses `FrameArena.current()`, one confined arena per platform thread.
Call `FrameArena.closeCurrent()` when retiring a long-lived worker.

Do not use this convenience indiscriminately on millions of short-lived virtual
threads: each participating thread would retain native high-water capacity. Give
such tasks an explicit arena, process them in a bounded worker pool, or keep their
math on the heap until measurement justifies something else.

### Thread safety

`FrameArena` is intentionally thread-confined, not synchronized. Debug mode rejects
cross-thread access. Turning allocation into an atomic bump would not make vector
mutation safe and would give a misleading partial guarantee.

| Need | Correct model |
| --- | --- |
| Scratch math on several workers | One `FrameArena` per worker/thread |
| Transfer result to another thread | Copy into a heap value or shared owned storage |
| Shared read-only bulk data | Shared `MemorySegment`/buffer view after publication |
| Concurrent mutation | Partition disjoint ranges or synchronize at the owning data structure |
| Frame reset | Owner thread only, after all work using that arena has joined |

A future `PersistentArena` may use shared FFM storage, but a shared *frame* arena is
not currently planned as the default abstraction.

## Segment and mapped-buffer Vec3 backend

`SegmentVec3Buffer` is a non-owning view over a `MemorySegment` or the remaining
region of a direct `ByteBuffer`. Its inline `SegmentVec3f` handles include a buffer
identity and offset, so accidentally using a handle with another view fails.

```kotlin
val positions = SegmentVec3Buffer.of(mappedBytes, count, Vec3StorageLayout.ALIGNED_16)
with(positions) {
    positions[0].set(1f, 2f, 3f)
    positions[0].cross(positions[1], positions[2])
}
```

The vector operations are emitted from the same typed operation model as heap and
arena vectors.

### Vec3 layouts

| Layout | Stride | Alignment | Intended use |
| --- | ---: | ---: | --- |
| `PACKED_12` | 12 bytes | 4 bytes | Dense CPU arrays, packed vertex streams, formats explicitly declaring 3 floats |
| `ALIGNED_16` | 16 bytes | 16 bytes | SIMD-friendly records and array layouts equivalent to a 16-byte-stride GPU vec3 |

An aligned write zeros the padding lane. This backend models an *array stride*.
It does not assert that every GLSL struct member following a `vec3` starts at byte
16; that depends on the complete block layout and must come from the future
structure-layout generator.

### Vulkan rules

The view makes CPU addressing convenient; it does not perform Vulkan synchronization.
Before CPU writes, the caller must obey the buffer's memory properties, mapping
lifetime, host coherency flush requirements, fences and queue ownership. Before CPU
reads, it must invalidate non-coherent ranges where required and wait for GPU work.

`SegmentVec3Buffer.of(ByteBuffer, ...)` views `buffer.slice()`, so byte zero is the
buffer's current position. Keep the Vulkan allocation and mapped buffer alive for
the complete use of the view.

## Geometry helpers

The first geometry layer favors operations repeatedly needed in creative coding,
picking, collision probes and mesh tools:

- closest point on 2D/3D segments;
- closest points between two 3D segments;
- closest point on a 3D triangle or AABB;
- barycentric coordinates and point-in-triangle;
- point/AABB and point/sphere containment;
- AABB/AABB, sphere/sphere and AABB/sphere overlap;
- 2D segment/segment and ray/triangle distance/hit point;
- ray/plane, ray/sphere, ray/AABB and segment/AABB intersection.

Heap forms accept read interfaces and write to explicit destinations. Hot arena
forms currently cover segment closest point, AABB closest point/containment and
ray/AABB. Expand arena coverage from measured Field call sites rather than mirror
every overload blindly.

## Generator architecture

The vector operation model in `AlgebraModel.kt` is a typed expression tree:
component reads, scalar parameters, constants, binary expressions and calls. The
heap, frame-arena and segment Vec3 emitters lower that same model into specialized
storage access. This is the most important proof that generation is not just a
way to stamp out similar classes.

Quaternion, matrix and small compatibility types currently come from deterministic
Kotlin templates. They are generated and freshness-checked, but their mathematics
has not yet been moved into the typed AST. That is the main generator debt.

### Other appropriate generation targets

The same semantic and layout models can usefully produce:

- `FloatArray`, direct `ByteBuffer`, FFM and Vulkan-mapped accessors;
- array-of-structs and struct-of-arrays bulk kernels;
- GLSL declarations, helper functions and CPU/GPU offset assertions;
- vertex descriptions and Vulkan binding/attribute metadata;
- std140/std430/scalar-layout structures, including nested structs and arrays;
- randomized differential and aliasing tests;
- serialization adapters and schema migrations;
- API tables and layout diagrams for documentation;
- future Vector API/SIMD bulk loops;
- future JVM value-object carriers without changing mathematical definitions.

Generation is not appropriate for policy that cannot be made explicit. Ownership,
GPU synchronization, singular-operation behavior and aliasing rules must be in the
model rather than hidden in emitter strings.

## What is still missing

### Backend completeness

- `PersistentArena` and region ownership;
- arena quaternion and matrix handles;
- generated `FloatArray` and broader segment vector/matrix views;
- struct schemas shared by Kotlin, GLSL and Vulkan reflection;
- generated bulk AoS/SoA operations;
- mapped-buffer adapters integrated directly with Four's buffer ownership objects.

### Numerical and API work

- move quaternion/matrix semantics from templates into the typed AST;
- define zero-normalization and singular-inverse policy explicitly;
- remove temporary arrays from matrix inversion/determinant hot paths;
- broaden randomized differential and degeneracy testing;
- add frustum extraction/classification and projection/unprojection utilities;
- add the remaining high-value geometry operations below.

### High-value geometry queue

1. ray/capsule and segment/triangle;
2. point/polygon distance;
3. 2D segment intersection, winding containment and polygon clipping;
4. plane/sphere/AABB classification and frustum culling;
5. triangle/triangle and swept sphere/capsule tests;
6. robust predicates or double-precision variants where topology matters.

JOML can remain a useful API inventory and same-license reference, but new code
should be expressed in Field's conventions, destination policy and degeneracy tests.

## Valhalla seam

Heap API, operation semantics and storage lowering are separate. When the target JVM
offers the mutable value-object semantics Field needs, add a carrier emitter and
benchmark it against heap fields, frame handles and bulk memory. Existing box code
should not need a mathematical API rewrite. Until then, do not contort the public
API around preview constraints.

## Build and verification

```text
./gradlew generateLinalg
./gradlew checkGeneratedLinalg
./gradlew jvmTest
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean
```

Generated output lives under `src/generatedLinalg`; edit the model or emitter, not
the generated Kotlin.

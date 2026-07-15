# Linear algebra in Field

Field has its own generated vector, quaternion and matrix library. The everyday API
is intentionally familiar, while performance-sensitive code can select scratch or
mapped-memory storage without changing the underlying mathematics.

## The normal case stays short

Vectors and matrices in a Field code box need no storage ceremony:

```kotlin
val position = Vector3f(1f, 2f, 3f)
val camera = Matrix4f().translate(0f, 0f, -5f)

position.x += 1f
position.normalize()
```

These are mutable heap objects. They are right for persistent box properties,
small collections, saved state and almost all code where allocation is not the
bottleneck.

Operations can mutate the receiver or write into an explicit destination:

```kotlin
val normal = Vector3f()
edgeA.cross(edgeB, normal)       // no result allocation
normal.normalize()
```

## Frame scratch: many temporary vectors, one lifetime

When a loop creates large numbers of short-lived values:

```kotlin
frameScratch {
    val a = vec3(1f, 2f, 3f)
    val b = vec3(4f, 5f, 6f)
    val normal = vec3()

    a.cross(b, normal).normalize()
    println(normal.x)
}
```

`frameScratch` uses one `FrameArena` per platform thread. Its vector handles are
small inline values; their components live in a 16-byte-aligned `MemorySegment`.
Leaving the block restores one arena mark, invalidating the values together.

For explicit ownership and telemetry:

```kotlin
FrameArena(256 * 1024).use { arena ->
    arena.scoped {
        val p = vec3(10f, 20f, 30f)
        // ...
    }

    println("high water: ${arena.highWaterBytes} bytes")
    println("growths: ${arena.growthCount}")
}
```

Arena values must not escape their scope. Copy deliberately when a result becomes
persistent:

```kotlin
val persistent = Vector3f()

frameScratch {
    val temporary = vec3(1f, 2f, 3f)
    temporary.copyTo(persistent)
}
```

The frame arena is thread-confined rather than synchronized. Parallel workers each
get their own `FrameArena.current()`. This keeps allocation and vector mutation
honest: there is no partly-thread-safe shared scratch object.

Development builds include stale-handle, type, lifetime and thread checks. A
distribution can compile those checks out with `-PlinalgArenaChecks=false`; this is
a build profile rather than a global runtime switch, so checked builds remain the
default.

## Packed and aligned vector buffers

For dense or mapped data:

```kotlin
val bytes = java.nio.ByteBuffer.allocateDirect(16 * 1_000)
val positions = SegmentVec3Buffer.of(
    bytes,
    count = 1_000,
    layout = Vec3StorageLayout.ALIGNED_16,
)

with(positions) {
    positions[0].set(1f, 2f, 3f)
    positions[1].set(4f, 5f, 6f)
    positions[0].cross(positions[1], positions[2])
}
```

Two layouts are available:

| Layout | Bytes per Vec3 | Use it for |
| --- | ---: | --- |
| `PACKED_12` | 12 | tightly packed CPU arrays and vertex formats declared as three floats |
| `ALIGNED_16` | 16 | aligned records and GPU arrays with a 16-byte Vec3 stride |

The same generated API exists as `SegmentVec2Buffer` and `SegmentVec4Buffer`. Vec2
supports packed-8, aligned-8 and 16-byte-stride records; Vec4 supports packed-16 and
16-byte-aligned records.

`SegmentVec3Buffer` can also wrap an existing `MemorySegment`. Its handles are
allocation-free and tied to their buffer view, while the segment or mapped Vulkan
allocation remains the owner.

For Vulkan-mapped memory, this API solves addressing and layout—it does not replace
fences, queue ownership, host-coherency flush/invalidate calls, or allocation
lifetime. Those still belong to the Vulkan buffer owner.

Field code boxes import the linalg, arena and storage packages automatically. In a
regular Kotlin source file, import `afield.stdlib.linalg.arena.*` and/or
`afield.stdlib.linalg.storage.*` explicitly.

## Geometry that does not allocate results

Closest point:

```kotlin
val closest = Vector3f()
closestPointOnTriangle(point, a, b, c, closest)
```

Picking a triangle:

```kotlin
val hit = Vector3f()
if (intersectRayTriangle(rayOrigin, rayDirection, a, b, c, hit)) {
    println("hit at $hit")
}
```

Ray versus bounds:

```kotlin
val nearFar = Vector2f()
if (intersectRayAabb(origin, direction, minimum, maximum, nearFar)) {
    println("enter ${nearFar.x}, leave ${nearFar.y}")
}
```

The initial geometry set includes:

- closest point on segments, between segment pairs, on triangles and axis-aligned boxes;
- barycentric coordinates and triangle containment;
- point/AABB and point/sphere containment;
- AABB/AABB, sphere/sphere and AABB/sphere overlap;
- 2D segment/segment, ray/triangle, ray/plane, ray/sphere, ray/AABB and segment/AABB intersection.

Arena overloads are available for the most common scratch forms:

```kotlin
frameScratch {
    val nearFar = vec2()
    if (intersectRayAabb(origin, direction, minimum, maximum, nearFar)) {
        // no heap vector was created
    }
}
```

## Choosing a representation

| Situation | Use |
| --- | --- |
| Persistent box state or a handful of values | `Vector3f`, `Matrix4f`, `Quaternionf` |
| Temporary math in a stable render/update thread | `frameScratch { ... }` |
| A subsystem with explicit scratch ownership | `FrameArena` |
| Packed vector, vertex or point data | `SegmentVec2/3/4Buffer` with a `PACKED_*` layout |
| Aligned mapped records | `SegmentVec2/3/4Buffer` with an `ALIGNED_*` layout |
| Passing a scratch result into persistent state | Explicit `copyTo`/`set` |

## Why generation matters

Heap, arena and segment Vec2/Vec3/Vec4 operations are emitted from one typed operation model.
That lets Field add a storage backend without hand-copying every cross product,
normalization and aliasing rule.

The same mechanism is intended to grow into:

- generated CPU and GLSL structure layouts;
- Vulkan vertex and storage-buffer metadata;
- array-of-structs and struct-of-arrays kernels;
- `FloatArray` and broader `MemorySegment` views;
- SIMD bulk operations;
- future JVM value-object representations.

Core quaternion and matrix equations now have a typed transform model as well;
their generated compatibility classes delegate to it. Arena matrices/quaternions,
persistent arenas and live-generated full GPU structures are next steps, not hidden
features. Those GPU schemas will be generated when Field assembles a live shader,
not assumed to exist at Gradle build time. The ordinary heap API remains the default
while those specialized representations grow around it.

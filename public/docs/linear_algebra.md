# Linear algebra in Field

Linear algebra is the small vocabulary underneath movement, cameras, lighting,
meshes, simulation, and spatial data. Field's everyday vector and matrix API is
intentionally ordinary:

```kotlin
val position = Vector3f(1f, 2f, 3f)
position.x += 1f
position.normalize()
```

You do not need to understand storage backends to begin. This page starts with
the geometric ideas, builds a few useful recipes, and only then introduces the
allocation-free arena and mapped-memory forms.

For an exhaustive list of generated vector operations, see the
[generated vector API reference](linear_algebra_api.md).

## Vectors are arrows (and sometimes points)

A `Vector2f` is two numbers, a `Vector3f` is three, and a `Vector4f` is four:

```kotlin
val screen = Vector2f(320f, 180f)
val position = Vector3f(10f, 20f, -5f)
val color = Vector4f(1f, 0.4f, 0.1f, 1f)
```

The most useful mental picture is an arrow. Its components say how far the
arrow travels along each axis. Its `length()` is the arrow's magnitude.

The same three numbers can represent a point or a displacement. The operation
usually reveals which meaning you intend:

```kotlin
val from = Vector3f(2f, 1f, 0f)
val to = Vector3f(7f, 4f, 0f)
val displacement = Vector3f(to).sub(from) // (5, 3, 0)
val distance = displacement.length()
```

Subtracting two points gives the arrow between them. Adding an arrow to a point
moves the point.

## Length and normalization

`length()` answers “how much?”; `normalize()` keeps the direction and changes
the length to one:

```kotlin
val velocity = Vector3f(6f, 8f, 0f)
println(velocity.length()) // 10

val direction = Vector3f(velocity).normalize()
println(direction)         // (0.6, 0.8, 0)
println(direction.length()) // 1
```

A unit vector is useful whenever direction and magnitude should be independent:

```kotlin
val speed = 4f
val step = Vector3f(direction).mul(speed)
position.add(step)
```

The zero vector has no direction. Normalizing it produces non-finite floating-
point components, so guard it when zero is possible:

```kotlin
if (direction.lengthSquared() > 1e-12f) direction.normalize()
```

Use `lengthSquared()` and `distanceSquared()` for comparisons. They avoid the
square root while preserving the ordering:

```kotlin
if (candidate.distanceSquared(mouse) < 20f * 20f) {
    // within 20 units
}
```

## Dot product: “how much do these agree?”

The dot product multiplies matching components and adds them:

```text
a dot b = a.x*b.x + a.y*b.y + a.z*b.z
```

That modest formula is an extremely useful directional test. For normalized
vectors:

- `1` means they point the same way;
- `0` means they are perpendicular;
- `-1` means they point in opposite directions;
- values between those extremes describe partial agreement.

```kotlin
val forward = Vector3f(0f, 0f, -1f)
val towardTarget = Vector3f(target).sub(position).normalize()
val agreement = forward.dot(towardTarget)

if (agreement > 0f) println("the target is somewhere in front")
if (agreement > 0.9f) println("the target is close to straight ahead")
```

For unit vectors, the dot product is the cosine of the angle between them. A
common lighting calculation asks how much a surface normal agrees with the
direction toward a light:

```kotlin
val brightness = normal.dot(directionToLight).coerceAtLeast(0f)
```

The clamp says that a light behind the surface contributes no direct light.

### Projection with a dot product

To measure how much of a vector lies along a unit direction:

```kotlin
val amountAlongAxis = velocity.dot(axis) // axis must have length 1
val alongAxis = Vector3f(axis).mul(amountAlongAxis)
val sideways = Vector3f(velocity).sub(alongAxis)
```

This decomposes a velocity into “along the track” and “across the track.”

## Cross product: “which way is perpendicular?”

The 3D cross product returns an arrow perpendicular to both inputs:

```kotlin
val edgeA = Vector3f(b).sub(a)
val edgeB = Vector3f(c).sub(a)
val normal = edgeA.cross(edgeB, Vector3f()).normalize()
```

This is the standard way to find a triangle's surface normal. Order matters:
`a.cross(b)` and `b.cross(a)` point in opposite directions. Field follows the
right-hand rule: curl the fingers of your right hand from the first input toward
the second; your thumb points along the result.

Before normalization, the result's length is the area of the parallelogram made
by the inputs. Parallel inputs therefore produce the zero vector.

## Interpolation: values between values

`lerp` traces a straight line from one vector to another:

```kotlin
val start = Vector3f(0f, 0f, 0f)
val end = Vector3f(100f, 40f, 0f)
val halfway = start.lerp(end, 0.5f, Vector3f())
```

At `t = 0` the result is `start`; at `t = 1` it is `end`. Values outside that
range extrapolate beyond the endpoints.

For repeated “position plus direction times amount” calculations, `fma` expresses
the whole pattern without a temporary vector:

```kotlin
position.fma(deltaSeconds, velocity) // position += velocity * deltaSeconds
```

## Matrices and quaternions

A `Matrix4f` combines translation, rotation, scale, camera, and projection
transformations. A `Quaternionf` represents rotation without privileging a
particular Euler-angle order.

```kotlin
val model = Matrix4f()
    .translate(10f, 0f, -5f)
    .rotate(Quaternionf().rotateY(0.4f))
    .scale(2f)

val worldPosition = model.transformPosition(localPosition, Vector3f())
val worldDirection = model.transformDirection(localDirection, Vector3f())
```

Positions are affected by translation; directions are not. This distinction is
why Field provides both `transformPosition` and `transformDirection`.

Quaternion angles are in radians:

```kotlin
val turn = Quaternionf().rotateY(Math.toRadians(45.0).toFloat())
turn.transform(direction, direction)
```

## The normal case stays short

Heap vectors, matrices, and quaternions are right for persistent box properties,
saved state, ordinary collections, and almost all code where allocation is not a
bottleneck:

```kotlin
val p = Vector3f(1f, 2f, 3f)
p.x += 1f
p.normalize()
```

Operations can mutate the receiver or write into an explicit destination:

```kotlin
val normal = Vector3f()
edgeA.cross(edgeB, normal) // no result allocation
normal.normalize()
```

Mutating methods return the receiver. Destination methods return the destination,
so both forms chain naturally.

## Frame scratch: many temporary vectors, one lifetime

When a loop creates many short-lived values:

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
Leaving the block restores an arena mark and invalidates its allocations together.

Copy deliberately when a result becomes persistent:

```kotlin
val persistent = Vector3f()

frameScratch {
    val temporary = vec3(1f, 2f, 3f)
    temporary.copyTo(persistent)
}
```

The arena is thread-confined rather than synchronized. Parallel workers each get
their own `FrameArena.current()`. Development builds detect stale handles, wrong
types, expired scopes, and cross-thread access. A measured distribution build can
compile those checks out with `-PlinalgArenaChecks=false`.

## Packed and aligned vector buffers

For dense CPU data, direct memory, or mapped buffers:

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

| Layout | Bytes per Vec3 | Use it for |
| --- | ---: | --- |
| `PACKED_12` | 12 | tightly packed CPU arrays and vertex formats declared as three floats |
| `ALIGNED_16` | 16 | aligned records and GPU arrays with a 16-byte Vec3 stride |

Equivalent generated views exist for Vec2 and Vec4. A segment view handles
addressing and layout; the `MemorySegment`, direct buffer, or mapped Vulkan
allocation remains the owner. Vulkan fences, host-coherency flush/invalidate,
and queue ownership remain the responsibility of the buffer owner.

## Geometry recipes

Closest point on a triangle:

```kotlin
val closest = Vector3f()
closestPointOnTriangle(point, a, b, c, closest)
```

Ray picking:

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

Field includes closest-point, barycentric, containment, AABB/sphere overlap, and
common segment/ray intersection helpers. Their destination arguments follow the
same no-result-allocation convention as vectors.

## Choosing a representation

| Situation | Use |
| --- | --- |
| Persistent box state or a handful of values | `Vector3f`, `Matrix4f`, `Quaternionf` |
| Temporary math in a stable render/update thread | `frameScratch { ... }` |
| A subsystem with explicit scratch ownership | `FrameArena` |
| Packed vector, vertex, or point data | `SegmentVec2/3/4Buffer` with a `PACKED_*` layout |
| Aligned mapped records | `SegmentVec2/3/4Buffer` with an `ALIGNED_*` layout |
| Passing a scratch result into persistent state | Explicit `copyTo` or `set` |

Field code boxes import the linalg, arena, and storage packages automatically.
Regular Kotlin source files can import `afield.stdlib.linalg.*`,
`afield.stdlib.linalg.arena.*`, and `afield.stdlib.linalg.storage.*` explicitly.

## Why the source is generated

Heap, arena, and segment vector operations come from one typed operation model.
The generator emits specialized access for each storage form and emits the KDoc
seen in completion plus the [API reference](linear_algebra_api.md). This keeps the
mathematics, aliasing behavior, and explanation in one reviewable place.

The public guide is intentionally handwritten: examples and explanations should
follow the way people learn, not the order of declarations in a source file.

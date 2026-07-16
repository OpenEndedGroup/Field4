# `afield.stdlib.linalg`

## Generated linear algebra for Field·4

### Status

Draft design specification, revision 2.

This revision is grounded in the current Field·4 checkout. It is deliberately
more conservative about compatibility and arena lifetimes than the original
proposal.

### Target

Runtime package:

```kotlin
afield.stdlib.linalg
```

The first implementation is JVM-only and belongs in Field's `jvmMain` source
set. Nothing in the first implementation requires a Kotlin/JS linear-algebra
API.

---

## 1. Decision summary

Field should build a generated linear-algebra library, but it should not begin
by replacing JOML and introducing arena-backed matrices at the same time.

The work is divided into three ordered products:

1. a generated mutable heap API that reproduces the JOML surface and numerical
   behavior actually used by Field;
2. a Field migration layer covering live-code imports, generated snippets,
   serialization, visualizers, reflection-based GPU type registries, examples,
   and saved documents;
3. an explicitly scoped scratch-arena API, introduced only after the heap API
   is stable and measured in a real allocation-heavy Field path.

The immediate compatibility target is not “most of JOML.” It is:

```text
Field core + bundled examples + Field's live Kotlin environment + a checked-in
corpus of representative documents
```

The first arena target is not persistent application state or GPU buffers. It
is short-lived, thread-confined scratch arithmetic with explicit destinations.

---

## 2. Why this is a Field subsystem, not only a math library

JOML is currently part of Field's language and data model, not merely an
implementation dependency.

The current checkout uses or names JOML in all of the following places:

- JVM runtime and renderer source;
- the default imports injected into every live Kotlin box;
- Kotlin compiler import allowlists and completion classification;
- `Vec2`, `Vec3`, `Vec4`, `Quat`, and `DualQuat` aliases and the `vec(...)`,
  `quat(...)`, and `dualquat(...)` helpers;
- `FLine`, camera, projection, GLTF, Assimp, shader, push-constant, and uniform
  APIs;
- contextual JSON serializers;
- keyframer-generated Kotlin source;
- runtime visualizers and browser/table formatting through JOML read-only
  interfaces;
- hard-coded Kotlin type names in shader-structure introspection;
- exact runtime type checks used when writing vectors and matrices to buffers;
- saved document code and preambles that may contain `org.joml.*` imports or
  fully qualified names.

The current type surface is also broader than the six types in the original
draft. Field directly uses:

```text
Vector2f, Vector3f, Vector4f
Vector2fc, Vector3fc, Vector4fc
Quaternionf, Quaternionfc
Matrix2f, Matrix3f, Matrix4f and read-only matrix interfaces
DualQuaternionf
Vector2d, Vector3d, Vector4d and read-only double interfaces
org.joml.primitives.Rectanglef
```

`DualQuaternionf` is currently implemented locally in
`src/jvmMain/kotlin/org/joml/DualQuaternionf.kt`, which must be treated as part
of the migration rather than as an untouched upstream dependency.

Therefore two completion targets must be named separately:

### Float-core replacement

Field's float vector, quaternion, and matrix paths no longer depend on JOML.
JOML may remain temporarily for double geometry and `joml-primitives`.

### Complete JOML removal

No production dependency, source import, injected import, generated source,
runtime class-name registry, or representative saved document requires any
JOML artifact. This requires the used double and primitive types too.

The second target is not a success criterion for the first two milestones.

---

## 3. Goals and non-goals

### Goals

1. Define mathematical operations once in a typed semantic model.
2. Generate deterministic mutable heap implementations with the API shape
   Field actually needs.
3. Preserve the observable behavior of Field's existing transforms, cameras,
   geometry, serialization, and GPU uploads.
4. Keep old Field documents executable during a staged migration.
5. Generate allocation-free destination operations.
6. Add an explicit, thread-confined scratch representation after the heap API
   is proven.
7. Make CPU and GPU layout decisions explicit and testable without pretending
   that one layout serves every use case.

### Non-goals for the initial project

- binary compatibility with JOML;
- implementing the complete JOML API;
- changing `FLine` coordinates or other persistent state to arena handles;
- making frame handles safe to retain across suspension, callbacks, or frames;
- replacing `Structure`, `VulkanBuffer`, or Field's existing buffer ownership
  model with `MemorySegment`;
- generating a general shader language or optimizing every operation with SIMD;
- relying on escape analysis to make allocating APIs cheap;
- changing Field's matrix convention;
- silently rewriting user documents on load.

---

## 4. Compatibility inventory is a generated artifact

Before public classes are generated, add a repeatable inventory task. A simple
text search is useful but insufficient because constructors, overloads,
extension operators, interface-typed calls, and string-generated Kotlin all
matter.

The inventory must scan:

- `src/jvmMain/kotlin` Kotlin syntax and resolved calls where feasible;
- `metadata_store`, `templates`, checked-in example documents, and a curated
  user-document corpus;
- string literals containing `org.joml`, JOML simple names, or emitted Kotlin;
- default imports, compiler allowlists, completion filters, and AI/runtime
  preludes;
- `is`, `as`, `KClass`, reflection, and serializer registrations;
- `ByteBuffer` and `FloatBuffer` reads and writes;
- Kotlin extension imports such as `org.joml.plus`, `minus`, and `times`;
- use of JOML property fields and matrix component methods;
- dependencies on `joml`, `joml-primitives`, and `joml-experimental`.

Suggested output:

```json
{
  "snapshot": {
    "gitCommit": "...",
    "jomlVersion": "1.10.7"
  },
  "types": {
    "Vector3f": {
      "constructors": [{"signature": "(float,float,float)", "count": 123}],
      "methods": [{"signature": "cross(Vector3fc,Vector3f)", "count": 8}],
      "componentReads": ["x", "y", "z"],
      "componentWrites": ["x", "y", "z"],
      "bufferForms": ["get(FloatBuffer)", "get(int,ByteBuffer)"],
      "exampleCallsites": ["..."]
    }
  },
  "fqcnStrings": ["org.joml.Vector3f", "..."],
  "unresolvedDynamicUses": ["..."]
}
```

The report is versioned or reproducibly generated and diffed in CI. Counts are
evidence, not API requirements by themselves. Every exposed compatibility
member is labeled:

- `FIELD_REQUIRED`: observed in core, examples, runtime-generated code, or the
  document corpus;
- `FIELD_COMMON`: deliberately included because it is central to the type and
  cheap to support;
- `DEFERRED`: absent or costly, with a recorded callsite if later discovered.

Compile errors remain an important feedback loop, but they are a validation of
the inventory rather than the sole API-design mechanism.

---

## 5. Package and source layout

Recommended runtime packages:

```text
afield.stdlib.linalg
    Vector2f, Vector3f, Vector4f
    Vector2fc, Vector3fc, Vector4fc
    Quaternionf, Quaternionfc
    Matrix2f, Matrix3f, Matrix4f
    Matrix2fc, Matrix3fc, Matrix4fc
    DualQuaternionf (when its current Field surface is modeled)

afield.stdlib.linalg.arena
    FrameArena
    FrameScope
    ArenaVec2f, ArenaVec3f, ArenaVec4f
    ArenaQuatf, ArenaMat3f, ArenaMat4f (later)

afield.stdlib.linalg.layout
    ScalarLayout
    AggregateLayout
    PackedLayout
    Std430Layout
    VertexLayout

afield.stdlib.linalg.internal
    generated implementation details only
```

The generator should be isolated from runtime code, for example:

```text
tools/linalg-generator/
src/generatedLinalg/kotlin/
src/generatedLinalgTest/kotlin/
```

Exact Gradle wiring may follow existing repository conventions, but generator
classes must not become part of Field's runtime or live-code classpath.

Generated public source should be checked in initially. This makes API changes
reviewable, keeps IDE and live-compiler behavior unsurprising, and permits a
strict freshness check. If generation later becomes cheap and completely
reliable, this decision may be revisited.

Every generated file contains:

```text
DO NOT EDIT
generator version
model version/digest
source model path
```

---

## 6. Semantic model

The core model describes values and operations, not class source text.

At minimum it represents:

```kotlin
sealed interface ScalarType
data object F32 : ScalarType

data class AlgebraType(...)
data class Component(...)
data class Operation(...)
data class Parameter(...)
data class Local(...)
data class ApiOverload(...)
data class LayoutPolicy(...)

sealed interface Expr
sealed interface Statement
```

The expression and statement AST must include:

- typed constants and component reads;
- local scalar temporaries;
- component writes;
- arithmetic, comparisons, selection, and conditionals;
- scalar intrinsics such as `sqrt`, trigonometric functions, and finite checks;
- calls to other semantic operations;
- destination and returned-value identity;
- aliasing rules;
- preconditions and compatibility-defined degenerate behavior;
- optional operation facts such as “result is affine” or “result is normalized.”

Unrestricted strings must not encode mathematics. Emitters may use templates
for declarations and formatting, but every expression and write originates in
the typed AST.

Example:

```kotlin
operation("cross", receiver = Vec3, result = Destination(Vec3)) {
    parameter("other", Vec3Read)
    local("rx", self.y * other.z - self.z * other.y)
    local("ry", self.z * other.x - self.x * other.z)
    local("rz", self.x * other.y - self.y * other.x)
    dest.x set ref("rx")
    dest.y set ref("ry")
    dest.z set ref("rz")
    aliases(self, other, dest, allowed = true)
    returns(dest)
}
```

The local temporaries are normative: `a.cross(b, a)` and `a.cross(b, b)` must
work where JOML and the model permit those aliases.

### Semantic and compatibility models are distinct

`cross` is one semantic operation. Constructors, `cross(other)`,
`cross(other, dest)`, arena receiver syntax, and GLSL syntax are API projections
of that operation.

The compatibility model also records JVM details that matter to Field:

- method descriptors and return types;
- overload sets and numeric parameter types;
- public component fields;
- read-only interface methods;
- buffer interop methods;
- Kotlin extension operators;
- exact simple and qualified names used by generated snippets.

---

## 7. Heap API: the first production backend

Heap types are ordinary mutable JVM objects and are the only types allowed at
Field persistence, reflection, UI, and long-lived API boundaries during the
initial migration.

Representative shape:

```kotlin
interface Vector3fc {
    fun x(): Float
    fun y(): Float
    fun z(): Float

    fun add(other: Vector3fc, dest: Vector3f): Vector3f
    fun dot(other: Vector3fc): Float
}

class Vector3f : Vector3fc {
    @JvmField var x: Float = 0f
    @JvmField var y: Float = 0f
    @JvmField var z: Float = 0f

    override fun x(): Float = x
    override fun y(): Float = y
    override fun z(): Float = z
}
```

The exact declaration is generator-owned. The important requirements are:

- Kotlin supports `v.x`, `v.x = value`, and `v.x += value`;
- read-only interface calls such as `v.x()` remain available;
- Java-visible component fields and method descriptors are verified where the
  inventory requires them;
- mutable methods return the receiver;
- destination methods return the destination;
- destination methods allocate nothing;
- copying constructors and `set` copy components rather than share storage.

Initial vector constructors include the observed JOML forms, normally:

```kotlin
Vector3f()
Vector3f(s: Float)
Vector3f(x: Float, y: Float, z: Float)
Vector3f(other: Vector3fc)
```

Do not infer all other constructors by symmetry. Generate the observed and
deliberately selected common overloads, then compile-test their JVM signatures
and Kotlin overload resolution.

### Field-native helpers remain a separate layer

The current `afield.stdlib` aliases and helpers are valuable language surface:

```text
Vec2, Vec3, Vec4, Quat, DualQuat
vec(...), quat(...), dualquat(...)
swizzles, operators, collection geometry helpers
```

They should migrate from JOML to the generated heap classes without being
folded wholesale into the generated compatibility model. This keeps Field's
creative-coding vocabulary independently evolvable.

### Allocation rules

Constructors allocate. Receiver-mutating and explicit-destination methods do
not. Heap operators such as `a + b` may allocate only when already required by
Field's existing source surface, and their allocation is documented. No new
allocating operator family is added for completeness alone.

---

## 8. Numerical compatibility

JOML 1.10.7 is the initial compatibility oracle because that is Field's current
dependency. It is not the permanent semantic specification.

Before replacing any type, generate golden results for:

- ordinary and adversarial inputs;
- zero and near-zero normalization;
- singular and near-singular inversion;
- quaternion interpolation at equal and opposite endpoints;
- NaN, infinity, and signed zero where observable;
- aliasing of receiver, arguments, and destination;
- exact buffer byte order and component order;
- `equals`, tolerance comparisons, `hashCode`, and `toString` only where Field
  or documents depend on them.

Compatibility behavior wins during migration even if a safer alternative looks
more attractive. For example, zero-vector normalization must first match the
observed JOML behavior. A later safe operation can have a distinct name.

Tests classify assertions as:

- exact scalar or byte equality;
- ULP-bounded equality;
- absolute/relative tolerance;
- invariant/property equality.

Tolerance is operation-specific. A single global epsilon is not acceptable for
matrix inversion, quaternion interpolation, and basic addition.

---

## 9. Matrix and quaternion conventions

The generated API must preserve Field's current JOML convention exactly. The
specification must not use “row-major” or “column-major” without naming which of
the following is meant:

- mathematical indexing (`m(column,row)` versus `m(row,column)`);
- in-object component naming (`m00` through `m33`);
- multiplication and transform order;
- serialized `FloatBuffer`/`ByteBuffer` order;
- GLSL interpretation;
- external formats such as Assimp, GLTF, and Vulkan ray-tracing 3×4 transforms.

Create executable convention fixtures before matrix generation. At minimum:

1. Field camera `modelView()` and projection behavior;
2. composition of translate, rotate, and scale;
3. point versus direction transformation;
4. `Matrix4f.mul` and `premul` order;
5. matrix transpose before uniform upload where Field currently performs it;
6. Assimp/GLTF matrix import and TRS decomposition;
7. conversion to the explicitly row-major 3×4 array used by Vulkan
   ray-tracing instances;
8. `get(FloatBuffer)` and indexed `get(ByteBuffer)` byte fixtures.

The semantic model names mathematical rows and columns independently of emitted
JOML-compatible component names. Emitters perform the mapping.

Matrix implementations may maintain derived property flags for optimization,
but those flags are backend details. Tests must ensure mutators cannot leave
stale flags that change results.

---

## 10. Layout is policy, not a property of `Vector3f`

Field already needs multiple physical layouts for the same mathematical type.
For example:

- a vertex `vec3` is commonly written as three tightly packed floats;
- a `std430` member has 16-byte base alignment even though its payload is three
  floats;
- the current matrix buffer paths write dense component sequences;
- a scratch arena may reasonably use four lanes for every `vec3`;
- a mapped Vulkan buffer has ownership and synchronization rules unrelated to a
  frame scratch arena.

Therefore this statement is invalid as a universal rule:

```text
Vector3f = 16 bytes
```

Instead, declare layouts as combinations of semantic type and policy:

```kotlin
layout(Vec3, ScratchAligned)  // size 16, alignment 16
layout(Vec3, Packed)          // size 12, alignment 4
layout(Vec3, Std430Member)    // payload 12, base alignment 16, aggregate stride policy
layout(Mat3, PackedColumns)   // explicit component order and stride
```

The layout model must distinguish:

- payload size;
- base alignment;
- array stride;
- matrix stride and major order;
- aggregate padding;
- scalar encoding and byte order.

CPU accessors, buffer writers, generated GLSL declarations, and layout tests may
share this model. They must not share one hard-coded size table.

The first migration should adapt Field's existing `PrimitiveType`, `Structure`,
push-constant, and `tryToWrite` registries to recognize generated heap types and
prefer a semantic type registry over qualified-class-name strings. Replacing
their buffer ownership model is later work.

---

## 11. Buffer interoperability required by Field

The heap compatibility milestone includes the observed dense buffer methods,
including forms equivalent to:

```kotlin
fun get(buffer: FloatBuffer): FloatBuffer
fun get(index: Int, buffer: ByteBuffer): ByteBuffer
```

Exact return values, buffer-position behavior, indexed-write behavior, native
byte order assumptions, and matrix component order are oracle-tested against
JOML. Field currently has callsites that both rely on JOML writes and manually
advance buffer positions, so tests must characterize the current contract
rather than assume conventional NIO behavior.

Bridges to `MemorySegment` are useful for the arena backend and FFM callsites,
but they do not replace `ByteBuffer`/`FloatBuffer` compatibility in the heap
milestone.

---

## 12. Saved documents and live Kotlin compatibility

A successful source migration must cover more than repository imports.

### During coexistence

- keep JOML on the runtime classpath;
- migrate Field production files in controlled groups;
- do not inject both `org.joml.*` and `afield.stdlib.linalg.*` wildcards into
  the same live compilation because simple names become ambiguous;
- migrate `Vec*`, `Quat`, constructors, generated snippets, serializers, and
  runtime registries as a coherent change;
- run representative documents with the actual Field backdoor, not a standalone
  Kotlin compiler alone.

### Transition shim

After the JOML artifact is removed, a Kotlin-source compatibility shim under
`org.joml` may provide typealiases and operator extensions for the subset needed
by old Kotlin documents:

```kotlin
package org.joml

typealias Vector3f = afield.stdlib.linalg.Vector3f
typealias Vector3fc = afield.stdlib.linalg.Vector3fc
```

This shim:

- is introduced only after conflicting JOML classes leave the classpath;
- is source compatibility for Kotlin, not Java or binary compatibility;
- includes only inventoried names;
- has compile fixtures for `import org.joml.*`, fully qualified constructors,
  and `org.joml.plus/minus/times` imports;
- is explicitly temporary or explicitly supported—never accidental.

If the shim proves insufficient, provide an opt-in document migration command
that previews edits and preserves history. Field must not silently rewrite code
or preambles merely because a document is opened.

### Other migration seams

Update and test together:

- `Boxes.kt` default live-code imports;
- `KotlinInterface`/`KotlinInterface2` import allowlists;
- completion categorization and filtering;
- AI and ML runtime preludes;
- keyframer literal generation;
- contextual JSON serializers;
- value visualizers and H2 explorer formatting;
- shader `PrimitiveType` qualified names and generated preambles;
- `Structure`, push constants, uniform binding, and static-value tests;
- docs, smoke fixtures, and saved metadata containing qualified names.

Serialization formats should remain component-based and package-neutral. A
vector saved as numeric components must load into the new heap class without a
document-format migration.

---

## 13. Arena API: second backend, later milestone

Arena-backed values represent mutable views into storage owned by an explicit
scope. They do not implement the heap read-only interfaces initially, because
that would require an arena reference, hidden lookup, boxing, or virtual
dispatch.

Recommended user syntax:

```kotlin
FrameArena().use { arena ->
    arena.scoped {
        val a = vec3(1f, 2f, 3f)
        val b = vec3(4f, 5f, 6f)
        val out = vec3()

        a.cross(b, out)
        out.normalize()
        persistentPosition.set(out)
    }
}
```

`scoped` receives a non-suspending `FrameScope.() -> R`. Operations and mutable
component extension properties are members of `FrameScope`, so `a.cross(...)`
resolves only while the storage context is explicit.

Also permit the less implicit form:

```kotlin
val out = arena.vec3()
arena.cross(a, b, out)
```

Do not base the first public API on experimental context parameters. Field's
live compiler and Java-facing API benefit from ordinary receiver syntax, and
generated bytecode is easier to inspect.

### Field-specific escape rules

An arena handle must not be retained in:

- a `Box` property or exported value;
- an `FLine`, node property, camera, scene, mesh, or material;
- a uniform or push-constant supplier invoked later;
- a Runloop `preRun`/`postRun` hook;
- a renderer queue callback;
- a coroutine, `start {}`, `loop {}`, closure, sequence, or callback that may
  run after the scope returns;
- a display/output value, websocket payload, serializer, or history entry;
- a collection that outlives the scope.

In particular, a non-suspending scope prevents a direct `frame()`/suspension
inside the block but cannot prevent a handle from being captured. Debug checks
remain required.

`FrameArena.current()` may be added as a convenience only if it returns a
thread-confined arena and does not imply automatic reset. The owner of a Field
frame or renderer phase must call reset at a documented boundary. “Frame” is
not assumed to mean the audio thread, Vulkan queue thread, browser frame, or
arbitrary coroutine iteration.

---

## 14. Arena handle identity and lifetime checking

The logical handle is:

```text
arena identity + allocation stamp + byte offset
```

The physical release representation may be an inline `Long`, but “offset only”
is not sufficient for the required debug semantics:

- after reset, offset zero may immediately be reused;
- after restoring a nested mark, only allocations above the mark become stale;
- applying a handle to the wrong arena must be detectable;
- a global generation increment on `restore(mark)` would incorrectly
  invalidate values allocated below the mark.

The prototype must choose and benchmark one of these strategies:

1. pack owner/epoch/offset fields into a 64-bit token with documented capacity
   limits;
2. use an offset token plus a debug side table of monotonic allocation stamps;
3. generate distinct debug and release handle representations while preserving
   source API compatibility.

For nested marks, debug mode must distinguish allocations, not merely arena
resets. A stale handle to a restored slot must not become valid again when that
offset is reallocated.

Required debug checks:

```text
correct arena owner
correct thread
allocation still live
type-compatible slot
offset and alignment in bounds
arena not closed
```

Generation and allocation-stamp wraparound behavior must be specified. Debug
poisoning with NaN is useful but supplemental.

Suggested queries:

```kotlin
fun owns(value: ArenaVec3f): Boolean
fun isAlive(value: ArenaVec3f): Boolean
```

They are diagnostic aids, not a substitute for checked access.

---

## 15. `FrameArena` storage and growth

`FrameArena` is:

- `AutoCloseable`;
- thread-confined by default;
- a bump allocator over storage it owns;
- resettable in constant time in steady state;
- non-clearing in release mode;
- capable of retaining its high-water capacity.

The initial scratch layout is intentionally alignment-friendly:

```text
ArenaVec2f    16 bytes
ArenaVec3f    16 bytes
ArenaVec4f    16 bytes
ArenaQuatf    16 bytes
ArenaMat3f    policy-defined; likely 48 bytes
ArenaMat4f    64 bytes
```

These are `ScratchAligned` sizes, not GPU or serialization sizes. Padding lanes
are deterministically initialized in debug mode and must never carry hidden
semantic data.

`MemorySegment` is appropriate because Field currently targets Java 25 and
already uses the Foreign Function and Memory API. The implementation is a
custom bump allocator over one or more segments; `java.lang.foreign.Arena`
itself does not provide resettable bump semantics.

Growth is a cold path and must preserve all handles allocated in the current
scope. Candidate strategies are:

- allocate a larger segment, copy the used prefix, and keep logical offsets
  stable;
- use geometric slabs and encode/resolve a slab identifier.

Both must be benchmarked. A grow-and-copy implementation must account for the
fact that an FFM arena cannot individually free an old allocation. It may swap
and close an internal backing arena only because raw `MemorySegment` slices and
native addresses are not exposed to users.

Suggested geometric policy:

```text
new capacity >= max(required capacity, old capacity * 2)
```

No growth policy is shared automatically with mapped Vulkan memory.

### Marks

```kotlin
@JvmInline
value class ArenaMark internal constructor(internal val token: Long)

val mark = arena.mark()
try {
    // temporary allocations
} finally {
    arena.restore(mark)
}
```

Marks carry owner and nesting/lifetime information in debug mode. Restore is
LIFO. Restoring a foreign, stale, or out-of-order mark fails in debug mode.

### Persistent storage

Do not implement a vaguely specified `PersistentArena` in the first arena
milestone. When needed, define an owned region API with clear close/reset/page
semantics. “Values can be explicitly freed” and “no individual deallocation”
are different contracts and must not appear in the same initial type.

Mapped Vulkan memory is a view owned by `VulkanBuffer`, with flush,
synchronization, and renderer-thread obligations. It should become a separate
generated storage adapter, not a `PersistentArena` implementation.

---

## 16. Heap/arena bridges

All persistence crossings are explicit copies:

```kotlin
heap.set(arenaValue)              // inside a FrameScope
arenaValue.set(heap)
arena.copyTo(arenaValue, heap)
val retained = heapCopyOf(arenaValue)
```

The generator emits bridges for matching semantic types. A bridge copies
components and never stores a handle or transfers ownership.

Any API accepting `Any`, a serializer, a visualizer, or a Field property should
reject arena handles with a useful diagnostic in debug builds. This catches
escapes closer to their source than a later stale read.

---

## 17. Initial type and operation scope

The operation list is generated from the inventory plus a deliberately small
common core. The following is the expected baseline, not permission to skip the
inventory.

### Vectors

```text
construct/copy, set, zero
add, sub, component mul/div, scalar mul/div
negate, absolute, min, max
dot, length, lengthSquared
distance, distanceSquared
normalize, lerp
finite and tolerance comparisons
buffer get forms required by Field
```

Additional `Vector3f` operations:

```text
cross, angle, reflect
quaternion transform
matrix position/direction transforms required by Field
```

Additional `Vector4f` operations:

```text
matrix transform
xyz extraction/assignment required by Field helpers
```

Floor, ceil, round, perspective divide, half-vector helpers, and operators are
included only when inventoried or deliberately approved as `FIELD_COMMON`.

### Quaternion

```text
identity, set, normalize, conjugate, invert
mul, premul, dot
axis-angle construction
rotate X/Y/Z and observed Euler forms
slerp and observed normalized interpolation
transform vector
lookAlong, rotationTo, difference where observed
```

### Matrices

`Matrix2f` must not be omitted if the goal includes current Field GPU type
registries. `Matrix3f` and `Matrix4f` include the observed forms of:

```text
identity, zero, set
component accessors/mutators
transpose, determinant, invert
mul, premul
translate/scale/rotate as applicable
lookAt/lookAlong, perspective, orthographic
transform, transformPosition, transformDirection
project/unproject where observed
normal matrix and TRS extraction where observed
dense buffer writes
```

### Deferred types

Double vectors and `Rectanglef` remain on JOML during float-core replacement.
They become required before claiming complete JOML removal. Integer vectors,
double matrices, affine matrices, and dual quaternions beyond Field's current
surface remain inventory-driven.

---

## 18. Generation and Gradle integration

Required tasks:

```text
generateLinalg
checkGeneratedLinalg
testLinalg
generateJomlUsageReport
checkJomlUsageReport
```

`compileKotlinJvm` depends on generated source being present or freshly
generated according to the chosen checked-in-source workflow.

`checkGeneratedLinalg` runs the generator into a temporary directory and
byte-compares normalized output with checked-in generated files. It reports the
first differing files and the regeneration command.

Generation is deterministic with respect to:

- file order;
- declaration and overload order;
- imports;
- whitespace and line endings;
- numeric literal rendering;
- model and generator versions.

No timestamp or absolute machine path appears in generated output.

Initial emitters:

```text
KotlinHeapEmitter
KotlinCompatibilityEmitter
KotlinTestEmitter
KotlinArenaEmitter (later milestone)
KotlinBridgeEmitter (later milestone)
LayoutTestEmitter
GLSLEmitter (after layout policies stabilize)
```

Generated Java is added only if an inventoried Java source or reflection
contract cannot be expressed cleanly by generated Kotlin and verified JVM
signatures.

---

## 19. Verification strategy

### Generator tests

- typed-AST validation rejects invalid reads, writes, returns, and aliases;
- golden source tests cover stable formatting and overload order;
- every operation is emitted for every claimed backend or explicitly excluded;
- generated-source freshness is checked.

### Oracle and differential tests

While JOML remains available to tests:

```text
JOML 1.10.7 ~= generated heap
generated heap ~= generated arena
```

Use deterministic seeds and retain the seed and operands for every failure.
Random tests supplement, rather than replace, named edge-case fixtures.

### Aliasing tests

For each operation, generate only the alias patterns declared legal:

```text
dest distinct
dest === self
dest === argument 1
self === argument 1 where meaningful
```

### JVM surface tests

Use reflection or bytecode inspection to verify required:

- class and interface names;
- public fields;
- method descriptors;
- constructors;
- return types;
- absence of accidental boxing on measured arena paths.

### Field compile tests

Compile in widening rings:

1. generated library and Field-native `linalg.kt` helpers;
2. one representative geometry file;
3. camera, projection, GPU structure, GLTF, and Assimp seams;
4. all `jvmMain` production source;
5. bundled examples and smoke entrypoints;
6. representative live-document snippets through Field's actual compiler.

### Runtime and render tests

Use Field's existing automation:

```bash
uv run --project . --group backdoor python tools/run_field_tests.py --refresh-catalog

uv run --project . --group backdoor python tools/run_ui_regressions.py \
  --port 9490 --timeout 45
```

Add focused smoke entries for:

- camera transforms and projection/unprojection;
- `FLine` geometry output;
- GLTF/Assimp transforms;
- `Structure` and push-constant bytes;
- one standard renderer scene and one graphics document;
- contextual JSON round trips;
- keyframer-generated code compilation;
- legacy `org.joml.*` document compilation during the promised transition.

For visual paths, verify execution and numerical state in addition to
screenshots. Perceptual image comparison must retain the actual failure image
and log.

---

## 20. Performance validation

Performance claims are made separately for:

- generated heap receiver/destination methods versus JOML;
- generated heap allocating forms versus destination forms;
- arena explicit-destination methods;
- debug versus release arena access;
- buffer writes and bridge copies.

Measure:

```text
throughput and latency distribution
objects and bytes allocated per frame
GC count and pause time
arena used/high-water bytes
arena growth count and copied bytes
component access and handle-validation cost
boxing at generic/interface boundaries
```

Use microbenchmarks for operation cost and a representative Field render or
geometry update loop for frame behavior. The arena milestone is not accepted
because a synthetic loop reports zero allocations; it must reduce allocation or
tail latency in a real selected path without changing output.

“No steady-state managed allocation” applies to a documented local handle loop.
It does not claim that handles never box in collections, `Any`, interfaces, or
generic APIs.

---

## 21. Diagnostics

`FrameArena` exposes:

```kotlin
val capacityBytes: Long
val usedBytes: Long
val highWaterBytes: Long
val allocationCount: Long
val growthCount: Long
val bytesCopiedDuringGrowth: Long
val resetCount: Long
val ownerThread: Thread
val isClosed: Boolean
```

Optional debug facilities:

```kotlin
arena.dumpStats()
arena.resetHighWaterMark()
arena.enableAllocationTracing(sampleRate = ...)
```

Diagnostics must not allocate on every access or allocation when disabled.
Error messages identify the value type, arena, allocation/reset epoch, current
thread, and likely escape boundary.

---

## 22. Milestones

### Milestone 0 — inventory and behavioral baseline

Deliver:

- machine-readable usage report;
- curated saved-document corpus;
- JOML 1.10.7 numerical and buffer fixtures;
- matrix convention fixtures;
- list of runtime string/reflection/serialization migration seams;
- baseline allocation and render measurements for candidate hot paths.

Success means the team can state precisely what “float-core replacement” and
“complete removal” cover.

### Milestone 1 — generated heap vector vertical slice

Deliver:

```text
Vector2f/c, Vector3f/c, Vector4f/c
typed scalar/expression/statement AST
heap and test emitters
deterministic generation and freshness task
JOML oracle, alias, JVM-surface, and buffer tests
```

Success criteria:

1. generated vectors pass oracle and alias tests;
2. observed component fields, constructors, methods, and buffer forms compile;
3. Field's `Vec*` helper layer can compile against the generated vectors in an
   isolated migration source set;
4. one representative geometry source file compiles after controlled imports,
   with no semantic edits except documented compatibility gaps.

### Milestone 2 — Field float heap compatibility

Deliver:

```text
Quaternionf/c
Matrix2f/c, Matrix3f/c, Matrix4f/c
the used DualQuaternionf surface
all inventoried float-core overloads and buffer methods
```

Migrate the runtime seams listed in section 12.

Success criteria:

1. Field JVM production source and bundled float-core examples compile;
2. live Kotlin boxes use the new types through `Vec*` and new imports;
3. JSON, keyframer literals, visualizers, shader structure generation, uniform
   writes, and push constants pass focused tests;
4. selected camera, GLTF/Assimp, `FLine`, and renderer smokes match baseline;
5. JOML remains only in explicitly reported double, primitive, oracle, or
   transition-shim paths.

### Milestone 3 — document transition and float JOML removal

Deliver either the tested Kotlin `org.joml` source shim or an explicit document
migration workflow, plus legacy document fixtures.

Success criteria:

1. the float JOML production dependency is removed;
2. representative existing documents open and run without silent rewriting;
3. no hard-coded float JOML class name remains outside the intentional shim and
   tests;
4. the usage-report diff explains every remaining JOML reference.

### Milestone 4 — vector scratch arena proof

Deliver:

```text
FrameArena, FrameScope
ArenaVec2f, ArenaVec3f, ArenaVec4f
heap/arena bridges
debug owner/thread/allocation lifetime checks
marks and growth
telemetry and allocation benchmarks
```

Convert exactly one measured scratch-heavy Field path first.

Success criteria:

1. heap/arena differential and alias tests pass;
2. reset and mark restore reliably invalidate stale handles in debug mode;
3. wrong-arena and wrong-thread access fail with useful diagnostics;
4. the measured local loop performs no steady-state managed allocation;
5. the selected Field path preserves numerical/render output and improves the
   chosen allocation or frame-latency metric.

### Milestone 5 — broader types and layout generation

Only after milestone 4:

- add arena quaternion/matrix types where a measured use exists;
- adapt generated packed, std430, vertex, or mapped-buffer views;
- generate GLSL declarations/helpers from explicit layout policies;
- add CPU/GPU layout assertions;
- replace remaining double and primitive JOML types if complete removal remains
  valuable.

---

## 23. Normative design rules

1. Mathematical operations are defined once in a typed semantic model.
2. API compatibility, storage, layout, allocation, and language target are
   separate projections of that model.
3. The heap backend is completed and migrated before arena integration becomes
   a compatibility requirement.
4. Field compatibility includes live compilation, generated source,
   persistence, reflection/type registries, GPU writes, and saved documents.
5. Destination operations allocate nothing and return their destination.
6. Generated operations implement every declared legal alias pattern.
7. Degenerate numerical behavior is specified by fixtures; it is not left to
   backend accident.
8. Matrix convention and buffer order are executable tests, not prose alone.
9. A mathematical type has no universal byte size; every physical layout names
   a policy.
10. Frame-arena lifetimes are explicit, thread-confined, non-suspending, and
    checked in debug mode.
11. Offset-only handles are not claimed to provide stale-handle or ownership
    safety.
12. Persistent Field state never silently retains a frame handle.
13. Mapped Vulkan memory remains under Vulkan buffer ownership and
    synchronization rules.
14. Raw native pointers and `MemorySegment` slices are not exposed from the
    scratch API.
15. Generated source is deterministic, reviewable, and freshness-checked.
16. JOML is an initial oracle and transition dependency, not the permanent
    semantic definition.
17. Old documents are not silently rewritten.
18. Performance claims require allocation and tail-latency evidence in Field,
    not only microbenchmark throughput.

---

## 24. Prototype questions that remain open

The following require code generation, bytecode inspection, and benchmarks:

1. Does the arena use a packed token, a debug side table, or separate debug and
   release representations?
2. Is grow-and-copy or geometric slab storage faster for Field's actual scratch
   sizes?
3. Which Field path is the best first arena conversion based on JFR allocation
   evidence?
4. Which heap operators are genuinely part of the live-language contract?
5. Does any inventoried Java/reflection use require generated Java rather than
   Kotlin with verified JVM fields and descriptors?
6. Can the Kotlin `org.joml` typealias shim cover the representative saved
   corpus, including operator imports and fully qualified constructor strings?
7. Which double and `joml-primitives` types are worth generating after the
   float-core migration?
8. Should matrix property flags be generated, computed conservatively, or
   omitted until profiles show a regression?

These are prototype questions. The following are no longer open:

- the first arena API will not hide allocation in ordinary heap operators;
- arena handles will not initially implement heap read-only interfaces;
- a universal 16-byte `Vector3f` layout will not be used for GPU and packed
  storage;
- `FrameArena` will not reset itself based on an ambiguous global notion of a
  Field frame;
- the project will not claim Field compatibility after compiling only one
  source file.

---

## 25. Final success criterion

The project succeeds when all of the following are true:

```text
Field's generated heap math types replace the inventoried float JOML surface;
live boxes, generated snippets, serializers, type registries, examples, and
representative existing documents continue to work;
camera, geometry, buffer, and render behavior remains within explicit fixtures;
and one measured scratch-heavy path uses an explicit checked arena to reduce
steady-state allocation without leaking frame-scoped values.
```

Complete removal of double and primitive JOML dependencies is a separately
measured follow-on goal, not a condition that should delay proving the generated
heap and scratch designs.

---

## 26. Current repository seams to inspect first

This list is an implementation index, not a substitute for the generated usage
report:

```text
build.gradle.kts
    pinned JOML artifacts, Java 25 toolchain, Kotlin 2.4 compiler

src/jvmMain/kotlin/afield/stdlib/linalg.kt
    Vec aliases, constructors, swizzles, operators, geometry helpers

src/jvmMain/kotlin/afield/Boxes.kt
    default imports injected into live Kotlin

src/jvmMain/kotlin/afield/KotlinInterface.kt
src/jvmMain/kotlin/afield/KotlinInterface2.kt
src/jvmMain/kotlin/afield/KotlinCompletionSupport.kt
    import policy, type exposure, completion classification

src/jvmMain/kotlin/afield/JomlSerialization.kt
    component-based JSON serializers and registrations

src/jvmMain/kotlin/afield/stdlib/keyframer.kt
    runtime type checks, interpolation, generated Kotlin literals

src/jvmMain/kotlin/afield/visualization/ValueVisualizer.kt
src/jvmMain/kotlin/afield/server/h2explorer.kt
    read-only-interface formatting and visualization

src/jvmMain/kotlin/afield/four/modules/Introspection.kt
    GLSL primitive metadata and hard-coded Kotlin qualified names

src/jvmMain/kotlin/afield/four/buffers/Structure.kt
src/jvmMain/kotlin/afield/four/util/util.kt
src/jvmMain/kotlin/afield/four/util/superpc.kt
    runtime type dispatch, dense buffer writes, std430-like layout

src/jvmMain/kotlin/afield/four/buffers/VulkanBuffer.kt
    persistently mapped Vulkan memory and ownership boundary

src/jvmMain/kotlin/afield/four/util/Camera.kt
src/jvmMain/kotlin/afield/four/util/Projection.kt
src/jvmMain/kotlin/afield/four/util/CameraPose.kt
    matrix/quaternion convention fixtures

src/jvmMain/kotlin/afield/four/modules/simpleGLTFSupport.kt
src/jvmMain/kotlin/afield/server/assimpBrowsing.kt
    imported matrix order, TRS decomposition, animation interpolation

src/jvmMain/kotlin/afield/graphics/FLine.kt
src/jvmMain/kotlin/afield/graphics/FLinesAndJavaShapes.kt
    public geometry types and the double-vector removal boundary

src/jvmMain/kotlin/org/joml/DualQuaternionf.kt
    Field-owned class currently living in the upstream package
```

The first implementation PR should cite the inventory entries that justify its
API members and the focused fixtures that cover every seam it changes.

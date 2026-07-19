# Generated linear algebra architecture

`afield.stdlib.linalg` is generated from typed operation models so the same
mathematics can be emitted for several storage representations. User-facing
concepts belong in `public/docs/linear_algebra.md`; the generated operation
reference is `public/docs/linear_algebra_api.md`.

## Sources of truth

- `tools/linalg-generator/.../AlgebraModel.kt`: vector shapes, scalar kinds,
  operation expressions, and `ApiDocumentation`.
- `tools/linalg-generator/.../TransformModel.kt`: modeled quaternion and square
  matrix operations.
- `tools/linalg-generator/.../KotlinEmitter.kt`: heap, arena, segment, bulk, and
  transform emission.
- `src/generatedLinalg/kotlin/`: checked-in generated Kotlin. Do not edit it by
  hand.
- `public/docs/linear_algebra_api.md`: generated Markdown from the same model.

`./gradlew generateLinalg` regenerates Kotlin and Markdown.
`./gradlew checkGeneratedLinalg` checks both against the model byte-for-byte.

## Shipped representations

- Mutable heap float/double vectors, float quaternions and matrices,
  double-precision square matrices, and heap-only `Vector8h` half storage.
- Float `FrameArena` handles for `ArenaVec2f`, `ArenaVec3f`, and `ArenaVec4f`.
- Float `SegmentVec2Buffer`, `SegmentVec3Buffer`, and `SegmentVec4Buffer` views
  over `MemorySegment` or direct `ByteBuffer` storage.
- Generated float-array structure-of-arrays bulk kernels.
- Heap geometry helpers for common closest-point, containment, overlap, and
  intersection operations, with a smaller arena subset.

There is no `org.joml` package or compatibility dependency under `src/` in this
checkout. Default imports and persistence use `afield.stdlib.linalg`.

## Storage invariants

Heap values are the normal long-lived and serializable representation.
Destination overloads write into their explicit destination and must honor the
aliasing contract in their generated KDoc.

`FrameArena` owns confined native storage. Vec2, Vec3, and Vec4 each consume a
16-byte slot. Handles encode a stamp and offset; with checks compiled in, access
validates ownership, lifetime, mark/reset invalidation, kind, and live bounds.
The arena is thread-confined. Do not let handles escape their scope or cross a
persistence/thread boundary; copy the result to owned storage instead.

Arena checks are compiled in by default. Build with
`-PlinalgArenaChecks=false` to emit the unchecked profile. Runtime
`debugChecks=false` can disable checks only when they were compiled in; it cannot
restore checks omitted at build time. LIFO mark rules remain allocator behavior.

Segment buffers are non-owning views. Vec3 supports packed 12-byte and aligned
16-byte strides; the other layouts are declared by their generated enums. A
view does not extend the lifetime of the underlying mapped allocation and does
not perform Vulkan flush, invalidate, fence, or ownership synchronization.
`of(ByteBuffer, ...)` starts at the buffer's current position through a slice.

## Generation and documentation rules

An operation change is complete only when its typed expression and
`ApiDocumentation` are updated together, generated output is refreshed, and
`checkGeneratedLinalg` passes. Branch-heavy compatibility helpers that are not
yet represented by the typed transform model remain in deterministic templates;
do not describe them as modeled operations until they move.

The completion source index includes `src/generatedLinalg/kotlin`, so generated
KDoc is used by completion/source navigation. `SemanticRetrievalService`
separately indexes generated source as field internals and public Markdown as
documentation; those are local retrieval corpora, not an agent-only copy.

## Verification

```bash
./gradlew generateLinalg
./gradlew checkGeneratedLinalg
./gradlew jvmTest
```

Performance claims belong with reproducible JMH commands and current results in
`tools/linalg-bench/README.md`, not in this architectural map.

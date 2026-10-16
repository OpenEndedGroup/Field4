# Eight earlier helper approvals

Implemented after Marc identified their omission from the newly triaged helper batch.
Teaching originals stay unchanged until the corpus edit pass.

| Family | API and contract |
| --- | --- |
| Scalar `uint` push members | Generated mesh, compute, mesh-shader and ray-dispatch suppliers accept `Int` or `UInt`. Values are checked when evaluated and use existing scalar coercion/range rules. Kotlin has no `Int \| UInt` type, so these suppliers are declared with `Any`; other values fail with a member-specific error. Uniforms and vertex attributes retain their existing types. |
| Numeric text | `Number.f0` through `.f9` return fixed-point strings with that many fractional digits. Decimal formatting uses `Locale.ROOT`. |
| Compute dependency | `computeBarrier(graphics = false, buffer = null)` returns a `ComputeTask`: compute-write to subsequent compute shader read/write, on the same queue. `graphics = true` additionally publishes compute writes to graphics shader reads/writes. An optional Vulkan buffer limits the dependency. Host visibility and queue ownership transfers need their own operations. |
| Shared push setup | `bindPush(m1, m2) { ... }` applies the receiver block to each concrete target. Suppliers remain live and are not sampled by this helper. |
| Fullscreen draw | `fullscreenTriangle(module = fromContext())` returns a three-vertex non-indexed `Mesh` for a vertex shader using `gl_VertexIndex`. Requires triangle-list topology and no vertex attributes. Attach it to the module explicitly. |
| Bounded markdown | `markdownAppend(channel, line, maxEntries = 200, maxChars = 32768, width = null, open = true)` appends paragraphs and trims oldest entries. A single oversized entry keeps its tail; the character budget includes separators. Explicit `markdown` resets history. History survives reruns, while a late append from an older run is ignored once a newer run writes that channel. Stop/finally output remains visible until that newer write. |
| Key pairs | `scope.Key("camera" to pose, "amount" to 1.0)` copies pairs into a mutable linked map. Nulls are retained; duplicate names use the last value. |
| Mesh measurements | `compare(label, measured, analytic)` prints both values and absolute/relative error (absolute only for a zero reference). `mesh.integrate(DoubleArray/FloatArray)` uses the existing mixed vertex areas and validates field length. `randomPoints(count, seed = 0L, bounds)` uses an independent seeded Java RNG and explicit finite ordered bounds; zero-extent axes are supported. |

## Verification

- **157 focused JVM tests passed; zero failures, errors or skips.** Includes generated shader
  preambles, scalar coercion, keyframes, markdown and mesh regressions, plus ten new contract tests.
- A real Vulkan rehearsal compiles both `Int` and `UInt` generated push bindings, applies shared
  bindings to two compute dispatches, uses compute and compute-to-graphics barriers, and draws
  with the no-argument fullscreen helper. Its fragment shader reads the compute result and turns
  white only when the sum is 34. A live edit to the second push supplier changes the sum
  to 35 and produces red; restoring it produces white again. All genuine native captures
  were inspected.
- Additional real box calls exercise numeric text, key pairs, seeded points, mesh integration,
  comparison and bounded markdown, including a rerun. A separate headless browser capture shows
  their actual output.
- Rehearsal Kotlin goes through Field's mechanical import/ktfmt step before the final run.
  These are private verification copies, not edited teaching sources.

The initial GPU rehearsal used fragment execution for the renderer and therefore did not pump
frames. Launching the renderer under its persistent strand fixed the harness; no helper source
change was needed. The final rehearsal clears the correct per-frame buffer through `zeroBefore`.
The mesh test compares independent float-vector area calculations at their existing numerical
precision, rather than requiring an unattainable 1e-9 match.

[Verification record](older-verification.json) binds the evidence to source hashes.
[Evidence archive](older-evidence.tar.gz) contains runnable fixtures, genuine captures and test XML.
The landing queue regenerates public references/completion fixtures and runs the full guard.

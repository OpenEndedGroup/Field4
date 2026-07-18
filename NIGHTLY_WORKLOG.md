# Nightly worklog — 2026-07-18 / kind B — Double-precision matrix family in the linalg generator

## Plan

### Findings (how the generator is actually structured)
- The linalg generator has two kinds of "generated" output:
  1. **Model-driven** operation functions emitted by `KotlinEmitter.emitTransformSemantics()` into
     `src/generatedLinalg/.../TransformSemantics.kt`. These are the free functions
     `multiplyMatrix3`, `determinantMatrix4`, `multiplyMatrix3d`, … driven by
     `TransformModel.kt`. **The emitter is already fully precision-generic**: an operation's
     precision comes from `operation.receiver.scalar` (a `ScalarKind`), which drives literal
     formatting (`transformConstant`) and the scalar return/param type. So the f64 `Matrix3d`
     *operations* are already model-instantiated (lines 146-149 of `TransformModel.kt`).
  2. **Template passthrough**: `Transforms.kt` and `CompatTypes.kt` are literal template files
     under `tools/linalg-generator/src/main/resources/templates/`, copied verbatim (with a
     header) by `Main.generatedTemplate()`. The matrix/quaternion *classes and read interfaces*
     (`Matrix3fc/Matrix3f`, `Matrix4fc/Matrix4f`, `Matrix2fc/Matrix2f`, and the bespoke
     `Matrix3dc/Matrix3d`) live here. These are NOT emitted from the model — they carry a large
     hand-written surface (buffer I/O, perspective/lookAt, quaternion construction) that is out of
     scope for the equation model.
- So "fold Matrix3d fully into the model" is only partially meaningful: its *equations* are
  already model-driven; only its *class shell* is a template (exactly like every float matrix).
  The correct uniform treatment is therefore: keep the class shells in the templates (as today),
  and instantiate the f64 *operations* from the model for every arity — matching how `Matrix3d`
  already works.

### Scope taken (uniform, model-driven where the float family is)
1. `TransformModel.kt` — add `matrix2` (f32), `matrix2d` (f64), `matrix4d` (f64) shapes.
   - Refactor `determinantMatrix4` / `invertMatrix4` / `translateMatrix4` / `scaleMatrix4` to accept
     `shape`+`name` (mirroring the already-parameterized `determinantMatrix3`/`invertMatrix3`).
   - Add 2x2 equation helpers `determinantMatrix2` / `invertMatrix2` (column-major).
   - Instantiate operations: f32 2x2 (mul/det/invert/transpose, to keep Matrix2f symmetric),
     f64 2x2, and f64 4x4 (mul/det/invert/transpose/translate/scale).
2. Templates — add the f64 class shells alongside their float siblings:
   - `CompatTypes.kt`: enrich `Matrix2f` to the full linear surface + add `Matrix2dc`/`Matrix2d`.
   - `Transforms.kt`: add `Matrix4dc`/`Matrix4d` next to `Matrix4f`. `Matrix3dc`/`Matrix3d` stays.
   - f64 matrices expose: `set(...)`, `set(other)`, `identity()`, `zero()`, `m00()..` accessors +
     setters, `mul`, `determinant()`, `invert()`, `transpose()`, copy ctor, buffer/array I/O
     (DoubleBuffer/ByteBuffer), and for 4d `translate`/`scale`/`get3x3`. Camera/quaternion helpers
     (perspective/lookAt/rotation-from-quaternion) stay float-only by design (quaternion + Field
     camera path is f32) — noted, not silently dropped.
   - JOML column-major storage `m{col}{row}` preserved throughout (verified against Matrix3d test).
3. Regenerate: `./gradlew :tools:linalg-generator:run` (generation is NOT wired into compile).
4. Verify: `./gradlew compileKotlinJvm`; add `DoubleMatrixTest.kt` (mul against known product,
   determinant of known matrix, invert∘mul ≈ identity within f64 eps, transpose).
5. Docs: update `dev_docs/stdlib/GENERATED_LINALG_ARCHITECTURE.md` + Matrix3d KDoc ("family is
   single-precision" is now false).

Consumers: grep shows no production consumer of `Matrix2d`/`Matrix4d` (only a comment in
ValueVisualizer). No push-constant packing path touches f64 matrices. Low blast radius.

## Execution & results

### Files changed
Generator source (the authored inputs — edit these, then regenerate):
- `tools/linalg-generator/src/main/kotlin/afield/stdlib/linalg/codegen/TransformModel.kt`
  — added `matrix2`/`matrix2d`/`matrix4d` shapes; parameterized `determinantMatrix4`,
  `invertMatrix4`, `translateMatrix4`, `scaleMatrix4` by shape+name; added 2x2 det/invert
  helpers; instantiated f32 2x2, f64 2x2 and f64 4x4 operations. **No emitter change needed** —
  `emitTransformOperation` already derives precision from `receiver.scalar`.
- `tools/linalg-generator/src/main/resources/templates/CompatTypes.kt` — enriched `Matrix2f`
  (accessors/setters/set/identity/zero/mul/determinant/invert/transpose/copy-ctor) and added
  `Matrix2dc`/`Matrix2d`.
- `tools/linalg-generator/src/main/resources/templates/Transforms.kt` — added `Matrix4dc`/
  `Matrix4d`; fixed the false "family is single-precision" KDoc on `Matrix3dc`.

Regenerated output (do NOT hand-edit — produced by `./gradlew :tools:linalg-generator:run`):
- `src/generatedLinalg/kotlin/afield/stdlib/linalg/TransformSemantics.kt` (new f32/f64 ops)
- `src/generatedLinalg/kotlin/afield/stdlib/linalg/CompatTypes.kt`
- `src/generatedLinalg/kotlin/afield/stdlib/linalg/Transforms.kt`

Test + docs:
- `src/jvmTest/kotlin/afield/stdlib/linalg/DoubleMatrixTest.kt` (new, 9 tests)
- `dev_docs/stdlib/GENERATED_LINALG_ARCHITECTURE.md` (double family now documented)

### Verification (real results)
- `./gradlew :tools:linalg-generator:run` — BUILD SUCCESSFUL, 3 files regenerated.
- `./gradlew compileKotlinJvm` — **BUILD SUCCESSFUL (exit 0)**; only pre-existing warnings in
  `ReplBootstrapGateTest.kt`/`ValueVisualizer.kt`, none from this change.
- `./gradlew jvmTest --tests 'afield.stdlib.linalg.DoubleMatrixTest'` — **BUILD SUCCESSFUL**;
  JUnit report: `tests="9" skipped="0" failures="0" errors="0"`.
- `./gradlew checkGeneratedLinalg` — "generated linear-algebra sources are current" (committed
  output matches the model; no drift).
- NOT verified: no visual/Field-box exercise (correctly out of scope for a codegen/library change,
  per the mission); did not run the full smoke/test suite (only the targeted test + compile).

### Risks / unknowns
- Low blast radius: no production code consumes `Matrix2d`/`Matrix4d` today; `Matrix2f`
  enrichment is purely additive (existing 4-arg constructor + `m00()..` + `get(...)` preserved).
- f64 matrices deliberately omit the f32-only camera/quaternion helpers (`perspective`, `lookAt`,
  rotation-from-quaternion) — Field's quaternion + camera path is single precision. This is a
  documented design choice, not an oversight.
- No push-constant / GPU packing path touches f64 matrices, so packing layout is unaffected.

### Recommendation
**Merge.** Self-contained generalization: compiles clean, new numeric tests pass, generated output
is in sync with the model, and the double family is now uniform (2d/3d/4d) and documented. Future
extension (f64 vector transforms, or moving branch-heavy shells into the model) is optional follow-up.

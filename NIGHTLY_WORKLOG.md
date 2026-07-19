# Nightly worklog — 2026-07-19 / kind C — Matrix family explainer

## Plan

**Mission:** public worked-example explainer for the generated matrix family (evidence: f64
`Matrix2d`/`Matrix3d`/`Matrix4d` landed 2026-07-18, commits e636ec87/f3ac9558/34aa5190).

**Gap confirmed (step 1):**
- `public/docs/` has NO `*matrix*` doc.
- `public/docs/vector_algebra.md` does not mention matrices at all (grep clean).
- So the true gap is a matrix explainer. Proceed as briefed.

**API verified against real source (not guessed):**
- `Matrix2d`/`Matrix2dc` — `src/generatedLinalg/.../CompatTypes.kt:114-140`.
  ctor `Matrix2d(m00,m01,m10,m11)` column-major; `set`, `mul`, `determinant`, `invert`,
  `transpose`, `identity`.
- `Matrix3d`/`Matrix3dc`, `Matrix4d`/`Matrix4dc` — `src/generatedLinalg/.../Transforms.kt:233-411`.
  Matrix3d: linear-only (`set`, `mul`, `determinant`, `invert`, `transpose`, `identity`).
  Matrix4d: adds `scale`, `translate`, `translation`, `scaling`, `get[i]`, `get[col,row]`.
- **Design split (documented in Transforms.kt:360-367):** rotation-from-quaternion, camera
  helpers (`perspective`/`lookAt`), and vector-transform (`transformPosition`/`times`) stay on
  the **f32** `Matrix4f`. The f64 family covers only the precision-agnostic linear/affine
  surface. The doc teaches this split honestly rather than inventing f64 methods.
- Ground truth for numeric expectations: `src/jvmTest/.../DoubleMatrixTest.kt`
  (A*B=[[23,31],[34,46]], det examples, inverse round-trips).

**Capture harness reality (step 1 finding):**
- `ValueVisualizer` (`src/jvmMain/.../ValueVisualizer.kt`) renders matrices as diverging
  heatmaps — BUT only for `Matrix2fc`/`Matrix3fc`/`Matrix4fc`. Lines 270-271 carry a now-STALE
  comment claiming "the linalg system has no double-precision matrix/quaternion types". The f64
  matrix types now exist, so feeding a `Matrix2d` currently falls through to the reflective tree.
- Honest fix (matches "fix the code / fix the capture system"): extend the visualizer to render
  `Matrix2dc`/`Matrix3dc`/`Matrix4dc` heatmaps too, and correct the stale comment. Then the f64
  captures are real renders of real f64 values.

**Steps:**
1. Extend `ValueVisualizer`: add f64 matrix branches + `formatMatrix(Matrix{2,3,4}dc)` overloads
   reusing `renderMatrixThumbnail`; add to `shouldCheckCircular`; fix stale comment.
2. Add matrix capture cases to `ValueVisualizerDocCaptureTest.kt` writing under
   `public/docs/assets/matrix_algebra/`.
3. Write `public/docs/matrix_algebra.md` — worked example, snippets verified against real API.
4. Wire into `public/zensical.yml` nav under Reference > Linear Algebra.
5. Run the capture test (module-scoped `jvmTest --tests`), READ every PNG, confirm real heatmaps.
6. `git add` all referenced assets; grep doc for image refs and confirm each is tracked.

Disk at start: 8.9 GB free (`df -h /`). Above the 3 GB floor.

## Execution log

Files changed (3 commits, worktree-scoped):
- `766f7bf2` `src/jvmMain/kotlin/afield/visualization/ValueVisualizer.kt` — added
  `Matrix2dc`/`Matrix3dc`/`Matrix4dc` dispatch branches + three `formatMatrix` overloads
  (reusing `renderMatrixThumbnail`; f64 narrowed to Float only for the grid, header keeps full
  precision), added the f64 types to `shouldCheckCircular`, and corrected the stale
  "no double-precision matrix types" comment. Why: without this an f64 matrix rendered as a
  reflective tree, not a heatmap — the captures would not be honest.
- `eb491174` `ValueVisualizerDocCaptureTest.kt` + `public/docs/assets/matrix_algebra/*.png` —
  7 new capture cases writing to a dedicated matrix asset dir.
- `557321e5` `public/docs/matrix_algebra.md` (new) + `public/zensical.yml` (nav entry under
  Reference > Linear Algebra).

## Verification

Ran the capture test module-scoped: `./gradlew jvmTest --tests
'afield.ValueVisualizerDocCaptureTest' --console=plain` → **BUILD SUCCESSFUL in 1m 14s**, test
passed. compileKotlinJvm was clean for my changes (the one pre-existing warning at
ValueVisualizer.kt:985 is unrelated). Because the test executes each doc snippet's value and
rasterizes it, a passing run proves every snippet compiles and runs.

Every snippet in the doc corresponds to a capture; I opened all 7 PNGs and confirmed each is a
real heatmap with the expected values:
- `construct_mat2` — mat2d header `[1, 3 / 2, 4]`, grid = column-major storage. ✓
- `multiply_mat2` — `[23, 31 / 34, 46]` = A·B (matches DoubleMatrixTest). ✓
- `inverse_identity_mat2` — `[1, 0 / 0, 1]`, A·A⁻¹ = identity. ✓
- `linear_mat3` — `mat3d [det=25]`, labelled 3×3 matching set(...). ✓
- `affine_mat4` — `mat4d [det=24, t=(10, 18, 28)]`; diagonal 2,3,4,1. Confirmed the
  translate-in-scaled-frame subtlety (t = 5·2,6·3,7·4) and documented it. ✓
- `rotate_mat4f` — `mat4 [det=1]` (labelled f32, not f64), cos/sin ≈ 0.83/0.56/-0.56. ✓
- `transform_point` — `vec3(6, 8, 10)` = (1,2,3)+(5,6,7), arrow render. ✓

Disk at capture time: **8.5 GB free** (`df -h /`), well above the 3 GB floor. No Field JVM
(aot-cache path) was started — this was a plain Gradle jvmTest.

All 7 asset PNGs are `git ls-files`-tracked in this branch; every `assets/matrix_algebra/*.png`
ref in the doc resolves to a tracked file (grep-verified).

## Risks & recommendation

- **Risk (low):** all API used is verified against the generated source and every value against
  its render, so no unverified API. The one behavioural subtlety (affine translation in the
  scaled frame) is explained in the doc rather than glossed. The ValueVisualizer change is
  additive (new `when` branches + overloads) and cannot alter existing f32 rendering.
- **Recommendation: MERGE.** Fills a real public-docs gap with fully-verified, honestly-rendered
  captures, and incidentally corrects a stale comment + a genuine visualizer gap (f64 matrices
  now render as heatmaps everywhere the visualizer is used).

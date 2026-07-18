# Nightly worklog — 2026-07-18 / 3 — Vector swizzle & operator explainer

Branch: `nightly/2026-07-18/3-vector-swizzle-operators-explainer` (base `1ea64a2d`)
Kind: C (documentation / worked example with REAL rasterized captures)

## Plan

Deliver a public worked example, `public/docs/vector_algebra.md`, teaching the
swizzle + operator surface shipped by `e6177fd3`, with every image produced by
the REAL `ValueVisualizer` via the existing Playwright rasterization harness.

### Ground truth (verified against the emitted source, not the commit message)

- Read swizzles: `val VectorNfc.<seq>` for every len-2/3/4 sequence — reorder
  (`v.zyx`), repeat (`v.xx`), expand (`v.xxxx`). Returns a same-arity `VectorKfc`.
  Source: `src/generatedLinalg/kotlin/afield/stdlib/Swizzles.kt`.
- Write swizzles: `var VectorNf.<seq>` for distinct-index sequences only, so
  `v.xy = other.zw` mutates in place; repeated-index swizzles stay read-only.
- Color spelling `r/g/b/a` mirrors `x/y/z/w` (never mixed in one swizzle).
- Fixed bug: `Vector3fc.yy` / `Vector4fc.yy` now return `(y, y)` (was `(y, z)`).
- Operators (`src/generatedLinalg/.../VectorOperators.kt`):
  - `invoke` copy-with-changes: `v(y = 2f)` — Float params, keep the rest.
  - `invoke` copy-then-block: `v { normalize() }` — returns a copy, v untouched.
  - `%=` (`remAssign`): `a %= b` copies b's components into a in place.
  - `len` (infix, RESCALE — not a magnitude getter): `v len 10` returns a copy
    rescaled to length 10, keeping direction. Contrast with JOML `.length()`.
- Algebra (`src/generatedLinalg/.../linalg/CompatTypes.kt`): `+ - * /` for
  vec/vec (component-wise) and vec/scalar, plus unary minus; scalar*vec too
  (`linalg.kt`). `vec(...)` is the ergonomic constructor (`linalg.kt:484`).

### Capture path

Reuse `src/jvmTest/kotlin/afield/ValueVisualizerDocCaptureTest.kt`'s pattern:
`ValueVisualizer.visualize(value)` -> minimal dark doc -> Playwright chromium
element screenshot -> PNG. Vec2/Vec3 render as real auto-scaled arrows (Vec4
draws its x/y arrow); a `linkedMapOf` labels several related values in one shot.
New test `VectorAlgebraDocCaptureTest` writes to
`public/docs/assets/vector_algebra/`. Every referenced asset must be committed.

### Steps

1. (this) Plan into worklog.
2. Write `vector_algebra.md`; wire into mkdocs nav under Reference > Linear Algebra.
3. Add `VectorAlgebraDocCaptureTest`; run module-scoped; eyeball every PNG.
4. Verify no untracked assets; git add + commit in small steps.
5. Finish worklog; kill any JVM/Playwright I started.

## Results (done)

### Files added / changed
- `public/docs/vector_algebra.md` (NEW) — the deliverable. Worked example teaching
  vec(...) construction, read swizzles (reorder/narrow/expand/repeat incl. the fixed
  `.yy`), write swizzles, `+ - * /`, `invoke` (copy-with-changes + copy-then-block),
  `len` rescale (explicitly contrasted with `.length()`), and `%=`. House voice/format
  matched to reading_your_values.md (`diffstatic {data-lang=kotlin}` + `:::static`).
- `public/mkdocs.yml` — nav entry under Reference > Linear Algebra, after "Generated
  Vector API". (Its topical home; still in the same Reference section as Reading Your
  Values, honoring the "next to" intent while keeping the linalg pages together.)
- `src/jvmTest/kotlin/afield/VectorAlgebraDocCaptureTest.kt` (NEW) — capture harness,
  cloned from ValueVisualizerDocCaptureTest's proven pattern.
- `public/docs/assets/vector_algebra/{read_swizzles,write_swizzle,algebra,copy_operators,remassign}.png`
  (NEW) — the 5 real captures.

### Verification — ALL captures REAL and eyeballed
`./gradlew jvmTest --tests 'afield.VectorAlgebraDocCaptureTest'` → BUILD SUCCESSFUL.
Every snippet in the page compiled and evaluated as part of producing its capture, so
the prose is grounded in the actually-emitted API, not the commit message. I opened
each PNG and confirmed a correct, non-black, non-garbled ValueVisualizer render:
- read_swizzles: v=(1,2,3); zyx=(3,2,1); xy=(1,2); xxxx=(1,1,1,1); **yy=(2,2)** (fix
  confirmed) — real isometric/2D arrows.
- write_swizzle: target (0,0,0,0) dot → after `target.xy = source.zw` → (3,4,0,0).
- algebra: a=(2,1), b=(1,3), a+b=(3,4), a-b=(1,-2), a*2=(4,2), a*b=(2,3).
- copy_operators: v(y=1f)=(3,1), v{normalize()}=(0.6,0.8), v len 2=(1.2,1.6), v still (3,4).
- remassign: a (9,9) → `a %= b` → (1,2).
Untracked-asset guard: clean (all 5 referenced assets committed). No honest gaps.

### Corrections made during the work
- Mission referenced `Vectors.kt`; the real generated files are `Swizzles.kt`,
  `VectorOperators.kt`, and `CompatTypes.kt` (the latter holds `+ - * /`). Grounded on those.
- `len` is a *rescale* (infix, returns copy at length l), NOT a magnitude getter — page
  says so and points to JOML `.length()` for magnitude.
- Swizzles + invoke/len/%= are float-only; `+ - * /` also cover double/half. Closing
  paragraph states this precisely (initial draft overclaimed swizzles for double/half — fixed).

### Risks
Low. Docs-only + a test-only capture harness; no production code touched. The page won't
render in a built site until a docs build runs, but the mkdocs nav wiring matches the
existing pattern exactly.

### Recommendation
MERGE. Self-contained public worked example with 5 real, eyeballed captures of the newly
shipped surface; fills the "underexplained" gap flagged for e6177fd3.

## Processes
Playwright/chromium closed in the harness `finally`; verified no Field JVM for this
worktree and no :9092/:9477 held. Gradle daemon left running (shared, normal).

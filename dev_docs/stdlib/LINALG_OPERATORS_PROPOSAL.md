# Proposal: a fuller operator surface for live-coding linear algebra

Status: Tier 1 implemented (2026-11-25, branch `claude/linalg-tier1`; tests in
`LiveCodingShorthandsTest`, rows on `linear_algebra_api.md`). Tiers 2 and 3 are proposals. Written
against `main` at `7a1baebf87`, after `Complexf/d` and `Hypercomplexf/d` landed.

The aim is the feel of the existing `a lerp 0.5 to b`, `v len 10`, `v.n` and `a x b`: geometry that
reads as a sentence, typed while a picture is running, with no allocation surprises and no new
mental model per type. The proposal is long on purpose (it was asked for "rich and full"), and
then ranked, so that a first landing can take the top tier and stop.

## What exists today

So that nothing below duplicates it (survey of `src/` on 2026-11-25):

- **Vectors, generated** (`src/generatedLinalg/.../VectorOperators.kt`, `CompatTypes.kt`,
  `Swizzles.kt`): `+ - * /` and unary `-` against vectors and numbers (float and double),
  `Number * v` (float only), `.n`, `.length`, `a lerp t to b`, `a step d to b` (also on arena and
  segment forms), and on float heap vectors only `v len 10`, `v(y = 2f)`, `v { … }`, `a %= b`;
  read and write swizzles; `v[i]`, `v[i] = x`; collection literals (`val v: Vec3 = [1, 2, 3]`).
- **Vectors, hand-written** (`src/jvmMain/.../math/linalg/linalg.kt`): `vec(...)`, `a x b` (on
  mutable `Vector3f` only), `.v2`/`.v3`, `isNaN()`, `Collection<VecN>.median`.
- **Quaternions**: `quat(angle, axis)`, `q * r`, `q * v`, `v * q`, `Vec2 * q`, `q * 2`, `q + r`,
  `q - r`, `q %= r`; dual quaternions: `dualquat(...)`, `t * u`, `t * p`, `p * t`, `-t`, `t %= u`.
- **Matrices**: only `Matrix4fc * Matrix4fc | Vector4fc | Vector3fc` and indexed `get`/`set` on
  `Matrix4f`/`Matrix4d`. `Matrix2/3` and `Matrix4d` have no operators.
- **Complex and hypercomplex** (`Complex.kt`): the full arithmetic set, `Number` on either side for
  `Complex` only, `.conj`, `.abs`, `.arg`, `.norm`, `exp`, `ln`, `pow(real)`, `polar`, literals.
- **Lists of vectors** (`FLineGeometry.kt`): `points + v`, `points * 2`, `points - other` and the
  rest, against numbers, vectors, lists of vectors and `List<Float>`; infix `points dot v` and
  `points cross v`; `points.perp()`. `List<FLine> * Quat` exists, `List<Vec3> * Quat` does not.
- **Vector `..`** is taken: `vec(0, 0)..vec(1, 1)` is an `FLine` segment (`StandardDrawing.kt`).
- **`Number` shorthands taken**: `.f .i .l .d` (conversions, `stdlib.kt`), `.p .hz` (synth),
  `.mm .cm .m .inch .ft .yd` (lengths), `.f0`–`.f9` (formatting). `.deg`, `.rad`, `.turns`, `.im`
  are free.
- **No public scalar shaping helpers**: no top-level `lerp`, `clamp`, `remap`, `smoothstep`,
  `fract` for numbers; the ones that exist are scoped (`Functions`, `autodiff`, StackSL's CPU GLSL).
- **`then`** already means "this, then that" on `FLineSampler`, `Fn`, `FnTransform`, `FloatFilter`,
  `Stage` and RectSL's `Transform2d`, which is the precedent for using it for rotations below.

### Fixed first: `/` used to mutate

The generated mutable vectors declared their in-place `div(other)` and `div(scalar: Float)`
members as operators, so `v / 2f` and `a / b` wrote into `v` and `a`, while `v / 2` (an `Int`,
which only the `Number` extension accepts) did not. Those members are now ordinary methods:
`v.div(2f)` still divides in place, and `/` always reaches the `CompatTypes` extensions, which
return a new vector (`GeneratedLinalgTest.divisionOperatorLeavesItsOperandsAlone`). A compile with
the member operators deprecated found no caller in `src/` that relied on the mutation. Library code
that imports operators by name now needs `import afield.math.linalg.div`; box code star-imports
the package and needs nothing.

## Rules every addition follows

1. **Operators and infix calls return new values and never touch their operands.** The methods
   (`mul`, `normalize`, `rotateX`, `slerp`) stay the in-place, chainable layer. This is already the
   rule for `+`, `lerp … to`, `len`, `.n` and `Quat * Quat`; the proposal extends it, it does not
   bend it.
2. **One spelling across every type that means the same thing.** `rot` turns a `Vec2`, a `Complex`
   and (with a quaternion) a `Vec3`; `then` composes quaternions, dual quaternions, complex numbers
   and matrices; `lerp … to` and `slerp … to` work on vectors, quaternions and complex numbers.
3. **Generated where the model can express it.** Vector, complex and hypercomplex additions go in
   `tools/linalg-generator` (`emitVectorOperators`, `emitComplex`) so the `f`/`d` pair, and where
   cheap the arena and segment forms, come out together and `linear_algebra_api.md` documents them
   from the same table. Quaternion and matrix additions go in `linalg.kt` beside the operators
   already there until the transform template grows a model.
4. **One line per infix declaration, one simple parameter type.** Completion's infix support (`InfixCompletion` in `execution.kt`) finds operand types by scanning for single-line `infix fun … .<op>(param: Type)` declarations.
5. **`Number` on the parameter side** (`v len 10`, `quat(PI / 2)`), `Float` or `Double` results
   matching the receiver's precision.
6. **No new top-level single-letter names.** Box code star-imports `afield.stdlib` and
   `afield.math.linalg`, so a top-level `val X` or `fun r(...)` shadows, or is shadowed by, every
   user's own variable of that name. Constants live on companions (`Vec3.X`).
7. **No `plusAssign`/`timesAssign` on types that already have `plus`/`times`.** For a `var v`,
   `v += w` becomes an overload ambiguity compile error the moment both exist. This is the reason
   `%=` is the in-place spelling today, and it stays the only one.

## Tier 1: small, unambiguous, used constantly

Implemented as described, with one consequence worth knowing: `.deg`, `.turns` and `.rad` return
`Float`, so an angle written with them carries float precision (about 1e-7) into a double
calculation. `(1 cis 90.deg) near Complexd.I` is false at the double tolerance of 1e-9;
`1 cis PI / 2` is true.

### Angles as values

| Proposed | Meaning | Note |
| --- | --- | --- |
| `90.deg`, `0.25.turns`, `1.5.rad` | Radians as `Float`. | `.deg` alone removes most `PI / 180` and `(PI / 2).toFloat()` from box code, and makes the `Float`-angle methods (`rotateX`, `slerp`) pleasant: `q.rotateX(90.deg)`. |
| `30.deg.dir` | The unit `Vec2` at that angle: `vec(cos, sin)`. | Counterpart of `v.angle`. |
| `v.angle` | `atan2(y, x)` of a `Vec2`, in −π..π. | Same meaning as `Complex.arg`. |
| `a angleTo b` | The unsigned angle between two vectors (`Vec2`, `Vec3`); for `Vec2`, `a signedAngleTo b` too. | Uses `atan2(\|a×b\|, a·b)`, which stays accurate near 0 and π where `acos(dot)` does not. |

`TAU` as a top-level constant (Field code writes `2 * PI` everywhere). It is a word, not a
letter, and the Kotlin stdlib does not define it.

### Products and measurements as infix

| Proposed | Meaning | Note |
| --- | --- | --- |
| `a dot b` | Dot product, all vector types. | Reads better than `a.dot(b)` inside a longer expression: `(p - c) dot n`. The member `dot` already exists without `infix`, and an infix call does not fall back past a non-infix member to an extension, so this is the generator adding `infix` to the member. |
| `a x b` on `Vector3fc`, `Vector3dc` and `Vector2fc`/`Vector2dc` | Cross product; for 2D the scalar `a.x*b.y − a.y*b.x`. | Replaces today's `Vector3f.x` (mutable receiver only, so `v.xyz x w` fails). Move it into the generator rather than keep both. |
| `a dist b` | Distance, the same as `a.distance(b)`. | |
| `v.sq` | `lengthSquared()`. | Short enough to use in comparisons: `if ((p - c).sq < r * r)`. |
| `a near b` | Component-wise equality within `1e-5` (float) or `1e-9` (double); `a.near(b, 0.01)` for another tolerance (an infix function takes exactly one argument, so the tolerance form is a plain call). | The honest replacement for `==`, which compares exactly. Also on `Complex`, `Quat` (sign-insensitive, since `q` and `−q` are the same rotation) and `Hypercomplex`. |

### 2D rotation and perpendiculars

| Proposed | Meaning | Note |
| --- | --- | --- |
| `v.perp` | `v` turned a quarter turn anticlockwise, `vec(-y, x)`. | The most-typed 2D move after `.n`: normals of a polyline, tangents of a circle. |
| `v rot 30.deg` | `v` turned about the origin. | |
| `v about c rot 30.deg` | `v` turned about the point `c`. | `about` returns a small pivot value whose `rot` finishes the sentence, the same shape as `lerp … to`. |
| `v rot q` | 3D `v` turned by quaternion `q`. | The same as `q * v`, spelled the way the 2D one is. |

### Quaternion fields

| Proposed | Meaning | Note |
| --- | --- | --- |
| `q.inv`, `q.conj` | New inverse and conjugate. | `invert()`/`conjugate()` mutate; these do not. |
| `q.axis`, `q.angle` | The axis (unit `Vec3`) and angle (radians, 0..2π) of the rotation. | |
| `q.forward`, `q.up`, `q.right` | The rotated −z, +y and +x: where a camera with this orientation looks. | Saves `q * vec(0, 0, -1)` in every camera box. Decided: −z, because Field's camera (`Camera.modelView` → `Matrix4f.lookAtField`) is a right-handed look-at whose eye looks down −z. `Quaternionf.lookAlong` stays as JOML has it (it turns `direction` onto **+z**, and existing code relies on that); boxes reach for `facing` below instead. |
| `facing(dir, up)` | The orientation whose `forward` is `dir` and whose `up` leans towards `up`. | Equal to `lookAlong(d, up).inv * (180.deg about Vec3.Y)`: the half turn takes −z to +z and leaves +y alone, so `facing(d, up).forward == d.n`. Top-level, on rule 6's terms: a word, not a letter. |
| `90.deg about Vec3.Y` | A quaternion: the angle about the axis. | Same as `quat(PI / 2, vec(0, 1, 0))`, read aloud. |
| `Vec3.X`, `Vec3.Y`, `Vec3.Z`, `Vec3.ZERO`, `Vec3.ONE` (and `Vec2.*`) | Read-only (`Vector3fc`) constants on the companions. | Read-only so nobody can `normalize()` the shared `Y` axis. |

### Double-precision parity

`Vector*d` lacks `Number * v`, `len`, `v(...)`, `v { }`, `%=`, `x` and `* Quat`. These are generator flags today set for float only; turning them on for double costs nothing in design. (`* Quat` needs a double quaternion, so it waits for `Quaterniond`.)

### Complex numbers

| Proposed | Meaning | Note |
| --- | --- | --- |
| `3.im` | `Complexd(0, 3)`, so `2 + 3.im` is a complex literal. | `.i` would read better but is taken: `Number.i` is `toInt()`. |
| `r cis theta` | `Complexd.polar(r, theta)`, the textbook name (cos + i sin). | `1 cis 30.deg`. |
| `z.n`, `z.sq` | Unit-length copy, and `\|z\|²`. | Same names as vectors. |
| `z rot theta` | `z` turned by `theta`. | Same word as `Vec2`. |
| `v * z`, `z * v` | A `Vec2` multiplied as a complex number: rotate and scale a point by `z`. | Makes `Complexf` the planar counterpart of `Vec3 * Quat`. |
| `z.pow(w)` for complex `w`, `z.sqrt()`, `z.sin()`, `z.cos()` | The rest of the elementary functions. | `z.sqrt()` is the principal root; conformal maps (`z.sin()` on a grid) are the reason to have them. |
| `Number.minus/plus/times/div` for `Hypercomplex` | `2 * z` and `1 - z` for hypercomplex numbers. | `Complex` has them; `Hypercomplex` takes numbers only on the right today. |

## Tier 2: interpolation, shaping and motion

These are the moves that make a running sketch feel alive. They all extend the existing
`a lerp t to b` builder shape.

| Proposed | Meaning | Note |
| --- | --- | --- |
| `a mid b` | The midpoint. | |
| `a towards b` | The unit direction from `a` to `b` (`(b - a).n`). | |
| `a smooth t to b` | `lerp` with `t` through smoothstep. | |
| `a ease (t to curve) to b` | `lerp` with any easing curve `(Float) -> Float`. | Takes a pair because infix has one argument; the builder keeps it a sentence. Pairs well with a small `Ease` object of the usual curves, which Field does not have at top level. |
| `a damp k to b` | Framerate-independent exponential follow: moves `a` towards `b` by `1 − e^(−k·dt)` of the gap, with `dt` the runloop's frame time. | `pos = pos damp 6 to mouse` is the single most useful line in interactive sketches. Needs a frame-time source; outside the runloop it takes `a damp (k, dt) to b`. |
| `a slerp t to b` | Spherical interpolation, on `Vec3` directions, `Quat` (new value, unlike the method) and unit `Complex`. | |
| `q.pow(t)`, `q.exp()`, `q.ln()` | Quaternion powers and logarithm, new values. | `q.pow(0.5f)` is half the rotation. `exp`/`ln` are what `linear_algebra.md` sums as a power series by hand today. |
| `a then b` | Composition read in the order it happens: "`a`, then `b`" is `b * a`. On `Quat`, `DualQuat`, `Complex` and the matrices. | The Hamilton-product order is the most common bug in rotation code; `then` makes the order the reading order and leaves `*` as the algebra. |
| `v limit 5` | Clamp the length to at most 5, keeping direction. | Processing's `limit`; `len` sets it exactly. |
| `v snap 10` | Round each component to the nearest multiple of 10; `v snap grid` for a `Vec2` spacing. | |
| `v.floor`, `v.fract`, `v.abs`, `v.sign` | Component-wise, new values. | GLSL names, so a shader written in a box and its Kotlin twin agree. |
| `v.maxComponent`, `v.minComponent`, `v.sum` | Scalars. | |
| `v reflect n`, `v proj b`, `v rej b` | Reflection in the plane with normal `n`, projection onto `b`, the part of `v` perpendicular to `b`. | |

## Tier 3: vectors as ranges, sequences and fields

These change how whole drawings are written, so they need more design care, and a quick vote before
they are built.

| Proposed | Meaning | Note |
| --- | --- | --- |
| `t.remap(0.0..1.0, -1.0..1.0)`, `x.fract`, `t.smoothstep`, `x.clamp01` | The scalar shaping helpers live code reaches for between vector expressions. | None is public at top level today. `0.0..1.0` is the Kotlin range everywhere except inside a `Functions` scope, where `..` builds a `Domain`; the helpers should take `ClosedRange<Double>` so both work. |
| `(0 until 10) x (0 until 8)` | The grid of `Vec2` integer points, as a sequence. | `x` again, on `IntRange` receivers. `grid(10, 8)` is the plain alternative. |
| `points * q`, `points rot 30.deg`, `points about c rot θ` | The rotations broadcast over a `List<Vec2>` / `List<Vec3>`, returning a new list. | `points + offset` and `points * 2` already exist in `FLineGeometry.kt`; rotation is the missing piece, and `List<FLine> * Quat` already exists, so this matches it. |
| `points.centroid`, `points.bounds`, `points.closest(p)`, `points.sortedAround(c)` | Small, constantly re-written helpers. | `median` already exists in `linalg.kt`. |
| `Vec3.onSphere()`, `Vec2.inDisc()`, `v jitter 0.1` | Random vectors with the distributions people actually want (uniform on the sphere, not normalized cubes). | Beside `random`, `noise` and `perlinNoise` in `randoms.kt`, using the same seeded source (`setRandomSeed`) so a re-run reproduces. |
| `val (x, y, z) = v` | Destructuring. | `component1..4` do not exist today. |
| `v.xy0`, `v.xy1`, `v.x0z` | Swizzles with constant 0 or 1 lanes, GLSL style. | The two that matter are `xy0` (lift a 2D point) and `xyz1` (homogeneous); the generator could emit only those, not every combination. |
| `m * v`, `m * m` on `Matrix2f/2d`, `Matrix3f/3d`, `Matrix4d` | The operators `Matrix4fc` already has, on the other matrices. | `linear_algebra.md` teaches `Matrix2d(a).mul(b)` today because there is nothing shorter. |
| `translate(v) then rotate(q) then scale(2)` | Matrix builders that compose with `then`. | Top-level names `translate`/`rotate`/`scale` are too generic for rule 6; on `Matrix4f.Companion` instead: `Mat4.translate(v)`. |

## Considered and rejected

| Idea | Why not |
| --- | --- |
| `v += w` that mutates | Rule 7: ambiguous with `plus` for every `var`. `%=` already covers in-place set; `v.add(w)` is in-place add. |
| `a ^ b`, `a · b`, `a × b` | Kotlin has no `^` operator, and `·`/`×` are not identifier characters; backticked names are worse to type than `dot`/`x`. |
| `3 by 4` for `vec(3, 4)` | `by` is a soft keyword (delegation) and `val p = 3 by 4` reads like a delegated property. `vec(3, 4)` and `[3, 4]` already exist. |
| Top-level `X`, `Y`, `Z`, `i`, `j`, `k` | Rule 6. |
| Implicit `Vec2 ↔ Complex` conversion | Kotlin has none, and an extension `times` both ways (Tier 1) gives the useful half. |
| Operators on arena and segment vectors that return arena values | Arena values must not escape their scope; an expression-style operator makes that easy to do by accident. Arena code keeps the destination methods. |

## Suggested first landing

The `/` fix has landed on its own branch. Tier 1 goes next, in two branches. The first, in the generator: `.deg/.turns/.rad`, `.dir`, `.angle`,
`angleTo`, `dot`, `dist`, `.sq`, `near`, `.perp`, `rot`, `about … rot`, the `Vec2`/`Vec3d`/`Vector3fc`
cross forms, and the complex additions (`.im`, `cis`, `z.n`, `rot`, `v * z`, `sqrt`, `sin`, `cos`,
`Number` on the left of `Hypercomplex`), each with a row in `linear_algebra_api.md`'s generated
tables and behaviour tests beside `ComplexTest`. The second, in `linalg.kt`: the quaternion fields,
`about` for quaternions, the companion constants, `then`, and `slerp … to`, with the page's new
quaternion tables updated to match.

Each branch is checked the usual way: `./gradlew generateLinalg checkGeneratedLinalg`, the JVM
tests for the new behaviour, `checkBoxIdioms` on any page that starts using them, and one box run
through the backdoor that uses every new spelling together, to catch overload ambiguities with the
star-imported box preamble (the `Vector3fc.times` ambiguity in `linalg.kt` is the precedent).

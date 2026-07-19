# Vector & matrix algebra: swizzles, operators & transforms

Field's float heap vectors — `Vector2f`, `Vector3f`, `Vector4f` — read like GLSL. You can reorder their components by name, do arithmetic with the usual `+ - *` symbols, copy a vector with one field changed, and rescale it to a length, all without reaching for a method call. This surface is *generated* rather than hand-written (from the typed algebra model in `tools/linalg-generator`): the generator emits every modeled swizzle combination, and its tests cover the old hand-written `.yy` regression.

This page is a worked tour. Every image below is captured straight from Field's real value visualizer — `ValueVisualizer.visualize(...)`, the same renderer described in [Reading your values](reading_your_values.md) — fed the actual result of the code above it. The vectors draw as auto-scaled arrows (a 2D arrow for `vec2`, an isometric frame for `vec3`, the x/y arrow for `vec4`), so a reorder or a rescale is something you can *see*. Nothing here is mocked. For the exhaustive member list, see the [Generated Vector API](linear_algebra_api.md); for the wider library, the [Linear Algebra guide](linear_algebra.md).

## Constructing vectors

`vec(...)` is the ergonomic constructor: two, three, or four numbers give you a `Vector2f`, `Vector3f`, or `Vector4f`. It takes any `Number`, so integer literals are fine — they are coerced to the float components the type stores.

```diffstatic {data-lang=kotlin}
:::static
import afield.stdlib.*        // vec, swizzles, invoke / %= / len
import afield.stdlib.linalg.* // + - * / on the vector types

val a = vec(2, 1)          // Vector2f
val v = vec(1, 2, 3)       // Vector3f
val p = vec(0.25, 0.5, 1.0, 2.0)  // Vector4f
```

Everything below assumes those two imports. In a Field box they come in with the standard preamble, so you rarely type them yourself.

## Read swizzles: reorder, narrow, expand, repeat

Every length-2, length-3, and length-4 sequence of a vector's components is a read swizzle — an extension `val` on the *read* interface (`Vector3fc`, …), so it works on any view, not just a mutable vector. A swizzle returns a same-arity vector: `v.xy` is a `vec2`, `v.xyz` a `vec3`. The sequence is free to:

- **reorder** the components — `v.zyx` reverses a `vec3`;
- **narrow** to fewer components — `v.xy` drops `z`;
- **expand** past the source arity — `v.xxxx` broadcasts `x` into a `vec4`;
- **repeat** a component — `v.yy` is `(y, y)`.

```diffstatic {data-lang=kotlin}
:::static
val v = vec(1, 2, 3)
v.zyx   // vec3(3, 2, 1)  — reorder
v.xy    // vec2(1, 2)     — narrow
v.xxxx  // vec4(1, 1, 1, 1) — expand
v.yy    // vec2(2, 2)     — repeat (this used to wrongly return (y, z))
```

![Read swizzles: reorder, narrow, expand and the fixed repeat](assets/vector_algebra/read_swizzles.png)

Components can be spelled two ways — positional `x`/`y`/`z`/`w` or colour `r`/`g`/`b`/`a` — so `v.rgb` and `v.xyz` are the same swizzle. The two vocabularies are never mixed inside one name.

That last row is the fix worth calling out: `Vector3fc.yy` and `Vector4fc.yy` used to return `(y, z)`. Because the whole surface is now generated from one model, `.yy` means exactly `(y, y)`, and so does every other name.

## Write swizzles: GLSL-style assignment

When a swizzle's indices are all *distinct*, it also gets a writable `var` on the mutable vector type, so you can assign through it. This mutates the target **in place**:

```diffstatic {data-lang=kotlin}
:::static
val target = vec(0, 0, 0, 0)
val source = vec(1, 2, 3, 4)
target.xy = source.zw     // target becomes vec4(3, 4, 0, 0)
```

![Write swizzle: target.xy = source.zw copies two components in place](assets/vector_algebra/write_swizzle.png)

The right-hand `source.zw` is an ordinary read swizzle producing a `vec2`; the left-hand `target.xy` receives it. Repeated-index swizzles like `v.xx` have no unambiguous target, so they stay read-only — there is no `var` for them.

## Arithmetic: `+ - * /`

The vectors carry the usual operators. Between two vectors of the same arity they act component-wise; against a scalar they broadcast. Each returns a fresh vector, leaving its operands untouched.

```diffstatic {data-lang=kotlin}
:::static
val a = vec(2, 1)
val b = vec(1, 3)
a + b   // vec2(3, 4)   — component-wise
a - b   // vec2(1, -2)
a * 2   // vec2(4, 2)   — scalar broadcast (2 * a works too)
a * b   // vec2(2, 3)   — component-wise (Hadamard) product
```

![Component-wise and scalar arithmetic on vec2 values](assets/vector_algebra/algebra.png)

Note that `a * b` is the *component-wise* product, not a dot or cross product — those keep their named methods (`a.dot(b)`, and `a x b` for the 3D cross). Division by a scalar (`a / 2`) and unary minus (`-a`) round out the set.

## Copy operators: `invoke` and `len`

`invoke` gives a vector two live-coding conveniences. Called with named arguments, it returns a **copy with those components replaced**, keeping the rest — a tidy way to nudge one axis:

```diffstatic {data-lang=kotlin}
:::static
val v = vec(3, 4)     // length 5
v(y = 1f)             // vec2(3, 1) — copy, only y changed
```

Called with a block, it returns a **copy after applying the block**, leaving the original alone — handy for mutating vector methods:

```diffstatic {data-lang=kotlin}
:::static
v { normalize() }     // vec2(0.6, 0.8) — a normalized copy; v is untouched
```

Finally, the infix `len` returns a copy **rescaled to a given length**, keeping direction. It is a *setter*, not a getter — for the current magnitude use `v.length()`:

```diffstatic {data-lang=kotlin}
:::static
v len 2               // vec2(1.2, 1.6) — same direction, length 2
```

![invoke copy-with-changes, copy-then-block, and len rescale — v stays vec2(3, 4)](assets/vector_algebra/copy_operators.png)

The bottom row confirms the copy semantics: after all three expressions `v` is still `vec2(3, 4)`. Nothing above mutated it.

## In-place assignment: `%=`

When you *do* want to overwrite a vector in place — reusing the same object rather than allocating — `%=` copies another vector's components into it:

```diffstatic {data-lang=kotlin}
:::static
val a = vec(9, 9)
val b = vec(1, 2)
a %= b     // a is now vec2(1, 2); same object, new contents
```

![a %= b overwrites a's components in place](assets/vector_algebra/remassign.png)

This pairs naturally with the copy operators: reach for `v { … }` and `v(…)` when you want a new vector, and for `%=` (or the write swizzles) when you want to mutate an existing one — for example a persistent vector held across animation frames.

## Matrices: transforms & precision

Field's matrices come in two precisions that share one shape. The **single-precision** family — `Matrix2f`, `Matrix3f`, `Matrix4f` — is what the renderer, camera, and quaternion code speak. The **double-precision** family — `Matrix2d`, `Matrix3d`, `Matrix4d` — is its f64 sibling, for the times you need exact arithmetic that never quietly rounds to `float`. Both families are *generated* from the same typed algebra model in `tools/linalg-generator` that emits the vector swizzles above, so a method that exists on both behaves identically bit-for-bit apart from the storage width.

Every image below is captured straight from the same value visualizer — `ValueVisualizer.visualize(...)` — fed the actual result of the code above it. A matrix draws as a **diverging heatmap**: neutral grey at zero, blue for positive entries, orange for negative, scaled by magnitude. The terse header keeps the full-precision numbers (and, for 3×3 and 4×4, the determinant and translation). Nothing here is mocked.

All matrices are **column-major** (the JOML convention): storage index runs down each column before moving right, and the `set(...)` constructors take their arguments in that order.

### Constructing a matrix

Each type has a component constructor and defaults to the identity. The 2×2 constructor takes `(m00, m01, m10, m11)` — column by column, so `m00, m01` is the first column and `m10, m11` is the second. Read as a conventional maths matrix that is the two rows `(m00, m10)` and `(m01, m11)`.

```diffstatic {data-lang=kotlin}
:::static
import afield.stdlib.*         // vec
import afield.stdlib.linalg.*  // Matrix2d / 3d / 4d, Matrix4f, operators

val m = Matrix2d(1.0, 2.0, 3.0, 4.0)  // columns (1,2) and (3,4) → rows (1,3),(2,4)
```

![A 2×2 double matrix drawn as a labelled heatmap; header reads mat2d [1, 3 / 2, 4]](assets/matrix_algebra/construct_mat2.png)

The header spells the two rows — `[1, 3 / 2, 4]` — while the grid shows the column-major storage. Every constructor accepts `Double`s for the `…d` types and `Float`s for the `…f` types; a bare `Matrix4d()` is the 4×4 identity.

### Multiply, determinant, inverse, transpose

These four operations are *precision-agnostic* — they read the same on `Matrix2d` as on `Matrix2f`. `mul` composes another matrix onto the receiver **in place**, so wrap the left operand in a copy constructor when you want to keep it:

```diffstatic {data-lang=kotlin}
:::static
val a = Matrix2d(1.0, 2.0, 3.0, 4.0)   // rows (1,3),(2,4)
val b = Matrix2d(5.0, 6.0, 7.0, 8.0)   // rows (5,7),(6,8)
Matrix2d(a).mul(b)                     // A·B — a fresh copy, a and b untouched
```

![The product A·B as a heatmap; header reads mat2d [23, 31 / 34, 46]](assets/matrix_algebra/multiply_mat2.png)

The product `[[23, 31], [34, 46]]` is exactly the textbook result. `determinant()` and `invert()` come along for free; a matrix times its own inverse returns to the identity, and the round-trip stays exact at f64 scale:

```diffstatic {data-lang=kotlin}
:::static
a.determinant()                        // -2.0
Matrix2d(a).mul(Matrix2d(a).invert())  // ≈ identity
```

![A·A⁻¹ collapses to the identity; header reads mat2d [1, 0 / 0, 1]](assets/matrix_algebra/inverse_identity_mat2.png)

`invert()` and `transpose()` also mutate in place (with a `transpose(dest)` overload that writes into a destination instead), matching the `mul` convention. Because both families are generated from one model, the same names — `mul`, `determinant`, `invert`, `transpose`, `identity`, `set` — exist on the 2×2, 3×3, and 4×4 types alike.

### A 3×3 linear map

`Matrix3d` carries the same linear surface at one size up. Its `set(...)` takes nine values, row by row for readability, and the visualizer labels every cell and prints the determinant in the header:

```diffstatic {data-lang=kotlin}
:::static
val m3 = Matrix3d().set(
    2.0, 0.0, 1.0,
    1.0, 3.0, 0.0,
    0.0, 1.0, 4.0,
)
m3.determinant()   // 25.0
```

![A 3×3 double matrix as a labelled heatmap; header reads mat3d [det=25]](assets/matrix_algebra/linear_mat3.png)

A non-zero determinant means the map is invertible; `Matrix3d(m3).invert()` gives the inverse, and `m3.determinant()` reports `25.0` exactly.

### Composing affine transforms in 4D

`Matrix4d` adds the affine builders `scale`, `translate`, `scaling`, and `translation`. Like `mul`, they compose onto the receiver in place and return it, so you can chain them. Composition applies each step *in the frame established so far* — the JOML "local" convention — which is worth watching:

```diffstatic {data-lang=kotlin}
:::static
val t = Matrix4d().scale(2.0, 3.0, 4.0).translate(5.0, 6.0, 7.0)
t.determinant()    // 24.0  (= 2·3·4; translation never changes the determinant)
```

![A 4×4 affine matrix as a heatmap; header reads mat4d [det=24, t=(10, 18, 28)]](assets/matrix_algebra/affine_mat4.png)

The header reports `t=(10, 18, 28)`, **not** `(5, 6, 7)`. Because the `translate` runs *after* the `scale`, it moves in the already-scaled frame, so each translation component is multiplied by its axis scale: `(5·2, 6·3, 7·4)`. The determinant stays `24` — a pure translation shears nothing and preserves volume. The diagonal `2, 3, 4, 1` is the scale, and the fourth column is the translation.

### Where precision stops: the f32 bridge

The f64 family deliberately covers only the parts of the API that are precision-agnostic — multiply, determinant, invert, transpose, scale, translate. **Rotation, projection/camera helpers (`perspective`, `lookAt`), and transforming a vector live on the single-precision `Matrix4f`**, matching Field's f32 quaternion and render pipeline. So a rotation is built on `Matrix4f`:

```diffstatic {data-lang=kotlin}
:::static
val r = Matrix4f().rotateY(0.6f)   // rotate about Y by 0.6 rad
r.determinant()                    // 1.0 — a rotation preserves volume
```

![A Y-rotation as a heatmap; header reads mat4 [det=1, t=(0, 0, 0)], cells show 0.83 / 0.56 / -0.56](assets/matrix_algebra/rotate_mat4f.png)

The header now reads `mat4` (not `mat4d`) — the visualizer distinguishes the two precisions. The `cos 0.6 ≈ 0.83` on the diagonal and the `±sin 0.6 ≈ ±0.56` off-diagonal are the classic Y-rotation entries, orange marking the single negative.

Transforming a point is likewise a `Matrix4f` operation. The `*` operator applied to a `vec3` calls `transformPosition` — it includes the translation — and returns a fresh `Vector3f`:

```diffstatic {data-lang=kotlin}
:::static
Matrix4f().translation(5f, 6f, 7f) * vec(1, 2, 3)   // vec3(6, 8, 10)
```

![The transformed point as an isometric arrow; header reads vec3(6, 8, 10)](assets/matrix_algebra/transform_point.png)

The result `(6, 8, 10)` is `(1, 2, 3)` shifted by the translation, drawn by the vector visualizer as an arrow. Use `transformDirection` (or the `Vector4f` overload with `w = 0`) when you want to rotate/scale a direction *without* the translation.

## The shape of it

Read top to bottom, the vocabulary is small and regular. **Vectors:** name components in any order to read them, assign through distinct-index names to write them, use `+ - * /` for arithmetic, `invoke` and `len` to copy, and `%=` to overwrite — the same rules hold for `vec2`, `vec3`, and `vec4` alike (swizzles and the `invoke`/`len`/`%=` operators are emitted for the *float* heap vectors; the arithmetic operators additionally cover the `double` and half-precision vectors). **Matrices:** pick the width by need — reach for the `…f` family for anything that ends up on screen (cameras, rotations, per-frame transforms) and the `…d` family when accumulated arithmetic must stay exact; the shared surface (`mul`, `determinant`, `invert`, `transpose`, `identity`, `set`, and on 4×4 `scale`/`translate`) reads identically across both, while rotation, projection, and pushing a vector through a matrix are the f32 `Matrix4f`'s job. Both sides are emitted from the one generator model in `tools/linalg-generator`. For the complete member listing see the [Generated Vector API](linear_algebra_api.md); for the full guide, [Linear Algebra](linear_algebra.md).

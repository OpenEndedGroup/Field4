# Vector algebra: swizzles & operators

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

## The shape of it

Read top to bottom, the vocabulary is small and regular: name components in any order to read them, assign through distinct-index names to write them, use `+ - * /` for arithmetic, `invoke` and `len` to copy, and `%=` to overwrite. Because it is generated from one model, the same rules hold for `vec2`, `vec3`, and `vec4` alike. The swizzles and the `invoke`/`len`/`%=` operators are emitted for the *float* heap vectors; the arithmetic operators (`+ - * /`, unary minus) additionally cover the `double` and half-precision vectors. For the complete member listing see the [Generated Vector API](linear_algebra_api.md).

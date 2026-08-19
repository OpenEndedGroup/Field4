# How Box Code Runs

When you run a box, Field does **not** hand your text straight to the Kotlin compiler. It:

1. splits the code into **fragments** (on `//%-` lines),
2. **macro-expands** each fragment (rewriting some calls and injecting generated glue),
3. prepends a **preamble** gathered from the box *and its parents* in the graph,
4. compiles and evaluates the fragment,
5. and folds some of what just ran (`@exported` declarations) back into the preamble.

Most of the day-to-day magic — `prop`, `once`, inherited `renderer`/`window`, the typed accessors you get from `moduleHere { }`, `@exported` helpers — is built on this pipeline. This page explains the machinery. For the gentle user-level tour of `connect`/`prop`/`once` see the [Feature Overview](funFeatures.md); for the graphics-box API see [afield.four Graphics](afield_four_graphics.md). This page is the layer underneath both.

This page stops where the last fragment finishes evaluating. What happens to the *value* it produced — and in particular what happens when that value is a `Frames`, as it is for every `animate { }` box — is [Frames, and what a frame is](animation_time_model.md).

There are two distinct "code that writes code" mechanisms, and it helps to keep them straight from the start:

- **Macros** run *before* compilation. They are a source→source rewrite of your text (plus a side-effect: they can inject glue into the preamble, and even set box properties).
- **`@exported`** runs *after* evaluation. It reflects over what you defined and promotes it into the preamble.

Both ultimately write into the same place — the box's **preamble** — which is the shared, auto-regenerated layer of glue between a box, its own future runs, and its children.

---

## Macros: calls rewritten before they compile

A handful of things that look like ordinary function calls are actually **source macros**, expanded at compile time. The most important are `prop` / `rootProp`, `moduleHere` / `computeModuleHere` / `meshModuleHere` / `shadertoyHere`, `fieldStatsHere`, `print` / `println`, and `sourceBlock`.

They are intercepted before the compiler sees them. If a macro ever reaches the compiler un-expanded it throws — the bare declarations exist only to fail loudly:

```diffstatic {data-lang=kotlin}
fun <T> Box.prop(name: String) {
    throw MacroRegistry.MacroNotExpandedException(
        "prop should not be callable directly, but should be expanded as a macro")
}
```

Expansion produces an **`Insert`** with three parts that matter:

- **`here`** — text that replaces the call site.
- **`preamble` / `preambleAfterImports`** — glue (imports + generated declarations) pushed into the box's preamble.
- **`target`** — optionally, *which* box's preamble to write to (`rootProp` targets the document root).

The expander walks your code's syntax tree, asks the macro registry to rewrite each recognised call, and applies the edits. Two consequences are worth internalising:

- **Macro expansion is not pure.** Besides rewriting text, it mutates box state. `moduleHere { }`, for instance, seeds the box's `vertex`/`fragment` shader properties and *compiles those shaders* during expansion (it needs the shader interface to generate typed accessors — see **Generated GLSL accessors** below). This is why the ordering of `//%-` fragments matters (see **The `//%-` fragment break** below).
- **A macro's output lands in the preamble**, so it becomes available not just to the rest of your code but — if tagged for inheritance — to child boxes too.

---

## `prop` and `once`: typed, inheritable box state

`prop<T>(name)` is the workhorse. Writing it in a parent box:

```diffstatic {data-lang=kotlin}
prop<Int>(savemarker) = 12321
```

expands to *nothing at the call site but the bare name* (`savemarker = 12321`), plus two generated lines injected into the box's preamble:

```diffstatic {data-lang=kotlin}
var Box.savemarker by Inheritance<Int>() /**/
val Once.savemarker by Onceing<Int>() /**/
```

(and an `import` for the type). Now `savemarker` is a real, typed Kotlin property. From then on:

- **Writing** `savemarker = …` stores the value **on this box**.
- **Reading** `savemarker` returns this box's value if set, otherwise walks *up the box graph* to the first ancestor that has one (that is what `Inheritance` does — see **Property inheritance** below).

The companion `once.savemarker { … }` computes and stores the value when the property is `null`,
then returns the stored value on later calls:

```diffstatic {data-lang=kotlin}
class Onceing<T> {
    operator fun getValue(f: Once, property: KProperty<*>): (() -> T) -> T = { compute ->
        var a = f.inside.getPropertyRaw(property.name) as T?
        if (a == null) { a = compute(); if (a != null) f.inside.setPropertyRaw(property.name, a, true) }
        a as T
    }
}
```

`prop` and `once` are two halves of one feature: `prop` declares the typed, inheritable slot; `once`
fills a non-null value lazily. Because `null` is the empty sentinel, a computation that returns
`null` is not cached and may run again. This is the idiom behind a renderer box publishing its
window and renderer to its children:

```diffstatic {data-lang=kotlin}
prop<Window>(window)
prop<Renderer>(renderer)

once.renderer {                       // cached after the first non-null result
    window = Window("My sketch")
    Renderer(window)
}
```

`window` and `renderer` are now typed properties on this box, filled once, and — because `prop` generated inheriting accessors — visible to every box connected beneath it.

---

## Property inheritance: how children see a parent's values

Box properties are, underneath, an untyped `name → value` bag per box. Two delegates turn that bag into typed Kotlin properties, differing in **where they read from**:

| Delegate | Read | Write |
|---|---|---|
| `Delegation<T>` | this box only (with optional lazy default) | this box |
| `Inheritance<T>` | this box, else breadth-first **up the parent graph** | this box (shadows the parent) |
| `InheritanceFromContext<T>` | like `Inheritance`, but also checks live REPL variables; used by `@exported` | this box |

By convention the underscore-prefixed shader properties (`_vertex`, `_fragment`, …) are box-local `Delegation`; the user-facing ones (`vertex`, `fragment`, …) are `Inheritance`, so a child that doesn't set its own `vertex` transparently inherits the parent's.

Two things have to be true for a child to use a parent's value:

1. **The typed accessor must exist in the child's compilation scope.** Accessors travel through the preamble. When a box is about to run, its *effective* preamble is regathered from its own preamble plus, from each ancestor, the `import` lines and any line ending in the ` /**/` marker:

    ```diffstatic {data-lang=kotlin}
    // in the child's gathered preamble, pulled down from the parent:
    var Box.savemarker by Inheritance<Int>() /**/
    ```

    That ` /**/` suffix is exactly why `prop` (and `@exported`) tag their generated lines with it — it is the "inherit me into descendants" marker.

2. **The value is fetched at read time** by the `Inheritance` delegate's walk up `parents`. The preamble only carries the *accessor*; the *value* stays on whichever box set it.

### Topology is load-bearing

Both the preamble gather and the value walk follow `parents` — so **which box is whose parent decides what is visible.** Connecting a child under a parent (`parent.connect(child)`, or selecting the parent and pressing `g` in the UI) is what puts the parent into the child's ancestor chain.

This is the classic graphics gotcha: a freshly created box whose only parent is the document root is a **sibling** of the renderer box, not a descendant. It will *not* inherit `renderer` — the accessor never gathers into it, and the value walk never reaches it. Only after you connect it **beneath the renderer box** does the next run gather the accessor and resolve the live `Renderer`. (See the RectSL galleries, where "each example is a child of the renderer, inheriting `renderer`.")

The preamble is regenerated **on run**: Field recomputes the gathered preamble, diffs it against the cached copy, and executes only the newly-added segments — so adding a `prop` in a parent and re-running a child is enough to make the new accessor appear.

---

## `@exported`: promote what you defined

`prop` is the explicit way to make an inheritable slot. `@exported` is the automatic cousin: annotate a normal Kotlin `fun`/`val`/`var`, and *after the box runs*, Field reflects over it and does the same job for you.

```diffstatic {data-lang=kotlin}
@exported fun add(x: Int, y: Int): Int = x + y
@exported val answer: Int = 42
```

After this box runs, its preamble gains inheriting accessors (note the leading-underscore **alias**):

```diffstatic {data-lang=kotlin}
val Box._add by InheritanceFromContext<(Int, Int) -> Int>() /**/
val Box._answer by InheritanceFromContext<Int>() /**/
```

and the values are bound on the box. **Note the leading underscore**: `@exported` publishes under an *aliased* name — `add` becomes `_add`, `answer` becomes `_answer` (a name already starting with `_` is left as-is). So from a later run or a child box you call `_add(2, 3)` and read `_answer`, **not** `add`/`answer`. (This is the opposite of `prop<T>(name)`, which keeps the exact name — `prop<Int>(savemarker)` is read back as `savemarker`.) The alias exists so the export never collides with the original live declaration in the defining box. The upsert is idempotent (re-running doesn't duplicate lines; a type change replaces the old declaration), and exotic types are simplified so the generated accessor always compiles in the child.

Three variants set **where** the accessor goes:

- `@exported` → this box (and therefore its descendants).
- `@rootExported` → this box **and** the document root (visible document-wide).
- `@metarootExported` → this box **and** the cross-document metaroot (shared across open
  documents).

That makes `@rootExported`/`@metarootExported` the tidy way to define a helper once and share it across boxes.

`prop` and `@exported` are two front-ends to the same back-end — both write a ` /**/`-tagged `Inheritance…` accessor into the preamble, and both rely on the graph-walking delegate to resolve the value at read time. `prop` is a macro for a *value slot* you assign; `@exported` promotes *declarations you already wrote*.

---

## Generated GLSL accessors: `moduleHere { }`

A shader box's GLSL is not just code — it is also the **contract that shapes the box's Kotlin API**. `moduleHere { }` is a macro: at expansion it introspects the box's vertex/fragment (or compute) shaders and generates typed Kotlin accessors into the preamble, then rewrites itself to the real runtime builder. (The [afield.four Graphics](afield_four_graphics.md) page covers using these from a user's point of view; here we show what is actually generated.)

For a vertex shader with `layout(location = 0) in vec3 pos;` and a push-constant block, expansion generates lines like:

```diffstatic {data-lang=kotlin}
// one per vertex input — resolves mesh.input("pos")
val Mesh.pos by FromInput()

// a builder so you can fill the mesh: m.set(nVerts, nTris) { pos.add(...); tri.add(...) }
// (generated MappedStructure with a FloatBuffer per input + an IntBuffer `tri`)

// per push-constant / UBO member — typed, two ways to reach it:
var Mesh.worldToClip by Mesh.BoundFromStructure<afield.stdlib.linalg.Matrix4f>()
// ...or the untyped map form, which needs no generated accessor:
//   m.push["worldToClip"] = ...
```

A GLSL `struct` (or a `buffer_reference` buffer) becomes a generated Kotlin class mirroring its layout, with nested structs emitted recursively:

```diffstatic {data-lang=kotlin}
class Params(/* layout members */) : Structure(/* ... */) {
    var resolution_time_samples by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
    val P: Params get() = childStructure("P") { m, b, p, r -> Params(m, b, p, r) }   // nested
}
fun ParamsByteSize(): Long = 464L
fun HasBuffer.asParams(): Params = asStructure(Params(), ParamsLayoutMembers())
```

`{{ stack.auto.name }}` slots in GLSL generate assignable StackSL properties on the module pointer (`var ModulePointer.name: String`) — see [`stack.auto` in the StackSL examples](stacksl_examples.md).

**Where the module's shaders actually come from — the important nuance.** `moduleHere { }` builds its module from the box's **shader-source properties** (`vertex`, `fragment`, prepared into `_vertex`/`_fragment`), **not** from assignments inside the lambda. If the box has no shaders yet, it seeds the defaults (a plain `in vec3 pos` vertex shader and a solid-colour fragment). To hand `moduleHere` a *non-default* shader from code, you must set the box's shader-source property **before** `moduleHere` expands — either by editing the box's shader tab, or from code with `once.vertex { … }` / `once.fragment { … }`:

```diffstatic {data-lang=kotlin}
once.vertex { RECT_ANIMATION_VERTEX_SHADER }
once.fragment { myMaterial.exampleFragmentShader }
```

But there is a catch — `once.vertex` runs at *runtime*, and `moduleHere` reads the property at *expansion time*. Getting the order right is exactly what the next section is about.

---

## The `//%-` fragment break, and the problem it solves

A box's code can be split into **fragments** with a line whose trimmed text starts with `//%-` (or `/*%-*/`):

```diffstatic {data-lang=kotlin}
val a = expensiveSetup()
//%-
useThe(a)
```

Fragments are **partially-editable chunks**, and — crucially — they run in order with **expand → compile → evaluate interleaved per fragment**: fragment N+1 is macro-expanded only *after* fragment N has finished running. Between fragments, the preamble/setup blocks are re-run too.

Why does that matter? Because macro expansion is not pure (see **Macros** above). Sometimes a later fragment's macro needs a value an earlier fragment set at runtime. The canonical case is seeding a shader for `moduleHere`:

```diffstatic {data-lang=kotlin}
once.vertex   { RECT_ANIMATION_VERTEX_SHADER }
once.fragment { myMaterial.exampleFragmentShader }

//%-

val module = moduleHere {           // reads the box's vertex/fragment PROPERTIES
    blend = true
    depthTest = false
    primitiveType = triangles
}
```

Walk it through:

- **Without** the `//%-`, both are one compilation unit. All of its macros expand first, then it runs. So `moduleHere` expands — reading the box's shaders, finding none, seeding the **default `in vec3 pos`** shader and generating accessors for *that* — *before* `once.vertex` has ever run. The result: the module uses the wrong shader (and, for RectSL, the mesh's vertex attributes won't match the shader's inputs).
- **With** the `//%-`, fragment 1 runs first: `once.vertex`/`once.fragment` set the box's real shader source. Then fragment 2 is expanded, and `moduleHere` now sees the shader you supplied and generates the correct accessors and module.

So `//%-` is not cosmetic here — it is what makes "set some state, then run a macro that reads it" work. Interactive execution expands and evaluates fragments in sequence. A single Kotlin box launched through the transcript/multi-box path is also awaited fragment by fragment; mixed-language launch groups do not promise the same first-frame ordering across boxes.

---

## Putting it together

- During interactive execution, Kotlin box code is **fragmented** on `//%-`, and each fragment is **macro-expanded, compiled, and evaluated in turn** — so a fragment sees the runtime effects of earlier fragments in that box.
- **Macros** (`prop`, `moduleHere`, …) rewrite calls *before* compilation and inject glue into the **preamble**; some also mutate box state or compile shaders during expansion.
- **`@exported`** promotes declarations into the preamble *after* a run.
- The **preamble** is the shared glue layer: a box's effective preamble is gathered from itself plus its ancestors' `import` and ` /**/`-tagged lines — which is how typed accessors from `prop`/`@exported`/`moduleHere` reach child boxes.
- **Property inheritance** resolves values by walking *up the box graph*, so **connecting boxes** (topology) decides what a box can see — the reason a graphics box must be a child of its renderer.

See also: [Feature Overview](funFeatures.md) (user-level `prop`/`connect`/`once`), [afield.four Graphics](afield_four_graphics.md) (graphics API), [StackSL Examples](stacksl_examples.md) (`stack.auto`), [RectSL Examples](rectsl_examples.md), and the [Kotlin Overview](kotlin_overview.md).

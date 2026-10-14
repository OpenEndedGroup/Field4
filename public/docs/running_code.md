# Running code in a box

This page is about what Field does behind the scenes when you run a box. You do not need it to
start writing boxes. It is for when something that looks like an ordinary call turns out not to be
one, or when a child box cannot see a value its parent set.

- **Running versus launching.** `alt/option-enter` runs a selection, a line, or its outer enclosing block;
  `alt/option-0` runs everything in the box; `alt/option-click` and `alt/option-up` *launch* it, and `alt/option-1` relaunches
  just the code `alt/option-enter` would run, without rerunning the rest of the box. Running evaluates and
  stops. Launching gives the code a
  chance to put something into the animation loop and keep going. The keys themselves, the `?`
  cheat sheet and the `ctrl/command-shift-P` palette are in the [Survival Guide](survivalGuide.md).
- **What happens between the keystroke and the compiler** — fragments, macro expansion, the
  preamble, inheritance from parent boxes, `@exported` — is the rest of this page.
- **What happens after the last line evaluates**, when what you returned is a `Frames`, is
  [Frames and the animation loop](animation_time_model.md). Launching a box is exactly the case where
  that page takes over from this one.
- **When a value comes from a box you did not expect**, [Why does this value resolve
  here?](why.md) asks Field which box supplied it and by what path. It is the inheritance walk
  described below, reported for one name.

For a friendly tour of `connect` / `prop` / `once` in use, rather than in mechanism, read the
[Feature Overview](funFeatures.md); for the graphics-box API on top of all this, see
[afield.four Graphics](afield_four_graphics.md). This page is the layer underneath both.

---

## Running code around the cursor

In the code editor, **alt/option-Enter** runs the selected text if you have a selection. With no
selection, it runs the line under the cursor. If that line is inside a `{ … }` block, it runs the
**outer enclosing block**, from its opening line through its closing line. This includes the whole
outer block when the cursor is inside a nested block:

```diffstatic {data-lang=kotlin}
repeat(2) {
    if (true) {
        println("hello") // Alt+Enter here runs the whole repeat(2) block
    }
}
```

Select just the inner lines when you want to run less. **alt/option-0** runs the whole box, regardless of
the cursor position.

**alt/option-1** is for relaunching part of a box you have already run. It picks out the same code
as alt/option-Enter (the selection, or the line, or its outer enclosing block) and *launches* just
that, so the setup at the top of the box is not run again. The fragment sees the variables earlier
runs left behind: below, the box has been run once, and Alt+1 inside the `loop` relaunches the loop
with the `count` the top part already made. Launched from a box that has never been run, the same
fragment fails with `Unresolved reference 'count'`. The striped green bar in the margin marks the
lines that were launched while they keep running:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1000x500" clip-selector=".monaco-editor-container" screenshot="assets/running_code/launch-fragment.png" transcript="../../dev_docs/agents/transcripts/docs_capture/launch_fragment.yaml" padding="0" wait="0" timeout="120" embed=true hidden=true name="running-code-launch-fragment" alt="Field's editor with the cursor inside a loop block after Alt+1. A striped green bar in the left margin spans the five lines of the loop, lines 4 to 8, and not the two lines above it. Beside print(count) the running count reads 475."}
ui.openEditor("launch_fragment")
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    Object.assign(c.style, {width:"560px", height:"260px", top:"20px", right:"20px", left:"auto", bottom:"auto", transform:"none"})
    window.globalEditor.layout()
    window.globalEditor.setPosition({lineNumber: 6, column: 5})
    window.globalEditor.focus()
    return true
""".trimIndent())
ui.press("Alt+1")
ui.pause(1500)
ui.eval("""
    const bars = document.querySelectorAll(".monaco-editor .margin [class*='execution-marker-']")
    if (bars.length == 0) throw new Error("no execution marker in the margin")
    return true
""".trimIndent())
```

To tune a value while watching its effect, put the cursor on a numeric literal and use **Increment
number under cursor and rerun** or **Decrement number under cursor and rerun**. The shortcuts are
**ctrl/command-alt/option-K/J** (K increments, J decrements). Each
command changes that number and then performs the same run as alt/option-Enter, so a number inside a block
reruns the outer enclosing block. Integers change by one; decimal numbers use a step scaled to the
value and keep their decimal precision. You can also find both commands by name in the **ctrl/command-shift-P**
palette.

---

## Calling suspend functions with `go { }`

Some Field APIs, including calls into embedded editors, are `suspend` functions: they wait for a
reply while the rest of Field keeps running. From ordinary box code, put these calls in `go { }`:

```diffstatic {data-lang=kotlin}
go {
    delay(100)
    println("done")
}
```

`go { }` starts a coroutine on Field's run loop and returns immediately. Code after the call
continues without waiting for the block to finish. Inside the block, suspend calls run in order,
and an uncaught error appears on the box as an execution error. The block keeps this box as its
context, so Field's usual box-scoped values and output work there. Stopping the box cancels a
running `go` block at its next suspension. Use `go { }` when a box needs to start a suspend
operation; an `aiTool { action { ... } }` can call suspend functions directly.

While a `go` block is still running, the box shows it: a striped square in its top-right corner
holds the number of `go` blocks that have not finished, and the box is not drawn as launched. If
you started the block with Alt+1, its margin marker stays on until the block ends too. Stopping the
box (Alt+Down) cancels them and clears both. Inside `go { }`, `delay(ms)` waits without holding up
the rest of Field:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1000x420" clip="40,40,940,330" screenshot="assets/running_code/go-jobs-badge.png" transcript="../../dev_docs/agents/transcripts/docs_capture/go_jobs_badge.yaml" padding="0" wait="0" timeout="120" embed=true hidden=true name="running-code-go-jobs-badge" alt="A box named counting with a small striped square holding the number 1 in its top-right corner. Beside it, Field's editor shows the box's go block with the cursor inside it after Alt+1: a striped bar in the margin spans the go block, and the count printed beside print(ticks) reads 3."}
ui.openEditor("go_jobs_badge")
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    Object.assign(c.style, {width:"560px", height:"280px", top:"45px", right:"60px", left:"auto", bottom:"auto", transform:"none"})
    window.globalEditor.layout()
    window.globalEditor.setPosition({lineNumber: 4, column: 9})
    window.globalEditor.focus()
    return true
""".trimIndent())
ui.press("Alt+1")
ui.pause(1800)
ui.eval("""
    const box = [...document.querySelectorAll(".box")].find(b => b.uid === "go_jobs_badge")
    const badge = box && box.querySelector(":scope > .go-jobs-badge")
    if (!badge || badge.textContent.trim() !== "1") throw new Error("go badge missing or wrong: " + (badge && badge.textContent))
    if (box.classList.contains("running")) throw new Error("the box is drawn as launched")
    if (!document.querySelector(".monaco-editor .margin [class*='execution-marker-']")) throw new Error("no margin marker")
    return true
""".trimIndent())
```

---

## The pipeline, in five steps

When you run a box, Field does **not** hand your text straight to the Kotlin compiler. It:

1. splits the code into **fragments** (on `//%-` lines),
2. **macro-expands** each fragment (rewriting some calls and injecting generated glue),
3. prepends a **preamble** gathered from the box *and its parents* in the graph,
4. compiles and evaluates the fragment,
5. and folds some of what just ran (`@exported` declarations) back into the preamble.

These five steps are what make `prop`, `once`, inherited `renderer`/`window`, the typed accessors you get from `moduleHere { }`, and `@exported` helpers work.

Two distinct "code that writes code" mechanisms are involved, and they run at different times:

- **Macros** run *before* compilation. They are a source→source rewrite of your text (plus a side-effect: they can inject glue into the preamble, and even set box properties).
- **`@exported`** runs *after* evaluation. It reflects over what you defined and promotes it into the preamble.

Both write into the same place, the box's **preamble**: the shared, auto-regenerated layer of glue between a box, its own future runs, and its children.

---

## Macros: calls rewritten before they compile

A handful of things that look like ordinary function calls are actually **source macros**, expanded at compile time. The most important are `prop` / `rootProp`, `moduleHere` / `computeModuleHere` / `meshModuleHere` / `shadertoyHere`, `fieldStatsHere`, `print` / `println`, and the two that keep their own source, `sourceBlock` and `interpolant`.

These are Field's built-in macros. To write your own source rewrite rules in a box, see
[Rewriting your own syntax](pattern_rewrite.md) for the `rewrite { … }` DSL.

They are intercepted before the compiler sees them. Most have a bare declaration that exists only to fail loudly if a call ever reaches the compiler un-expanded:

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

The expander walks your code's syntax tree, asks the macro registry to rewrite each recognised call, and applies the edits. Two consequences follow from that:

- **Macro expansion is not pure.** Besides rewriting text, it mutates box state. `moduleHere { }`, for instance, seeds the box's `vertex`/`fragment` shader properties and *compiles those shaders* during expansion (it needs the shader interface to generate typed accessors — see **Generated GLSL accessors** below). This is why the ordering of `//%-` fragments matters (see **The `//%-` fragment break** below).
- **A macro's output lands in the preamble**, so it becomes available not just to the rest of your code but — if tagged for inheritance — to child boxes too.

### `sourceBlock` and `interpolant`: code that keeps its own source

Two macros wrap a lambda so that it carries the text it was written as. That text can be saved, and
compiled again later, so the code still works when the box that wrote it has not run.

`sourceBlock { … }` gives you a block you call like a function, with its body's text in `.source`:

```diffstatic {data-lang=kotlin}
val answer = sourceBlock { 6 * 7 }
println(answer())          // 42
println(answer.source)     // 6 * 7
```

The keyframer and code blends save blocks this way. A block loaded back from a saved file has no
lambda yet; the first call compiles its source in the box it came from.

`interpolant("name") { … }` defines a key type for the keyframe editor: a segment shape, written as
the curve's value across the segment.

```diffstatic {data-lang=kotlin}
interpolant("wobble") { lerp(u) + 0.08 * sin(u * 6 * PI) * (1 - u) }
```

The type appears in the key menu, and its source is saved with the box. A curve that uses it keeps
its shape when the definition has not run yet (it comes later in the box, or you deleted it),
because Field compiles the saved source instead. See [Your own key types](keyframe_editor.md#your-own-key-types)
for what the lambda can use.

!!! warning "Don't call a saved block from its own box's code"
    Compiling a saved block in its box runs that box's code again — which calls the block again.
    Call a block that came from a file from another box, a frame loop or a control callback. A block
    made by `sourceBlock` in this run already has its lambda and is safe to call at once. (Field
    stops the loop after one repeat, with an error saying so.)

---

## `prop`, `once` and `cached`: values that outlive a run

Each run of a box starts with fresh local variables: a `val` written at the top of the code is
made again every time you run it. These three keep a value across runs.

### `prop`: a typed slot that children inherit

`prop<T>(name)` is the workhorse. Writing it in a parent box:

```diffstatic {data-lang=kotlin}
prop<Int>(savemarker) = 12321
```

expands to *nothing at the call site but the bare name* (`savemarker = 12321`), plus three generated lines injected into the box's preamble:

```diffstatic {data-lang=kotlin}
var Box.savemarker by Inheritance<Int>() /**/
val Once.savemarker by Onceing<Int>() /**/
val Why.savemarker by Whying() /**/
```

(and an `import` for the type). Now `savemarker` is a real, typed Kotlin property. From then on:

- **Writing** `savemarker = …` stores the value **on this box**.
- **Reading** `savemarker` returns this box's value if set, otherwise walks *up the box graph* to the first ancestor that has one (that is what `Inheritance` does — see **Property inheritance** below).
- **`why.savemarker`** says which box that value came from; see [Why does this value resolve here?](why.md).

The name is written unquoted: it is a name, not a string.

### `once`: fill the slot the first time

`once.savemarker { … }` runs the block only when the property is `null`, stores the result, and
returns the stored value on every later run. This is the idiom behind a renderer box publishing its
window and renderer to its children:

```diffstatic {data-lang=kotlin}
prop<Window>(window)
prop<Renderer>(renderer)

once.renderer {                       // runs on the first run only
    window = Window("My sketch")
    Renderer(window)
}
```

`window` and `renderer` are now typed properties on this box, filled once, and visible to every box
connected beneath it. A child reads `renderer` by name; it never needs to look the renderer up.

`null` means "empty": a block that returns `null` is not cached and runs again next time.

### `cached`: one value per set of keys

`once` has one slot per name. When a value depends on something else — a view made for a
particular renderer, a buffer made for a particular size — use `cached`, which keeps one value per
type *and* key:

```diffstatic {data-lang=kotlin}
val view = cached<ElectronRTTView>(renderer) { ElectronRTTView(640, 360) }
```

Re-run the box and you get the same view back. Connect the box under a different renderer, or pass
different keys, and the block runs again to make a new one; the old one is closed (if it is
`AutoCloseable`), so changing a key replaces rather than leaks. Keys are compared with `equals`, and
any number can be given: `cached<FloatArray>(width, height) { FloatArray(width * height) }`.

One run may ask for several values of one type under different keys — one view per renderer in a
loop — and keeps them all. Two calls with the *same* type and keys share one value; add a key such as
`"left"` to keep them apart. `cached` needs no `prop` first, and its value is not visible to child
boxes: return it into a `prop` if children should see it.

`T` should be a library type. A class declared in the box's own code is a new class each time the
box compiles, so a `cached` of it never finds the previous value.

### `aside`: code an ordinary run skips

`once` and `cached` run their block on the first full run. Sometimes you want the opposite: code
that a full run never runs, and that you run by hand when you want it — a one-off experiment, a
reset, a debug dump. Wrap it in `aside`:

```diffstatic {data-lang=kotlin}
aside("reset") {
    savemarker = 0
    println("reset")
}
```

Running the whole box skips the block. Put the cursor inside it and press alt/option-Enter to run
just that block. (Running a selection that includes it runs it too.) The name is only a label for
you; it changes nothing. It saves keeping that code commented out and uncommenting it each time.

### `flipbox`: a block with a checkbox

A `flipbox` sits between `once` and `aside`: a block you switch on and off with a checkbox. Press ctrl/command-shift-. to insert a marker (drawn
as a single rune), then write `.flipbox(true) { … }` after it:

```diffstatic {data-lang=kotlin}
__m("Zk3q-a1").flipbox(true) {
    buildTheExpensiveThing()
}
```

The editor draws the `true` as a checkbox. The block runs when the box runs with the box ticked,
and each run unticks it. With `true` it starts ticked, so it runs on the first run like `once`, and
later runs skip it. Click the checkbox to run the block again, without running the box, like
alt/option-Enter on an `aside`. With `false` it runs only when you click. The tick is not saved
with the document, so `true` applies again after Field restarts.

### What lasts how long

What the block of a `once` or `cached` makes — a slider, a render module, an audio patch —
belongs to the box, not to the run that happened to make it, so a re-run does not take it apart.

| | re-run | stop | delete the box / close the document |
|---|---|---|---|
| a `val` in the code | made again | — | — |
| `prop` value | kept | kept | gone |
| `once` / `cached` value | kept | kept, unless its block started something that runs (an audio patch, a process); then cleared and made again on the next run | released |
| `cached` value whose keys changed | closed, a new one made | — | — |

Stop only stops what is running. If a `once` or `cached` block started an audio patch, stop ends
the patch and forgets the value, so the next run makes a fresh one instead of handing back one whose
sound has gone.

---

## Property inheritance: how children see a parent's values

Box properties are, underneath, an untyped `name → value` bag per box. Three delegates turn that bag into typed Kotlin properties, differing in **where they read from**:

<div data-code-lang="kotlin" markdown>

| Declaration example | Read behavior | Write behavior |
| --- | --- | --- |
| `var Box.level by Delegation<Float>()` | Reads this box’s own slot. A supplied lazy default initializes it locally. | Writes this box’s slot. |
| `var Box.level by Inheritance<Float>()` | Reads locally first, then searches parents breadth-first. Use it when children should inherit a parent’s setting. | Writes locally, shadowing the inherited value. |
| `var Box.level by InheritanceFromContext<Float>()` | Also consults live REPL variables while resolving the inherited value. This is the mechanism used by `@exported`. | Writes this box’s slot. |

</div>

By convention the underscore-prefixed shader properties (`_vertex`, `_fragment`, …) are box-local `Delegation`; the user-facing ones (`vertex`, `fragment`, …) are `Inheritance`, so a child that doesn't set its own `vertex` transparently inherits the parent's.

Two things have to be true for a child to use a parent's value:

1. **The typed accessor must exist in the child's compilation scope.** Accessors travel through the preamble. When a box is about to run, its *effective* preamble is regathered from its own preamble plus, from each ancestor, the `import` lines and any line ending in the ` /**/` marker:

    ```diffstatic {data-lang=kotlin}
    // in the child's gathered preamble, pulled down from the parent:
    var Box.savemarker by Inheritance<Int>() /**/
    ```

    That ` /**/` suffix is exactly why `prop` (and `@exported`) tag their generated lines with it: it is the "inherit me into descendants" marker.

    The filter applies to every ancestor, including one that has no preamble of its own. So the accessors `moduleHere { }` generates from a box's shaders (below) do *not* come down: they are untagged, and `time` or `worldToClip` is a name two unrelated shaders will both use. A child that wants them regenerates its own, which it gets for free: `vertex`/`fragment` are themselves `Inheritance` properties, so a child with no shaders of its own inherits the parent's *source* and its own `moduleHere { }` generates accessors from it.

2. **The value is fetched at read time** by the `Inheritance` delegate's walk up `parents`. The preamble only carries the *accessor*; the *value* stays on whichever box set it.

### Which box is whose parent decides what is visible

Both the preamble gather and the value walk follow `parents`. Connecting a child under a parent (`parent.connect(child)`, or selecting the parent and pressing `g` in the UI) is what puts the parent into the child's ancestor chain.

This is the commonest graphics mistake: a freshly created box whose only parent is the document root is a **sibling** of the renderer box, not a descendant. It will *not* inherit `renderer`: the accessor never gathers into it, and the value walk never reaches it. Only after you connect it **beneath the renderer box** does the next run gather the accessor and resolve the live `Renderer`. (The [RectSL examples](rectsl_examples.md) are built this way: one `window + renderer` box, with every example connected beneath it.)

When a value comes from a box you did not expect, [Why does this value resolve here?](why.md) shows how to ask Field which box supplied it, by what path, and which other boxes it shadowed.

The preamble is regenerated **on run**: Field recomputes the gathered preamble, diffs it against the cached copy, and executes only the newly-added segments, so adding a `prop` in a parent and re-running a child is enough to make the new accessor appear.

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

and the values are bound on the box. **Note the leading underscore**: `@exported` publishes under an *aliased* name. `add` becomes `_add`, `answer` becomes `_answer` (a name already starting with `_` is left as-is). So from a later run or a child box you call `_add(2, 3)` and read `_answer`, **not** `add`/`answer`. (This is the opposite of `prop<T>(name)`, which keeps the exact name; `prop<Int>(savemarker)` is read back as `savemarker`.) The alias exists so the export never collides with the original live declaration in the defining box. The upsert is idempotent (re-running doesn't duplicate lines; a type change replaces the old declaration), and exotic types are simplified so the generated accessor always compiles in the child.

Three variants set **where** the accessor goes:

- `@exported` → this box (and therefore its descendants).
- `@rootExported` → this box **and** the document root (visible document-wide).
- `@metarootExported` → this box **and** the cross-document metaroot (shared across open
  documents).

That makes `@rootExported`/`@metarootExported` the tidy way to define a helper once and share it across boxes.

`prop` and `@exported` are two front-ends to the same back-end: both write a ` /**/`-tagged `Inheritance…` accessor into the preamble, and both rely on the graph-walking delegate to resolve the value at read time. `prop` is a macro for a *value slot* you assign; `@exported` promotes *declarations you already wrote*.

---

## Generated GLSL accessors: `moduleHere { }`

A shader box's GLSL is also the **contract that shapes the box's Kotlin API**. `moduleHere { }` is a macro: at expansion it introspects the box's vertex/fragment (or compute) shaders and generates typed Kotlin accessors into the preamble, then rewrites itself to the real runtime builder. (The [afield.four Graphics](afield_four_graphics.md) page covers using these from a user's point of view; here we show what is actually generated.)

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

`{{ stack.auto.name }}` slots in GLSL generate assignable StackSL properties on the module pointer (`var ModulePointer.name: String`); see [`stack.auto` in the StackSL examples](stacksl_examples.md).

**Where the module's shaders come from.** `moduleHere { }` builds its module from the box's **shader-source properties** (`vertex`, `fragment`, prepared into `_vertex`/`_fragment`), **not** from assignments inside the lambda. If the box has no shaders yet, it seeds the defaults (a plain `in vec3 pos` vertex shader and a solid-colour fragment). To hand `moduleHere` a *non-default* shader from code, you must set the box's shader-source property **before** `moduleHere` expands, either by editing the box's shader tab or from code with `once.vertex { … }` / `once.fragment { … }`:

```diffstatic {data-lang=kotlin}
once.vertex { RECT_ANIMATION_VERTEX_SHADER }
once.fragment { myMaterial.exampleFragmentShader }
```

`once.vertex` runs at *runtime*, though, and `moduleHere` reads the property at *expansion time*. Getting that order right is what the next section is about.

---

## The `//%-` fragment break

A box's code can be split into **fragments** with a line whose trimmed text starts with `//%-` (or `/*%-*/`):

```diffstatic {data-lang=kotlin}
val a = expensiveSetup()
//%-
useThe(a)
```

Fragments are **partially-editable chunks**, and they run in order with **expand → compile → evaluate interleaved per fragment**: fragment N+1 is macro-expanded only *after* fragment N has finished running. Between fragments, the preamble/setup blocks are re-run too.

That ordering matters because macro expansion is not pure (see **Macros** above): sometimes a later fragment's macro needs a value an earlier fragment set at runtime. The usual case is seeding a shader for `moduleHere`:

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

What happens in each case:

- **Without** the `//%-`, both are one compilation unit. All of its macros expand first, then it runs. So `moduleHere` expands — reading the box's shaders, finding none, seeding the **default `in vec3 pos`** shader and generating accessors for *that* — *before* `once.vertex` has ever run. The result: the module uses the wrong shader (and, for RectSL, the mesh's vertex attributes won't match the shader's inputs).
- **With** the `//%-`, fragment 1 runs first: `once.vertex`/`once.fragment` set the box's real shader source. Then fragment 2 is expanded, and `moduleHere` now sees the shader you supplied and generates the correct accessors and module.

So `//%-` is how you set some state and then run a macro that reads it. A single Kotlin box launched through the transcript/multi-box path is also awaited fragment by fragment; mixed-language launch groups do not promise the same first-frame ordering across boxes.

The preamble and setup blocks also re-run between fragments, so a fragment that *writes* to the box's preamble at runtime has published a real source declaration by the time the next fragment is compiled. That is how `DataFrame.typed("People")` makes typed columns survive into the second half of the same execution; see [DataFrames in a Box](dataframe.md#fragment-marker).

---

## Putting it together

- During interactive execution, Kotlin box code is **fragmented** on `//%-`, and each fragment is **macro-expanded, compiled, and evaluated in turn** — so a fragment sees the runtime effects of earlier fragments in that box.
- **Macros** (`prop`, `moduleHere`, …) rewrite calls *before* compilation and inject glue into the **preamble**; some also mutate box state or compile shaders during expansion.
- **`@exported`** promotes declarations into the preamble *after* a run.
- **`once`** and **`cached`** keep a value across runs; `cached` keeps one per type and key, and closes the old one when a key changes (see **What lasts how long**).
- **`aside { }`** is the reverse: a block a full run skips, run by hand with alt/option-Enter.
- The **preamble** is the shared glue layer: a box's effective preamble is gathered from itself plus its ancestors' `import` and ` /**/`-tagged lines — which is how typed accessors from `prop`/`@exported` reach child boxes. `moduleHere`'s generated shader accessors are *not* tagged, so they stay in the box whose shader declared them.
- **Property inheritance** resolves values by walking *up the box graph*, so **connecting boxes** (topology) decides what a box can see — the reason a graphics box must be a child of its renderer.

See also: [Feature Overview](funFeatures.md) (user-level `prop`/`connect`/`once`), [afield.four Graphics](afield_four_graphics.md) (graphics API), [StackSL Examples](stacksl_examples.md) (`stack.auto`), [RectSL Examples](rectsl_examples.md), [Why Does This Value Resolve Here?](why.md) (which box a
value came from), and the [Kotlin Overview](kotlin_overview.md).

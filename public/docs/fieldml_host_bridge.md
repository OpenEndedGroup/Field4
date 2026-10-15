# FieldML's JVM bridge

FieldML's prelude covers geometry (`fline`, `vec3`, `draw`, `animate`). Everything else in Field is Kotlin, and the bridge is how a FieldML box calls it.

From a FieldML box you can call a Kotlin method with any number of arguments, positionally or by name; pass a FieldML function where Kotlin wants a lambda, including a `suspend` one; and use Field's box-code macros such as `prop`, `moduleHere` and `println`. A lambda you hand to a Kotlin method gets its parameter types from that method's signature, so `.x` on a `Vec3` parameter is checked before the box runs. The exceptions are listed under [what it will not do](#what-it-still-will-not-do).

## One box, end to end

This box is written in FieldML, and the drawing is done by Kotlin. A FieldML function of `(x, y)` is handed to `Img.generate`, which rasterises it; the result goes through Field's `colormap` extension and out through `showImg`.

```diffstatic_runner {data-lang=fieldml screenshot="assets/fieldml_host_bridge/generated_field.png" wait="2.0" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="fieldml-host-bridge-field" w="760" h="560" alt="a 240 by 240 raster in the viridis colourmap: concentric rings around a bright yellow centre, the rings shading out through green to deep indigo and back to teal, each ring pinched into lobes above and below the centre"}
let Img = hostClass "afield.media.image.Img"
let Imaging = hostClass "afield.media.image.Imaging_fieldKt"

// The whole picture is this function. x and y are Ints; the result is one
// sample. Kotlin calls it once per pixel.
let sample x y =
  let u = (toFloat x - 120.0) / 120.0 in
  let v = (toFloat y - 120.0) / 120.0 in
  let r = sqrt (u * u + v * v) in
  sin (r * 11.0) * cos (u * 5.0) * exp (0.0 - r * 1.1) * 0.5 + 0.5

let picture =
  Img
    |> hostGet "Companion"
    |> hostCall "generate" (240, 240, sample)
    |> hostExt Imaging "colormap" ("viridis", hostNull, hostNull)

hostStaticCall "showImg" (thisBox, picture, "main", 480, "Img.generate, driven by a FieldML function") Imaging
```

Change `11.0` in `sin (r * 11.0)` and the ring spacing changes; change `"viridis"` to `"magma"` or `"coolwarm"` and the palette does.

The last two steps show the two ways to call a Kotlin extension function. `colormap` is an extension on `Img`, so the piped picture is its receiver and `hostExt` fits. `showImg` is an extension on `Box`, so its receiver is `thisBox` and the picture is an ordinary argument; that call uses `hostStaticCall`, with every parameter written out in order.

## Calling a method

`hostCall name arguments target` calls a method. The target comes last, so it can sit at the end of a pipe:

```diffstatic {data-lang=fieldml}
let Img = hostClass "afield.media.image.Img"
let img = hostNew (4, 4, 1, [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 0.9, 0.8, 0.7, 0.6, 0.5]) Img

img |> hostCall "index" (1, 0, 0)
```

The arguments are a tuple, and a tuple is spread, so `(1, 0, 0)` is three arguments. A list written in place is spread the same way, `()` is no arguments at all, and anything else is one argument. That last rule is the only place to be careful: to pass a single *list* as one argument, use `hostCall1`, which never spreads.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Form | Arguments |
| --- | --- |
| `hostCall "m" () t` | Calls `m` on target `t` with no arguments; `()` is the empty bundle. |
| `hostCall0 "m" t` | Calls the same zero-argument method without an explicit bundle. |
| `hostCall1 "m" x t` | Passes exactly one argument, preserving `x` even when it is a list, tuple or record. |
| `hostCall "m" (a, b, c) t` | Spreads the tuple into three positional arguments, in order. |
| `hostCall "m" [a, b, c] t` | Spreads the list into three positional arguments. |
| `hostCall1 "m" [a, b, c] t` | Passes the list as one argument, without spreading it. |

</div>

`hostNew arguments class` and `hostNew0 class` are the same for constructors, and `hostStaticCall name arguments class` is the same for statics. `hostExt class name arguments` is a static called with the piped value in front of the written arguments. That is how a Kotlin extension function compiles, and most of Field's API is extension functions.

When a method is overloaded, each candidate is scored argument by argument, so `hostStaticCall "max" (3, 7)` reaches `Math.max(int, int)` and `(3.5, 7.25)` reaches `Math.max(double, double)`. FieldML has one integer type and one float type, so a conversion happens on nearly every call: `Int` reaches `long`/`short`/`Number`, `Float` reaches `float` and `Double`, a FieldML list reaches a JVM array or a `vararg`, and `hostNull` reaches anything nullable. That last one fills an optional Kotlin parameter, since a reflective call cannot use Kotlin's defaults.

A parameter of the same kind wins (floating point for a FieldML `Float`, integral for an `Int`), and a lossy conversion scores worst. `Vector2f` declares both `void set(int, float)` and `Vector2f set(float, float)`; a FieldML `(3.0, 4.0)` fits both, and reaches the second. The type checker and the runtime score the same way, so the overload completion shows you is the one the call reaches.

## Passing a function

A FieldML function can go wherever Kotlin expects a function: a Kotlin lambda parameter (`(Int, Int) -> Float`), a receiver lambda (`Box.() -> Unit`, whose receiver arrives as the first argument), or a Java interface such as `Runnable`, `Supplier` or `Comparator`. This one bends a line through `FLine.transform`:

```diffstatic {data-lang=fieldml}
fline
  |> moveTo 0.0 0.0
  |> lineTo 100.0 0.0
  |> lineTo 100.0 60.0
  |> hostCall1 "transform" (fun p ->
       vec3 (p.x) (p.y + 20.0 * sin (p.x / 25.0)) (p.z)
     )
```

`p` is a `Vec3` without you saying so. `FLine.transform` takes a Kotlin `(Vec3) -> Vec3`, and the type checker reads that signature, so `.x`, `.y` and `.z` are checked, `p.xx` is an error before the box runs, and completion works inside the body. The same goes for `Img.generate(w, h) { x, y -> … }` and `Img.mapIndexed { x, y, c, v -> … }`, where `x` and `y` are `Int`s and `x + y` is integer arithmetic, and for a receiver lambda (`BufferParameters.() -> Unit`), whose receiver is typed as its first parameter.

Two kinds of parameter are left for FieldML to infer:

- An erased generic. `Collections.max(collection, Comparator<? super T>)` has no `T` at the call, so `fun a b -> b - a` gets ordinary inferred parameters, which is what lets the subtraction through.
- A nullable Kotlin parameter. `FLine.byFiltering((Vec3?, Node) -> Boolean)` is handed a null for the first node's predecessor, so that parameter stays open while the second is typed `Node`.

You still need `hostAs` when the receiver is not known where the call is written. Inside `let warp line = line |> hostCall1 "transform" (fun p -> …)` there is no class to read, because `line` is just a function parameter. Written as part of a pipeline, it is typed.

A function taking no arguments (`Runnable`) is written `fun ignored -> …`.

## Types

A value that comes from Kotlin keeps its JVM class, and the type checker uses it. Here `img.width` is an `Int`:

```diffstatic {data-lang=fieldml}
let Img = hostClass "afield.media.image.Img"
let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i / 32.0) 32) Img

// Int, inferred from Img.getWidth() — not Host, and no cast anywhere.
img.width * img.height + img.channels
```

So:

- `thisBox` is a `Box`, `fline` is an `FLine`, `hostClass "afield.media.image.Img"` is a `Class<Img>`, and `hostGet "Companion"` on it is an `Img.Companion`;
- dot access on a host value reads a JVM member, so `img.width` is an `Int`;
- a member or an overload that does not exist is a type error, reported where the name was written, rather than a crash on the first run — misspell `hostGet "widht"` and the box says `Box has no member 'widht'` before it runs;
- completion offers the members of a typed receiver: inside `img |> hostGet "wi` the list is `width`, and it says `Int`.

### `Host`, the type reflection falls back to

When the class cannot be known (a generic erased to `Object`, an array, a `Map`, a nullable Kotlin return), the type is `Host`. `Host` unifies with numbers, strings, booleans and other host values, and satisfies the numeric, vector and equality constraints, so such a result is usable without a cast.

It never stands for a FieldML function or a FieldML structure, because a value coming from Kotlin is never converted into one. Calling it, or treating it as a list, is a type error that says what to do instead:

```diffstatic {data-lang=text}
(hostInput |> hostGet "whatever") 42
  A Host cannot be used as (Int -> 'a): reflection could not type this
  value, so call it with hostCall1 "invoke" instead

hostInput |> hostGet "whatever" |> head
  A Host cannot be used as ['a]: reflection could not type this value,
  so convert it with hostAsList
```

Two host types whose classes are both *known* and unrelated do not unify either: `if true then img else fline` is `Img is not FLine (afield.media.image.Img and afield.geometry.path.FLine are unrelated)`. Two where one is assignable from the other do, so a `Vector2f` reaches a parameter declared as its read-only `Vector2fc` interface.

`hostAsList` turns a host value into a FieldML list: it converts a JVM `List`, `Set`, array or `Map`, which is also how you walk `Box.parents`. The rest of the `hostAs…` family turns a value into a scalar:

```diffstatic {data-lang=fieldml}
let Collections = hostClass "java.util.Collections"

// Collections.max returns T, which erases to Object, so it stays Host.
hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections
  |> hostAsInt
```

`hostAsInt`, `hostAsFloat`, `hostAsBool` and `hostAsString` read a scalar out; `hostAs "SomeClass"` is a checked cast to a class, and doubles as the way FieldML names one.

## Reaching Field 4

Field 4 is Field's Vulkan layer. A small complete scene (a window, a compute shader over particles, a draw pass) can be written in FieldML statement for statement, including `defaultBuffer { size = N.vec4s }`, whose argument is a Kotlin receiver lambda and whose `vec4s` only exists inside it, and the per-frame builders. Imports have no FieldML counterpart because FieldML names every class in full. Several of the calls such a scene makes are source macros, covered next.

## Source macros

`prop<Double>(speed)`, `moduleHere { }`, `computeModuleHere { }` and `println(x)` are macros: they rewrite a Kotlin box's source before it is compiled. FieldML is not compiled as Kotlin, so it reaches them another way. A macro that rewrites a call turns it into an ordinary `Box` extension function (`moduleHere` becomes `_moduleHere`), and does its real work while it expands: `moduleHere` seeds this box's `vertex` and `fragment` properties and compiles both shaders. FieldML runs the expansion, then calls the function it rewrote to. This box uses `prop`, `println` and the raw expansion of `prop`:

```diffstatic {data-lang=fieldml}
// `prop<Double>(swirl)` in a Kotlin box declares an inheritable slot. FieldML has
// no declarations, and does not need one -- the slot is made by writing it, and
// reading it is the same breadth-first walk up `parents` the generated delegate does.
let _ = setProp "swirl" 0.85
let swirl = prop "swirl" |> hostAsFloat

// Field's own `println` macro, expanded and then called. The 7 is the line number
// the Kotlin pass would have supplied.
let _ = hostMacro "println" "(x)" (7, "from a FieldML box")

// And when there is nothing to call, the generated Kotlin comes back as text.
(hostMacroExpand "prop" ("<Double>", "(speed)")).declarations
```

That last line is `var Box.speed by Inheritance<Double>()` and its `Once` counterpart, the text a Kotlin box would have compiled into its preamble.

The functions for reaching macros:

<div class="field-reference" data-code-lang="fieldml" markdown>

| Name | For |
| --- | --- |
| `hostMacroExpand "name" args` | any registered macro. Runs the expansion against this box, side effects and all, and hands the rewrite back as a record: `.call`, `.here`, `.declarations`, `.preamble` |
| `hostMacro "name" args values` | the macros that rewrite a call. Expands, then calls the `Box` extension it rewrote to, with `values` as the arguments |
| `prop` `propOr` `setProp` `rootProp` `setRootProp` `once` | the macros that generate a *declaration*. No expansion involved: these read and write the same box slot the generated delegate would have |

</div>

A macro's arguments are source text, one string per piece: `hostMacroExpand "prop" ("<Double>", "(speed)")` is the call `prop<Double>(speed)`. `hostMacro` takes those and then the values for the rewritten call. The values can be a record, which makes a named call; `_fieldStatsHere` has a default for each of its parameters, so you can name the one you want to change.

A Kotlin box's `speed` and a FieldML box's `prop "speed"` are the same property, read the same way up the box graph. A FieldML box can hold a live parameter that a Kotlin box below it uses, or read one a Kotlin box exports, and the property inspector shows either.

The macros that only generate Kotlin declarations cannot be used from FieldML: `sourceBlock` (a lambda whose body is Kotlin), `jsonParser` (a generated class), `graphLens` (a property reference built from a path) and `ai`. `hostMacroExpand` still returns their generated source as text.

Two more things to know:

- You do not get a macro's generated accessors. `moduleHere`'s expansion also returns typed Kotlin declarations (`val Mesh.pos by FromInput()`, an accessor per push-constant member, the `triangles` constant). In FieldML the same members are reached through `hostGet` and `hostCall`.
- A FieldML integer is a JVM `Long`. Writing one into a slot a Kotlin box declared as `prop<Int>` would change what is *in* the slot even though the Kotlin read still works, so `setProp` keeps the width that is already there. Nothing protects you from a value too large for the declared type; that truncates silently, as it would in Kotlin.

## Every frame

Field's animation helpers, `eachFrame { … }`, `loop { … }`, `forever { … }` and `animate { … }`, take a `suspend Frames.() -> Unit`, and a FieldML function can fill it. Write it with one parameter, the `Frames`, and start it with `launchFrames`:

```diffstatic {data-lang=fieldml}
let Phased = hostClass "afield.runtime.lifecycle.PhasedKt"

// `f` is a Frames, typed off the JVM signature -- so `f.frameNumber` is checked
// here, not on the first frame.
let animation =
  hostStaticCall "eachFrame" (fun f -> f |> hostGet "frameNumber") Phased

// ...and run it on Field's Runloop, with this box as the owner: Stop on this box
// stops the animation, and re-running the box replaces it rather than stacking a
// second one.
thisBox |> hostExt Phased "launchFrames" (animation, "spin", "frames", true)
```

Your result is converted to the type the helper expects, so returning `false` from a `forever` body stops it.

The FieldML body cannot itself suspend: `Frames.frame()` and `Frames.delay()` are out of reach, so use `eachFrame`, `loop` or `forever`, which call your body once per frame, rather than `frames { }`, whose body has to call `frame()` itself.

These are four frames of the spinning-triangle demo [below](#open-it), a second apart:

![four captures of a native Vulkan window, a second apart: a large filled triangle in the middle of a black frame, at a different rotation in each and shading from green-teal through cyan](assets/fieldml_frames_demo/spin-frames.png)

## By name

To pass arguments by name, and leave out the ones with Kotlin defaults, write a record where a tuple would go. A positional call cannot skip a parameter, so named arguments are the only way to use a default:

```diffstatic {data-lang=fieldml}
let Img = hostClass "afield.media.image.Img"
let img = hostNew {width = 2, height = 2, channels = 3, data = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]} Img

// Img.index(x, y, c = 0) -- `c` is left to Kotlin, which is something no
// positional call can express. Order is not position, either.
(img |> hostCall "index" {x = 1, y = 0}, img |> hostCall "index" {c = 2, y = 0, x = 1})
```

This works with `hostCall`, `hostStaticCall`, `hostExt` and `hostNew` alike: a tuple or a list spreads positionally, `()` is no arguments, and a record spreads by name. `hostCall1`, which never spreads, passes one value.

<div class="field-reference" data-code-lang="fieldml" markdown>

| Written | Means |
| --- | --- |
| `hostNew (w, []) Renderer` | Calls the constructor positionally with both arguments present. The empty list explicitly supplies `additionalWindows`. |
| `hostNew {mainWindow = w} Renderer` | Matches constructor parameters by name. Omitting `additionalWindows` uses its Kotlin default. |
| `img \|> hostExt Imaging "colormap" {name = "magma"}` | Calls the extension with a named palette. Omitted `lo` and `hi` use Kotlin’s defaults, computed from the image. |
| `hostCall1 "m" {a = 1} t` | Passes the record as one value. It does not use the record’s fields as named JVM arguments. |

</div>

A name the method does not have is an error where it is written. When two overloads both accept the names you gave, the call is refused with the candidates listed, and a `?` on each parameter that has a default.

## What it still will not do

- A lambda inside a named record has untyped parameters, because parameter types only reach a lambda written as a positional argument. When a callback is involved, use the positional form: `hostStaticCall "defaultBuffer" (hostNull, "inData", fun p -> …)` types `p`, the named spelling does not.
- A JVM `Map` comes back as a FieldML record, so passing one to `hostCall` as one argument needs `hostCall1`, which never spreads. Lists work the same way.
- A callback written inside a helper has untyped parameters. `line |> hostCall1 "transform" (fun p -> …)` in a pipeline is typed; the same call inside `let warp line = …` is not, because `line` is a function parameter with no known class. Use `hostAs "Vec3"` there.
- A lambda's result is not checked against what Kotlin wants. It is converted, which is why `fun x y -> 0` satisfies a function returning `Float`, but a result of the wrong shape fails when it runs.
- A `suspend` method cannot be called. Passing a FieldML function as a suspend lambda works (see [Every frame](#every-frame)), but calling `Renderer.run`, which is `suspend`, does not. The box that owns your window and renderer is still a Kotlin box.
- Two overloads that differ only in how many parameters their lambda takes leave the callback untyped. `start` takes both `suspend Frames.() -> Unit` and `suspend () -> Unit`; the call works, but `eachFrame`, `loop` and `forever`, which have one form each, type the parameter.
- `internal` Kotlin members cannot be reached by their source names, because the compiler renames them.
- Buffers, kernels and anchors, which FieldML cannot build and which have no constructor to call, are out of reach.

## Open it

Each of these documents demonstrates part of the page. Start with the macro demo: it needs no GPU and nothing wired up.

The [`fieldml_macro_demo`](agent_transcripts.md#fieldml_macro_demo) starting point has four boxes: a `prop` written in one and inherited by another that draws a spiral from it, Field's own `println` macro expanded and called, and the generated Kotlin of `prop` and `sourceBlock` as readable text. Run the parameter box, run the drawing box, then change `swirl` or `turns` in the first and run both again.

[Open ↗](offline.html?file=assets/fieldml_macro_demo/metadata.edn), or open the [`fieldml_macro_demo`](agent_transcripts.md#fieldml_macro_demo) starting point from the welcome screen.

![two captures of the same FieldML box side by side on black: on the left a wide four-turn cyan spiral filling the frame, on the right a tight nine-turn spiral about half the diameter](assets/fieldml_macro_demo/spiral-pair.png)

<div class="field-data-table" markdown>

| In | Change | What happens |
| --- | --- | --- |
| the parameter box | `setProp "turns" 4` → `setProp "turns" 9` | re-run both boxes: nine turns instead of four. The drawing box is unchanged — it inherits the value |
| the parameter box | `setProp "swirl" 0.85` → `0.35` | a tighter spiral; the `swirl` scales the radius |
| the parameter box | run it twice | `firstRunAt` does not move. That is `once`: computed on the first read and stored |
| the parameter box | `propOr "neverWritten" "not set"` → `prop "neverWritten"` | an error naming the slot, because nothing up the graph has it. `propOr` is the read that does not fail |
| the println box | `(4, "…")` → `(12, "…")` | the output claims to come from line 12, and the gutter marker moves |
| the println box | `{line = 5}` → `{line = 5, a = "named"}` | the vararg named rather than omitted |
| the expansion box | `"prop"` → `"rootProp"` | the call site becomes `root.speed` instead of `speed` |
| the expansion box | `"sourceBlock"` → `"jsonParser"` | ...and an error: `jsonParser` wants a type argument and a schema string |
| the drawing box | disconnect it from the FieldML root | it becomes a Kotlin box, and reports Kotlin errors. Only the *direct* children of a FieldML root are FieldML, which is why it has two parents |

</div>

The host bridge demo has five boxes: the raster above, a line warped by a FieldML lambda through `FLine.transform`, a box whose host member types are all inferred, one that exercises overload resolution, varargs and `hostNull`, and one that calls Kotlin by name and leaves its defaults alone.

[Open ↗](offline.html?file=assets/fieldml_host_bridge_demo/metadata.edn), or open the [`fieldml_host_bridge_demo`](agent_transcripts.md#fieldml_host_bridge_demo) starting point from the welcome screen.

Things to change:

<div class="field-data-table" markdown>

| In | Change | What happens |
| --- | --- | --- |
| the raster box | `sin (r * 11.0)` → `sin (r * 5.0)` | fewer, wider rings |
| the raster box | `"viridis"` → `"magma"` | the palette; anything in Field's `Colormaps` works |
| the raster box | `(240, 240, sample)` → `(96, 96, sample)` | a coarser raster, rendered faster |
| the warp box | `24.0` → `60.0` | a taller wave |
| the types box | `img.width` → `img.widht` | a type error, before the box runs |
| the types box | put the caret inside `hostGet ""` | completion lists `Img`'s members with their types |
| the named box | `{x = 1, y = 0}` → `{x = 1, yy = 0}` | a type error listing the names you gave, before the box runs |
| the named box | `{name = "magma"}` → `{name = "magma", lo = 0.0, hi = 0.5}` | the two defaults stop being computed from the image |

</div>

On a machine with a GPU, the Field 4 demo builds a scene from FieldML. One box finds the `Renderer` above it, builds a shader module from two GLSL strings through `parameters(renderer) { vertex = …; fragment = … }`, attaches a three-vertex mesh, and registers it with the render target. Connect it to whichever box owns your renderer.

[Open ↗](offline.html?file=assets/fieldml_afield_four_demo/metadata.edn), or open the [`fieldml_afield_four_demo`](agent_transcripts.md#fieldml_afield_four_demo) starting point from the welcome screen.

The frames demo is the same scene with a `Frames` loop on it, the spinning triangle pictured above. Its first box is a small Kotlin box that opens the window and owns the `Renderer`, so there is nothing to wire up. Open it, run `window and renderer`, then run the FieldML box.

[Open ↗](offline.html?file=assets/fieldml_frames_demo/metadata.edn), or open the [`fieldml_frames_demo`](agent_transcripts.md#fieldml_frames_demo) starting point from the welcome screen.

| In | Change | What happens |
| --- | --- | --- |
| the FieldML box | `* 0.04` → `* 0.2` | it spins five times faster, and the colour cycles with it |
| the FieldML box | `spin |> hostCall "set" (t, 0.0, 0.0, 1.0)` → `(0.0, 0.0, 0.0, 1.0)` | it stops dead: the loop still runs, it just writes the same value |
| the FieldML box | `"eachFrame"` → `"loop"` | no visible difference — `loop` advances a frame only when the body did not |
| the FieldML box | `f |> hostGet "frameNumber"` → `f |> hostGet "frameNumbr"` | a type error naming `Frames`, before the box runs: the callback parameter has a type |
| the fragment shader | `vec3(0.0, 2.1, 4.2)` → `vec3(0.0, 0.0, 0.0)` | it cycles through greys instead of hues |

In the macro module demo, a macro builds the scene: instead of hand-writing `parameters(renderer) { vertex = …; fragment = … }` and `Module(params)`, the box calls Field's `moduleHere`, so its GLSL lives in the box's own `vertex` and `fragment` properties, exactly where a Kotlin graphics box keeps it. Self-contained: run the Kotlin `window and renderer` box, then the scene box.

Then run the fourth box while it is spinning. It writes `spinRate` with `setRootProp`, and the scene reads that slot on *every frame*, so another box changes the speed of the running scene.

[Open ↗](offline.html?file=assets/fieldml_macro_module_demo/metadata.edn), or open the [`fieldml_macro_module_demo`](agent_transcripts.md#fieldml_macro_module_demo) starting point from the welcome screen.

<div class="field-data-table" markdown>

| In | Change | What happens |
| --- | --- | --- |
| the live box | `setRootProp "spinRate" 0.30` → `0.02` | the triangle slows, immediately, without the scene box being run |
| the live box | run it twice | `framesDrawn` grows — that is the per-frame body reporting from inside the render loop |
| the scene box | `propOr "spinRate" 0.04` inside `eachFrame` → move it above the lambda | it becomes a constant: changing the live box does nothing |
| the scene box | edit the `fragment` string's `vec3(0.0, 2.1, 4.2)` | different hues, because `moduleHere` recompiles from the box's properties |
| the scene box | break the GLSL | the shader error is reported onto *this box*, which is the thing `moduleHere` gives you over hand-built parameters |

</div>

Its output record says what the macro did: `builtBy = "_moduleHere"` names the function it rewrote to, and `pushConstantBytes` describes the push-constant block `moduleHere` compiled from the box's `vertex` and `fragment` properties.

The mesh demo has a vertex shader with real inputs, whose data FieldML recomputes every frame: two `layout(location = …) in` variables, both written through `Mesh.write`, one positionally and one by name, leaving out `Mesh.write`'s `inside` default. Like the frames demo, it opens its own window.

[Open ↗](offline.html?file=assets/fieldml_mesh_demo/metadata.edn), or open the [`fieldml_mesh_demo`](agent_transcripts.md#fieldml_mesh_demo) starting point from the welcome screen.

![four captures of a native Vulkan window a second apart, each a ring of coloured triangles on black, the hues rotating between frames](assets/fieldml_mesh_demo/ring-frames.png)

The lambda demo is about callback parameter types: a warp with no `hostAs` in it, a raster whose two callbacks have typed parameters, a box of inferred types, and a box showing where a parameter stays untyped.

[Open ↗](offline.html?file=assets/fieldml_lambda_demo/metadata.edn), or open the [`fieldml_lambda_demo`](agent_transcripts.md#fieldml_lambda_demo) starting point from the welcome screen.

<div class="field-data-table" markdown>

| In | Change | What happens |
| --- | --- | --- |
| the warp box | `p.x` → `p.xx` | a type error naming `Vec3`'s components, before the box runs |
| the warp box | `26.0` → `60.0` | a taller wave |
| the warp box | put the caret after `p.` inside the lambda | completion lists `Vec3`'s components — the callback parameter has a type |
| the raster box | `13.0` → `5.0` | fewer, wider rings |
| the raster box | `"magma"` → `"viridis"` | the palette |
| the limits box | delete `hostAs "Vec3"` from `scale` | a type error: inside a helper, the parameter has no type without it |

</div>

## Where to look

The reflection lives in `afield.lang.fieldml.core.FieldMLHostBridge` (candidates, scoring, coercion, SAM proxies) and `afield.lang.fieldml.type.FieldMLHostTypes` (JVM types to FieldML types, member and overload resolution at type-check time). `Inferencer.refineHostIntrinsic` is where the two meet, and `Inferencer.preInferHostSpine` is what threads a JVM parameter type into a lambda's parameters. The macro layer is `afield.lang.fieldml.FieldMLMacroLayer`: expansion, the callee extraction, and the ledger of which macro is reachable how. `afield.document.properties.inheritedPropertyRaw` is the walk `prop` and Kotlin's `Inheritance` delegate share. The design notes are in [`dev_docs/FIELDML_HOST_BRIDGE.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/FIELDML_HOST_BRIDGE.md).

??? aside "Which test holds which claim"
    `FieldMLHostBridgeTest` has one test per capability. `FieldMLHostSurfaceCoverageTest` is the
    reachability-and-agreement measurement. `FieldMLHostAgreementTest` holds the semantically interesting
    calls beside the Kotlin they should equal. `FieldMLLambdaExpectedTypeTest` covers the typed callback
    parameters and the cases that stay untyped. `FieldMLSuspendCallbackTest` is the per-frame
    surface: every test in it asserts that the closure *ran*, not that a proxy exists.
    `FieldMLAfieldFourReachTest` is the `threebox-2` survey with its citations, and `FieldMLSourceMacroTest`
    holds the macro ledger to the live contents of `afield.runtime.macros.MacroRegistry` in both directions.

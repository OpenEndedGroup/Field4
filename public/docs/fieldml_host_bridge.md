# FieldML's JVM bridge

FieldML is a small language with a large host. The prelude covers geometry — `fline`, `vec3`, `draw`, `animate` — but Field itself is thousands of Kotlin functions, and the bridge is how a FieldML box reaches them. This page is that bridge: what it can do, what it refuses, and where it deliberately stops short.

The short version: **a FieldML box can call a Kotlin method with any number of arguments, pass a FieldML function where Kotlin wants a lambda, and get a real type back — on both sides of that lambda.** The measurement in `FieldMLHostSurfaceCoverageTest` puts the first part at 344 of the 362 public methods of `FLine`, `Img`, the three linalg vectors and Field's two imaging extension files — 95%, against a modelled 166 for the narrower bridge this replaced. The second part is 21 of the 23 callback argument slots across the same types: a lambda handed to a JVM method has its parameters typed from that method's signature, and the two that stay untyped are one erased generic and one nullable Kotlin parameter, both of which would be lies.

## The one that proves it

This box never leaves FieldML, and everything it draws comes out of Kotlin. A FieldML function of `(x, y)` is handed to `Img.generate`, which rasterises it; the result goes through Field's `colormap` extension and out through `showImg`. All three steps were unreachable before: three arguments, a Kotlin lambda parameter, a static member off a class handle, and an extension function called with the piped value as its receiver.

```diffstatic_runner {data-lang=fieldml screenshot="assets/fieldml_host_bridge/generated_field.png" wait="2.0" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="fieldml-host-bridge-field" w="760" h="560" alt="a 240 by 240 raster in the viridis colourmap: concentric rings around a bright yellow centre, the rings shading out through green to deep indigo and back to teal, each ring pinched into lobes above and below the centre"}
let Img = hostClass "afield.stdlib.Img"
let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"

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

Change `11.0` in `sin (r * 11.0)` and the ring spacing changes; change `"viridis"` to `"magma"` or `"coolwarm"` and the palette does. Both are ordinary edits to a FieldML box that re-run the Kotlin rasteriser.

The last two steps show the two shapes a Kotlin extension function can be called in. `colormap` is an extension on `Img`, so the piped picture is its receiver and `hostExt` reads well. `showImg` is an extension on `Box`, so its receiver is `thisBox` and the picture is an ordinary argument — which is `hostStaticCall`, with every parameter written out in order.

## Calling a method

`hostCall name arguments target` — the target last, so it sits at the end of a pipe.

```diffstatic {data-lang=fieldml}
let Img = hostClass "afield.stdlib.Img"
let img = hostNew (4, 4, 1, [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 0.9, 0.8, 0.7, 0.6, 0.5]) Img

img |> hostCall "index" (1, 0, 0)
```

The arguments are a tuple, and a tuple is spread — so `(1, 0, 0)` is three arguments. A list written in place is spread the same way, `()` is no arguments at all, and anything else is one argument. That last rule is the only place to be careful: to pass a single *list* as one argument, use `hostCall1`, which never spreads.

| Form | Arguments |
| --- | --- |
| `hostCall "m" () t` | none |
| `hostCall0 "m" t` | none |
| `hostCall1 "m" x t` | exactly one, whatever `x` is |
| `hostCall "m" (a, b, c) t` | three |
| `hostCall "m" [a, b, c] t` | three |
| `hostCall1 "m" [a, b, c] t` | one, a list |

`hostNew arguments class` and `hostNew0 class` are the same for constructors. `hostStaticCall name arguments class` is the same for statics, and `hostExt class name arguments` is a static called with the piped value in front of the written arguments — which is what a Kotlin extension function compiles to, and most of Field's API is extension functions.

Overloads are resolved against the real reflected candidate set, scored per argument, so `hostStaticCall "max" (3, 7)` reaches `Math.max(int, int)` and `(3.5, 7.25)` reaches `Math.max(double, double)`. FieldML has one integer type and one float type, so a conversion happens on nearly every call: `Int` reaches `long`/`short`/`Number`, `Float` reaches `float` and `Double`, a FieldML list reaches a JVM array or a `vararg`, and `hostNull` reaches anything nullable — which is how an optional Kotlin parameter gets filled, since a reflective call cannot use Kotlin's defaults.

## Passing a function

A FieldML function handed to a parameter whose type is a functional interface becomes one. That covers a Kotlin lambda parameter (`(Int, Int) -> Float`), a receiver lambda (`Box.() -> Unit`, whose receiver arrives as the first argument), and the Java classics — `Runnable`, `Supplier`, `Comparator`.

```diffstatic {data-lang=fieldml}
fline
  |> moveTo 0.0 0.0
  |> lineTo 100.0 0.0
  |> lineTo 100.0 60.0
  |> hostCall1 "transform" (fun p ->
       vec3 (p.x) (p.y + 20.0 * sin (p.x / 25.0)) (p.z)
     )
```

**`p` is a `Vec3`, and nothing above says so.** `FLine.transform` takes a Kotlin `(Vec3) -> Vec3`, the type checker reads that signature off the receiver, and the lambda's parameters are bound to what the JVM is going to hand them — so `.x`, `.y` and `.z` are ordinary checked vector components, `p.xx` is an error before the box runs, and completion works inside the body. Six parameters get typed in `Img.generate(w, h) { x, y -> … }` plus `Img.mapIndexed { x, y, c, v -> … }`; `x` and `y` really are `Int`s, so `x + y` is integer arithmetic. A Kotlin *receiver* lambda (`BufferParameters.() -> Unit`) gets its receiver typed the same way, because the receiver is simply argument zero.

Two cases keep the old behaviour, and keep it on purpose:

- **An erased generic.** `Collections.max(collection, Comparator<? super T>)` has no `T` at the use site, so `fun a b -> b - a` keeps ordinary inferred parameters. That is better than a type: `Host` would refuse the subtraction.
- **A nullable Kotlin parameter.** `FLine.byFiltering((Vec3?, Node) -> Boolean)` really is handed a null for the first node's predecessor, so that parameter stays open while the second — not nullable — is typed `Node`.

And one case where you still need `hostAs`: the receiver has to be known *where the call is written*. Inside `let warp line = line |> hostCall1 "transform" (fun p -> …)` there is no class to read, because `line` is just a function parameter. Written as part of a pipeline, it is typed.

A function taking no arguments (`Runnable`) is written `fun ignored -> …`.

## Types

This is the half that is easy to miss. Host values carry their real JVM class, and the type checker reflects on it *while it checks*:

```diffstatic {data-lang=fieldml}
let Img = hostClass "afield.stdlib.Img"
let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i / 32.0) 32) Img

// Int, inferred from Img.getWidth() — not Host, and no cast anywhere.
img.width * img.height + img.channels
```

So:

- `thisBox` is a `Box`, `fline` is an `FLine`, `hostClass "afield.stdlib.Img"` is a `Class<Img>`, and `hostGet "Companion"` on it is an `Img.Companion`;
- dot access on a host value reads a JVM member, so `img.width` is an `Int`;
- a member or an overload that does not exist is a **type error**, reported where the name was written, rather than a crash on the first run — misspell `hostGet "widht"` and the box says `Box has no member 'widht'` before it runs;
- completion offers the members of a typed receiver: inside `img |> hostGet "wi` the list is `width`, and it says `Int`.

### `Host` is the dynamic type, and it has a boundary

Reflection cannot always say. A generic erased to `Object`, an array, a `Map`, a nullable Kotlin return that may arrive as nothing at all — for all of these the answer stays the opaque `Host`, because naming a class the program can violate on its first run would be worse than saying nothing.

`Host` therefore unifies with other types, and satisfies the numeric, vector and equality constraints, so an erased result is usable without a cast. But it is bounded, and the bound is one sentence: **`Host` stands in for a value of unknown *class*, not of unknown *shape*.**

An `Object`-erased JVM value may well turn out to be a number, a string, a boolean, nothing at all, or another object — so `Host` unifies with all of those. It is never a FieldML *function* and never a FieldML *structure*, because the host-to-FieldML conversion cannot produce one. So these are type errors, and they say what to do instead:

```diffstatic {data-lang=text}
(hostInput |> hostGet "whatever") 42
  A Host cannot be used as (Int -> 'a): reflection could not type this
  value, so call it with hostCall1 "invoke" instead

hostInput |> hostGet "whatever" |> head
  A Host cannot be used as ['a]: reflection could not type this value,
  so convert it with hostAsList
```

Two host types whose classes are both *known* and unrelated do not unify either — `if true then img else fline` is `Img is not FLine (afield.stdlib.Img and afield.graphics.FLine are unrelated)`. Two where one is assignable from the other do, which is what lets a `Vector2f` reach a parameter declared as its read-only `Vector2fc` interface.

`hostAsList` is the way from an opaque host value to a FieldML list — it converts a JVM `List`, `Set`, array or `Map`, which is also how `Box.parents` becomes something a FieldML box can walk. The rest of the `hostAs…` family is for when a value needs to become a scalar:

```diffstatic {data-lang=fieldml}
let Collections = hostClass "java.util.Collections"

// Collections.max returns T, which erases to Object, so it stays Host.
hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections
  |> hostAsInt
```

`hostAsInt`, `hostAsFloat`, `hostAsBool` and `hostAsString` read a scalar out; `hostAs "SomeClass"` is a checked cast to a class, and doubles as the way FieldML names one.

## Does it get the right answer?

Reachability is not correctness. A bridge that chose the wrong overload, put an argument in the wrong slot, or lost precision converting a result would still report a high "callable" number — so the coverage measurement calls every method **twice**: once through the bridge, once through plain `Method.invoke` with argument values of exactly the right JVM types, and compares the results.

```
callable now:         344 / 362 (95.0%)
reachable before:     166 / 362 (45.9%)
calls run both ways:  341
comparable:           324
agreed:               324 / 324 (100.0%)
no way to compare:    17    (a returned class with no value equality)
input-independent:    2     (FLine.getUid/getRev read a global counter)
```

It earned its keep on the first run. `Vector2f` has both `void set(int, float)` and `Vector2f set(float, float)`; a FieldML `(3.0, 4.0)` fits both, they scored equal, and the winner was whatever the JVM happened to list first — so `hostCall "set" (3.0, 4.0)` set the wrong component and returned nothing. Overload scoring now prefers the parameter of the same *kind* (floating point for a FieldML `Float`, integral for an `Int`) and charges most for the lossy direction, and the type checker and the runtime share that scoring so they cannot disagree about which method a call means.

## Reaching `afield.four`

`afield.four` is Field's Vulkan layer and the reason this bridge exists — the live workspaces are hundreds of Kotlin boxes driving it. FieldML can now express most of it, and the honest accounting matters more than the headline.

Taking `threebox-2`, the minimal complete `afield.four` scene (a window, a compute shader over 100 000 particles, a draw pass), **12 of its 19 statements are expressible in FieldML**. Five run headlessly and are checked against the same call in Kotlin — including `defaultBuffer { size = N.vec4s }`, whose argument is a Kotlin *receiver* lambda and whose `vec4s` is a member extension property that only exists inside it. Everything downstream of a Vulkan device is expressible but untested here, because a headless test JVM has no device and a demo that pretended otherwise would be worth nothing.

The seven that are not expressible are worth knowing by name, because they are all the same kind of thing:

| Statement | Why not |
| --- | --- |
| `prop<Window>(window)` | a **source macro**: it generates `var Box.window by Inheritance<Window>()` into the box's preamble. `prop` itself throws if it is ever really called |
| `once.renderer { … }` | a **macro-generated property delegate** — the name does not exist until the generated preamble is compiled |
| `moduleHere { … }`, `computeModuleHere { }`, `fieldProbeHere(…)` | source macros that also write shader source into box properties at expansion time |
| `start(renderer::run)` | a **suspend** function type: it erases to a `Function2` taking a `Continuation`, which a dynamic proxy cannot satisfy |
| `import …` | not needed — FieldML names every class in full |

Macros are the whole story: nine of Field's box-code names are registered rewrites over Kotlin *source*, applied before the compiler runs, so there is no JVM member for reflection to find. What is reachable is what they are sugar *over* — `prop` is a named slot on a box, and `getPropertyRaw`/`setPropertyRaw` are ordinary methods, so a FieldML box can read an inherited property with a four-line recursive walk over `parents`. `once` is get-or-compute-and-store over the same pair. Both of those run today.

## What it still will not do

Worth knowing before you fight it:

- **Kotlin default arguments are invisible.** A reflective call has to spell every parameter — `Renderer(window)` becomes `hostNew (window, []) …`. `hostTap` is the exception, because it reads defaults out of Field's *source* index, and it remains the best tool when a Kotlin function is an effect in the middle of a pipe.
- **Named arguments are invisible** for the same reason: a reflective call is positional.
- **A callback written inside a helper has untyped parameters.** The expected type comes from the receiver, so `line |> hostCall1 "transform" (fun p -> …)` in a pipeline is typed and the same call inside `let warp line = …` is not — `line` is a function parameter and there is no class to reflect on. `hostAs "Vec3"` is still the way through that one. The same is true of anything whose type reflection genuinely cannot read: an erased generic, a nullable Kotlin parameter.
- **A lambda's *result* is not checked** against what the JVM wants. The proxy coerces it, which is why `fun x y -> 0` satisfies a `Function2<…, Float>` — but a body that returns the wrong shape entirely still fails at run time.
- **`suspend` function types cannot be satisfied.** They need coroutine machinery, not a proxy.
- **`internal` Kotlin members are unreachable** under their source names, because the compiler mangles them.
- **Buffers, kernels and anchors** — anything FieldML has no way to construct and no constructor to call — are still out of reach. In the coverage measurement those are most of the remaining 5%.

## Open it

There are two Field documents for this page.

The first has four boxes: the raster above, a line warped by a FieldML lambda through `FLine.transform`, a box whose host member types are all inferred, and one that exercises overload resolution, varargs and `hostNull`.

[Open ↗](/offline.html?file=/field4/assets/fieldml_host_bridge_demo/metadata.edn) — or open `public/docs/assets/fieldml_host_bridge_demo/metadata.edn` from a Field workspace.

Things to change, in the order they are most likely to teach you something:

| In | Change | What happens |
| --- | --- | --- |
| the raster box | `sin (r * 11.0)` → `sin (r * 5.0)` | fewer, wider rings |
| the raster box | `"viridis"` → `"magma"` | the palette; anything in Field's `Colormaps` works |
| the raster box | `(240, 240, sample)` → `(96, 96, sample)` | a coarser raster, rendered faster |
| the warp box | `24.0` → `60.0` | a taller wave |
| the types box | `img.width` → `img.widht` | a type error, before the box runs |
| the types box | put the caret inside `hostGet ""` | completion lists `Img`'s members with their types |

The second is about the lambda parameters specifically — a warp with no `hostAs` in it, a raster whose two callbacks have six typed parameters between them, a box of inferred types, and a box that says where the inference still declines to say anything.

[Open ↗](/offline.html?file=/field4/assets/fieldml_lambda_demo/metadata.edn) — or open `public/docs/assets/fieldml_lambda_demo/metadata.edn` from a Field workspace.

| In | Change | What happens |
| --- | --- | --- |
| the warp box | `p.x` → `p.xx` | a type error naming `Vec3`'s components, before the box runs |
| the warp box | `26.0` → `60.0` | a taller wave |
| the warp box | put the caret after `p.` inside the lambda | completion lists `Vec3`'s components — the callback parameter has a type |
| the raster box | `13.0` → `5.0` | fewer, wider rings |
| the raster box | `"magma"` → `"viridis"` | the palette |
| the limits box | delete `hostAs "Vec3"` from `scale` | a type error — that is the one case still on its own |

## Where to look

The reflection lives in `afield.ml.core.FieldMLHostBridge` (candidates, scoring, coercion, SAM proxies) and `afield.ml.type.FieldMLHostTypes` (JVM types to FieldML types, member and overload resolution at type-check time). `Inferencer.refineHostIntrinsic` is where the two meet, and `Inferencer.preInferHostSpine` is what threads a JVM parameter type into a lambda's parameters. `FieldMLHostBridgeTest` has one test per capability, `FieldMLHostSurfaceCoverageTest` is the reachability-and-agreement measurement, `FieldMLHostAgreementTest` holds the semantically interesting calls beside the Kotlin they should equal, `FieldMLLambdaExpectedTypeTest` covers the typed callback parameters and the cases that deliberately stay untyped, and `FieldMLAfieldFourReachTest` is the `threebox-2` survey with its citations.

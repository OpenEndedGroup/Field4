# FieldML's JVM bridge

FieldML is a small language with a large host. The prelude covers geometry — `fline`, `vec3`, `draw`, `animate` — but Field itself is thousands of Kotlin functions, and the bridge is how a FieldML box reaches them. This page is that bridge: what it can do, what it refuses, and where it deliberately stops short.

The short version: **a FieldML box can call a Kotlin method with any number of arguments, pass a FieldML function where Kotlin wants a lambda, and get a real type back.** The measurement in `FieldMLHostSurfaceCoverageTest` puts that at 344 of the 362 public methods of `FLine`, `Img`, the three linalg vectors and Field's two imaging extension files — 95%. The narrower bridge this replaced reached 166 of them.

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
  |> hostCall1 "transform" (fun raw ->
       let p = hostAs "Vec3" raw in
       vec3 (p.x) (p.y + 20.0 * sin (p.x / 25.0)) (p.z)
     )
```

The value the JVM passes in is, from FieldML's point of view, a value of unknown class — so `hostAs "Vec3"` is where it gets a type, and from there `.x`, `.y` and `.z` are ordinary checked vector components. A function taking no arguments (`Runnable`) is written `fun ignored -> …`.

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

### `Host` is the dynamic type

Reflection cannot always say. A generic erased to `Object`, an array, a `Map`, a nullable Kotlin return that may arrive as nothing at all — for all of these the answer stays the opaque `Host`, because naming a class the program can violate on its first run would be worse than saying nothing.

`Host` is therefore the one type that unifies with every other, and satisfies the numeric, vector and equality constraints. That keeps every FieldML program written before any of this existed working unchanged, and it is what the `hostAs…` family is for when a value needs to become concrete:

```diffstatic {data-lang=fieldml}
let Collections = hostClass "java.util.Collections"

// Collections.max returns T, which erases to Object, so it stays Host.
hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections
  |> hostAsInt
```

`hostAsInt`, `hostAsFloat`, `hostAsBool` and `hostAsString` read a scalar out; `hostAs "SomeClass"` is a checked cast to a class, and doubles as the way FieldML names one.

## What it still will not do

Worth knowing before you fight it:

- **Kotlin default arguments are invisible.** A reflective call has to spell every parameter. `hostTap` is the exception — it reads defaults out of Field's *source* index — and it remains the best tool when a Kotlin function is an effect in the middle of a pipe.
- **A lambda's parameters have no inferred type.** The JVM calls back into FieldML with a value the checker knows nothing about, so `hostAs` is needed inside the lambda. Pushing the reflected parameter type into the lambda would need expected types threaded through inference, which FieldML does not have yet.
- **`internal` Kotlin members are unreachable** under their source names, because the compiler mangles them.
- **Buffers, kernels and anchors** — anything FieldML has no way to construct and no constructor to call — are still out of reach. In the coverage measurement those are most of the remaining 5%.

## Open it

There is a Field document for this page. It has four boxes: the raster above, a line warped by a FieldML lambda through `FLine.transform`, a box whose host member types are all inferred, and one that exercises overload resolution, varargs and `hostNull`.

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

## Where to look

The reflection lives in `afield.ml.core.FieldMLHostBridge` (candidates, scoring, coercion, SAM proxies) and `afield.ml.type.FieldMLHostTypes` (JVM types to FieldML types, member and overload resolution at type-check time). `Inferencer.refineHostIntrinsic` is where the two meet. `FieldMLHostBridgeTest` has one test per capability, and `FieldMLHostSurfaceCoverageTest` is the measurement.

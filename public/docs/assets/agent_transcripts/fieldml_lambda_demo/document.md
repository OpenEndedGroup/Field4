# Field Document: fieldml_lambda_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. FieldML — lambdas with types

<!-- field-box id="fieldml-lambda-root" index="0" -->

```edn [field-box id="fieldml-lambda-root" role="properties"]
{"id" "fieldml-lambda-root" "index" 0 "name" "FieldML — lambdas with types" "parents" ["root"] "children" ["fieldml-lambda-warp" "fieldml-lambda-raster" "fieldml-lambda-types" "fieldml-lambda-limits"] "properties" {"children" #{#afield/box "fieldml-lambda-warp" #afield/box "fieldml-lambda-raster" #afield/box "fieldml-lambda-types" #afield/box "fieldml-lambda-limits"} "parents" #{#afield/box "root"} "name" "FieldML — lambdas with types" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 2. A lambda the JVM calls, with no cast

<!-- field-box id="fieldml-lambda-warp" index="1" -->

### Narrative

From `code`

FLine.transform takes a Kotlin (Vec3) -> Vec3. The type checker reads that
signature off the receiver and gives `p` a type, so `p.x` is a checked Vec3
component and there is no `hostAs` anywhere in this box. Misspell `p.x` as
`p.xx` and the box says so before it runs.

Change 26.0 for the wave height, 20.0 for its wavelength.

```edn [field-box id="fieldml-lambda-warp" role="properties"]
{"id" "fieldml-lambda-warp" "index" 1 "name" "A lambda the JVM calls, with no cast" "parents" ["root" "fieldml-lambda-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-lambda-root"} "name" "A lambda the JVM calls, with no cast" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-lambda-warp_code_.kt" "lines" 17 "characters" 677 "sha256" "66b8c2bd25dfb1f4d87e17e4612f220edb878cff07aa790d3b9cee215ddc135c"}]}
```

```kotlin [field-property box="fieldml-lambda-warp" name="code" path="fieldml-lambda-warp_code_.kt"]
// FLine.transform takes a Kotlin (Vec3) -> Vec3. The type checker reads that
// signature off the receiver and gives `p` a type, so `p.x` is a checked Vec3
// component and there is no `hostAs` anywhere in this box. Misspell `p.x` as
// `p.xx` and the box says so before it runs.
//
// Change 26.0 for the wave height, 20.0 for its wavelength.
linspace 0.0 280.0 140
  |> map (fun x -> vec2 x 70.0)
  |> fold (fun line point -> line |> lineToV point) fline
  |> hostCall1 "transform" (fun p ->
       vec3 p.x (p.y + 26.0 * sin (p.x / 20.0)) p.z
     )
  |> stroked true
  |> filled false
  |> color (vec4 0.35 0.8 1.0 1.0)
  |> thickness 2.5
  |> drawSized "main" 320.0 170.0
```

## 3. Where it still says nothing

<!-- field-box id="fieldml-lambda-limits" index="2" -->

### Narrative

From `code`

The other half of the story, and the honest half. Reflection cannot always
say what a callback will be handed, and where it cannot the parameter keeps
the open type variable it has always had — claiming a type there would
refuse programs that work today.

```edn [field-box id="fieldml-lambda-limits" role="properties"]
{"id" "fieldml-lambda-limits" "index" 2 "name" "Where it still says nothing" "parents" ["root" "fieldml-lambda-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-lambda-root"} "name" "Where it still says nothing" "_tombstone" false "frame" #afield/rect [760.0 300.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-lambda-limits_code_.kt" "lines" 31 "characters" 1483 "sha256" "fbb0f2343836e34097682f69251d3bd36ee3bb94c1a4d3035d7a2772611ad53d"}]}
```

```kotlin [field-property box="fieldml-lambda-limits" name="code" path="fieldml-lambda-limits_code_.kt"]
// The other half of the story, and the honest half. Reflection cannot always
// say what a callback will be handed, and where it cannot the parameter keeps
// the open type variable it has always had — claiming a type there would
// refuse programs that work today.
let Collections = hostClass "java.util.Collections"

{
  // Comparator<? super T>: T is erased at the use site, so `a` and `b` stay
  // open. They are still perfectly usable — inferred from the body, as before.
  reversedMax = hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections |> hostAsInt

  // FLine.byFiltering((Vec3?, Node) -> Boolean). The first parameter really can
  // arrive null, so it stays open; the second is not nullable and is typed.
  kept =
    fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0 |> lineTo 20.0 8.0
      |> hostCall1 "byFiltering" (fun previous node -> true)
      |> hostGet "nodes" |> length

  // And the third case: a receiver that is a *function parameter* has no known
  // class yet, so neither the callback inside `let f line = ...` nor the result
  // of calling it gets a type. `hostAs` remains the way out of that one, and it
  // is needed twice here — once going in, once coming out.
  viaHelper =
    let scale line =
      line |> hostCall1 "transform" (fun raw ->
        let p = hostAs "Vec3" raw in
        vec3 (p.x * 0.5) p.y p.z
      )
    in scale (fline |> moveTo 0.0 0.0 |> lineTo 10.0 0.0)
         |> hostAs "FLine" |> hostGet "nodes" |> length
}
```

## 4. Six typed callback parameters

<!-- field-box id="fieldml-lambda-raster" index="3" -->

```edn [field-box id="fieldml-lambda-raster" role="properties"]
{"id" "fieldml-lambda-raster" "index" 3 "name" "Six typed callback parameters" "parents" ["root" "fieldml-lambda-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-lambda-root"} "name" "Six typed callback parameters" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-lambda-raster_code_.kt" "lines" 22 "characters" 1020 "sha256" "3c2bf415b83fb5861c0d8a7a998debd1db826d9f1eeb69343901b03409f8bb08"}]}
```

```kotlin [field-property box="fieldml-lambda-raster" name="code" path="fieldml-lambda-raster_code_.kt"]
let Img = hostClass "afield.media.image.Img"
let Imaging = hostClass "afield.media.image.Imaging_fieldKt"

// Two callbacks, six typed parameters, no casts.
//
// Img.Companion.generate wants a Kotlin (Int, Int) -> Float, and Img.mapIndexed
// wants an (Int, Int, Int, Float) -> Float. Those shapes exist only on the
// *use site's* generic type — `Function4.invoke` itself erases to Objects — so
// reading them there is what makes `x + y` below integer arithmetic.
let flat = Img |> hostGet "Companion" |> hostCall "generate" (220, 220, fun x y -> 0.0)

let rings =
  flat |> hostCall1 "mapIndexed" (fun x y c v ->
    let u = (toFloat x - 110.0) / 110.0 in
    let w = (toFloat y - 110.0) / 110.0 in
    let r = sqrt (u * u + w * w) in
    sin (r * 13.0 - toFloat ((x + y) |> mod 9) * 0.2) * exp (0.0 - r * 1.2) * 0.5 + 0.5
  )

let coloured = rings |> hostExt Imaging "colormap" ("magma", hostNull, hostNull)

hostStaticCall "showImg" (thisBox, coloured, "main", 330, "Img.mapIndexed, four typed parameters") Imaging
```

## 5. What the checker knows inside a lambda

<!-- field-box id="fieldml-lambda-types" index="4" -->

### Narrative

From `code`

Everything below is inferred. Put the caret inside a lambda body and ask for
completion, or misspell a member, and the box answers from the JVM signature
the callback is going to satisfy.

```edn [field-box id="fieldml-lambda-types" role="properties"]
{"id" "fieldml-lambda-types" "index" 4 "name" "What the checker knows inside a lambda" "parents" ["root" "fieldml-lambda-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-lambda-root"} "name" "What the checker knows inside a lambda" "_tombstone" false "frame" #afield/rect [400.0 170.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-lambda-types_code_.kt" "lines" 21 "characters" 898 "sha256" "c9fad63b4aaffaeea8a96870dabe5ae996ef208fab3d5e8b4d1fdaab10798b4f"}]}
```

```kotlin [field-property box="fieldml-lambda-types" name="code" path="fieldml-lambda-types_code_.kt"]
// Everything below is inferred. Put the caret inside a lambda body and ask for
// completion, or misspell a member, and the box answers from the JVM signature
// the callback is going to satisfy.
let Img = hostClass "afield.media.image.Img"

// `x` and `y` are Ints because Function2's type arguments say so, so `x * 4 + y`
// is integer arithmetic and `toFloat` is needed to leave it.
let img = Img |> hostGet "Companion" |> hostCall "generate" (8, 4, fun x y -> toFloat (x * 4 + y) / 32.0)

// `p` is a Vec3, so `normalize` and `vlength` are checked vector operations
// rather than dynamic calls that would only fail on the first run.
let unit =
  fline |> moveTo 0.0 0.0 |> lineTo 3.0 4.0
    |> hostCall1 "transform" (fun p -> normalize p * (vlength p / 5.0))

{
  nodes = unit |> hostGet "nodes" |> length
  width = img.width
  height = img.height
  sample = img |> hostCall "at" (3, 1, 0)
}
```


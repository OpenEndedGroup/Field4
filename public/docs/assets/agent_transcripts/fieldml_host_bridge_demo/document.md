# Field Document: fieldml_host_bridge_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. FieldML — the JVM bridge

<!-- field-box id="fieldml-bridge-root" index="0" -->

```edn [field-box id="fieldml-bridge-root" role="properties"]
{"id" "fieldml-bridge-root" "index" 0 "name" "FieldML — the JVM bridge" "parents" ["root"] "children" ["fieldml-bridge-raster" "fieldml-bridge-warp" "fieldml-bridge-types" "fieldml-bridge-overloads" "fieldml-bridge-named"] "properties" {"children" #{#afield/box "fieldml-bridge-raster" #afield/box "fieldml-bridge-warp" #afield/box "fieldml-bridge-types" #afield/box "fieldml-bridge-overloads" #afield/box "fieldml-bridge-named"} "parents" #{#afield/box "root"} "name" "FieldML — the JVM bridge" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 2. Host members have real types now

<!-- field-box id="fieldml-bridge-types" index="1" -->

```edn [field-box id="fieldml-bridge-types" role="properties"]
{"id" "fieldml-bridge-types" "index" 1 "name" "Host members have real types now" "parents" ["root" "fieldml-bridge-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-bridge-root"} "name" "Host members have real types now" "_tombstone" false "frame" #afield/rect [400.0 300.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-bridge-types_code_.kt" "lines" 15 "characters" 524 "sha256" "42803f4bc2867ef72861c4e6e143dcdf2b3f24318b517e847ce284c46df87be7"}]}
```

```kotlin [field-property box="fieldml-bridge-types" name="code" path="fieldml-bridge-types_code_.kt"]
let Img = hostClass "afield.stdlib.Img"
let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i / 32.0) 32) Img

// Try it: put the caret after the quote in hostGet "" and ask for
// completion — it offers Img's members with their types. Then misspell
// one (widht) and the box reports a type error before it runs.
{
  width = img.width
  height = img.height
  samples = img.pixelCount
  scalar = img.isScalar
  area = img.width * img.height
  first = img |> hostCall "at" (0, 0, 0)
  boxName = thisBox |> hostGet "name"
}
```

## 3. Named arguments, and Kotlin defaults

<!-- field-box id="fieldml-bridge-named" index="2" -->

### Narrative

From `code`

Named arguments, which are also the only way to leave a Kotlin default alone.

Write a RECORD where a tuple would go. Plain reflection sees neither a
parameter's name nor its default -- `Img.index(x, y, c)` is all it can tell
you -- so this goes through Kotlin's own reflection, and naming the arguments
is what makes the defaults reachable: a positional call has no way to say
which parameter it is skipping.

```edn [field-box id="fieldml-bridge-named" role="properties"]
{"id" "fieldml-bridge-named" "index" 2 "name" "Named arguments, and Kotlin defaults" "parents" ["root" "fieldml-bridge-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-bridge-root"} "name" "Named arguments, and Kotlin defaults" "_tombstone" false "frame" #afield/rect [1120.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-bridge-named_code_.kt" "lines" 34 "characters" 1649 "sha256" "b87d211359e17b87bd09b0900dbfbbade501d1cc1002622be19383260944035f"}]}
```

```kotlin [field-property box="fieldml-bridge-named" name="code" path="fieldml-bridge-named_code_.kt"]
// Named arguments, which are also the only way to leave a Kotlin default alone.
//
// Write a RECORD where a tuple would go. Plain reflection sees neither a
// parameter's name nor its default -- `Img.index(x, y, c)` is all it can tell
// you -- so this goes through Kotlin's own reflection, and naming the arguments
// is what makes the defaults reachable: a positional call has no way to say
// which parameter it is skipping.
let Img = hostClass "afield.stdlib.Img"
let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"

let img = hostNew {width = 2, height = 2, channels = 3, data = [
  0.0, 0.0, 0.0,  0.0, 0.0, 0.0,
  0.0, 0.0, 0.0,  0.0, 0.0, 0.0
]} Img

// `Img.index(x, y, c = 0)`. The first line leaves `c` to Kotlin -- there is no
// positional call that can. The second shows that order is not position.
let defaulted = img |> hostCall "index" {x = 1, y = 0}
let reordered = img |> hostCall "index" {c = 2, y = 0, x = 1}

// An extension, three parameters, all with defaults: positionally you must write
// `("magma", hostNull, hostNull)`, and the two hostNulls are the tell that
// something is missing. Named, `lo` and `hi` really are computed from the image.
let gray = hostNew {width = 2, height = 2, channels = 1, data = [0.0, 0.25, 0.5, 1.0]} Img
let coloured = gray |> hostExt Imaging "colormap" {name = "magma"}

// A name reflection does not know is a compile error where it is written, and it
// names what you gave. Try changing `x` to `xx` above.
{
  defaulted = defaulted
  reordered = reordered
  colouredChannels = coloured |> hostGet "channels"
  note = "a record spreads by name; a tuple still spreads by position"
}
```

## 4. A FieldML function, rasterised by Kotlin

<!-- field-box id="fieldml-bridge-raster" index="3" -->

```edn [field-box id="fieldml-bridge-raster" role="properties"]
{"id" "fieldml-bridge-raster" "index" 3 "name" "A FieldML function, rasterised by Kotlin" "parents" ["root" "fieldml-bridge-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-bridge-root"} "name" "A FieldML function, rasterised by Kotlin" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-bridge-raster_code_.kt" "lines" 18 "characters" 699 "sha256" "ceab6e799164e4642f2e363040527f30267597129eac4696f02efb959944f02e"}]}
```

```kotlin [field-property box="fieldml-bridge-raster" name="code" path="fieldml-bridge-raster_code_.kt"]
let Img = hostClass "afield.stdlib.Img"
let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"

// The whole picture is this function. Change 11.0 to change the ring
// spacing; change "viridis" below to "magma" or "coolwarm".
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

## 5. A FieldML lambda inside FLine.transform

<!-- field-box id="fieldml-bridge-warp" index="4" -->

### Narrative

From `code`

FLine.transform takes a Kotlin (Vector3f) -> Vector3f. The value it
hands back has no static type, so hostAs is where it gets one.
Change 24.0 to change the wave height.

```edn [field-box id="fieldml-bridge-warp" role="properties"]
{"id" "fieldml-bridge-warp" "index" 4 "name" "A FieldML lambda inside FLine.transform" "parents" ["root" "fieldml-bridge-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-bridge-root"} "name" "A FieldML lambda inside FLine.transform" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-bridge-warp_code_.kt" "lines" 18 "characters" 576 "sha256" "00d348d6c9f0578741daace5c2bd5b0d3082f2c68f8fb5ec2105c140bb703010"}]}
```

```kotlin [field-property box="fieldml-bridge-warp" name="code" path="fieldml-bridge-warp_code_.kt"]
// FLine.transform takes a Kotlin (Vector3f) -> Vector3f. The value it
// hands back has no static type, so hostAs is where it gets one.
// Change 24.0 to change the wave height.
let wobble line =
  line |> hostCall1 "transform" (fun raw ->
    let p = hostAs "Vec3" raw in
    vec3 p.x (p.y + 24.0 * sin (p.x / 22.0)) p.z
  )

linspace 0.0 260.0 120
  |> map (fun x -> vec2 x 60.0)
  |> fold (fun line point -> line |> lineToV point) fline
  |> wobble
  |> stroked true
  |> filled false
  |> color (vec4 1.0 0.55 0.2 1.0)
  |> thickness 2.5
  |> drawSized "main" 300.0 160.0
```

## 6. Overloads, varargs, null

<!-- field-box id="fieldml-bridge-overloads" index="5" -->

```edn [field-box id="fieldml-bridge-overloads" role="properties"]
{"id" "fieldml-bridge-overloads" "index" 5 "name" "Overloads, varargs, null" "parents" ["root" "fieldml-bridge-root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "fieldml-bridge-root"} "name" "Overloads, varargs, null" "_tombstone" false "frame" #afield/rect [760.0 170.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fieldml-bridge-overloads_code_.kt" "lines" 17 "characters" 726 "sha256" "c5acdb3ab186048ce6a78a5ea6fe8b12361bf995cee72cf2c8b134bee042fccb"}]}
```

```kotlin [field-property box="fieldml-bridge-overloads" name="code" path="fieldml-bridge-overloads_code_.kt"]
let Math = hostClass "java.lang.Math"
let Strings = hostClass "java.lang.String"
let Collections = hostClass "java.util.Collections"

// Every line here is a call the previous bridge could not make.
{
  // Math.max is declared four times; the argument widths choose one.
  intMax = hostStaticCall "max" (3, 7) Math
  floatMax = hostStaticCall "max" (3.5, 7.25) Math

  // A Java vararg, filled from the spread tuple.
  formatted = hostStaticCall "format" ("%s has %d", "the bridge", 3) Strings

  // A FieldML function as a java.util.Comparator. The result is an erased
  // generic, so it stays Host until hostAsInt says otherwise.
  smallest = hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections |> hostAsInt
}
```


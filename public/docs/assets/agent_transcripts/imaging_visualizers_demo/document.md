# Field Document: imaging_visualizers_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 10 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. signed dx — diverging, zero fixed

<!-- field-box id="viz_signed" index="0" -->

### Narrative

From `code`

sobel().dx is two-signed: positive on a dark->bright edge, negative on bright->dark.
A min..max normalisation puts zero at an arbitrary grey; the diverging policy pins it.

```edn [field-box id="viz_signed" role="properties"]
{"id" "viz_signed" "index" 0 "name" "signed dx — diverging, zero fixed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "signed dx — diverging, zero fixed" "_tombstone" false "frame" #afield/rect [530.0 60.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_signed_code_.kt" "lines" 8 "characters" 380 "sha256" "90f73b4b27954a3335d4afd045ffc55b269ecc07d705ce2a6cfd12db0fb15196"}]}
```

```kotlin [field-property box="viz_signed" name="code" path="viz_signed_code_.kt"]
// sobel().dx is two-signed: positive on a dark->bright edge, negative on bright->dark.
// A min..max normalisation puts zero at an arbitrary grey; the diverging policy pins it.
val src =
    Img.generate(240, 180) { x, y ->
        if (x in 30..90 && y in 26..76) 0.9f
        else if (hypot(x - 172.0, y - 62.0) <= 30.0) 0.7f else 0.15f + 0.1f * (y / 179f)
    }
src.sobel().dx
```

## 2. Gradient — dx, dy, magnitude, direction

<!-- field-box id="viz_gradient" index="1" -->

```edn [field-box id="viz_gradient" role="properties"]
{"id" "viz_gradient" "index" 1 "name" "Gradient — dx, dy, magnitude, direction" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Gradient — dx, dy, magnitude, direction" "_tombstone" false "frame" #afield/rect [530.0 400.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_gradient_code_.kt" "lines" 6 "characters" 199 "sha256" "ce33d3103fe0490a61db65f046ed1f1a2c3f22e086d9e50e1f0e198658a8ff4f"}]}
```

```kotlin [field-property box="viz_gradient" name="code" path="viz_gradient_code_.kt"]
val src =
    Img.generate(240, 180) { x, y ->
        if (x in 30..90 && y in 26..76) 0.9f
        else if (hypot(x - 172.0, y - 62.0) <= 30.0) 0.7f else 0.15f + 0.1f * (y / 179f)
    }
src.sobel()
```

## 3. FlowField — quiver, failures included

<!-- field-box id="viz_flow" index="2" -->

### Narrative

From `code`

The second frame is the first rotated 0.06 rad about its centre, so the arrows should
trace a rotation field: little motion near the middle, tangential motion at the edges.

```edn [field-box id="viz_flow" role="properties"]
{"id" "viz_flow" "index" 2 "name" "FlowField — quiver, failures included" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "FlowField — quiver, failures included" "_tombstone" false "frame" #afield/rect [1000.0 740.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_flow_code_.kt" "lines" 10 "characters" 560 "sha256" "515f6bd9712016152dde2f2ece4a62583145962351f9fbfd4c59f7a0d3c302a1"}]}
```

```kotlin [field-property box="viz_flow" name="code" path="viz_flow_code_.kt"]
// The second frame is the first rotated 0.06 rad about its centre, so the arrows should
// trace a rotation field: little motion near the middle, tangential motion at the edges.
val a =
    Img.generateRGB(240, 180) { x, y ->
            val chk = if (x in 24..200 && y in 20..160 && ((x / 8 + y / 8) % 2 == 0)) 1f else 0f
            Triple(0.15f + 0.7f * chk, 0.18f + 0.5f * chk, 0.30f + 0.4f * chk)
        }
        .luma()
val b = a.rotate(0.06)
opticalFlowLK(a, b, a.goodFeaturesToTrack(maxCount = 80, minDistance = 12).map { it.toVec2() }, levels = 3)
```

## 4. List<Corner> — scatter by strength

<!-- field-box id="viz_corners" index="3" -->

### Narrative

From `code`

The rectangle is x 30..90, y 26..76, so the four strongest corners should read back as
(30, 26), (89, 26), (30, 75), (89, 75) — check the disclosure against those numbers.

```edn [field-box id="viz_corners" role="properties"]
{"id" "viz_corners" "index" 3 "name" "List<Corner> — scatter by strength" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "List<Corner> — scatter by strength" "_tombstone" false "frame" #afield/rect [60.0 740.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_corners_code_.kt" "lines" 7 "characters" 384 "sha256" "510ab0468cccb7b34cd35fb2426d6552e40b75db51f3e1abf1ccc039b7d6783e"}]}
```

```kotlin [field-property box="viz_corners" name="code" path="viz_corners_code_.kt"]
// The rectangle is x 30..90, y 26..76, so the four strongest corners should read back as
// (30, 26), (89, 26), (30, 75), (89, 75) — check the disclosure against those numbers.
val src =
    Img.generate(240, 180) { x, y ->
        if (x in 30..90 && y in 26..76) 0.9f else if (x in 150..152 || y in 140..142) 0.98f else 0.15f
    }
src.harrisCorners(minDistance = 8, maxCount = 40)
```

## 5. GaussianPyramid — rungs at true scale

<!-- field-box id="viz_pyramid" index="4" -->

### Narrative

From `code`

Each rung is drawn at its real fraction of the base resolution, bottom-aligned, so the
halving is the visible fact rather than a number in a caption.

```edn [field-box id="viz_pyramid" role="properties"]
{"id" "viz_pyramid" "index" 4 "name" "GaussianPyramid — rungs at true scale" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "GaussianPyramid — rungs at true scale" "_tombstone" false "frame" #afield/rect [1000.0 400.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_pyramid_code_.kt" "lines" 12 "characters" 613 "sha256" "366f1957e28e444492ca6c054be402229ed78a09e826bb8eebb25f08f753707b"}]}
```

```kotlin [field-property box="viz_pyramid" name="code" path="viz_pyramid_code_.kt"]
// Each rung is drawn at its real fraction of the base resolution, bottom-aligned, so the
// halving is the visible fact rather than a number in a caption.
Img.generateRGB(240, 180) { x, y ->
        val disc = if (hypot(x - 172.0, y - 62.0) <= 30.0) 1f else 0f
        val rect = if (x in 30..90 && y in 26..76) 1f else 0f
        Triple(
            (0.10f + 0.30f * (x / 239f) + 0.8f * rect).coerceIn(0f, 1f),
            (0.12f + 0.22f * (y / 179f) + 0.7f * rect + 0.6f * disc).coerceIn(0f, 1f),
            (0.34f - 0.20f * (x / 239f) + 0.8f * disc).coerceIn(0f, 1f),
        )
    }
    .gaussianPyramid(5)
```

## 6. auto-ranged — the true range printed

<!-- field-box id="viz_autoranged" index="5" -->

### Narrative

From `code`

Edge magnitude x25 lands far outside 0..1. Clamping would show a flat white rectangle;
the visualizer rescales for display and prints the range it rescaled from.

```edn [field-box id="viz_autoranged" role="properties"]
{"id" "viz_autoranged" "index" 5 "name" "auto-ranged — the true range printed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "auto-ranged — the true range printed" "_tombstone" false "frame" #afield/rect [1000.0 60.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_autoranged_code_.kt" "lines" 7 "characters" 354 "sha256" "173a00a11e24d55d52dc00a480a90f1a1b297d175cfcfb8467bb6d7e0fec2b42"}]}
```

```kotlin [field-property box="viz_autoranged" name="code" path="viz_autoranged_code_.kt"]
// Edge magnitude x25 lands far outside 0..1. Clamping would show a flat white rectangle;
// the visualizer rescales for display and prints the range it rescaled from.
val src =
    Img.generate(240, 180) { x, y ->
        if (x in 30..90 && y in 26..76) 0.9f else if (hypot(x - 172.0, y - 62.0) <= 30.0) 0.7f else 0.15f
    }
src.sobelMagnitude() * 25f
```

## 7. Img — thumbnail, channels, histogram

<!-- field-box id="viz_scene" index="6" -->

### Narrative

From `code`

A synthetic scene with something for every detector: a bright rectangle (corners), a
disc (a Hough circle), two rulings (Hough lines), a checker patch (flow texture).
The value of the box IS the image, so the inline visualizer is what you see below.

```edn [field-box id="viz_scene" role="properties"]
{"id" "viz_scene" "index" 6 "name" "Img — thumbnail, channels, histogram" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Img — thumbnail, channels, histogram" "_tombstone" false "frame" #afield/rect [60.0 60.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_scene_code_.kt" "lines" 31 "characters" 868 "sha256" "7f74b68e7c09295b5d3fbb5aeb925fc9c05eb901aea50a33de0d19f99180ca62"}]}
```

```kotlin [field-property box="viz_scene" name="code" path="viz_scene_code_.kt"]
/*
A synthetic scene with something for every detector: a bright rectangle (corners), a
disc (a Hough circle), two rulings (Hough lines), a checker patch (flow texture).
The value of the box IS the image, so the inline visualizer is what you see below.
*/
Img.generateRGB(240, 180) { x, y ->
    var r = 0.10f + 0.30f * (x / 239f)
    var g = 0.12f + 0.22f * (y / 179f)
    var b = 0.34f - 0.20f * (x / 239f)
    if (x in 30..90 && y in 26..76) {
        r = 0.94f
        g = 0.86f
        b = 0.42f
    }
    if (hypot(x - 172.0, y - 62.0) <= 30.0) {
        r = 0.30f
        g = 0.80f
        b = 0.92f
    }
    if (x in 150..152 || y in 140..142) {
        r = 0.98f
        g = 0.98f
        b = 0.98f
    }
    if (x in 24..104 && y in 104..164 && ((x / 8 + y / 8) % 2 == 0)) {
        r = 0.88f
        g = 0.30f
        b = 0.55f
    }
    Triple(r, g, b)
}
```

## 8. ComponentLabelling — one hue per blob

<!-- field-box id="viz_components" index="7" -->

### Narrative

From `code`

Adjacent label ids get unrelated hues (golden-ratio rotation), which is what makes two
touching blobs distinguishable at thumbnail size.

```edn [field-box id="viz_components" role="properties"]
{"id" "viz_components" "index" 7 "name" "ComponentLabelling — one hue per blob" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ComponentLabelling — one hue per blob" "_tombstone" false "frame" #afield/rect [60.0 1080.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_components_code_.kt" "lines" 10 "characters" 436 "sha256" "41a184a9c05700a8f334ab7547cbad3eb77e4a9910111a6164d68c2ebe5054b0"}]}
```

```kotlin [field-property box="viz_components" name="code" path="viz_components_code_.kt"]
// Adjacent label ids get unrelated hues (golden-ratio rotation), which is what makes two
// touching blobs distinguishable at thumbnail size.
Img.generate(240, 180) { x, y ->
        val a = x in 20..70 && y in 20..70
        val b = hypot(x - 150.0, y - 50.0) <= 32.0
        val c = x in 40..90 && y in 110..160
        val d = x in 170..220 && y in 120..150
        if (a || b || c || d) 1f else 0f
    }
    .connectedComponents()
```

## 9. NaN + infinity — counted and magenta

<!-- field-box id="viz_non_finite" index="8" -->

### Narrative

From `code`

Non-finite samples are excluded from the range and the statistics, and painted magenta.
Inspect the magenta patch and isolated infinity beside the finite gradient.

```edn [field-box id="viz_non_finite" role="properties"]
{"id" "viz_non_finite" "index" 8 "name" "NaN + infinity — counted and magenta" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "NaN + infinity — counted and magenta" "_tombstone" false "frame" #afield/rect [60.0 400.0 350.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_non_finite_code_.kt" "lines" 9 "characters" 356 "sha256" "ceed0f314de6255ab70e788a00f09e55bbf31c88432f2414695afdd528a8f6e5"}]}
```

```kotlin [field-property box="viz_non_finite" name="code" path="viz_non_finite_code_.kt"]
// Non-finite samples are excluded from the range and the statistics, and painted magenta.
// Inspect the magenta patch and isolated infinity beside the finite gradient.
Img.generate(160, 120) { x, y ->
    when {
        x in 60..75 && y in 40..55 -> Float.NaN
        x == 120 && y == 30 -> Float.POSITIVE_INFINITY
        else -> (x + y) / 278f
    }
}
```

## 10. List<HoughLine> — geometry + accumulator

<!-- field-box id="viz_hough" index="9" -->

### Narrative

From `code`

Two rulings planted at x=151 and y=141; the peaks should come back as rho~149 theta=0
and rho~139 theta=pi/2. A HoughLine is infinite, so the left panel has to invent a
canvas — it says which one, and points at linesToFLines for the in-place version.

```edn [field-box id="viz_hough" role="properties"]
{"id" "viz_hough" "index" 9 "name" "List<HoughLine> — geometry + accumulator" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "List<HoughLine> — geometry + accumulator" "_tombstone" false "frame" #afield/rect [530.0 740.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "viz_hough_code_.kt" "lines" 10 "characters" 477 "sha256" "28a3cf845d9f380a3938ecd173988883f2697020bcdf2e6aa82bf7db25e7d2f1"}]}
```

```kotlin [field-property box="viz_hough" name="code" path="viz_hough_code_.kt"]
/*
Two rulings planted at x=151 and y=141; the peaks should come back as rho~149 theta=0
and rho~139 theta=pi/2. A HoughLine is infinite, so the left panel has to invent a
canvas — it says which one, and points at linesToFLines for the in-place version.
*/
val src =
    Img.generate(240, 180) { x, y ->
        if (x in 150..152 || y in 140..142) 0.98f else if (x in 30..90 && y in 26..76) 0.9f else 0.12f
    }
houghLines(src.canny(0.10, 0.25), maxLines = 8, threshold = 40)
```


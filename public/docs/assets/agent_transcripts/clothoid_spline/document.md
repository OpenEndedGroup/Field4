# Field Document: clothoid_spline

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - A closed round shape

<!-- field-box id="clothoid-spline-shape" index="0" -->

```edn [field-box id="clothoid-spline-shape" role="properties"]
{"id" "clothoid-spline-shape" "index" 0 "name" "3 - A closed round shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - A closed round shape" "_tombstone" false "frame" #afield/rect [1020.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "clothoid-spline-shape_code_.kt" "lines" 20 "characters" 641 "sha256" "6881872c63f8662aba669c01c4f8b9e7612a75e221acbe84e60052ecb33c4f2e"}]}
```

```kotlin [field-property box="clothoid-spline-shape" name="code" path="clothoid-spline-shape_code_.kt"]
val knots = [vec(60, 80), vec(200, 40), vec(330, 110), vec(300, 240), vec(170, 200), vec(80, 260)]

val shape = clothoidSplineFrom(knots, closed = true).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val fill = shape.copy().apply {
    stroked = false
    filled = true
    color = vec(0.5, 0.8, 1.0, 0.15)
}
val comb = shape.curvatureComb(scale = 1200.0, samples = 200).apply { color = vec(1.0, 1.0, 1.0, 0.35) }
val dots = polylineFrom(knots).apply {
    stroked = false
    pointed = true
    pointSize = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

lines(fill, comb, shape, dots, w = 420.0, h = 320.0, fitToSize = true)
```

## 2. 1 - Clothoid vs cubic, with combs

<!-- field-box id="clothoid-spline-vs-cubic" index="1" -->

```edn [field-box id="clothoid-spline-vs-cubic" role="properties"]
{"id" "clothoid-spline-vs-cubic" "index" 1 "name" "1 - Clothoid vs cubic, with combs" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Clothoid vs cubic, with combs" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "clothoid-spline-vs-cubic_code_.kt" "lines" 32 "characters" 1119 "sha256" "5169463402a8e919386de90203a5df634f4e92e3b715e6e5c2c70dd530a615a4"}]}
```

```kotlin [field-property box="clothoid-spline-vs-cubic" name="code" path="clothoid-spline-vs-cubic_code_.kt"]
val knots = (0..7).map { i -> vec(40 + i * 50, 130 + 70 * kotlin.math.sin(i * 0.9)) }

val cubic = cubicSplineFrom(knots).apply {
    thickness = 3.0
    color = vec(1.0, 0.7, 0.3, 1.0)
}
val clothoid = clothoidSplineFrom(knots).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}

val cubicComb = cubic.curvatureComb(scale = 1500.0).apply { color = vec(1.0, 0.7, 0.3, 0.5) }
val clothoidComb = clothoid.curvatureComb(scale = 1500.0).apply { color = vec(0.5, 0.8, 1.0, 0.5) }

// curvature against distance, cubic above, clothoid below
val cubicPlot = (cubic.curvaturePlot(400.0, 70.0, maxCurvature = 0.05) + vec(20, 240)).apply {
    thickness = 2.0
    color = vec(1.0, 0.7, 0.3, 1.0)
}
val clothoidPlot = (clothoid.curvaturePlot(400.0, 70.0, maxCurvature = 0.05) + vec(20, 320)).apply {
    thickness = 2.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}

val dots = polylineFrom(knots).apply {
    stroked = false
    pointed = true
    pointSize = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

lines(cubicComb, clothoidComb, cubic, clothoid, dots, cubicPlot, clothoidPlot, w = 420.0, h = 400.0, fitToSize = true)
```

## 3. 2 - Points on a circle

<!-- field-box id="clothoid-spline-circle" index="2" -->

```edn [field-box id="clothoid-spline-circle" role="properties"]
{"id" "clothoid-spline-circle" "index" 2 "name" "2 - Points on a circle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Points on a circle" "_tombstone" false "frame" #afield/rect [540.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "clothoid-spline-circle_code_.kt" "lines" 17 "characters" 604 "sha256" "f82082576d7ac113539742206e3e032181c0d05202065cec9e56b92ffb390ec5"}]}
```

```kotlin [field-property box="clothoid-spline-circle" name="code" path="clothoid-spline-circle_code_.kt"]
val knots =
    (0 until 7).map { i ->
        val a = 2 * kotlin.math.PI * i / 7 + 0.3 * kotlin.math.sin(i * 1.7)
        vec(200 + 100 * kotlin.math.cos(a), 150 + 100 * kotlin.math.sin(a))
    }

val round = clothoidSplineFrom(knots, closed = true).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val cubic = cubicSplineFrom(knots, closed = true).apply {
    thickness = 1.5
    color = vec(1.0, 0.7, 0.3, 0.9)
}
val comb = round.curvatureComb(scale = 2000.0, samples = 140).apply { color = vec(0.5, 0.8, 1.0, 0.4) }

lines(comb, cubic, round, w = 420.0, h = 320.0, fitToSize = true)
```


# Field Document: squircles

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Squircles by gauge (slider)

<!-- field-box id="squircle_morph" index="0" -->

```edn [field-box id="squircle_morph" role="properties"]
{"id" "squircle_morph" "index" 0 "name" "1 - Squircles by gauge (slider)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Squircles by gauge (slider)" "_tombstone" false "frame" #afield/rect [60.0 60.0 360.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "squircle_morph_code_.kt" "lines" 41 "characters" 1393 "sha256" "0bf3dda069167fd5d794c11b289074026f8d45446a0a82e7fe508f754882c702"}]}
```

```kotlin [field-property box="squircle_morph" name="code" path="squircle_morph_code_.kt"]
val styles = [
    SquircleStyle.Superellipse,
    SquircleStyle.SuperellipseCubic,
    SquircleStyle.Chromium,
    SquircleStyle.Figma,
    SquircleStyle.Clothoid,
]
val colors = [
    vec(1.0, 1.0, 1.0, 1.0),
    vec(1.0, 0.55, 0.45, 1.0),
    vec(1.0, 0.8, 0.3, 1.0),
    vec(0.7, 0.6, 1.0, 1.0),
    vec(0.5, 0.8, 1.0, 1.0),
]

// every shape, and its curvature round one corner, at one gauge
fun draw(gauge: Double) {
    val drawn = ArrayList<FLine>()
    for ((i, style) in styles.withIndex()) {
        val left = 20.0 + i * 130
        drawn +=
            squircleFrom(vec(left + 55, 70), 50.0, gauge = gauge, style = style).apply {
                thickness = 3.0
                color = colors[i]
            }
        val profile = squircleCurvatureProfile(style, gauge)
        val length = profile.last().x
        drawn +=
            polylineFrom(profile.map { vec(left + it.x / length * 110, 260 - it.y * 15) }).apply {
                thickness = 2.0
                color = colors[i]
            }
        drawn += polylineFrom([vec(left, 260), vec(left + 110, 260)]).apply { color = vec(1.0, 1.0, 1.0, 0.3) }
    }
    lines(drawn, w = 660.0, h = 280.0, fitToSize = true)
    print("gauge ${gauge.f3} = superellipse n ${squircleExponent(gauge).f2}")
}

val start = squircleGauge(3.0)
slider("Gauge", min = 0.708, max = 0.95, initialValue = start) { draw(it) }
draw(start)
```

## 2. 2 - Rounded rectangles with squircle corners

<!-- field-box id="squircle_rect" index="1" -->

```edn [field-box id="squircle_rect" role="properties"]
{"id" "squircle_rect" "index" 1 "name" "2 - Rounded rectangles with squircle corners" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Rounded rectangles with squircle corners" "_tombstone" false "frame" #afield/rect [60.0 260.0 360.0 136.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "squircle_rect_code_.kt" "lines" 16 "characters" 850 "sha256" "9373895d92ed49fe11c6a821ed783697c594f01b5197f9509ece16007125391f"}]}
```

```kotlin [field-property box="squircle_rect" name="code" path="squircle_rect_code_.kt"]
val circular = squircleRectFrom(20.0, 20.0, 380.0, 160.0, radius = 70.0, gauge = Math.sqrt(0.5)).apply {
    thickness = 1.5
    color = vec(1.0, 1.0, 1.0, 0.8)
}
val figma = squircleRectFrom(20.0, 20.0, 380.0, 160.0, radius = 70.0, gauge = 0.79, style = SquircleStyle.Figma).apply {
    thickness = 3.0
    color = vec(0.7, 0.6, 1.0, 1.0)
}
val clothoid = squircleRectFrom(20.0, 20.0, 380.0, 160.0, radius = 70.0, gauge = 0.79, style = SquircleStyle.Clothoid).apply {
    thickness = 3.0
    color = vec(0.5, 0.8, 1.0, 1.0)
}
val figmaComb = figma.curvatureComb(scale = 600.0, samples = 400).apply { color = vec(0.7, 0.6, 1.0, 0.45) }
val clothoidComb = clothoid.curvatureComb(scale = 600.0, samples = 400).apply { color = vec(0.5, 0.8, 1.0, 0.45) }

lines(figmaComb, clothoidComb, circular, figma, clothoid, w = 420.0, h = 200.0, fitToSize = true)
```


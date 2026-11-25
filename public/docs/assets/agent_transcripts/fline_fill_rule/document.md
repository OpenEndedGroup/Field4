# Field Document: fline_fill_rule

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. one fill rule, every renderer

<!-- field-box id="ffr_every_renderer" index="0" -->

```edn [field-box id="ffr_every_renderer" role="properties"]
{"id" "ffr_every_renderer" "index" 0 "name" "one fill rule, every renderer" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "one fill rule, every renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ffr_every_renderer_code_.kt" "lines" 48 "characters" 1883 "sha256" "34b79c44cf324e95c270269e38e5f6ce5b400b865d9a45131ef808132b38ee6b"}]}
```

```kotlin [field-property box="ffr_every_renderer" name="code" path="ffr_every_renderer_code_.kt"]
import afield.graphics.nile.*

// A pentagram crosses itself (winding 2 in its centre); the squares nest, wound the same way.
fun shape(dx: Double, rule: FillRule): FLine {
    val f = FLine()
    for (k in 0..5) {
        val a = Math.PI * (-0.5 + 0.8 * k)
        val x = dx + 45 + 38 * Math.cos(a)
        val y = 50 + 38 * Math.sin(a)
        if (k == 0) f.moveTo(x, y) else f.lineTo(x, y)
    }
    f.close()
    f.moveTo(dx + 100, 15).lineTo(dx + 170, 15).lineTo(dx + 170, 85).lineTo(dx + 100, 85).close()
    f.moveTo(dx + 118, 33).lineTo(dx + 152, 33).lineTo(dx + 152, 67).lineTo(dx + 118, 67).close()
    f.filled = true
    f.stroked = false
    f.color = vec(0.25, 0.55, 0.95, 1.0)
    f.fillRule = rule
    return f
}

val nonZero = shape(0.0, FillRule.NON_ZERO)
val evenOdd = shape(190.0, FillRule.EVEN_ODD)

// Field's own line renderer: the left pair non-zero, the right pair even-odd.
lines([nonZero, evenOdd], w = 380.0, h = 100.0, channel = "field")

// Gezira's winding rasteriser reads the same attribute.
val g = GeziraFLine.render([nonZero, evenOdd], 380, 100)
display("gezira", GeziraSvg.png(g.canvas.rgba, 380, 100))

// The triangles the Vulkan fill is made of, drawn as outlines.
val triangles = FLine()
for (f in [nonZero, evenOdd]) f.tessellate { v, idx ->
    for (t in idx.indices step 3) {
        val (a, b, c) = Triple(idx[t], idx[t + 1], idx[t + 2])
        triangles.moveTo(v[3 * a], v[3 * a + 1]).lineTo(v[3 * b], v[3 * b + 1]).lineTo(v[3 * c], v[3 * c + 1]).close()
    }
}
triangles.color = vec(0.95, 0.6, 0.2, 1.0)
lines([triangles], w = 380.0, h = 100.0, channel = "triangles")

for ((name, f) in ["non-zero" to nonZero, "even-odd" to evenOdd]) {
    val dx = if (f === nonZero) 0f else 190f
    println(
        "$name: contains2D star centre ${f.contains2D(Vec2(dx + 45f, 50f))}, inner square ${f.contains2D(Vec2(dx + 135f, 50f))}",
    )
}
```


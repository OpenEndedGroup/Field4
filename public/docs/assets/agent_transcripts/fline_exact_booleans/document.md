# Field Document: fline_exact_booleans

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Booleans, Area (left) and exact (right)

<!-- field-box id="fline-exact-booleans" index="0" -->

```edn [field-box id="fline-exact-booleans" role="properties"]
{"id" "fline-exact-booleans" "index" 0 "name" "1 - Booleans, Area (left) and exact (right)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Booleans, Area (left) and exact (right)" "_tombstone" false "frame" #afield/rect [60.0 60.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-exact-booleans_code_.kt" "lines" 43 "characters" 1567 "sha256" "7366183272a5e0011d91c601fd85182f723bfb2ae8d3c51e4eb606a005c907d2"}]}
```

```kotlin [field-property box="fline-exact-booleans" name="code" path="fline-exact-booleans_code_.kt"]
fun circle(cx: Double, cy: Double, r: Double) = FLine().circle(cx, cy, r)

fun blob(x: Double, y: Double) = fline {
    moveTo(x, y)
    cubicTo(x + 40, y - 30, x + 90, y + 10, x + 100, y + 50)
    cubicTo(x + 110, y + 90, x + 60, y + 70, x + 50, y + 110)
    cubicTo(x + 40, y + 150, x - 20, y + 120, x - 10, y + 80)
    cubicTo(x, y + 50, x - 30, y + 30, x, y)
    close()
}

// a dot on every node: how many segments each answer used
fun dots(f: FLine, c: Vec4) =
    f.nodes
        .mapNotNull { it.toOrNull }
        .map { p ->
            FLine().circle(p.x, p.y, 2.5).apply {
                filled = true
                stroked = false
                color = c
            }
        }

val out = mutableListOf<FLine>()
for ((row, op) in [FLineBooleanOp.Union, FLineBooleanOp.Subtract].withIndex()) {
    val y = 40.0 + row * 190
    for (col in 0..1) {
        val x = 40.0 + col * 260
        val a = blob(x + 20, y)
        val b = circle(x + 90, y + 60, 45.0)
        val r =
            if (col == 1) a.booleanExact(b, op).line
            else if (op == FLineBooleanOp.Union) a.byUnionWith(b) else a.byDifferenceWith(b)
        r.filled = true
        r.stroked = true
        r.thickness = 1.5
        r.color = if (col == 0) vec(1.0, 0.55, 0.35, 0.35) else vec(0.4, 0.9, 1.0, 0.35)
        out.add(r)
        out.addAll(dots(r, if (col == 0) vec(1.0, 0.4, 0.2, 1.0) else vec(0.3, 0.8, 1.0, 1.0)))
        println("$op ${if (col == 0) "Area" else "exact"}: ${r.segmentCount2D()} segments")
    }
}
lines(out, w = 460.0, h = 380.0, fitToSize = true)
```

## 2. 2 - Offsets, sampled (left) and exact (right)

<!-- field-box id="fline-exact-offsets" index="1" -->

```edn [field-box id="fline-exact-offsets" role="properties"]
{"id" "fline-exact-offsets" "index" 1 "name" "2 - Offsets, sampled (left) and exact (right)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Offsets, sampled (left) and exact (right)" "_tombstone" false "frame" #afield/rect [560.0 60.0 460.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-exact-offsets_code_.kt" "lines" 47 "characters" 1553 "sha256" "53225276e60f5895f99105b560d892dc8bd6e8f6d52127959432f06c17c3e572"}]}
```

```kotlin [field-property box="fline-exact-offsets" name="code" path="fline-exact-offsets_code_.kt"]
fun star(cx: Double, cy: Double) = fline {
    breakNext()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) 70.0 else 32.0
        val a = kotlin.math.PI * i / 5
        lineTo(cx + r * kotlin.math.cos(a), cy + r * kotlin.math.sin(a))
    }
    close()
}

fun sCurve(x: Double, y: Double) = fline {
    moveTo(x, y)
    cubicTo(x + 40, y - 55, x + 70, y + 55, x + 110, y)
    cubicTo(x + 140, y - 35, x + 170, y + 30, x + 200, y + 5)
    lineTo(x + 215, y + 55)
}

fun dots(f: FLine, c: Vec4) =
    f.nodes
        .mapNotNull { it.toOrNull }
        .map { p ->
            FLine().circle(p.x, p.y, 2.0).apply {
                filled = true
                stroked = false
                color = c
            }
        }

val out = mutableListOf<FLine>()
for (col in 0..1) {
    val x = 30.0 + col * 280
    val shapes = [star(x + 110, 100.0), sCurve(x, 270.0)]
    for (s in shapes) {
        s.thickness = 1.0
        s.color = vec(1.0, 1.0, 1.0, 0.6)
        out.add(s)
        for (d in [12.0, -12.0]) {
            val o = if (col == 0) s.byOffsetting(d.toFloat()) else s.byOffsettingExact(d.toFloat())
            o.thickness = 1.5
            o.color = if (col == 0) vec(1.0, 0.55, 0.35, 1.0) else vec(0.4, 0.9, 1.0, 1.0)
            out.add(o)
            out.addAll(dots(o, if (col == 0) vec(1.0, 0.4, 0.2, 1.0) else vec(0.3, 0.8, 1.0, 1.0)))
            println("${if (col == 0) "byOffsetting" else "byOffsettingExact"} d=$d: ${o.segmentCount2D()} segments")
        }
    }
}
lines(out, w = 560.0, h = 360.0, fitToSize = true)
```


# Field Document: fline_measure

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - Crossings, bounds, orientation

<!-- field-box id="fline-measure-cross" index="0" -->

```edn [field-box id="fline-measure-cross" role="properties"]
{"id" "fline-measure-cross" "index" 0 "name" "3 - Crossings, bounds, orientation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Crossings, bounds, orientation" "_tombstone" false "frame" #afield/rect [60.0 520.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-cross_code_.kt" "lines" 29 "characters" 967 "sha256" "350c786f226226504bda46afa513f035bb75047c90273bb3618a1b4c67cf2ec0"}]}
```

```kotlin [field-property box="fline-measure-cross" name="code" path="fline-measure-cross_code_.kt"]
val loop = fline {
    moveTo(60, 200)
    cubicTo(420, 40, 0, 40, 340, 210)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

val ring = fline {
    circle(200, 130, 70)
    thickness = 2.0
    color = vec(0.6, 0.8, 1.0, 1.0)
}

// where the loop crosses the ring, and where it crosses itself
val hits = listOf(loop.crossings2D(ring), loop.selfCrossings2D()).flatten().map { it.point }
val dots = polylineFrom(hits.map { it.v2 }).apply {
    pointed = true
    pointSize = 3.0
    stroked = false
    color = vec(1.0, 0.5, 0.2, 1.0)
}

// bounds that hug the curve, against the control-point bounds
val tight = fline { rect(loop.tightBounds2D()); thickness = 1.0; color = vec(0.4, 1.0, 0.6, 1.0) }
val loose = fline { rect(loop.fast2DBounds()); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.25) }

print("${hits.size} crossings; clockwise on screen: ${ring.isClockwise2D()}")

lines(listOf(loose, tight, ring, loop, dots), w = 420.0, h = 260.0, fitToSize = true)
```

## 2. 2 - Trim and split by distance

<!-- field-box id="fline-measure-trim" index="1" -->

```edn [field-box id="fline-measure-trim" role="properties"]
{"id" "fline-measure-trim" "index" 1 "name" "2 - Trim and split by distance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Trim and split by distance" "_tombstone" false "frame" #afield/rect [520.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-trim_code_.kt" "lines" 23 "characters" 735 "sha256" "02f9c7f059da7d32c0b9b9cd8c6ec4c2152d0e5179d963c4067c33fb08edb3c2"}]}
```

```kotlin [field-property box="fline-measure-trim" name="code" path="fline-measure-trim_code_.kt"]
val curve = fline {
    moveTo(40, 200)
    cubicTo(100, 20, 220, 20, 250, 130)
    lineTo(380, 60)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.35)
}

val total = curve.length()

// from a quarter to 60% of the way, by distance, drawn over the original
val middle = curve.trim(0.25f * total, 0.6f * total).apply {
    thickness = 5.0
    color = vec(0.4, 0.9, 1.0, 1.0)
}

// cut at 100 units: two pieces whose lengths add up to the whole
val (head, tail) = curve.splitAt(100f)
head.apply { thickness = 2.0; color = vec(1.0, 0.6, 0.2, 1.0) }

print("total ${"%.1f".format(total)} = ${"%.1f".format(head.length())} + ${"%.1f".format(tail.length())}")

lines(listOf(curve, head, middle), w = 420.0, h = 260.0, fitToSize = true)
```

## 3. 4 - Fill rules that follow the curve

<!-- field-box id="fline-measure-fill" index="2" -->

### Narrative

From `code`

two circles drawn the same way round: a ring

```edn [field-box id="fline-measure-fill" role="properties"]
{"id" "fline-measure-fill" "index" 2 "name" "4 - Fill rules that follow the curve" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Fill rules that follow the curve" "_tombstone" false "frame" #afield/rect [520.0 520.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-fill_code_.kt" "lines" 23 "characters" 836 "sha256" "d4b5ba669e5a5c611c567c19b77e86f60b05bb16cb0ba904f4c79e21adc88e8e"}]}
```

```kotlin [field-property box="fline-measure-fill" name="code" path="fline-measure-fill_code_.kt"]
// two circles drawn the same way round: a ring
fun ring(dx: Double) = fline {
    circle(110 + dx, 120, 95)
    circle(110 + dx, 120, 40)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

// dots that are inside under each fill rule: non-zero on the left, even-odd on the right
fun insideDots(dx: Double, rule: FLineFillRule) = fline {
    val shape = ring(dx)
    for (i in 0..24) for (j in 0..24) {
        val p = Vec2((15 + dx + i * 8).toFloat(), (25 + j * 8).toFloat())
        if (shape.contains2D(p, rule)) { moveTo(p.x, p.y); lineTo(p.x + 0.01, p.y) }
    }
    pointed = true
    pointSize = 0.7
    stroked = false
    color = vec(1.0, 0.6, 0.2, 1.0)
}

lines(listOf(ring(0.0), ring(240.0), insideDots(0.0, FLineFillRule.NonZero), insideDots(240.0, FLineFillRule.EvenOdd)),
    w = 460.0, h = 240.0, fitToSize = true)
```

## 4. 1 - Locations along a line

<!-- field-box id="fline-measure-locate" index="3" -->

```edn [field-box id="fline-measure-locate" role="properties"]
{"id" "fline-measure-locate" "index" 3 "name" "1 - Locations along a line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Locations along a line" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-locate_code_.kt" "lines" 31 "characters" 951 "sha256" "536dbfde574848aa10e723fe4f009e7309e8a23ba59980cb6f9790fb282b4573"}]}
```

```kotlin [field-property box="fline-measure-locate" name="code" path="fline-measure-locate_code_.kt"]
val curve = fline {
    moveTo(40, 210)
    cubicTo(90, 30, 230, 30, 260, 140)
    cubicTo(280, 220, 360, 230, 390, 90)
    thickness = 2.0
    color = vec(1.0, 1.0, 1.0, 1.0)
}

// thirteen locations, evenly spaced by distance
val stops = (0..12).mapNotNull { curve.locationAtFraction(it / 12f) }

val normals = stops.map { loc ->
    fline {
        moveTo(loc.point.x, loc.point.y)
        lineTo(loc.point.x + loc.normal2D.x * 22, loc.point.y + loc.normal2D.y * 22)
        thickness = 1.5
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
}

// the circle that matches the bend, at three of them
val bends = listOf(stops[3], stops[7], stops[10]).map { loc ->
    val r = 1.0 / loc.signedCurvature2D
    fline {
        circle(loc.point.x + loc.normal2D.x * r, loc.point.y + loc.normal2D.y * r, kotlin.math.abs(r))
        thickness = 1.0
        color = vec(1.0, 0.6, 0.2, 0.8)
    }
}

lines(listOf(curve) + normals + bends, w = 420.0, h = 260.0, fitToSize = true)
```


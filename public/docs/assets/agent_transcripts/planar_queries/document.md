# Field Document: planar_queries

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - Orientation and bounds of point lists

<!-- field-box id="planar-orientation" index="0" -->

```edn [field-box id="planar-orientation" role="properties"]
{"id" "planar-orientation" "index" 0 "name" "2 - Orientation and bounds of point lists" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Orientation and bounds of point lists" "_tombstone" false "frame" #afield/rect [60.0 560.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "planar-orientation_code_.kt" "lines" 28 "characters" 929 "sha256" "bc9ed9da2a41c447d7d62c16c12137325ec58ac7fb5cced51b639f440a3e1921"}]}
```

```kotlin [field-property box="planar-orientation" name="code" path="planar-orientation_code_.kt"]
val polygons = [
    [vec(30, 30), vec(150, 30), vec(150, 120), vec(30, 120)],
    [vec(200, 30), vec(200, 130), vec(330, 80)],
    [vec(40, 170), vec(90, 260), vec(140, 170), vec(90, 200)],
    [vec(330, 250), vec(260, 270), vec(190, 180), vec(300, 160)],
]

val out = mutableListOf<FLine>()
for (points in polygons) {
    val area = signedArea2D(points)
    val bounds = pointBounds2D(points)

    // blue counter-clockwise, orange clockwise
    out +=
        polylineFrom(points + points.first()).apply {
            filled = true
            thickness = 2.0
            color = if (area > 0) vec(0.4, 0.7, 1.0, 0.6) else vec(1.0, 0.55, 0.2, 0.6)
        }
    out += fline {
        rect(bounds.x, bounds.y, bounds.w, bounds.h)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    println("area ${area.f1}, bounds ${bounds.w.f1} x ${bounds.h.f1}")
}

lines(out, w = 420.0, h = 300.0, fitToSize = true)
```

## 2. 1 - Distance to segments, and crossings

<!-- field-box id="planar-distance" index="1" -->

### Narrative

From `code`

segments as (ax, ay, bx, by)

```edn [field-box id="planar-distance" role="properties"]
{"id" "planar-distance" "index" 1 "name" "1 - Distance to segments, and crossings" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Distance to segments, and crossings" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "planar-distance_code_.kt" "lines" 70 "characters" 2469 "sha256" "c87995023d6ff9971a37756ca88a799b38ee5a076b94e2803b3dca6b7990ebdd"}]}
```

```kotlin [field-property box="planar-distance" name="code" path="planar-distance_code_.kt"]
// segments as (ax, ay, bx, by)
val segments = [
    // an X: a proper crossing
    [30.0, 30.0, 150.0, 150.0],
    [30.0, 150.0, 150.0, 30.0],
    // a T: one segment ends on the other
    [200.0, 40.0, 340.0, 40.0],
    [270.0, 40.0, 270.0, 140.0],
    // a V: a shared endpoint
    [200.0, 200.0, 270.0, 260.0],
    [270.0, 260.0, 340.0, 200.0],
    // two collinear pieces that overlap
    [30.0, 230.0, 120.0, 230.0],
    [90.0, 230.0, 160.0, 230.0],
]

val drawn = segments.map { s ->
    fline {
        moveTo(s[0], s[1])
        lineTo(s[2], s[3])
        thickness = 2.0
        color = vec(1.0, 1.0, 1.0, 1.0)
    }
}

// each dot shaded by its distance to the nearest segment
val reach = 40.0
val dots = mutableListOf<FLine>()
for (gx in 0..20) for (gy in 0..14) {
    val x = 10.0 + gx * 19.0
    val y = 10.0 + gy * 19.0
    val d = segments.minOf { s -> pointSegmentDistance2D(x, y, s[0], s[1], s[2], s[3]) }
    val near = 1.0 - (d / reach).coerceIn(0.0, 1.0)
    dots += fline {
        circle(x, y, 1.0 + 4.0 * near)
        filled = true
        stroked = false
        color = vec(0.3 + 0.1 * near, 0.5 + 0.4 * near, 1.0, 0.25 + 0.6 * near)
    }
}

// every pair: a proper crossing, a touch only, or apart
val marks = mutableListOf<FLine>()
var crossing = 0
var touching = 0
for (i in segments.indices) for (j in i + 1 until segments.size) {
    val (a, b) = segments[i] to segments[j]
    val proper = segmentsCrossProperly2D(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])
    val touch = segmentsTouch2D(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])
    if (!touch) continue
    if (proper) crossing++ else touching++
    // a crossing is where the lines meet; a touch is the endpoint nearest the other segment
    val ends = [a[0] to a[1], a[2] to a[3], b[0] to b[1], b[2] to b[3]]
    val (mx, my) =
        if (proper) drawn[i].crossings2D(drawn[j]).first().point.let { it.x.toDouble() to it.y.toDouble() }
        else
            ends.minBy { (x, y) ->
                val other = if (a[0] == x && a[1] == y || a[2] == x && a[3] == y) b else a
                pointSegmentDistance2D(x, y, other[0], other[1], other[2], other[3])
            }
    marks += fline {
        circle(mx, my, 7.0)
        thickness = 2.5
        color = if (proper) vec(1.0, 0.55, 0.2, 1.0) else vec(1.0, 0.9, 0.2, 1.0)
    }
}

print("$crossing proper crossings, $touching touching pairs")

lines(dots + drawn + marks, w = 420.0, h = 300.0, fitToSize = true)
```


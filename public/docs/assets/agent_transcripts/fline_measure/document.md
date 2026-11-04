# Field Document: fline_measure

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 - Crossings, bounds, orientation

<!-- field-box id="fline-measure-cross" index="0" -->

```edn [field-box id="fline-measure-cross" role="properties"]
{"id" "fline-measure-cross" "index" 0 "name" "3 - Crossings, bounds, orientation" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - Crossings, bounds, orientation" "_tombstone" false "frame" #afield/rect [60.0 520.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-cross_code_.kt" "lines" 37 "characters" 979 "sha256" "474db65f62ce864a897bf849077332769752ab37793a625f4be51d82c39f28d2"}]}
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
val hits = [loop.crossings2D(ring), loop.selfCrossings2D()].flatten().map { it.point }
val dots = polylineFrom(hits.map { it.v2 }).apply {
    pointed = true
    pointSize = 3.0
    stroked = false
    color = vec(1.0, 0.5, 0.2, 1.0)
}

// bounds that hug the curve, against the control-point bounds
val tight = fline {
    rect(loop.tightBounds2D())
    thickness = 1.0
    color = vec(0.4, 1.0, 0.6, 1.0)
}
val loose = fline {
    rect(loop.fast2DBounds())
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.25)
}

print("${hits.size} crossings; clockwise on screen: ${ring.isClockwise2D()}")

lines(loose, tight, ring, loop, dots, w = 420.0, h = 260.0, fitToSize = true)
```

## 2. 2 - Trim and split by distance

<!-- field-box id="fline-measure-trim" index="1" -->

```edn [field-box id="fline-measure-trim" role="properties"]
{"id" "fline-measure-trim" "index" 1 "name" "2 - Trim and split by distance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Trim and split by distance" "_tombstone" false "frame" #afield/rect [520.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-trim_code_.kt" "lines" 26 "characters" 698 "sha256" "d18cc2e2b1f3dbbede5a4059ade8db7910b78e0e0e23e9d9188f7a27f6451fdf"}]}
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
head.apply {
    thickness = 2.0
    color = vec(1.0, 0.6, 0.2, 1.0)
}

print("total ${total.f1} = ${head.length().f1} + ${tail.length().f1}")

lines(curve, head, middle, w = 420.0, h = 260.0, fitToSize = true)
```

## 3. 5 - A 3D frame along a helix

<!-- field-box id="fline-measure-helix" index="2" -->

### Narrative

From `code`

a helix of radius 80 rising 30 units per turn, one cubic per quarter turn

```edn [field-box id="fline-measure-helix" role="properties"]
{"id" "fline-measure-helix" "index" 2 "name" "5 - A 3D frame along a helix" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 - A 3D frame along a helix" "_tombstone" false "frame" #afield/rect [60.0 980.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-helix_code_.kt" "lines" 61 "characters" 1943 "sha256" "7e5733e1dbfbe38f1bee76bd6eac8d888214b7a99161396aad336b072e32e022"}]}
```

```kotlin [field-property box="fline-measure-helix" name="code" path="fline-measure-helix_code_.kt"]
// a helix of radius 80 rising 30 units per turn, one cubic per quarter turn
val r = 80.0
val rise = 30.0
val k = 0.5522847 * r
val helix = fline {
    moveTo(r, 0, 0)
    for (q in 0 until 12) {
        val a0 = q * PI / 2
        val a1 = a0 + PI / 2
        val z0 = rise * q / 4
        val z1 = rise * (q + 1) / 4
        cubicTo(
            r * cos(a0) - k * sin(a0),
            r * sin(a0) + k * cos(a0),
            z0 + (z1 - z0) / 3,
            r * cos(a1) + k * sin(a1),
            r * sin(a1) - k * cos(a1),
            z0 + 2 * (z1 - z0) / 3,
            r * cos(a1),
            r * sin(a1),
            z1,
        )
    }
}

// look at it from the side and a little above: x across, z up, y into the page
fun view(p: Vec3) = vec(p.x + 0.35 * p.y, -p.z - 0.35 * p.y)

val projected = helix.sampleByNumber(400).map(::view)
val drawn = fline {
    data("m l+", projected)
    thickness = 1.5
    color = vec(1.0, 1.0, 1.0, 1.0)
}

// the 3D frame at 16 evenly spaced places: tangent orange, normal blue, binormal green
fun tick(p: Vec3, d: Vec3?, c: Vec4) = fline {
    if (d != null) {
        moveTo(view(p))
        lineTo(view(p + d * 18.0))
    }
    thickness = 2.0
    color = c
}
val frames =
    (0..15).flatMap { i ->
        val loc = helix.locationAtFraction(i / 15f)!!
        [
            tick(loc.point, loc.tangent, vec(1.0, 0.6, 0.2, 1.0)),
            tick(loc.point, loc.normal, vec(0.4, 0.8, 1.0, 1.0)),
            tick(loc.point, loc.binormal, vec(0.5, 1.0, 0.5, 1.0)),
        ]
    }

// a helix has constant curvature r / (r^2 + c^2), with c the rise per radian
val c = rise / (2 * PI)
val mid = helix.locationAtFraction(0.5f)!!
println("curvature %.5f, a true helix %.5f".format(mid.curvature, r / (r * r + c * c)))
println("normal points at the axis: %.3f".format(mid.normal!!.dot(vec(-mid.point.x, -mid.point.y, 0.0).normalize())))

lines([drawn] + frames, w = 420.0, h = 260.0, fitToSize = true)
```

## 4. 4 - Fill rules that follow the curve

<!-- field-box id="fline-measure-fill" index="3" -->

### Narrative

From `code`

two circles drawn the same way round: a ring

```edn [field-box id="fline-measure-fill" role="properties"]
{"id" "fline-measure-fill" "index" 3 "name" "4 - Fill rules that follow the curve" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 - Fill rules that follow the curve" "_tombstone" false "frame" #afield/rect [520.0 520.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-fill_code_.kt" "lines" 33 "characters" 888 "sha256" "73a9d01602ffef55613bb272b364aa38e412104b123c57237b6dc6f07636d7aa"}]}
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
        if (shape.contains2D(p, rule)) {
            moveTo(p.x, p.y)
            lineTo(p.x + 0.01, p.y)
        }
    }
    pointed = true
    pointSize = 0.7
    stroked = false
    color = vec(1.0, 0.6, 0.2, 1.0)
}

lines(
    ring(0.0),
    ring(240.0),
    insideDots(0.0, FLineFillRule.NonZero),
    insideDots(240.0, FLineFillRule.EvenOdd),
    w = 460.0,
    h = 240.0,
    fitToSize = true,
)
```

## 5. 1 - Locations along a line

<!-- field-box id="fline-measure-locate" index="4" -->

```edn [field-box id="fline-measure-locate" role="properties"]
{"id" "fline-measure-locate" "index" 4 "name" "1 - Locations along a line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Locations along a line" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "fline-measure-locate_code_.kt" "lines" 32 "characters" 987 "sha256" "fc713c531d9270fc28367cbbfd8ebad8ea9a7d849fce1affec4cbb78a21c7f99"}]}
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
val bends =
    [stops[3], stops[7], stops[10]].map { loc ->
        val r = 1.0 / loc.signedCurvature2D
        fline {
            circle(loc.point.x + loc.normal2D.x * r, loc.point.y + loc.normal2D.y * r, kotlin.math.abs(r))
            thickness = 1.0
            color = vec(1.0, 0.6, 0.2, 0.8)
        }
    }

lines([curve] + normals + bends, w = 420.0, h = 260.0, fitToSize = true)
```


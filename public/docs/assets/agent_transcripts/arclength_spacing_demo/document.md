# Field Document: arclength_spacing_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. where the mechanism actually is

<!-- field-box id="arclength_ticks" index="0" -->

### Narrative

From `code`

Same two curves, ticked instead of dashed, so you can see the spacing itself rather
than its effect on a dash.

```edn [field-box id="arclength_ticks" role="properties"]
{"id" "arclength_ticks" "index" 0 "name" "where the mechanism actually is" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "where the mechanism actually is" "_tombstone" false "frame" #afield/rect [80.0 720.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "arclength_ticks_code_.kt" "lines" 96 "characters" 3910 "sha256" "4e182240b5102ea9a2146bac1f0bede14bade71600189d9bcc7ff1a2278037bf"}]}
```

```kotlin [field-property box="arclength_ticks" name="code" path="arclength_ticks_code_.kt"]
// Same two curves, ticked instead of dashed, so you can see the spacing itself rather
// than its effect on a dash.
val bent = fline {
    moveTo(20, 40)
    cubicTo(90, 200, 330, -80, 400, 90)
}

val straight = fline {
    moveTo(20, 250)
    cubicTo(340, 250, 372, 250, 400, 250)
}

val steps = 24

val ticks = { line: FLine, byArcLength: Boolean ->
    val c = line.cursor()
    val total = c.lengthD()
    val span = (line.nodes.size - 1).toFloat()
    val out = mutableListOf<FLine>()
    for (i in 0..steps) {
        val f = i.toFloat() / steps
        if (byArcLength) c.setD(total * f) else c.setT(span * f)
        val p = c.position()
        // normal2() is the tangent rotated 90 degrees and is NOT normalised — its
        // magnitude is |B'(t)|, which is in the hundreds here — so normalise it or the
        // ticks come out as a fan hundreds of units long.
        val n = c.normal2()
        val len = kotlin.math.hypot(n?.x ?: 0f, n?.y ?: 1f).coerceAtLeast(1e-6f)
        val nx = (n?.x ?: 0f) / len
        val ny = (n?.y ?: 1f) / len
        out.add(
            FLine()
                .moveTo(vec(p.x - nx * 8f, p.y - ny * 8f, p.z))
                .lineTo(vec(p.x + nx * 8f, p.y + ny * 8f, p.z))
                .apply {
                    thickness = 2.0
                    color = if (byArcLength) vec(0.35, 0.95, 0.6, 1.0) else vec(1.0, 0.45, 0.35, 1.0)
                }
        )
    }
    out + line.apply { thickness = 1.0; color = vec(0.55, 0.55, 0.65, 1.0) }
}

// The gap between consecutive tick positions, as a max/median ratio. 1.0 is perfect.
val evenness = { line: FLine, byArcLength: Boolean ->
    val c = line.cursor()
    val total = c.lengthD()
    val span = (line.nodes.size - 1).toFloat()
    val pts = (0..steps).map { i ->
        val f = i.toFloat() / steps
        if (byArcLength) c.setD(total * f) else c.setT(span * f)
        c.position()
    }
    val gaps = (1..steps).map { pts[it].distance(pts[it - 1]).toDouble() }.sorted()
    gaps.last() / gaps[gaps.size / 2]
}

showPlaneGrid(
    "S-curve, ticked by parameter\nmax/median gap %.3f".format(evenness(bent, false)) to ticks(bent, false),
    "S-curve, ticked by arc length\nmax/median gap %.3f".format(evenness(bent, true)) to ticks(bent, true),
    "collinear, ticked by parameter\nmax/median gap %.3f".format(evenness(straight, false)) to ticks(straight, false),
    "collinear, ticked by arc length\nmax/median gap %.3f".format(evenness(straight, true)) to ticks(straight, true),
    columns = 2,
    cellWidth = 420,
    cellHeight = 220,
)

// The mechanism, live. `flatness` is the OLD subdivision criterion: the distance of the
// control points from the chord. `skew` is the term added on 2026-07-31: the distance
// between the curve's mid-PARAMETER point B(0.5) and the chord's mid-POINT. Neither
// subsumes the other, which is why the criterion takes the larger of the two.
val report = { name: String, line: FLine ->
    val n = line.nodes[1] as FLine.CubicTo
    val a = line.nodes[0].to
    val b = n.to
    val ptSeg = { p: Vec3 ->
        field.graphics.FLinesAndJavaShapes.ptSegDistSq3(a.x, a.y, a.z, b.x, b.y, b.z, p.x, p.y, p.z)
    }
    val flatness = kotlin.math.sqrt(maxOf(ptSeg(n.c1), ptSeg(n.c2)))
    val mid = vec(
        0.125 * (a.x + 3 * n.c1.x + 3 * n.c2.x + b.x),
        0.125 * (a.y + 3 * n.c1.y + 3 * n.c2.y + b.y),
        0.125 * (a.z + 3 * n.c1.z + 3 * n.c2.z + b.z),
    )
    val chordMid = vec(0.5 * (a.x + b.x), 0.5 * (a.y + b.y), 0.5 * (a.z + b.z))
    val skew = mid.distance(chordMid)
    val leaves = afield.graphics.PathFlattener(line, 0.01f).mappings.size
    "%-22s flatness %8.3f   skew %8.3f   leaves %5d".format(name, flatness, skew, leaves)
}

listOf(
    report("S-curve", bent),
    report("collinear", straight),
    "",
    "flatness alone stops at one leaf whenever it reads 0.000, and no tolerance can beat 0.",
).joinToString("\n")
```

## 2. dashes, spaced by parameter and by arc length

<!-- field-box id="arclength_dashes" index="1" -->

### Narrative

From `code`

Two curves. `bent` is an ordinary S-shaped cubic — its control points are far off the
chord, so the old flatness criterion always subdivided it and its spacing was already
fine. `straight` has its control points exactly ON the chord but bunched at the far
end: geometrically a straight line, parameterised so that t = 0.5 is 82% of the way
along. Its flatness is exactly 0.0, so no tolerance could ever force a subdivision.

```edn [field-box id="arclength_dashes" role="properties"]
{"id" "arclength_dashes" "index" 1 "name" "dashes, spaced by parameter and by arc length" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "dashes, spaced by parameter and by arc length" "_tombstone" false "frame" #afield/rect [80.0 80.0 410.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "arclength_dashes_code_.kt" "lines" 76 "characters" 2744 "sha256" "09f355be91be728410e3c7fa896877934cf5093278b86d09ece7acc6635da634"}]}
```

```kotlin [field-property box="arclength_dashes" name="code" path="arclength_dashes_code_.kt"]
// Two curves. `bent` is an ordinary S-shaped cubic — its control points are far off the
// chord, so the old flatness criterion always subdivided it and its spacing was already
// fine. `straight` has its control points exactly ON the chord but bunched at the far
// end: geometrically a straight line, parameterised so that t = 0.5 is 82% of the way
// along. Its flatness is exactly 0.0, so no tolerance could ever force a subdivision.
val bent = fline {
    moveTo(20, 40)
    cubicTo(90, 200, 330, -80, 400, 90)
}

val straight = fline {
    moveTo(20, 250)
    cubicTo(340, 250, 372, 250, 400, 250)
}

val dash = 22f
val gap = 13f

// What a one-leaf mapping produced: t marched in equal steps, each dash a straight chord
// between its two ends. Reproduced explicitly rather than remembered, so the comparison
// is computed here and now on both sides.
val byParameter = { line: FLine ->
    val c = line.cursor()
    val span = (line.nodes.size - 1).toFloat()
    val total = c.lengthD()
    val out = mutableListOf<FLine>()
    var d = 0f
    while (d < total) {
        c.setT(span * d / total)
        val a = c.position()
        c.setT(span * minOf(d + dash, total) / total)
        val b = c.position()
        out.add(FLine().moveTo(a).lineTo(b))
        d += dash + gap
    }
    out
}

// The drawn length of one dash, following whatever nodes it actually has.
val drawnLength = { d: FLine ->
    (1 until d.nodes.size).sumOf { d.nodes[it].to.distance(d.nodes[it - 1].to).toDouble() }
}

val paint = { dashes: List<FLine> ->
    dashes.mapIndexed { i, d ->
        d.apply {
            thickness = 3.0
            color = if (i % 2 == 0) vec(1.0, 0.65, 0.2, 1.0) else vec(0.35, 0.95, 0.6, 1.0)
        }
    }
}

// The interior dashes are the ones that should all be `dash` long; the last one is
// whatever length is left over at the end of the curve.
val spread = { dashes: List<FLine> ->
    val lengths = dashes.dropLast(1).map(drawnLength).filter { it > 0.0 }
    if (lengths.isEmpty()) "no dashes"
    else "asked $dash, drew %.1f .. %.1f".format(lengths.min(), lengths.max())
}

val bentOld = paint(byParameter(bent))
val bentNew = paint(bent.byDashing(dash, gap))
val straightOld = paint(byParameter(straight))
val straightNew = paint(straight.byDashing(dash, gap))

showPlaneGrid(
    "S-curve, spaced by parameter\n${spread(bentOld)}" to bentOld,
    "S-curve, spaced by arc length\n${spread(bentNew)}" to bentNew,
    "collinear control points, by parameter\n${spread(straightOld)}" to straightOld,
    "collinear control points, by arc length\n${spread(straightNew)}" to straightNew,
    columns = 2,
    cellWidth = 420,
    cellHeight = 220,
)

"left column = the old spacing, right column = today's byDashing"
```


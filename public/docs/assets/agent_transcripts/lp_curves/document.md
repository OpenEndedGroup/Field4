# Field Document: lp_curves

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. a turning objective (launch me)

<!-- field-box id="lp_turning" index="0" -->

### Narrative

From `code`

The objective turns once every `period` seconds while the hexagon breathes. Every route is
recomputed for each picture; they are cheap.

```edn [field-box id="lp_turning" role="properties"]
{"id" "lp_turning" "index" 0 "name" "a turning objective (launch me)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "a turning objective (launch me)" "_tombstone" false "frame" #afield/rect [80.0 560.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_turning_code_.kt" "lines" 62 "characters" 2079 "sha256" "5cff722b025dd2e89e5427511012a76d3eab5144d9d7c35ec8c1d23ae64774a5"}]}
```

```kotlin [field-property box="lp_turning" name="code" path="lp_turning_code_.kt"]
// The objective turns once every `period` seconds while the hexagon breathes. Every route is
// recomputed for each picture; they are cheap.
val period = 12.0
val tau = 0.3
val weights = doubleArrayOf(1.0, 1.0, 3.0, 1.0, 1.0, 1.0)
val window = Rect(-3.6, -3.4, 7.2, 6.8)

// Each vertex's radius wanders between 1.9 and 2.9, which keeps the hexagon convex.
val shape = { t: Double ->
    LpPolygon(
        (0 until 6).map { k ->
            val a = 2.0 * PI * k / 6 + 0.25
            val r = 2.4 + 0.5 * sin(t * 0.9 + k * 1.7)
            vec(r * cos(a), r * sin(a))
        },
    )
}

// Each frame's central path starts Newton from the previous frame's: same path, fewer steps.
val continuation = LpContinuation()
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0
val draw: (Double) -> Unit = { t ->
    val polygon = shape(t)
    val theta = 2.0 * PI * t / period
    val c = vec(cos(theta), sin(theta))
    val central = continuation.centralPath(polygon, c, weights, samples = 90)
    val spiral = polygon.pdhg(c, iterations = 300, tau = tau, sigma = tau)
    val walk = polygon.simplexWalk(c)
    val o = polygon.center
    val arrow = fline {
        moveTo(o.x, o.y)
        lineTo(o.x + 0.9 * c.x, o.y + 0.9 * c.y)
        color = vec(1.0, 1.0, 1.0, 0.5)
        thickness = 1.0
    }
    showPlane(
        [
            polygon.toFLine(),
            arrow,
            spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.7), 0.9),
            walk.toFLine(vec(0.6, 0.75, 1.0, 0.95), 2.4),
            central.toFLine(vec(1.0, 0.72, 0.32, 1.0), 2.0),
        ],
        title = "θ = %3.0f°".format(Math.toDegrees(theta) % 360.0),
        caption = "orange: central path · pink: PDHG · blue: simplex walk",
        width = 520,
        height = 500,
        window = window,
        interactive = false,
        yUp = true,
    )
}

animate {
    val t = AnimationClock.nowSeconds() - started
    // Draw twenty pictures a second; the loop itself runs much faster than that.
    if (t >= nextDraw) {
        draw(t)
        nextDraw = t + 1.0 / 20.0
    }
}
```

## 2. every route to the same vertex

<!-- field-box id="lp_routes" index="1" -->

```edn [field-box id="lp_routes" role="properties"]
{"id" "lp_routes" "index" 1 "name" "every route to the same vertex" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "every route to the same vertex" "_tombstone" false "frame" #afield/rect [80.0 80.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_routes_code_.kt" "lines" 42 "characters" 2320 "sha256" "4fb0947e8d9be5ee5985b7a3f076da25d04b964bdbdee8fd55531aa074bebd01"}]}
```

```kotlin [field-property box="lp_routes" name="code" path="lp_routes_code_.kt"]
val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(1.0, 0.4)
val weights = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)

val central = polygon.centralPath(c, weights)
val fan = polygon.centralFan(angles = 48, samples = 80, weights = weights)
val pd = polygon.primalDual(c)
val spiral = polygon.pdhg(c, iterations = 600, tau = 0.3, sigma = 0.3)
val ellipsoid = polygon.ellipsoid(c, iterations = 30)
val walk = polygon.simplexWalk(c, startVertex = 4)
val cuts = polygon.cuttingPlanes(c, iterations = 12)

val outline = polygon.toFLine()
val answer = pointCloudFLine([polygon.optimalVertex(c)], vec(1.0, 0.4, 0.4, 1.0), 2.0)
val dots = { t: LpTrajectory -> pointCloudFLine(t.positions, vec(1.0, 1.0, 1.0, 0.9), 1.0) }

showPlaneGrid(
    "centralPath\nμ %.0e → %.0e, ${central.samples.size} samples"
        .format(central.samples.first().scale, central.last.scale) to [outline, central.toFLine(), answer],
    "centralFan\n${fan.size} directions" to [outline] + fan.toFLines(),
    "primalDual\n${pd.samples.size - 1} Newton steps" to
        [outline, pd.toFLine(vec(0.55, 0.9, 0.6, 0.95)), dots(pd), answer],
    "pdhg, tau = sigma = 0.3\n${spiral.trajectory.samples.count { it.violation > 0.0 }} of ${spiral.trajectory.samples.size} iterates outside" to
        [outline, spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.85), 1.0), answer],
    "ellipsoid\n${ellipsoid.ellipses.size} ellipses, each ${(kotlin.math.sqrt(ellipsoid.ellipses[1].determinant / ellipsoid.ellipses[0].determinant)).f2} of the last's area" to
        [outline] +
            ellipsoid.ellipses.take(16).map { it.toFLine() } +
            ellipsoid.trajectory.toFLine(vec(1.0, 1.0, 1.0, 0.6), 0.8),
    "simplexWalk\n${walk.samples.size - 1} pivots" to
        [outline, walk.toFLine(vec(0.6, 0.75, 1.0, 0.95), 2.4), dots(walk), answer],
    "cuttingPlanes (analytic centres)\narea %.2f → %.1e"
        .format(cuts.trajectory.samples.first().scale, cuts.trajectory.last.scale) to
        [outline] +
            cuts.regions.map { r -> polylineFrom(r, closed = true).also { it.color = vec(0.6, 0.8, 1.0, 0.4) } } +
            cuts.trajectory.toFLine(),
    columns = 4,
    yUp = true,
    cellWidth = 260,
    cellHeight = 230,
)

"every route ends at ${polygon.optimalVertex(c)}"
```

## 3. drag the polygon, redraw the fan map

<!-- field-box id="lp_fan_map" index="2" -->

### Narrative

From `code`

Choose the pencil tool and drag a corner of the white outline. When the drag ends the
grid is recomputed for the convex hull of the edited outline.

```edn [field-box id="lp_fan_map" role="properties"]
{"id" "lp_fan_map" "index" 2 "name" "drag the polygon, redraw the fan map" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "drag the polygon, redraw the fan map" "_tombstone" false "frame" #afield/rect [560.0 80.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "lp_fan_map_code_.kt" "lines" 20 "characters" 806 "sha256" "e3cf55bf1d4c143abb7b10a06afac0970021cbe868a000c557751e6eed613324"}]}
```

```kotlin [field-property box="lp_fan_map" name="code" path="lp_fan_map_code_.kt"]
// Choose the pencil tool and drag a corner of the white outline. When the drag ends the
// grid is recomputed for the convex hull of the edited outline.
fun redraw(polygon: LpPolygon) {
    val grid = polygon.fanMap().grid(thetas = 72, logMus = 30)
    val outline = polylineFrom(polygon.vertices, closed = true).also {
        it.color = vec(1.0, 1.0, 1.0, 0.95)
        it.thickness = 2.0
    }
    threeLines(
        [outline] + grid.thetaLines(every = 2, alpha = 0.7) + grid.muLines(every = 3),
        channel = "main",
        name = "fan-map",
        height = 520,
        width = 620,
        editable = true,
        onEditCommitted = { edited -> redraw(LpPolygon.hullOf(edited[0])) },
    )
}

redraw(LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)]))
```


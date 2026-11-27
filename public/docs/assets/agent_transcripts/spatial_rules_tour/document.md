# Field Document: spatial_rules_tour

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 · Loom with memory

<!-- field-box id="sr_loom" index="0" -->

```edn [field-box id="sr_loom" role="properties"]
{"id" "sr_loom" "index" 0 "name" "2 · Loom with memory" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · Loom with memory" "_tombstone" false "frame" #afield/rect [650.0 285.0 290.0 78.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sr_loom_code_.kt" "lines" 256 "characters" 10275 "sha256" "665b0e2692ce16b51ec05c8106d043e47712e93a8171b24448c8f7aa3f8542cf"}]}
```

```kotlin [field-property box="sr_loom" name="code" path="sr_loom_code_.kt"]
import afield.data.graph.SpatialLifetime.MATCH
import afield.data.graph.inspectOnClick
import java.util.concurrent.atomic.AtomicLong

val world = SpatialRuleWorld()
val stitchInk = vec(0.27, 0.77, 0.93, 0.74)
val weaveInk = vec(1.0, 0.70, 0.29, 0.82)
val echoInk = vec(0.80, 0.53, 0.97, 0.73)
val knotInk = vec(1.0, 0.90, 0.61, 1.0)
val treeInk = vec(0.73, 0.88, 0.99, 0.8)
val inspected = AtomicLong(-1L)
val stations = 3
val tipsPerRow = 7
val departureFade = 1.3

fun midpoint(a: Vec3, b: Vec3) = Vec3(a).add(b).mul(0.5f)
fun rise(age: Double, duration: Double) = (age / duration).coerceIn(0.0, 1.0)
fun project(p: Vec3) = vec(p.x + 0.22 * p.z, p.y - 0.35 * p.z, 0)

fun bow(a: Vec3, b: Vec3, offset: Double, ink: Vec4, width: Double, opacity: Double): FLine {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
    val n = vec(-dy * offset / length, dx * offset / length, 0)
    val c1 = vec(a.x + dx / 3, a.y + dy / 3, 0).add(n)
    val c2 = vec(b.x - dx / 3, b.y - dy / 3, 0).add(n)
    return FLine().moveTo(a).cubicTo(c1, c2, b).apply {
        color = vec(ink.x, ink.y, ink.z, ink.w * opacity)
        thickness = width
    }
}

// Bow the centreline, then give it a width that tapers to zero at both ends.
fun ribbon(a: Vec3, b: Vec3, width: Double, opacity: Double): FLine =
    bow(a, b, 17.0, treeInk, 1.0, opacity)
        .byVariableRibboning(parameterFromLambda { t, _ -> (2.0 * width * sin(PI * t)).toFloat() }, samples = 24)
        .apply {
            color = vec(treeInk.x, treeInk.y, treeInk.z, treeInk.w * opacity)
            filled = true
            stroked = false
        }

world.source("shuttles") {
    val start = now
    val rows = Array(stations) { Array(2) { arrayOfNulls<SpatialPoint>(tipsPerRow) } }
    while (true) {
        val t = now - start
        for (station in 0 until stations) for (row in 0..1) {
            val side = if (row == 0) -1 else 1
            for (i in 0 until tipsPerRow) {
                val phase = t * 1.1 + i * 0.56 + station * 0.8
                val cycle = (t + station * 2.5 + i * 0.19 + row * 0.8) % 9.5
                val present = cycle < if (row == 0) 8.3 else 6.3
                val p = vec(
                    150 + station * 260 + (i - 3) * 24 + 20 * sin(phase + row),
                    220 + 38 * sin(t * 0.6 + station * 1.4) + side * (36 + 23 * cos(phase * 0.7)) + 28 * sin(phase),
                    24 * cos(phase + row * 1.7),
                )
                val tip = rows[station][row][i]
                if (present) {
                    if (tip == null) {
                        rows[station][row][i] = point("tip", p, mapOf("station" to station, "row" to row))
                    } else {
                        move(tip, p)
                        draw(spatialDot(project(tip.pos), 2.6, if (row == 0) stitchInk else echoInk))
                    }
                } else if (tip != null) {
                    retract(tip)
                    rows[station][row][i] = null
                }
            }
        }
        nextFrame()
    }
}

world.rules("loom") {
    val tips = points("tip")
    val knots = points("knot")
    val opposing =
        tips.pairsWithin(80.0, leave = 101.0).where { (a, b) ->
            a["station"] == b["station"] && a["row"] != b["row"]
        }

    onEnter(
        "bow a stitch",
        opposing,
        condition = "Same station, opposite rows, both live and within 101 units (80 to join)",
    ) { (a, b) ->
        val strand = edge("stitch", a, b, lifetime = MATCH)
        val knot = point("knot", midpoint(a.pos, b.pos), mapOf("station" to (a["station"] as Int)), MATCH)
        val born = now
        var start = project(a.pos)
        var end = project(b.pos)
        var bend = 0.0
        var width = 1.0
        while (match.live) {
            val length = a.pos.distance(b.pos).toDouble()
            start = project(a.pos)
            end = project(b.pos)
            bend = 12 + 10 * sin((now - born) * 4 + a.id)
            width = 0.65 + 2.45 * ((length - 30) / 71).coerceIn(0.0, 1.0)
            val middle = midpoint(a.pos, b.pos)
            val dx = end.x - start.x
            val dy = end.y - start.y
            val projectedLength = hypot(dx.toDouble(), dy.toDouble()).coerceAtLeast(1.0)
            middle.add(vec(-dy * bend * 0.75 / projectedLength, dx * bend * 0.75 / projectedLength, 0))
            move(knot, middle)
            set(strand, "length", length)
            draw(bow(start, end, bend, stitchInk, width, rise(now - born, 0.42)))
            nextFrame()
        }
        val released = now
        while (now - released < departureFade) {
            val opacity = 1 - rise(now - released, departureFade)
            draw(
                bow(start, end, bend + 25 * (1 - opacity), stitchInk, width * (0.4 + 0.6 * opacity), opacity * opacity),
            )
            nextFrame()
        }
    }

    val neighbors = knots.pairsWithin(29.0, leave = 37.0).where { (a, b) -> a["station"] == b["station"] }
    onEnter("weave neighboring knots", neighbors) { (a, b) ->
        edge("weave", a, b, lifetime = MATCH)
        val born = now
        var ends = [project(a.pos), project(b.pos)]
        while (match.live) {
            ends = [project(a.pos), project(b.pos)]
            draw(spatialStroke(ends, weaveInk, width = 0.9, opacity = rise(now - born, 0.35)))
            nextFrame()
        }
        val released = now
        while (now - released < 0.48) {
            draw(spatialStroke(ends, weaveInk, width = 0.9, opacity = 1 - rise(now - released, 0.48)))
            nextFrame()
        }
    }

    val taut = edges("stitch").where { ((it["length"] as? Number)?.toDouble() ?: 0.0) > 73 }
    onEnter("echo a taut stitch", taut, condition = "Stitch length exceeds 73 units") { stitch ->
        edge("echo", stitch.from, stitch.to, lifetime = MATCH)
        val born = now
        var a = project(stitch.from.pos)
        var b = project(stitch.to.pos)
        var bend = -24.0
        while (match.live) {
            a = project(stitch.from.pos)
            b = project(stitch.to.pos)
            bend = -25 - 9 * sin(4 * (now - born))
            draw(bow(a, b, bend, echoInk, 1.1, rise(now - born, 0.32)))
            nextFrame()
        }
        val released = now
        while (now - released < 0.75) {
            val tail = rise(now - released, 0.75)
            draw(bow(a, b, bend - 16 * tail, echoInk, 1.1, 1 - tail))
            nextFrame()
        }
    }
}

// Fit an ellipse to the projected second moments of each station's committed knot cloud.
class Fit(val centre: Vec3, val angle: Double, val major: Double, val minor: Double)

fun fit(points: List<Vec3>): Fit {
    val cx = points.sumOf { it.x.toDouble() } / points.size
    val cy = points.sumOf { it.y.toDouble() } / points.size
    val xx = points.sumOf { (it.x - cx) * (it.x - cx) } / points.size
    val yy = points.sumOf { (it.y - cy) * (it.y - cy) } / points.size
    val xy = points.sumOf { (it.x - cx) * (it.y - cy) } / points.size
    val spread = hypot(xx - yy, 2 * xy)
    return Fit(
        vec(cx, cy, 0),
        0.5 * atan2(2 * xy, xx - yy),
        2 * sqrt(((xx + yy + spread) / 2).coerceAtLeast(0.0)) + 13,
        2 * sqrt(((xx + yy - spread) / 2).coerceAtLeast(0.0)) + 10,
    )
}

fun outline(fit: Fit, opacity: Double): FLine {
    val (ux, uy) = cos(fit.angle) to sin(fit.angle)
    val rim =
        List(65) { i ->
            val t = 2 * PI * i / 64
            val x = fit.major * cos(t)
            val y = fit.minor * sin(t)
            vec(fit.centre.x + ux * x - uy * y, fit.centre.y + uy * x + ux * y, 0)
        }
    return spatialStroke(rim, treeInk, width = 0.9, opacity = opacity)
}

world.source("fit and connect") {
    var previousTime = now
    val visible = DoubleArray(stations)
    val fits = arrayOfNulls<Fit>(stations)
    while (true) {
        val dt = (now - previousTime).coerceAtMost(0.10)
        previousTime = now
        val groups = world.points("knot").groupBy { it["station"] as Int }
        for (station in 0 until stations) {
            val cloud = groups[station].orEmpty()
            if (cloud.size >= 4) fits[station] = fit(cloud.map { project(it.pos) })
            visible[station] = (visible[station] + if (cloud.size >= 4) dt * 2.7 else -dt * 1.35).coerceIn(0.0, 1.0)
            val mark = fits[station]
            if (mark != null && visible[station] > 0) draw(outline(mark, visible[station] * 0.66))
        }
        val live = (0 until stations).filter { visible[it] > 0 && fits[it] != null }
        if (live.size >= 2) {
            val centres = live.map { fits[it]!!.centre }
            val tree = vec3MinimumSpanningTree(centres)
            for (edge in tree.edges) {
                val alpha = minOf(visible[live[edge.i]], visible[live[edge.j]])
                draw(ribbon(centres[edge.i], centres[edge.j], width = 2.2 + 1.8 * alpha, opacity = 0.53 * alpha))
            }
        }
        nextFrame()
    }
}

world.rules("inspect") {
    onEnter("show knot", points("knot").each()) { knot ->
        while (match.live) {
            val radius = if (inspected.get() == knot.id) 7.0 else 3.2
            draw(
                world.inspectOnClick(knot, spatialDot(project(knot.pos), radius, knotInk)) { why ->
                    inspected.set(why.id)
                    val inputs = why.inputs.joinToString { "${it.kind ?: "tip"} #${it.id}" }
                    val age = why.ageSeconds.f2
                    markdown(
                        "Why this knot?",
                        "**Knot #${why.id}** · $age s old\n\n**${why.origin.process}** from $inputs.\n\n${why.origin.condition}",
                    )
                },
            )
            nextFrame()
        }
    }
}

displayOpen("loom")
var lastPreview = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= 1.0 / 30.0) {
        lastPreview = time
        lines(
            world.step(time),
            channel = "loom",
            w = 850.0,
            h = 370.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(0.0, 35.0, 850.0, 370.0),
        )
    }
}.end { world.close() }
```

## 2. 3 · A thousand moving points

<!-- field-box id="sr_torus" index="1" -->

```edn [field-box id="sr_torus" role="properties"]
{"id" "sr_torus" "index" 1 "name" "3 · A thousand moving points" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · A thousand moving points" "_tombstone" false "frame" #afield/rect [1630.0 285.0 290.0 78.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sr_torus_code_.kt" "lines" 105 "characters" 3517 "sha256" "0c1bbd6a977d42828dbb4b630d1b0ab60263f9bd607e6cd2c471135788038412"}]}
```

```kotlin [field-property box="sr_torus" name="code" path="sr_torus_code_.kt"]
import afield.data.graph.SpatialLifetime.MATCH

val rings = 50
val samples = 20
val world = SpatialRuleWorld(parallelSearch = true)
val filamentInk = vec(0.43, 0.83, 1.0, 0.66)
val bandInk = vec(1.0, 0.66, 0.29, 0.64)
val pointInk = vec(0.84, 0.92, 1.0, 0.47)

fun surface(i: Int, j: Int, t: Double): Vec3 {
    val u = 2 * PI * i / rings
    val v = 2 * PI * j / samples
    val wave = cos(3 * u - 1.8 * t)
    val tube = 47 + 24 * wave + 7 * sin(2 * v + t)
    val radius = 166 + tube * cos(v)
    return vec(radius * cos(u), tube * sin(v) + 22 * sin(2 * u + t), radius * sin(u))
}

val start = AnimationClock.nowSeconds()
fun project(p: Vec3, t: Double): Vec3 {
    val turn = 0.43 * t
    val x = p.x * cos(turn) - p.z * sin(turn)
    val z = p.x * sin(turn) + p.z * cos(turn)
    val tilt = 0.62 + 0.22 * sin(t * 0.62)
    val y = p.y * cos(tilt) - z * sin(tilt)
    val depth = p.y * sin(tilt) + z * cos(tilt)
    val zoom = 910 / (910 + depth)
    return vec(410 + x * zoom, 241 + y * zoom, 0)
}

world.source("a thousand moving points") {
    val anchors =
        List(rings * samples) { n ->
            val i = n / samples
            val j = n % samples
            point("sample", surface(i, j, 0.0), mapOf("band" to i))
        }
    while (true) {
        val t = now - start
        val marks = FLine()
        for ((n, anchor) in anchors.withIndex()) {
            move(anchor, surface(n / samples, n % samples, t))
            val p = project(anchor.pos, t)
            marks.moveTo(p.x - 0.8, p.y).lineTo(p.x + 0.8, p.y)
        }
        marks.color = pointInk
        marks.thickness = 1.45
        draw(marks)
        nextFrame()
    }
}

world.rules("surface links") {
    val nearby = points("sample").pairsWithin(19.0, leave = 23.0)
    onEnter("thread nearby samples", nearby) { (a, b) ->
        edge("thread", a, b, lifetime = MATCH)
        val ink = if (a["band"] == b["band"]) filamentInk else bandInk
        val born = now
        var start3 = a.pos
        var end3 = b.pos
        while (match.live) {
            start3 = a.pos
            end3 = b.pos
            val t = now - start
            val alpha = ((now - born) / 0.33).coerceIn(0.0, 1.0)
            val width = 0.65 + 0.9 * (1 - start3.distance(end3) / 23.0).coerceIn(0.0, 1.0)
            draw(spatialStroke([project(start3, t), project(end3, t)], ink, width, alpha))
            nextFrame()
        }
        val released = now
        while (now - released < 0.55) {
            val t = now - start
            val alpha = 1 - (now - released) / 0.55
            draw(spatialStroke([project(start3, t), project(end3, t)], ink, 0.7, alpha * alpha))
            nextFrame()
        }
    }
}

displayOpen("torus")
var lastPreview = Double.NEGATIVE_INFINITY
var lastReadout = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= 1.0 / 24.0) {
        lastPreview = time
        val drawing = world.step(time)
        lines(
            drawing,
            channel = "torus",
            w = 820.0,
            h = 480.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(35.0, 18.0, 750.0, 444.0),
        )
        val threads = world.edges("thread").size
        if (threads > 0 && time - lastReadout >= 0.5) {
            lastReadout = time
            markdown(
                "Counts",
                "${world.points("sample").size} moving samples  ·  $threads live threads",
            )
        }
    }
}.end { world.close() }
```

## 3. 1 · One filament

<!-- field-box id="sr_filament" index="2" -->

```edn [field-box id="sr_filament" role="properties"]
{"id" "sr_filament" "index" 2 "name" "1 · One filament" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · One filament" "_tombstone" false "frame" #afield/rect [65.0 285.0 290.0 78.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sr_filament_code_.kt" "lines" 78 "characters" 2713 "sha256" "080abf46264c1ff0706f28ba05f8f49f7031d9b1671d83aad1655819675fdc12"}]}
```

```kotlin [field-property box="sr_filament" name="code" path="sr_filament_code_.kt"]
import afield.data.graph.SpatialLifetime.MATCH
import afield.data.graph.inspectOnClick

val world = SpatialRuleWorld()
val ink = Vec4(0.8f, 0.92f, 1f, 1f)

world.source("moving tips") {
    var pullApart = true
    while (true) {
        val left = point("tip", Vec3(110f, 100f, 0f))
        val right = point("tip", Vec3(170f, 100f, 0f))
        val born = now
        val travel = if (pullApart) 170.0 else 45.0
        while (now - born < 2.2) {
            val position = vec(170 + travel * ((now - born) / 2.2), 100, 0)
            move(right, position)
            draw(spatialDot(left.pos, 4.0, ink))
            draw(spatialDot(right.pos, 4.0, ink))
            nextFrame()
        }
        retract(left)
        retract(right)
        pullApart = !pullApart
        val released = now
        while (now - released < 1.0) nextFrame()
    }
}

world.rules("filaments") {
    val close = points("tip").pairsWithin(100.0, leave = 120.0)
    onEnter("join tips", close, condition = "Both tips are present within the 120-unit release distance") { (a, b) ->
        val filament = edge("filament", a, b, lifetime = MATCH)
        fun stroke(ends: List<Vec3>, opacity: Double = 1.0): FLine {
            return world.inspectOnClick(filament, spatialStroke(ends, ink, width = 2.0, opacity = opacity)) { why ->
                val phase = if (why.matchLive == true) "Matched" else "Fading"
                val inputs =
                    why.inputs.joinToString { input ->
                        val status = if (input.live) "live" else "retracted"
                        "${input.kind} #${input.id} ($status)"
                    }
                markdown(
                    "Why this filament?",
                    "**$phase** · ${why.ageSeconds.f2} s old\n\n**${why.origin.process}** · $inputs\n\n${why.origin.condition}",
                )
            }
        }
        var ends = [a.pos, b.pos]
        while (match.live) {
            ends = [a.pos, b.pos]
            draw(stroke(ends))
            nextFrame()
        }

        val released = now
        while (now - released < 0.7) {
            draw(stroke(ends, opacity = 1.0 - (now - released) / 0.7))
            nextFrame()
        }
    }
}

displayOpen("filament")
val previewInterval = 1.0 / 60.0
var lastPreview = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= previewInterval) {
        lastPreview = time
        lines(
            world.step(time),
            channel = "filament",
            w = 460.0,
            h = 200.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(0.0, 0.0, 460.0, 200.0),
        )
    }
}.end { world.close() }
```

## 4. Start here · run the tour

<!-- field-box id="sr_tour" index="3" -->

```edn [field-box id="sr_tour" role="properties"]
{"id" "sr_tour" "index" 3 "name" "Start here · run the tour" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Start here · run the tour" "_tombstone" false "frame" #afield/rect [65.0 70.0 290.0 78.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sr_tour_code_.kt" "lines" 58 "characters" 2757 "sha256" "57ac629297e9ab0bc5258daedac8f7eb1931154b9c778f5309a3813faaa0faae"}]}
```

```kotlin [field-property box="sr_tour" name="code" path="sr_tour_code_.kt"]
val filament = inside.boxes.values.first { it.name == "1 · One filament" }
val loom = inside.boxes.values.first { it.name == "2 · Loom with memory" }
val torus = inside.boxes.values.first { it.name == "3 · A thousand moving points" }

val show = slideShow {
    step("Welcome") {
        markdownNote(
            """
            ## Spatial rules
            Follow a line from the instant its two inputs meet to the moment it fades. Then build a loom from that rule and watch a thousand points wire themselves into a moving surface.

            The tour selects each box. **Alt+Up** launches the selected box, and **Alt+Down** stops it.

            [[next:Start with two points →]]""".trimIndent(),
        )
        selectBox(thisBox)
    }
    step("A match ends") {
        markdownNote(
            """
            ## 1 · One filament
            Launch this box. Watch the two tips separate, then watch them disappear while still close. The filament remembers its last positions and fades in both cases.

            Click the filament while it fades to see which tips are still present and what kept the line alive. Try a two-second fade in the second `while` loop, then relaunch.

            Stop this box before you continue. [[back]] [[next:Build a loom →]]""".trimIndent(),
        )
        selectBox(filament)
        annotate(id = "filament", targetBox = filament, color = "rgba(122, 213, 255, 0.8)")
    }
    step("Patterns compose") {
        markdownNote(
            """
            ## 2 · Loom with memory
            Launch the loom and watch all three stations for a few seconds. The gold knots come from cyan stitches. Violet echoes run alongside taut stitches. Pale ellipses fit live knots, and the tapered ribbons span their centres.

            Click a gold knot to see its two source tips. Change `departureFade` to set how long old stitches remain on the page.

            Stop the loom before you continue. [[back]] [[next:Scale it up →]]""".trimIndent(),
        )
        selectBox(loom)
        annotate(id = "loom", targetBox = loom, color = "rgba(250, 190, 104, 0.8)")
    }
    step("A thousand moving points") {
        markdownNote(
            """
            ## 3 · A thousand moving points
            Launch the torus. Cyan threads join samples in one ring. Amber threads join different rings. Watch the dense bands travel as the tube breathes and check the live edge count below it.

            Try changing the join radius from 19 to 16 units. Relaunch and compare which threads survive.

            [[home:Start again]] [[finish]]""".trimIndent(),
        )
        selectBox(torus)
        annotate(id = "torus", targetBox = torus, color = "rgba(122, 213, 255, 0.8)")
    }
}
runBlocking { show.start() }
```


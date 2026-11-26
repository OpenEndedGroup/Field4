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

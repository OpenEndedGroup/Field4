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

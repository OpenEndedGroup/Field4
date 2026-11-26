import afield.data.graph.SpatialLifetime.MATCH
import afield.data.graph.inspectOnClick
import java.util.concurrent.atomic.AtomicLong

val world = SpatialRuleWorld(parallelSearch = true)
val stitchInk = Vec4(0.35f, 0.75f, 0.91f, 0.70f)
val weaveInk = Vec4(0.97f, 0.70f, 0.34f, 0.88f)
val echoInk = Vec4(0.82f, 0.60f, 0.96f, 0.72f)
val knotInk = Vec4(1f, 0.91f, 0.7f, 1f)
val inspectedKnot = AtomicLong(-1L)

fun midpoint(a: Vec3, b: Vec3) = Vec3(a).add(b).mul(0.5f)

world.source("two moving rows") {
    while (true) {
        val born = now
        val rows =
            List(2) { row ->
                List(9) { column ->
                    point("tip", vec(72 + 60 * column, 160 + 60 * row, 0), mapOf("row" to row))
                }
            }
        while (now - born < 9.0) {
            val t = now - born
            for ((row, tips) in rows.withIndex()) {
                val side = if (row == 0) -1 else 1
                for ((i, tip) in tips.withIndex()) {
                    val phase = t * 1.8 + i * 0.70
                    val p = vec(
                        72 + 60 * i + 23 * sin(phase + row),
                        190 + side * (43 + 34 * cos(phase * 0.55)) + 35 * sin(phase),
                        26 * cos(phase + row * 1.6),
                    )
                    move(tip, p)
                    draw(spatialDot(tip.pos, 2.7, if (row == 0) stitchInk else echoInk))
                }
            }
            nextFrame()
        }
        for (tip in rows.flatten()) retract(tip)
        val released = now
        while (now - released < 1.1) nextFrame()
    }
}

world.rules("weaving") {
    val tips = points("tip")
    val knots = points("knot")
    val opposing = tips.pairsWithin(91.0, leave = 112.0).where { (a, b) -> a["row"] != b["row"] }

    onEnter(
        "stitch",
        opposing,
        condition = "Both tips stay live, in opposite rows, and within 112 units (91 to join)",
    ) { (a, b) ->
        val stitch = edge("stitch", a, b, lifetime = MATCH)
        val knot = point("knot", midpoint(a.pos, b.pos), lifetime = MATCH)
        var path = [a.pos, knot.pos, b.pos]
        val born = now

        while (match.live) {
            val start = a.pos
            val end = b.pos
            val middle = midpoint(start, end).add(vec(0, 12 * sin((now - born) * 4), 0))
            move(knot, middle)
            set(stitch, "length", start.distance(end))
            path = [start, middle, end]
            draw(spatialStroke(path, stitchInk, width = 1.4))
            nextFrame()
        }

        val released = now
        while (now - released < 0.65) {
            draw(spatialStroke(path, stitchInk, width = 1.4, opacity = 1.0 - (now - released) / 0.65))
            nextFrame()
        }
    }

    onEnter("weave neighboring knots", knots.pairsWithin(59.0, leave = 69.0)) { (a, b) ->
        edge("weave", a, b, lifetime = MATCH)
        while (match.live) {
            draw(spatialStroke([a.pos, b.pos], weaveInk, width = 1.1))
            nextFrame()
        }
    }

    val taut = edges("stitch").where { ((it["length"] as? Number)?.toDouble() ?: 0.0) > 74.0 }
    onEnter("echo a taut stitch", taut, condition = "Stitch length exceeds 74") { stitch ->
        // The echo and the stitch are two independent edges on the same endpoints.
        edge("echo", stitch.from, stitch.to, lifetime = MATCH)
        val born = now
        while (match.live) {
            val a = stitch.from.pos
            val b = stitch.to.pos
            val middle = midpoint(a, b).add(vec(22 * sin((now - born) * 5), -20, 0))
            draw(spatialStroke([a, middle, b], echoInk, width = 1.0))
            nextFrame()
        }
    }

    onEnter("show knot", knots.each()) { knot ->
        while (match.live) {
            val radius = if (inspectedKnot.get() == knot.id) 8.0 else 5.0
            val dot = spatialDot(knot.pos, radius, knotInk)
            draw(
                world.inspectOnClick(knot, dot) { why ->
                    inspectedKnot.set(why.id)
                    val inputs = why.inputs.joinToString { "${it.kind ?: "input"} #${it.id}" }
                    val age = why.ageSeconds.f2
                    val status = if (why.matchLive == true) "Match active" else "Match ended"
                    markdown(
                        "Why this knot?",
                        """
                    **Knot #${why.id}** · $age s old

                    Produced by **${why.origin.process}** from $inputs.

                    **$status.** ${why.origin.condition}""".trimIndent(),
                    )
                },
            )
            nextFrame()
        }
    }
}

displayOpen("weave")
markdown("Why this knot?", "Click a gold knot to see its source tips and the rule that produced it.")
val previewInterval = 1.0 / 60.0
var lastPreview = Double.NEGATIVE_INFINITY
loop {
    val time = AnimationClock.nowSeconds()
    if (time - lastPreview + 1e-6 >= previewInterval) {
        lastPreview = time
        lines(
            world.step(time),
            channel = "weave",
            w = 640.0,
            h = 380.0,
            fitToSize = true,
            framing = LinesFraming.Fixed(0.0, 0.0, 640.0, 380.0),
        )
    }
}.end { world.close() }

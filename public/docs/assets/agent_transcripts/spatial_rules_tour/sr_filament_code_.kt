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

import afield.data.graph.SpatialLifetime.MATCH

val fadeSeconds = 0.7
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
    onEnter("join tips", points("tip").pairsWithin(100.0, leave = 120.0)) { (a, b) ->
        edge("filament", a, b, lifetime = MATCH)
        var ends = [a.pos, b.pos]
        while (match.live) {
            ends = [a.pos, b.pos]
            draw(spatialStroke(ends, ink, width = 2.0))
            nextFrame()
        }

        val released = now
        while (now - released < fadeSeconds) {
            draw(spatialStroke(ends, ink, width = 2.0, opacity = 1.0 - (now - released) / fadeSeconds))
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

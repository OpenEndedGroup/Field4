/*
spatial rules source: a sample scene for "FLine layers". Seeds wander; nearby seeds bond;
triangles of bonds fill in. Every frame, the world's step() output becomes the parent's
list of lines. Needs "FLine layers" as its parent.
*/
import afield.data.graph.SpatialLifetime.MATCH

val world = SpatialRuleWorld()
val w = 600.0
val h = 360.0
val ink = vec(0.85, 0.9, 1.0, 1.0)
val skin = vec(0.3, 0.5, 0.9, 0.2)

fun zigzag(u: Double) = asin(sin(u)) * 2 / PI

fun wander(i: Int, t: Double) = vec(
    w / 2 + 0.45 * w * zigzag((0.06 + 0.12 * (i * 0.618 % 1.0)) * t + 5.2 * i),
    h / 2 + 0.45 * h * zigzag((0.06 + 0.12 * (i * 0.382 % 1.0)) * t + 2.4 * i),
    0,
)

world.source("drifting seeds") {
    val seeds = 40 { i -> point("seed", wander(i, 0.0)) }
    while (true) {
        for ((i, seed) in seeds.withIndex()) move(seed, wander(i, age))
        // All the seeds as one pointed line: the points layer draws a dot at each node.
        draw(
            spatialStroke(seeds.map { it.pos }, ink).apply {
                stroked = false
                pointed = true
                pointSize = 1.2
            },
        )
        nextFrame()
    }
}

world.rules("bonds") {
    onEnter("bond nearby seeds", points("seed").pairsWithin(90.0, leave = 110.0)) { (a, b) ->
        val bond = edge("bond", a, b, lifetime = MATCH)
        whileLive { draw(spatialStroke(bond, ink, width = 1.5, opacity = 0.7)) }
        during(0.8) { t -> draw(spatialStroke(bond, ink, width = 1.5, opacity = 0.7 * (1 - t))) }
    }
}

world.rules("membranes") {
    onEnter("fill a triangle", edges("bond").triangles()) { triangle ->
        // A triangle is star-convex: STAR fills it as a fan, with no tessellator.
        whileLive {
            draw(spatialFill(triangle, skin, opacity = min(1.0, age / 0.5)).apply { fillHint = FillHint.STAR })
        }
    }
}

// Each frame's lines replace the parent's list; its loop uploads them.
loop {
    flineLayers.lines.clear()
    flineLayers.lines += world.step(AnimationClock.nowSeconds())
}.end { world.close() }

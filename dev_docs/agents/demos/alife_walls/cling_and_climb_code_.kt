// Two local drives that know about the environment, in the same weighted-blend idiom as
// separate/align/cohere.
//
//   cling(distance, clockwise) — follow the nearest wall at a chosen standoff. Tangential motion
//   plus a radial correction, which is exactly what orbit() is; the difference is that the thing
//   being orbited is read off the geometry rather than passed in.
//
//   gradient(field, towards) — climb (or descend) a scalar field by central difference. Taxis:
//   chemotaxis, phototaxis, whichever the field means. It finds a LOCAL maximum and has nothing
//   to say about the global one, which is why two lamps split a population rather than gathering
//   it — that is the correct behaviour, not a weight to tune away.

val w = ScriptWorld(width = 200.0, height = 200.0, seed = 11L, wrap = false)
w.addDisc(62.0, 130.0, 26.0, segments = 28, name = "pillar")
w.addRect(120.0, 40.0, 50.0, 34.0, name = "block")

val lamps = pointSourceField(
    150.0, 160.0, 1.0,
    45.0, 40.0, 1.0,
    falloff = 26.0
)

// Six clingers round the pillar, alternating direction — a population that all clings the same
// way never meets itself coming back.
repeat(6) { i ->
    val a = 2.0 * Math.PI * i / 6.0
    w.spawn(
        62.0 + Math.cos(a) * 36.0, 130.0 + Math.sin(a) * 36.0,
        maxSpeed = 26.0, radius = 1.0, tag = "cling"
    ) {
        moveFor(12.0, 26.0) { cling(distance = 3.0, clockwise = self.id % 2 == 0) }
    }
}

// Nine climbers on a grid centred between the two lamps, with the block in the way of one of
// them. Centred deliberately: start them nearer one lamp and "they split" is a claim about the
// starting positions rather than about the sensing.
repeat(9) { i ->
    val x = 78.0 + (i % 3) * 22.0
    val y = 78.0 + (i / 3) * 22.0
    w.spawn(x, y, maxSpeed = 18.0, radius = 1.0, tag = "climb") {
        moveFor(12.0, 18.0) {
            gradient(lamps, towards = true, probe = 2.0)
            avoidWalls(clearance = 3.0, weight = 0.5)
            separate(range = 5.0, weight = 0.4)
        }
    }
}

w.run(720)

val clingers = w.living.filter { it.tag == "cling" }
val climbers = w.living.filter { it.tag == "climb" }
fun near(a: ScriptActor, x: Double, y: Double) = Math.hypot(a.position.x - x, a.position.y - y) < 10.0

println("cling  : mean distance to the nearest wall %.2f (asked for radius 1.0 + 3.0 = 4.0)".format(
    clingers.map { w.wallField.distanceTo(it.position.x, it.position.y) }.average()))
println("climb  : mean field value %.3f".format(
    climbers.map { lamps.at(it.position.x, it.position.y) }.average()))
println("         %d at the lamp at (150, 160), %d at the lamp at (45, 40), %d still travelling".format(
    climbers.count { near(it, 150.0, 160.0) },
    climbers.count { near(it, 45.0, 40.0) },
    climbers.count { !near(it, 150.0, 160.0) && !near(it, 45.0, 40.0) }))
println()
println("Nothing told the climbers which lamp to pick — a gradient sensor reads the slope where")
println("it is standing and knows nothing about the other maximum. The split is the whole point.")

lines(
    *(w.obstacleFLines() + w.trailFLines()).toTypedArray(),
    channel = "arena", w = 440.0, h = 440.0, fitToSize = true
)

// Try this: `gradient(lamps, towards = false)` and the climbers seek the dark instead — the
// corners of the arena, and the shadow behind the block.

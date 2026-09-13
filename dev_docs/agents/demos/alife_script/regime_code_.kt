// Phases: the same population, told to do different things at different times.
//
// Ninety agents, four 5 s phases — flock, scatter, flock, scatter. `phase("name")` is just a label
// on a stretch of a program, but because every agent is running its own program, a phase change is
// ninety independent decisions rather than a global mode switch.
//
// Measured with alife.kt's own polarization, through asBoidFlock(): 1 is everybody heading the same
// way, 0 is no agreement. Nothing here asserts the phases worked — the plot shows whether they did.

// Two things about the initial condition, and both of them are the whole reason this figure works.
//
// RANDOM headings, not tangential ones. Ninety agents on a ring all facing along it is perfectly
// symmetric, so their velocities cancel and polarization is EXACTLY 0.0 — and because align and
// cohere preserve that symmetry, it is a fixed point: the first flock phase then reads 0.000 for
// five seconds and the figure looks broken. A symmetric start is not disordered, it is cancelling.
//
// And a velocity at all, rather than rest: align steers toward the mean NEIGHBOUR velocity, and a
// population at rest has none — rest is not disordered, it is empty.
val rng = kotlin.random.Random(5L)
val w = ScriptWorld(width = 220.0, height = 220.0, seed = 5L)
repeat(90) { i ->
    val a = 2.0 * Math.PI * i / 90
    val h = rng.nextDouble(0.0, 2.0 * Math.PI)
    w.spawn(
        110.0 + Math.cos(a) * 40.0, 110.0 + Math.sin(a) * 40.0,
        maxSpeed = 30.0, vx = Math.cos(h) * 12.0, vy = Math.sin(h) * 12.0
    ) {
        forever {
            phase("flock") { moveFor(5.0) { align(range = 26.0); cohere(range = 26.0, weight = 0.5); separate(range = 7.0); avoidEdges() } }
            // turn = 40, not 6: at 60 Hz a small turn random-walks a heading less than a radian
            // across 5 s, which PRESERVES the alignment it inherited instead of breaking it.
            phase("scatter") { moveFor(5.0) { wander(turn = 40.0); separate(range = 9.0); avoidEdges() } }
        }
    }
}

val order = mutableListOf<Double>()
repeat(1200) {
    w.step()
    val f = w.asBoidFlock()
    order += polarization(f.vx, f.vy)
}

println("phase means over the last second of each 5 s phase:")
listOf("flock" to 0, "scatter" to 1, "flock" to 2, "scatter" to 3).forEach { (name, k) ->
    println("  %-8s %.3f".format(name, order.subList(k * 300 + 240, k * 300 + 300).average()))
}

// One output, on one channel. Two panels in a box stack on top of each other, and the plot IS the
// claim here — a picture of ninety trails is a grey doughnut whichever phase drew it.
// `marks` only on the two scatter boundaries: a mark per phase repeats "flock"/"scatter" four
// times in the legend, and a mark at step 0 labels nothing.
showSignals(
    "polarization" to order,
    channel = "order",
    title = "alignment: flock, scatter, flock, scatter — 5 s each",
    marks = listOf("scatter" to 300.0, "scatter" to 900.0)
)

// Try this: drop `turn = 40.0` to 6.0 and watch the scatter phases stop working — polarization
// stays high, because a gentle random walk does not decorrelate a heading inside five seconds.
// That was a real bug in this demo's first version, and the plot is what caught it.

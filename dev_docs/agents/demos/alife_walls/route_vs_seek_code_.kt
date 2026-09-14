// seek() points at the goal and pushes. That is correct and cheap when there is a straight line,
// and when there is a barrier in the way it presses into the barrier for as long as you let it.
// No amount of avoidWalls() fixes that: avoidance is local, and the decision to go the OTHER way
// round a wall is not. route() plans — a radius-aware A* through the world's walls, smoothed
// against the same swept test the physics uses, then followed waypoint by waypoint.
//
// The maze is an S. The first barrier is open only at the BOTTOM, the second only at the TOP, so
// no fixed "always turn left" solves it. Both arms run avoidWalls at the same weight: this is
// routing against the best local steering available, not routing against nothing.

val GOAL_X = 175.0
val GOAL_Y = 100.0
val STEPS = 1800                 // 30 s at 60 Hz
val SEEDS = listOf(1L, 2L, 3L, 4L)
val PER_SEED = 8

fun maze(seed: Long, routed: Boolean): ScriptWorld {
    val w = ScriptWorld(width = 200.0, height = 200.0, seed = seed, wrap = false)
    w.addWall(70.0, 45.0, 70.0, 200.0, name = "open at the bottom")
    w.addWall(130.0, 0.0, 130.0, 155.0, name = "open at the top")
    val jitter = kotlin.random.Random(seed)      // same jitter in both arms: same start positions
    repeat(PER_SEED) {
        w.spawn(
            18.0 + jitter.nextDouble(-8.0, 8.0), 100.0 + jitter.nextDouble(-28.0, 28.0),
            maxSpeed = 35.0, radius = 1.2, steerResponse = 0.3,
            tag = if (routed) "routed" else "direct"
        ) {
            val goal = point(GOAL_X, GOAL_Y)         // R3c: this now works inside moveUntil too
            moveUntil(maxSeconds = STEPS / 60.0, speed = 35.0, until = { near(goal, 6.0) }) {
                if (routed) route(goal, clearance = 3.5) else seek(goal)
                avoidWalls(clearance = 3.0, weight = 0.6)
                separate(range = 4.0, weight = 0.4)
            }
            rest(2.0)
        }
    }
    return w
}

val curves = LinkedHashMap<String, List<Double>>()
val shown = LinkedHashMap<String, ScriptWorld>()
for (routed in listOf(true, false)) {
    val name = if (routed) "routed" else "direct"
    val cumulative = MutableList(STEPS) { 0.0 }
    var population = 0
    var travel = 0.0
    for (s in SEEDS) {
        val w = maze(s, routed)
        val done = mutableSetOf<Int>()
        repeat(STEPS) { t ->
            w.step()
            for (a in w.actors) if (a.id !in done && a.distanceTo(GOAL_X, GOAL_Y) <= 6.0) done += a.id
            cumulative[t] = cumulative[t] + done.size
        }
        population += w.actors.size
        travel += w.totalPathLength()
        if (s == SEEDS.first()) shown[name] = w
    }
    curves[name] = cumulative.map { it / population }
    println("%-7s arrived %.3f of %d   mean travel %.1f".format(
        name, cumulative.last() / population, population, travel / population))
}
println()
println("Both arms travel — the second column is the non-degeneracy check. A margin over a")
println("control that never left home would mean nothing.")

showSignals(
    "routed" to curves["routed"]!!,
    "direct" to curves["direct"]!!,
    channel = "arrivals",
    title = "fraction arrived, ${SEEDS.size} seeds x $PER_SEED agents per arm",
    yTitle = "of 1"
)

// Try this: change `route(goal, clearance = 3.5)` to `clearance = 0.5`. The route still gets
// there, but it plans 1.7 units from every corner — inside the reach of the avoidWalls term the
// same agent is running — and the two terms then disagree all the way round each corner. The
// arrival curve sags. Plan outside your own avoidance range.

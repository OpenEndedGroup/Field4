// What a per-agent program buys that one global rule cannot: a DIVISION OF LABOUR.
//
// Six sites, twenty-four agents, two arms at the same speed on the same integrator.
//   - scripted: each agent walks the six sites in its OWN rotation, then feeds for 1.2 s.
//   - one global rule: the site is a function of the world clock, so everybody goes to the
//     same place at the same time. That is not a straw man — it is given the kinder 2.5 s
//     dwell, long enough to arrive and short enough to reach all six inside the run.
//
// The number underneath is "how many of the six sites had somebody on them", averaged over every
// step. Six agents spread over six sites scores 6; twenty-four agents in a heap scores 1.

val sites = (0 until 6).map { i ->
    val a = 2.0 * Math.PI * i / 6
    Vector2d(100.0 + Math.cos(a) * 60.0, 100.0 + Math.sin(a) * 60.0)
}
val agents = 24
val steps = 900

fun world(scripted: Boolean) = ScriptWorld(width = 200.0, height = 200.0, seed = 21L).also { w ->
    for (i in 0 until agents) {
        val a = 2.0 * Math.PI * i / agents
        val order = (0 until 6).map { (it + i) % 6 }   // <- the per-agent part
        w.spawn(100.0 + Math.cos(a) * 18.0, 100.0 + Math.sin(a) * 18.0, maxSpeed = 45.0) {
            forever {
                if (scripted) {
                    for (s in order) {
                        val site = point(sites[s].x, sites[s].y)
                        phase("travel") {
                            moveUntil(maxSeconds = 5.0, until = { near(site, 7.0) }) {
                                arrive(site, slowRadius = 14.0); separate(range = 6.0, weight = 0.35)
                            }
                        }
                        phase("feed") { rest(1.2) }
                    }
                } else {
                    val s = ((time / 2.5).toInt()) % 6      // <- the global part: clock only
                    val site = point(sites[s].x, sites[s].y)
                    moveFor(0.1, 45.0) {
                        arrive(site, slowRadius = 14.0); separate(range = 6.0, weight = 0.35)
                    }
                }
            }
        }
    }
}

val arms = listOf("scripted" to world(true), "one global rule" to world(false))
val breadth = arms.map { mutableListOf<Double>() }
repeat(steps) {
    arms.forEachIndexed { i, (_, w) ->
        w.step()
        breadth[i] += sites.count { s -> w.living.any { it.distanceTo(s) <= 14.0 } }.toDouble()
    }
}

arms.forEachIndexed { i, (name, w) ->
    println("%-16s mean sites occupied %.3f   travel %.0f".format(
        name, breadth[i].average(), w.totalPathLength()))
}
println()
println("Both arms move (compare the travel column) — a margin over a control that never left")
println("home would mean nothing. The scripted arm travels LESS and covers MORE.")

showSignals(
    "scripted" to breadth[0],
    "one global rule" to breadth[1],
    channel = "breadth",
    title = "sites occupied, per step",
    yTitle = "of 6"
)

// Try this: set `order` to `(0 until 6).toList()` for every agent — same script, no rotation —
// and the scripted arm collapses towards the global one. The rotation IS the division of labour.

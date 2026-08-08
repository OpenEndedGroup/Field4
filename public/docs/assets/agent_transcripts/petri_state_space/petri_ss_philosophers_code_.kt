import afield.petri.*

// Five philosophers, five forks, everybody left-handed. Whether this wedges depends on
// the order the grabs happen in, which is precisely what running it once cannot tell you.
fun table(reverseLast: Boolean) = petri {
    conflict = PetriConflict.ORDER
    grid = cyc(1, 4)
    for (i in 0 until 5) place("fork.$i", tokens = 1)
    for (i in 0 until 5) {
        val left = i
        val right = (i + 1) % 5
        val reversed = reverseLast && i == 4
        t("grab.$i") { from("fork.${if (reversed) right else left}"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.${if (reversed) left else right}"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}

val net = table(reverseLast = false)

// The deterministic question: does *this* net, played *this* way, stop?
println("willDeadlock()  = ${net.willDeadlock()}   (one interleaving, chosen by the policy)")

// The classical one: could *any* interleaving stop it? Exhaustive, so the answer is a fact
// about the net rather than about the way it happens to be being played.
val graph = net.stateSpace()
println(graph.report())

val trace = graph.deadlockTrace()!!
println("shortest counterexample (${trace.length} firings): $trace")
println(trace.describe(ending = "→ nothing is enabled; the table is wedged"))

// A counterexample that checks itself: re-fired through the engine's own enabling test.
println("verify() = ${trace.verify() ?: "checks out"}")

// BFS layers left to right, one dot per reachable marking, the counterexample in orange.
showPetriStateSpace(graph, w = 1100.0, h = 640.0)

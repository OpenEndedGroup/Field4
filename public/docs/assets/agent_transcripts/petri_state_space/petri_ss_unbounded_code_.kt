import afield.petri.*

// `make` puts its token straight back and drops another one in `queue`, so `queue` grows
// forever and the state space is infinite.
val leak = petri {
    place("run", tokens = 1)
    t("make") { from("run"); to("run"); to("queue") }
}

// The exhaustive search cannot finish, and does not pretend to. Every accessor that would
// be a claim about markings nobody visited returns null — unknown — rather than false.
val graph = leak.stateSpace(budget = 500)
println("outcome     ${graph.outcome}")
println("canDeadlock ${graph.canDeadlock}   (null = unknown, NOT 'safe')")
println("bounds      ${graph.bounds}")
println("neverFires  ${graph.neverFires()}")
println()

// Karp-Miller accelerates the growth to omega and therefore always terminates. Two nodes
// is the whole answer.
val cover = leak.coverability()
println(cover.report())
println("bound('queue') = ${cover.bound("queue")}   (null here means omega, not unknown)")
println("bound('run')   = ${cover.bound("run")}")

// The trade, stated: coverability terminates on everything and is only *valid* for plain
// place/transition nets. Add a capacity and it says so instead of guessing.
val capped = petri {
    place("run", tokens = 1)
    place("queue", capacity = 3)
    t("make") { from("run"); to("run"); to("queue") }
}
println()
println(capped.coverability().report())
println("…so use the exhaustive search there: ${capped.stateSpace().report().lines().first()}")

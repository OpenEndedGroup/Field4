import afield.petri.*

// The producer/consumer buffer from public/docs/petri_nets.md. Each worker holds a token
// it puts straight back, which is what makes it *one* worker rather than an unbounded
// supply of them.
val buffer = petri {
    grid = cyc(1, 8)
    place("free", tokens = 4)
    place("items", capacity = 4)
    place("producer", tokens = 1)
    place("consumer", tokens = 1)
    t("produce") { from("producer"); from("free"); to("items"); to("producer") }
    t("consume") { from("consumer"); from("items"); to("free"); to("consumer") }
}

val graph = buffer.stateSpace()
println(graph.report())
println("bounds: " + graph.bounds)

// `witness` is EF with the route attached: the shortest way to reach a marking that
// satisfies the predicate, or null when nothing does.
val full = graph.witness { it.count("items") == 4 }!!
println("shortest route to a full buffer (${full.length} firings): $full")

// `invariant` is the same search on the negation, so a null *is* the proof.
val conserved = graph.invariant { it.count("items") + it.count("free") == 4 }
println("items + free == 4 always? " + (conserved?.let { "no — $it" } ?: "yes, over all ${graph.stateCount} markings"))

showPetriStateSpace(graph, w = 900.0, h = 320.0)

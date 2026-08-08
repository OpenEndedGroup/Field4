import afield.petri.*

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

val net = table(reverseLast = true)
val graph = net.stateSpace()

// Not "it did not deadlock in the run we tried": every one of the 70 reachable markings
// was visited and every one of them enables something.
println(graph.report())

// The sharp bit. Two analyses, two answers, both correct, about different things:
println()
println("deadTransitions()  ${net.deadTransitions()}")
println("                   ^ this policy always gives the contested fork to the first")
println("                     claimant, so philosopher 4 never eats. That is STARVATION,")
println("                     and it is a property of the conflict policy.")
println("neverFires()       ${graph.neverFires()}")
println("                   ^ …but some interleaving does feed philosopher 4, so no")
println("                     transition is genuinely dead. That is a property of the NET.")

showPetriStateSpace(graph, w = 1100.0, h = 640.0)

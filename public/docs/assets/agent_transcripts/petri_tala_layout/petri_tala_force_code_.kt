import afield.petri.*

// Five philosophers, five forks. Every philosopher needs both neighbouring forks to eat,
// which is what makes this dense rather than deep: 25 nodes and 40 arcs, all in one
// strongly connected lump with no layering to find.
fun philosophers(count: Int = 5): PetriNet = petri {
    for (index in 0 until count) place("fork.$index", tokens = 1)
    for (index in 0 until count) {
        val right = (index + 1) % count
        place("think.$index", tokens = 1)
        place("eat.$index")
        t("take.$index") {
            from("think.$index"); from("fork.$index"); from("fork.$right"); to("eat.$index")
        }
        t("drop.$index") {
            from("eat.$index"); to("think.$index"); to("fork.$index"); to("fork.$right")
        }
    }
}

val net = philosophers()
showPetriNet(net, layout = PetriLayout.FORCE, w = 620.0, h = 520.0)

// PetriLayoutSolver does not trust the physics: a spring model finds *a* minimum of its
// own energy, which is not the same as a drawing you can read. So it relaxes from 48
// different random starts and scores each result on crossings, overlaps and edges
// passing under unrelated nodes, then keeps the best. Seeded from the net's own
// structure, so the same net always draws the same way.
"FORCE: 48 seeded restarts, scored on crossings and overlaps, best kept"

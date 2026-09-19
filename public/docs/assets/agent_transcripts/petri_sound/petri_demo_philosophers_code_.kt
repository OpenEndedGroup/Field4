import afield.petri.*

// Nothing in the model knows what a note is. Here is the same engine as a concurrency
// checker: five philosophers, five forks, and the shape of the net computed in a loop —
// which is the one thing the `#petri` notation cannot do.
fun table(reverseLast: Boolean, fairly: Boolean) = petri {
    conflict = if (fairly) PetriConflict.ROUND_ROBIN else PetriConflict.ORDER
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

val naive = table(reverseLast = false, fairly = false)
showPetriNet(naive, w = 760.0, h = 460.0)

for ((label, net) in listOf(
    "all left-handed" to naive,
    "one reaches right first" to table(reverseLast = true, fairly = false),
    "…and round-robin conflict" to table(reverseLast = true, fairly = true),
)) {
    val stuck = net.deadTransitions()
    println("%-26s deadlocks=%-5s never fires: %s".format(
        label, net.willDeadlock(), stuck.ifEmpty { listOf("—") }.joinToString(", ")))
}

// The middle line is where breaking the symmetry removes the deadlock and leaves a
// starvation behind it. Two faults, two different fixes.

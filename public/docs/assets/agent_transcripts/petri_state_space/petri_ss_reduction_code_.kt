import afield.petri.*

fun philosophers(n: Int) = petri {
    conflict = PetriConflict.ORDER
    grid = cyc(1, 4)
    for (i in 0 until n) place("fork.$i", tokens = 1)
    for (i in 0 until n) {
        val left = i
        val right = (i + 1) % n
        t("grab.$i") { from("fork.$left"); to("holding.$i") }
        t("eat.$i") { from("holding.$i"); from("fork.$right"); to("eating.$i") }
        t("done.$i") { from("eating.$i"); to("fork.$left"); to("fork.$right") }
    }
}

// An exhaustive search is exponential in how much is happening at once, so the useful
// question is not "does this work" but "how far does it get". The answer is a fact
// about your net and your machine, so measure it rather than guess.
println(" n   full         reduced   speed-up   verdict     ms(full) ms(red)")
for (n in 2..12) {
    val net = philosophers(n)
    val t0 = System.nanoTime()
    val full = net.stateSpace()
    val fullMs = (System.nanoTime() - t0) / 1_000_000

    // The same search, exploring a *stubborn set* at each marking instead of every
    // enabled transition — one ordering of five independent grabs rather than all 120.
    val t1 = System.nanoTime()
    val red = net.stateSpace(reduction = PetriReduction.STUBBORN)
    val redMs = (System.nanoTime() - t1) / 1_000_000

    println("%2d   %-11s  %-7d   %-8s   %-9s   %5d    %5d".format(
        n,
        if (full.complete) "${full.stateCount}" else "BUDGET(${full.stateCount})",
        red.stateCount,
        if (full.complete) "%.1fx".format(full.stateCount.toDouble() / red.stateCount) else "—",
        "${full.canDeadlock}/${red.canDeadlock}",
        fullMs, redMs))
}

// The full sequence is the companion Pell numbers, a(n) = 2a(n-1) + a(n-2); the reduced
// one is 2n^2 - 2n + 2. Twelve philosophers is past the default budget for the full
// search — which says BUDGET and refuses to answer — and 266 markings for the reduced one.
val twelve = philosophers(12)
val reduced = twelve.stateSpace(reduction = PetriReduction.STUBBORN)
println()
println("full   : " + twelve.stateSpace().report().trim().lines().joinToString(" | "))
println("reduced: " + reduced.report().trim().lines().joinToString(" | "))
println("counterexample: " + reduced.deadlockTrace()!! + "   verify=" +
    (reduced.deadlockTrace()!!.verify() ?: "checks out"))

// The reduction answers deadlock and refuses everything else, because that is all the
// theorem covers. `exact` is the flag that keeps the two apart.
println()
println("reduced.complete=${reduced.complete}  reduced.exact=${reduced.exact}")
println("reduced.bounds=${reduced.bounds}  isLive=${reduced.isLive}  isReversible=${reduced.isReversible}")

showPetriStateSpace(reduced, w = 1100.0, h = 600.0)

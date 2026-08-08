import afield.petri.*

// Two processes and a critical section each. The only difference between these two nets
// is an inhibitor arc — "not while the other one is in there".
fun net(guarded: Boolean) = petri {
    place("idle.a", tokens = 1)
    place("idle.b", tokens = 1)
    place("crit.a")
    place("crit.b")
    t("enter.a") { from("idle.a"); if (guarded) inhibit("crit.b"); to("crit.a") }
    t("leave.a") { from("crit.a"); to("idle.a") }
    t("enter.b") { from("idle.b"); if (guarded) inhibit("crit.a"); to("crit.b") }
    t("leave.b") { from("crit.b"); to("idle.b") }
}

val safe = net(guarded = true)
val unsafe = net(guarded = false)

// An inhibitor arc moves no token, and the incidence matrix is a record of *change*. So
// the two nets have the same matrix, and no amount of algebra can distinguish them.
println("same incidence matrix? " +
    (safe.incidence().map { it.toList() } == unsafe.incidence().map { it.toList() }))
println(safe.incidenceTable())
println("P-invariants: " + safe.pInvariants())
println("…none of which couples crit.a to crit.b, so the algebra cannot prove exclusion.")
println()

// The search asks the engine whether a transition is enabled, and the engine knows about
// inhibitor arcs. So it can.
println("guarded:   mutually exclusive? " + safe.stateSpace().mutuallyExclusive("crit.a", "crit.b") +
    "   over ${safe.stateSpace().stateCount} reachable markings")
val breach = unsafe.stateSpace().mutualExclusionFailure("crit.a", "crit.b")!!
println("unguarded: broken in ${breach.length} firings — $breach")
println(breach.describe(ending = "→ both processes are inside the critical section"))

showPetriNet(safe, w = 760.0, h = 400.0, layout = PetriLayout.FORCE)

# Field Document: petri_state_space

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 4 — a leak, and an omega

<!-- field-box id="petri_ss_unbounded" index="0" -->

```edn [field-box id="petri_ss_unbounded" role="properties"]
{"id" "petri_ss_unbounded" "index" 0 "name" "4 — a leak, and an omega" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — a leak, and an omega" "_tombstone" false "frame" #afield/rect [60.0 200.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_ss_unbounded_code_.kt" "lines" 38 "characters" 1693 "sha256" "5260fccc075482de19d532f1f8ef8d11b161c40d458cb940dbd31acc1240987f"}]}
```

```kotlin [field-property box="petri_ss_unbounded" name="code" path="petri_ss_unbounded_code_.kt"]
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
// place/transition nets. Add a capacity and it says so rather than guessing — even here,
// where it happens to get the right answer anyway, because a capped place is bounded by
// its capacity whatever the pumping argument says and so is never given an ω.
val capped = petri {
    place("run", tokens = 1)
    place("queue", capacity = 3)
    t("make") { from("run"); to("run"); to("queue") }
}
println()
println(capped.coverability().report())
println("the caveat is conservative, not a wrong answer — the exhaustive search agrees:")
println("  " + capped.stateSpace().report().lines().first())
```

## 2. 1 — the table can wedge

<!-- field-box id="petri_ss_philosophers" index="1" -->

```edn [field-box id="petri_ss_philosophers" role="properties"]
{"id" "petri_ss_philosophers" "index" 1 "name" "1 — the table can wedge" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — the table can wedge" "_tombstone" false "frame" #afield/rect [60.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_ss_philosophers_code_.kt" "lines" 37 "characters" 1660 "sha256" "d0f051479977fb4f75cd029209581fd13dd4e3742329e878ccb4c3413a743a49"}]}
```

```kotlin [field-property box="petri_ss_philosophers" name="code" path="petri_ss_philosophers_code_.kt"]
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
```

## 3. 5 — what the algebra cannot see

<!-- field-box id="petri_ss_exclusion" index="2" -->

```edn [field-box id="petri_ss_exclusion" role="properties"]
{"id" "petri_ss_exclusion" "index" 2 "name" "5 — what the algebra cannot see" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — what the algebra cannot see" "_tombstone" false "frame" #afield/rect [420.0 200.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_ss_exclusion_code_.kt" "lines" 36 "characters" 1714 "sha256" "39811f62360bac42ff8a388f319685a11fba355281748dabdd6ed89cb458f8cd"}]}
```

```kotlin [field-property box="petri_ss_exclusion" name="code" path="petri_ss_exclusion_code_.kt"]
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
```

## 4. 2 — proved, and the difference from starved

<!-- field-box id="petri_ss_asymmetric" index="3" -->

```edn [field-box id="petri_ss_asymmetric" role="properties"]
{"id" "petri_ss_asymmetric" "index" 3 "name" "2 — proved, and the difference from starved" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — proved, and the difference from starved" "_tombstone" false "frame" #afield/rect [420.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_ss_asymmetric_code_.kt" "lines" 34 "characters" 1508 "sha256" "d7133d0c5bda354f4c0db9abb504bb131fca19da310fc46dd3ef04a80b25ec2e"}]}
```

```kotlin [field-property box="petri_ss_asymmetric" name="code" path="petri_ss_asymmetric_code_.kt"]
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
```

## 5. 6 — the wall, and stubborn sets

<!-- field-box id="petri_ss_reduction" index="4" -->

```edn [field-box id="petri_ss_reduction" role="properties"]
{"id" "petri_ss_reduction" "index" 4 "name" "6 — the wall, and stubborn sets" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — the wall, and stubborn sets" "_tombstone" false "frame" #afield/rect [780.0 200.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_ss_reduction_code_.kt" "lines" 58 "characters" 2719 "sha256" "427d174c1fac2411433bfa4d02b50211143acc141720856aad7095d7b984e3d6"}]}
```

```kotlin [field-property box="petri_ss_reduction" name="code" path="petri_ss_reduction_code_.kt"]
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
```

## 6. 3 — a buffer, counted exactly

<!-- field-box id="petri_ss_buffer" index="5" -->

```edn [field-box id="petri_ss_buffer" role="properties"]
{"id" "petri_ss_buffer" "index" 5 "name" "3 — a buffer, counted exactly" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — a buffer, counted exactly" "_tombstone" false "frame" #afield/rect [780.0 60.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "petri_ss_buffer_code_.kt" "lines" 29 "characters" 1244 "sha256" "383a496135c05360a28ac15436aa95b208b22dcf5d8a483d6c84991862709533"}]}
```

```kotlin [field-property box="petri_ss_buffer" name="code" path="petri_ss_buffer_code_.kt"]
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
```


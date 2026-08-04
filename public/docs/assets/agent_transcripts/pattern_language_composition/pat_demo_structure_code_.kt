// When two patterns meet, one of three things happens to the rhythm. The bars say where
// the structure comes from, exactly as in Tidal.
val twos = pat("0 1").nums()
val threes = pat("10 20 30").nums()

showPatRoll("structure-choice")
displayOpen("main")

// A lambda, not a named `fun`: K2 cannot codegen a named function inside a box that
// captures the script's implicit Box receiver (which `println` needs).
val table = { label: String, p: PatPattern<Double> ->
    val events = p.events(PatArc(PatQ.ZERO, PatQ.ONE))
        .sortedBy { it.arc.start.toDouble() }
    println("%-10s %d events  %s".format(label, events.size,
        events.joinToString(" ") { "[${it.arc.start}..${it.arc.end}]=${patFormatNumber(it.value)}" }))
}
table("|+| both", twos.`|+|`(threes))   // 4 events: the intersection grid
table("|+ left", twos.`|+`(threes))     // 2 events: exactly the left's
table("+| right", twos.`+|`(threes))    // 3 events: exactly the right's

// `/`, `<` and `>` are illegal in a Kotlin identifier, so division is `|over|` and the
// take-value family spells the arrow as an L or an R. The bar keeps its Tidal meaning.
table("|over|", twos.`|over|`(threes))
println("bd sd `#` x y z w = " + (pat("bd sd").`#`(pat("x y z w")))
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value })

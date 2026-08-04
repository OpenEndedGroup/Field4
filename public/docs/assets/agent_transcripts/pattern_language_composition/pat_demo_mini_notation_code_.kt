// Three standalone step markers. Each is shorthand for a bracketed form, so each pair
// below must come out arc-for-arc identical — and the roll draws them one above the other
// so you can check by eye as well as by assertion.
showPatRoll("mini-notation")
displayOpen("main")

val pairs = listOf(
    "bd sd . hh hh hh" to "[bd sd] [hh hh hh]",   // `.` groups
    "bd _ _ sd" to "bd@3 sd",                     // `_` extends
    "bd ! ! sd" to "bd!3 sd",                     // bare `!` repeats
)
for ((shorthand, bracketed) in pairs) {
    val a = pat(shorthand).events(PatArc(PatQ.ZERO, PatQ.ONE)).sortedBy { it.arc.start.toDouble() }
    val b = pat(bracketed).events(PatArc(PatQ.ZERO, PatQ.ONE)).sortedBy { it.arc.start.toDouble() }
    val same = a.map { it.arc } == b.map { it.arc } && a.map { it.value } == b.map { it.value }
    println("%-20s == %-22s : %s   %s".format(shorthand, bracketed, same,
        a.joinToString(" ") { "${it.value}@${it.arc.start}" }))
}

// A `.` inside a token is still part of the token — decimals and sample payloads are safe.
println("0.5 1.25 -> " + pat("0.5 1.25").nums()
    .events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value })
println("my_atom  -> " + pat("my_atom b").events(PatArc(PatQ.ZERO, PatQ.ONE)).map { it.value })

// A sample-index suffix becomes an `n` parameter without changing the atom.
pat("bd:3 sd 808:12").events(PatArc(PatQ.ZERO, PatQ.ONE)).forEach {
    println("atom ${it.value} -> n=${it.annotations["n"]}, span=\"${it.sourceSpan?.text}\"")
}

// Hear the dot: two cycles of five even notes, then the same five notes grouped.
showPatAudio(
    pat("bd sd hh hh hh").whenCycle({ it >= 2 }) { pat("bd sd . hh hh hh") },
    cycles = 4, cps = 1.0,
    voice = patVoice(decay = 0.012, sustain = 0.12),
    channel = "dot",
)
displayOpen("dot")

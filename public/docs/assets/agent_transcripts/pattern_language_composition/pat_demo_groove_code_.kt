// swing() is exact rational time, not a nudge in milliseconds: every off-beat lands a
// third of a subdivision late and the pattern stays exactly quantised.
val hats = pat("hh*8")
showPatRoll("groove")
displayOpen("main")

println("straight : " + hats.events(PatArc(PatQ.ZERO, PatQ.ONE)).map { "${it.arc.start}" })
println("swing(4) : " + hats.swing(4).events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { "${it.arc.start}" })

// squeeze replaces each event with a whole pattern, compressed into that event's arc —
// mini-notation nesting, over Kotlin values. Compare `join`, which does not rescale.
val nested = pat("a b").squeeze { if (it == "a") pat("1 2 3") else pat("4 5") }
println("squeeze  : " + nested.events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { "${it.value}@${it.arc.start}" })

// Two cycles straight, then two swung. This is the one to listen to.
showPatAudio(
    hats.whenCycle({ it >= 2 }) { hats.swing(4) },
    cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.NOISE, decay = 0.008, sustain = 0.06),
    channel = "swing",
)
displayOpen("swing")

// And a full bar from one Euclidean figure: bd on the onsets, hh on everything else.
showPatAudio(
    euclidFull(3, 8, pat("bd"), pat("hh") with gain(0.35)),
    cycles = 4, cps = 2.0,
    voice = patVoice(decay = 0.012, sustain = 0.1),
    channel = "euclid",
)
displayOpen("euclid")

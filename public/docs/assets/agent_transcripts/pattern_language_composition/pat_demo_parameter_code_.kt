// Before tonight the only way to attach a value to an atom was zip(...) with a string
// pattern: one literal per note, typed by hand. Now a parameter can be a FUNCTION, and
// the note count does not change when you read it.
showPatRoll("continuous-parameter")
displayOpen("main")

val curve = sine().rangex(300, 6000)
for (n in listOf(4, 8, 16)) {
    val notes = (pat("45*$n") with cutoff(curve))
        .events(PatArc(PatQ.ZERO, PatQ.ONE)).sortedBy { it.arc.start.toDouble() }
    println("bd*%-2d -> %2d notes, cutoff %s".format(n, notes.size,
        notes.joinToString(" ") { "%.0f".format(it.num("cutoff")) }))
}
// appBoth is the other choice, and it is not a bug — it fragments on purpose:
println("appBoth against a 16-step sweep -> " +
    pat("45*4").appBoth(curve.segment(16)) { a, _ -> a }
        .events(PatArc(PatQ.ZERO, PatQ.ONE)).size + " events")

// Hear it: a 110 Hz saw whose filter follows the curve, one value latched per note.
showPatAudio(
    pat("45*16") with cutoff(curve) with gain(0.7),
    cycles = 4, cps = 2.0,
    voice = patVoice(wave = PatWave.SAW, baseHz = 110.0, decay = 0.012, sustain = 0.3),
    channel = "sound",
)
displayOpen("sound")

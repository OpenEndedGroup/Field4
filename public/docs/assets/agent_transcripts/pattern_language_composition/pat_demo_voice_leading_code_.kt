// Spelled from each chord's own root, a progression makes the hand leap. voiceLead()
// picks each chord's inversion and octave to minimise how far the voices have to move.
val progression = chords("c'maj7 a'min7 d'min7 g'dom7")
val asWritten = progression.events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { it.value }
val voiceLed = progression.voiceLead(center = 60).events(PatArc(PatQ.ZERO, PatQ.ONE))
    .sortedBy { it.arc.start.toDouble() }.map { it.value }

showPatRoll("voice-leading")
displayOpen("main")

println("as written : $asWritten")
println("voice-led  : $voiceLed")
println("movement   : %.0f semitones as written, %.0f voice-led".format(
    patTotalVoiceMovement(asWritten), patTotalVoiceMovement(voiceLed)))
println("harmony unchanged: " + asWritten.zip(voiceLed).all { (a, b) ->
    a.map { Math.floorMod(it, 12) }.toSortedSet() == b.map { Math.floorMod(it, 12) }.toSortedSet()
})

// Hear both: two cycles as written, two voice-led.
val arped = progression.arpeggiate().texts()
val led = progression.voiceLead(center = 60).arpeggiate().texts()
showPatAudio(
    arped.whenCycle({ it >= 2 }) { led },
    cycles = 4, cps = 1.0,
    voice = patVoice(wave = PatWave.SINE, decay = 0.02, sustain = 0.45),
    channel = "sound",
)
displayOpen("sound")

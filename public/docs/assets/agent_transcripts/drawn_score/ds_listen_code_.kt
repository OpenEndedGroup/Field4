// Play both readings again, then take the sound apart: an onset per strike, a pitch per note.
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))
val tuned = DrawnScoreDocs.pentatonic(notes)
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
val heard = listOf(notes, tuned).map { DrawnScoreDocs.listen(patRenderStereo(patTimed(it, beats = 8.0), cycles = 2, cps = 0.25, voice = voice), it) }
val trace = heard[0].trace()
showSignals(
    "drawn" to trace.map { it.third }, "heard" to trace.map { it.second },
    x = trace.map { it.first }, title = "pitch: drawn and heard", xTitle = "seconds", yTitle = "MIDI",
)
println(heard.joinToString("\n") { it.describe() })

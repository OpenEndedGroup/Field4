// Render the reading again, then take the sound apart: an onset per strike, a pitch per note.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
val notes = score.notes()
val audio = patRenderStereo(score.toPattern(), cycles = 2, cps = 0.25, voice = DrawnScoreDocs.VOICE)
val heard = DrawnScoreDocs.listen(audio, notes)
val trace = heard.trace()
showSignals(
    "drawn" to trace.map { it.third }, "heard" to trace.map { it.second },
    x = trace.map { it.first }, title = "pitch: drawn and heard", xTitle = "seconds", yTitle = "MIDI",
)
println("${heard.onsetsDetected} onsets for ${heard.scheduled} notes, worst %.1f ms; worst pitch error %.3f semitones"
    .format(heard.maxOnsetErrorMs, heard.maxPitchError))

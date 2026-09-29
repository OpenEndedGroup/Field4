// The same drawing read another way: every note snapped to C minor pentatonic.
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))   // the loop in `play`

val minorPentatonic = setOf(0, 3, 5, 7, 10)                                // C Eb F G Bb
val inScale = (24..108).filter { it % 12 in minorPentatonic }
val snap = { midi: Double -> inScale.minBy { kotlin.math.abs(it - midi) }.toDouble() }
val tuned = mutableListOf<TimedNote>()
for (n in notes.map { it.copy(note = snap(it.note)) }) {
    val last = tuned.lastOrNull()
    if (last != null && last.note == n.note && last.end == n.start) tuned[tuned.size - 1] = last.copy(end = n.end)   // same pitch: hold it
    else tuned += n
}

val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
showPatRollOf("read" to patTimed(notes, beats = 8.0), "C minor pentatonic" to patTimed(tuned, beats = 8.0), title = "one drawing, two readings", cycles = 1, pitched = true, channel = "roll")
showPatAudio(patTimed(tuned, beats = 8.0), cycles = 2, cps = 0.25, voice = voice, channel = "audio")

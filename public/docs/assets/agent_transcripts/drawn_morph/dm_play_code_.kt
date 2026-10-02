// The morph out to the heart and back as 32 notes: MIDI 72 at the circle's size, an
// octave lower for every halving. Edit the loop to change what you hear.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.6)
for ((label, partner) in listOf("matched" to matchPoints(from, to).reorder(to), "by list index" to to)) {
    val notes = mutableListOf<TimedNote>()
    val spread0 = spreadOf(from)
    for (k in 0 until 32) {
        val t = if (k < 16) k / 15.0 else (31 - k) / 15.0   // out to the heart, then back
        val midi = 72 + 12 * log2(spreadOf(morphBetween(from, partner, t)) / spread0)
        notes += TimedNote(k * 0.25, k * 0.25 + 0.25, midi)
    }
    println("$label: lowest note %.2f".format(notes.minOf { it.note }))
    showPatAudio(patTimed(notes, beats = 8.0), cycles = 2, cps = 0.25, voice = voice, channel = label)
}

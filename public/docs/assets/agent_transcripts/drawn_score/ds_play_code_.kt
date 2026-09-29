// What each mark means as music is decided here, and you can change it.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)   // eight beats; MIDI 84 at the top
val glideStep = 0.125                                                 // how finely a moving stroke is read

val notes = mutableListOf<TimedNote>()
for (e in score.events) {
    val kind = e.mark.kind
    if (kind == MarkType.TEXT || kind == MarkType.FRAME || kind == MarkType.ARROW) continue   // labels, not notes
    if (e.end <= e.start) continue                                                           // no width, no time
    val moving = (kind == MarkType.STROKE || kind == MarkType.LINE) && kotlin.math.abs(e.endValue - e.value) > 0.5
    if (!moving) {                                                  // a rectangle, or a level line: one note
        notes += TimedNote(e.start, e.end, e.value)
        continue
    }
    var t = e.start                                                 // a glissando: cut it every glideStep
    while (t < e.end - 1e-9) {
        val hit = score.vertical(t).firstOrNull { it.mark.id == e.mark.id && !it.isSpan }
        if (hit != null) notes += TimedNote(t, minOf(t + glideStep, e.end), hit.value, hit.pressure ?: 1.0)
        t += glideStep
    }
}

val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
showPatRollOf("read" to patTimed(notes, beats = 8.0), title = "the score, read", subtitle = "${notes.size} notes", cycles = 1, pitched = true, channel = "roll")
showPatAudio(patTimed(notes, beats = 8.0), cycles = 2, cps = 0.25, voice = voice, channel = "audio")

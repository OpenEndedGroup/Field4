// A MIDI clip written in code, with the sustain pedal, rendered to a WAV in one call.
val notes =
    listOf(48, 55, 60, 64, 67, 72).mapIndexed { i, p ->
        MidiNote(p, startBeats = i * 0.5, durationBeats = 0.4, velocity = 80)
    }
val pedal = listOf(
    MidiControlChange(controller = 64, value = 127, beats = 0.0),
    MidiControlChange(controller = 64, value = 0, beats = 3.5),
)
val clip = MidiClip(tracks = listOf(MidiTrackData(notes = notes, controlChanges = pedal)))
val wav = java.io.File.createTempFile("piano-midi", ".wav")
val r = pianoRenderMidi(
    clip,
    wav,
    piano = Piano(sampleRate = 44_100, preparations = listOf(PianoPreparation.bolt(60..72, at = 0.3, grams = 3.0))),
)
println("wrote ${wav.path}: ${r.seconds.f1} s")
showPianoAudio(r, "midi")
displayOpen("midi")

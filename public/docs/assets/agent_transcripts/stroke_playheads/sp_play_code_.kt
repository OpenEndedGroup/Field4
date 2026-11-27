// The notes as a pattern: a piano roll per track, and all three in an audio player.
val run = StrokePlayheadsDocs.run(StrokePlayheadsDocs.readStrokes(thisBox))
val timed = run.timedNotes()
val rolls =
    ["wave", "rise", "arc"].map { name ->
        name to
            patTimed(
                run.notes.filter { it.track == name }.map { TimedNote(it.start, it.end, it.note, it.gain) },
                beats = 32.0,
            )
    }
showPatRollOf(*rolls.toTypedArray(), title = "three playheads", cycles = 1, pitched = true, channel = "roll")
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
showPatAudio(patTimed(timed, beats = 32.0), cycles = 1, cps = 1.0 / 16.0, voice = voice, channel = "audio")

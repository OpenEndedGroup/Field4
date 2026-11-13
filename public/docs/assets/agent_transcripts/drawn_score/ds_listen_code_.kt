// Play both readings again, then take the sound apart: an onset per strike, a pitch per note.
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))
val tuned = DrawnScoreDocs.pentatonic(notes)
fun listen(audio: PatSoundDocs.Stereo, notes: List<TimedNote>): DrawnScoreDocs.Listening {
    val sampleRate = 44100
    val beats = 8.0
    val cycles = 2
    val cps = 0.25
    val onsetTolerance = 0.030 // seconds: allow the detector's window to lead the strike
    val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
    val mono = audio.mono
    val secondsPerBeat = 1.0 / cps / beats
    val onsets = PatSoundDocs.onsetPeaks(mono, threshold = 0.05).map { it.start.toDouble() / sampleRate }
    val frames = patF0Frames(mono, sampleRate, minHz = 220.0, maxHz = 900.0)
    val measured =
        (0 until cycles).flatMap { cycle ->
            notes.map { note ->
                val start = (cycle * beats + note.start) * secondsPerBeat
                val hold = (note.end - note.start) * secondsPerBeat * voice.sustain
                val onset =
                    onsets.minByOrNull { abs(it - start) }?.takeIf { abs(it - start) <= onsetTolerance } ?: Double.NaN
                // Use only voiced frames wholly inside the held portion, away from its edges.
                val pitches =
                    frames
                        .filter { frame ->
                            val a = frame.startSample.toDouble() / sampleRate
                            val b = (frame.startSample + frame.spanSamples).toDouble() / sampleRate
                            frame.voiced && a >= start + 0.004 && b <= start + hold - 0.002
                        }
                        .map { it.midi }
                        .sorted()
                val midi = if (pitches.isEmpty()) Double.NaN else pitches[pitches.size / 2]
                DrawnScoreDocs.Heard(note, start, onset, midi)
            }
        }
    return DrawnScoreDocs.Listening(measured, onsets.size, frames)
}
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
val heard =
    [notes, tuned].map { reading ->
        val audio = patRenderStereo(patTimed(reading, beats = 8.0), cycles = 2, cps = 0.25, voice = voice)
        listen(audio, reading)
    }
val trace = heard[0].trace()
showSignals(
    "drawn" to trace.map { it.third },
    "heard" to trace.map { it.second },
    x = trace.map { it.first },
    title = "pitch: drawn and heard",
    xTitle = "seconds",
    yTitle = "MIDI",
)
println(heard.joinToString("\n") { it.describe() })

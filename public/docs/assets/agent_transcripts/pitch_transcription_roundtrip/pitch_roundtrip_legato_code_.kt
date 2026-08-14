import afield.four.synth.*

// One oscillator, continuous phase, stepped frequency. The amplitude never moves, so the RMS
// envelope is flat and there is no attack anywhere except the entry from silence.
//
// patRenderStereo CANNOT produce this. It adds one voice per event and gives each its own
// 3 ms attack ramp, so even at sustain = 1.0 a "legato" Pat render still has one amplitude
// rise per note — and an energy detector handles it fine. That was measured, and it is why
// this box uses Dough instead.
val steps = listOf(60.0, 62.0, 64.0, 65.0, 67.0, 69.0, 71.0, 72.0)
val stepSeconds = 0.25
val sr = 48_000
val audio = doughRenderMono(
    patSteppedSine(steps, stepSeconds), (steps.size * stepSeconds).toFloat(), sr
)

val reference = steps.mapIndexed { i, midi ->
    PatRefNote(
        startSample = (i * stepSeconds * sr).toInt(),
        endSample = ((i + 1) * stepSeconds * sr).toInt(),
        midi = midi,
    )
}
val scheduled = reference.map { it.startSample }
val tolerance = (0.030 * sr).toInt()
val frames = patF0Frames(audio, sr, minHz = patMidiToHz(59.0), maxHz = patMidiToHz(73.0))

val energy = patDetectOnsetSamples(audio, sr, stepSeconds)
val pitch = patDetectPitchChangeSamples(audio, sr, stepSeconds, frames)
val merged = patDetectNoteOnsets(audio, sr, stepSeconds, frames)

display("the counterfactual", """
    eight notes, one continuous tone, flat envelope:
      ${patTallyOnsets(energy, scheduled, tolerance).describe("energy  (amplitude envelope)")}
      ${patTallyOnsets(pitch, scheduled, tolerance).describe("pitch   (periodicity)      ")}
      ${patTallyOnsets(merged, scheduled, tolerance).describe("merged  (the union)        ")}
    The energy detector is not misconfigured. The evidence it consumes is not in this signal.
""".trimIndent())

showPatTranscriptionOf(
    audio,
    sampleRate = sr,
    reference = reference,
    minGapSeconds = stepSeconds,
    minHz = patMidiToHz(59.0),
    maxHz = patMidiToHz(73.0),
    label = "legato, no attacks — 48 kHz Dough render",
)

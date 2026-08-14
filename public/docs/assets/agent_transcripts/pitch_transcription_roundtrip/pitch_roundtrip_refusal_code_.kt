import afield.four.synth.*

// Three simultaneous pitches. A monophonic detector's correct output here is a stated
// inability, not the loudest note. `~` is the only thing mini-notation can say for a span
// that sounded and was not named, so the refusal lives in the warnings and the verdict.
val notation = "[60,64,67] ~ [62,65,69] ~"
val cycles = 2
val cps = 0.75
val sustain = 0.85
val audio = patRenderStereo(
    pat(notation), cycles, cps, patVoice(sustain = sustain, decay = 0.03)
).mono

val heard = showPatTranscriptionOf(
    audio,
    reference = patReferenceNotes(notation, cycles, cps, sustain = sustain),
    minGapSeconds = (1.0 / cps) / 4,
    minHz = patMidiToHz(59.0),
    maxHz = patMidiToHz(73.0),
    label = "a triad — outlines with nothing in them",
)

display("named", "${heard.notes.size} notes  ·  pat(\"${heard.miniNotation}\")")
display("verdict", heard.diagnosis.describe())
display("warnings", heard.warnings.joinToString("\n\n"))
display("how it knows", """
    the voicing gate does nearly all of this: three sines in a non-harmonic ratio are not
    periodic at any single lag, so YIN rejects them frame by frame without consulting a
    spectrum. The spectrum is then only asked one question — sparse or dense — which is what
    separates a chord (a few discrete partials) from a snare (a continuum).
    Compare: a sawtooth is a full harmonic stack on ONE note and is NOT refused,
    because timbre is not polyphony.
""".trimIndent())

import afield.four.synth.*

// The notation, and the ground truth Field already owns because it wrote it.
val notation = "60 62 64 65 67 69 71 72"
val cycles = 2
val cps = 1.0
val sustain = 0.85
val voice = patVoice(sustain = sustain, decay = 0.03)

val audio = patRenderStereo(pat(notation), cycles, cps, voice).mono
val reference = patReferenceNotes(notation, cycles, cps, sustain = sustain)

// One call: transcribe, score against the notation, draw both on one axis.
val heard = showPatTranscriptionOf(
    audio,
    reference = reference,
    minGapSeconds = (1.0 / cps) / 8,
    minHz = patMidiToHz(59.0),
    maxHz = patMidiToHz(73.0),
    label = "notated against recovered",
)

display("authored", "pat(\"$notation\") × $cycles cycles at cps $cps")
display("recovered", "pat(\"${heard.miniNotation}\") at cps ${"%.3f".format(heard.cps)}")
display("pitch error", heard.notes
    .joinToString("\n") { "MIDI ${it.midi}  ${"%+.1f".format(it.centsOffset)} cents  conf ${"%.2f".format(it.confidence)}" })
display("summary", heard.describe())

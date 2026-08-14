import afield.four.synth.*

// Sixteen notes in half a second: 31 ms each.
val notation = "60 62 64 65 67 69 71 72 72 71 69 67 65 64 62 60"
val cycles = 2
val cps = 2.0
val sustain = 0.9
val audio = patRenderStereo(
    pat(notation), cycles, cps, patVoice(sustain = sustain, decay = 0.01)
).mono
val reference = patReferenceNotes(notation, cycles, cps, sustain = sustain)

// ─── CHANGE THIS ONE LINE ────────────────────────────────────────────────────────────
// The analysis window is derived from minHz, because YIN needs a frame at least twice the
// longest period it is asked to consider. So the lowest pitch you admit sets the shortest
// note you can resolve, and declaring the register truthfully is worth more than any
// threshold in this package.
//
//   patMidiToHz(59.0)  ≈ 247 Hz → window ≈ 16 ms → these 31 ms notes survive
//   PAT_PITCH_MIN_HZ   =   65 Hz → window ≈ 46 ms → longer than the notes
//
// Swap the line below for `val minHz = PAT_PITCH_MIN_HZ` and re-run. Measured on exactly
// this audio, onset recall falls from 0.91 to 0.03, every recovered note disappears, and
// the verdict below changes from PITCHED to UNRESOLVED — which names the fix rather than
// just reporting silence. The audio does not change. Only what the detector was promised.
val minHz = patMidiToHz(59.0)
// ─────────────────────────────────────────────────────────────────────────────────────

val window = patPitchFrameSpan(PatSoundDocs.SR, minHz)
val heard = showPatTranscriptionOf(
    audio,
    reference = reference,
    minGapSeconds = (1.0 / cps) / 16,
    minHz = minHz,
    maxHz = patMidiToHz(73.0),
    label = "31 ms notes, minHz = ${"%.0f".format(minHz)} Hz",
)

display("resolution", """
    minHz ${"%.1f".format(minHz)} Hz → analysis window $window samples (${"%.1f".format(1000.0 * window / PatSoundDocs.SR)} ms)
    the notes are ${"%.1f".format(1000.0 / cps / 16)} ms long
""".trimIndent())
display("verdict", heard.diagnosis.describe())
display("recovered", "pat(\"${heard.miniNotation}\")")

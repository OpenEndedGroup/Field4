import afield.four.synth.*

// Where the notes actually landed, in samples, against where they were notated.
//
// Seven notes to a cycle on purpose: 48000/7 = 6857, and 6857 % 128 = 105, so twelve of the
// fourteen onsets fall *inside* a 128-frame audio block rather than on its edge. A scheduler that
// wrote parameters once per block — which is what FaustDsp's parameter queue is — would drag every
// one of those forward to the next boundary, up to 110 frames late.
//
// Before believing any onset count: check the signal has onsets. faustSilentGaps must find real
// silence between the notes, or the "onsets" are envelope ripple in a continuous tone.
val render = faustPatternRender("""
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = (os.sawtooth(freq) + os.sawtooth(freq * 1.005)) * 0.5
        : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""", pat("c4 e4 g4 a4 c5 d5 e5"), cycles = 2, cps = 1.0, voices = 8, sustain = 0.5)

val mono = render.mono()
val gaps = faustSilentGaps(mono, render.sampleRate)
val notated = render.notes.map { it.startFrame }.sorted()
val onsets = faustOnsets(mono, render.sampleRate)

println("silent gaps:  " + gaps.size + " (so the signal really has attacks)")
println("notated:      " + notated.size + " notes, " + notated.count { it % 128L != 0L } + " of them off the block grid")
println("detected:     " + onsets.size + " onsets")
if (onsets.size == notated.size) {
    val errors = onsets.mapIndexed { i, o -> o - notated[i].toInt() }
    val median = errors.sorted()[errors.size / 2]
    println("error/note:   " + errors)
    println("median " + median + " frames, jitter +/-" + errors.maxOf { Math.abs(it - median) } +
        " frames, worst " + errors.maxOf { Math.abs(it) } +
        " — against up to 110 if the writes were quantised to the block")
}

display("waveform", afield.four.audio.WavFloatArrayWriter.encodeStereo16(render.left, render.right, render.sampleRate, 0.9f))
showSignals(
    "mono" to (0 until render.frames step 24).map { mono[it] },
    x = (0 until render.frames step 24).map { it.toDouble() / render.sampleRate },
    channel = "waveform", title = "14 notes, 8 voices", xTitle = "seconds", yTitle = "amplitude"
)

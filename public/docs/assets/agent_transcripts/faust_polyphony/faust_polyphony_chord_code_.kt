import afield.four.synth.*

// The assertion that proves the feature rather than the plumbing.
//
// Counting attacks cannot tell polyphony from monophony: a single voice that steals itself
// produces exactly the same number of attacks as three voices. What cannot be faked is the
// spectrum. `[c4,e4,g4]` is one chord — three events on the same arc, so three notes on the same
// frame — whose fundamentals are 130.81, 164.81 and 196.00 Hz. The band 100-230 Hz can hold
// nothing else: the lowest second harmonic in the chord is 261.6 Hz. So a peak in that band is
// somebody's fundamental, and counting them counts notes sounding.
//
// ONE oscillator per voice here, not the detuned pair the other boxes use — that matters, and it
// was found by running this: two sawtooths 0.5% apart put a *beating pair* in the band, so four
// voices reported four peaks and one voice reported two, which muddies the very thing the box is
// for. A measurement wants one partial per note.
val voice = """
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = os.sawtooth(freq) : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
"""

val chord = pat("[c4,e4,g4]")
val poly = faustPatternRender(voice, chord, cycles = 1, cps = 1.0, voices = 4, sustain = 0.9, gain = 0.18)
val one = faustPatternRender(voice, chord, cycles = 1, cps = 1.0, voices = 1, sustain = 0.9, gain = 0.18)

val at = (0.12 * poly.sampleRate).toInt()
println("notated:    " + poly.notes.map { "%.2f".format(it.hz) })
println("4 voices:   " + faustSpectrumPeaks(poly.mono(), at, 32768, poly.sampleRate, 100.0, 230.0, 0.15))
println("1 voice:    " + faustSpectrumPeaks(one.mono(), at, 32768, one.sampleRate, 100.0, 230.0, 0.15))
println("steals:     4 voices " + poly.steals + ", 1 voice " + one.steals)
println("concurrency: 4 voices " + poly.peakConcurrency + ", 1 voice " + one.peakConcurrency)

display("chord", afield.four.audio.WavFloatArrayWriter.encodeStereo16(poly.left, poly.right, poly.sampleRate, 0.9f))
display("one voice", afield.four.audio.WavFloatArrayWriter.encodeStereo16(one.left, one.right, one.sampleRate, 0.9f))

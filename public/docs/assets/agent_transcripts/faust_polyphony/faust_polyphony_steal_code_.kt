import afield.four.synth.*

// The one knob in this feature with an *audible* consequence, and the only way to know what it
// does is to render the same passage twice.
//
// Four notes struck together on TWO voices, so two of them steal. A steal has to make the new note
// attack — and writing gate=1 to a voice whose gate is already up does nothing at all, because
// Faust envelopes trigger on a rising edge. So the gate is dropped and raised again, and THAT is
// where the trade lives: `en.adsr` resets to zero on a trigger, so a one-sample dip drops the
// outgoing note's whole amplitude in one sample. That is a click.
//
// ── CHANGE THIS LINE AND RE-RUN, then play the two players below ────────────────────────────
val steal = 1L        // 1 = the default (on time, clicks) · 1440 = 30ms (no click, 30ms late)
// ───────────────────────────────────────────────────────────────────────────────────────────
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

// Genuine over-subscription, which takes a moment's care to arrange: a pair held for the WHOLE
// cycle, stacked with a second pair entering halfway through. Two voices, four notes, and the late
// pair arrives while the first pair's gates are still up — so it must steal. (Writing this as
// `pat("[c4,e4] [g4,b4]")` looks equivalent and is not: those pairs do not overlap, the first has
// already released, and the render reports 0 steals and measures nothing. The `steals` line below
// is printed for exactly that reason.)
val busy = stack(pat("[c4,e4]"), pat("~ [g4,b4]"))

// The statistic is scale-free on purpose: the largest single-sample step as a fraction of the
// local peak, at the steal, against the same thing in the same note once it has settled. Raw
// deltas would just tell you that a louder, higher sawtooth has a bigger wrap.
fun stealRatio(x: FloatArray, at: Int): Double =
    faustNormalisedStep(x, at - 4, 64) / faustNormalisedStep(x, at + 3000, 1000)

for (frames in listOf(steal, 1L, 1440L).distinct()) {
    val r = faustPatternRender(
        voice, busy, cycles = 1, cps = 1.0, voices = 2, sustain = 0.9, gain = 0.18,
        stealReleaseFrames = frames
    )
    val at = r.notes.map { it.startFrame }.distinct().sorted().last().toInt()
    println(
        "stealReleaseFrames = " + frames + " (" + "%.0f".format(frames * 1000.0 / r.sampleRate) +
            "ms):  " + r.steals + " steals at frame " + at +
            ",  normalised step across it " + "%.2f".format(stealRatio(r.mono(), at)) +
            "x the settled one" + (if (frames == 1L) "   <- clicks" else "   <- clean")
    )
    display(
        "steal=" + frames,
        afield.four.audio.WavFloatArrayWriter.encodeStereo16(r.left, r.right, r.sampleRate, 0.9f)
    )
}
println()
println("A ratio near 1.0 means the biggest one-sample jump is as big as the whole local peak —")
println("that is a discontinuity, not a slope. Stealing is an overload condition either way; the")
println("default prefers the note being on time. See FaustVoiceBank.stealReleaseFrames.")

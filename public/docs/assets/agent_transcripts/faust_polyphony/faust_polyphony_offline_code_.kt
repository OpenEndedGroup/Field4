import afield.four.synth.*

// The identical path with no audio device and no GPU: the same FaustVoiceBank, rendered in 128-frame
// blocks exactly as the audio thread would render it, into a WAV player and a waveform.
//
// `sustain = 0.55` rather than 1.0 on purpose. At 1.0 the gate never falls between adjacent notes,
// and what you get is one continuous tone with a single attack — which sounds wrong and, worse,
// makes an onset detector report envelope ripple as notes. FAUST_PATTERN_SUSTAIN defaults to 0.9
// for exactly that reason.
val render = showFaustPattern("""
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = (os.sawtooth(freq) + os.sawtooth(freq * 1.005)) * 0.5
        : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""",
    pat("c4 ef4 g4 bf4 c5 bf4 g4 ef4"),
    cycles = 2,
    cps = 1.0,
    voices = 8,
    sustain = 0.55,
    automate = mapOf("cutoff" to pat("900 2600 1400 5200").nums())
)

println(
    render.notes.size.toString() + " notes over " + "%.2f".format(render.seconds) + "s, " +
        render.voices + " voices, peak concurrency " + render.peakConcurrency +
        ", " + render.automationApplied + " cutoff steps, " +
        render.steals + " steals, peak " + "%.3f".format(render.peak)
)

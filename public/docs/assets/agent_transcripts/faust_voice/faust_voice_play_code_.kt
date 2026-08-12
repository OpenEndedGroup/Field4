import afield.four.synth.*

// A Faust program, playing. Click this box and the sliders below appear — they are the
// program's own hslider declarations, not something written here. Move `cutoff` while it
// sounds: the value is written straight into the running DSP's WebAssembly memory.
//
// The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
// `FaustEngine.warmInBackground()` in another box first if you would rather wait now.
faust("""
#faust
import("stdfaust.lib");
freq   = hslider("freq [unit:Hz] [scale:log]", 110, 40, 880, 0.01);
cutoff = hslider("cutoff [unit:Hz]", 1200, 60, 6000, 1);
gain   = hslider("gain", 0.18, 0, 0.6, 0.001);
process = os.sawtooth(freq) : fi.lowpass(3, cutoff) * gain <: _,_;
""")

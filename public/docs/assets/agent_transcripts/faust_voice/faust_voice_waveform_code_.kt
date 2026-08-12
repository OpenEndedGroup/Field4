import afield.four.synth.*

// The same program, rendered offline and plotted. Nothing here touches the audio engine —
// which is what makes an honest Faust figure possible in the documentation, and what makes
// this box safe to run on a machine with no sound card.
//
// The source is identical to the diagram box above, so this compile is a cache hit: compiles
// are memoised on a hash of the source.
val program = faustCompile("""
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(3, 1200) * 0.2 <: _,_;
""")

faustWaveform(program, seconds = 0.02)
println("rendered 20ms of " + program.outputs + " channels")

import afield.four.synth.*

// A polyphonic program has to declare `freq` and `gate`. Faust hands its whole declared interface
// back as JSON, so Field can check — and the interesting question is what happens when it fails.
//
// Writing pitch to control index 0 and hoping would produce silence, or a filter sweep, and there
// is nothing in silence to read. So this refuses, and names what is missing, what the program did
// declare, and the line of Faust that would fix it.
val program = faustCompile("""
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 1200, 60, 8000, 1);
freq   = hslider("freq [unit:Hz]", 220, 40, 4000, 0.001);
process = os.sawtooth(freq) : fi.lowpass(2, cutoff) * 0.2 <: _,_;
""")

println("it declares, in the order Faust lists them (alphabetical, not as written):")
program.ui.writable.forEach { println("  " + it.displayLabel + "  " + it.type + "  index " + it.index) }
println()
println(runCatching { FaustPolyControls.of(program) }.exceptionOrNull()?.message ?: "accepted?!")

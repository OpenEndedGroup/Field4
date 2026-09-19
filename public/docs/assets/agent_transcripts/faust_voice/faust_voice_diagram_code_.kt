import afield.four.synth.*

// Faust is a language about signal flow, and the block diagram *is* the signal flow. This
// comes out of the embedded compiler, so there is no `faust -svg` and no command line.
//
// generateAuxFiles throws *after* writing the SVG (it goes on to try a C++ backend that
// is not built), so FaustEngine reads the files regardless of the exception.
val program = faustCompile("""
#faust
import("stdfaust.lib");
process = os.sawtooth(220) : fi.lowpass(3, 1200) * 0.2 <: _,_;
""")

faustDiagram(program)
println("diagram for " + program.name + " — " + program.wasm.size + " bytes of wasm")

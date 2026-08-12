import afield.four.synth.*

// Deliberately broken — `os.sawtoothX` does not exist. Run it and Faust's own message lands
// on the `process` line below rather than on this box's first line.
//
// That is less obvious than it looks. `#` is not a comment in Faust (unlike Ruby, where
// `#sonicpi` can stay in the program Sonic Pi is handed), so the marker is stripped before
// compiling and every line the compiler reports is short by however many lines went with it.
// FaustEngine adds them back before placing the marker.
faust("""
#faust
import("stdfaust.lib");
process = os.sawtoothX(440);
""")

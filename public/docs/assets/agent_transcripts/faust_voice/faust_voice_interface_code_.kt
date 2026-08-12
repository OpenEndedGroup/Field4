import afield.four.synth.*

// What the compiler says about a program's own interface. This is the whole reason the
// controls in the first box are generated rather than written.
//
// `index` is the byte offset of that parameter inside the DSP's WebAssembly memory, which is
// why setting a value is a float store and not a message. And note the order: Faust lists
// controls alphabetically, not in the order they were declared.
val program = faustCompile("""
#faust
import("stdfaust.lib");
freq   = hslider("freq [unit:Hz] [scale:log]", 220, 50, 2000, 0.01);
cutoff = hslider("cutoff [unit:Hz]", 1400, 50, 8000, 1);
gate   = button("gate");
bypass = checkbox("bypass");
level  = hbargraph("level", 0, 1);
env = en.adsr(0.01, 0.2, 0.6, 0.4, gate);
dry = os.sawtooth(freq) : fi.lowpass(2, cutoff) * env * 0.2;
process = (dry : (_ <: attach(_, abs : max ~ *(0.999) : level))) * (1 - bypass) <: _,_;
""")

println(program.inputs.toString() + " in, " + program.outputs + " out")
program.ui.writable.forEach { c ->
    println(
        c.displayLabel + "  " + c.type +
            "  " + c.min + ".." + c.max + " step " + c.step +
            "  init " + c.init + "  " + c.scale + "  index " + c.index
    )
}
program.ui.meters.forEach { println(it.label + "  " + it.type + " (read-only, use launch.meter)") }

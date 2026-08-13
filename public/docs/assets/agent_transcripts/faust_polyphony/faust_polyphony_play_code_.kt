import afield.four.synth.*

// Eight instances of one compiled Faust program, allocated to notes by a pattern. Nothing is
// recompiled per voice: a Faust wasm factory built with internal memory keeps its whole state at
// offset 0 of its own memory, so eight WebAssembly instances of one module are eight independent
// voices, and they all live in one GraalVM context.
//
// `freq` / `gate` / `gain` is Faust's own polyphonic convention — the three names `-nvoices` looks
// for — and they are found by LABEL, never by index. Faust lists controls alphabetically, so index
// 0 in this program is `cutoff`; an allocator that wrote pitch to index 0 would sweep a filter and
// never change a note.
//
// The first compile of a session is slow (13-22s of GraalVM warm-up, then ~600ms). Run
// `FaustEngine.warmInBackground()` in another box first if you would rather wait now.
val inst = faustPoly("""
#faust
import("stdfaust.lib");
cutoff = hslider("cutoff [unit:Hz]", 2600, 120, 9000, 1);
freq   = hslider("freq [unit:Hz]", 220, 30, 4000, 0.001);
gain   = hslider("gain", 0.22, 0, 1, 0.001);
gate   = button("gate");
env = en.adsr(0.004, 0.09, 0.55, 0.03, gate);
process = (os.sawtooth(freq) + os.sawtooth(freq * 1.005)) * 0.5
        : fi.resonlp(cutoff, 1.4, 1) * env * gain <: _,_;
""", voices = 8)

// Two patterns, one timeline. The chords are notes; the cutoff is a parameter. Both are scheduled
// in frames and applied inside the audio block rather than at its edge, so a filter that opens on
// the beat opens on the *sample* of the beat.
//
// The slider that appears below is `cutoff` only. `freq`, `gate` and `gain` are deliberately not
// in the control group: the notes write them, and a slider fighting the notes for them would look
// broken.
inst.play(
    pat("[c4,ef4,g4] [af3,c4,ef4] [bf3,d4,f4] [ef4,g4,bf4]"),
    cps = 0.5,
    sustain = 0.85,
    automate = mapOf("cutoff" to pat("900 2600 1400 5200").nums())
)

inst

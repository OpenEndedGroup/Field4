// Hear it.
//
// The same lowering, played. Sonic Pi 5 has to be installed; Field starts it itself — owned mode,
// so there is no Sonic Pi window and this box is the buffer. The box stays lit while the music
// runs, and the stop button stops it.
//
// Turn your volume down before you run this.

// Three things a layer can carry beyond its pattern: which synth its bare notes play on, an FX
// chain wrapped around its live_loop, and parameters from the pattern algebra. Every FX name and
// every FX opt below is checked against the installed vocabulary when the layer is built — Sonic
// Pi's answer to `with_fx :revreb` is a runtime error on the loop's own thread, minutes in.
//
// Field's octave numbering puts middle C at c5 = 60, one octave below Sonic Pi's :c4. That is why
// the lowering emits MIDI numbers rather than :symbols — the number the pattern meant survives.
sonicPiPattern(
    sonicPiLayer("drums", pat("bd ~ [bd bd bd] bd")),
    sonicPiLayer(
        "hats",
        pat("[hh hh hh hh]").degradeBy(0.35, salt = 4) with gain(0.35),
        fx = listOf(sonicPiFx("echo", "phase" to 0.125, "decay" to 2, "mix" to 0.3))
    ),
    sonicPiLayer(
        "bass",
        pat("<e3 g3> ~ e4 ~").every(4) { it.rev() } with cutoff(700.0),
        synth = "tb303",
        fx = listOf(sonicPiFx("reverb", "room" to 0.7, "mix" to 0.4))
    ),
    cps = 0.5,
    cycles = 4
)

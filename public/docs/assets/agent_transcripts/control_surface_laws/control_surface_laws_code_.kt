// Four sliders over the same four decades, one per law. Click this box, open the output
// disclosure, and drag them.
//
// The point is the pair `cutoff` / `cutoff, linear`: identical ranges, and 1 kHz sits at 57%
// of the log track against 4.9% of the linear one. On the linear slider every note below A2
// is inside the first pixel of travel, which is not a resolution problem — no number of
// pixels fixes a 200 Hz jump per pixel — it is the wrong shape.
var cutoff = 1000.0
var attack = 0.05
var pan = 0.0
var linearCutoff = 1000.0

controlGroup("laws") {
    // Equal travel is equal ratio. Note the readout: 20.00 at the bottom and 20000 at the
    // top, from one control, because a nonlinear law formats by significant digits.
    slider("cutoff", min = 20.0, max = 20000.0, initialValue = cutoff, law = ControlLaw.LOGARITHMIC) {
        cutoff = it
    }
    slider("cutoff, linear", min = 20.0, max = 20000.0, initialValue = linearCutoff) {
        linearCutoff = it
    }
    // A taper: happy with a zero minimum, where a logarithmic law is not. Most of the track
    // is spent on the short attacks, which is where the ear is.
    slider("attack", min = 0.0, max = 2.0, initialValue = attack, law = ControlLaw.EXPONENTIAL, lawExponent = 3.0) {
        attack = it
    }
    // Two tapers about a centre, so dead centre is exactly mid-travel and you can land on it.
    slider("pan", min = -1.0, max = 1.0, initialValue = pan, law = ControlLaw.BIPOLAR) {
        pan = it
    }
}

// Where each law puts a handle, in percent of the track. The same numbers the browser uses.
val log = ControlLawSpec(ControlLaw.LOGARITHMIC, 20.0, 20000.0)
val linear = ControlLawSpec(ControlLaw.LINEAR, 20.0, 20000.0)
listOf(20.0, 100.0, 440.0, 1000.0, 5000.0, 20000.0).forEach { hz ->
    println(
        "%8.1f Hz   log %5.1f%%   linear %5.1f%%".format(
            hz, log.normalizedFor(hz) * 100, linear.normalizedFor(hz) * 100
        )
    )
}
println()
println("a LOGARITHMIC law over a range that starts at zero is refused, not repaired:")
println(
    runCatching { ControlLawSpec(ControlLaw.LOGARITHMIC, 0.0, 20000.0).validate("cutoff") }
        .exceptionOrNull()?.message ?: "(nothing was thrown — that would be the bug)"
)

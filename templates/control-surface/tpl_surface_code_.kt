// A performer's surface: a logarithmic slider, a bipolar one, a momentary gate and a meter.
// Run it, then read `cutoff`, `pan` and `gate` from the code below it, or point the meter's
// `source` at a number of your own.
var cutoff = 1000.0
var pan = 0.0
var gate = false

val start = System.nanoTime()
fun level() = 0.5 + 0.48 * Math.sin((System.nanoTime() - start) / 1e9 * 2.2)

controlGroup("surface") {
    // Equal travel is an equal ratio, so 1 kHz sits near the middle of 20 Hz-20 kHz.
    slider("cutoff", min = 20.0, max = 20000.0, initialValue = cutoff, law = ControlLaw.LOGARITHMIC) { cutoff = it }
    // Dead centre is exactly mid-travel.
    slider("pan", min = -1.0, max = 1.0, initialValue = pan, law = ControlLaw.BIPOLAR) { pan = it }
    // Down while held, up on release, even when the pointer leaves the button.
    button("gate") { down -> gate = down }
    meter("level", min = 0.0, max = 1.0, source = { level() })
}
displayOpen("surface")

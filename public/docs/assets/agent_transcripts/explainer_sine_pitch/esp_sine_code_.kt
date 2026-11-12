// One sine wave, drawn and heard. How often it repeats is its pitch.
val pitch = Param(220f)
val loudness = Param(0f)

// Twenty milliseconds of the wave, 640 pixels wide.
fun draw(hz: Double) = lines(
    fline {
        moveTo(0.0, 120.0)
        for (x in 1..640) lineTo(x.toDouble(), 120.0 - 90.0 * sin(2 * PI * hz * 0.02 * x / 640))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    },
    channel = "wave",
    w = 640.0,
    h = 240.0,
)

draw(220.0)
controlGroup {
    slider("frequency", 110.0, 880.0, step = 1.0, initialValue = 220.0, law = ControlLaw.LOGARITHMIC) {
        pitch.base = it.toFloat()
        draw(it)
    }
    checkbox("sound") { loudness.base = if (it) 0.15f else 0f }
}

audio(SineOsc(pitch), gain = loudness)

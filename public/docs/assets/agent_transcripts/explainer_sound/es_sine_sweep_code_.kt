// One sine wave, drawn and heard. How often it repeats is its pitch; how far it swings, its loudness.
val pitch = Param(220f)
val level = Param(0f)
var hz = 220.0
var amplitude = 0.8
var on = false

// Twenty milliseconds of the wave, 640 pixels wide.
// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun draw() = lines(
    frame,
    fline {
        moveTo(0.0, 120.0)
        for (x in 1..640) lineTo(x.toDouble(), 120.0 - 100.0 * amplitude * sin(2 * PI * hz * 0.02 * x / 640))
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    },
    channel = "wave",
    w = 640.0,
    h = 240.0,
)

fun update() {
    pitch.base = hz.toFloat()
    level.base = if (on) (0.15 * amplitude).toFloat() else 0f
    draw()
}

update()
controlGroup {
    slider("frequency", 20.0, 20000.0, step = 1.0, initialValue = 220.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        update()
    }
    slider("amplitude", 0.0, 1.0, step = 0.01, initialValue = 0.8) {
        amplitude = it
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio(SineOsc(pitch), gain = level)

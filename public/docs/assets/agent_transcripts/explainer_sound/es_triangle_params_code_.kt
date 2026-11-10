// One repeating triangle and the three numbers that place it. The faint line is the same triangle
// with no shift, for comparison; the sound follows amplitude and frequency.
var amplitude = 0.6
var hz = 220.0
var phase = 0.0
var on = false
val pitch = Param(220f)
val level = Param(0f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 240.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun triangle(t: Double) = 1 - 4 * abs(t - floor(t + 0.5))

fun wave(shift: Double, c: Vec4) = fline {
    moveTo(0.0, 120.0 - 100 * amplitude * triangle(-shift))
    for (x in 1..640) lineTo(x.toDouble(), 120.0 - 100 * amplitude * triangle(hz * 0.02 * x / 640 - shift))
    thickness = 3.0
    color = c
}

fun update() {
    pitch.base = hz.toFloat()
    level.base = if (on) (0.15 * amplitude).toFloat() else 0f
    lines(
        frame,
        wave(0.0, vec(1.0, 1.0, 1.0, 0.35)),
        wave(phase, vec(0.4, 0.9, 1.0, 1.0)),
        channel = "wave",
        w = 640.0,
        h = 240.0,
    )
}

update()
controlGroup {
    slider("amplitude", 0.0, 1.0, step = 0.01, initialValue = 0.6) {
        amplitude = it
        update()
    }
    slider("frequency", 55.0, 880.0, step = 1.0, initialValue = 220.0, law = ControlLaw.LOGARITHMIC) {
        hz = it
        update()
    }
    slider("phase", 0.0, 1.0, step = 0.01, initialValue = 0.0) {
        phase = it
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio(TriOsc(pitch), gain = level)

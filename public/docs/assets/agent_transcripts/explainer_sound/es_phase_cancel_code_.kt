// Two identical tones, the second slid along by "shift" (a fraction of a cycle), and their sum.
var shift = 0.0
var on = false
val offset = Param(0f) // the second tone's shift, in radians
val level = Param(0f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun row(y: Double, scale: Double, f: (Double) -> Double, c: Vec4) = fline {
    moveTo(0.0, y - scale * f(0.0))
    for (x in 1..640) lineTo(x.toDouble(), y - scale * f(0.02 * x / 640))
    thickness = 3.0
    color = c
}

fun update() {
    offset.base = (2 * PI * shift).toFloat()
    level.base = if (on) 0.15f else 0f
    val first = { t: Double -> sin(2 * PI * 220 * t) }
    val second = { t: Double -> sin(2 * PI * 220 * t + 2 * PI * shift) }
    lines(
        frame,
        row(46.0, 38.0, first, vec(0.4, 0.9, 1.0, 1.0)),
        row(134.0, 38.0, second, vec(0.4, 0.9, 1.0, 0.6)),
        fline {
            moveTo(0.0, 182.0)
            lineTo(640.0, 182.0)
            thickness = 1.0
            color = vec(1.0, 1.0, 1.0, 0.35)
        },
        row(240.0, 28.0, { t -> first(t) + second(t) }, vec(1.0, 0.6, 0.2, 1.0)),
        channel = "wave",
        w = 640.0,
        h = 300.0,
    )
}

update()
controlGroup {
    slider("shift", 0.0, 1.0, step = 0.01, initialValue = 0.0) {
        shift = it
        update()
    }
    checkbox("sound") {
        on = it
        update()
    }
}

audio((SineOsc(Param(220f)) + SineOsc(Param(220f), phaseMod = offset)) / 2.0, gain = level)

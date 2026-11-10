// A plate that jumps. Hold "jump" to move it forward by "distance"; tick "repeat" and it jumps back
// and forth by itself, "rate" times a second. With "sound" on you hear the air the plate pushes.
var distance = 0.5
var rate = 4.0
var held = false
var repeat = false
val level = Param(0f)
val swing = Param(0f) // how far the repeated motion goes
val offset = Param(0f) // where "jump" holds the plate
val pace = Param(4f)

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline {
    rect(0.0, 0.0, 640.0, 300.0)
    thickness = 1.0
    color = vec(1.0, 1.0, 1.0, 0.0)
}

fun position(t: Double) =
    (if (held) distance else 0.0) + (if (repeat) distance * (if ((t * rate) % 1.0 < 0.5) 1.0 else -1.0) else 0.0)

fun update() {
    swing.base = if (repeat) distance.toFloat() else 0f
    offset.base = if (held) distance.toFloat() else 0f
    pace.base = rate.toFloat()
}

// The plate, seen from the side, and a quarter of a second of its position below it.
fun draw(now: Double) {
    // Faster than a few jumps a second the eye cannot follow it, so the plate is drawn as a blur
    // across the whole of its travel, the way a fast-moving thing looks.
    val blur = repeat && rate > 6
    val x = if (blur) 120 - 60 * distance + (if (held) 60 * distance else 0.0) else 120 + 60 * position(now)
    val plate = fline {
        rect(x, 20.0, if (blur) 12 + 120 * distance else 12.0, 120.0)
        filled = true
        stroked = false
        color = vec(1.0, 0.6, 0.2, if (blur) 0.45 else 1.0)
    }
    val rest = fline {
        moveTo(126.0, 14.0)
        lineTo(126.0, 146.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    val trace = fline {
        moveTo(0.0, 230.0 - 60 * position(now - 0.25))
        for (i in 1..640) lineTo(i.toDouble(), 230.0 - 60 * position(now - 0.25 + 0.25 * i / 640))
        thickness = 2.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    val axis = fline {
        moveTo(0.0, 230.0)
        lineTo(640.0, 230.0)
        thickness = 1.0
        color = vec(1.0, 1.0, 1.0, 0.35)
    }
    lines(frame, plate, rest, axis, trace, channel = "plate", w = 640.0, h = 300.0)
}

draw(0.0)
controlGroup {
    keys("jump") { _, down ->
        held = down
        update()
    }
    slider("distance", 0.0, 1.0, step = 0.01, initialValue = 0.5) {
        distance = it
        update()
    }
    checkbox("repeat") {
        repeat = it
        update()
    }
    checkbox("sound") { level.base = if (it) 0.15f else 0f }
    slider("rate", 1.0, 440.0, step = 1.0, initialValue = 4.0, law = ControlLaw.LOGARITHMIC) {
        rate = it
        update()
    }
}

val start = AnimationClock.nowSeconds()
// A held offset makes no sound, only its changes do, so a high-pass filter takes the offset out.
loop { draw(AnimationClock.nowSeconds() - start) } +
    audio((SquareOsc(pace) * swing + offset) / hpf(15, 0.1), gain = level)

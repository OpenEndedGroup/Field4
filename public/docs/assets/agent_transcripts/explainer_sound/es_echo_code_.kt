// You clap, standing "distance to the wall" metres from a wall. The pulse travels there and back at
// 343 m/s, shown at its real speed. Below, a timeline: the clap at 0, its echo when it returns; the
// shaded part is the first 50 ms, inside which the ear hears the two as one sound.
var distance = 20.0
var clapped = -10.0
var time = 0.0
val gate = Gate()
val lag = Param(2 * 20f / 343)
val echoLevel = Param(0.15f)

fun echoGain(d: Double) = (0.6 * 4 / (2 * d)).coerceAtMost(0.6)

fun draw() {
    val metre = 600 / 60.0                        // sixty metres across
    val wall = 20 + metre * distance
    val travelled = 343 * (time - clapped)        // metres the pulse has gone
    val out = travelled <= distance
    val front = if (out) 20 + metre * travelled else wall - metre * (travelled - distance)
    val pulse = if (travelled > 2 * distance) null else fline {
        moveTo(front, 40.0); lineTo(front, 160.0)
        thickness = 3.0
        color = if (out) vec(0.4, 0.9, 1.0, 1.0) else vec(1.0, 0.6, 0.2, 1.0)
    }
    val wallLine = fline { rect(wall, 30.0, 8.0, 140.0); filled = true; stroked = false; color = vec(1.0, 1.0, 1.0, 0.7) }
    val you = fline { circle(20.0, 100.0, 8.0); filled = true; stroked = false; color = vec(1.0, 1.0, 1.0, 0.9) }
    // The timeline: 0 to 400 ms across the figure.
    val ms = 600 / 400.0
    val returns = 1000 * 2 * distance / 343
    val merged = fline { rect(20.0, 205.0, 50 * ms, 50.0); filled = true; stroked = false; color = vec(1.0, 1.0, 1.0, 0.12) }
    val axis = fline { moveTo(20.0, 255.0); lineTo(620.0, 255.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.35) }
    val clap = fline { moveTo(20.0, 255.0); lineTo(20.0, 210.0); thickness = 3.0; color = vec(0.4, 0.9, 1.0, 1.0) }
    val echo = fline {
        moveTo(20 + ms * returns.coerceAtMost(400.0), 255.0)
        lineTo(20 + ms * returns.coerceAtMost(400.0), 255 - 45 * (echoGain(distance) / 0.6).coerceAtLeast(0.3))
        thickness = 3.0
        color = vec(1.0, 0.6, 0.2, 1.0)
    }
    val frame = fline { rect(0.0, 0.0, 640.0, 280.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }
    lines(listOfNotNull(frame, wallLine, you, pulse, merged, axis, clap, echo), channel = "wall", w = 640.0, h = 280.0)
}

draw()
controlGroup {
    keys("clap", letters = "c") { _, down ->
        if (down) { clapped = time; gate.open() } else gate.close()
    }
    slider("distance to the wall", 2.0, 60.0, step = 0.5, initialValue = 20.0) {
        distance = it
        lag.base = (2 * it / 343).toFloat()
        echoLevel.base = echoGain(it).toFloat()
    }
}

val start = AnimationClock.nowSeconds()
// A clap is a short burst of noise; its echo is the same burst, later and quieter.
val burst = WhiteNoise() * perc(0.06, 0.001, gate)
loop {
    time = AnimationClock.nowSeconds() - start
    draw()
} + audio(burst + burst / Delay(lag, Param(0f), Param(1f), maxDelaySec = 1f) * echoLevel, gain = 0.15f)

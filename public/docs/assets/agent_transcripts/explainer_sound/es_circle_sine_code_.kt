// A point going round a circle, and its height over time. The trace scrolls left, newest at the circle.
var radius = 0.8
var speed = 0.5      // turns per second
var start = 0.0      // where the point starts, as a fraction of a turn
var angle = 0.0
val heights = mutableListOf<Double>()

// An invisible frame the size of the figure, so its bounds, and so its scale, never change.
val frame = fline { rect(0.0, 0.0, 640.0, 280.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }

fun draw() {
    val cx = 110.0
    val cy = 140.0
    val r = 100 * radius
    val turn = 2 * PI * (angle + start)
    val px = cx + r * cos(turn)
    val py = cy - r * sin(turn)
    val guide = vec(1.0, 1.0, 1.0, 0.35)
    val circle = fline { circle(cx, cy, r); thickness = 1.5; color = guide }
    val spoke = fline { moveTo(cx, cy); lineTo(px, py); thickness = 1.5; color = guide }
    val level = fline { moveTo(px, py); lineTo(240.0, py); thickness = 1.0; color = guide }
    val dot = fline { circle(px, py, 6.0); filled = true; stroked = false; color = vec(1.0, 0.6, 0.2, 1.0) }
    val axis = fline { moveTo(240.0, cy); lineTo(640.0, cy); thickness = 1.0; color = guide }
    val trace = fline {
        heights.reversed().forEachIndexed { i, h -> if (i == 0) moveTo(240.0, cy - h) else lineTo(240.0 + i, cy - h) }
        thickness = 3.0
        color = vec(0.4, 0.9, 1.0, 1.0)
    }
    lines(frame, axis, circle, spoke, level, trace, dot, channel = "circle", w = 640.0, h = 280.0)
}

draw()
controlGroup {
    slider("radius", 0.1, 1.2, step = 0.01, initialValue = 0.8) { radius = it }
    slider("turns per second", 0.1, 2.0, step = 0.01, initialValue = 0.5) { speed = it }
    slider("start", 0.0, 1.0, step = 0.01, initialValue = 0.0) { start = it }
}

// The trace takes one sample every sixtieth of a second, however unevenly frames arrive.
var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    while (clock < now) {
        clock += 1 / 60.0
        angle += speed / 60
        heights += 100 * radius * sin(2 * PI * (angle + start))
    }
    while (heights.size > 400) heights.removeAt(0)
    draw()
}

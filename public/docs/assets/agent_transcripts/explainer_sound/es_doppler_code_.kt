// A source moving left to right past a listener (orange), sending out rings that spread at the
// speed of sound. Time is slowed a hundred times, so a ring grows by a fraction of the gap between
// rings in each painted frame even at fifteen frames a second. Ahead of the source the rings crowd together, behind
// it they spread out. With "sound" on you hear a 440 Hz tone at the pitch the listener receives.
val metre = 64.0
val c = 343.0
val slow = 100.0          // picture time runs this many times slower than real time
val every = 0.27          // seconds of picture time between rings
var speed = 150.0          // metres per second
var on = false
var time = 0.0
val emitted = mutableListOf<Pair<Double, Double>>()   // (time, x in metres) of each ring
var x = 0.0
var nextRing = 0.0
val pitch = Param(440f)
val level = Param(0f)
val listener = Pair(6.0, -2.5)  // metres: right of centre, below the path

fun ring(cx: Double, cy: Double, r: Double) = fline {
    var drawing = false
    for (i in 0..72) {
        val a = 2 * PI * i / 72
        val px = cx + r * cos(a)
        val py = cy + r * sin(a)
        val inside = px in 0.0..640.0 && py in 0.0..300.0
        if (inside && drawing) lineTo(px, py) else if (inside) moveTo(px, py)
        drawing = inside
    }
}

fun step(dt: Double) {
    time += dt
    x += speed / slow * dt
    if (x > 10.5) { x = -0.5; emitted.clear(); nextRing = time }
    // A ring every few tenths of a second of picture time, placed where the source was when it left.
    while (nextRing <= time) {
        emitted += nextRing to x - speed / slow * (time - nextRing)
        nextRing += every
    }
    emitted.removeAll { (t0, _) -> c / slow * (time - t0) * metre > 800 }
    // The pitch heard now, from the source's motion towards the listener.
    val dx = listener.first - x
    val dy = listener.second
    val towards = speed * dx / sqrt(dx * dx + dy * dy)
    pitch.base = (440 * c / (c - towards.coerceAtMost(0.95 * c))).toFloat()
    level.base = if (on) 0.15f else 0f
}

fun draw() {
    val y = 130.0
    val rings = emitted.map { (t0, x0) -> ring(metre * x0, y, c / slow * (time - t0) * metre).apply { thickness = 1.5; color = vec(0.4, 0.9, 1.0, 0.8) } }
    val source = fline { circle(metre * x, y, 8.0); filled = true; stroked = false; color = vec(1.0, 1.0, 1.0, 0.9) }
    val ear = fline { circle(metre * listener.first, y - metre * listener.second, 9.0); filled = true; stroked = false; color = vec(1.0, 0.6, 0.2, 1.0) }
    val frame = fline { rect(0.0, 0.0, 640.0, 300.0); thickness = 1.0; color = vec(1.0, 1.0, 1.0, 0.0) }
    lines([frame] + rings + source + ear, channel = "street", w = 640.0, h = 300.0)
}

draw()
controlGroup {
    slider("speed", 0.0, 300.0, step = 1.0, initialValue = 150.0) { speed = it }
    checkbox("sound") { on = it }
}

var clock = AnimationClock.nowSeconds()
loop {
    val now = AnimationClock.nowSeconds()
    step(now - clock)
    clock = now
    draw()
} + audio(SineOsc(pitch), gain = level)

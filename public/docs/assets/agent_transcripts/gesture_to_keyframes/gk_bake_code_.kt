var p = Vector2f(0f, 0f)
lateinit var pad: GestureHandle

// A figure eight drawn by a slightly unsteady hand: 240 samples over two seconds.
val rnd = java.util.Random(7)
val eight = 240 { i ->
    val a = i / 239.0 * 2 * Math.PI
    val wobble = { 0.008 * (rnd.nextDouble() - 0.5) }
    i / 239.0 * 2.0 to Vector2f((0.7 * Math.sin(a) + wobble()).toFloat(), (0.5 * Math.sin(2 * a) + wobble()).toFloat())
}

val x = keyframes("lanes", "x")
val y = keyframes("lanes", "y")

fun bake() {
    val (bx, by) = bakeGesture(pad, tolerance = 0.02, channel = "lanes")
    println("baked: ${pad.fit(bx, by)}")
}

controlGroup("gesture") {
    pad = gesture("take", initialTake = eight) { p = it }
    button("bake take") { if (it) bake() }
    button("replay lanes") { if (it) { pad.setTake(x, y); println("pad now plays the lanes: ${pad.samples.size} samples") } }
}
displayOpen("gesture")

if (x.isEmpty() || y.isEmpty()) bake() else println("lanes kept: ${pad.fit(x, y)}")

val start = System.nanoTime()
loop {
    val d = pad.duration
    if (d > 0) {
        val t = ((System.nanoTime() - start) / 1e9) % d
        pad.at(t)?.let { p = it }
        pad.playhead(t)
        x.setTime(t)
        y.setTime(t)
    }
}

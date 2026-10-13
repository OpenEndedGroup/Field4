import afield.paint.*

var p = Vector2f(0f, 0f)
lateinit var pad: GestureHandle

// A wave a pen might draw across the pad in 1.6 s: pressing hardest through the middle,
// leaning right and toward you, with a little tremor.
val rnd = java.util.Random(11)
val wave = (0 until 190).map { i ->
    val u = i / 189.0
    PenSample(1.6 * u, -0.85 + 1.7 * u + 0.006 * (rnd.nextDouble() - 0.5), 0.35 * sin(2 * PI * u) + 0.006 * (rnd.nextDouble() - 0.5),
        pressure = 0.05 + 0.95 * sin(PI * u), tiltX = 35.0, tiltY = 20.0)
}

val x = keyframes("lanes", "x")
val y = keyframes("lanes", "y")
val pressure = keyframes("lanes", "pressure")

fun bake() {
    val (bx, by) = bakeGesture(pad, tolerance = 0.01, channel = "lanes")
    pressure.setKeys(pad.penLane("pressure", tolerance = 0.03))
    println("baked ${pad.samples.size} samples: ${pad.fit(bx, by)}, pressure ${pressure.getKeyframeCount()} keys")
}

fun show(paintIt: (PaintCanvas) -> TimedGesture) {
    if (!PaintCanvas.isAvailable) { markdown(PaintLibrary.describe()); return }
    PaintCanvas(600, aspect = 1.0, groundColor = rgb(0.97, 0.96, 0.92), sizeMm = 200.0, seed = 4).use { c ->
        val g = paintIt(c)
        c.stroke(g, c.dynamicBrush(BrushKind.Round, 40.0, hairs = 32).load(rgb(0.25, 0.15, 0.1), stiff = 0.5), seed = 4)
        println("painted %.2f s, pressure %.2f to %.2f".format(g.duration, g.samples.minOf { it.pressure }, g.samples.maxOf { it.pressure }))
        display("main", c.png(relief = 0.6))
    }
}
fun paintLanes() = show { c -> c.gesture(x, y, pad.xRange, pad.yRange, pressureLane = pressure) }
fun paintTake() = show { c -> c.gesture(pad) }

controlGroup("gesture") {
    pad = gesture("pen", pen = true) { p = it }
    button("bake take") { if (it) { bake(); paintLanes() } }
    button("paint lanes") { if (it) paintLanes() }
    button("paint take") { if (it) paintTake() }
    button("replay lanes") { if (it) { pad.setTake(x, y); println("the pad plays the lanes: ${pad.samples.size} samples") } }
}
displayOpen("gesture")

if (!pad.hasTake) pad.setTake(wave)
if (x.isEmpty() || y.isEmpty()) bake() else println("lanes kept: ${pad.fit(x, y)}")
paintLanes()

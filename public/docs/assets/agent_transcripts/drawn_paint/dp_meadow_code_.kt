import afield.paint.*
import kotlin.random.Random

run {
if (!PaintCanvas.isAvailable) {
    markdown(PaintLibrary.describe())
    return@run
}
// A meadow at evening, from 560 pen strokes on the drawing. The strokes are drawn by code here
// (a seeded random hand) into the region below; draw your own there instead and paint those.
val region = Rect(40.0, 1460.0, 1600.0, 1000.0)
val X = region.x
val Y = region.y
val rnd = Random(7)
fun r(a: Double, b: Double) = rnd.nextDouble(a, b)
fun sweep(x: Double, y: Double, w: Double, dy: Double = 0.0) =
    FLine().moveTo(X + x, Y + y).cubicTo(X + x + w * 0.3, Y + y - 20, X + x + w * 0.7, Y + y + 20, X + x + w, Y + y + dy)
notation {
    for (i in 0 until 18) stroke(sweep(r(-160.0, 0.0), 20.0 + i * 26 + r(-6.0, 6.0), 1760.0, r(-10.0, 10.0)),   // sky
        name = "sky $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    for (c in 0 until 5) {                                                                                  // clouds
        val cx = r(150.0, 1350.0); val cy = r(90.0, 300.0)
        for (k in 0 until 7) stroke(sweep(cx + r(-120.0, 80.0), cy + r(-35.0, 25.0), r(80.0, 220.0), 4.0),
            name = "cloud $c.$k", pressure = { 0.2 + 0.8 * sin(PI * it) })
    }
    for (i in 0 until 5) stroke(sweep(-80.0, 500.0 + i * 10, 1760.0, -40.0),                                 // far hills
        name = "hill $i", pressure = { 0.4 + 0.6 * sin(PI * it) })
    for (i in 0 until 22) stroke(sweep(-80.0, 540.0 + i * 22, 1760.0),                                       // the field
        name = "field $i", pressure = { 0.7 + 0.3 * sin(PI * it) })
    for (i in 0 until 420) {                                                                                // grass
        val y = r(560.0, 1000.0); val d = (y - 540) / 460; val x = r(-20.0, 1620.0)
        val h = r(40.0, 120.0) + 300 * d * d; val lean = r(-0.3, 0.5) * h
        stroke(FLine().moveTo(X + x, Y + y).cubicTo(X + x + lean * 0.1, Y + y - h * 0.5, X + x + lean * 0.5, Y + y - h * 0.9, X + x + lean, Y + y - h),
            name = "grass $i", pressure = { 1.0 - 0.95 * it })                                              // flicked up from the root
    }
    for (i in 0 until 60) {                                                                                 // poppies
        val y = r(570.0, 990.0); val x = r(40.0, 1560.0); val s = 4.0 + 22 * (y - 540) / 460
        stroke(FLine().moveTo(X + x - s, Y + y).cubicTo(X + x - s * 0.3, Y + y - s, X + x + s * 0.5, Y + y - s * 0.8, X + x + s, Y + y + s * 0.2),
            name = "poppy $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}

val canvas = PaintCanvas(1200, aspect = 1.6, ground = PaintGround.OilRedGround, view = region)
val strokes = notation.strokes.filter { it.bounds.intersects(region) }
fun named(p: String) = strokes.filter { it.label?.startsWith(p) == true }
fun depth(s: Mark) = ((s.start!!.y - Y - 540) / 460).coerceIn(0.0, 1.0)   // 0 at the horizon, 1 at the front
fun paint(s: Mark, b: PaintBrush, p: Double = 1.0, shake: Double = 1.0) =
    canvas.stroke(s.toFLine(), b, pressure = p..p, swell = s.pressuresAlong(32), shake = shake)

val sky = canvas.brush(BrushKind.Filbert, width = 60.0)
named("sky").forEachIndexed { i, s ->
    val t = i / 17.0   // cobalt at the top, warming to Naples yellow and a little rose at the horizon
    paint(s, sky.load(mapOf("lead white" to 3.0, "cobalt blue" to 2.0 * (1 - t) * (1 - t), "Naples yellow" to 2.5 * t * t, "rose madder" to 0.4 * t), wipe = 0.5))
}
val cloud = canvas.brush(BrushKind.HogFlat, width = 34.0)
named("cloud").forEach { paint(it, cloud.load(Tubes.mix(mapOf("lead white" to 4.0, "Naples yellow" to 0.4, "rose madder" to 0.1)), hiding = 0.5, wipe = 0.3), shake = 0.8) }
val hill = canvas.brush(BrushKind.HogFlat, width = 26.0)
named("hill").forEachIndexed { i, s -> paint(s, hill.load(mapOf("lead white" to 3.0 - i * 0.4, "cobalt blue" to 1.0, "green earth" to 1.0 + i * 0.4), wipe = 0.6)) }
val field = canvas.brush(BrushKind.HogFlat, width = 50.0)
named("field").forEachIndexed { i, s ->
    val t = i / 21.0
    paint(s, field.load(mapOf("green earth" to 2.0, "yellow ochre" to 2.0 * (1 - t), "raw umber" to 1.5 * t, "lead white" to 1.0 * (1 - t)), wipe = 0.5))
}
canvas.wait(40.0)   // let the field set a little before the grass goes into it
val grass = canvas.brush(BrushKind.Round, width = 10.0)
val greens = listOf(mapOf("viridian" to 1.0, "yellow ochre" to 1.5), mapOf("green earth" to 2.0, "chrome yellow" to 1.0),
    mapOf("viridian" to 1.0, "raw umber" to 1.0), mapOf("yellow ochre" to 2.0, "lead white" to 1.0),
    mapOf("Prussian blue" to 0.2, "chrome yellow" to 2.0), mapOf("Naples yellow" to 2.0, "green earth" to 1.0))
named("grass").sortedBy { it.start!!.y }.forEachIndexed { i, s -> paint(s, grass.load(greens[i % greens.size], wipe = 0.5), 0.4 + 0.6 * depth(s), shake = 0.6) }
val poppy = canvas.brush(BrushKind.Filbert, width = 24.0)
named("poppy").sortedBy { it.start!!.y }.forEach { s -> paint(s, poppy.load(mapOf("vermilion" to 3.0, "cadmium red" to 1.0, "lead white" to 0.2), wipe = 0.9), 0.3 + 0.7 * depth(s)) }
canvas.dry()
canvas.glaze(rgb(0.6, 0.45, 0.2), coats = 0.12)   // a thin warm varnish over the whole thing
display("main", canvas.png())
}

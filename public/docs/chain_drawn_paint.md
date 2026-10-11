# Drawn Strokes Become Paint

Every pen stroke on the drawing layer is a line of points, and a tablet pen records how hard it was
pressed at each one. `notation` reads both back, and `PaintCanvas` drags a simulated oil brush along
the points, pressing as hard as the pen did. The paint is mixed from tube colours, stays wet, smears
into whatever wet paint it crosses, and dries over simulated hours.

This meadow is 560 strokes on the drawing, painted back to front onto a red-ground oil canvas: a sky
of long sweeps that warm from cobalt to Naples yellow, clouds scumbled in lead white, a field, then
grass flicked up from the root so each blade thins as the pen lifts, and poppies on top. The grass
goes into the field paint before it is dry and drags some of it along.

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/meadow.png" wait="40" capture="box" open-outputs="true" padding="18" timeout="300" requires="paint" name="chain-drawn-paint-meadow" embed=true alt="An oil painting of a meadow at evening on a canvas with a visible weave. A sky of horizontal brushstrokes shades from grey-blue at the top to a warm peach at the horizon, with five low clouds of thick cream paint whose bristle ridges catch the light. Below a thin blue-green band of distant hills, a green and ochre field is crowded with hundreds of tapering blades of grass, short and fine near the horizon and taller and broader towards the bottom, darkening to umber at the foot. Scattered through the grass are sixty red poppies, small in the distance and larger in front."}
:::static
import afield.paint.*
import kotlin.random.Random

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

:::fold paint them: a palette of tube paints per kind of stroke, back to front
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
:::endfold
display("main", canvas.png())
```

The strokes are drawn by code here, from a seeded random hand, so that the page rebuilds the same
way. The painting part reads them back from a region of the drawing, `notation.strokes.filter {
it.bounds.intersects(region) }`, so strokes you draw in that region with the pen are painted too.

## Step by step

The same materials, one action per frame. Two wet bands are crossed by a loaded brush, which mixes
into them, and by a clean one, which only smears them; then the meadow is laid in from the back,
sky, clouds, hills and field, a few grass blades and poppies at a time, and finished with the
varnish:

```diffstatic_runner {data-lang=kotlin video="assets/chain_drawn_paint/steps.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="13" fps="10" timeout="600" requires="paint" embed=true name="chain-drawn-paint-steps" alt="A painting being made. A yellow band and a red band are laid on white; a blue stroke comes down across both and turns green and violet where it crosses them; a clean brush drags yellow down into the red beside it. Then a red-ground canvas appears and fills with sky strokes from the top, cobalt warming to peach, then cream clouds, blue-green hills, a green and ochre field, grass blades added a handful at a time from the horizon forward, red poppies, and last a slight warm darkening as the varnish goes on."}
:::static
import afield.paint.*
import kotlin.random.Random

:::fold the meadow's 560 strokes, drawn into the drawing as in the first figure
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
:::endfold

// Every step below is one action on a real canvas; the loop takes one step per frame and shows it.
val steps = mutableListOf<Pair<String, () -> Unit>>()
val hold = 6   // frames to hold on a step worth seeing

// Act one: two wet bands, blue straight across them, then a clean brush dragged through.
val bands = PaintCanvas(900, aspect = 2.0)
var showing = bands
val band = bands.brush(BrushKind.HogFlat, width = 90.0)
steps += "yellow" to { bands.stroke(FLine().moveTo(120, 170).cubicTo(400, 150, 650, 190, 900, 170), band.load(mapOf("cadmium yellow" to 1.0), wipe = 1.0), shake = 0.5) }
steps += "red" to { bands.stroke(FLine().moveTo(120, 340).cubicTo(400, 320, 650, 360, 900, 340), band.load(mapOf("vermilion" to 1.0), wipe = 1.0), shake = 0.5) }
steps += "blue, into the wet bands" to { bands.stroke(FLine().moveTo(330, 60).cubicTo(360, 200, 320, 320, 350, 450), bands.brush(BrushKind.HogFlat, width = 70.0).load(mapOf("ultramarine blue" to 1.0)), shake = 0.5) }
steps += "a clean brush, dragged through" to { bands.stroke(FLine().moveTo(680, 60).cubicTo(700, 200, 670, 320, 700, 460), bands.brush(BrushKind.HogFlat, width = 70.0), shake = 0.5) }

:::fold act two: the meadow, back to front, a few strokes a frame, then the varnish
val canvas = PaintCanvas(900, aspect = 1.6, ground = PaintGround.OilRedGround, view = region)
steps += "a red-ground canvas" to { showing = canvas }
val strokes = notation.strokes.filter { it.bounds.intersects(region) }
val named: (String) -> List<Mark> = { p -> strokes.filter { it.label?.startsWith(p) == true } }
val depth: (Mark) -> Double = { s -> ((s.start!!.y - Y - 540) / 460).coerceIn(0.0, 1.0) }
val paint: (Mark, PaintBrush, Double, Double) -> Unit = { s, b, p, shake -> canvas.stroke(s.toFLine(), b, pressure = p..p, swell = s.pressuresAlong(32), shake = shake) }
val sky = canvas.brush(BrushKind.Filbert, width = 60.0)
named("sky").forEachIndexed { i, s -> val t = i / 17.0
    steps += "sky" to { paint(s, sky.load(mapOf("lead white" to 3.0, "cobalt blue" to 2.0 * (1 - t) * (1 - t), "Naples yellow" to 2.5 * t * t, "rose madder" to 0.4 * t), wipe = 0.5), 1.0, 1.0) } }
val cloud = canvas.brush(BrushKind.HogFlat, width = 34.0)
named("cloud").chunked(4).forEach { group -> steps += "clouds" to { group.forEach { paint(it, cloud.load(Tubes.mix(mapOf("lead white" to 4.0, "Naples yellow" to 0.4, "rose madder" to 0.1)), hiding = 0.5, wipe = 0.3), 1.0, 0.8) } } }
val hill = canvas.brush(BrushKind.HogFlat, width = 26.0)
steps += "hills" to { named("hill").forEachIndexed { i, s -> paint(s, hill.load(mapOf("lead white" to 3.0 - i * 0.4, "cobalt blue" to 1.0, "green earth" to 1.0 + i * 0.4), wipe = 0.6), 1.0, 1.0) } }
val field = canvas.brush(BrushKind.HogFlat, width = 50.0)
named("field").withIndex().chunked(2).forEach { group -> steps += "field" to { group.forEach { (i, s) -> val t = i / 21.0
    paint(s, field.load(mapOf("green earth" to 2.0, "yellow ochre" to 2.0 * (1 - t), "raw umber" to 1.5 * t, "lead white" to 1.0 * (1 - t)), wipe = 0.5), 1.0, 1.0) } } }
steps += "forty minutes pass" to { canvas.wait(40.0) }
val grass = canvas.brush(BrushKind.Round, width = 10.0)
val greens = listOf(mapOf("viridian" to 1.0, "yellow ochre" to 1.5), mapOf("green earth" to 2.0, "chrome yellow" to 1.0),
    mapOf("viridian" to 1.0, "raw umber" to 1.0), mapOf("yellow ochre" to 2.0, "lead white" to 1.0),
    mapOf("Prussian blue" to 0.2, "chrome yellow" to 2.0), mapOf("Naples yellow" to 2.0, "green earth" to 1.0))
named("grass").sortedBy { it.start!!.y }.withIndex().chunked(14).forEach { group -> steps += "grass" to { group.forEach { (i, s) -> paint(s, grass.load(greens[i % greens.size], wipe = 0.5), 0.4 + 0.6 * depth(s), 0.6) } } }
val poppy = canvas.brush(BrushKind.Filbert, width = 24.0)
named("poppy").sortedBy { it.start!!.y }.chunked(5).forEach { group -> steps += "poppies" to { group.forEach { s -> paint(s, poppy.load(mapOf("vermilion" to 3.0, "cadmium red" to 1.0, "lead white" to 0.2), wipe = 0.9), 0.3 + 0.7 * depth(s), 1.0) } } }
steps += "dry, then a warm varnish" to { canvas.dry(); canvas.glaze(rgb(0.6, 0.45, 0.2), coats = 0.12) }
:::endfold

val slow = setOf("yellow", "red", "blue, into the wet bands", "a clean brush, dragged through", "a red-ground canvas", "dry, then a warm varnish")
val frames = steps.flatMap { s -> List(if (s.first in slow) hold else 1) { k -> if (k == 0) s else null } }
var at = 0
loop {
    if (at < frames.size) {
        frames[at]?.let { (label, act) -> act(); display("main", showing.png(relief = 0.6)); display("step", label) }
        at++
    } else end()
}
```

## Three strokes, with pressure

`notation { stroke(...) }` writes a stroke into the drawing. Its `pressure` argument gives the pen
pressure, 0 to 1, as a function of how far along the stroke the pen is. This box draws three: a wave
named `S` that is pressed hardest in the middle, a `sweep` that starts hard and lifts off, and a
`loop` that starts light and bears down as it curls in to its end. It reads them back and plots the
pressure along each. The figure shows the drawing on the left and the box with its output on the
right:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/strokes.png" wait="5" capture="window" open-outputs="true" window-outputs="true" x="1100" y="40" w="300" h="50" clip="0,0,1850,780" padding="18" timeout="90" name="chain-drawn-paint-strokes" embed=true alt="On the left, the three strokes as they appear on the drawing layer: a wave, an arc and a spiral in pale grey on the dark canvas. On the right, the box that drew them and its output: three curves of pen pressure against distance along the stroke. The S rises from light to a peak in the middle and falls back; the sweep falls in a straight line from hard to light; the loop rises slowly at first, then steeply, to full pressure at its end. A table below lists each stroke's point count and its lightest and heaviest pressure."}
// Three pen strokes, drawn from code into the document's drawing, each with a pen pressure
// (0..1) as a function of how far along the stroke the pen is. Running it again replaces them.
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
// Read back every stroke in this part of the drawing, with its pressure at 50 evenly spaced points.
val strokes = notation.strokes.filter { it.bounds.intersects(Rect(40.0, 40.0, 1040.0, 400.0)) }
showSignals(*strokes.map { (it.label ?: it.id) to it.pressuresAlong(50) }.toTypedArray(),
    x = (0 until 50).map { it / 49.0 }, title = "pen pressure along each stroke",
    xTitle = "fraction of the way along", yTitle = "pressure", channel = "pressure")
markdown("table", "| stroke | points | lightest | heaviest |\n|---|---|---|---|\n" + strokes.joinToString("\n") {
    "| ${it.label ?: "drawn"} | ${it.points.size} | %.2f | %.2f |".format(it.pressures.minOrNull() ?: 0.0, it.pressures.maxOrNull() ?: 0.0)
}, width = 380)
```

`pressures` is the list Excalidraw keeps, one value per point. A pen records points closer together
where it moves slowly, so that list is not evenly spaced along the stroke. `pressuresAlong(n)` gives
the pressure at `n` points evenly spaced by distance, which is what a brush wants. A stroke drawn
with a mouse has no pressure, and both lists are empty.

## Painted, pressing as the pen did

`PaintCanvas(view = ...)` stretches a rectangle of drawing coordinates over the whole canvas, so the
strokes need no conversion: `toFLine()` gives each one in drawing coordinates, and the drawing and
the canvas both have y growing downwards. A brush's `swell` is a list of pressure multipliers along
the stroke, so with the base `pressure` at 1, `swell = pressuresAlong(64)` presses the brush as the
pen pressed.

The top row is painted that way. In the middle row the pen's pressure is squared first, and in the
bottom row the brush is held at one even pressure:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/painted.png" wait="10" capture="box" open-outputs="true" padding="18" timeout="150" requires="paint" name="chain-drawn-paint-painted" embed=true alt="Three rows of three oil-paint brushstrokes on white: a blue wave, a red arc and a green spiral. In the top row the wave narrows at both ends, the arc narrows from a broad start to a point, and the spiral widens as it curls in. In the middle row the same changes are much stronger: the wave's ends and the arc's tail thin to a line of bristle marks, and the spiral's outer curve is a thin trail. In the bottom row all three are bands of one even width."}
:::static
import afield.paint.*

:::fold the same three strokes as above, drawn into this box's drawing
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
:::endfold
// Every stroke in that region, painted three times, one row each: the brush
// pressing as the pen did; the pen's pressure squared, so light strokes go lighter; one even pressure.
val region = Rect(40.0, 40.0, 1040.0, 400.0)
val strokes = notation.strokes.filter { it.bounds.intersects(region) }
val colours = listOf(mapOf("ultramarine blue" to 2.0, "bone black" to 0.3), mapOf("vermilion" to 1.0), mapOf("viridian" to 1.0, "chrome yellow" to 0.6))
val canvas = PaintCanvas(900, aspect = 0.9, view = Rect(40.0, 40.0, 1000.0, 1110.0))
val brush = canvas.brush(BrushKind.Round, width = 70.0)
fun lower(s: Mark, dy: Double) = FLine().also { f -> s.points.forEachIndexed { k, p -> if (k == 0) f.moveTo(p.x, p.y + dy) else f.lineTo(p.x, p.y + dy) } }
strokes.forEachIndexed { i, s ->
    val pen = s.pressuresAlong(64)
    brush.load(colours[i % 3], wipe = 1.0)
    canvas.stroke(s.toFLine(), brush, pressure = 1.0..1.0, swell = pen)
    brush.load(colours[i % 3], wipe = 1.0)
    canvas.stroke(lower(s, 360.0), brush, pressure = 1.0..1.0, swell = pen.map { it * it })
    brush.load(colours[i % 3], wipe = 1.0)
    canvas.stroke(lower(s, 720.0), brush, pressure = 0.6..0.6)
}
display("main", canvas.png())
notation.clear()   // take the strokes down so they do not lie over the picture
```

A round brush pressed as lightly as it goes still lays a band about a third of its full width, so
painting straight from the pen gives a narrower range than the pen recorded. Squaring the pressure
spreads the light end out: a stroke at half pressure paints at a quarter, and the lightest parts of
a stroke thin to a few bristles. Any function of the pressure works here, and a curve like this one
is what a drawing program's pressure setting is.

## Strokes that cross

Wet paint mixes with what is on the canvas the way pigment does, not the way light does. Here four
yellow and red pairs of bands are each crossed by one stroke. In the first, ultramarine goes straight
across the wet bands and turns green over the yellow and dark over the red. In the second the same
blue crosses the same bands after `dry()`, and sits on top of them as a transparent film. In the
third a clean, dry hog-hair brush with no paint of its own is dragged through: it picks up the
yellow and smears it down into the red. In the fourth a clean badger blender does the same more
softly:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/crossings.png" wait="15" capture="box" open-outputs="true" padding="18" timeout="150" requires="paint" name="chain-drawn-paint-crossings" embed=true alt="Four pairs of horizontal bands, yellow above red, each crossed by a vertical stroke. First: a blue stroke that turns streaky green where it crosses the yellow and a dark red-violet where it crosses the red. Second: a blue stroke laid over the bands after they dried, a clean blue that darkens the yellow and red beneath it without mixing. Third: no blue, but a smear of yellow pulled down from the top band across the gap and into the red, with bristle streaks, ending in an orange tail below the red band. Fourth: a softer, blurred smear of yellow fading down into the red and a soft red tail below it."}
:::static
import afield.paint.*

// Four pairs of bands, yellow over red, each crossed by one vertical stroke.
notation {
    for (k in 0 until 4) {
        val x = 60.0 + k * 250
        stroke(FLine().moveTo(x, 560).cubicTo(x + 70, 545, x + 140, 575, x + 210, 560), name = "yellow $k", pressure = { 0.8 })
        stroke(FLine().moveTo(x, 760).cubicTo(x + 70, 745, x + 140, 775, x + 210, 760), name = "red $k", pressure = { 0.8 })
        stroke(FLine().moveTo(x + 95, 480).cubicTo(x + 120, 600, x + 90, 720, x + 115, 860), name = "across $k",
            pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}
val canvas = PaintCanvas(900, aspect = 2.4, view = Rect(40.0, 460.0, 1000.0, 416.0))
fun paint(name: String, brush: PaintBrush) = notation.named(name)!!.let {
    canvas.stroke(it.toFLine(), brush, pressure = 1.0..1.0, swell = it.pressuresAlong(32), shake = 0.5)
}
val band = canvas.brush(BrushKind.HogFlat, width = 70.0)
for (k in 0 until 4) {
    paint("yellow $k", band.load(mapOf("cadmium yellow" to 1.0), wipe = 1.0))
    paint("red $k", band.load(mapOf("vermilion" to 1.0), wipe = 1.0))
}
// 1: blue straight across the wet bands, so the colours mix where they meet
paint("across 0", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
// 3: a clean, dry hog-hair brush dragged through them: no paint of its own, it smears theirs
paint("across 2", canvas.brush(BrushKind.HogFlat, width = 60.0))
// 4: a clean badger blender, which only softens what is there
paint("across 3", canvas.brush(BrushKind.Badger, width = 60.0))
// 2: the same blue, after everything has dried
canvas.dry()
paint("across 1", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
display("main", canvas.png())
notation.clear()   // take the strokes down so they do not lie over the picture
```

## How long paint stays wet

`wait(minutes)` lets simulated time pass. This yellow band is crossed in blue straight away, and then
again after 2, 10, 33 and 100 hours:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/drying.png" wait="15" capture="box" open-outputs="true" padding="18" timeout="150" requires="paint" name="chain-drawn-paint-drying" embed=true alt="A long horizontal yellow band crossed by five vertical blue strokes. The first two, crossed at once and after two hours, turn green and streaky where they cross. The third, after ten hours, is a darker, patchier green with a ridge of blue at the band's edges. The last two, after 33 and 100 hours, lie over the yellow as smooth dark blue with no green in them. A caption below reads: crossed after 0 h, 2 h, 10 h, 33 h, 100 h (left to right)."}
:::static
import afield.paint.*

// One yellow band, crossed in blue after longer and longer waits.
val hours = listOf(0.0, 2.0, 10.0, 33.0, 100.0)
notation {
    stroke(FLine().moveTo(60, 1080).cubicTo(400, 1070, 700, 1090, 1020, 1080), name = "slow band", pressure = { 0.85 })
    hours.indices.forEach { i ->
        val x = 150.0 + i * 190
        stroke(FLine().moveTo(x, 960).lineTo(x + 20, 1200), name = "after $i", pressure = { 0.5 + 0.5 * sin(PI * it) })
    }
}
val canvas = PaintCanvas(900, aspect = 3.2, view = Rect(40.0, 940.0, 1000.0, 312.0))
fun paint(name: String, brush: PaintBrush) = notation.named(name)!!.let {
    canvas.stroke(it.toFLine(), brush, pressure = 1.0..1.0, swell = it.pressuresAlong(32), shake = 0.4)
}
paint("slow band", canvas.brush(BrushKind.HogFlat, width = 80.0).load(mapOf("cadmium yellow" to 1.0)))
var waited = 0.0
hours.forEachIndexed { i, h ->
    canvas.wait((h - waited) * 60); waited = h      // simulated minutes
    paint("after $i", canvas.brush(BrushKind.HogFlat, width = 60.0).load(mapOf("ultramarine blue" to 1.0)))
}
display("main", canvas.png())
markdown("times", "crossed after " + hours.joinToString(", ") { "${it.toInt()} h" } + " (left to right)", width = 500)
notation.clear()
```

How fast a stroke dries depends on how thick it is and on its paint: lead white dries in about half
the time of an average paint and bone black in more than twice. `dry()` waits until every stroke is
touch-dry. Rendering does not change the canvas, so the strokes are still wet for the next one.

## In pencil

`canvas.draw(line, pencil, pressure = ...)` takes the same kind of list, a pressure at evenly spaced
points along the line. Pressed lightly, a pencil leaves a pale grey line; pressed hard, a dark one.
The three strokes from the start of the page, in a 6B pencil on a 20 cm sheet:

```diffstatic_runner {data-lang=kotlin screenshot="assets/chain_drawn_paint/pencil.png" wait="10" capture="box" open-outputs="true" padding="18" timeout="150" requires="paint" name="chain-drawn-paint-pencil" embed=true alt="The same three strokes drawn in grey pencil on white paper. The wave is pale at both ends and darkest in its middle trough; the arc starts dark and fades to a pale grey; the spiral is pale on its outer curve and darkens where it curls in to its end."}
:::static
import afield.paint.*

:::fold the same three strokes as above, drawn into this box's drawing
notation {
    stroke(FLine().moveTo(80, 300).cubicTo(200, 60, 330, 420, 420, 140), name = "S",
        pressure = { 0.15 + 0.85 * sin(PI * it) })
    stroke(FLine().moveTo(470, 110).cubicTo(560, 300, 700, 330, 820, 180), name = "sweep",
        pressure = { 1.0 - 0.85 * it })
    stroke(FLine().moveTo(880, 330).cubicTo(1060, 300, 1020, 60, 900, 120).cubicTo(820, 160, 880, 260, 960, 250),
        name = "loop", pressure = { 0.15 + 0.85 * it * it })
}
:::endfold
// The strokes in that region in a 6B pencil, on a 20 cm sheet, its pressure following the pen.
val region = Rect(40.0, 40.0, 1040.0, 400.0)
val canvas = PaintCanvas(900, aspect = 3.0, view = Rect(40.0, 40.0, 1040.0, 347.0), sizeMm = 200.0)
val pencil = canvas.pencil("6B")
for (s in notation.strokes.filter { it.bounds.intersects(region) })
    canvas.draw(s.toFLine(), pencil, pressure = s.pressuresAlong(32).ifEmpty { listOf(0.6) })
display("main", canvas.png())
notation.clear()
```

## Drawing your own

Open the [drawn_paint](agent_transcripts.md#drawn_paint) starting point and run its boxes in order: `strokes`,
`paint`, `crossings`, `drying`, `meadow` and `pencil`. Each reads the strokes in one region of the
drawing. To paint your own, press `alt-d`, choose the pen (`p`) and draw in a region, then run the
box that reads it. A tablet pen records pressure; a mouse does not, and for a mouse stroke
`pressuresAlong` is empty, so the brush paints at its base pressure. To make a canvas that fits
whatever you drew:

```diffstatic {data-lang=kotlin}
val drawn = notation.strokes.filter { it.handDrawn }
val view = drawn.map { it.bounds }.reduce { a, b -> a.union(b) }.let { Rect(it.x - 40, it.y - 40, it.w + 80, it.h + 80) }
val canvas = PaintCanvas(900, aspect = view.w / view.h, view = view)
```

## Where each piece is documented

* [Drawing and Notation](drawing_and_notation.md) for the drawing layer and `notation`.
* [Paint and pencil from FLines](paint_canvas.md) for `PaintCanvas`, brushes, tube paints, grounds,
  glazes and pencils, and for building the native library the paint figures need.
* [A Drawn Morph, Heard](chain_drawn_morph.md) reads strokes from the same drawing as shapes to morph
  between.

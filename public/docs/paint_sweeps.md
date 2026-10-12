# Paint settings, swept

The [moving-hairs brush](paint_gestures.md) has many settings, and most of them interact. The
figures on this page each paint one gesture over and over, changing one setting from left to right
and keeping everything else as it is in the [black Elegy](paint_gestures.md#a-finished-piece): a
soft house painter's brush 10 cm wide, laying a thin film of fat black paint. Click a figure to see
it full size.

The gesture has four parts, top to bottom: a long straight stroke; a loop that crosses itself, so
the brush paints over its own wet paint; a scrub back and forth; and a last straight in four pieces,
each faster and lighter than the one before (100, 200, 350 and 600 mm/s) and lifting off the canvas
at the end. The jumps in speed show as bands across that last stroke. The code that paints it is
folded inside every example below; open it to see or change it. Each figure takes 10 to 20 seconds
to paint.

## The paint

How much paint the brush carries matters more than anything else about the paint. With `amount`
(how full the brush is loaded, 0..1) low, the bar is a grey drag of hair tracks and the end of the
stroke all but vanishes; loaded full, it is solid black and the loop closes into a clean ring.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/amount.png" source-image="/tmp/field-docs-paint-sweep-amount.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. Left to right the brush is loaded more fully: the first is grey and streaked throughout with a faint, broken end; the last is solid black down to the lifted streaks." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-amount" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.05, 0.15, 0.5, 1.0, 4.0, 16.0, 128.0).map { column("amount $it", Settings(amount = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-amount.png")
```

`lay` (in `BrushConfig`) is how thick a film a full brush lays. Thin, the bristle tracks show
through the black; thick, they disappear under it.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/lay.png" source-image="/tmp/field-docs-paint-sweep-lay.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. Left to right the film thickens from a grey, finely striated bar and ring to a dense black in which the tracks are lost." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-lay" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.5, 1.0, 2.0, 8.0, 32.0, 256.0).map { column("lay $it", Settings(lay = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-lay.png")
```

`run` is how far a load lasts, in canvas units. Short, the brush is dry before it reaches the scrub;
long, the last stroke stays dark to the end, bands and all.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/run.png" source-image="/tmp/field-docs-paint-sweep-run.png" alt="Five columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. With a short run the loop is already pale and the scrub and last stroke are faint ghosts; with a long one the last stroke is dark to its end." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-run" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(50.0, 300.0, 1100.0, 4000.0, 30000.0, 1000000.0).map { column("run ${it.toInt()}", Settings(run = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-run.png")
```

A little painting medium (`thinner`) changes the black all at once. With none, the film covers; at
0.02 it begins to break, and from 0.03 on it is a veil of streaks over the ground with dark
outlines, the brush's hair tracks drawn rather than filled. There is no setting that is half of
each.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/thinner.png" source-image="/tmp/field-docs-paint-sweep-thinner.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. The first is solid black; by the third the bar has split into dark lines with cream between them, and the last four are pale veils of streaks with crisp dark outlines and a ringed loop." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-thinner" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.01, 0.02, 0.05, 0.2, 0.5, 0.9).map { column("thinner $it", Settings(thinner = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-thinner.png")
```

`hiding`, `oil` and the paint's own `stiff` change little here. Black absorbs almost everything at
any film thickness, so `hiding` matters only at 1, where the paint covers solidly; and at this thin
film, oil and stiffness hardly show. They matter much more with colour, with thick paint, and with
paint laid over paint.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hiding.png" source-image="/tmp/field-docs-paint-sweep-hiding.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. The first five look nearly alike; only the last, fully hiding, is a denser black throughout." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hiding" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0001, 0.001, 0.01, 0.1, 0.65, 1.0).map { column("hiding $it", Settings(hiding = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hiding.png")
```


```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/stiff.png" source-image="/tmp/field-docs-paint-sweep-stiff.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. All seven look nearly alike." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-stiff" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.5, 2.0, 5.0, 30.0, 300.0).map { column("stiff $it", Settings(stiff = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-stiff.png")
```

## The brush

The stiffness of the hairs (`BrushConfig.stiffness`, 0 soft hair to 1 hog bristle) changes the most.
Soft hair cannot hold the brush's shape against the canvas: the hairs flop and drag, and the stroke
turns into a fur of separate hair tracks that loses the loop. From about 0.4 the brush keeps its
shape, and stiffer still, the scrub drags hard diagonal marks and the loop's crossing shows.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/stiffness.png" source-image="/tmp/field-docs-paint-sweep-stiffness.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. The first two are soft, mottled furs of short hair marks, with the loop a fuzzy blur; from the third on the stroke is crisp, and in the stiffest the loop shows its crossing as a dark diagonal and the scrub as hard zigzag lines." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-stiffness" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(-1.0, -0.25, 0.0, 0.4, 1.0, 2.0, 1000.0).map { column("stiffness $it", Settings(stiffness = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-stiffness.png")
```

`hairs` is how many hairs are simulated. Fewer, and the brush lays separate bands with gaps
between, most visibly where it lifts off; more, and the marks are finer and denser.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hairs.png" source-image="/tmp/field-docs-paint-sweep-hairs.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. With 8 hairs the last stroke breaks into a few broad bands; with 96 it is fine and even." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hairs" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(2, 4, 8, 48, 256, 1024).map { column("hairs $it", Settings(hairs = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hairs.png")
```

HAIR_PROSE

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hair.png" source-image="/tmp/field-docs-paint-sweep-hair.png" alt="HAIR_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hair" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(1.0, 0.5, 0.2, 0.05, 0.01, 0.002).map { column("hair $it", Settings(hair = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hair.png")
```

WIDTH_PROSE

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/width.png" source-image="/tmp/field-docs-paint-sweep-width.png" alt="WIDTH_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-width" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(200.0, 100.0, 50.0, 20.0, 8.0, 2.0).map { column("width ${it.toInt()}", Settings(width = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-width.png")
```

`pickup` is how much of the wet paint under it a hair takes up. With none, the brush only gives
paint and runs dry; with a lot, it takes up the black it has just laid in the loop and the scrub and
carries it down, so the last stroke comes out darker than the brush's own load would leave it.
`push` is how much a moving hair ploughs aside: with none the stroke is soft and smooth, and with a
lot the paint piles into dark ridges at the edges of every pass.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/pickup.png" source-image="/tmp/field-docs-paint-sweep-pickup.png" alt="Five columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. With no pickup the last stroke is a faint ghost; with full pickup it carries dark bands of black down from the scrub." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-pickup" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.5, 1.0, 2.0, 4.0, 10.0).map { column("pickup $it", Settings(pickup = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-pickup.png")
```

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/push.png" source-image="/tmp/field-docs-paint-sweep-push.png" alt="Five columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. With no push the stroke is soft and smooth-edged; with full push every pass is outlined in dark ridges." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-push" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.5, 1.0, 2.0, 10.0, 100.0).map { column("push $it", Settings(push = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-push.png")
```

`ragged` and `splay` shape an ordinary brush's bristles. A moving brush's footprint comes from
where its simulated hairs actually lie, so here they make little difference.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/ragged.png" source-image="/tmp/field-docs-paint-sweep-ragged.png" alt="Five columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. All five look nearly alike." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-ragged" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 1.0, 8.0, 64.0, 512.0, 4096.0).map { column("ragged $it", Settings(ragged = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-ragged.png")
```

SPLAY_PROSE

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/splay.png" source-image="/tmp/field-docs-paint-sweep-splay.png" alt="SPLAY_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-splay" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.3, 0.6, 1.0, 2.0, 4.0, 10.0).map { column("splay $it", Settings(splay = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-splay.png")
```


## Drying

DRYING_PROSE_1

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/gloss.png" source-image="/tmp/field-docs-paint-sweep-gloss.png" alt="GLOSS_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-gloss" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.15, 0.4, 0.8, 1.3, 4.0).map { column("oil $it", Settings(oil = it, lamp = true, dry = true)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-gloss.png")
```

DRYING_PROSE_2

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/absorbent.png" source-image="/tmp/field-docs-paint-sweep-absorbent.png" alt="ABSORBENT_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-absorbent" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 0.15, 0.4, 0.8, 1.3, 4.0).map { column("oil $it", Settings(oil = it, absorbent = 1.0, lamp = true, dry = true)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-absorbent.png")
```

DRYING_PROSE_3

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/wipe.png" source-image="/tmp/field-docs-paint-sweep-wipe.png" alt="WIPE_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-wipe" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
fun hours(m: Double) = if (m < 60) "${m.toInt()} min" else if (m < 2880) "${(m / 60).toInt()} h" else "${(m / 1440).toInt()} d"
val strokes = listOf(0.0, 480.0, 1440.0, 2880.0, 5760.0, 10080.0, 20160.0).map { column("wipe " + hours(it), Settings(wipeAfter = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-wipe.png")
```

DRYING_PROSE_4

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/drying.png" source-image="/tmp/field-docs-paint-sweep-drying.png" alt="DRYING_ALT" wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-drying" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.25, 1.0, 4.0, 16.0, 64.0, 256.0).map { column("drying $it", Settings(drying = it, wipeAfter = 480.0)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-drying.png")
```

## The hand

`tilt` is the handle's lean from upright in degrees. Upright, only the tips touch and the brush
runs out quickly; laid over, the bellies of the hairs lie on the canvas.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/tilt.png" source-image="/tmp/field-docs-paint-sweep-tilt.png" alt="Five columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. Left to right the handle is laid further over." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-tilt" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0, 30.0, 60.0, 85.0, 120.0, 179.0).map { column("tilt $it", Settings(tilt = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-tilt.png")
```

The whole gesture faster or slower (`faster`, a factor on its speed): a slow hand lays more paint
and runs out sooner, a fast one lays a thinner film further.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/faster.png" source-image="/tmp/field-docs-paint-sweep-faster.png" alt="Four columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. Left to right the gesture is painted faster." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-faster" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(0.0625, 0.25, 1.0, 4.0, 16.0, 64.0).map { column("speed × $it", Settings(faster = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-faster.png")
```

`facets` makes the hand move in straight chords of that length instead of following the curve. At
each corner the handle turns at once, the hairs whip round, and the loop comes out beaded, a bead
a corner. A path drawn as a polyline does the same at its own corners.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/facets.png" source-image="/tmp/field-docs-paint-sweep-facets.png" alt="Five columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. The first follows the curve; in the rest the loop is cut into straight chords, fewer and longer to the left, with beads of paint at the corners." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-facets" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(null, 200.0, 60.0, 20.0, 6.0, 2.0).map { column(if (it == null) "smooth" else "facets ${it.toInt()}", Settings(facets = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-facets.png")
```

Last, the moving brush beside an ordinary one along the same path with the same pressures. The
ordinary brush has no timing and no tilt: its loop is smooth, and its last stroke narrows to a point
as the pressure falls, where the moving brush stays wide and breaks up.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/moving.png" source-image="/tmp/field-docs-paint-sweep-moving.png" alt="Two columns. On the left, the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that breaks into bands as it speeds up and lifts away into streaks. On the right, the same path with an ordinary brush: a smooth bar and loop, a softer scrub, and a last stroke that tapers to a point." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-moving" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.6, val lay: Double = 0.8, val run: Double = 1100.0,
    val pickup: Double? = null, val push: Double? = null, val hairs: Int = 48,
    val stiff: Double = 0.5, val thinner: Double = 0.0, val oil: Double = 1.5, val amount: Double = 0.5, val hiding: Double = 0.65,
    val tilt: Double = 30.0, val faster: Double = 1.0, val facets: Double? = null, val moving: Boolean = true,
    val width: Double = 200.0, val hair: Double? = null,
    val drying: Double = 1.0, val absorbent: Double = 0.0, val lamp: Boolean = false, val wipeAfter: Double? = null, val dry: Boolean = false,
)

// a canvas 50 cm wide and 1.25 m tall, and a brush 10 cm wide
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) { val a = PI / 2 + 2 * PI * 1.15 * i / 480; lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a)) }
}
val scrub = FLine().moveTo(560, 1330).apply { for (i in 0 until 5) lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i) }
val last = listOf(1600.0, 1800.0, 2000.0, 2200.0, 2400.0).zipWithNext().mapIndexed { k, (y0, y1) -> FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1) }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g = part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = listOf(1.0, 0.8, 0.6, 0.3, -0.2)
    for ((k, speed) in listOf(100.0, 200.0, 350.0, 600.0).withIndex()) g = g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
    val config = BrushConfig(hair = s.hair, stiffness = s.stiffness, ragged = s.ragged, splay = s.splay, lay = s.lay, run = s.run, pickup = s.pickup, push = s.push)
    val black = rgb(0.05, 0.045, 0.04)
    if (s.moving) {
        val brush = c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(gesture(c, s), brush)
    } else {
        // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
        val brush = c.brush(BrushKind.HogFlat, s.width, config = config)
            .load(black, hiding = s.hiding, stiff = s.stiff, drying = s.drying, thinner = s.thinner, oil = s.oil, amount = s.amount)
        c.stroke(straight, brush, pressure = 0.8..1.0); c.stroke(loop, brush, pressure = 1.0..0.9); c.stroke(scrub, brush, pressure = 1.1..1.1)
        c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
    }
    // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
    s.wipeAfter?.let { minutes ->
        if (minutes.isInfinite()) c.dry() else c.wait(minutes)
        val rag = c.rag(150.0)
        for (y in listOf(500.0, 1090.0, 2000.0)) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, listOf(0.8))
    }
    if (s.dry) c.dry()
    val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
    return label to imgFromBytes(png).also { c.close() }
}
:::endfold
val strokes = listOf(column("moving hairs", Settings()), column("ordinary brush", Settings(moving = false)))
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 4)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-moving.png")
```

See also: [Brushes with moving hairs](paint_gestures.md), [Paint and pencil from FLines](paint_canvas.md#brushes-made-to-order).

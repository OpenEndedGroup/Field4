# Paint settings, swept

The [moving-hairs brush](paint_gestures.md) has many settings, and most of them interact. The
figures on this page each paint one gesture over and over, changing one setting from left to right
and keeping everything else as it is in the [black Elegy](paint_gestures.md#a-finished-piece): a
soft house painter's brush 10 cm wide, laying a thin film of fat black paint. Most settings are
taken well past their usual range, where they stop behaving and start doing something else. Click
a figure to see it full size.

The gesture has four parts, top to bottom: a long straight stroke; a loop that crosses itself, so
the brush paints over its own wet paint; a scrub back and forth; and a last straight in four pieces,
each faster and lighter than the one before (100, 200, 350 and 600 mm/s) and lifting off the canvas
at the end. The code that paints it is
folded inside every example below; open it to see or change it. Each figure takes 10 to 20 seconds
to paint.

## The paint

How much paint the brush carries (`amount`, 1 is a full load) matters more than anything else
about the paint. A light load is a grey drawing of hair tracks; a full one is solid black; and a
brush loaded far past full stands its paint up in a thick crust where it first touches.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/amount.png" source-image="/tmp/field-docs-paint-sweep-amount.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Loaded lightly the stroke is a pale grey drawing of hair tracks; loaded more it darkens to solid black, and loaded far past full (16, 128) the paint stands in a thick, lumpy crust at the start of the bar." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-amount" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-amount.png")
```

`lay` (in `BrushConfig`) is how thick a film a full brush lays. Thin, the bristle tracks show
through the black; thick, they disappear, and far past the usual the paint spreads into a
silhouette and bursts at the start of the stroke.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/lay.png" source-image="/tmp/field-docs-paint-sweep-lay.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. From a thin grey film showing every hair track, the black thickens until the ring and scrub are solid black shapes, and at 256 the bar's top end bursts into a ragged blot." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-lay" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-lay.png")
```

`run` is how far a load lasts, in canvas units. Short, the brush is dry before it reaches the
loop; very long, it never runs out and the gesture is one solid shape.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/run.png" source-image="/tmp/field-docs-paint-sweep-run.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With a run of 50 only the top of the bar is painted before the brush is dry; at 300 the loop is already pale; from 30 000 the whole gesture is one solid black silhouette." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-run" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-run.png")
```

A little painting medium (`thinner`, a share of the paint's volume) changes the black all at once.
Up to about 0.02 the film still covers; from 0.05 it breaks into a veil of streaks over the ground
with dark outlines; and at 0.9 the paint is nearly all medium and almost nothing stays.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/thinner.png" source-image="/tmp/field-docs-paint-sweep-thinner.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. The first three are black; at 0.05 the bar breaks into streaks; at 0.2 and 0.5 the stroke is a pale grey veil of streaks with crisp outlines; at 0.9 almost nothing is left on the canvas." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-thinner" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-thinner.png")
```

`hiding` is how much one coat covers. Black absorbs so strongly that it covers from about 0.1 up,
but at very small values it fades to a ghost.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hiding.png" source-image="/tmp/field-docs-paint-sweep-hiding.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. At the lowest hiding the gesture is a barely visible ghost; it darkens steadily to a dense black at 1." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hiding" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hiding.png")
```

The paint's own stiffness (`stiff`) hardly changes a film this thin: it matters for thick paint,
where it decides whether the paint holds the brush's ridges or levels out.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/stiff.png" source-image="/tmp/field-docs-paint-sweep-stiff.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. All six look nearly alike: the paint's stiffness barely changes a stroke this thin." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-stiff" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-stiff.png")
```

## The brush

The stiffness of the hairs (`BrushConfig.stiffness`, 0 soft hair to 1 hog bristle) changes the
most. Below 0 the hairs are driven away from their own shape and hardly reach the canvas; at 0 they
flop and drag into a fur of hair marks; from about 0.4 the brush holds its shape; and far past 1
the hairs are rigid rods that splinter the loop and the scrub into hard straight marks.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/stiffness.png" source-image="/tmp/field-docs-paint-sweep-stiffness.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. At negative stiffness the hairs barely reach the canvas: a few marks at the top and a scatter of blots; at 0 a soft fur of hair marks; from 0.4 crisp; and at 1000 the scrub and loop break into hard, straight, splintered marks thrown out to the sides." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-stiffness" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-stiffness.png")
```

`hairs` is how many hairs are simulated. Two hairs paint two separate bands; from about eight the
stroke is whole, and more make it finer.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hairs.png" source-image="/tmp/field-docs-paint-sweep-hairs.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With 2 hairs the bar is two separate bands and the loop a pale smear; from 8 hairs up the stroke is whole and finer." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hairs" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hairs.png")
```

`hair` (in `BrushConfig`) is how thick each hair is, which decides how much neighbouring hairs
overlap. With thinner hairs the tracks close up, until the stroke is a solid black silhouette with a
sharp, fringed edge.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hair.png" source-image="/tmp/field-docs-paint-sweep-hair.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With hairs as thick as usual the stroke shows its tracks; with finer hairs it closes up, until at 0.05 and below it is a solid black silhouette with sharp, fringed edges." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hair" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hair.png")
```

`width` is the brush's width in canvas units, here from 10 cm down to 1 mm: the same gesture from a
house painter's brush down to a fine liner.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/width.png" source-image="/tmp/field-docs-paint-sweep-width.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. From a brush 10 cm wide down to one 1 mm wide: the gesture narrows from broad bands to a fine drawn line, the ring and zigzag a thin outline." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-width" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-width.png")
```

`pickup` is how much of the wet paint under it a hair takes up. With none, the brush only gives
paint and runs dry; with more, it takes up the black it has just laid and the whole stroke goes
paler, until the brush mostly lifts what it lays.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/pickup.png" source-image="/tmp/field-docs-paint-sweep-pickup.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With no pickup the last stroke fades out; with more, the brush takes up the black it lays and the whole stroke goes paler and greyer, almost cream at 10." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-pickup" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-pickup.png")
```

`push` is how much of the wet paint a moving hair ploughs aside. With none the stroke is soft;
with more, every pass is outlined in dark ridges; and with a lot, all the paint is pushed to the
edges and the stroke becomes a pale shape drawn in black outlines.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/push.png" source-image="/tmp/field-docs-paint-sweep-push.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With no push the stroke is soft and the scrub a dark smudge; with more, every pass is outlined in dark ridges; at 10 and 100 the paint is all ploughed to the edges and the stroke is a pale shape drawn in thin black outlines." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-push" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-push.png")
```

`ragged` makes the hairs uneven in length. A little roughens the edges; a lot gives some hairs many
times the length of the rest, and at 512 the longest hairs reach across the canvas and draw great
circles around the gesture.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/ragged.png" source-image="/tmp/field-docs-paint-sweep-ragged.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. To 8 the stroke barely changes; at 64 its hairs are of wildly different lengths and the marks fly apart; at 512 the brush's longest hairs reach the whole canvas and draw great circles over it; at 4096 almost nothing touches near the path." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-ragged" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-ragged.png")
```

`splay` is how far a hair pressed into the canvas is pushed out from the brush's middle. With a lot,
a flat pressed hard parts in two, and the loop becomes two rings.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/splay.png" source-image="/tmp/field-docs-paint-sweep-splay.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With no splay the bar is one band; with more, the hairs pressed into the canvas are pushed out to the sides, and the bar parts into two bands with a widening channel between them; the loop becomes two rings." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-splay" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-splay.png")
```

## Drying

Oil paint dries over days, and the oil in it decides how it looks dry. Below, the strokes are
painted with more and less oil (`oil`, 1 is tube paint), dried, and lit from above so the gloss
shows: lean paint dries dull, oily paint dark and glossy.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/gloss.png" source-image="/tmp/field-docs-paint-sweep-gloss.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. dried and lit from above. Lean paint dries dull grey; oil-rich paint dries darker and glossier." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-gloss" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-gloss.png")
```

On an absorbent ground (`prime(..., absorbent = 1.0)`) the ground draws oil out of the paint laid
on it. Very oily paint is drawn in so fast that the stroke runs out sooner.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/absorbent.png" source-image="/tmp/field-docs-paint-sweep-absorbent.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. on an absorbent ground, dried and lit from above. Lean and ordinary paint look much alike; the oiliest paint is drawn into the ground and the stroke runs out at the scrub." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-absorbent" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-absorbent.png")
```

A rag wiped across the paint shows how far it has set. Wiped within hours, black oil paint lifts
almost to the ground; after a day only partly; and from four days it has set and does not move.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/wipe.png" source-image="/tmp/field-docs-paint-sweep-wipe.png" alt="Seven columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. each crossed by three horizontal rag wipes, made after longer and longer. After minutes and 8 hours the wipes lift the black almost to the ground; after a day they only lighten it; from 2 days they barely show, and from 4 days the paint is set." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-wipe" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-wipe.png")
```

`drying` speeds or slows the paint's drying. Each stroke here is wiped 8 hours after it was
painted: at the usual rate the paint is still open; four times as fast and it has begun to set.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/drying.png" source-image="/tmp/field-docs-paint-sweep-drying.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. each wiped by a rag 8 hours after painting. Slow-drying paint lifts cleanly; from 4 times the usual rate it has begun to set and the wipe only lightens it; faster still, the wipes do not show." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-drying" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-drying.png")
```

## The hand

`tilt` is the handle's lean from upright in degrees, here past 90, where the handle leans back the
way it came.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/tilt.png" source-image="/tmp/field-docs-paint-sweep-tilt.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. From upright to the handle laid well over and past it: tilted further, the bar gets narrower and the loop and scrub come out with a sharper diagonal crossing." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-tilt" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-tilt.png")
```

The whole gesture faster or slower (`faster`, a factor on its speed). At 16 times as fast the
hairs chatter on the last stroke; at 64 times the brush lands late and the loop is a blur.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/faster.png" source-image="/tmp/field-docs-paint-sweep-faster.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Slower and faster versions of the same gesture look much alike until 16 times as fast, where the last stroke breaks into chattering marks; at 64 the brush lands late, the bar starts low, and the loop is a blurred swirl." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-faster" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-faster.png")
```

`facets` makes the hand move in straight chords of that length instead of following the curve. At
each corner the handle turns at once and the hairs whip round: with long chords the loop is a
polygon with beaded corners.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/facets.png" source-image="/tmp/field-docs-paint-sweep-facets.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With chords of 200 units the loop is a hexagon of straight bands with beaded corners; at 60 a beaded polygon; at 20 and below it is round again." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-facets" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-facets.png")
```

Last, the moving brush beside an ordinary one along the same path with the same pressures. The
ordinary brush has no timing and no tilt: its loop is smooth, and its last stroke narrows to a point
as the pressure falls, where the moving brush stays wide and breaks up.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/moving.png" source-image="/tmp/field-docs-paint-sweep-moving.png" alt="Two columns. On the left, the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. On the right, the same path with an ordinary brush: a smooth bar and loop, a softer scrub, and a last stroke that tapers to a point." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-moving" embed=true}
:::static
import afield.paint.*

:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5, val ragged: Double = 0.5, val splay: Double = 0.0, val lay: Double = 0.8, val run: Double = 1100.0,
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
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-moving.png")
```

See also: [Brushes with moving hairs](paint_gestures.md), [Paint and pencil from FLines](paint_canvas.md#brushes-made-to-order).

# Paint settings, swept

The [moving-hairs brush](paint_gestures.md) has many settings, and most of them interact. The
figures on this page each paint one gesture over and over, changing one setting from left to right
and keeping everything else as it is in the [black Elegy](paint_gestures.md#a-finished-piece): a
soft house painter's brush 10 cm wide, laying a thin film of fat black paint. Most settings are
taken well past their usual range, where they stop behaving and start doing something else. Where a
setting also belongs to an [ordinary brush](paint_canvas.md#brushes-made-to-order), a second row
paints the same path with one, at the same pressures. Click a figure to see it full size.

The gesture has four parts, top to bottom: a long straight stroke; a loop that crosses itself, so
the brush paints over its own wet paint; a scrub back and forth; and a last straight in four pieces,
each faster and lighter than the one before (100, 200, 350 and 600 mm/s) and lifting off the canvas
at the end. The code that paints it is
folded inside every example below; open it to see or change it. Each figure takes 10 to 20 seconds
to paint.

## The paint

How much paint the brush carries (`amount`, 1 is a full load) matters more than anything else
about the paint. A light load is a grey drawing of hair tracks; a full one is solid black; and a
brush loaded far past full stands its paint up in a thick crust where it first touches. The
ordinary brush goes the same way, but its black is a smooth silhouette with a tapered tail.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/amount.png" source-image="/tmp/field-docs-paint-sweep-amount.png" alt="Two rows of 7 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: loaded lightly the stroke is a pale grey drawing of hair tracks; loaded more it darkens to black, and far past full (16, 128) the paint stands in a lumpy crust on the bar and fills the ring. Ordinary brush: the same pale, streaky grey at first, solid black from 4 up, a smooth silhouette whose last stroke tapers to a point instead of breaking into streaks." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-amount" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.05, 0.15, 0.5, 1.0, 4.0, 16.0, 128.0].map { column("amount $it", Settings(amount = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-amount.png")
```

`lay` (in `BrushConfig`) is how thick a film a full brush lays. Thin, the bristle tracks show
through the black; thick, they disappear, and far past the usual the paint spreads into one solid
silhouette, lumpy where the bar starts. The ordinary brush is solid black from 2.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/lay.png" source-image="/tmp/field-docs-paint-sweep-lay.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: from a thin film showing every hair track the black thickens, until from 8 the ring, scrub and last stroke are one solid shape, the top of the bar lumpy at 32 and 256. Ordinary brush: streaky brown at 0.5, solid black from 2, its last stroke a tapered black spike rather than a streaked tail." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-lay" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.5, 1.0, 2.0, 8.0, 32.0, 256.0].map { column("lay $it", Settings(lay = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-lay.png")
```

`run` is how far a load lasts, in canvas units. Short, the brush is dry before it reaches the
loop; very long, it never runs out and the gesture is one solid shape. Both brushes run out alike.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/run.png" source-image="/tmp/field-docs-paint-sweep-run.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. In both rows only the top of the bar is painted at a run of 50 before the brush is dry, and at 300 the loop is already pale. From 30 000 the moving brush paints one solid silhouette with a square, streaked end; the ordinary brush a solid shape whose last stroke tapers to a point." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-run" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [50.0, 300.0, 1100.0, 4000.0, 30000.0, 1000000.0].map {
        column("run ${it.toInt()}", Settings(run = it, moving = moving))
    }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-run.png")
```

A little painting medium (`thinner`, a share of the paint's volume) changes the black all at once.
Up to about 0.01 the film still covers; from 0.02 it breaks into streaks with dark outlines, and
then into a pale veil over the ground; and at 0.9 the paint is nearly all medium and almost nothing
stays. The ordinary brush holds its black a little longer, to about 0.05.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/thinner.png" source-image="/tmp/field-docs-paint-sweep-thinner.png" alt="Two rows of 7 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: black up to 0.01; from 0.02 the bar breaks into streaks with dark outlines; at 0.2 and 0.5 a pale veil of streaks; at 0.9 almost nothing. Ordinary brush: black and smooth up to 0.05, then a pale brown veil with crisp outlines at 0.2 and 0.5, nearly gone at 0.9." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-thinner" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0, 0.01, 0.02, 0.05, 0.2, 0.5, 0.9].map { column("thinner $it", Settings(thinner = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-thinner.png")
```

`hiding` is how much one coat covers. Black absorbs so strongly that it covers from about 0.1 up,
but at very small values it fades to a ghost. The ordinary brush follows it step for step.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hiding.png" source-image="/tmp/field-docs-paint-sweep-hiding.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. In both rows the gesture is a barely visible ghost at the lowest hiding and darkens steadily to black at 1. At each step the ordinary brush is browner and more streaked than the moving one, and its last stroke tapers to a point." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hiding" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0001, 0.001, 0.01, 0.1, 0.65, 1.0].map { column("hiding $it", Settings(hiding = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hiding.png")
```

`stiff` is the paint's own stiffness (1 is tube paint). Up to tube paint it hardly changes a film
this thin. Past it the paint is a stiffer paste than any tube: it gathers the hairs into fewer,
wider clumps that leave bare furrows between their ridges, and comes off the hairs more reluctantly,
so the black goes paler and more broken the stiffer it is. The ordinary brush pales at 2 as well,
but past that it changes little.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/stiff.png" source-image="/tmp/field-docs-paint-sweep-stiff.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: dense black up to tube paint, then paler and more finely striped, the ring fading toward its outer edge, until at 300 the bar and ring are grey. Ordinary brush: black up to 0.5, paler and more streaked at 2, and then hardly changing from 5 to 300." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-stiff" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0, 0.5, 2.0, 5.0, 30.0, 300.0].map { column("stiff $it", Settings(stiff = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-stiff.png")
```

## The brush

The stiffness of the hairs (`BrushConfig.stiffness`, 0 soft hair to 1 hog bristle) changes the
most. Below 0 the hairs are driven away from their own shape and hardly reach the canvas; at 0 they
flop and drag into a fur of hair marks; from about 0.4 the brush holds its shape; and far past 1
the hairs are rigid rods that splinter the loop and the scrub into hard straight marks. The ordinary
brush hardly notices: from 1 up its scrub spreads a little wider and its last stroke parts at the top.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/stiffness.png" source-image="/tmp/field-docs-paint-sweep-stiffness.png" alt="Two rows of 7 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: at negative stiffness the hairs barely reach the canvas, leaving a few marks and blots; at 0 a soft fur of hair marks; from 0.4 crisp; and at 1000 the scrub and loop splinter into hard straight marks thrown out to the sides. Ordinary brush: almost the same smooth stroke in every column; from 1 up the scrub spreads a little wider and the last stroke parts at its top." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-stiffness" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [-1.0, -0.25, 0.0, 0.4, 1.0, 2.0, 1000.0].map { column("stiffness $it", Settings(stiffness = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-stiffness.png")
```

`hairs` is how many guide hairs are simulated. Even two give a whole stroke on this flat. With few,
the loop drags a dark wedge across the ring where it crosses itself; from about 48 the ring is evenly
striped, and more hairs change little.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hairs.png" source-image="/tmp/field-docs-paint-sweep-hairs.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Even with 2 hairs the bar is one whole band. With 2 and 4 hairs the loop drags a dark wedge across the ring where it crosses itself; from 48 up the ring is evenly striped with a toothed outer edge, and the stroke changes little from 48 to 1024." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hairs" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [2, 4, 8, 48, 256, 1024].map { column("hairs $it", Settings(hairs = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hairs.png")
```

`hairs` means nothing to an ordinary brush. Its count is `bristles` (in `BrushConfig`), the hairs
that touch the canvas: 140 for this flat. One bristle draws a single beaded line, a few paint
separate tracks, and from about 40 the stroke is whole; far past the default it closes up into a
smooth dark shape with hardly a hair mark.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/bristles.png" source-image="/tmp/field-docs-paint-sweep-bristles.png" alt="Eight columns of the same black brushstroke painted with an ordinary brush on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. With 1 bristle the gesture is a single thin, beaded line; 3 and 10 bristles paint separate tracks with gaps between them; from 40 the stroke is whole; past the default of 140 it closes up into denser black, and at 10 000 and 100 000 it is a smooth dark brown shape with hardly a hair mark." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-bristles" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes =
    [1, 3, 10, 40, 140, 1000, 10000, 100000].map { column("bristles $it", Settings(bristles = it, moving = false)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-bristles.png")
```

`hair` (in `BrushConfig`) is how thick each hair is, which decides how much neighbouring hairs
overlap. With thinner hairs the tracks close up, until the stroke is a solid black silhouette with a
sharp, fringed edge. The ordinary brush closes up sooner, solid by 0.5, and finer still its last
stroke ends in splinters.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/hair.png" source-image="/tmp/field-docs-paint-sweep-hair.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: as thick as usual the stroke shows its tracks; with finer hairs it closes up into a solid black silhouette with sharp, fringed edges. Ordinary brush: solid black already at 0.5; from 0.2 the edges of the scrub go ragged and the last stroke ends in a few sharp splinters." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-hair" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [1.0, 0.5, 0.2, 0.05, 0.01, 0.002].map { column("hair $it", Settings(hair = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-hair.png")
```

`width` is the brush's width in canvas units, here from 10 cm down to 1 mm: the same gesture from a
house painter's brush down to a fine liner. At the narrowest the ordinary brush still draws the last
stroke to its end, tapering to a point.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/width.png" source-image="/tmp/field-docs-paint-sweep-width.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. From a brush 10 cm wide down to one 1 mm wide the gesture narrows to a fine drawn line in both rows. At the narrow widths the moving brush stops short on the last stroke and fades; the ordinary brush draws it at full length as a line tapering to a point." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-width" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [200.0, 100.0, 50.0, 20.0, 8.0, 2.0].map { column("width ${it.toInt()}", Settings(width = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-width.png")
```

`pickup` is how much of the wet paint under it a hair takes up. With none, the brush only gives
paint and runs dry; with more, it takes up the black it has just laid and the whole stroke goes
paler, until the brush mostly lifts what it lays. With moving hairs the paling is patchy, as some
hairs keep their black; the ordinary brush pales evenly.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/pickup.png" source-image="/tmp/field-docs-paint-sweep-pickup.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: with no pickup the last stroke fades out; with more, the stroke goes paler in places and keeps dark patches where some hairs held their black. Ordinary brush: the whole stroke goes evenly paler, from black to a light grey-brown at 10." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-pickup" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0, 0.5, 1.0, 2.0, 4.0, 10.0].map { column("pickup $it", Settings(pickup = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-pickup.png")
```

`push` is how much of the wet paint a moving hair ploughs aside. With none the stroke is soft;
with more, every pass is outlined in dark ridges; and with a lot, all the paint is pushed to the
edges and the stroke becomes a pale shape drawn in black outlines. The ordinary brush gets there
sooner, by 2.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/push.png" source-image="/tmp/field-docs-paint-sweep-push.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: with no push the stroke is soft; with more, every pass is outlined in dark ridges; at 10 and 100 the paint is ploughed to the edges and the stroke is a pale shape in thin black outlines, the top of the bar still dark. Ordinary brush: the same, sooner: from 2 the whole gesture, bar included, is a pale shape drawn in black outlines." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-push" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0, 0.5, 1.0, 2.0, 10.0, 100.0].map { column("push $it", Settings(push = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-push.png")
```

`ragged` makes the hairs uneven in length. A little roughens the edges; a lot gives some hairs many
times the length of the rest, and at 512 the longest hairs reach across the canvas and draw great
circles around the gesture. The ordinary brush comes apart much sooner: at 8 its hairs wander in
curls around the path, and at 64 they loop away across the canvas.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/ragged.png" source-image="/tmp/field-docs-paint-sweep-ragged.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: to 8 the stroke barely changes; at 64 its hairs fly apart; at 512 the longest hairs draw great circles over the canvas; at 4096 almost nothing touches near the path. Ordinary brush: at 8 the hairs already wander into a tangle of curls around the path; at 64 they loop out across the top of the canvas; at 512 a few long straight lines cross it; at 4096 almost nothing is left." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-ragged" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0, 1.0, 8.0, 64.0, 512.0, 4096.0].map { column("ragged $it", Settings(ragged = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-ragged.png")
```

`splay` is how far a hair pressed into the canvas is pushed out from the brush's middle. With a lot,
a flat pressed hard parts in two, and the loop becomes two rings. An ordinary flat does not part:
splay widens its mark, until at 4 the gesture is a broad fan of hair marks and at 10 they cover the
canvas.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/splay.png" source-image="/tmp/field-docs-paint-sweep-splay.png" alt="Two rows of 7 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away. The top row is painted with moving hairs, the bottom row with an ordinary brush along the same path. Moving hairs: with no splay the bar is one band; with more, it parts into two bands with a widening channel between them, and the loop becomes two rings. Ordinary brush: the stroke spreads instead of parting: the bar widens, the ring is a broad disc at 2, the gesture a wide fan of hair marks at 4, and at 10 hair marks cover the whole canvas." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-splay" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val sheet = rows { moving ->
    [0.0, 0.3, 0.6, 1.0, 2.0, 4.0, 10.0].map { column("splay $it", Settings(splay = it, moving = moving)) }
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-splay.png")
```


The moving hairs' clumping comes in two models, and `clumping` (set on the brush:
`brush.set(clumping = ...)`) blends between them. At 0, as a brush is made, the liquid draws the tips
of hairs that are neighbours at the root together. At 1 it joins any two wet hairs that come near
each other with a bridge of paint, which pulls them together and snaps when they are pulled a few
spacings apart, so the brush gathers into clumps that splay can tear apart, and the bristles they
carry gather with them. Between, the two pull together; past 1 the bridges pull harder, and below 0
the old model pulls harder while the bridges push. The sweep runs from -1 to 2 at cohesion 4,
unsplayed and at splay 2.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/clumping.png" source-image="/tmp/field-docs-paint-sweep-clumping.png" alt="Two rows of 6 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away, painted with moving hairs at cohesion 4 and clumping from -1 to 2; unsplayed above, at splay 2 below. From -1 to 1 the strokes look much alike, the ring at 1 gathered into firmer concentric bands; at 1.5 and 2 the brush draws into narrower, sharper bands and the ring and last stroke go paler. In the splayed row the two halves of the bar get ragged inner edges as clumping rises, and the loop folds into an off-centre spiral." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-clumping" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
// moving hairs only (an ordinary brush has neither model): unsplayed above, splayed below
val sheet = montage(
    [0.0, 2.0].map { sp ->
        val strokes = [-1.0, 0.0, 0.5, 1.0, 1.5, 2.0].map {
            column("clumping $it", Settings(cohesion = 4.0, clumping = it, splay = sp))
        }
        "cohesion 4, splay $sp" to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
    },
    columns = 1,
    cellSide = 4000,
    textScale = 3,
)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-clumping.png")
```

`cohesion` is how strongly the liquid pulls. With liquid bridges it also sets how far a bridge
reaches, so a stronger pull gathers the hairs into fewer, larger clumps, and past about 16 the
gathered brush lays its paint sooner; below 0 the hairs push each other apart. A hog flat's own is
0.35.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/cohesion.png" source-image="/tmp/field-docs-paint-sweep-cohesion.png" alt="Two rows of 7 columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away, painted with moving hairs joined by liquid bridges, cohesion from -8 to 32; unsplayed above, at splay 3 fanned open below. Unsplayed, the stroke changes little up to 4; at 16 and 32 the bar fades early and the ring and last stroke are pale, gathered into fine dark bands. Fanned, the bar parts in two with ragged inner edges that widen with cohesion, the ring splits into concentric bands, and at 16 and 32 the bar's halves fray and the ring breaks into pale and dark rings." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-cohesion" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
// liquid bridges (clumping 1); unsplayed above, fanned open below (`splayFan`, next)
val sheet = montage(
    [0.0, 3.0].map { sp ->
        val strokes = [-8.0, 0.0, 0.35, 1.0, 4.0, 16.0, 32.0].map {
            column(
                "cohesion $it",
                Settings(cohesion = it, clumping = 1.0, splay = sp, splayFan = if (sp > 0) 1.0 else null),
            )
        }
        (if (sp > 0) "liquid bridges, splay $sp fanned" else "liquid bridges, no splay") to
            montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
    },
    columns = 1,
    cellSide = 4000,
    textScale = 3,
)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-cohesion.png")
```

`splay` pushes every pressed hair straight out from the brush's middle, so a flat can only part in
two. `splayFan` 1 pushes each in proportion to how far out its root is, so the brush fans open and
neighbours part everywhere; `splayScatter` turns each push off the outward by its own slowly drifting
angle and amount, alike for hairs near each other, the way pressed hairs buckle in bundles. With
liquid bridges, the bundles hold together and each leaves its own trail, braiding and crossing.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/scatter.png" source-image="/tmp/field-docs-paint-sweep-scatter.png" alt="Six columns of the same black brushstroke on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away, painted by a flat half the usual width with 64 moving hairs, splay 3 fanned open and liquid bridges, the splay's scatter from 0 to 2. At 0 the bar is two narrow bands; at 0.3 it begins to waver; at 0.6 it tears into several wavy ropes; at 1 and 2 bundles of hairs braid and cross through the loop and the scrub, and the last stroke trails apart into separate strands." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-scatter" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
// a flat half the usual width, 64 hairs, splay 3 fanned open, liquid bridges
val sheet = run {
    val strokes = [0.0, 0.15, 0.3, 0.6, 1.0, 2.0].map {
        column(
            "scatter $it",
            Settings(
                width = 100.0,
                hairs = 64,
                splay = 3.0,
                splayFan = 1.0,
                cohesion = 1.0,
                clumping = 1.0,
                splayScatter = it,
            ),
        )
    }
    montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
}
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-scatter.png")
```

## Drying

Oil paint dries over days, and the oil in it decides how it looks dry. Below, the strokes are
painted with more and less oil (`oil`, 1 is tube paint), dried, and lit from above so the gloss
shows: lean paint dries dull, oily paint dark and glossy.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/gloss.png" source-image="/tmp/field-docs-paint-sweep-gloss.png" alt="Six columns of the same black brushstroke painted side by side on off-white, dried and lit from above: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Lean paint, from no oil to 0.8, dries a dull grey; at 1.3 and 4 the oil-rich paint dries darker and glossier." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-gloss" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [0.0, 0.15, 0.4, 0.8, 1.3, 4.0].map { column("oil $it", Settings(oil = it, lamp = true, dry = true)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-gloss.png")
```

On an absorbent ground (`prime(..., absorbent = 1.0)`) the ground draws oil out of the paint laid
on it. Very oily paint is drawn in so fast that the stroke runs out sooner.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/absorbent.png" source-image="/tmp/field-docs-paint-sweep-absorbent.png" alt="Six columns of the same black brushstroke painted side by side on off-white on an absorbent ground, dried and lit from above: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Lean and ordinary paint look much alike, a dull grey; at 1.3 the top of the bar is darker; at 4 the oiliest paint is drawn into the ground, the ring breaks into pale rings, and the stroke runs out at the scrub." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-absorbent" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [0.0, 0.15, 0.4, 0.8, 1.3, 4.0].map {
    column("oil $it", Settings(oil = it, absorbent = 1.0, lamp = true, dry = true))
}
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-absorbent.png")
```

A rag wiped across the paint shows how far it has set. Wiped within hours, black oil paint lifts
almost to the ground; after a day only partly; after two days hardly at all; and from four days it
has set and does not move.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/wipe.png" source-image="/tmp/field-docs-paint-sweep-wipe.png" alt="Seven columns of the same black brushstroke painted side by side on off-white, each crossed by three horizontal rag wipes made after longer and longer: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Wiped at once or after 8 hours, the black lifts almost to the ground on the bar, the ring and the last stroke; after a day the wipe still lifts the bar but only lightens the ring and the last stroke; after 2 days it leaves a faint grey patch on the bar; from 4 days the paint is set and the wipes do not show." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-wipe" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
fun hours(m: Double) =
    if (m < 60) "${m.toInt()} min" else if (m < 2880) "${(m / 60).toInt()} h" else "${(m / 1440).toInt()} d"
val strokes =
    [0.0, 480.0, 1440.0, 2880.0, 5760.0, 10080.0, 20160.0].map { column("wipe " + hours(it), Settings(wipeAfter = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-wipe.png")
```

`drying` speeds or slows the paint's drying. Each stroke here is wiped 8 hours after it was
painted: at the usual rate the paint is still open; four times as fast and it has begun to set; from
sixteen times the wipes do not show.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/drying.png" source-image="/tmp/field-docs-paint-sweep-drying.png" alt="Six columns of the same black brushstroke painted side by side on off-white, each wiped by a rag 8 hours after painting: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. At a quarter of the usual rate and at the usual rate the wipes lift cleanly; at 4 times the rate the bar still lifts but the ring and the last stroke barely do; from 16 times the paint has set and the wipes do not show." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-drying" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [0.25, 1.0, 4.0, 16.0, 64.0, 256.0].map { column("drying $it", Settings(drying = it, wipeAfter = 480.0)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-drying.png")
```

## The hand

`tilt` is the handle's lean from upright in degrees, here past 90, where the handle leans back the
way it came.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/tilt.png" source-image="/tmp/field-docs-paint-sweep-tilt.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Upright (0) the brush paints only the top of the bar and a faint ghost of the ring. At 30 the usual stroke; from 60 a dark diagonal band crosses the ring and the scrub; at 85 the ring and the last stroke are paler; at 120, with the handle leaning back, the last stroke is a dark band all the way to its end." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-tilt" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [0.0, 30.0, 60.0, 85.0, 120.0, 179.0].map { column("tilt $it", Settings(tilt = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-tilt.png")
```

`faster` plays the whole gesture faster or slower, as a factor on its speed. At 16 times as fast the
hairs chatter on the last stroke; at 64 times the brush lands late and the loop is a blur.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/faster.png" source-image="/tmp/field-docs-paint-sweep-faster.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. Slowed down, the ring and scrub have crisp dark outlines and pale ridges. The versions look much alike up to 4 times as fast; at 16 the end of the last stroke breaks into chattering dashes; at 64 the brush lands late, leaving a few marks above a bar that starts low, the loop is a blurred swirl wider than the rest and the scrub a loose tangle." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-faster" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [0.0625, 0.25, 1.0, 4.0, 16.0, 64.0].map { column("speed × $it", Settings(faster = it)) }
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-faster.png")
```

`facets` makes the hand move in straight chords of that length instead of following the curve. At
each corner the handle turns at once and the hairs whip round: with long chords the loop is a
polygon with beaded corners.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/facets.png" source-image="/tmp/field-docs-paint-sweep-facets.png" alt="Six columns of the same black brushstroke painted side by side on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. With chords of 200 units the loop is a hexagon of straight bands and the scrub a tangle; at 60 the ring has a scalloped edge and a beaded inner rim; at 20 and below it is round again." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-facets" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [null, 200.0, 60.0, 20.0, 6.0, 2.0].map {
    column(if (it == null) "smooth" else "facets ${it.toInt()}", Settings(facets = it))
}
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-facets.png")
```

Last, the moving brush beside an ordinary one along the same path with the same pressures. The
ordinary brush has no timing and no tilt: its loop is smooth, and its last stroke narrows to a point
as the pressure falls, where the moving brush stays wide and breaks up.

```diffstatic_runner {data-lang=kotlin screenshot="assets/paint_sweeps/moving.png" source-image="/tmp/field-docs-paint-sweep-moving.png" alt="Two columns of the same path on off-white: a long vertical bar, a loop that crosses itself into a ring, a zigzag scrub, and a last straight that speeds up and lifts away into streaks. On the left, moving hairs: a wide bar, a ring with a toothed rim, a crisp zigzag scrub, and a last stroke that stays wide and breaks into streaks. On the right, an ordinary brush: a bar that narrows into the loop, a smooth ring, a softer scrub, and a last stroke that tapers to a point." wait="10" timeout="900" capture="box" open-outputs="true" padding="20" requires="paint" name="sweep-moving" embed=true}
:::static
:::fold the gesture, and a column of it painted with settings `s`
// what every column starts from: the black of the Elegy above
data class Settings(
    val stiffness: Double = 0.5,
    val ragged: Double = 0.5,
    val splay: Double = 0.0,
    val lay: Double = 0.8,
    val run: Double = 1100.0,
    val pickup: Double? = null,
    val push: Double? = null,
    val hairs: Int = 48,
    val bristles: Int? = null,
    val stiff: Double = 0.5,
    val thinner: Double = 0.0,
    val oil: Double = 1.5,
    val amount: Double = 0.5,
    val hiding: Double = 0.65,
    val tilt: Double = 30.0,
    val faster: Double = 1.0,
    val facets: Double? = null,
    val moving: Boolean = true,
    val width: Double = 200.0,
    val hair: Double? = null,
    val cohesion: Double? = null,
    val clumping: Double? = null,
    val splayFan: Double? = null,
    val splayScatter: Double? = null,
    val drying: Double = 1.0,
    val absorbent: Double = 0.0,
    val lamp: Boolean = false,
    val wipeAfter: Double? = null,
    val dry: Boolean = false,
)

// A canvas 50 cm wide and 1.25 m tall; the default brush is 10 cm wide.
fun canvas() = PaintCanvas(300, aspect = 0.4, groundColor = rgb(0.93, 0.9, 0.83), sizeMm = 500.0, seed = 7)
val straight = FLine().moveTo(500, 120).lineTo(510, 900)
val loop = FLine().moveTo(510, 900).apply {
    for (i in 1..480) {
        val a = PI / 2 + 2 * PI * 1.15 * i / 480
        lineTo(510 + 190 * cos(a), 1090 - 190 * sin(a))
    }
}
val scrub = FLine().moveTo(560, 1330).apply {
    for (i in 0 until 5) {
        lineTo(if (i % 2 == 0) 380.0 else 640.0, 1375.0 + 45 * i)
    }
}
val last =
    [1600.0, 1800.0, 2000.0, 2200.0, 2400.0].zipWithNext().mapIndexed { k, (y0, y1) ->
        FLine().moveTo(500.0 + 4 * k, y0).lineTo(504.0 + 4 * k, y1)
    }

// a long straight, a loop that crosses itself, a scrub back and forth, and a last straight in four
// pieces, each faster and lighter: 100, 200, 350, then 600 mm/s, lifting off the canvas at the end
fun gesture(c: PaintCanvas, s: Settings): TimedGesture {
    fun part(line: FLine, speed: Double, vararg pressure: Double) =
        c.gesture(line, speed = speed, pressure = pressure.toList(), tilt = s.tilt, facets = s.facets)
    var g =
        part(straight, 250.0, 0.8, 1.0, 1.0).then(part(loop, 250.0, 1.0, 0.9)).pause(0.1).then(part(scrub, 300.0, 1.1))
    val press = [1.0, 0.8, 0.6, 0.3, -0.2]
    for ((k, speed) in [100.0, 200.0, 350.0, 600.0].withIndex()) g =
        g.then(part(last[k], speed, press[k], press[k + 1]))
    return g.faster(s.faster)
}

fun column(label: String, s: Settings): Pair<String, Img> {
    val c = canvas()
    try {
        if (s.absorbent > 0.0) c.prime(rgb(0.93, 0.9, 0.83), texture = 0.0, absorbent = s.absorbent)
        val config = BrushConfig(
            bristles = s.bristles,
            hair = s.hair,
            stiffness = s.stiffness,
            ragged = s.ragged,
            splay = s.splay,
            lay = s.lay,
            run = s.run,
            pickup = s.pickup,
            push = s.push,
        )
        val black = rgb(0.05, 0.045, 0.04)
        if (s.moving) {
            val brush =
                c.dynamicBrush(BrushKind.HogFlat, s.width, hairs = s.hairs, segments = 6, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            brush.set(
                cohesion = s.cohesion,
                clumping = s.clumping,
                splayFan = s.splayFan,
                splayScatter = s.splayScatter,
            )
            c.stroke(gesture(c, s), brush)
        } else {
            // an ordinary brush along the same path, with the same pressures; it has no timing or tilt
            val brush =
                c.brush(BrushKind.HogFlat, s.width, config = config)
                    .load(
                        black,
                        hiding = s.hiding,
                        stiff = s.stiff,
                        drying = s.drying,
                        thinner = s.thinner,
                        oil = s.oil,
                        amount = s.amount,
                    )
            c.stroke(straight, brush, pressure = 0.8..1.0)
            c.stroke(loop, brush, pressure = 1.0..0.9)
            c.stroke(scrub, brush, pressure = 1.1..1.1)
            c.stroke(FLine().moveTo(500, 1600).lineTo(516, 2400), brush, pressure = 1.0..0.0)
        }
        // a rag wiped across the bar, the loop and the last stroke after some minutes (infinite: dry first)
        s.wipeAfter?.let { minutes ->
            if (minutes.isInfinite()) c.dry() else c.wait(minutes)
            val rag = c.rag(150.0)
            for (y in [500.0, 1090.0, 2000.0]) c.wipe(FLine().moveTo(250, y).lineTo(760, y + 10), rag, [0.8])
        }
        if (s.dry) c.dry()
        val png = if (s.lamp) c.png(relief = 0.3, light = PaintLight.Gallery) else c.png(relief = 0.3)
        return label to imgFromBytes(png)
    } finally {
        c.close()
    }
}

// the same columns twice: a row with moving hairs above a row with an ordinary brush
fun rows(cells: (moving: Boolean) -> List<Pair<String, Img>>): Img {
    val rows =
        ["moving hairs" to true, "ordinary brush" to false].map { (name, moving) ->
            val strokes = cells(moving)
            name to montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
        }
    return montage(rows, columns = 1, cellSide = 4000, textScale = 3)
}
:::endfold
val strokes = [column("moving hairs", Settings()), column("ordinary brush", Settings(moving = false))]
val sheet = montage(strokes, columns = strokes.size, cellSide = 750, textScale = 3)
showImg(sheet)
sheet.savePng("/tmp/field-docs-paint-sweep-moving.png")
```

See also: [Brushes with moving hairs](paint_gestures.md), [Paint and pencil from FLines](paint_canvas.md#brushes-made-to-order).

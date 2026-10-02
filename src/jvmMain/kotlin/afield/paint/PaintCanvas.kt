@file:afield.CompletionCategory("Graphics")
package afield.paint

import afield.Rect
import afield.graphics.FLine
import afield.stdlib.Vec4
import afield.util.PngEncoder
import java.lang.foreign.MemorySegment

/**
 * A simulated oil-painting canvas that [FLine]s are painted and drawn onto.
 *
 * The simulation is [claude-paint](https://github.com/aliceisjustplaying/claude-paint)'s `paint`
 * crate: brushes made of individual bristles carrying paint, Kubelka–Munk mixing on the canvas,
 * paint that stays wet, stiffens and dries over simulated minutes, graphite and chalk that catch
 * the tooth of the ground, stippling, glazes, and craquelure. Field reaches it through a small Rust
 * wrapper, `native/field-paint`, which is optional — build it once with `./gradlew buildPaintNative`
 * (needs `cargo`).
 *
 * ```
 * val canvas = PaintCanvas(900, aspect = 1.4)
 * val brush = canvas.brush(BrushKind.HogFlat, width = 30.0)
 * brush.load(rgb(0.75, 0.3, 0.1))
 * canvas.stroke(FLine().moveTo(100, 300).cubicTo(300, 100, 600, 500, 900, 300), brush)
 * display("main", canvas.png())
 * ```
 *
 * Coordinates are the crate's own: the canvas is 1000 units wide and `1000 / aspect` tall, with
 * y growing downwards, whatever its pixel width. Set [view] to paint lines drawn in some other
 * rectangle instead; that rectangle is stretched over the whole canvas.
 *
 * Methods that take a *region* ([hatch], [stipple], [work], [glaze], [erase]) read a closed
 * [FLine]: every subpath is a ring, and a point is inside when it is inside an odd number of them,
 * so a ring inside another makes a hole.
 *
 * A canvas holds native memory. It is freed when the canvas is garbage-collected, or at once by
 * [close].
 */
class PaintCanvas internal constructor(
    internal val handle: MemorySegment,
    /** width / height. */
    val aspect: Double,
    seed: Long,
    /** The rectangle of FLine coordinates that maps onto the canvas, or `null` for canvas units. */
    var view: Rect?,
) : AutoCloseable {

    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::canvasFree))

    /** Pixel width and height of the picture [render] returns. */
    val pixelSize: Pair<Int, Int> = PaintNative.canvasSize(handle)

    /** Pixel width. */
    val widthPx: Int get() = pixelSize.first

    /** Height in canvas units (the width is always 1000). */
    val heightUnits: Double get() = 1000.0 / aspect

    private var nextSeed = seed * 7919 + 1
    internal fun seed(): Long = nextSeed++

    /** A brush held over this canvas. Load it with [PaintBrush.load] before stroking. */
    fun brush(kind: BrushKind = BrushKind.Round, width: Double = 20.0, point: Double = 0.0): PaintBrush =
        PaintBrush(kind, width, point, seed())

    /**
     * A graphite pencil of [grade] ("2H", "HB", "4B", ...) for [draw], [sketch] and [hatch]. Hard
     * grades lay a pale grey and stay sharp; soft ones lay more, darker, and blunt as they draw.
     */
    fun pencil(grade: String = "HB"): Pencil = Pencil(DrawingMedium.Graphite, PaintNative.pencilSoftness(grade))

    /** Natural black chalk: deep, matte, broad and crumbly; it wears fast. */
    fun chalk(): Pencil = Pencil(DrawingMedium.Chalk, 6.0)

    // ---- brushes ------------------------------------------------------------------------------

    /**
     * Drag [brush] along every subpath of [line].
     *
     * Each subpath is one movement of the hand. Curves are sampled every [spacing] canvas units
     * (the crate resamples again to ~2 px, so this only has to follow the curve). The brush is not
     * reloaded between subpaths: it runs out the way a real one does, which is most of the look.
     *
     * @param pressure pressure at the start and end of each movement, 0..1.
     * @param shake hand unsteadiness: 0 mechanically exact, 1 a normal hand.
     * @param along hold the brush's wide axis along the stroke rather than across it.
     * @param swell pressure multipliers at evenly spaced knots along each movement.
     */
    fun stroke(
        line: FLine,
        brush: PaintBrush,
        pressure: ClosedFloatingPointRange<Double> = 0.8..0.8,
        shake: Double = 1.0,
        attack: Double = 0.08,
        release: Double = 0.15,
        along: Boolean = false,
        swell: List<Double> = emptyList(),
        spacing: Double = 2.0,
    ): PaintCanvas = apply {
        for (xy in polylines(line, spacing)) {
            PaintNative.drag(handle, brush.handle, xy, pressure.start.toFloat(), pressure.endInclusive.toFloat(),
                shake.toFloat(), attack.toFloat(), release.toFloat(), along, swell)
        }
    }

    /**
     * Touch the brush tip down at ([x], [y]) and lift it, with no path: a dab. Harder [pressure]
     * makes a bigger, fuller mark; [drag] moves the hand that far while the hairs are down,
     * [twist] rolls the handle (radians) and [angle] turns a flat's wide axis (radians).
     */
    fun touch(
        brush: PaintBrush, x: Double, y: Double, pressure: Double = 0.6,
        drag: Pair<Double, Double> = 0.0 to 0.0, twist: Double = 0.0, angle: Double = 0.0,
    ): PaintCanvas = apply {
        val (dx, dy) = drag
        PaintNative.touch(handle, brush.handle, toCanvasX(x).toFloat(), toCanvasY(y).toFloat(), pressure.toFloat(),
            toCanvasDx(dx).toFloat(), toCanvasDy(dy).toFloat(), twist.toFloat(), angle.toFloat())
    }

    /**
     * Work a whole [region] with many strokes, the way a painter blocks in an area: the crate
     * plans the strokes (lengths, directions, curvature, broken and restated strokes, trips to the
     * palette) from a [Handling] preset, and paints each with a simulated brush.
     *
     * @param angle stroke direction, radians (0 is left to right); `null` keeps the preset's.
     * @param width brush width in canvas units; `null` keeps the preset's brush.
     * @param length stroke length range in canvas units, `null` for the preset's.
     * @param coverage how many layers of strokes cover each point on average.
     * @param cross criss-cross: strokes fall into two families at ± this angle (radians).
     * @param clip stop the bristles exactly at the region's edge, instead of letting strokes
     *   overrun it a little.
     * @param feather soften the region's edge by this many canvas units.
     */
    fun work(
        region: FLine,
        color: Vec4,
        handling: Handling = Handling.Body,
        hiding: Double = 0.85,
        stiff: Double = 0.8,
        angle: Double? = null,
        angleJitter: Double? = null,
        width: Double? = null,
        length: ClosedFloatingPointRange<Double>? = null,
        coverage: Double? = null,
        pressure: ClosedFloatingPointRange<Double>? = null,
        cross: Double? = null,
        order: StrokeOrder? = null,
        clip: Boolean? = null,
        feather: Double = 0.0,
    ): PaintCanvas = apply {
        val nan = Float.NaN
        val (orderCode, sweep) = when (order) {
            null -> -1 to 0f
            is StrokeOrder.Passages -> 0 to 0f
            is StrokeOrder.Sweep -> 1 to order.angle.toFloat()
            is StrokeOrder.Scatter -> 2 to 0f
        }
        PaintNative.work(handle, rings(region), toCanvasLength(feather).toFloat(), handling.code,
            linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(), stiff.toFloat(),
            angle?.toFloat() ?: nan, angleJitter?.toFloat() ?: nan,
            length?.start?.let { toCanvasLength(it).toFloat() } ?: nan,
            length?.endInclusive?.let { toCanvasLength(it).toFloat() } ?: nan,
            coverage?.toFloat() ?: nan, pressure?.start?.toFloat() ?: nan, pressure?.endInclusive?.toFloat() ?: nan,
            cross?.toFloat() ?: nan, orderCode, sweep, when (clip) { null -> -1; true -> 1; false -> 0 },
            width?.let { toCanvasLength(it).toFloat() } ?: nan, seed())
    }

    /**
     * Draw a hand outline through the rough points of [line] — each subpath one outline, closed
     * when its last point returns to its first or [closed] says so — and drag [brush] along the
     * strokes a hand would use: they lift and restart, overlap or leave gaps, overshoot corners and
     * restate stretches, each with its own pressure. Returns those strokes as an [FLine] (in the
     * same coordinates as [line]), so the drawing can be reused; pass `brush = null` to get the
     * strokes without painting them.
     *
     * @param amount scales every irregularity of the [character]; 0 is a clean curve.
     * @param size the hand's scale in canvas units; `null` takes it from the outline's extent.
     */
    fun outline(
        line: FLine,
        brush: PaintBrush?,
        character: OutlineCharacter = OutlineCharacter.Firm,
        pressure: Double = 0.7,
        amount: Double? = null,
        closed: Boolean? = null,
        size: Double? = null,
    ): FLine {
        val out = FLine()
        for (piece in line.pieces()) {
            val xy = toCanvas(piece.sampleByDistance(toLineUnits(4.0).toFloat()).flatMap { listOf(it.x.toDouble(), it.y.toDouble()) })
            if (xy.size < 4) continue
            val first = piece.nodes.first().to
            val last = piece.nodes.last().toOrNull
            val isClosed = closed ?: (last == null || (last.x - first.x) * (last.x - first.x) + (last.y - first.y) * (last.y - first.y) < 1e-6f)
            val strokes = PaintNative.outline(handle, brush?.handle, xy, isClosed, character.code,
                amount?.toFloat() ?: Float.NaN, pressure.toFloat(), size?.let { toCanvasLength(it).toFloat() } ?: 0f, seed())
            for (s in strokes) {
                for (i in s.indices step 2) {
                    val x = fromCanvasX(s[i].toDouble())
                    val y = fromCanvasY(s[i + 1].toDouble())
                    if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
                }
            }
        }
        return out
    }

    /**
     * A transparent Kubelka–Munk glaze of [color], [coats] thick, over [region] (or the whole
     * canvas). It dries at once. Glaze over dry paint: call [dry] first if the paint under it
     * is still wet.
     */
    fun glaze(color: Vec4, region: FLine? = null, coats: Double = 1.0, hiding: Double = 0.05, feather: Double = 0.0): PaintCanvas = apply {
        PaintNative.glaze(handle, region?.let { rings(it) }, toCanvasLength(feather).toFloat(),
            linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(), coats.toFloat())
    }

    // ---- graphite and chalk -------------------------------------------------------------------

    /**
     * Draw [line] with [pencil]: each subpath one line, smoothed through its points and wavering a
     * little ([tremor] canvas units), its pressure following [pressure] (one value, a start and
     * end, or a profile through evenly spaced stations). A light line catches only the tops of the
     * ground's tooth and breaks up into its grain; a heavy one fills in. The pencil wears as it
     * draws. Graphite skips wet paint.
     *
     * @param ruler a ruled line: straight between the points, no tremor.
     */
    fun draw(line: FLine, pencil: Pencil, pressure: List<Double> = listOf(0.6), tremor: Double = 0.4, ruler: Boolean = false): PaintCanvas = apply {
        for (xy in polylines(line, 2.0)) {
            pencil.worn += PaintNative.drawLine(handle, pencil, xy, pressure, toCanvasLength(tremor).toFloat(), !ruler, ruler, seed())
        }
    }

    /** [draw] with one pressure for the whole line. */
    fun draw(line: FLine, pencil: Pencil, pressure: Double, tremor: Double = 0.4, ruler: Boolean = false): PaintCanvas =
        draw(line, pencil, listOf(pressure), tremor, ruler)

    /**
     * Sketch along [line]: [passes] light strokes per subpath, each wandering about [wander]
     * canvas units off the path and broken into pieces, the way a searching line is drawn.
     */
    fun sketch(line: FLine, pencil: Pencil, pressure: Double = 0.5, passes: Int = 3, wander: Double = 2.0, tremor: Double = 0.4): PaintCanvas = apply {
        for (xy in polylines(line, 2.0)) {
            pencil.worn += PaintNative.sketchLine(handle, pencil, xy, pressure.toFloat(), passes,
                toCanvasLength(wander).toFloat(), toCanvasLength(tremor).toFloat(), seed())
        }
    }

    /**
     * Hatch [region]: short parallel strokes at [angle] (radians), [spacing] canvas units apart,
     * each at most [length] long, bowed a little, heavy where they start and lifting off at the end.
     */
    fun hatch(region: FLine, pencil: Pencil, angle: Double = 0.8, spacing: Double = 6.0, length: Double = 60.0, pressure: Double = 0.6): PaintCanvas = apply {
        pencil.worn += PaintNative.hatch(handle, pencil, rings(region), angle.toFloat(),
            toCanvasLength(spacing).toFloat(), toCanvasLength(length).toFloat(), pressure.toFloat(), seed())
    }

    /**
     * A kneaded eraser over [region]: lifts [strength] (0..1) of the loose drawing, better from the
     * tops of the tooth than the hollows, and never quite all of it. Fixed or painted-over drawing
     * stays.
     */
    fun erase(region: FLine, strength: Double = 0.8, feather: Double = 0.0): PaintCanvas = apply {
        PaintNative.erase(handle, rings(region), toCanvasLength(feather).toFloat(), strength.toFloat())
    }

    /** Spray fixative over the drawing: an eraser no longer lifts it. */
    fun fixDrawing(): PaintCanvas = apply { PaintNative.fixDrawing(handle) }

    // ---- stipple ------------------------------------------------------------------------------

    /**
     * Stipple [region] with many separate touches of an upright stippling brush [size] canvas units
     * wide: [coverage] touches per point on average, in [color]. Touches into wet paint pick some
     * of it up and mix with it, like every other brush mark.
     *
     * @param pressure peak pressure range; harder touches make bigger marks.
     * @param cluster 0 spreads the touches evenly, 1 gathers them in clumps.
     * @param drag how far the tip drifts while down, in canvas units.
     * @param dipEvery touches between trips to the palette; each touch lays a little less.
     * @param feather soften the region's edge by this many units, so the touches thin out there.
     */
    fun stipple(
        region: FLine, color: Vec4, size: Double = 3.0, coverage: Double = 1.0,
        hiding: Double = 0.4, stiff: Double = 0.3, pressure: ClosedFloatingPointRange<Double> = 0.4..0.75,
        cluster: Double = 0.15, drag: Double = 0.0, dipEvery: Int = 24, feather: Double = 0.0,
    ): PaintCanvas = apply {
        PaintNative.stipple(handle, rings(region), toCanvasLength(feather).toFloat(), toCanvasLength(size).toFloat(),
            coverage.toFloat(), linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(), stiff.toFloat(),
            pressure.start.toFloat(), pressure.endInclusive.toFloat(), cluster.toFloat(), toCanvasLength(drag).toFloat(),
            dipEvery, seed())
    }

    // ---- the surface and time -----------------------------------------------------------------

    /**
     * Lay a ground over the whole canvas: [microns] µm of [color] at [hiding], with body [stiff]
     * (fluid chalk-glue ≈ 0.2, oil lead white ≈ 0.6), roughened by [texture] before it levels.
     * Dries the canvas first.
     */
    fun prime(color: Vec4, hiding: Double = 0.85, microns: Double = 60.0, stiff: Double = 0.4, texture: Double = 0.3): PaintCanvas = apply {
        PaintNative.prime(handle, linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(),
            microns.toFloat(), stiff.toFloat(), texture.toFloat(), seed())
    }

    /**
     * Age the painting: craquelure grown through paint and ground in a stress field, cupped,
     * with dirt in the cracks. [CrackKind.Aged] is a hierarchy of cracks developing patchily;
     * [CrackKind.Even] one uniform network. Fitted to the ground's thickness, so it reads best on
     * an oil ground. Dries the canvas first.
     */
    fun crack(kind: CrackKind = CrackKind.Aged): PaintCanvas = apply { PaintNative.crack(handle, kind.code, seed()) }

    /** Let [minutes] of simulated time pass: wet paint stiffens, levels and sets where it lies. */
    fun wait(minutes: Double): PaintCanvas = apply { PaintNative.waitMinutes(handle, minutes.toFloat()) }

    /** Wait until every film is touch-dry. Paint laid afterwards no longer mixes with it. */
    fun dry(): PaintCanvas = apply { PaintNative.dry(handle) }

    /** Where the painting stands: its clock, the hand time spent, and how much paint is still wet. */
    val stats: PaintStats get() = PaintNative.stats(handle)

    // ---- looking at it ------------------------------------------------------------------------

    /**
     * The picture as sRGB ARGB pixels, row major, [pixelSize] wide and high.
     *
     * By default this renders a copy dried to touch-dry, the way the painting will look; the canvas
     * itself stays wet. [wet] shows it as it looks now instead, wet paint at its laid thickness (no
     * relief). [relief] lights the impasto from the upper left (0.3–1 is reasonable) and [gloss]
     * adds a varnish sheen.
     */
    fun render(relief: Double = 0.0, gloss: Double = 0.0, wet: Boolean = false): IntArray {
        val n = pixelSize.first * pixelSize.second
        return if (wet) PaintNative.renderWetArgb(handle, n)
        else PaintNative.renderArgb(handle, n, relief.toFloat(), gloss.toFloat())
    }

    /** [render] encoded as PNG — what `display("main", ...)` shows as an image. */
    fun png(relief: Double = 0.6, gloss: Double = 0.0, wet: Boolean = false): ByteArray =
        PngEncoder.encode(render(relief, gloss, wet), pixelSize.first, pixelSize.second)

    /** Save a dried copy with the crate's own dithered PNG writer (no relief). */
    fun save(path: String) = PaintNative.savePng(handle, path)

    /** An independent copy of this canvas, wet paint and all: try something, and keep the original. */
    fun copy(): PaintCanvas = PaintCanvas(PaintNative.canvasCopy(handle), aspect, nextSeed, view)

    /** Write the whole state, wet paint included, so [PaintCanvas.load] can carry on from here. */
    fun saveState(path: String) = PaintNative.saveState(handle, path)

    override fun close() = cleanable.clean()

    // ---- coordinates --------------------------------------------------------------------------

    private fun toCanvasX(x: Double) = view?.let { (x - it.x) / it.w * 1000.0 } ?: x
    private fun toCanvasY(y: Double) = view?.let { (y - it.y) / it.h * heightUnits } ?: y
    private fun fromCanvasX(x: Double) = view?.let { it.x + x / 1000.0 * it.w } ?: x
    private fun fromCanvasY(y: Double) = view?.let { it.y + y / heightUnits * it.h } ?: y
    private fun toCanvasDx(d: Double) = view?.let { d / it.w * 1000.0 } ?: d
    private fun toCanvasDy(d: Double) = view?.let { d / it.h * heightUnits } ?: d
    private fun toLineUnits(d: Double) = view?.let { d * it.w / 1000.0 } ?: d
    private fun toCanvasLength(d: Double) = view?.let { d / it.w * 1000.0 } ?: d

    private fun toCanvas(xy: List<Double>): FloatArray =
        FloatArray(xy.size) { i -> (if (i % 2 == 0) toCanvasX(xy[i]) else toCanvasY(xy[i])).toFloat() }

    /** Each subpath of [line] sampled every [spacing] canvas units, in canvas units. */
    private fun polylines(line: FLine, spacing: Double): List<FloatArray> = line.pieces().mapNotNull { piece ->
        val pts = piece.sampleByDistance(toLineUnits(spacing).toFloat())
        if (pts.size < 2) null else toCanvas(pts.flatMap { listOf(it.x.toDouble(), it.y.toDouble()) })
    }

    /** Each subpath of [region] as a ring, in canvas units. */
    private fun rings(region: FLine): List<FloatArray> = polylines(region, 2.0).filter { it.size >= 6 }.also {
        require(it.isNotEmpty()) { "a region needs at least one closed subpath with some area" }
    }

    companion object {
        /**
         * A new canvas.
         *
         * @param widthPx pixel width; the height follows from [aspect].
         * @param aspect width / height.
         * @param ground what the canvas starts as; [PaintGround.Bare] (the default) is a flat ground
         *   of [groundColor].
         * @param groundColor the colour of a [PaintGround.Bare] or [PaintGround.Linen] canvas, sRGB;
         *   white by default.
         * @param seed seeds the brushes' bristles, the marks' irregularity and an oil ground's
         *   linen; the same seed and the same calls paint the same picture.
         * @param view the rectangle of FLine coordinates that maps onto the canvas, or `null` for
         *   canvas units.
         * @param sizeMm the physical width of the canvas in millimetres; `null` is 700 mm for a bare
         *   or linen canvas and each oil style's own size. Every mark has a real size — a pencil
         *   point is 0.3 mm, a crack opens a few dozen µm — so a small sheet at a given pixel width
         *   shows them larger. A pencil drawing at 800 px reads best at about 200 mm.
         */
        operator fun invoke(
            widthPx: Int = 800,
            aspect: Double = 1.0,
            ground: PaintGround = PaintGround.Bare,
            groundColor: Vec4 = Vec4(1f, 1f, 1f, 1f),
            seed: Long = 1,
            view: Rect? = null,
            sizeMm: Double? = null,
        ): PaintCanvas = PaintCanvas(
            PaintNative.canvasNew(widthPx, aspect.toFloat(), ground.code, seed,
                linear(groundColor.x), linear(groundColor.y), linear(groundColor.z), sizeMm?.toFloat() ?: 0f),
            aspect, seed, view,
        )

        /** A canvas written by [saveState], wet paint and all. */
        fun load(path: String, seed: Long = 1, view: Rect? = null): PaintCanvas {
            val h = PaintNative.loadState(path)
            val (w, ht) = PaintNative.canvasSize(h)
            return PaintCanvas(h, w.toDouble() / ht, seed, view)
        }

        /** Whether `native/field-paint` has been built where Field looks for it. */
        val isAvailable: Boolean get() = PaintLibrary.isAvailable

        /** The paint engine version the wrapper was built against. */
        val engine: Int get() = PaintNative.engine()
    }
}

/** Clock, hand time and drying state of a [PaintCanvas]. Shares are of the canvas's area. */
data class PaintStats(
    /** Simulated minutes passed ([PaintCanvas.wait], [PaintCanvas.dry]). */
    val clockMinutes: Double,
    /** An estimate of the hand time the marks so far would take a painter, in minutes. */
    val handMinutes: Double,
    /** Workable wet paint: blends, lifts, levels. */
    val open: Double,
    /** Near its gel point: stiff and sticky, barely blends. */
    val setting: Double,
    /** A set film: doesn't move, grabs the brush. */
    val tacky: Double,
    /** Touch-dry paint, or bare ground. */
    val dry: Double,
)

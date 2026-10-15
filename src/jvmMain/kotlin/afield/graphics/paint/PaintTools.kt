@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

import afield.math.linalg.Vec4
import java.lang.foreign.MemorySegment
import kotlin.math.pow

/** What a [PaintCanvas] starts as. */
enum class PaintGround(internal val code: Int) {
    /** A flat ground of the canvas's `groundColor`, no texture. */
    Bare(0),

    /** The crate's oil canvas: woven linen, sized, primed with a warm red-brown ground. */
    Oil(1),

    /** The crate's "red ground" canvas: a red-brown layer under two pale knifed and rolled grounds. */
    OilRedGround(2),

    /** Raw linen: the weave with nothing on it, in the canvas's `groundColor`. Prime it yourself. */
    Linen(3),

    /**
     * Unprimed cotton duck, as soak-stain painters used it: the bare cloth in its own colour (the
     * canvas's `groundColor` is ignored), with no ground to seal it. Engine 3 and later.
     */
    RawCotton(4),

    /** Unprimed linen cloth in its own colour, as [RawCotton]. Engine 3 and later. */
    RawLinen(5),
}

/** The brushes the crate models. */
enum class BrushKind(internal val code: Int) {
    /** Soft round sable: smooth and precise, ploughs little paint. */
    Round(0),

    /** Stiff hog flat: broad, ragged, ploughs wet paint aside. */
    HogFlat(1),

    /** A flat with a rounded end. */
    Filbert(2),

    /** A fan, for feathering and broken texture. */
    Fan(3),

    /** A long thin rigger, for lines. */
    Rigger(4),

    /** A soft badger blender: moves wet paint more than it lays it. */
    Badger(5),

    /** An upright stippling brush: short stiff hair cut flat, for dabs straight down. */
    Stippler(6),
}

/**
 * How a brush is made, beyond its kind and width: any field left `null` keeps the kind's own. Lengths
 * are canvas units; the rest are the crate's own scales.
 *
 * ```
 * canvas.brush(BrushKind.HogFlat, 30.0, config = BrushConfig(bristles = 40, stiffness = 0.9, ragged = 0.6))
 * ```
 */
data class BrushConfig(
    /** Number of simulated bristles (1..4000). */
    val bristles: Int? = null,
    /** How far the tips trail behind the contact at full pressure. */
    val length: Double? = null,
    /** 0 soft hair (sable, badger) .. 1 stiff hog bristle. */
    val stiffness: Double? = null,
    /** Bristle thickness multiplier: how much neighbouring hairs overlap. */
    val hair: Double? = null,
    /** The stroke length over which a full load runs down (e-folding), canvas units. */
    val run: Double? = null,
    /** Paint thickness laid at the start of a fully loaded stroke (coats). */
    val lay: Double? = null,
    /** Share of the wet paint under it a bristle takes up per pass. */
    val pickup: Double? = null,
    /** Share of the wet paint a moving bristle ploughs aside per pass. */
    val push: Double? = null,
    /** How much the footprint grows under pressure. */
    val splay: Double? = null,
    /** Unevenness of the bristle lengths: ragged edges and broken marks. */
    val ragged: Double? = null,
    /** 0 a blunt tuft .. 1 a fine point (the `point` argument of [PaintCanvas.brush], when given here instead). */
    val point: Double? = null,
    /**
     * How far a bristle's dryness keeps it on the canvas's tooth: 0 (as made), pressure pushes it
     * into the weave's hollows however dry it is; 1, a bristle running dry rides only the peaks,
     * however hard it is pressed, and breaks into dry-brush skips on a ground with grain (on a smooth
     * one, none: it thins evenly). Past 1 drier still; below 0 the reverse.
     */
    val tooth: Double? = null,
) {
    internal fun values(): FloatArray = floatArrayOf(
        bristles?.toFloat() ?: Float.NaN, length?.toFloat() ?: Float.NaN, stiffness?.toFloat() ?: Float.NaN,
        hair?.toFloat() ?: Float.NaN, run?.toFloat() ?: Float.NaN, lay?.toFloat() ?: Float.NaN,
        pickup?.toFloat() ?: Float.NaN, push?.toFloat() ?: Float.NaN, splay?.toFloat() ?: Float.NaN,
        ragged?.toFloat() ?: Float.NaN, point?.toFloat() ?: Float.NaN, tooth?.toFloat() ?: Float.NaN)
}

/**
 * Which part of a brush a dip reaches ([PaintBrush.load]): a painter double-loads a brush by
 * dipping one edge in a second pile.
 */
data class LoadPart(
    /** The edge of the brush's wide axis that goes in: -1 one edge, 1 the other, 0 the whole width alike. */
    val side: Double = 0.0,
    /** The share of the width, from that edge, that reaches the paint. */
    val share: Double = 1.0,
    /** 0 every bristle in reach takes it up alike; 1 in streaks, as from an unevenly knifed pile. */
    val streak: Double = 0.0,
    /**
     * Where the streaks fall. By default they follow from the brush as it is (how full it is), so a
     * restored brush dips alike; give a seed to fix them, as two eyes of a stereo pair must.
     */
    val seed: Long? = null,
)

/**
 * A light to look at the canvas under ([PaintCanvas.render] `light =`): from [azimuth] degrees (0
 * from the right, 90 from the top, 135 the upper left) at [elevation] degrees above the canvas.
 * Relief casts shadows along it, and wet paint has an oily sheen. [strength] scales the shading.
 */
data class PaintLight(val azimuth: Double = 135.0, val elevation: Double = 25.0, val strength: Double = 1.0) {
    companion object {
        /** A low lamp raking from the upper left, the way a painter looks at the surface. */
        val Raking = PaintLight(135.0, 12.0)

        /** A gallery's high light, 55° from above and a little left. */
        val Gallery = PaintLight(110.0, 55.0)
    }
}

/** How [PaintCanvas.work] covers a region: the crate's own presets for its oil painters. */
enum class Handling(internal val code: Int) {
    /** Long sweeping strokes with a big brush, for laying in large areas. */
    Broad(0),

    /** Medium strokes, the main body of the painting. */
    Body(1),

    /** Short, crisp strokes with a small brush, clipped to the region. */
    Detail(2),

    /** Short parallel touches in clumps. */
    Hatch(3),

    /** Long soft strokes of thin paint with a soft filbert. */
    Glaze(4),

    /** Short back-and-forth scrubs that leave the paint broken. */
    Scumble(5),

    /** A clean blender that lays nothing and softens the wet paint already there. */
    Blend(6),
}

/** The order [PaintCanvas.work] lays its strokes in. */
sealed interface StrokeOrder {
    /** Patch by patch, the strokes in a patch laid side by side. */
    data object Passages : StrokeOrder

    /** One sweep across the region in direction [angle] (radians; π/2 is top to bottom). */
    data class Sweep(val angle: Double) : StrokeOrder

    /** Anywhere, in random order. */
    data object Scatter : StrokeOrder
}

/** How a hand draws an outline ([PaintCanvas.outline]). */
enum class OutlineCharacter(internal val code: Int) {
    /** Little wobble, long confident strokes, slight overshoots at corners. */
    Firm(0),

    /** Shorter strokes, restated, drifting a little off the line. */
    Searching(1),

    /** Faceted, with notches and gaps. */
    Broken(2),

    /** Rounded lobes and a soft, lost-in-places edge. */
    Soft(3),
}

/** The two kinds of craquelure ([PaintCanvas.crack]). */
enum class CrackKind(internal val code: Int) {
    /** A hierarchy of cracks, developing patchily, with grime. */
    Aged(0),

    /** One even network, every crack alike. */
    Even(1),
}

/** What a [Pencil] is made of. */
enum class DrawingMedium(internal val code: Int) { Graphite(0), Chalk(1) }

/**
 * A pencil or stick of chalk for [PaintCanvas.draw], [PaintCanvas.sketch] and [PaintCanvas.hatch].
 * Get one from [PaintCanvas.pencil] or [PaintCanvas.chalk]. It remembers how far it has drawn, and
 * its line widens as it blunts; [sharpen] starts it fresh.
 */
class Pencil internal constructor(val medium: DrawingMedium, val softness: Double) : PaintTool {
    /** Millimetres drawn since the pencil was last sharpened. */
    var worn: Double = 0.0
        internal set(v) { field = v }

    /** Sharpen the point. */
    fun sharpen(): Pencil = apply { worn = 0.0 }
}

/**
 * Something a painter holds and puts down again: a [PaintBrush], [DynamicBrush], [WaterBrush],
 * [PaintKnife], [PaintRag] or [Pencil].
 */
sealed interface PaintTool

/**
 * A brush held over a [PaintCanvas], carrying paint between strokes.
 *
 * Get one from [PaintCanvas.brush]. It keeps what it picks up: dip it again with [load] (wiping some
 * or none of what is there) and it carries on dirty, the way a real one does. [state] snapshots it
 * bristle by bristle, and [PaintBrush.restore] puts it back exactly. It holds native memory, freed
 * when collected or by [close].
 */
class PaintBrush internal constructor(internal val handle: MemorySegment, val kind: BrushKind) : PaintTool, AutoCloseable {

    internal constructor(kind: BrushKind, width: Double, point: Double, seed: Long, engine: Int, config: BrushConfig?) :
        this(PaintNative.brushNewV2(kind.code, width.toFloat(), seed, engine,
            (config ?: BrushConfig()).let { if (it.point == null) it.copy(point = point) else it }.values()), kind)

    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::brushFree))

    /** Width at full pressure, canvas units. */
    val width: Double get() = PaintNative.brushConfig(handle)[1].toDouble()

    /** How the brush is made: every construction value as it is, none left to the preset. */
    val config: BrushConfig get() = PaintNative.brushConfig(handle).let { v ->
        BrushConfig(v[2].toInt(), v[3].toDouble(), v[4].toDouble(), v[5].toDouble(), v[6].toDouble(), v[7].toDouble(),
            v[8].toDouble(), v[9].toDouble(), v[10].toDouble(), v[11].toDouble(), v[12].toDouble(), v[13].toDouble())
    }

    /**
     * Dip the brush in paint of masstone [color] (sRGB, as `rgb(...)` gives).
     *
     * @param hiding how much one coat hides what is under it: 0.05 a glaze, 0.5 a scumble, 0.92 body
     *   colour.
     * @param stiff 0 fluid and rich in medium, 1 stiff tube paint. Stiff paint keeps its brush
     *   marks as it dries; fluid paint levels.
     * @param drying drying rate relative to average paint (lead white about 2, bone black 0.4).
     * @param amount fraction of a full load to take up.
     * @param wipe fraction of the paint already in the brush to wipe off first. 0 mixes the new
     *   paint into what is there.
     * @param thinner share (0..0.95) of what the brush takes up that is solvent beside the paint: a
     *   thinned stroke lays a thinner film, and the solvent evaporates from it over minutes (engine 3
     *   and later).
     * @param turps share (0..0.9) of the paint's own volume that is turpentine: it flows on the
     *   brush, doesn't clump the hairs, spatters readily, and evaporates as it is laid (engine 4).
     * @param oil richness in oil relative to tube paint (1): more with medium, less blotted. A
     *   film's gloss follows it (engine 4).
     * @param part dip only part of the brush, to double-load it.
     */
    fun load(
        color: Vec4,
        hiding: Double = 0.92,
        stiff: Double = 0.8,
        drying: Double = 1.0,
        amount: Double = 1.0,
        wipe: Double = 0.85,
        thinner: Double = 0.0,
        turps: Double = 0.0,
        oil: Double = 1.0,
        part: LoadPart? = null,
    ): PaintBrush = apply {
        loadNative(linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(), Float.NaN, stiff.toFloat(), drying.toFloat(),
            amount, wipe, thinner, turps, oil, part)
    }

    /**
     * Dip the brush in a pile mixed from tube paints, by name and parts by volume —
     * `load(mapOf("lead white" to 3.0, "yellow ochre" to 1.0))`. The tubes mix the way paint does
     * (Mixbox, weighted by each pigment's tinting strength), and the pile keeps their hiding,
     * stiffness and drying rate. [medium] (0..1) thins it with oil. [Tubes.names] lists the tubes.
     * [thinner], [turps], [oil] and [part] are as in the other [load].
     */
    fun load(
        tubes: Map<String, Double>, medium: Double = 0.0, amount: Double = 1.0, wipe: Double = 0.85,
        thinner: Double = 0.0, turps: Double = 0.0, oil: Double = 1.0, part: LoadPart? = null,
    ): PaintBrush = apply {
        val m = PaintNative.mixTubes(tubes, medium.toFloat())
        loadNative(m[0], m[1], m[2], 0.9f, m[3], m[4], m[5], amount, wipe, thinner, turps, oil, part)
    }

    private fun loadNative(r: Float, g: Float, b: Float, hiding: Float, scatter: Float, stiff: Float, drying: Float,
                           amount: Double, wipe: Double, thinner: Double, turps: Double, oil: Double, part: LoadPart?) {
        val p = part ?: LoadPart()
        PaintNative.brushLoadV2(handle, r, g, b, hiding, scatter, stiff, drying, amount.toFloat(), wipe.toFloat(),
            thinner.toFloat(), turps.toFloat(), oil.toFloat(), p.side.toFloat(), p.share.toFloat(), p.streak.toFloat(),
            // a streaked dip's streaks follow from the brush as it is, so a restored brush dips alike
            p.seed ?: (PaintNative.brushFullness(handle).toBits().toLong() * 31 + p.hashCode()))
    }

    /** Wipe [fraction] of the paint out of the brush on a rag, without loading any. */
    fun wipe(fraction: Double = 0.85): PaintBrush = apply { PaintNative.brushWipe(handle, fraction.toFloat()) }

    /** Paint left in the brush, relative to a full load. */
    val fullness: Double get() = PaintNative.brushFullness(handle).toDouble()

    /** A snapshot of the brush, bristle by bristle: [restore] makes a brush that carries on exactly as this one. */
    fun state(): ByteArray = PaintNative.brushState(handle)

    override fun close() = cleanable.clean()

    companion object {
        /** A brush from [state]; [kind] is what it was made as (a [BrushKind.Stippler] is otherwise a round). */
        fun restore(state: ByteArray, kind: BrushKind? = null): PaintBrush {
            val h = PaintNative.brushFromState(state)
            return PaintBrush(h, kind ?: BrushKind.entries.first { it.code == PaintNative.brushConfig(h)[0].toInt() })
        }
    }
}

/**
 * A painting knife: a flexible steel blade [width] canvas units long, and the bead of paint it
 * carries. It lays paint in flat slabs with ridges where it is pressed out at the blade's ends and
 * a bead where it lifts; pressed hard without paint it scrapes the wet paint off down to the dry
 * surface. On engine 5 a slab tears where it parts from the blade. Get one from [PaintCanvas.knife].
 */
class PaintKnife internal constructor(internal val handle: MemorySegment) : PaintTool, AutoCloseable {
    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::knifeFree))

    /**
     * Pick up [amount] (0..1 of a full bead: about a millilitre on a 4 cm blade) of paint of
     * masstone [color]. [wipe] cleans the blade first; otherwise it mixes into what is there.
     */
    fun load(
        color: Vec4, hiding: Double = 0.92, stiff: Double = 0.9, drying: Double = 1.0, amount: Double = 0.6,
        wipe: Boolean = true, thinner: Double = 0.0, turps: Double = 0.0, oil: Double = 1.0,
    ): PaintKnife = apply {
        PaintNative.knifeLoad(handle, linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(), Float.NaN,
            stiff.toFloat(), drying.toFloat(), amount.toFloat(), wipe, thinner.toFloat(), turps.toFloat(), oil.toFloat())
    }

    /** [load] from tube paints, by name and parts by volume. */
    fun load(tubes: Map<String, Double>, medium: Double = 0.0, amount: Double = 0.6, wipe: Boolean = true): PaintKnife = apply {
        val m = PaintNative.mixTubes(tubes, medium.toFloat())
        PaintNative.knifeLoad(handle, m[0], m[1], m[2], 0.9f, m[3], m[4], m[5], amount.toFloat(), wipe, 0f, 0f, 1f)
    }

    /** Wipe the blade clean. */
    fun wipe(): PaintKnife = apply { PaintNative.knifeWipe(handle) }

    /** Paint on the blade, relative to a full bead. */
    val fullness: Double get() = PaintNative.knifeFullness(handle).toDouble()

    /** A snapshot of the blade and its bead, for [restore]. */
    fun state(): ByteArray = PaintNative.knifeState(handle)

    override fun close() = cleanable.clean()

    companion object {
        fun restore(state: ByteArray): PaintKnife = PaintKnife(PaintNative.knifeFromState(state))
    }
}

/**
 * A soft cotton rag bunched into a pad [width][PaintCanvas.rag] canvas units across, wiped or
 * pressed over open paint to lift it ([PaintCanvas.wipe], [PaintCanvas.blot]). What it lifts soaks
 * into the cloth: the face in use loads up and lifts less until it is refolded
 * ([PaintCanvas.refold]); dampened with spirits ([PaintCanvas.dampen]) it lifts nearly to the
 * ground. Only wet paint comes away: past its gel point paint is part of the dry picture.
 */
class PaintRag internal constructor(internal val handle: MemorySegment) : PaintTool, AutoCloseable {
    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::ragFree))

    /** A snapshot of the rag, for [restore]. */
    fun state(): ByteArray = PaintNative.ragStateBytes(handle)

    override fun close() = cleanable.clean()

    companion object {
        fun restore(state: ByteArray): PaintRag = PaintRag(PaintNative.ragFromState(state))
    }
}

/**
 * A round watercolour brush: a belly that holds water, and the pigment dissolved in it. Where it
 * touches the paper water flows between them by capillary suction, from the wetter to the drier: a
 * full brush lays water and pigment, a damp one on a wet wash drinks it back up (with the pigment
 * floating in it). A fast stroke lays less than a slow one. A light, nearly dry brush reaches only
 * the peaks of the paper's tooth: drybrush comes from the paper, not a texture. Get one from
 * [PaintCanvas.waterBrush].
 */
class WaterBrush internal constructor(internal val handle: MemorySegment) : PaintTool, AutoCloseable {
    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::wbrushFree))

    /**
     * Dip it: first squeeze out [wipe] (0..1) of what it holds, then take up water to [fill] of its
     * capacity (more than 1 overloads it) with [paint] dissolved in it.
     */
    fun load(paint: WaterPaint, fill: Double = 1.0, wipe: Double = 1.0): WaterBrush = apply {
        PaintNative.wbrushLoad(handle, paint, fill.toFloat(), wipe.toFloat())
    }

    /** Touch it to a towel: it loses [share] of its water and the pigment in it. */
    fun blot(share: Double = 0.6): WaterBrush = apply { PaintNative.wbrushBlot(handle, share.toFloat()) }

    private val st get() = PaintNative.wbrushState(handle)

    /** Width at full pressure, canvas units. */
    val width: Double get() = st[0].toDouble()
    /** Water the belly holds full, mm³. */
    val capacityMm3: Double get() = st[1].toDouble()
    /** Water in it now, mm³. */
    val waterMm3: Double get() = st[2].toDouble()
    /** Pigment in it now, µg. */
    val pigmentUg: Double get() = st[3].toDouble()
    /** Its water over its capacity. */
    val fill: Double get() = waterMm3 / capacityMm3

    fun state(): ByteArray = PaintNative.wbrushStateBytes(handle)
    override fun close() = cleanable.clean()

    companion object {
        fun restore(state: ByteArray): WaterBrush = WaterBrush(PaintNative.wbrushFromState(state))
    }
}

/**
 * A brush whose hairs move: a few dozen guide hairs simulated as chains of segments under a timed
 * gesture of the handle ([TimedGesture]: position, pressure, tilt, lean and roll). The hairs bend
 * against the paper and its paint, stick and slip by friction, clump when wet, and keep a bend they
 * were forced past (retained deformation, so a brush once splayed stays splayed for a while). Load it
 * with oil ([load]: upstream's bristles, following the hairs, carry and lay the paint) or with
 * watercolour ([loadWater]). Get one from [PaintCanvas.dynamicBrush].
 */
class DynamicBrush internal constructor(internal val handle: MemorySegment, val kind: BrushKind) : PaintTool, AutoCloseable {
    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::dbrushFree))

    private val info get() = PaintNative.dbrushInfo(handle)

    /** Width across the ferrule, mm. */
    val widthMm: Double get() = info[0].toDouble()
    /** Hair length out of the ferrule, mm. */
    val lengthMm: Double get() = info[1].toDouble()
    /** Guide hairs simulated. */
    val hairCount: Int get() = info[3].toInt()
    /** Static and kinetic friction against the surface. */
    val friction: Pair<Double, Double> get() = info[5].toDouble() to info[6].toDouble()
    /** How full of paint or water it is, 0..1. */
    val fill: Double get() = info[11].toDouble()
    /** True when it paints watercolour (it was last loaded with [loadWater]). */
    val watercolour: Boolean get() = info[12] > 0.5f
    /** The bend the hairs keep at rest, radians, averaged over every joint: 0 a straight brush. */
    val retainedBend: Double get() = info[13].toDouble()

    /**
     * How the hairs behave; `null` keeps a value. [friction] is static to kinetic against the surface;
     * a joint bent more than [yieldDegrees] from its rest angle keeps some of the bend, at [plasticity]
     * per second; rest bends straighten again at [recovery] per second; [cohesion] is how strongly
     * the liquid in a wet brush draws its hairs together: 1 draws a full brush's tips to a point, more
     * pulls harder and closer, 0 none, and below 0 pushes nearby hairs apart.
     *
     * [clumping] is which way the liquid does it. 0 (as a brush is made) draws the tips of hairs that
     * are neighbours at the root together. 1 joins any two wet hairs that come within about their
     * spacing at the root by a liquid bridge, which pulls them together and snaps when they are pulled
     * a few spacings apart (further the more liquid it holds), so that the hairs gather into clumps
     * that splay can tear apart, and the bristles they carry gather with them. Between 0 and 1 the two
     * pull together, each by its share; past 1 the bridges pull harder and reach further; below 0 the
     * tip pull is stronger and the bridges push.
     *
     * [splay] is how far a pressed hair is pushed out from the brush's middle, per mm pressed in (the
     * brush's `BrushConfig.splay` until set). [splayFan] is how that grows across the brush: 0 every
     * hair pushed alike (each half of a flat moves as one), 1 in proportion to how far out its root
     * is, so that the brush fans open and neighbours part everywhere. [splayScatter] turns each hair's
     * push off the outward by its own slowly drifting angle and amount (0 none, 1 up to half a turn and
     * twice as far), the way pressed hairs buckle each their own way.
     */
    fun set(friction: Pair<Double, Double>? = null, yieldDegrees: Double? = null, plasticity: Double? = null, recovery: Double? = null, cohesion: Double? = null,
            clumping: Double? = null, splay: Double? = null, splayFan: Double? = null, splayScatter: Double? = null): DynamicBrush = apply {
        val nan = Float.NaN
        PaintNative.dbrushSet(handle, friction?.first?.toFloat() ?: nan, friction?.second?.toFloat() ?: nan,
            yieldDegrees?.let { Math.toRadians(it).toFloat() } ?: nan, plasticity?.toFloat() ?: nan, recovery?.toFloat() ?: nan, cohesion?.toFloat() ?: nan)
        clumping?.let { PaintNative.dbrushParam(handle, "clumping", it.toFloat()) }
        splay?.let { PaintNative.dbrushParam(handle, "splay", it.toFloat()) }
        splayFan?.let { PaintNative.dbrushParam(handle, "splay fan", it.toFloat()) }
        splayScatter?.let { PaintNative.dbrushParam(handle, "splay scatter", it.toFloat()) }
    }

    /** Load it with oil paint, as [PaintBrush.load]. */
    fun load(color: Vec4, hiding: Double = 0.92, stiff: Double = 0.8, drying: Double = 1.0, amount: Double = 1.0, wipe: Double = 0.85,
             thinner: Double = 0.0, turps: Double = 0.0, oil: Double = 1.0, part: LoadPart? = null): DynamicBrush = apply {
        val p = part ?: LoadPart()
        PaintNative.dbrushLoad(handle, linear(color.x), linear(color.y), linear(color.z), hiding.toFloat(), Float.NaN, stiff.toFloat(), drying.toFloat(),
            amount.toFloat(), wipe.toFloat(), thinner.toFloat(), turps.toFloat(), oil.toFloat(), p.side.toFloat(), p.share.toFloat(), p.streak.toFloat(), p.hashCode().toLong())
    }

    /** Load it with tube paints by parts, as [PaintBrush.load]. */
    fun load(tubes: Map<String, Double>, medium: Double = 0.0, amount: Double = 1.0, wipe: Double = 0.85): DynamicBrush = apply {
        val m = PaintNative.mixTubes(tubes, medium.toFloat())
        PaintNative.dbrushLoad(handle, m[0], m[1], m[2], 0.9f, m[3], m[4], m[5], amount.toFloat(), wipe.toFloat(), 0f, 0f, 1f, 0f, 1f, 0f, 0)
    }

    /** Load it with watercolour, as [WaterBrush.load]: from now on it paints watercolour, on [canvas]'s scale. */
    fun loadWater(canvas: PaintCanvas, paint: WaterPaint, fill: Double = 1.0, wipe: Double = 1.0): DynamicBrush = apply {
        PaintNative.dbrushLoadWater(canvas.handle, handle, paint, fill.toFloat(), wipe.toFloat())
    }

    /** A snapshot of the whole brush: hairs, their retained bends, and the paint or water in it. */
    fun state(): ByteArray = PaintNative.dbrushState(handle)
    override fun close() = cleanable.clean()

    companion object {
        fun restore(state: ByteArray, kind: BrushKind): DynamicBrush = DynamicBrush(PaintNative.dbrushFromState(state), kind)
    }
}

/** What a segmented-brush stroke did ([PaintCanvas.stroke] with a [DynamicBrush]). */
data class DynamicReport(
    val substeps: Long,
    val seconds: Double,
    /** Hair contacts summed over the substeps. */
    val contacts: Long,
    /** The most of one hair's length that lay on the paper, 0..1. */
    val maxContact: Double,
    /** Watercolour: mm³ of water laid and taken up, µg of pigment laid and taken up. */
    val waterLaid: Double, val waterTaken: Double, val pigmentLaid: Double, val pigmentTaken: Double,
)

/** How loaded a [PaintRag] is ([PaintCanvas.ragState]). */
data class RagState(
    /** The face in use: 0 clean .. 1 full. */
    val load: Double,
    /** The whole cloth: 0 .. 1, every face full. */
    val soaked: Double,
    /** How damp the face is with spirits now, 0 dry .. 1 dipped well. */
    val damp: Double,
)

/** Paint carried by a brush or knife ([PaintCanvas.carried]). */
data class Carried(
    /** The paint's colour, mixed across the bristles as paint mixes (sRGB); `null` when clean. */
    val color: Vec4?,
    /** mm³ of paint. */
    val paintMm3: Double,
    /** mm³ of solvent beside it (a thinned load). */
    val solventMm3: Double,
)

/** One tube paint in the crate's catalogue. Colours are sRGB. */
data class Tube(
    val name: String,
    /** What it is made of. */
    val pigment: String,
    /** Its masstone: the colour laid thick. */
    val color: Vec4,
    /** How much one coat hides (0 transparent .. 1 opaque). */
    val hiding: Double,
    /** Stiffness straight from the tube (0 fluid .. 1 stiff). */
    val stiff: Double,
    /** Tinting strength relative to an average pigment. */
    val strength: Double,
    /** Drying rate relative to average paint. */
    val drying: Double,
)

/** The crate's tube paints, and paint-like colour mixing. */
object Tubes {
    private val table: Pair<List<Tube>, List<String>> by lazy {
        val lines = PaintNative.tubes().lines()
        val tubes = lines.filter { !it.startsWith("boxes") && it.isNotBlank() }.map { l ->
            val f = l.split('\t')
            Tube(f[0], f[1], Vec4(srgb(f[2].toFloat()), srgb(f[3].toFloat()), srgb(f[4].toFloat()), 1f),
                f[5].toDouble(), f[6].toDouble(), f[7].toDouble(), f[8].toDouble())
        }
        tubes to (lines.firstOrNull { it.startsWith("boxes") }?.split('\t')?.drop(1) ?: emptyList())
    }

    /** Every tube in the catalogue. */
    val all: List<Tube> get() = table.first

    /** Their names, for [PaintBrush.load]. */
    val names: List<String> get() = all.map { it.name }

    /** The tube called [name]. */
    operator fun get(name: String): Tube = all.firstOrNull { it.name == name }
        ?: throw NoSuchElementException("no tube named \"$name\"; there are: ${names.joinToString()}")

    /** The masstone of a pile mixed from [tubes] (name to parts by volume), as paint mixes. */
    fun mix(tubes: Map<String, Double>): Vec4 {
        val m = PaintNative.mixTubes(tubes, 0f)
        return Vec4(srgb(m[0]), srgb(m[1]), srgb(m[2]), 1f)
    }

    /**
     * Mix colours as pigments rather than light, by weight: per-wavelength Kubelka–Munk (a port of
     * spectral.js), so blue and yellow make green rather than grey.
     */
    fun mixSpectral(vararg colors: Pair<Vec4, Double>): Vec4 {
        val rgb = FloatArray(colors.size * 3)
        colors.forEachIndexed { i, (c, _) ->
            rgb[3 * i] = linear(c.x); rgb[3 * i + 1] = linear(c.y); rgb[3 * i + 2] = linear(c.z)
        }
        val m = PaintNative.mixSpectral(rgb, FloatArray(colors.size) { colors[it].second.toFloat() })
        return Vec4(srgb(m[0]), srgb(m[1]), srgb(m[2]), 1f)
    }
}

internal fun linear(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
}

internal fun srgb(c: Float): Float {
    val v = c.coerceIn(0f, 1f)
    return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

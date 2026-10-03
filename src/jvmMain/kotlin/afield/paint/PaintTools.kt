@file:afield.CompletionCategory("Graphics")
package afield.paint

import afield.stdlib.Vec4
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
class Pencil internal constructor(val medium: DrawingMedium, val softness: Double) {
    /** Millimetres drawn since the pencil was last sharpened. */
    var worn: Double = 0.0
        internal set(v) { field = v }

    /** Sharpen the point. */
    fun sharpen(): Pencil = apply { worn = 0.0 }
}

/**
 * A brush held over a [PaintCanvas], carrying paint between strokes.
 *
 * Get one from [PaintCanvas.brush]. It holds native memory, freed when collected or by [close].
 */
class PaintBrush internal constructor(val kind: BrushKind, val width: Double, point: Double, seed: Long) : AutoCloseable {

    internal val handle: MemorySegment = PaintNative.brushNew(kind.code, width.toFloat(), point.toFloat(), seed)
    private val cleanable = PaintNative.cleaner.register(this, PaintNative.Free(handle, PaintNative::brushFree))

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
     */
    fun load(
        color: Vec4,
        hiding: Double = 0.92,
        stiff: Double = 0.8,
        drying: Double = 1.0,
        amount: Double = 1.0,
        wipe: Double = 0.85,
    ): PaintBrush = apply {
        PaintNative.brushLoad(handle, linear(color.x), linear(color.y), linear(color.z),
            hiding.toFloat(), stiff.toFloat(), drying.toFloat(), amount.toFloat(), wipe.toFloat())
    }

    /**
     * Dip the brush in a pile mixed from tube paints, by name and parts by volume —
     * `load(mapOf("lead white" to 3.0, "yellow ochre" to 1.0))`. The tubes mix the way paint does
     * (Mixbox, weighted by each pigment's tinting strength), and the pile keeps their hiding,
     * stiffness and drying rate. [medium] (0..1) thins it with oil. [Tubes.names] lists the tubes.
     */
    fun load(tubes: Map<String, Double>, medium: Double = 0.0, amount: Double = 1.0, wipe: Double = 0.85): PaintBrush = apply {
        val m = PaintNative.mixTubes(tubes, medium.toFloat())
        PaintNative.brushLoadKm(handle, m[0], m[1], m[2], m[3], m[4], m[5], amount.toFloat(), wipe.toFloat())
    }

    /** Paint left in the brush, relative to a full load. */
    val fullness: Double get() = PaintNative.brushFullness(handle).toDouble()

    override fun close() = cleanable.clean()
}

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

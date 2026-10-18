@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

import afield.math.linalg.Vec4
import java.lang.foreign.MemorySegment

/**
 * Watercolour and ink on paper: the [Paper] kinds, [WaterPigment]s and their [Watercolors]
 * palette, the [WaterPaint] mixed from them, and the reports and ledgers a wash returns.
 *
 * A sheet is made with [PaintCanvas.paper] and worked with [PaintCanvas.waterBrush]. [WaterMap]
 * names the fields of the sheet's state that can be read back. Test: `WaterMediaTest`.
 */

/**
 * A sheet of paper for watercolour and ink ([PaintCanvas.paper]). Each is a physical make-up — how
 * much water it holds, how fast it drinks and wicks, its grain, tooth and sizing — not a texture:
 * the same drop behaves differently on each because the water does. The numbers are estimates in
 * the ranges reported for each kind of paper.
 */
enum class Paper(internal val id: String) {
    /** 300 g/m² cotton, cold pressed: a moderate tooth, sized, so a wash stands for a while before it sinks in. */
    ColdPress("cold press"),

    /** The same sheet hot pressed: smooth, a little harder sized. Crisp edges. */
    HotPress("hot press"),

    /** A deep tooth that a nearly dry brush skips across. */
    Rough("rough"),

    /** Thin unsized xuan (rice) paper of long fibres, for ink: it drinks at once and feathers along its grain. */
    Xuan("xuan"),

    /** Thick unsized blotting paper: takes up a lot of water fast, and holds the ink where it lands. */
    Blotting("blotting"),
}

/**
 * A pigment as water carries it: its optics (Kubelka–Munk absorption [k] per channel and scattering
 * [s], per µg/mm² of pigment) and what decides how it moves — particle [density] (g/cm³) and
 * [diameterUm] (dense, coarse pigment settles out onto the paper within a minute; fine pigment
 * stays suspended), [stain] (per second it adsorbs onto fibres from water in the sheet, after which
 * it can't be lifted) and [lift] (per second a deposit goes back into still water standing on it; a
 * moving brush lifts much faster).
 *
 * [Watercolors] has named ones; [WaterPigment.of] makes one from a colour.
 */
data class WaterPigment(
    val name: String,
    val k: List<Double>,
    val s: Double,
    val density: Double,
    val diameterUm: Double,
    val stain: Double,
    val lift: Double,
) {
    internal fun params(): FloatArray = floatArrayOf(k[0].toFloat(), k[1].toFloat(), k[2].toFloat(), s.toFloat(),
        density.toFloat(), diameterUm.toFloat(), stain.toFloat(), lift.toFloat())

    /** In paint `concentration` µg per mm³ of water (a strong wash is a few). */
    operator fun times(concentration: Double): WaterPaint = WaterPaint(mapOf(this to concentration))

    companion object {
        /**
         * A pigment whose masstone (laid thick) is [masstone] (sRGB), tinting with absorption
         * [strength] per µg/mm², [opacity] its scattering relative to that (0 a dye, 1 a body colour,
         * more for more).
         */
        fun of(
            name: String, masstone: Vec4, strength: Double = 1.0, opacity: Double = 0.05, density: Double = 2.5,
            diameterUm: Double = 0.5, stain: Double = 0.02, lift: Double = 0.004,
        ): WaterPigment {
            val v = PaintNative.aqPigmentMake(linear(masstone.x), linear(masstone.y), linear(masstone.z), strength.toFloat(),
                opacity.toFloat(), density.toFloat(), diameterUm.toFloat(), stain.toFloat(), lift.toFloat())
            return fromParams(name, v)
        }

        internal fun fromParams(name: String, v: FloatArray) =
            WaterPigment(name, listOf(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()), v[3].toDouble(),
                v[4].toDouble(), v[5].toDouble(), v[6].toDouble(), v[7].toDouble())
    }
}

/** Watercolour pigments, by their common names; estimates of each one's physics. */
object Watercolors {
    /** The names [get] knows. */
    val names: List<String> by lazy { PaintNative.aqPigmentNames() }

    /** The pigment called [name]. */
    operator fun get(name: String): WaterPigment = PaintNative.aqPigment(name)?.let { WaterPigment.fromParams(name, it) }
        ?: throw NoSuchElementException("no watercolour pigment \"$name\"; there are: ${names.joinToString()}")

    /** PB29: coarse and dense, it settles fast, and lifts readily. */
    val ultramarine get() = get("ultramarine")
    /** PB15: very fine and staining: smooth, intense, hard to lift. */
    val phthaloBlue get() = get("phthalo blue")
    /** PBr7: an iron oxide, fine but dense. */
    val burntSienna get() = get("burnt sienna")
    val quinacridoneRose get() = get("quinacridone rose")
    /** PY35: dense and semi-opaque. */
    val cadmiumYellow get() = get("cadmium yellow")
    /** PG18: coarse, settles fast. */
    val viridian get() = get("viridian")
    /** Carbon in animal glue: very fine, fixed by its glue once dry. */
    val sumi get() = get("sumi")
}

/** Pigments dissolved in water, each at a concentration in µg per mm³ of water. Add two with `+`. */
data class WaterPaint(val pigments: Map<WaterPigment, Double>) {
    operator fun plus(o: WaterPaint) = WaterPaint((pigments.keys + o.pigments.keys).associateWith { (pigments[it] ?: 0.0) + (o.pigments[it] ?: 0.0) })

    companion object {
        /** Clean water. */
        val Clear = WaterPaint(emptyMap())
    }
}

/** What a watercolour stroke did, mm³ of water and µg of pigment. */
data class WashReport(val waterLaid: Double, val waterTaken: Double, val pigmentLaid: Double, val pigmentTaken: Double, val seconds: Double)

/** One pigment's books on a sheet, µg. */
data class PigmentLedger(
    val name: String,
    /** In the surface water, moving with it. */
    val suspended: Double,
    /** In the water in the sheet's pores, moving with it. */
    val carried: Double,
    /** Settled out of water still on or in the sheet: water flowing through carries it on. */
    val deposited: Double,
    /** Settled and dried in place, its gum set: rewetting lifts it slowly, a brush faster. */
    val glued: Double,
    /** Stained into the fibres: it no longer lifts. */
    val bound: Double,
    val added: Double,
    val taken: Double,
    val runoff: Double,
    /** Sealed into the picture under paint laid over it. */
    val sealed: Double,
) {
    /** On and in the sheet, less what the ledger says should be: rounding only. */
    val imbalance: Double get() = suspended + carried + deposited + glued + bound - (added - taken - runoff - sealed)
}

/** The water on a sheet and every volume that came or went, mm³ (µl), at [clockSeconds]. */
data class WaterLedger(
    val clockSeconds: Double,
    val surfaceMm3: Double,
    val absorbedMm3: Double,
    val addedMm3: Double,
    val takenMm3: Double,
    val evaporatedMm3: Double,
    val runoffMm3: Double,
    /** All the surface water that has gone into the sheet so far. */
    val absorbedInAllMm3: Double,
    val pigments: List<PigmentLedger>,
) {
    /** Water on and in the sheet, less what the ledger says should be: rounding only. */
    val imbalanceMm3: Double get() = surfaceMm3 + absorbedMm3 - (addedMm3 - takenMm3 - evaporatedMm3 - runoffMm3)
    /** True once no water stands on or in the sheet. */
    val dry: Boolean get() = surfaceMm3 <= 0.0 && absorbedMm3 <= 0.0
}

/** A map of the water's state ([PaintCanvas.waterMap]): one value per pixel. */
enum class WaterMap(internal val code: Int, val unit: String) {
    /** Water standing on the sheet. */
    SurfaceWater(0, "µm"),
    /** Water in the sheet's pores. */
    AbsorbedWater(1, "µm"),
    /** The pores' saturation, 0..1. */
    Saturation(2, ""),
    /** All the pigment, in every state. */
    Pigment(3, "µg/mm²"),
    Suspended(4, "µg/mm²"),
    /** Loose deposit: sediment, and pigment dried in place. */
    Deposited(5, "µg/mm²"),
    Bound(6, "µg/mm²"),
    /** The paper's surface relief. */
    Relief(7, "µm"),
    /** Where paint has sealed the sheet against water, 0..1. */
    Sealed(8, "share"),
    /** Pigment in the water in the pores. */
    Carried(9, "µg/mm²"),
    /** Pigment settled and dried in place, its gum set. */
    Glued(10, "µg/mm²"),
}

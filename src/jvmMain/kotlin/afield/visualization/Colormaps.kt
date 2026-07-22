package afield

import kotlin.math.floor

/**
 * A colour with `[0,1]` components. A small, dependency-free colour value shared across the
 * visualization helpers ([showHeatmap], [Colormaps]) and the RectSL data-viz words.
 *
 * (Lived in `afield.rectsl` until 2026-07-22; promoted here so general colour/colormap code no longer
 * pulls in the RectSL microlanguage. RectSL re-adds its `toVec4f()` bridge as an extension.)
 */
data class Color4(
    val r: Double,
    val g: Double,
    val b: Double,
    val a: Double = 1.0
) {
    fun withAlpha(alpha: Double): Color4 = copy(a = alpha)
    fun alphaTimes(alpha: Double): Color4 = copy(a = a * alpha)
    fun mix(other: Color4, t: Double): Color4 {
        val u = t.coerceIn(0.0, 1.0)
        fun lerp(a: Double, b: Double): Double = a + (b - a) * u
        return Color4(lerp(r, other.r), lerp(g, other.g), lerp(b, other.b), lerp(a, other.a))
    }

    companion object {
        val WHITE = Color4(1.0, 1.0, 1.0, 1.0)
        val BLACK = Color4(0.0, 0.0, 0.0, 1.0)

        fun fromHex(hex: Long): Color4 {
            val hasAlpha = hex > 0xffffffL
            val r = ((hex shr if (hasAlpha) 24 else 16) and 0xff).toDouble() / 255.0
            val g = ((hex shr if (hasAlpha) 16 else 8) and 0xff).toDouble() / 255.0
            val b = ((hex shr if (hasAlpha) 8 else 0) and 0xff).toDouble() / 255.0
            val a = if (hasAlpha) (hex and 0xff).toDouble() / 255.0 else 1.0
            return Color4(r, g, b, a)
        }
    }
}

/**
 * Perceptually-uniform colormaps as named colour ramps — matplotlib's viridis / magma /
 * plasma / inferno, Google's turbo, Kenneth Moreland's coolwarm diverging map, and a plain
 * grayscale. Each map is a table of evenly-spaced RGB control points taken from the published
 * data (the viridisLite 9-class swatches for the matplotlib family, the turbo_srgb_floats LUT
 * sampled every 16th entry, Moreland's cool→warm table); [sampleStops] interpolates linearly
 * between them. This is reusable domain code — the single source of truth for the [showHeatmap]
 * visualizer and RectSL's `colormap`/`cmap`, `bars`, `heat`, and `scatter` words, and directly
 * callable from Kotlin.
 */
object Colormaps {
    private fun hex(vararg codes: Long): List<Color4> = codes.map { Color4.fromHex(it) }
    private fun rgb255(vararg triples: Int): List<Color4> =
        triples.toList().chunked(3).map { Color4(it[0] / 255.0, it[1] / 255.0, it[2] / 255.0) }

    // matplotlib family — viridisLite 9-class hex swatches (dark → bright).
    val viridis: List<Color4> = hex(0x440154, 0x472D7B, 0x3B528B, 0x2C718E, 0x21908C, 0x28AE80, 0x5EC962, 0xADDC30, 0xFDE725)
    val magma: List<Color4> = hex(0x000004, 0x1C1044, 0x4F127B, 0x812581, 0xB5367A, 0xE55064, 0xFB8861, 0xFEC287, 0xFCFDBF)
    val inferno: List<Color4> = hex(0x000004, 0x1B0C42, 0x4B0C6B, 0x781C6D, 0xA52C60, 0xCF4446, 0xED6925, 0xFB9A06, 0xFCFFA4)
    val plasma: List<Color4> = hex(0x0D0887, 0x47039F, 0x7301A8, 0x9C179E, 0xBD3786, 0xD8576B, 0xED7953, 0xFA9E3B, 0xF0F921)

    // Google turbo — turbo_srgb_floats sampled at indices 0,16,…,240,255.
    val turbo: List<Color4> = listOf(
        Color4(0.18995, 0.07176, 0.23217), Color4(0.25107, 0.25237, 0.63374),
        Color4(0.27628, 0.42118, 0.89123), Color4(0.26592, 0.55979, 0.99583),
        Color4(0.20708, 0.66866, 0.97423), Color4(0.09532, 0.84455, 0.79299),
        Color4(0.12733, 0.91701, 0.67627), Color4(0.22142, 0.95692, 0.56914),
        Color4(0.32006, 0.97974, 0.47654), Color4(0.43321, 0.99551, 0.37345),
        Color4(0.54658, 0.99907, 0.29581), Color4(0.64362, 0.98999, 0.23356),
        Color4(0.74617, 0.95593, 0.20406), Color4(0.85010, 0.89328, 0.21074),
        Color4(0.93909, 0.80439, 0.22744), Color4(0.99314, 0.67408, 0.20348),
        Color4(0.47960, 0.01583, 0.01055)
    )

    // Moreland's coolwarm — diverging blue → light grey → red (sampled every 1/8).
    val coolwarm: List<Color4> = rgb255(
        59, 76, 192, 98, 130, 234, 141, 176, 254, 184, 208, 249,
        221, 221, 221, 245, 196, 173, 244, 154, 123, 222, 96, 77, 180, 4, 38
    )

    val grayscale: List<Color4> = hex(0x000000, 0xFFFFFF)

    // Field's house cyan→orange ramp (near-black → cyan mids → orange peaks), matching the
    // showSignals cyan/orange signal palette; the default for the spectral heatmap docs.
    val cyanorange: List<Color4> = rgb255(10, 14, 20, 60, 190, 230, 255, 150, 50)

    private val maps: Map<String, List<Color4>> = linkedMapOf(
        "viridis" to viridis, "magma" to magma, "inferno" to inferno, "plasma" to plasma,
        "turbo" to turbo, "coolwarm" to coolwarm, "grayscale" to grayscale, "gray" to grayscale, "greys" to grayscale,
        "cyanorange" to cyanorange, "cyan-orange" to cyanorange
    )

    val names: List<String> = listOf("viridis", "magma", "inferno", "plasma", "turbo", "coolwarm", "grayscale", "cyanorange")

    fun stops(name: String): List<Color4> =
        maps[name.trim().lowercase()]
            ?: throw IllegalArgumentException("unknown colormap '$name' (try ${names.joinToString(", ")})")

    /** Look up colormap [name] at parameter [t] in `[0,1]` (clamped). */
    fun sample(name: String, t: Double): Color4 = sampleStops(stops(name), t)

    /** Linearly interpolate an evenly-spaced list of control points at [t] in `[0,1]` (clamped). */
    fun sampleStops(stops: List<Color4>, t: Double): Color4 {
        require(stops.isNotEmpty()) { "colormap has no control points" }
        if (stops.size == 1) return stops[0]
        val scaled = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val lo = floor(scaled).toInt().coerceIn(0, stops.size - 2)
        return stops[lo].mix(stops[lo + 1], scaled - lo)
    }
}

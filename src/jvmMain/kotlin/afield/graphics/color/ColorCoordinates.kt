@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.color

import afield.math.linalg.Vec4
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** Coordinate systems for exchanging and interpolating colors. Polar hue is always in degrees. */
enum class ColorSpace {
    SRGB, LINEAR_SRGB, DISPLAY_P3, XYZ_D65, LAB_D65, OKLAB, OKLCH
}

/** How a color outside sRGB is brought onto the display. */
enum class ColorGamut {
    /** Reduce OKLCH chroma at fixed lightness and hue. */
    REDUCE_CHROMA,

    /** Clamp the three sRGB channels independently. */
    CLIP
}

/**
 * Three coordinates in [space] plus straight alpha. Conversion keeps extended-range coordinates
 * until [toSrgb] is called. XYZ uses Y=1 for reference white. CIELAB uses D65 with L=0..100.
 */
data class ColorCoordinates(
    val space: ColorSpace,
    val x: Double,
    val y: Double,
    val z: Double,
    val alpha: Double = 1.0
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Color coordinates must be finite" }
        require(alpha in 0.0..1.0) { "Alpha must be in 0..1" }
        require(space != ColorSpace.OKLCH || y >= 0.0) { "OKLCH chroma must be nonnegative" }
    }

    /** Express these coordinates in [target] without gamut mapping. */
    fun convert(target: ColorSpace): ColorCoordinates {
        if (space == target) return this
        if (space == ColorSpace.OKLCH) {
            val angle = Math.toRadians(z % 360.0)
            return ColorCoordinates(ColorSpace.OKLAB, x, y * cos(angle), y * sin(angle), alpha).convert(target)
        }
        if (target == ColorSpace.OKLCH) {
            val lab = convert(ColorSpace.OKLAB)
            val chroma = hypot(lab.y, lab.z)
            val hue = if (chroma < 1e-7) 0.0 else wrapDegrees(Math.toDegrees(atan2(lab.z, lab.y)))
            return ColorCoordinates(target, lab.x, chroma, hue, alpha)
        }
        if (space == ColorSpace.LAB_D65) {
            val fy = (x + 16.0) / 116.0
            return ColorCoordinates(
                ColorSpace.XYZ_D65,
                whiteX * labDecode(fy + y / 500.0), labDecode(fy), whiteZ * labDecode(fy - z / 200.0),
                alpha
            ).convert(target)
        }
        if (target == ColorSpace.LAB_D65) {
            val xyz = convert(ColorSpace.XYZ_D65)
            val fx = labEncode(xyz.x / whiteX)
            val fy = labEncode(xyz.y)
            val fz = labEncode(xyz.z / whiteZ)
            return ColorCoordinates(target, 116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz), alpha)
        }
        if (space == ColorSpace.DISPLAY_P3) {
            return transform(ColorSpace.XYZ_D65, p3ToXyz, decodeSrgb(x), decodeSrgb(y), decodeSrgb(z)).convert(target)
        }
        if (target == ColorSpace.DISPLAY_P3) {
            val xyz = convert(ColorSpace.XYZ_D65)
            val p3 = xyz.transform(target, xyzToP3)
            return p3.copy(x = encode(p3.x), y = encode(p3.y), z = encode(p3.z))
        }
        val linear = when (space) {
            ColorSpace.LINEAR_SRGB -> this
            ColorSpace.SRGB -> ColorCoordinates(ColorSpace.LINEAR_SRGB, decodeSrgb(x), decodeSrgb(y), decodeSrgb(z), alpha)
            ColorSpace.XYZ_D65 -> transform(ColorSpace.LINEAR_SRGB, xyzToSrgb)
            ColorSpace.OKLAB -> {
                val rgb = oklabToLinearSrgb(Oklab(x.toFloat(), y.toFloat(), z.toFloat()))
                ColorCoordinates(ColorSpace.LINEAR_SRGB, rgb.r.toDouble(), rgb.g.toDouble(), rgb.b.toDouble(), alpha)
            }
        }
        return when (target) {
            ColorSpace.LINEAR_SRGB -> linear
            ColorSpace.SRGB -> ColorCoordinates(target, encode(linear.x), encode(linear.y), encode(linear.z), alpha)
            ColorSpace.XYZ_D65 -> linear.transform(target, srgbToXyz)
            ColorSpace.OKLAB -> {
                val lab = linearSrgbToOklab(LinearRgb(linear.x.toFloat(), linear.y.toFloat(), linear.z.toFloat()))
                ColorCoordinates(target, lab.l.toDouble(), lab.a.toDouble(), lab.b.toDouble(), alpha)
            }
        }
    }

    /** True if this color's RGB channels can be displayed in sRGB. Alpha has no effect on the test. */
    fun isInSrgb(): Boolean {
        val rgb = convert(ColorSpace.SRGB)
        return rgb.x in srgbTolerance && rgb.y in srgbTolerance && rgb.z in srgbTolerance
    }

    /** Map to an sRGB [Vec4] for drawing. */
    fun toSrgb(gamut: ColorGamut = ColorGamut.REDUCE_CHROMA): Vec4 {
        val encoded = convert(ColorSpace.SRGB)
        val mapped = if (gamut == ColorGamut.CLIP || encoded.isInSrgb()) encoded else {
            val lch = convert(ColorSpace.OKLCH)
            val l = lch.x.coerceIn(0.0, 1.0)
            if (l == 0.0 || l == 1.0) return Vec4(l.toFloat(), l.toFloat(), l.toFloat(), alpha.toFloat())
            val c = minOf(lch.y, srgbChromaLimit(l, lch.z))
            lch.copy(x = l, y = c).convert(ColorSpace.SRGB)
        }
        return Vec4(
            mapped.x.coerceIn(0.0, 1.0).toFloat(), mapped.y.coerceIn(0.0, 1.0).toFloat(),
            mapped.z.coerceIn(0.0, 1.0).toFloat(), alpha.toFloat()
        )
    }

    private fun transform(
        target: ColorSpace, matrix: DoubleArray, a: Double = x, b: Double = y, c: Double = z
    ): ColorCoordinates = ColorCoordinates(
        target,
        matrix[0] * a + matrix[1] * b + matrix[2] * c,
        matrix[3] * a + matrix[4] * b + matrix[5] * c,
        matrix[6] * a + matrix[7] * b + matrix[8] * c,
        alpha
    )
}

/** Read an sRGB drawing color as [space]. RGB channels are on the 0..1 scale. */
fun colorCoordinates(color: Vec4, space: ColorSpace = ColorSpace.OKLAB): ColorCoordinates =
    ColorCoordinates(ColorSpace.SRGB, color.x.toDouble(), color.y.toDouble(), color.z.toDouble(), color.w.toDouble())
        .convert(space)

/** Draw an OKLab color from lightness and opponent axes. */
fun oklab(l: Number, a: Number, b: Number, alpha: Number = 1.0, gamut: ColorGamut = ColorGamut.REDUCE_CHROMA): Vec4 =
    ColorCoordinates(ColorSpace.OKLAB, l.toDouble(), a.toDouble(), b.toDouble(), alpha.toDouble()).toSrgb(gamut)

/** Draw an OKLCH color from lightness, chroma and hue in degrees. */
fun oklch(l: Number, c: Number, hueDegrees: Number, alpha: Number = 1.0, gamut: ColorGamut = ColorGamut.REDUCE_CHROMA): Vec4 =
    ColorCoordinates(ColorSpace.OKLCH, l.toDouble(), c.toDouble(), hueDegrees.toDouble(), alpha.toDouble()).toSrgb(gamut)

/** Find the approximate sRGB chroma boundary at this OKLCH lightness and hue. */
fun srgbChromaLimit(lightness: Number, hueDegrees: Number): Double {
    val l = lightness.toDouble()
    val h = hueDegrees.toDouble()
    require(l in 0.0..1.0 && h.isFinite()) { "Lightness must be in 0..1 and hue must be finite" }
    if (l == 0.0 || l == 1.0) return 0.0
    val angle = Math.toRadians(h % 360.0)
    val a = cos(angle)
    val b = sin(angle)
    var low = 0.0
    var high = 0.5 // Above the largest chroma in the sRGB gamut.
    repeat(22) {
        val mid = (low + high) * 0.5
        val rgb = oklabToLinearSrgb(Oklab(l.toFloat(), (a * mid).toFloat(), (b * mid).toFloat()))
        if (rgb.r in 0f..1f && rgb.g in 0f..1f && rgb.b in 0f..1f) low = mid else high = mid
    }
    return low
}

/** Euclidean distance in OKLab. Alpha is ignored. */
fun deltaEOK(first: Vec4, second: Vec4): Double {
    val a = colorCoordinates(first)
    val b = colorCoordinates(second)
    return hypot(a.x - b.x, hypot(a.y - b.y, a.z - b.z))
}

internal fun wrapDegrees(h: Double): Double = ((h % 360.0) + 360.0) % 360.0

private val srgbTolerance = -0.00002..1.00002
private const val whiteX = 0.9504559270516716
private const val whiteZ = 1.0890577507598784
private const val labEpsilon = 216.0 / 24389.0
private const val labKappa = 24389.0 / 27.0

private fun labEncode(v: Double): Double = if (v > labEpsilon) Math.cbrt(v) else (labKappa * v + 16.0) / 116.0
private fun labDecode(v: Double): Double {
    val cube = v * v * v
    return if (cube > labEpsilon) cube else (116.0 * v - 16.0) / labKappa
}

internal fun decodeSrgb(encoded: Double): Double {
    val v = abs(encoded)
    return if (v <= 0.04045) encoded / 12.92 else Math.copySign(((v + 0.055) / 1.055).pow(2.4), encoded)
}

private fun encode(linear: Double): Double {
    val v = abs(linear)
    return if (v <= 0.0031308) linear * 12.92 else Math.copySign(1.055 * v.pow(1.0 / 2.4) - 0.055, linear)
}

// CSS Color 4 sample conversion matrices, with XYZ scaled to Y=1.
private val srgbToXyz = doubleArrayOf(
    0.4123907993, 0.3575843394, 0.1804807884,
    0.2126390059, 0.7151686788, 0.0721923154,
    0.0193308187, 0.1191947798, 0.9505321522
)
private val xyzToSrgb = doubleArrayOf(
    3.2409699419, -1.5373831776, -0.4986107603,
    -0.9692436363, 1.8759675015, 0.0415550574,
    0.0556300797, -0.2039769589, 1.0569715142
)
private val p3ToXyz = doubleArrayOf(
    0.4865709486, 0.2656676932, 0.1982172852,
    0.2289745641, 0.6917385218, 0.0792869141,
    0.0, 0.0451133819, 1.0439443689
)
private val xyzToP3 = doubleArrayOf(
    2.4934969119, -0.9313836179, -0.4027107845,
    -0.8294889696, 1.7626640603, 0.0236246858,
    0.0358458302, -0.0761723893, 0.9568845240
)

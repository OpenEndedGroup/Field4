@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colour constructors for the stdlib. Everything here returns a `Vec4` of sRGB-encoded components
 * clamped to `[0,1]` with alpha last, so the results drop straight into FLine colours and uniforms.
 *
 * [rgb] / [rgba] accept either 0..1 or 0..255 components — the scale is inferred per call from
 * whether any component exceeds 1 — and [rgb255] forces the 0..255 reading. [hsl] is HSL with the
 * usual cube geometry, but the result is encoded through the sRGB transfer function on the way out,
 * so it is *not* interchangeable with CSS `hsl()` — see [hsl] for what that costs. [okhsl] and
 * [okhsv] are the Oklab-based equivalents; of the two only [okhsl] holds perceived lightness steady
 * across hue, because [okhsv] scales `v` against the per-hue gamut cusp exactly as HSV always has.
 * Hue may be given as a fraction of a turn or in degrees (magnitude above 1 is divided
 * by 360) and is wrapped either way.
 *
 * For sets: [hueSetHsl], [hueSetOkhsl] and [hueSetOkhsv] (aliased [hslSet], [okhslSet], [okhsvSet])
 * space equally-separated hues around the wheel at a fixed saturation and lightness.
 * [paletteForBackground] starts from the same okhsl ring but then walks each entry's lightness up
 * or down until it clears a minimum WCAG contrast ratio against the supplied background, returning
 * the highest-contrast candidate it found when it cannot reach the target.
 */

private data class LinearRgb(val r: Float, val g: Float, val b: Float)
private data class Oklab(val l: Float, val a: Float, val b: Float)
private data class LC(val l: Float, val c: Float)
private data class ST(val s: Float, val t: Float)
private data class Cs(val c0: Float, val cMid: Float, val cMax: Float)

private const val PI_F = 3.14159265358979323846f

private fun n(v: Number): Float = v.toFloat()
private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
private fun wrap01(v: Float): Float {
    val w = v % 1f
    return if (w < 0f) w + 1f else w
}

private fun hue01(v: Number): Float {
    val h = n(v)
    return if (abs(h) > 1f) wrap01(h / 360f) else wrap01(h)
}

private fun srgbTransfer(linear: Float): Float =
    if (linear <= 0.0031308f) 12.92f * linear
    else 1.055f * linear.pow(1f / 2.4f) - 0.055f

private fun srgbTransferInv(srgb: Float): Float =
    if (srgb > 0.04045f) ((srgb + 0.055f) / 1.055f).pow(2.4f)
    else srgb / 12.92f

private fun linearSrgbToOklab(c: LinearRgb): Oklab {
    val l = 0.4122214708f * c.r + 0.5363325363f * c.g + 0.0514459929f * c.b
    val m = 0.2119034982f * c.r + 0.6806995451f * c.g + 0.1073969566f * c.b
    val s = 0.0883024619f * c.r + 0.2817188376f * c.g + 0.6299787005f * c.b

    val l_ = cbrt(l.toDouble()).toFloat()
    val m_ = cbrt(m.toDouble()).toFloat()
    val s_ = cbrt(s.toDouble()).toFloat()

    return Oklab(
        0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
        1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
        0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
    )
}

private fun oklabToLinearSrgb(c: Oklab): LinearRgb {
    val l_ = c.l + 0.3963377774f * c.a + 0.2158037573f * c.b
    val m_ = c.l - 0.1055613458f * c.a - 0.0638541728f * c.b
    val s_ = c.l - 0.0894841775f * c.a - 1.2914855480f * c.b

    val l = l_ * l_ * l_
    val m = m_ * m_ * m_
    val s = s_ * s_ * s_

    return LinearRgb(
        +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
        -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
        -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    )
}

private fun computeMaxSaturation(a: Float, b: Float): Float {
    val k0: Float
    val k1: Float
    val k2: Float
    val k3: Float
    val k4: Float
    val wl: Float
    val wm: Float
    val ws: Float

    if (-1.88170328f * a - 0.80936493f * b > 1f) {
        k0 = +1.19086277f; k1 = +1.76576728f; k2 = +0.59662641f; k3 = +0.75515197f; k4 = +0.56771245f
        wl = +4.0767416621f; wm = -3.3077115913f; ws = +0.2309699292f
    } else if (1.81444104f * a - 1.19445276f * b > 1f) {
        k0 = +0.73956515f; k1 = -0.45954404f; k2 = +0.08285427f; k3 = +0.12541070f; k4 = +0.14503204f
        wl = -1.2684380046f; wm = +2.6097574011f; ws = -0.3413193965f
    } else {
        k0 = +1.35733652f; k1 = -0.00915799f; k2 = -1.15130210f; k3 = -0.50559606f; k4 = +0.00692167f
        wl = -0.0041960863f; wm = -0.7034186147f; ws = +1.7076147010f
    }

    var sat = k0 + k1 * a + k2 * b + k3 * a * a + k4 * a * b

    val kl = +0.3963377774f * a + 0.2158037573f * b
    val km = -0.1055613458f * a - 0.0638541728f * b
    val ks = -0.0894841775f * a - 1.2914855480f * b

    run {
        val l_ = 1f + sat * kl
        val m_ = 1f + sat * km
        val s_ = 1f + sat * ks

        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        val lDs = 3f * kl * l_ * l_
        val mDs = 3f * km * m_ * m_
        val sDs = 3f * ks * s_ * s_

        val lDs2 = 6f * kl * kl * l_
        val mDs2 = 6f * km * km * m_
        val sDs2 = 6f * ks * ks * s_

        val f = wl * l + wm * m + ws * s
        val f1 = wl * lDs + wm * mDs + ws * sDs
        val f2 = wl * lDs2 + wm * mDs2 + ws * sDs2

        sat -= f * f1 / (f1 * f1 - 0.5f * f * f2)
    }

    return sat
}

private fun findCusp(a: Float, b: Float): LC {
    val sCusp = computeMaxSaturation(a, b)
    val rgbAtMax = oklabToLinearSrgb(Oklab(1f, sCusp * a, sCusp * b))
    val lCusp = cbrt((1f / max(max(rgbAtMax.r, rgbAtMax.g), rgbAtMax.b)).toDouble()).toFloat()
    return LC(lCusp, lCusp * sCusp)
}

private fun findGamutIntersection(a: Float, b: Float, l1: Float, c1: Float, l0: Float, cusp: LC): Float {
    var t: Float

    if (((l1 - l0) * cusp.c - (cusp.l - l0) * c1) <= 0f) {
        t = cusp.c * l0 / (c1 * cusp.l + cusp.c * (l0 - l1))
    } else {
        t = cusp.c * (l0 - 1f) / (c1 * (cusp.l - 1f) + cusp.c * (l0 - l1))

        val dL = l1 - l0
        val dC = c1

        val kl = +0.3963377774f * a + 0.2158037573f * b
        val km = -0.1055613458f * a - 0.0638541728f * b
        val ks = -0.0894841775f * a - 1.2914855480f * b

        val ldt = dL + dC * kl
        val mdt = dL + dC * km
        val sdt = dL + dC * ks

        run {
            val l = l0 * (1f - t) + t * l1
            val c = t * c1

            val l_ = l + c * kl
            val m_ = l + c * km
            val s_ = l + c * ks

            val l3 = l_ * l_ * l_
            val m3 = m_ * m_ * m_
            val s3 = s_ * s_ * s_

            val ldt1 = 3f * ldt * l_ * l_
            val mdt1 = 3f * mdt * m_ * m_
            val sdt1 = 3f * sdt * s_ * s_

            val ldt2 = 6f * ldt * ldt * l_
            val mdt2 = 6f * mdt * mdt * m_
            val sdt2 = 6f * sdt * sdt * s_

            val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3 - 1f
            val r1 = 4.0767416621f * ldt1 - 3.3077115913f * mdt1 + 0.2309699292f * sdt1
            val r2 = 4.0767416621f * ldt2 - 3.3077115913f * mdt2 + 0.2309699292f * sdt2

            val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3 - 1f
            val g1 = -1.2684380046f * ldt1 + 2.6097574011f * mdt1 - 0.3413193965f * sdt1
            val g2 = -1.2684380046f * ldt2 + 2.6097574011f * mdt2 - 0.3413193965f * sdt2

            val bb = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3 - 1f
            val b1 = -0.0041960863f * ldt1 - 0.7034186147f * mdt1 + 1.7076147010f * sdt1
            val b2 = -0.0041960863f * ldt2 - 0.7034186147f * mdt2 + 1.7076147010f * sdt2

            val ur = r1 / (r1 * r1 - 0.5f * r * r2)
            val ug = g1 / (g1 * g1 - 0.5f * g * g2)
            val ub = b1 / (b1 * b1 - 0.5f * bb * b2)

            val tr = if (ur >= 0f) -r * ur else Float.MAX_VALUE
            val tg = if (ug >= 0f) -g * ug else Float.MAX_VALUE
            val tb = if (ub >= 0f) -bb * ub else Float.MAX_VALUE

            t += min(tr, min(tg, tb))
        }
    }

    return t
}

private fun toe(x: Float): Float {
    val k1 = 0.206f
    val k2 = 0.03f
    val k3 = (1f + k1) / (1f + k2)
    return 0.5f * (k3 * x - k1 + sqrt((k3 * x - k1) * (k3 * x - k1) + 4f * k2 * k3 * x))
}

private fun toeInv(x: Float): Float {
    val k1 = 0.206f
    val k2 = 0.03f
    val k3 = (1f + k1) / (1f + k2)
    return (x * x + k1 * x) / (k3 * (x + k2))
}

private fun toSt(cusp: LC): ST = ST(cusp.c / cusp.l, cusp.c / (1f - cusp.l))

private fun getStMid(a: Float, b: Float): ST {
    val s = 0.11516993f + 1f / (
        +7.44778970f + 4.15901240f * b +
            a * (-2.19557347f + 1.75198401f * b +
                a * (-2.13704948f - 10.02301043f * b +
                    a * (-4.24894561f + 5.38770819f * b + 4.69891013f * a)))
        )

    val t = 0.11239642f + 1f / (
        +1.61320320f - 0.68124379f * b +
            a * (+0.40370612f + 0.90148123f * b +
                a * (-0.27087943f + 0.61223990f * b +
                    a * (+0.00299215f - 0.45399568f * b - 0.14661872f * a)))
        )

    return ST(s, t)
}

private fun getCs(l: Float, a: Float, b: Float): Cs {
    val cusp = findCusp(a, b)
    val cMax = findGamutIntersection(a, b, l, 1f, l, cusp)
    val stMax = toSt(cusp)
    val k = cMax / min(l * stMax.s, (1f - l) * stMax.t)

    val cMid = run {
        val stMid = getStMid(a, b)
        val cA = l * stMid.s
        val cB = (1f - l) * stMid.t
        0.9f * k * sqrt(sqrt(1f / (1f / (cA * cA * cA * cA) + 1f / (cB * cB * cB * cB))))
    }

    val c0 = run {
        val cA = l * 0.4f
        val cB = (1f - l) * 0.8f
        sqrt(1f / (1f / (cA * cA) + 1f / (cB * cB)))
    }

    return Cs(c0, cMid, cMax)
}

private fun okhslToSrgb(h: Float, s: Float, l: Float): LinearRgb {
    val hh = wrap01(h)
    val ss = clamp01(s)
    val ll = clamp01(l)

    if (ll >= 1f) return LinearRgb(1f, 1f, 1f)
    if (ll <= 0f) return LinearRgb(0f, 0f, 0f)

    val a = cos(2f * PI_F * hh)
    val b = sin(2f * PI_F * hh)

    val lOk = toeInv(ll)
    val cs = getCs(lOk, a, b)

    val mid = 0.8f
    val midInv = 1.25f

    val c = if (ss < mid) {
        val t = midInv * ss
        val k1 = mid * cs.c0
        val k2 = 1f - k1 / cs.cMid
        t * k1 / (1f - k2 * t)
    } else {
        val t = (ss - mid) / (1f - mid)
        val k0 = cs.cMid
        val k1 = (1f - mid) * cs.cMid * cs.cMid * midInv * midInv / cs.c0
        val k2 = 1f - k1 / (cs.cMax - cs.cMid)
        k0 + t * k1 / (1f - k2 * t)
    }

    return oklabToLinearSrgb(Oklab(lOk, c * a, c * b))
}

private fun okhsvToSrgb(h: Float, s: Float, v: Float): LinearRgb {
    val hh = wrap01(h)
    val ss = clamp01(s)
    val vv = clamp01(v)

    if (vv <= 0f) return LinearRgb(0f, 0f, 0f)

    val a = cos(2f * PI_F * hh)
    val b = sin(2f * PI_F * hh)

    val cusp = findCusp(a, b)
    val stMax = toSt(cusp)
    val sMax = stMax.s
    val tMax = stMax.t
    val s0 = 0.5f
    val k = 1f - s0 / sMax

    val lV = 1f - ss * s0 / (s0 + tMax - tMax * k * ss)
    val cV = ss * tMax * s0 / (s0 + tMax - tMax * k * ss)

    var l = vv * lV
    var c = vv * cV

    val lVt = toeInv(lV)
    val cVt = cV * lVt / lV

    if (l > 1e-8f) {
        val lNew = toeInv(l)
        c = c * lNew / l
        l = lNew
    } else {
        l = 0f
        c = 0f
    }

    val rgbScale = oklabToLinearSrgb(Oklab(lVt, a * cVt, b * cVt))
    val scaleL = cbrt((1f / max(max(rgbScale.r, rgbScale.g), max(rgbScale.b, 0f))).toDouble()).toFloat()

    l *= scaleL
    c *= scaleL

    return oklabToLinearSrgb(Oklab(l, c * a, c * b))
}

private fun hslToSrgb(h: Float, s: Float, l: Float): LinearRgb {
    val hh = wrap01(h)
    val ss = clamp01(s)
    val ll = clamp01(l)

    val c = (1f - abs(2f * ll - 1f)) * ss
    val h6 = hh * 6f
    val x = c * (1f - abs((h6 % 2f) - 1f))

    val (r1, g1, b1) = when {
        h6 < 1f -> Triple(c, x, 0f)
        h6 < 2f -> Triple(x, c, 0f)
        h6 < 3f -> Triple(0f, c, x)
        h6 < 4f -> Triple(0f, x, c)
        h6 < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    val m = ll - c * 0.5f
    return LinearRgb(r1 + m, g1 + m, b1 + m)
}

private fun linearToVec4(rgb: LinearRgb, alpha: Float): Vec4 = Vec4(
    clamp01(srgbTransfer(rgb.r)),
    clamp01(srgbTransfer(rgb.g)),
    clamp01(srgbTransfer(rgb.b)),
    clamp01(alpha)
)

/**
 * A colour from sRGB components, on whichever of the two usual scales you happened to type.
 *
 * The scale is inferred per call: if any of `r`, `g`, `b` exceeds 1 the three are read as 0..255,
 * otherwise as 0..1. Alpha is inferred separately on the same rule, so `rgb(255, 128, 0)` and
 * `rgb(1, 0.5, 0)` both work and `rgb(255, 128, 0, 0.5)` means half-transparent. Everything is
 * clamped into `[0,1]` on the way out, and the components are taken as *already* sRGB-encoded —
 * no transfer function is applied, unlike [hsl] and [okhsl].
 *
 * Reach for [rgb255] instead when a component genuinely might be 0 or 1 on the 0..255 scale and
 * the guess would go the wrong way.
 */
fun rgb(r: Number, g: Number, b: Number, a: Number = 1f): Vec4 {
    val rf = n(r)
    val gf = n(g)
    val bf = n(b)
    val af = n(a)

    val rgbScale = if (max(max(rf, gf), bf) > 1f) 1f / 255f else 1f
    val aScaled = if (af > 1f) af / 255f else af

    return Vec4(
        clamp01(rf * rgbScale),
        clamp01(gf * rgbScale),
        clamp01(bf * rgbScale),
        clamp01(aScaled)
    )
}

/** [rgb] under the name that reads better when you are passing alpha explicitly. Identical. */
fun rgba(r: Number, g: Number, b: Number, a: Number = 1f): Vec4 = rgb(r, g, b, a)

/**
 * A colour from 0..255 sRGB components, with no scale guessing — the unambiguous form of [rgb].
 *
 * Alpha is 0..255 here too and defaults to opaque, so `rgb255(18, 18, 18)` is the near-black a
 * dark theme wants and `rgb255(255, 0, 0, 128)` is a half-transparent red.
 */
fun rgb255(r: Number, g: Number, b: Number, a: Number = 255f): Vec4 = Vec4(
    clamp01(n(r) / 255f),
    clamp01(n(g) / 255f),
    clamp01(n(b) / 255f),
    clamp01(n(a) / 255f)
)

/**
 * A colour from classic hue / saturation / lightness.
 *
 * Hue may be a fraction of a turn (`0.33`) or degrees (`120`) — a magnitude above 1 is divided by
 * 360, and then wrapped. Note what that rule costs at the boundary: `1.25` is *above* 1, so it is read
 * as 1.25 **degrees** and lands on red, not a quarter turn round. Keep turns strictly below 1 and
 * degrees at 1 or above. Saturation
 * and lightness are 0..1 and clamped; `l = 0.5` is the fully saturated band, 0 is black and 1 white.
 *
 * The HSL cube is treated as linear light and then encoded to sRGB, the same final step [okhsl]
 * takes. That makes this *not* CSS's `hsl()`: any component that lands strictly between 0 and 1 is
 * gamma-shifted upwards, so `hsl(30, 1, 0.5)` is `(1, 0.735, 0)` where CSS gives `(1, 0.502, 0)`,
 * and `hsl(0, 0, 0.5)` is `0.735` grey rather than `0.5`. Only colours whose every component is
 * exactly 0 or 1 survive unchanged — the six 60°-multiple corners at `l = 0.5`, plus black and
 * white — because those are the fixed points of the transfer. If you want perceptually even
 * lightness across hues — a ramp where the yellow does not jump out of the row — use [okhsl].
 */
fun hsl(h: Number, s: Number, l: Number, a: Number = 1f): Vec4 {
    val linear = hslToSrgb(hue01(h), clamp01(n(s)), clamp01(n(l)))
    return linearToVec4(linear, n(a))
}

/** [hsl] under the name that reads better when you are passing alpha explicitly. Identical. */
fun hsla(h: Number, s: Number, l: Number, a: Number = 1f): Vec4 = hsl(h, s, l, a)

/**
 * A colour from Oklab-based hue / saturation / lightness — HSL with the perceptual kinks taken out.
 *
 * Same argument conventions as [hsl] (hue in turns or degrees, wrapped; saturation and lightness
 * 0..1, clamped) but `l` is *perceived* lightness, so a set of colours sharing one `l` really does
 * read as one tonal step. `s = 1` is the most saturated colour the sRGB gamut holds at that hue and
 * lightness rather than a fixed distance, which is why okhsl ramps stay in gamut without clipping.
 *
 * This is the one to reach for when building a palette by hand; [hueSetOkhsl] does the ring for you
 * and [paletteForBackground] additionally makes it legible against a known background.
 */
fun okhsl(h: Number, s: Number, l: Number, a: Number = 1f): Vec4 {
    val linear = okhslToSrgb(hue01(h), clamp01(n(s)), clamp01(n(l)))
    return linearToVec4(linear, n(a))
}

/**
 * A colour from Oklab-based hue / saturation / *value* — the HSV-shaped sibling of [okhsl].
 *
 * Conventions as [hsl]. The difference from [okhsl] is the one HSV has always had from HSL: `v = 1`
 * with `s = 1` is the vivid, full-strength colour rather than the mid-lightness one, and `v` scales
 * the whole thing down toward black. Use it when you want "same colour, dimmer" and [okhsl] when you
 * want "same lightness, different hue".
 */
fun okhsv(h: Number, s: Number, v: Number, a: Number = 1f): Vec4 {
    val linear = okhsvToSrgb(hue01(h), clamp01(n(s)), clamp01(n(v)))
    return linearToVec4(linear, n(a))
}

private fun equallySpacedHues(count: Int, startHue: Float): List<Float> {
    require(count > 0) { "count must be > 0" }
    val step = 1f / count.toFloat()
    val start = wrap01(startHue)
    return List(count) { i -> wrap01(start + i * step) }
}

/**
 * [count] colours whose hues are spaced evenly right round the wheel, all at one HSL saturation and
 * lightness — the one-liner for "give me n distinguishable colours".
 *
 * The step is `1/count` of a turn from [startHue], so the last entry is one step short of coming
 * back round. `startHue` follows the [hsl] convention (turns or degrees, wrapped). Consecutive
 * entries are neighbours on the wheel, which is what you want for a gradient and not what you want
 * for a legend — shuffle, or take every other one, if the categories must not be confusable.
 *
 * [hueSetOkhsl] is the same idea with even *perceived* lightness, and is usually the better default.
 */
fun hueSetHsl(
    count: Int,
    saturation: Number,
    lightness: Number,
    alpha: Number = 1f,
    startHue: Number = 0f
): List<Vec4> {
    val s = clamp01(n(saturation))
    val l = clamp01(n(lightness))
    val a = clamp01(n(alpha))
    return equallySpacedHues(count, hue01(startHue)).map { h -> hsl(h, s, l, a) }
}

/**
 * [count] evenly spaced hues at one [okhsl] saturation and lightness — the palette generator to
 * reach for first.
 *
 * Spacing and the [startHue] convention are as [hueSetHsl]; the difference is that because okhsl's
 * `l` is perceived lightness, no entry in the returned set is conspicuously brighter than its
 * neighbours the way an HSL yellow is. If the set has to sit on a particular background and stay
 * readable, use [paletteForBackground] instead.
 */
fun hueSetOkhsl(
    count: Int,
    saturation: Number,
    lightness: Number,
    alpha: Number = 1f,
    startHue: Number = 0f
): List<Vec4> {
    val s = clamp01(n(saturation))
    val l = clamp01(n(lightness))
    val a = clamp01(n(alpha))
    return equallySpacedHues(count, hue01(startHue)).map { h -> okhsl(h, s, l, a) }
}

/**
 * [count] evenly spaced hues at one [okhsv] saturation and value — [hueSetOkhsl] for the cases where
 * you want the vivid end of the gamut rather than a fixed lightness.
 *
 * Spacing and the [startHue] convention are as [hueSetHsl].
 */
fun hueSetOkhsv(
    count: Int,
    saturation: Number,
    value: Number,
    alpha: Number = 1f,
    startHue: Number = 0f
): List<Vec4> {
    val s = clamp01(n(saturation))
    val v = clamp01(n(value))
    val a = clamp01(n(alpha))
    return equallySpacedHues(count, hue01(startHue)).map { h -> okhsv(h, s, v, a) }
}

/** Short alias for [hueSetHsl]. */
fun hslSet(
    count: Int,
    saturation: Number,
    lightness: Number,
    alpha: Number = 1f,
    startHue: Number = 0f
): List<Vec4> = hueSetHsl(count, saturation, lightness, alpha, startHue)

/** Short alias for [hueSetOkhsl]. */
fun okhslSet(
    count: Int,
    saturation: Number,
    lightness: Number,
    alpha: Number = 1f,
    startHue: Number = 0f
): List<Vec4> = hueSetOkhsl(count, saturation, lightness, alpha, startHue)

/** Short alias for [hueSetOkhsv]. */
fun okhsvSet(
    count: Int,
    saturation: Number,
    value: Number,
    alpha: Number = 1f,
    startHue: Number = 0f
): List<Vec4> = hueSetOkhsv(count, saturation, value, alpha, startHue)

private fun relativeLuminanceSrgb(color: Vec4): Float {
    val r = srgbTransferInv(clamp01(color.x))
    val g = srgbTransferInv(clamp01(color.y))
    val b = srgbTransferInv(clamp01(color.z))
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun contrastRatio(a: Vec4, b: Vec4): Float {
    val la = relativeLuminanceSrgb(a)
    val lb = relativeLuminanceSrgb(b)
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

private fun contrastAdjustedOkhsl(
    hue: Float,
    saturation: Float,
    baseLightness: Float,
    alpha: Float,
    background: Vec4,
    minContrast: Float,
    maxLightnessShift: Float,
    lightnessSteps: Int
): Vec4 {
    val base = okhsl(hue, saturation, baseLightness, alpha)
    var best = base
    var bestContrast = contrastRatio(base, background)
    if (bestContrast >= minContrast) return base

    val preferLighter = relativeLuminanceSrgb(background) < 0.5f
    val shift = clamp01(maxLightnessShift)
    val upper = min(1f, baseLightness + shift)
    val lower = max(0f, baseLightness - shift)

    fun scan(toLightness: Float): Vec4? {
        for (i in 1..lightnessSteps) {
            val t = i.toFloat() / lightnessSteps.toFloat()
            val l = baseLightness + (toLightness - baseLightness) * t
            val candidate = okhsl(hue, saturation, l, alpha)
            val contrast = contrastRatio(candidate, background)
            if (contrast > bestContrast) {
                bestContrast = contrast
                best = candidate
            }
            if (contrast >= minContrast) return candidate
        }
        return null
    }

    val passed = if (preferLighter) {
        scan(upper) ?: scan(lower)
    } else {
        scan(lower) ?: scan(upper)
    }

    return passed ?: best
}

/**
 * [count] evenly spaced okhsl hues, each nudged in lightness until it is actually legible against
 * [background].
 *
 * Starts from the same ring as [hueSetOkhsl], then for any entry whose WCAG contrast ratio against
 * the background falls short of [minContrast] it walks that entry's lightness in up to
 * [lightnessSteps] steps, at most [maxLightnessShift] away from [lightness], and takes the first
 * candidate that clears the bar. The direction is chosen from the background: a dark background is
 * searched lighter first, a light one darker first, and the other direction is tried if the first
 * runs out of room. When nothing reaches [minContrast] the best candidate found is returned rather
 * than an exception — the result is always [count] colours.
 *
 * [minContrast] is a ratio in the WCAG sense, where 3 is the large-text threshold, 4.5 body text and
 * 7 the strict one; the default of 3 suits the graph strokes and labels this is usually used for.
 * Contrast is measured against the background alone, so semi-transparent [alpha] values are not
 * accounted for.
 */
fun paletteForBackground(
    background: Vec4,
    count: Int,
    saturation: Number = 0.72f,
    lightness: Number = 0.62f,
    alpha: Number = 1f,
    startHue: Number = 0f,
    minContrast: Number = 3f,
    maxLightnessShift: Number = 0.30f,
    lightnessSteps: Int = 24
): List<Vec4> {
    require(count > 0) { "count must be > 0" }
    require(lightnessSteps > 0) { "lightnessSteps must be > 0" }

    val s = clamp01(n(saturation))
    val l = clamp01(n(lightness))
    val a = clamp01(n(alpha))
    val minCr = max(1f, n(minContrast))
    val maxShift = n(maxLightnessShift)

    return equallySpacedHues(count, hue01(startHue)).map { h ->
        contrastAdjustedOkhsl(
            hue = h,
            saturation = s,
            baseLightness = l,
            alpha = a,
            background = background,
            minContrast = minCr,
            maxLightnessShift = maxShift,
            lightnessSteps = lightnessSteps
        )
    }
}

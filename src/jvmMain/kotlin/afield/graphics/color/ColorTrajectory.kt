@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.graphics.color

import afield.graphics.paint.Tubes
import afield.math.linalg.Vec4
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which direction an OKLCH interpolation takes around the hue circle. */
enum class HuePath { SHORTER, LONGER, INCREASING, DECREASING }

/** A path from t=0 to t=1 returning drawable sRGB colors. */
fun interface ColorTrajectory {
    /** Evaluate the color at a fraction of the path. */
    operator fun invoke(t: Double): Vec4

    /** Sample [count] swatches, including both endpoints when count is at least two. */
    fun sample(count: Int): List<Vec4> {
        require(count > 0) { "Swatch count must be positive" }
        return List(count) { this(it.toDouble() / (count - 1).coerceAtLeast(1)) }
    }

    /**
     * Reparameterize the displayed path by approximate OKLab arc length using [resolution] intervals.
     * Useful for stepping through a ramp or driving an animation at roughly constant visual speed.
     * Alpha is ignored. A zero-length path keeps its original parameterization.
     */
    fun byPerceptualDistance(resolution: Int = 256): ColorTrajectory {
        require(resolution > 0) { "Resolution must be positive" }
        val distance = DoubleArray(resolution + 1)
        var previous = this(0.0)
        for (i in 1..resolution) {
            val current = this(i.toDouble() / resolution)
            distance[i] = distance[i - 1] + deltaEOK(previous, current)
            previous = current
        }
        if (distance.last() == 0.0) return this
        return ColorTrajectory { t ->
            val u = unitParameter(t)
            if (u == 0.0 || u == 1.0) this(u) else {
                val target = u * distance.last()
                val index = distance.binarySearch(target)
                if (index >= 0) this(index.toDouble() / resolution) else {
                    val upper = -index - 1
                    val fraction = (target - distance[upper - 1]) / (distance[upper] - distance[upper - 1])
                    this((upper - 1 + fraction) / resolution)
                }
            }
        }
    }
}

/**
 * Interpolate through equally spaced [stops]. Components are premultiplied by alpha in [space],
 * with hue interpolated separately. An achromatic OKLCH endpoint borrows its neighbor's hue.
 */
fun colorRamp(
    stops: List<Vec4>,
    space: ColorSpace = ColorSpace.OKLAB,
    huePath: HuePath = HuePath.SHORTER,
    gamut: ColorGamut = ColorGamut.REDUCE_CHROMA
): ColorTrajectory {
    require(stops.isNotEmpty()) { "A ramp needs at least one stop" }
    val keys = stops.map { colorCoordinates(it, ColorSpace.SRGB) }
    val points = keys.map { it.convert(space) }
    return ColorTrajectory { t ->
        val index = unitParameter(t) * points.lastIndex
        val left = floor(index).toInt()
        if (index == left.toDouble()) keys[left].toSrgb(gamut)
        else interpolateColor(points[left], points[left + 1], index - left, huePath).toSrgb(gamut)
    }
}

/** Mix two colors in [space] at fraction [t]. For repeated samples, build a [colorRamp] once. */
fun mixColor(
    first: Vec4, second: Vec4, t: Number,
    space: ColorSpace = ColorSpace.OKLAB, huePath: HuePath = HuePath.SHORTER,
    gamut: ColorGamut = ColorGamut.REDUCE_CHROMA
): Vec4 = colorRamp(listOf(first, second), space, huePath, gamut)(t.toDouble())

/**
 * A cubic Bézier in OKLab, where [handle1] and [handle2] steer the path between [start] and [end].
 * The coordinates are interpolated with premultiplied alpha and gamut mapped after evaluation.
 */
fun colorBezier(
    start: ColorCoordinates, handle1: ColorCoordinates, handle2: ColorCoordinates, end: ColorCoordinates,
    gamut: ColorGamut = ColorGamut.REDUCE_CHROMA
): ColorTrajectory {
    val (a, b, c, d) = listOf(start, handle1, handle2, end).map { it.convert(ColorSpace.OKLAB) }
    return ColorTrajectory { t ->
        val u = unitParameter(t)
        val ab = interpolateColor(a, b, u, HuePath.SHORTER)
        val bc = interpolateColor(b, c, u, HuePath.SHORTER)
        val cd = interpolateColor(c, d, u, HuePath.SHORTER)
        val abc = interpolateColor(ab, bc, u, HuePath.SHORTER)
        val bcd = interpolateColor(bc, cd, u, HuePath.SHORTER)
        interpolateColor(abc, bcd, u, HuePath.SHORTER).toSrgb(gamut)
    }
}

/**
 * Sweep OKLCH hue while changing lightness. [chromaFraction] is a share of available sRGB chroma
 * at each point. Signed [turns] controls direction and can describe part of a turn or several.
 */
fun colorHelix(
    startLightness: Double = 0.25, endLightness: Double = 0.90,
    startHueDegrees: Double = 265.0, turns: Double = -0.7, chromaFraction: Double = 0.7
): ColorTrajectory {
    require(startLightness in 0.0..1.0 && endLightness in 0.0..1.0 && chromaFraction in 0.0..1.0)
    require(startHueDegrees.isFinite() && (turns * 360.0).isFinite())
    return ColorTrajectory { t ->
        val u = unitParameter(t)
        val l = startLightness + (endLightness - startLightness) * u
        val h = wrapDegrees(startHueDegrees) + (turns * 360.0) * u
        oklch(l, chromaFraction * srgbChromaLimit(l, h), h)
    }
}

/**
 * Mix arbitrary sRGB endpoints with the paint simulator's spectral Kubelka–Munk mixer. The
 * endpoints are exact and alpha is interpolated separately from pigment weights.
 */
fun spectralColorRamp(first: Vec4, second: Vec4): ColorTrajectory {
    val a = colorCoordinates(first, ColorSpace.SRGB).toSrgb()
    val b = colorCoordinates(second, ColorSpace.SRGB).toSrgb()
    return ColorTrajectory { t ->
        val u = unitParameter(t)
        when (u) {
            0.0 -> Vec4(a)
            1.0 -> Vec4(b)
            else -> Tubes.mixSpectral(a to (1.0 - u), b to u).also {
                it.w = (a.w * (1.0 - u) + b.w * u).toFloat()
            }
        }
    }
}

/** Mix two named paint tubes using the same volume fractions and tinting strengths as a brush load. */
fun tubeColorRamp(first: String, second: String): ColorTrajectory {
    val a = Vec4(Tubes[first].color)
    val b = Vec4(Tubes[second].color)
    return ColorTrajectory { t ->
        val u = unitParameter(t)
        when {
            u == 0.0 || first == second -> Vec4(a)
            u == 1.0 -> Vec4(b)
            else -> Tubes.mix(mapOf(first to 1.0 - u, second to u))
        }
    }
}

private fun interpolateColor(
    a: ColorCoordinates, b: ColorCoordinates, t: Double, huePath: HuePath
): ColorCoordinates {
    if (t == 0.0) return a
    if (t == 1.0) return b
    val left = (1.0 - t) * a.alpha
    val right = t * b.alpha
    val alpha = (left + right).coerceIn(0.0, 1.0)
    val u = if (alpha > 0.0) right / (left + right) else t
    fun mix(x: Double, y: Double): Double = x * (1.0 - u) + y * u
    if (a.space != ColorSpace.OKLCH) {
        return ColorCoordinates(a.space, mix(a.x, b.x), mix(a.y, b.y), mix(a.z, b.z), alpha)
    }
    val aHue = if (a.y < 1e-7) b.z else a.z
    val bHue = if (b.y < 1e-7) aHue else b.z
    val hue = if (a.y < 1e-7 || b.y < 1e-7) aHue else interpolateHue(aHue, bHue, t, huePath)
    return ColorCoordinates(a.space, mix(a.x, b.x), mix(a.y, b.y), hue, alpha)
}

private fun interpolateHue(a: Double, b: Double, t: Double, path: HuePath): Double {
    val start = wrapDegrees(a)
    val d = wrapDegrees(b) - start
    val delta = when (path) {
        HuePath.SHORTER -> when {
            d > 180.0 -> d - 360.0
            d < -180.0 -> d + 360.0
            else -> d
        }
        HuePath.LONGER -> when {
            d in 0.0..<180.0 -> d - 360.0
            d > -180.0 && d < 0.0 -> d + 360.0
            else -> d
        }
        HuePath.INCREASING -> if (d < 0.0) d + 360.0 else d
        HuePath.DECREASING -> if (d > 0.0) d - 360.0 else d
    }
    return wrapDegrees(start + delta * t)
}

private fun unitParameter(t: Double): Double {
    require(t.isFinite()) { "Trajectory parameter must be finite" }
    return t.coerceIn(0.0, 1.0)
}

/** Parse CSS hex notation (#RGB, #RGBA, #RRGGBB or #RRGGBBAA). */
fun colorHex(hex: String): Vec4 {
    val digits = hex.trim().removePrefix("#")
    require(digits.length in listOf(3, 4, 6, 8) && digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
        "Expected a 3, 4, 6 or 8 digit hex color"
    }
    val bytes = if (digits.length <= 4) digits.map { it.digitToInt(16) * 17 }
    else digits.chunked(2).map { it.toInt(16) }
    return rgb255(bytes[0], bytes[1], bytes[2], bytes.getOrElse(3) { 255 })
}

/** Format a drawing color as CSS hex, with an alpha byte when [includeAlpha] is true. */
fun colorHex(color: Vec4, includeAlpha: Boolean = color.w < 1f): String {
    fun byte(channel: Float): String = (channel.coerceIn(0f, 1f) * 255f).roundToInt().toString(16).padStart(2, '0')
    val opaque = "#${byte(color.x)}${byte(color.y)}${byte(color.z)}"
    return if (includeAlpha) opaque + byte(color.w) else opaque
}

/** WCAG contrast after compositing [foreground] over an opaque [background] in encoded sRGB. */
fun wcagContrast(foreground: Vec4, background: Vec4): Double {
    require(background.w == 1f) { "Contrast requires an opaque background" }
    val alpha = foreground.w.toDouble().coerceIn(0.0, 1.0)
    fun channel(value: Float) = value.toDouble().coerceIn(0.0, 1.0)
    fun luminance(r: Double, g: Double, b: Double): Double =
        0.2126 * decodeSrgb(r) + 0.7152 * decodeSrgb(g) + 0.0722 * decodeSrgb(b)
    fun over(fg: Float, bg: Float) = channel(fg) * alpha + channel(bg) * (1.0 - alpha)
    val fg = luminance(over(foreground.x, background.x), over(foreground.y, background.y), over(foreground.z, background.z))
    val bg = luminance(channel(background.x), channel(background.y), channel(background.z))
    return (maxOf(fg, bg) + 0.05) / (minOf(fg, bg) + 0.05)
}

/** One contrast target and the actual candidate found on a trajectory. */
data class ContrastSwatch(
    val target: Double, val achieved: Double, val parameter: Double, val color: Vec4
) {
    /** True if the candidate meets the requested ratio. */
    val reached: Boolean get() = achieved >= target
}

/**
 * Pick colors along [trajectory] for a list of WCAG contrast targets on an opaque [background].
 * Uses the earliest crossing detected by [resolution] sampling intervals, refining it by bisection.
 * If the starting color already passes, it searches for a crossing from above as well. A target
 * with no detected crossing gets the closest sampled passing color, or the closest miss.
 * Raise [resolution] when the curve has narrow peaks or sharp changes.
 */
fun contrastPalette(
    trajectory: ColorTrajectory, background: Vec4, targets: List<Double>, resolution: Int = 256
): List<ContrastSwatch> {
    require(resolution > 0) { "Resolution must be positive" }
    require(targets.all { it in 1.0..21.0 }) { "WCAG targets must be in 1..21" }
    if (targets.isEmpty()) return emptyList()

    fun sample(t: Double, target: Double): ContrastSwatch {
        val color = trajectory(t)
        return ContrastSwatch(target, wcagContrast(color, background), t, color)
    }
    val samples = List(resolution + 1) { sample(it.toDouble() / resolution, 1.0) }
    return targets.map { target ->
        var previous = samples.first().copy(target = target)
        var closest = previous
        for (i in 1..resolution) {
            if (previous.achieved == target) return@map previous
            val next = samples[i].copy(target = target)
            if (previous.reached != next.reached) {
                var passing = if (previous.reached) previous else next
                var failing = if (previous.reached) next else previous
                repeat(24) {
                    val mid = sample((passing.parameter + failing.parameter) * 0.5, target)
                    if (mid.reached) passing = mid else failing = mid
                }
                return@map passing
            }
            if (abs(next.achieved - target) < abs(closest.achieved - target)) closest = next
            previous = next
        }
        closest
    }
}

/** Step names of a [contrastScale], 100 (closest to the background) to 1000 (furthest). */
val contrastScaleSteps: List<Int> = (1..10).map { it * 100 }

/** Per-step WCAG contrast targets against white, from TaishoDS's one-colour palette method. */
val contrastTargetsLight: List<Double> = listOf(1.08, 1.22, 1.5, 2.1, 3.6, 6.0, 8.5, 12.0, 16.0, 19.0)

/**
 * Per-step contrast targets against a near-black background. Not light mode flipped: the low steps
 * keep the same small separations from the ground, but the mid steps sit a little higher (dark
 * grounds compress perceived contrast) and the top is capped below the ~19.8 that `#0a0a0a`
 * allows against white. The 500/600 boundary (√(3.8·5.5) ≈ 4.57) stays above 4.5, so whatever
 * lands on 600 is text-safe.
 */
val contrastTargetsDark: List<Double> = listOf(1.08, 1.25, 1.6, 2.3, 3.8, 5.5, 8.0, 11.0, 14.5, 17.5)

/**
 * A ten-step ramp built by [contrastScale]: one [ContrastSwatch] per step, 100 to 1000.
 * [seedStep] is the step that holds the seed colour unchanged.
 */
data class ContrastScale(val background: Vec4, val swatches: List<ContrastSwatch>, val seedStep: Int) {
    /** The step colours, 100 to 1000. */
    val colors: List<Vec4> get() = swatches.map { it.color }

    /** Near-black or white per step, whichever has more contrast on that step's colour. */
    val textOn: List<Vec4>
        get() = colors.map { c ->
            val opaque = Vec4(c.x, c.y, c.z, 1f)
            if (wcagContrast(scaleDarkText, opaque) >= wcagContrast(scaleLightText, opaque)) scaleDarkText else scaleLightText
        }

    /** The swatch at a step name (100, 200, ... 1000). */
    operator fun get(step: Int): ContrastSwatch {
        val i = contrastScaleSteps.indexOf(step)
        require(i >= 0) { "step must be one of $contrastScaleSteps, got $step" }
        return swatches[i]
    }
}

private val scaleDarkText = Vec4(0.04f, 0.04f, 0.04f, 1f)
private val scaleLightText = Vec4(1f, 1f, 1f, 1f)

/**
 * A ten-step accessible ramp from one [seed] colour, each step at a set WCAG contrast against an
 * opaque [background]. After TaishoDS's method (taisho.design, MIT).
 *
 * Where [contrastPalette] takes a trajectory and any targets, this takes one colour and returns a
 * fixed ten-step scale with the seed on it unchanged. The seed's own contrast picks its step (the
 * target nearest on a log scale). Every other step runs [contrastPalette] along an OKLCH lightness
 * trajectory through the seed's hue, moving away from the background; chroma is capped at the
 * seed's, eased off toward both ends of the ramp, and reduced where sRGB cannot show it. Yellows
 * (OKLCH hue about 65°–125°) rotate toward orange as they darken and toward lemon as they lighten;
 * otherwise dark yellow is olive.
 *
 * [targets] defaults to [contrastTargetsLight] on a light background and [contrastTargetsDark] on a
 * dark one. Steps 600 and up clear 4.5:1, so they are usable as text; 100–300 are surfaces and borders.
 */
fun contrastScale(seed: Vec4, background: Vec4 = Vec4(1f, 1f, 1f, 1f), targets: List<Double>? = null): ContrastScale {
    val dark = wcagContrast(Vec4(1f, 1f, 1f, 1f), background) > wcagContrast(Vec4(0f, 0f, 0f, 1f), background)
    val t = targets ?: if (dark) contrastTargetsDark else contrastTargetsLight
    require(t.size == 10) { "targets must have 10 entries (steps 100..1000), got ${t.size}" }

    val lch = colorCoordinates(seed, ColorSpace.OKLCH)
    val seedContrast = wcagContrast(seed, background)
    val seedIndex = t.indices.minBy { abs(ln(seedContrast / t[it])) }
    val yellowWeight = (1.0 - abs(lch.z - 95.0) / 30.0).coerceIn(0.0, 1.0) * (lch.y / 0.06).coerceIn(0.0, 1.0)
    // Parameter 0 is lightness at the background's end, 1 the far end.
    fun lightness(p: Double) = if (dark) p else 1.0 - p

    val swatches = t.mapIndexed { i, target ->
        if (i == seedIndex) return@mapIndexed ContrastSwatch(target, seedContrast, if (dark) lch.x else 1.0 - lch.x, seed)
        val chroma = lch.y * (1.0 - 0.6 * (abs(i - 4.5) / 4.5).pow(2))
        val trajectory = ColorTrajectory { p ->
            val l = lightness(p)
            oklch(l, chroma, wrapDegrees(lch.z + yellowWeight * 70.0 * (l - lch.x)), seed.w)
        }
        contrastPalette(trajectory, background, listOf(target)).single()
    }
    return ContrastScale(background, swatches, contrastScaleSteps[seedIndex])
}

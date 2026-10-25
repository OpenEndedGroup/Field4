package afield.lang.stacksl.cpu

/**
 * The losses a [StackSLFit] can descend on: mean squared RGB error, LPIPS perceptual distance, and
 * mixtures of the two.
 *
 * [StackSLFitLoss] names the choices; [StackSLLpipsLoss] computes the LPIPS value and gradient of a
 * render against its target.
 */

import afield.ai.inference.onnx.Lpips
import afield.math.autodiff.ADGradient

/** What a [StackSLFit] descends on. */
enum class StackSLFitLoss(val label: String) {
    /** Mean squared RGB error: [StackSLImageLoss.valueAndGradient], the exact per-pixel loss. */
    MSE("mse"),

    /**
     * The LPIPS distance ([Lpips]) of the render, enlarged pixel by pixel to the side LPIPS sees,
     * from the target: [StackSLLpipsLoss]. Optionally averaged with LPIPS over a grid of regions.
     *
     * On its own it is a poor fit loss for these programs. LPIPS hardly tells a picture from its
     * negative (the inverted `rings` seed is at LPIPS skill 0.96), so a descent on it never turns an
     * inverted seed round; and through a `threshold`'s surrogate gradient it followed the
     * linearised network the wrong way on a `bands` seed, stripe frequency 2.6 to 2.1 where the
     * target's is 3, ending with the colours inverted (polarity -0.13) and its own loss doubled.
     * Measured in `StackSLLpipsFitMeasurements`; [LPIPS_PIXEL] is what a search uses.
     */
    LPIPS("lpips"),

    /**
     * The mean of [LPIPS] and [MSE], value and gradient. LPIPS compares features wherever they are
     * and barely distinguishes a picture from its negative; the squared error pins colours.
     */
    LPIPS_PIXEL("lpips+mse")
}

/**
 * An LPIPS loss over a wired program's parameter vector, with its gradient: the LPIPS gradient
 * with respect to the image ([Lpips.distanceAndGradientPlanar]) chained through the program by
 * [StackSLImageLoss.seededGradient].
 *
 * The render is [StackSLImageLoss.dim] square; LPIPS sees it enlarged nearest-neighbour by
 * `k = side / dim` (the way `StackSLImageObjective.lpipsPlanar` enlarges it for the search), so each
 * render pixel is `k x k` LPIPS pixels and its gradient is the sum over that block ([blockSum]).
 *
 * With [regionGrid] >= 2 the value is the mean of the whole-image distance and the mean distance
 * over a [regionGrid] by [regionGrid] tiling (each region scored as its own image, as the search's
 * lexicase cases are), and the gradient is the same mean of their gradients. The regions go to the
 * network as one batch.
 *
 * [kind] [StackSLFitLoss.LPIPS_PIXEL] adds the squared error with weight one half each; the two
 * seeds are added before the one backward sweep, so it costs no more than LPIPS alone.
 *
 * The value is exact; the gradient is exact except where an AlexNet max pool holds an exact tie
 * (see [Lpips.distanceAndGradientPlanar]) and wherever an [AdSurrogate] is on.
 */
class StackSLLpipsLoss(
    val image: StackSLImageLoss,
    val side: Int = DEFAULT_SIDE,
    val regionGrid: Int = 0,
    val kind: StackSLFitLoss = StackSLFitLoss.LPIPS
) {
    val dim: Int get() = image.dim

    init {
        require(kind != StackSLFitLoss.MSE) { "an MSE fit is StackSLImageLoss itself" }
        require(side % image.dim == 0 && side >= Lpips.MIN_SIDE) {
            "LPIPS side $side must be a multiple of the fit's ${image.dim} and at least ${Lpips.MIN_SIDE}"
        }
        require(regionGrid == 0 || (regionGrid >= 2 && side % regionGrid == 0 && side / regionGrid >= Lpips.MIN_SIDE)) {
            "a $regionGrid x $regionGrid region grid needs a side that is a multiple of it with regions of at least ${Lpips.MIN_SIDE}, got $side"
        }
    }

    private val targetPlanar: FloatArray = enlarge(image.target, side)
    private val targetRegions: FloatArray? = if (regionGrid >= 2) regions(targetPlanar, side, regionGrid) else null

    /** The LPIPS term for [raster] and its gradient with respect to the enlarged image (planar, `3 x side x side`). */
    private fun imageValueAndGradient(raster: CpuRaster): Pair<Double, FloatArray> {
        val planar = enlarge(raster, side)
        val (whole, g) = Lpips.distanceAndGradientPlanar(planar, side, side, targetPlanar)
        if (targetRegions == null) return whole to g
        val count = regionGrid * regionGrid
        val cell = side / regionGrid
        val (ds, gs) = Lpips.distancesAndGradientsPlanar(regions(planar, side, regionGrid), count, cell, cell, targetRegions, count)
        val back = unregions(gs, side, regionGrid)
        val combined = FloatArray(g.size) { 0.5f * g[it] + 0.5f * back[it] / count }
        return 0.5 * (whole + ds.average()) to combined
    }

    /** The loss at [values] (the [kind]'s value, no gradient). */
    fun value(values: DoubleArray): Double {
        val raster = image.render(values)
        val lp = imageValueAndGradient(raster).first
        return if (kind == StackSLFitLoss.LPIPS) lp else 0.5 * (lp + StackSLImageLoss.meanSquaredError(raster, image.target))
    }

    /** The loss and its gradient with respect to every wire. */
    fun valueAndGradient(values: DoubleArray, surrogate: AdSurrogate = AdSurrogate.OFF): ADGradient {
        val raster = image.render(values)
        val (lp, g) = imageValueAndGradient(raster)
        val seed = blockSum(g, side, dim)
        var value = lp
        if (kind == StackSLFitLoss.LPIPS_PIXEL) {
            val mse = StackSLImageLoss.meanSquaredError(raster, image.target)
            value = 0.5 * (lp + mse)
            val pixel = mseSeed(raster, image.target)
            for (i in seed.indices) seed[i] = 0.5f * seed[i] + 0.5f * pixel[i]
        }
        return ADGradient(value, image.seededGradient(values, seed, surrogate))
    }

    companion object {
        /** LPIPS's side for a 32 px fit: 64, as `StackSLImageObjective.DEFAULT_LPIPS_SIDE`. */
        const val DEFAULT_SIDE: Int = 64

        /**
         * [raster] as LPIPS input: planar `3 x side x side`, each pixel repeated `side / width`
         * times each way, clamped to `0..1`. The same numbers `StackSLImageObjective.lpipsPlanar`
         * produces.
         */
        fun enlarge(raster: CpuRaster, side: Int): FloatArray {
            require(raster.width == raster.height && side % raster.width == 0) {
                "cannot enlarge a ${raster.width}x${raster.height} raster to $side"
            }
            val k = side / raster.width
            val plane = side * side
            val out = FloatArray(3 * plane)
            for (y in 0 until side) for (x in 0 until side) {
                val src = ((y / k) * raster.width + x / k) * 4
                val dst = y * side + x
                for (ch in 0 until 3) out[ch * plane + dst] = raster.rgba[src + ch].coerceIn(0f, 1f)
            }
            return out
        }

        /**
         * The adjoint of [enlarge] (less its clamp): a planar `3 x side x side` gradient summed over
         * each `k x k` block into a planar `3 x dim x dim` one, `k = side / dim`. A render pixel is
         * copied into every pixel of its block, so its derivative is the sum of theirs.
         */
        fun blockSum(gradient: FloatArray, side: Int, dim: Int): FloatArray {
            require(side % dim == 0 && gradient.size == 3 * side * side) { "cannot sum a $side-pixel gradient into $dim" }
            val k = side / dim
            val out = FloatArray(3 * dim * dim)
            for (ch in 0 until 3) for (y in 0 until side) for (x in 0 until side) {
                out[ch * dim * dim + (y / k) * dim + x / k] += gradient[ch * side * side + y * side + x]
            }
            return out
        }

        /** The squared error's image gradient, `2 (out - target) / (3 dim^2)`, planar. */
        fun mseSeed(raster: CpuRaster, target: CpuRaster): FloatArray {
            val dim = raster.width
            val plane = dim * dim
            val scale = 2.0 / (3.0 * plane)
            return FloatArray(3 * plane) { i ->
                val ch = i / plane
                val p = i % plane
                ((raster.rgba[p * 4 + ch].toDouble() - target.rgba[p * 4 + ch].toDouble()) * scale).toFloat()
            }
        }

        /** The regions of a planar `3 x side x side` image, `grid^2` planar images, top-left first, row by row. */
        fun regions(planar: FloatArray, side: Int, grid: Int): FloatArray {
            val cell = side / grid
            val plane = side * side
            val cPlane = cell * cell
            val out = FloatArray(grid * grid * 3 * cPlane)
            for (q in 0 until grid * grid) {
                val ox = (q % grid) * cell
                val oy = (q / grid) * cell
                for (c in 0 until 3) for (y in 0 until cell) for (x in 0 until cell) {
                    out[q * 3 * cPlane + c * cPlane + y * cell + x] = planar[c * plane + (oy + y) * side + ox + x]
                }
            }
            return out
        }

        /** The inverse of [regions]: `grid^2` planar regions put back into one `3 x side x side` image. */
        fun unregions(parts: FloatArray, side: Int, grid: Int): FloatArray {
            val cell = side / grid
            val plane = side * side
            val cPlane = cell * cell
            val out = FloatArray(3 * plane)
            for (q in 0 until grid * grid) {
                val ox = (q % grid) * cell
                val oy = (q / grid) * cell
                for (c in 0 until 3) for (y in 0 until cell) for (x in 0 until cell) {
                    out[c * plane + (oy + y) * side + ox + x] = parts[q * 3 * cPlane + c * cPlane + y * cell + x]
                }
            }
            return out
        }
    }
}

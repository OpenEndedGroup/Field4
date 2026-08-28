@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A Gaussian splat cloud as **editable CPU data**: read it, ask it questions, crop it, filter it,
 * decimate it, move it, merge it, and write it back out as a `.compressed.ply` that
 * [CompressedSplatPly] reads again.
 *
 * ## Why a fourth representation
 *
 * The tree already has three. [PlyFloatTable] is the file's own conventions — log scales, logit
 * opacity, spherical-harmonic DC colour — which is right for a reader and hostile to a person: the
 * number you would type to make a splat half as wide is not `0.5` but a subtraction of `ln 2`.
 * [CompressedSplatPly.RawSplats] is the quantized payload, deliberately undecoded. And
 * [afield.four.fabricated.GaussianSplatRenderer]'s 16-float layout is a *rendering* form — the
 * covariance is already baked into six numbers from which the scale and rotation cannot be cleanly
 * recovered.
 *
 * [SplatCloud] is the form you can edit. Scales are **world units**, opacity is **0..1**, colour is
 * **linear 0..1**, rotations are unit quaternions. Every editing operation below is expressible as
 * arithmetic a person would recognise, and none of them needs to know that the file spells any of it
 * differently.
 *
 * It does not add a fourth *decoder*. [of] converts from the table [readCompressedSplatPly] already
 * produced; the bit-level unpacking happens once, in [CompressedSplatPly], as it did before.
 *
 * ## The shape worth reaching for
 *
 * ```
 * val cloud = SplatCloud.read("frame_000060.compressed.ply")
 * println(cloud.stats())                                   // query
 * val trimmed = cloud.filtered(minAlpha = 0.15f)           // edit
 *     .cropped(Vector3f(-0.5f, -1f, 0f), Vector3f(0.5f, 1f, 2f))
 *     .decimated(0.5f)
 * val png = SplatRaster.rasterize(                          // preview, no GPU
 *     listOf(GaussianSplatRenderer.SplatSource(trimmed.toRawSplats())), view
 * ).image.toPng()
 * trimmed.write(File("trimmed.compressed.ply"))             // write
 * ```
 *
 * Read, query, edit, preview, write — and the written file goes back through the same reader, which
 * `SplatCloudEditTest` checks by round-tripping and comparing.
 */
class SplatCloud(
    /** `x, y, z` per splat, world space. */
    val positions: FloatArray,
    /** Linear `r, g, b` per splat in 0..1 — what the shader consumes, not the SH DC coefficient. */
    val colors: FloatArray,
    /** Opacity in 0..1, post-sigmoid. */
    val alphas: FloatArray,
    /** Unit quaternion per splat, `w, x, y, z` — `rot_0` first, as a 3DGS PLY spells it. */
    val rotations: FloatArray,
    /** `sx, sy, sz` per splat in **world units**, not logs. */
    val scales: FloatArray,
    val count: Int
) {
    init {
        require(count >= 0) { "count must be non-negative, got $count" }
        require(positions.size >= count * 3) { "positions holds ${positions.size / 3} of $count splats" }
        require(colors.size >= count * 3) { "colors holds ${colors.size / 3} of $count splats" }
        require(alphas.size >= count) { "alphas holds ${alphas.size} of $count splats" }
        require(rotations.size >= count * 4) { "rotations holds ${rotations.size / 4} of $count splats" }
        require(scales.size >= count * 3) { "scales holds ${scales.size / 3} of $count splats" }
    }

    // ------------------------------------------------------------------------
    // Query
    // ------------------------------------------------------------------------

    /**
     * What is actually in this cloud.
     *
     * Percentiles rather than only means, because the questions a capture raises are about tails:
     * a depth estimator that lost the plot leaves a few splats a hundred times too large, and a mean
     * scale will not show it while a p99 will.
     */
    data class CloudStats(
        val count: Int,
        val boundsMin: Vector3f,
        val boundsMax: Vector3f,
        val meanColor: Vector3f,
        /** Mean of `max(rgb) - min(rgb)`. Near zero means the capture is greyscale. */
        val meanSaturation: Float,
        val meanAlpha: Float,
        val alphaPercentiles: FloatArray,
        /** Geometric mean of the three world-space scales, per splat, then averaged. */
        val meanScale: Float,
        val scalePercentiles: FloatArray,
        val nonFinite: Int
    ) {
        companion object {
            /** The percentiles [alphaPercentiles] and [scalePercentiles] are reported at. */
            val PERCENTILES = floatArrayOf(1f, 25f, 50f, 75f, 99f)
        }

        override fun toString(): String = buildString {
            append("$count splats\n")
            append("  bounds      $boundsMin .. $boundsMax\n")
            append("  size        (%.4f, %.4f, %.4f)\n".format(
                boundsMax.x - boundsMin.x, boundsMax.y - boundsMin.y, boundsMax.z - boundsMin.z
            ))
            append("  colour      mean (%.4f, %.4f, %.4f), saturation %.4f%s\n".format(
                meanColor.x, meanColor.y, meanColor.z, meanSaturation,
                if (meanSaturation < 0.02f) "  <- effectively greyscale" else ""
            ))
            append("  opacity     mean %.4f, p1/p25/p50/p75/p99 %s\n".format(
                meanAlpha, alphaPercentiles.joinToString(" / ") { "%.3f".format(it) }
            ))
            append("  scale (m)   mean %.5f, p1/p25/p50/p75/p99 %s\n".format(
                meanScale, scalePercentiles.joinToString(" / ") { "%.5f".format(it) }
            ))
            append(if (nonFinite == 0) "  non-finite  none" else "  non-finite  $nonFinite SPLATS")
        }
    }

    fun stats(): CloudStats {
        val min = Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var sumSaturation = 0.0
        var sumAlpha = 0.0
        var sumScale = 0.0
        var nonFinite = 0
        val alphaSamples = FloatArray(count)
        val scaleSamples = FloatArray(count)
        var good = 0

        for (splat in 0 until count) {
            val x = positions[splat * 3]
            val y = positions[splat * 3 + 1]
            val z = positions[splat * 3 + 2]
            val alpha = alphas[splat]
            val sx = scales[splat * 3]
            val sy = scales[splat * 3 + 1]
            val sz = scales[splat * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !alpha.isFinite() ||
                !sx.isFinite() || !sy.isFinite() || !sz.isFinite()
            ) {
                nonFinite++
                continue
            }
            min.set(min(min.x, x), min(min.y, y), min(min.z, z))
            max.set(max(max.x, x), max(max.y, y), max(max.z, z))
            val r = colors[splat * 3]
            val g = colors[splat * 3 + 1]
            val b = colors[splat * 3 + 2]
            sumR += r; sumG += g; sumB += b
            sumSaturation += (max(r, max(g, b)) - min(r, min(g, b))).toDouble()
            sumAlpha += alpha
            val scale = Math.cbrt((sx * sy * sz).toDouble()).toFloat()
            sumScale += scale
            alphaSamples[good] = alpha
            scaleSamples[good] = scale
            good++
        }
        if (good == 0) {
            return CloudStats(
                count, Vector3f(), Vector3f(), Vector3f(), 0f, 0f,
                FloatArray(CloudStats.PERCENTILES.size), 0f,
                FloatArray(CloudStats.PERCENTILES.size), nonFinite
            )
        }
        java.util.Arrays.sort(alphaSamples, 0, good)
        java.util.Arrays.sort(scaleSamples, 0, good)
        fun percentiles(sorted: FloatArray) = FloatArray(CloudStats.PERCENTILES.size) { index ->
            val at = ((CloudStats.PERCENTILES[index] / 100f) * (good - 1)).toInt().coerceIn(0, good - 1)
            sorted[at]
        }
        return CloudStats(
            count = count,
            boundsMin = min,
            boundsMax = max,
            meanColor = Vector3f(
                (sumR / good).toFloat(), (sumG / good).toFloat(), (sumB / good).toFloat()
            ),
            meanSaturation = (sumSaturation / good).toFloat(),
            meanAlpha = (sumAlpha / good).toFloat(),
            alphaPercentiles = percentiles(alphaSamples),
            meanScale = (sumScale / good).toFloat(),
            scalePercentiles = percentiles(scaleSamples),
            nonFinite = nonFinite
        )
    }

    // ------------------------------------------------------------------------
    // Edit
    // ------------------------------------------------------------------------

    /** A new cloud holding only the splats [keep] accepts, in the same order. */
    fun where(keep: (Int) -> Boolean): SplatCloud {
        val chosen = IntArray(count)
        var kept = 0
        for (splat in 0 until count) if (keep(splat)) chosen[kept++] = splat
        return gather(chosen, kept)
    }

    /** Splats whose centres lie inside the axis-aligned box [min]..[max], inclusive. */
    fun cropped(min: Vector3f, max: Vector3f): SplatCloud = where { splat ->
        val x = positions[splat * 3]
        val y = positions[splat * 3 + 1]
        val z = positions[splat * 3 + 2]
        x >= min.x && x <= max.x && y >= min.y && y <= max.y && z >= min.z && z <= max.z
    }

    /**
     * Splats within the given opacity and world-scale windows.
     *
     * The default [maxScale] of infinity and [minAlpha] of zero mean "no filter", so a caller can set
     * one bound and leave the rest alone. Non-finite splats are dropped by any filter at all — that is
     * the one place this is not purely a predicate, and it is deliberate: a filter is the natural
     * place to get rid of them, and keeping them would make every downstream count a lie.
     */
    fun filtered(
        minAlpha: Float = 0f,
        maxAlpha: Float = 1f,
        minScale: Float = 0f,
        maxScale: Float = Float.POSITIVE_INFINITY
    ): SplatCloud = where { splat ->
        val alpha = alphas[splat]
        val sx = scales[splat * 3]
        val sy = scales[splat * 3 + 1]
        val sz = scales[splat * 3 + 2]
        val largest = max(sx, max(sy, sz))
        alpha.isFinite() && sx.isFinite() && sy.isFinite() && sz.isFinite() &&
            positions[splat * 3].isFinite() && positions[splat * 3 + 1].isFinite() &&
            positions[splat * 3 + 2].isFinite() &&
            alpha >= minAlpha && alpha <= maxAlpha && largest >= minScale && largest <= maxScale
    }

    /**
     * A [fraction] of the splats, taken by a deterministic stride rather than at random.
     *
     * A stride over a Morton-ordered file — which is what a `.compressed.ply` is — thins the cloud
     * roughly uniformly in space, because neighbours in Morton order are neighbours in space. Random
     * sampling would give the same expected density with more variance and no reproducibility, and
     * reproducibility is what makes a decimated preview comparable between runs.
     */
    fun decimated(fraction: Float): SplatCloud {
        require(fraction > 0f && fraction <= 1f) { "fraction must be in (0, 1], got $fraction" }
        if (fraction == 1f) return this
        val chosen = IntArray(count)
        var kept = 0
        var accumulator = 0f
        for (splat in 0 until count) {
            accumulator += fraction
            if (accumulator >= 1f) { accumulator -= 1f; chosen[kept++] = splat }
        }
        return gather(chosen, kept)
    }

    /**
     * Every splat through [transform] — position by the full affine map, rotation and scale by the
     * linear part.
     *
     * The linear part is decomposed back into a rotation and three scales, because that is what a
     * `.compressed.ply` can store. A rigid transform, or a rigid one with uniform scale, survives
     * this exactly. **Shear does not**: the file format has no way to hold it, so a sheared covariance
     * is approximated by the nearest scale-and-rotate and [shearLoss] on the result says how much was
     * given up. If you need shear, bake it on the GPU side instead —
     * `GaussianSplatRenderer.SplatSource.transform` carries a general linear map through the
     * covariance congruence exactly and never has to write it down.
     */
    fun transformed(transform: Matrix4f): SplatCloud {
        val out = SplatCloud(
            positions = FloatArray(count * 3),
            colors = colors.copyOf(count * 3),
            alphas = alphas.copyOf(count),
            rotations = FloatArray(count * 4),
            scales = FloatArray(count * 3),
            count = count
        )
        var worstShear = 0f
        // Matrix4f is column-major, m<column><row>: L[row][col] is m<col><row>.
        val l = arrayOf(
            floatArrayOf(transform.m00, transform.m10, transform.m20),
            floatArrayOf(transform.m01, transform.m11, transform.m21),
            floatArrayOf(transform.m02, transform.m12, transform.m22)
        )
        for (splat in 0 until count) {
            val x = positions[splat * 3]
            val y = positions[splat * 3 + 1]
            val z = positions[splat * 3 + 2]
            out.positions[splat * 3] = l[0][0] * x + l[0][1] * y + l[0][2] * z + transform.m30
            out.positions[splat * 3 + 1] = l[1][0] * x + l[1][1] * y + l[1][2] * z + transform.m31
            out.positions[splat * 3 + 2] = l[2][0] * x + l[2][1] * y + l[2][2] * z + transform.m32

            // A = L * R, then A back to a rotation and three scales by Gram-Schmidt.
            val r = quaternionToMatrix(
                rotations[splat * 4], rotations[splat * 4 + 1],
                rotations[splat * 4 + 2], rotations[splat * 4 + 3]
            )
            val a = Array(3) { row ->
                FloatArray(3) { column ->
                    l[row][0] * r[0][column] + l[row][1] * r[1][column] + l[row][2] * r[2][column]
                }
            }
            // Columns of A are the splat's principal axes, scaled. Orthogonalise them.
            val axes = Array(3) { column -> floatArrayOf(a[0][column], a[1][column], a[2][column]) }
            val lengths = FloatArray(3)
            for (column in 0..2) {
                for (previous in 0 until column) {
                    val dot = axes[column][0] * axes[previous][0] +
                        axes[column][1] * axes[previous][1] + axes[column][2] * axes[previous][2]
                    worstShear = max(worstShear, abs(dot))
                    for (row in 0..2) axes[column][row] -= dot * axes[previous][row]
                }
                val length = sqrt(
                    axes[column][0] * axes[column][0] + axes[column][1] * axes[column][1] +
                        axes[column][2] * axes[column][2]
                )
                lengths[column] = length
                if (length > 1e-20f) for (row in 0..2) axes[column][row] /= length
                else { axes[column][0] = if (column == 0) 1f else 0f
                    axes[column][1] = if (column == 1) 1f else 0f
                    axes[column][2] = if (column == 2) 1f else 0f }
            }
            for (axis in 0..2) out.scales[splat * 3 + axis] = lengths[axis] * scales[splat * 3 + axis]
            val quaternion = matrixToQuaternion(axes)
            for (component in 0..3) out.rotations[splat * 4 + component] = quaternion[component]
        }
        out.shearLoss = worstShear
        return out
    }

    /**
     * How much shear [transformed] had to throw away: the largest absolute dot product it saw between
     * two axes that should have been orthogonal, before it orthogonalised them. Zero after any
     * transform the format can represent exactly.
     */
    var shearLoss: Float = 0f
        private set

    /** This cloud with every opacity multiplied by [factor] and clamped to 0..1. */
    fun withAlphaScaled(factor: Float): SplatCloud = SplatCloud(
        positions = positions.copyOf(count * 3),
        colors = colors.copyOf(count * 3),
        alphas = FloatArray(count) { (alphas[it] * factor).coerceIn(0f, 1f) },
        rotations = rotations.copyOf(count * 4),
        scales = scales.copyOf(count * 3),
        count = count
    )

    /** This cloud with every world scale multiplied by [factor]. */
    fun withScale(factor: Float): SplatCloud = SplatCloud(
        positions = positions.copyOf(count * 3),
        colors = colors.copyOf(count * 3),
        alphas = alphas.copyOf(count),
        rotations = rotations.copyOf(count * 4),
        scales = FloatArray(count * 3) { scales[it] * factor },
        count = count
    )

    private fun gather(chosen: IntArray, kept: Int): SplatCloud {
        val out = SplatCloud(
            FloatArray(kept * 3), FloatArray(kept * 3), FloatArray(kept),
            FloatArray(kept * 4), FloatArray(kept * 3), kept
        )
        for (slot in 0 until kept) {
            val from = chosen[slot]
            for (axis in 0..2) {
                out.positions[slot * 3 + axis] = positions[from * 3 + axis]
                out.colors[slot * 3 + axis] = colors[from * 3 + axis]
                out.scales[slot * 3 + axis] = scales[from * 3 + axis]
            }
            out.alphas[slot] = alphas[from]
            for (component in 0..3) out.rotations[slot * 4 + component] = rotations[from * 4 + component]
        }
        return out
    }

    // ------------------------------------------------------------------------
    // Back to something renderable, and to disk
    // ------------------------------------------------------------------------

    /**
     * Re-quantize into [CompressedSplatPly.RawSplats], so an edited cloud can go straight into
     * `GaussianSplatRenderer.packInto` — and therefore into [SplatRaster] and into a live GPU
     * `Cloud.reupload` — without a file in between.
     *
     * This is lossy in exactly the way the file format is: 11/10/11 bits of position per chunk, 10
     * bits per rotation component, 8 bits of colour and opacity. [CompressedSplatPlyWriter.quantize]
     * does the work and is the same code path [write] uses, so what you preview is what you save.
     */
    fun toRawSplats(): CompressedSplatPly.RawSplats = CompressedSplatPlyWriter.quantize(this)

    /** Write a valid PlayCanvas `.compressed.ply`. See [CompressedSplatPlyWriter]. */
    fun write(file: File, mortonSort: Boolean = true) =
        CompressedSplatPlyWriter.write(file, this, mortonSort)

    companion object {
        /**
         * Convert a decoded splat table — the one [readCompressedSplatPly] and
         * [readPlyAsFloatTable] produce — into editable conventions.
         *
         * This changes *spelling*, not values: `f_dc · SH_C0 + 0.5` for colour, a sigmoid for
         * opacity, `exp` for the scales. It deliberately does not re-read or re-decode anything, so
         * there is still exactly one bit-level decoder in the tree.
         */
        fun of(table: PlyFloatTable): SplatCloud {
            table.requireColumns(
                "x", "y", "z", "f_dc_0", "f_dc_1", "f_dc_2", "opacity",
                "rot_0", "rot_1", "rot_2", "rot_3", "scale_0", "scale_1", "scale_2"
            )
            val count = table.rowCount
            val cloud = SplatCloud(
                FloatArray(count * 3), FloatArray(count * 3), FloatArray(count),
                FloatArray(count * 4), FloatArray(count * 3), count
            )
            for (splat in 0 until count) {
                cloud.positions[splat * 3] = table.floatAt(splat, "x")
                cloud.positions[splat * 3 + 1] = table.floatAt(splat, "y")
                cloud.positions[splat * 3 + 2] = table.floatAt(splat, "z")
                for (channel in 0..2) {
                    cloud.colors[splat * 3 + channel] =
                        table.floatAt(splat, "f_dc_$channel") * CompressedSplatPly.SH_C0 + 0.5f
                }
                cloud.alphas[splat] = sigmoid(table.floatAt(splat, "opacity"))
                // Normalise on the way in: the file's quaternion is unit to ten bits per component,
                // and every consumer below assumes exactly unit.
                var w = table.floatAt(splat, "rot_0")
                var x = table.floatAt(splat, "rot_1")
                var y = table.floatAt(splat, "rot_2")
                var z = table.floatAt(splat, "rot_3")
                val length = sqrt(w * w + x * x + y * y + z * z)
                if (length > 1e-20f) { w /= length; x /= length; y /= length; z /= length }
                else { w = 1f; x = 0f; y = 0f; z = 0f }
                cloud.rotations[splat * 4] = w
                cloud.rotations[splat * 4 + 1] = x
                cloud.rotations[splat * 4 + 2] = y
                cloud.rotations[splat * 4 + 3] = z
                for (axis in 0..2) {
                    cloud.scales[splat * 3 + axis] =
                        exp(table.floatAt(splat, "scale_$axis").toDouble()).toFloat()
                }
            }
            return cloud
        }

        /** Read a `.compressed.ply` (or any splat PLY) into editable conventions. */
        fun read(path: String): SplatCloud = of(readPlyAsFloatTable(path))

        /** Read a `.compressed.ply` specifically, failing loudly if it is not one. */
        fun readCompressed(path: String): SplatCloud = of(readCompressedSplatPly(path))

        /**
         * Several clouds as one, concatenated in order.
         *
         * Concatenated rather than interleaved by depth: the order a cloud is written in is the order
         * a `SortMode.None` draw composites in, and re-sorting is `packInto`'s job, not this one's.
         */
        fun merge(clouds: List<SplatCloud>): SplatCloud {
            require(clouds.isNotEmpty()) { "merge needs at least one cloud" }
            if (clouds.size == 1) return clouds.single()
            val total = clouds.sumOf { it.count }
            val out = SplatCloud(
                FloatArray(total * 3), FloatArray(total * 3), FloatArray(total),
                FloatArray(total * 4), FloatArray(total * 3), total
            )
            var at = 0
            for (cloud in clouds) {
                System.arraycopy(cloud.positions, 0, out.positions, at * 3, cloud.count * 3)
                System.arraycopy(cloud.colors, 0, out.colors, at * 3, cloud.count * 3)
                System.arraycopy(cloud.alphas, 0, out.alphas, at, cloud.count)
                System.arraycopy(cloud.rotations, 0, out.rotations, at * 4, cloud.count * 4)
                System.arraycopy(cloud.scales, 0, out.scales, at * 3, cloud.count * 3)
                at += cloud.count
            }
            return out
        }

        internal fun sigmoid(value: Float): Float =
            (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

        /** A unit quaternion's rotation matrix, `[row][column]`, as `emitSplat` spells it. */
        internal fun quaternionToMatrix(w: Float, x: Float, y: Float, z: Float): Array<FloatArray> {
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            return arrayOf(
                floatArrayOf(1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy)),
                floatArrayOf(2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx)),
                floatArrayOf(2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy))
            )
        }

        /**
         * A rotation matrix's `w, x, y, z` quaternion, via the largest-diagonal branch so the square
         * root never divides by something near zero. [axes] is `[column][row]` — the three
         * orthonormal axes as [transformed] builds them, which is the transpose of
         * [quaternionToMatrix]'s convention.
         */
        internal fun matrixToQuaternion(axes: Array<FloatArray>): FloatArray {
            val m00 = axes[0][0]; val m01 = axes[1][0]; val m02 = axes[2][0]
            val m10 = axes[0][1]; val m11 = axes[1][1]; val m12 = axes[2][1]
            val m20 = axes[0][2]; val m21 = axes[1][2]; val m22 = axes[2][2]
            val trace = m00 + m11 + m22
            val q: FloatArray
            if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                q = floatArrayOf(0.25f * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1f + m00 - m11 - m22) * 2f
                q = floatArrayOf((m21 - m12) / s, 0.25f * s, (m01 + m10) / s, (m02 + m20) / s)
            } else if (m11 > m22) {
                val s = sqrt(1f + m11 - m00 - m22) * 2f
                q = floatArrayOf((m02 - m20) / s, (m01 + m10) / s, 0.25f * s, (m12 + m21) / s)
            } else {
                val s = sqrt(1f + m22 - m00 - m11) * 2f
                q = floatArrayOf((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25f * s)
            }
            val length = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
            return if (length > 1e-20f) FloatArray(4) { q[it] / length } else floatArrayOf(1f, 0f, 0f, 0f)
        }

        internal fun logOf(scale: Float): Float =
            if (scale > 0f) ln(scale.toDouble()).toFloat() else -20f
    }
}

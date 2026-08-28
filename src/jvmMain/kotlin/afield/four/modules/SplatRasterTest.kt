package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import afield.stdlib.linalg.Vector3f
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/*
FIELD_TEST
id: four.splat-raster.smoke
runner: jvm-main
entrypoint: afield.four.modules.SplatRasterTestKt
tags: [smoke, four, ply, splat, raster, image]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "splat raster tests PASSED"
*/
/**
 * Holds [SplatRaster] to the pixels.
 *
 * `SplatFusedPackTest` checks that the two pack paths produce the same *floats*, and every assertion
 * in it would still hold if the shader drew the cloud mirrored, upside down, or with red and blue
 * swapped. This is the test that cannot. It asserts:
 *
 * - **Orientation**, from four corner-marked splats — the only proof of the Y and X conventions that
 *   does not reduce to re-deriving the same viewport call the rasterizer was written from.
 * - **A known splat at a known pixel in a known colour**, with the pixel derived on paper from the
 *   perspective matrix and written here as a literal, so a change in the projection has to be
 *   noticed rather than absorbed.
 * - **Slot sensitivity** — the differential oracle. Each of the sixteen floats in
 *   [GaussianSplatRenderer]'s layout is perturbed in turn, and the image is required to move for the
 *   thirteen the shader reads and to *not* move for the three it does not. This is what makes "the
 *   CPU and the GPU are looking at the same cloud" a tested claim rather than a hopeful comment.
 * - **Pixel agreement between the two pack paths**, which is `SplatFusedPackTest`'s float claim
 *   carried through to what a person actually sees.
 * - **That the poison detector detects poison.** A cloud is deliberately broken with NaNs and the
 *   non-finite count is required to find exactly them — and required to be zero on good data, so the
 *   detector cannot be passing by always firing.
 */
object SplatRasterTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "splat_raster_test")

    /**
     * Where to also drop the PNGs for a human to look at. Unset in CI: a test writes to a temp
     * directory and inspects, and never into the checked-out tree.
     */
    private val lookAtDir: File? = System.getenv("AFIELD_SPLAT_RASTER_PNG_DIR")?.let { File(it) }

    private const val STRIDE = GaussianSplatRenderer.SPLAT_FLOAT_STRIDE

    /** Opacity logit that quantizes to the 255 byte, hence to an alpha of exactly 1. */
    private const val OPAQUE = 30f

    /**
     * Percentage of pixels that must be bit-identical between the two pack paths.
     *
     * Set by what the *negative* control tolerates, not by what feels safe:
     * [checkPackPathAgreement] moves one splat and requires this floor to reject the result, so the
     * floor can only be as loose as a genuine single-splat defect is detectable.
     */
    private const val AGREEMENT_FLOOR = 99.99

    /** Largest per-channel difference allowed between the two pack paths. Measured: 0. */
    private const val WORST_DELTA_CEILING = 1

    fun runTests(): Boolean {
        var passed = true
        testDir.mkdirs()
        lookAtDir?.mkdirs()
        println("CPU splat raster tests")
        println("=".repeat(72))

        passed = checkOrientation() && passed
        passed = checkKnownPixel() && passed
        passed = checkSlotSensitivity() && passed
        passed = checkPackPathAgreement() && passed
        passed = checkPoisonDetection() && passed
        passed = checkSortOrder() && passed
        passed = checkStatsAccounting() && passed

        println("=".repeat(72))
        println(if (passed) "splat raster tests PASSED" else "splat raster tests FAILED")
        return passed
    }

    // ------------------------------------------------------------------------
    // 1. Orientation
    // ------------------------------------------------------------------------

    /**
     * Four splats at the corners of a square in the `z = 0` plane, one primary colour each, seen from
     * `+z` looking back at the origin with `up = +y`.
     *
     * With that camera Field's `lookAtField` puts world `+x` on the viewer's right (`s = f × up` with
     * `f = -z` gives `s = +x`), and `Renderer.setViewportAndScissor`'s negative-height viewport puts
     * NDC `+y` at the top. So world `(-1, +1)` must land top-left. Nothing in this assertion is
     * derived from [SplatRaster]; it is derived from the two places in the engine that decide it.
     */
    private fun checkOrientation(): Boolean {
        print("Orientation: four corner-marked splats... ")
        return guard {
            val size = 192
            val marks = listOf(
                Triple(-1f, 1f, "top-left") to floatArrayOf(1f, 0f, 0f),      // red
                Triple(1f, 1f, "top-right") to floatArrayOf(0f, 1f, 0f),      // green
                Triple(-1f, -1f, "bottom-left") to floatArrayOf(0f, 0f, 1f),  // blue
                Triple(1f, -1f, "bottom-right") to floatArrayOf(1f, 1f, 0f)   // yellow
            )
            val splats = marks.map { (where, color) ->
                splat(where.first, where.second, 0f, color, scale = 0.22f)
            }
            val file = File(testDir, "orientation.compressed.ply")
            CompressedSplatPlyFixture.write(file, splats)

            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0f, 0f, 4f),
                target = Vector3f(0f, 0f, 0f),
                up = Vector3f(0f, 1f, 0f),
                width = size, height = size
            )
            val result = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(CompressedSplatPly.readRaw(file.absolutePath))),
                view
            )
            check(result.stats.drawn == 4) { "expected all four marks drawn, got ${result.stats}" }
            dump("orientation", result.image)

            val argb = result.image.toArgb()
            // The dominant hue of each quadrant, sampled well inside it.
            fun quadrant(left: Boolean, top: Boolean): String {
                val x = if (left) size / 4 else 3 * size / 4
                val y = if (top) size / 4 else 3 * size / 4
                var best = 0; var bestLum = -1
                for (dy in -12..12) for (dx in -12..12) {
                    val p = argb[(x + dx) + (y + dy) * size]
                    val lum = ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)
                    if (lum > bestLum) { bestLum = lum; best = p }
                }
                val r = (best shr 16) and 255
                val g = (best shr 8) and 255
                val b = best and 255
                check(bestLum > 60) {
                    "quadrant left=$left top=$top is empty (brightest sum $bestLum) — a mark is missing"
                }
                return when {
                    r > 96 && g > 96 && b < 64 -> "yellow"
                    r > 96 && g < 64 && b < 64 -> "red"
                    g > 96 && r < 64 && b < 64 -> "green"
                    b > 96 && r < 64 && g < 64 -> "blue"
                    else -> "muddled($r,$g,$b)"
                }
            }

            val topLeft = quadrant(left = true, top = true)
            val topRight = quadrant(left = false, top = true)
            val bottomLeft = quadrant(left = true, top = false)
            val bottomRight = quadrant(left = false, top = false)
            check(topLeft == "red") { "top-left should be red (world -x +y), got $topLeft" }
            check(topRight == "green") { "top-right should be green (world +x +y), got $topRight" }
            check(bottomLeft == "blue") { "bottom-left should be blue (world -x -y), got $bottomLeft" }
            check(bottomRight == "yellow") { "bottom-right should be yellow (world +x -y), got $bottomRight" }
            "TL=$topLeft TR=$topRight BL=$bottomLeft BR=$bottomRight"
        }
    }

    // ------------------------------------------------------------------------
    // 2. A known splat at a known pixel
    // ------------------------------------------------------------------------

    /**
     * One splat at world `(0.5, 0.25, 0)`, camera at `(0, 0, 4)`, 128 x 128, 50° vertical field of
     * view. The whole projection is on paper:
     *
     *     h        = 1 / tan(25°)                      = 2.144507
     *     view     = (0.5, 0.25, -4), depth 4
     *     ndc      = (h·0.5/4, h·0.25/4)               = (0.268063, 0.134032)
     *     centreX  = (ndc.x·0.5 + 0.5) · 128           = 81.156
     *     centreY  = (ndc.y·0.5 + 0.5) · 128           = 72.578
     *     column   = centreX - 0.5                     = 80.656
     *     row      = 128 - 0.5 - centreY               = 54.922
     *
     * so the splat's energy must be centred on column 80.656, row 54.922 — above and right of the
     * middle, which is the orientation claim again from a second direction. The literals are here on
     * purpose: a projection change should break this test rather than be silently tracked by a
     * recomputed expectation.
     *
     * The assertion is on the intensity-weighted **centroid**, to a quarter of a pixel, rather than
     * on which pixel is brightest. An opaque splat saturates its core, so "brightest pixel" is a
     * plateau and picking a member of it is an argmax tie-break, not a measurement — the first
     * version of this test asserted the nearest pixel and failed by one in each axis for exactly
     * that reason. The centroid is both unambiguous and a tighter claim.
     */
    private fun checkKnownPixel(): Boolean {
        print("A known splat at a known pixel and colour... ")
        return guard {
            val size = 128
            val color = floatArrayOf(1f, 0.25f, 0.5f)
            val file = File(testDir, "known.compressed.ply")
            // A single splat means a chunk whose min equals its max, so the colour and position
            // quantize exactly and the expected values below are not approximations.
            CompressedSplatPlyFixture.write(file, listOf(splat(0.5f, 0.25f, 0f, color, scale = 0.4f)))

            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0f, 0f, 4f), target = Vector3f(0f, 0f, 0f),
                width = size, height = size
            )
            val result = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(CompressedSplatPly.readRaw(file.absolutePath))),
                view
            )
            check(result.stats.drawn == 1) { "expected one splat drawn, got ${result.stats}" }
            dump("known_pixel", result.image)
            val argb = result.image.toArgb()

            // Centroid of the coverage, weighted by the un-quantized linear alpha.
            var weight = 0.0
            var colSum = 0.0
            var rowSum = 0.0
            for (row in 0 until size) for (col in 0 until size) {
                val alpha = result.image.linear[(col + row * size) * 4 + 3].toDouble()
                weight += alpha
                colSum += alpha * col
                rowSum += alpha * row
            }
            check(weight > 0.0) { "the splat left no coverage at all" }
            val col = colSum / weight
            val row = rowSum / weight
            check(abs(col - 80.656) < 0.25 && abs(row - 54.922) < 0.25) {
                "energy centred on column %.3f row %.3f; the projection says column 80.656 row 54.922"
                    .format(col, row)
            }
            // The argmax is still worth a look, because a peak far from the centroid would mean the
            // Gaussian is lopsided even though its centre of mass is right.
            var brightestAt = -1
            var brightest = -1
            for (pixel in argb.indices) {
                val p = argb[pixel]
                val lum = ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)
                if (lum > brightest) { brightest = lum; brightestAt = pixel }
            }
            check(abs(brightestAt % size - col) < 2.0 && abs(brightestAt / size - row) < 2.0) {
                "the brightest pixel (${brightestAt % size}, ${brightestAt / size}) is more than two " +
                    "pixels from the centroid (%.2f, %.2f), so the Gaussian is lopsided".format(col, row)
            }

            // The colour pipeline end to end: the file's linear 0..1 colour, decoded by colorGamma
            // 2.2 into the linear space the GPU blends in, composited premultiplied over black, and
            // sRGB-encoded on the way out exactly as a _SRGB framebuffer would encode it.
            val expected = IntArray(3) { channel ->
                (SplatRaster.linearToSrgb(color[channel].pow(2.2f)) * 255f + 0.5f).toInt()
            }
            val peak = argb[brightestAt]
            val got = intArrayOf((peak shr 16) and 255, (peak shr 8) and 255, peak and 255)
            check(expected[0] == 255) { "a colour of 1.0 must saturate, expected 255 got ${expected[0]}" }
            for (channel in 0..2) {
                // 2 code values of slack for the sub-pixel offset between the Gaussian's peak and the
                // centre of the pixel that samples it; the channels are 255/61/128 apart, so this
                // cannot absorb a channel swap or a missing gamma.
                check(abs(got[channel] - expected[channel]) <= 2) {
                    "channel $channel: expected ${expected[channel]} +/-2, got ${got[channel]} " +
                        "(all channels expected ${expected.joinToString()}, got ${got.joinToString()})"
                }
            }
            // Pin the digits, not just the agreement with a formula that could itself be wrong.
            check(expected.toList() == listOf(255, 61, 128)) {
                "the gamma pipeline moved: (1.0, 0.25, 0.5) should encode to (255, 61, 128), " +
                    "got ${expected.joinToString()}"
            }
            "centroid column %.3f row %.3f (expected 80.656, 54.922), sRGB ".format(col, row) +
                got.joinToString()
        }
    }

    // ------------------------------------------------------------------------
    // 3. The differential oracle: slot sensitivity
    // ------------------------------------------------------------------------

    /**
     * The claim this test exists for.
     *
     * [SplatRaster] consumes [GaussianSplatRenderer]'s 16-float layout. It is therefore possible for
     * it to compile, run, and produce a beautiful picture while reading slot 10 as if it were slot 12
     * — the covariance is symmetric enough that a swapped off-diagonal produces a *plausible* cloud.
     * A float-level comparison against `packInto` cannot see that at all, because the floats are
     * whatever they are; the question is what the consumer does with them.
     *
     * So: perturb one slot at a time and require the rendered image to change for the thirteen the
     * shader reads and to be **byte-identical** for the three it does not
     * ([GaussianSplatRenderer.SPLAT_SOURCE_FLOAT] and the two padding floats). Thirteen must move,
     * three must not. Both halves matter — "everything changes the image" would also pass if the
     * rasterizer hashed the whole buffer into noise.
     */
    private fun checkSlotSensitivity(): Boolean {
        print("Slot sensitivity: 13 floats must matter, 3 must not... ")
        return guard {
            val size = 96
            val file = File(testDir, "sensitivity.compressed.ply")
            CompressedSplatPlyFixture.write(
                file,
                (0 until 40).map { index ->
                    val t = index / 40f
                    splat(
                        (t - 0.5f) * 1.4f, ((index % 7) / 7f - 0.5f) * 1.2f, (index % 5) * 0.12f,
                        floatArrayOf(0.9f - t * 0.5f, 0.3f + t * 0.5f, 0.6f), scale = 0.15f
                    )
                }
            )
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0f, 0f, 3.2f), target = Vector3f(0f, 0f, 0f),
                width = size, height = size
            )

            val baselineBuffer = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, baselineBuffer)
            val baseline = SplatRaster.rasterize(baselineBuffer, raw.count, view).image.toArgb()
            check(baseline.any { (it and 0xffffff) != 0 }) { "the baseline image is blank" }

            // Which splat to poke. One in the middle of the cloud, so it is neither clipped nor
            // hidden: slot 3 (alpha) has to be able to make a visible difference.
            val target = raw.count / 2
            val inert = setOf(GaussianSplatRenderer.SPLAT_SOURCE_FLOAT, 14, 15)
            val moved = mutableListOf<Int>()
            val still = mutableListOf<Int>()

            for (slot in 0 until STRIDE) {
                val probe = SplatRaster.allocatePacked(raw.count)
                GaussianSplatRenderer.packInto(raw, probe)
                val at = target * STRIDE + slot
                val was = probe.get(at)
                // A perturbation big enough to be unmistakable but not so big it moves the splat out
                // of frame, where "the image changed" would be true for the wrong reason.
                probe.put(at, if (slot == 3) 0.05f else was + 0.35f * max(1f, abs(was)))
                val after = SplatRaster.rasterize(probe, raw.count, view).image.toArgb()
                var differing = 0
                for (pixel in baseline.indices) if (baseline[pixel] != after[pixel]) differing++
                if (differing > 0) moved += slot else still += slot
            }

            check(still.toSet() == inert) {
                "slots the rasterizer ignores should be exactly $inert " +
                    "(the source index and the two padding floats), but they are $still; " +
                    "moved = $moved"
            }
            check(moved.size == 13) { "expected 13 load-bearing slots, got ${moved.size}: $moved" }
            "13 load-bearing (${moved.joinToString()}), 3 inert (${still.joinToString()})"
        }
    }

    // ------------------------------------------------------------------------
    // 4. The two pack paths, in pixels
    // ------------------------------------------------------------------------

    /**
     * `SplatFusedPackTest` pins `pack` against `packInto` to ~3e-5 relative, field by field. This
     * carries that to the image: rasterize both and require the pictures to be the same.
     *
     * The floor is pinned at the digits below, not at a token percentage. The two paths differ only
     * by a sigmoid/logit and an `f_dc` round trip, both of which are far below a code value, so
     * "almost every pixel identical" is the *expected* outcome and anything less is a finding.
     */
    private fun checkPackPathAgreement(): Boolean {
        print("pack vs packInto, in pixels... ")
        return guard {
            val size = 160
            val file = File(testDir, "agreement.compressed.ply")
            CompressedSplatPlyFixture.write(file, syntheticCloud(1200))

            val table = readCompressedSplatPly(file.absolutePath)
            val reference = GaussianSplatRenderer.pack(table)
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val fused = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, fused)

            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0.6f, 0.4f, 3.4f), target = Vector3f(0f, 0f, 0f),
                width = size, height = size
            )
            val fromTable = SplatRaster.rasterize(
                FloatBuffer.wrap(reference.floats), reference.count, view
            )
            val fromFused = SplatRaster.rasterize(fused, raw.count, view)
            check(fromTable.stats.drawn == fromFused.stats.drawn) {
                "the two paths drew different numbers of splats: " +
                    "${fromTable.stats.drawn} from pack, ${fromFused.stats.drawn} from packInto"
            }
            dump("agreement_fused", fromFused.image)

            val a = fromTable.image.toArgb()
            val b = fromFused.image.toArgb()
            var identical = 0
            var worst = 0
            for (pixel in a.indices) {
                if (a[pixel] == b[pixel]) { identical++; continue }
                for (shift in intArrayOf(16, 8, 0)) {
                    val delta = abs(((a[pixel] shr shift) and 255) - ((b[pixel] shr shift) and 255))
                    if (delta > worst) worst = delta
                }
            }
            val agreement = identical * 100.0 / a.size
            // These two floors are the point of the test, and they sit next to the measured figure
            // rather than a comfortable distance below it: this comparison actually returns 100.000%
            // identical with a worst channel delta of 0, because the two paths' ~3e-5 relative
            // disagreement is an order of magnitude under an 8-bit code value.
            //
            // The first version of this floor was 99.9%, and the negative control below rejected it:
            // moving a single splat of 1200 by 0.01 leaves 99.938% of pixels identical with a worst
            // delta of 1, which 99.9% and "delta <= 1" both admit. A floor that a real perturbation
            // passes is not a floor. AGREEMENT_FLOOR is 99.99% — about two pixels of this image, so
            // still room for a differently-rounding FMA on another platform, and nowhere near room
            // for one splat in the wrong place.
            check(agreement >= AGREEMENT_FLOOR) {
                "only %.3f%% of pixels identical between the pack paths, expected >= %s%%"
                    .format(agreement, AGREEMENT_FLOOR)
            }
            check(worst <= WORST_DELTA_CEILING) {
                "a channel differs by $worst code values between the pack paths, " +
                    "expected <= $WORST_DELTA_CEILING"
            }
            check(fromFused.stats.drawn > 500) {
                "only ${fromFused.stats.drawn} splats drawn of ${raw.count}; the view is nearly empty, " +
                    "so this comparison would agree vacuously"
            }
            // The other half of the claim: the comparison can tell when the clouds DIFFER. Move one
            // splat of 1200 by a hundredth of a unit and require both floors to reject it.
            //
            // Without this, "100.000% identical" is unfalsifiable — a comparison that always returns
            // 100% would produce exactly the same reassuring line. This is the cheapest possible
            // version of breaking your own oracle and watching it go red, and it runs every time
            // rather than once, in a terminal, by an agent who then wrote down that it had.
            val sabotaged = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, sabotaged)
            val victim = raw.count / 3
            sabotaged.put(victim * STRIDE, sabotaged.get(victim * STRIDE) + 0.01f)
            val damaged = SplatRaster.rasterize(sabotaged, raw.count, view).image.toArgb()
            var stillIdentical = 0
            var damagedWorst = 0
            for (pixel in a.indices) {
                if (b[pixel] == damaged[pixel]) { stillIdentical++; continue }
                for (shift in intArrayOf(16, 8, 0)) {
                    val delta = abs(((b[pixel] shr shift) and 255) - ((damaged[pixel] shr shift) and 255))
                    if (delta > damagedWorst) damagedWorst = delta
                }
            }
            val damagedAgreement = stillIdentical * 100.0 / a.size
            // Deliberately re-uses the *same* two constants the positive assertion above uses, so the
            // floors cannot be loosened without this control noticing.
            check(damagedAgreement < AGREEMENT_FLOOR || damagedWorst > WORST_DELTA_CEILING) {
                "moving one splat of ${raw.count} by 0.01 left the comparison reporting " +
                    "%.3f%% identical with a worst delta of $damagedWorst — both inside the floors this "
                        .format(damagedAgreement) +
                    "test passes on ($AGREEMENT_FLOOR%%, $WORST_DELTA_CEILING), so those floors " +
                    "would not catch a real regression either"
            }

            "%.3f%% of ${a.size} pixels identical, worst channel delta $worst; ".format(agreement) +
                "one splat moved by 0.01 drops that to %.3f%% at delta $damagedWorst; "
                    .format(damagedAgreement) +
                "${fromFused.stats.drawn} splats drawn, ${fromFused.image.stats()}"
        }
    }

    // ------------------------------------------------------------------------
    // 5. Does the poison detector detect poison?
    // ------------------------------------------------------------------------

    /**
     * A validity filter that has never seen an invalid input is not evidence of anything.
     *
     * [SplatRaster.RasterStats.nonFinite] exists because a NaN position slips through the near-plane
     * cull — `NaN <= 0.0001` is false — and then contributes nothing, so a cloud that is NaN
     * throughout would otherwise report a clean render of an empty scene. This breaks a cloud on
     * purpose, in each of the three places a NaN can enter (position, colour, covariance), and
     * requires the count to be *exactly* the number poisoned. And it requires the count to be zero on
     * the same cloud unpoisoned, so the detector cannot be passing by always firing.
     */
    private fun checkPoisonDetection(): Boolean {
        print("Poisoned clouds are counted, clean ones are not... ")
        return guard {
            val size = 96
            val file = File(testDir, "poison.compressed.ply")
            CompressedSplatPlyFixture.write(file, syntheticCloud(300))
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0f, 0f, 3.4f), target = Vector3f(0f, 0f, 0f),
                width = size, height = size
            )

            val clean = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, clean)
            val cleanStats = SplatRaster.rasterize(clean, raw.count, view).stats
            check(cleanStats.nonFinite == 0) {
                "a clean cloud reported ${cleanStats.nonFinite} non-finite splats; the detector fires " +
                    "on good data, so it proves nothing about bad data"
            }
            check(cleanStats.drawn > 100) { "clean cloud drew only ${cleanStats.drawn}; too empty to test" }

            // Position, colour and covariance in turn: three NaNs each, in disjoint splats.
            val poisonedSlots = listOf(0 to "position x", 5 to "colour green", 12 to "covariance yz")
            val report = StringBuilder()
            for ((slot, label) in poisonedSlots) {
                val probe = SplatRaster.allocatePacked(raw.count)
                GaussianSplatRenderer.packInto(raw, probe)
                val victims = listOf(3, 17, 41, 88, 199)
                for (victim in victims) probe.put(victim * STRIDE + slot, Float.NaN)
                val stats = SplatRaster.rasterize(probe, raw.count, view).stats
                check(stats.nonFinite == victims.size) {
                    "poisoning $label in ${victims.size} splats was counted as ${stats.nonFinite}"
                }
                stats.check()
                report.append("$label ok; ")
            }

            // An infinity is the other way this goes wrong, and it is worse: an infinite covariance
            // produces an infinite screen radius, which after the maxScreenRadius clamp looks
            // entirely reasonable. It must be caught as bad data, not clamped into plausibility.
            val infinite = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, infinite)
            infinite.put(7 * STRIDE + 8, Float.POSITIVE_INFINITY)
            val infiniteStats = SplatRaster.rasterize(infinite, raw.count, view).stats
            check(infiniteStats.nonFinite == 1) {
                "an infinite covariance was counted as ${infiniteStats.nonFinite} non-finite, expected 1"
            }
            "clean 0 non-finite over ${cleanStats.drawn} drawn; ${report}infinity ok"
        }
    }

    // ------------------------------------------------------------------------
    // 6. Does the pack sort actually produce a drawable order?
    // ------------------------------------------------------------------------

    /**
     * `packInto(sortAlong = …)` writes the frame farthest-first so that the streaming path can draw
     * it under [GaussianSplatRenderer.SortMode.None] with no GPU sort at all. Until now nothing could
     * check that: the order is only observable in the composite.
     *
     * Rasterizing the same cloud three ways makes it observable. A `sortAlong` pack drawn in buffer
     * order must match a depth-sorted render closely; the *unsorted* pack drawn in buffer order must
     * not. If the third comparison were also close, the first would be proving nothing.
     */
    private fun checkSortOrder(): Boolean {
        print("A sortAlong pack is drawable in buffer order... ")
        return guard {
            val size = 128
            val file = File(testDir, "sorted.compressed.ply")
            // Overlapping, semi-transparent and spread in depth, which is the only arrangement where
            // compositing order shows up at all.
            //
            // z oscillates with the index rather than ramping with it. The first version of this test
            // ramped z, and the fixture does not Morton-sort, so file order *was* depth order and the
            // "unsorted" control agreed with the sorted render to 0.000 — the test passed its first
            // assertion while its own control proved nothing. Hence the second assertion below.
            CompressedSplatPlyFixture.write(
                file,
                (0 until 700).map { index ->
                    val t = index / 700f
                    splat(
                        (kotlin.math.sin(index * 2.4) * 0.5).toFloat(),
                        (kotlin.math.cos(index * 1.7) * 0.5).toFloat(),
                        (kotlin.math.sin(index * 0.9) * 0.9).toFloat(),
                        floatArrayOf(t, 0.4f, 1f - t),
                        scale = 0.2f,
                        opacityLogit = -0.4f
                    )
                }
            )
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0f, 0f, 3f), target = Vector3f(0f, 0f, 0f),
                width = size, height = size
            )
            val bufferOrder = SplatRaster.RasterConfig(depthOrder = SplatRaster.DepthOrder.BufferOrder)

            // The camera looks down -z from +z, so the far splats are at -z: sort along -z... the
            // renderer wants farthest first, and packInto writes descending along sortAlong, so the
            // direction that puts far splats first is the camera's view direction.
            val sorted = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(
                raw, sorted, sortAlong = Vector3f(0f, 0f, -1f)
            )
            val unsorted = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, unsorted)

            val truth = SplatRaster.rasterize(unsorted, raw.count, view).image.toArgb()
            val packSorted = SplatRaster.rasterize(sorted, raw.count, view, bufferOrder).image.toArgb()
            val naive = SplatRaster.rasterize(unsorted, raw.count, view, bufferOrder).image.toArgb()

            fun meanAbsDelta(a: IntArray, b: IntArray): Double {
                var sum = 0L
                for (pixel in a.indices) for (shift in intArrayOf(16, 8, 0)) {
                    sum += abs(((a[pixel] shr shift) and 255) - ((b[pixel] shr shift) and 255))
                }
                return sum.toDouble() / (a.size * 3)
            }
            val sortedError = meanAbsDelta(truth, packSorted)
            val naiveError = meanAbsDelta(truth, naive)
            check(sortedError < 0.5) {
                "a sortAlong pack drawn in buffer order differs from a depth sort by " +
                    "%.3f code values per channel, expected < 0.5".format(sortedError)
            }
            check(naiveError > 4.0 * max(sortedError, 0.01)) {
                "the unsorted pack is only %.3f off against the sorted pack's %.3f, so this test is "
                    .format(naiveError, sortedError) +
                    "not distinguishing an order that works from one that does not"
            }
            "sortAlong %.4f vs unsorted %.4f code values per channel".format(sortedError, naiveError)
        }
    }

    // ------------------------------------------------------------------------
    // 7. Stats accounting
    // ------------------------------------------------------------------------

    /** Every splat lands in exactly one bucket, in each of the ways a splat can be dropped. */
    private fun checkStatsAccounting(): Boolean {
        print("Every splat accounted for exactly once... ")
        return guard {
            val file = File(testDir, "accounting.compressed.ply")
            CompressedSplatPlyFixture.write(file, syntheticCloud(400))
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val packed = SplatRaster.allocatePacked(raw.count)
            GaussianSplatRenderer.packInto(raw, packed)

            val notes = mutableListOf<String>()
            // In frame, entirely behind the camera, and 900 units away. The last one still draws
            // every splat, and that is correct rather than a cull: `minScreenRadius` floors a splat
            // at 0.75 px precisely so a distant cloud does not evaporate. Noted here because "very
            // far, so it must be culled" is the wrong intuition to leave lying around.
            for ((label, view) in listOf(
                "in frame" to SplatRaster.View.lookAt(
                    eye = Vector3f(0f, 0f, 3f), target = Vector3f(0f, 0f, 0f), width = 64, height = 64
                ),
                "behind" to SplatRaster.View.lookAt(
                    eye = Vector3f(0f, 0f, 3f), target = Vector3f(0f, 0f, 9f), width = 64, height = 64
                ),
                "900 units off, floored at minScreenRadius" to SplatRaster.View.lookAt(
                    eye = Vector3f(0f, 0f, 900f), target = Vector3f(0f, 0f, 0f), width = 64, height = 64
                )
            )) {
                val stats = SplatRaster.rasterize(packed, raw.count, view).stats
                stats.check()
                check(stats.considered == raw.count) { "$label considered ${stats.considered}" }
                notes += "$label ${stats.drawn} drawn"
            }
            val behind = SplatRaster.rasterize(
                packed, raw.count,
                SplatRaster.View.lookAt(
                    eye = Vector3f(0f, 0f, 3f), target = Vector3f(0f, 0f, 9f), width = 64, height = 64
                )
            ).stats
            check(behind.culledBehind == raw.count) {
                "looking away should put all ${raw.count} splats behind the camera, got ${behind.culledBehind}"
            }
            notes.joinToString(", ")
        }
    }

    // ------------------------------------------------------------------------
    // Fixtures and plumbing
    // ------------------------------------------------------------------------

    /**
     * One splat in the uncompressed 3DGS conventions the fixture wants: an isotropic Gaussian of
     * [scale] world units, identity rotation, and [color] as a linear 0..1 triple.
     */
    private fun splat(
        x: Float, y: Float, z: Float,
        color: FloatArray,
        scale: Float,
        opacityLogit: Float = OPAQUE
    ): CompressedSplatPlyFixture.Splat = CompressedSplatPlyFixture.Splat(
        x = x, y = y, z = z,
        // The fixture measures its chunk colour range over f_dc * SH_C0 + 0.5, so inverting here
        // means `color` arrives at the renderer as the linear value written.
        fDc = FloatArray(3) { (color[it] - 0.5f) / CompressedSplatPly.SH_C0 },
        opacity = opacityLogit,
        rot = floatArrayOf(1f, 0f, 0f, 0f),
        scale = FloatArray(3) { ln(scale.toDouble()).toFloat() }
    )

    /** A deterministic cloud spread over a unit-ish box, with varied colour, scale and opacity. */
    private fun syntheticCloud(count: Int): List<CompressedSplatPlyFixture.Splat> =
        (0 until count).map { index ->
            val t = index.toFloat() / count
            splat(
                x = (kotlin.math.sin(index * 1.31) * 0.7).toFloat(),
                y = (kotlin.math.cos(index * 0.77) * 0.7).toFloat(),
                z = (kotlin.math.sin(index * 0.41) * 0.7).toFloat(),
                color = floatArrayOf(0.15f + 0.8f * t, 0.5f, 0.9f - 0.7f * t),
                scale = 0.05f + 0.1f * ((index % 9) / 9f),
                opacityLogit = 0.5f + 2f * ((index % 5) / 5f)
            )
        }

    /** Write a PNG to the temp directory, and to [lookAtDir] as well when one is set. */
    private fun dump(name: String, image: SplatRaster.SplatImage) {
        val png = image.toPng()
        File(testDir, "$name.png").writeBytes(png)
        lookAtDir?.let { File(it, "$name.png").writeBytes(png) }
    }

    private inline fun guard(body: () -> String): Boolean =
        try {
            println("OK  ${body()}")
            true
        } catch (e: Throwable) {
            println("FAIL: ${e.message}")
            false
        }
}

fun main() {
    System.exit(if (SplatRasterTest.runTests()) 0 else 1)
}

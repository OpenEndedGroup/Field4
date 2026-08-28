package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/*
FIELD_TEST
id: four.splat-cloud-edit.smoke
runner: jvm-main
entrypoint: afield.four.modules.SplatCloudEditTestKt
tags: [smoke, four, ply, splat, io, raster]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "splat cloud edit tests PASSED"
*/
/**
 * Holds [SplatCloud] and [CompressedSplatPlyWriter] — the read / query / edit / preview / write loop.
 *
 * The two claims that matter, and neither is checkable by reading the code:
 *
 * 1. **A cloud that goes out comes back.** [CompressedSplatPlyWriter] is a new encoder and the only
 *    thing standing behind it is that it is the inverse of [CompressedSplatPly]'s decoder. So: write,
 *    read back, and compare, with the error pinned at the digits the quantization actually delivers.
 *    And separately, write the *same* input through [CompressedSplatPlyFixture] — the independent
 *    transcription of splat-transform's own encoder that already lives here — and require the bytes
 *    to be identical. Two encoders that agree byte for byte is a much better position than one
 *    encoder that agrees with the reader it was written against.
 * 2. **The CPU transform is the transform the GPU bakes.** [SplatCloud.transformed] decomposes and
 *    re-composes a rotation and three scales; `packInto` folds the same matrix into the covariance
 *    congruence and never writes it down. If those two disagree, an edited cloud looks different
 *    after it is saved, and no amount of float comparison inside either path would show it. So the
 *    test rasterizes both and compares pixels.
 */
object SplatCloudEditTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "splat_cloud_edit_test")
    private val lookAtDir: File? = System.getenv("AFIELD_SPLAT_RASTER_PNG_DIR")?.let { File(it) }

    fun runTests(): Boolean {
        var passed = true
        testDir.mkdirs()
        lookAtDir?.mkdirs()
        println("Splat cloud edit tests")
        println("=".repeat(72))

        passed = checkWriterAgreesWithFixture() && passed
        passed = checkRoundTrip() && passed
        passed = checkMortonOrderEarnsItsKeep() && passed
        passed = checkQuantizeMatchesWrite() && passed
        passed = checkEditOperations() && passed
        passed = checkTransformMatchesGpuBake() && passed
        passed = checkShearIsReported() && passed
        passed = checkEndToEnd() && passed

        println("=".repeat(72))
        println(if (passed) "splat cloud edit tests PASSED" else "splat cloud edit tests FAILED")
        return passed
    }

    // ------------------------------------------------------------------------
    // 1. Two independent encoders, byte for byte
    // ------------------------------------------------------------------------

    /**
     * [CompressedSplatPlyWriter] and [CompressedSplatPlyFixture] encode the same splats to the same
     * bytes.
     *
     * The fixture is a line-by-line transcription of splat-transform's `CompressedChunk.pack`, kept
     * deliberately optionless so that [CompressedSplatPly] is checked against the format's own
     * encoder rather than against a second copy of the reader's beliefs. The writer is the feature.
     * They were written from the same reference but not from each other, and the payload is bit-exact
     * quantization, so byte equality is the right assertion — with `mortonSort = false`, because
     * sorting is the one thing the writer does that the fixture deliberately does not.
     */
    private fun checkWriterAgreesWithFixture(): Boolean {
        print("Writer and fixture agree byte for byte... ")
        return guard {
            val splats = fixtureSplats(700)
            val fromFixture = File(testDir, "encoder_fixture.compressed.ply")
            CompressedSplatPlyFixture.write(fromFixture, splats, withChunkColors = true)

            // The cloud the fixture's splats stand for, in editing conventions. Reading the fixture's
            // own output would launder any disagreement through the decoder, so this converts the
            // source splats directly.
            val cloud = cloudOf(splats)
            val fromWriter = File(testDir, "encoder_writer.compressed.ply")
            CompressedSplatPlyWriter.write(fromWriter, cloud, mortonSort = false)

            val a = fromFixture.readBytes()
            val b = fromWriter.readBytes()
            // The comment line in the header differs by design, so compare the payload after it.
            val payloadA = a.copyOfRange(a.size - splats.size * 16, a.size)
            val payloadB = b.copyOfRange(b.size - splats.size * 16, b.size)
            var firstDiff = -1
            for (index in payloadA.indices) {
                if (payloadA[index] != payloadB[index]) { firstDiff = index; break }
            }
            check(firstDiff < 0) {
                "the two encoders' vertex payloads first differ at byte $firstDiff " +
                    "(splat ${firstDiff / 16}, word ${(firstDiff % 16) / 4})"
            }
            // And the chunk ranges, which is where a min/max policy difference would show.
            val rangesA = a.copyOfRange(a.size - splats.size * 16 - chunkBytes(splats.size), a.size - splats.size * 16)
            val rangesB = b.copyOfRange(b.size - splats.size * 16 - chunkBytes(splats.size), b.size - splats.size * 16)
            check(rangesA.contentEquals(rangesB)) { "the two encoders' chunk ranges differ" }
            "${splats.size} splats, ${payloadA.size} payload bytes and ${rangesA.size} range bytes identical"
        }
    }

    // ------------------------------------------------------------------------
    // 2. Round trip
    // ------------------------------------------------------------------------

    /**
     * A cloud written and read back, with the error pinned where the format puts it.
     *
     * The floors below are the measured figures with a little headroom, not round numbers chosen for
     * comfort. Position gets 11/10/11 bits *per chunk of 256 Morton-adjacent splats*, so the error
     * scales with how tightly a chunk's own box bounds it — which is exactly why the next test checks
     * that the sort is doing something.
     */
    private fun checkRoundTrip(): Boolean {
        print("Write, read back, compare... ")
        return guard {
            val original = cloudOf(fixtureSplats(1500))
            val file = File(testDir, "roundtrip.compressed.ply")
            val written = original.write(file)
            check(written == original.count) { "wrote $written of ${original.count} splats" }
            check(CompressedSplatPly.isCompressedSplatPly(file.absolutePath)) {
                "the file we just wrote is not recognised as a compressed splat PLY"
            }

            val back = SplatCloud.readCompressed(file.absolutePath)
            check(back.count == original.count) { "read back ${back.count} of ${original.count}" }

            // Morton sort reorders, so compare as multisets keyed by nearest neighbour in the
            // original. At these error scales a splat's nearest original is unambiguously itself.
            val index = NearestIndex(original)
            var worstPosition = 0f
            var worstColor = 0f
            var worstAlpha = 0f
            var worstScaleRatio = 0f
            for (splat in 0 until back.count) {
                val match = index.nearest(
                    back.positions[splat * 3], back.positions[splat * 3 + 1], back.positions[splat * 3 + 2]
                )
                for (axis in 0..2) {
                    worstPosition = max(
                        worstPosition,
                        abs(back.positions[splat * 3 + axis] - original.positions[match * 3 + axis])
                    )
                    worstColor = max(
                        worstColor, abs(back.colors[splat * 3 + axis] - original.colors[match * 3 + axis])
                    )
                    val ratio = back.scales[splat * 3 + axis] / original.scales[match * 3 + axis]
                    worstScaleRatio = max(worstScaleRatio, abs(ratio - 1f))
                }
                worstAlpha = max(worstAlpha, abs(back.alphas[splat] - original.alphas[match]))
            }
            // Measured: position 1.2e-3, colour 2.1e-3, alpha 2.0e-3, scale ratio 4.4e-3.
            check(worstPosition < 4e-3f) { "worst position error %.3e, expected < 4e-3".format(worstPosition) }
            check(worstColor < 5e-3f) { "worst colour error %.3e, expected < 5e-3".format(worstColor) }
            check(worstAlpha < 4e-3f) { "worst alpha error %.3e, expected < 4e-3".format(worstAlpha) }
            check(worstScaleRatio < 2e-2f) {
                "worst scale ratio error %.3e, expected < 2e-2".format(worstScaleRatio)
            }
            "position %.2e, colour %.2e, alpha %.2e, scale ratio %.2e over ${back.count} splats"
                .format(worstPosition, worstColor, worstAlpha, worstScaleRatio)
        }
    }

    /**
     * Morton ordering measurably tightens the quantization, rather than being a plausible-sounding
     * thing the writer does.
     *
     * Same cloud, written twice, read back twice, position error compared. Unsorted, every chunk's
     * box is the whole scene; sorted, each is a small spatial cell.
     *
     * The assertion is on the **mean** error, and that is not a softening. The *worst* error barely
     * moves and cannot be expected to: chunks hold exactly 256 splats while Morton octants hold
     * however many they hold, so some chunk always straddles an octant boundary and inherits close to
     * the full span on that axis. The worst error is a statistic about that one unluckiest chunk. The
     * mean is a statistic about the file, which is what the sort is for.
     *
     * Both are reported, because the gap between them is the interesting part — and because asserting
     * on the worst is exactly the mistake this test made first: it demanded a factor of two on a
     * quantity that physically cannot deliver one, and so it failed while pointing at nothing.
     * (It did catch a real overflow in the sort key on the way, which is why the key is 13 bits per
     * axis now. See [CompressedSplatPlyWriter.mortonOrder].)
     */
    private fun checkMortonOrderEarnsItsKeep(): Boolean {
        print("Morton order tightens the quantization... ")
        return guard {
            // Enough splats for thirty-odd chunks, so the sort has something to arrange.
            val original = cloudOf(fixtureSplats(8000))
            val index = NearestIndex(original)

            fun error(sorted: Boolean): Pair<Double, Float> {
                val file = File(testDir, "morton_$sorted.compressed.ply")
                CompressedSplatPlyWriter.write(file, original, mortonSort = sorted)
                val back = SplatCloud.readCompressed(file.absolutePath)
                var worst = 0f
                var sum = 0.0
                for (splat in 0 until back.count) {
                    val match = index.nearest(
                        back.positions[splat * 3], back.positions[splat * 3 + 1],
                        back.positions[splat * 3 + 2]
                    )
                    for (axis in 0..2) {
                        val delta =
                            abs(back.positions[splat * 3 + axis] - original.positions[match * 3 + axis])
                        worst = max(worst, delta)
                        sum += delta.toDouble()
                    }
                }
                return (sum / (back.count * 3)) to worst
            }

            val (sortedMean, sortedWorst) = error(true)
            val (unsortedMean, unsortedWorst) = error(false)
            check(sortedMean < unsortedMean / 2.0) {
                "Morton order gives a mean position error of %.3e against %.3e unsorted — less than "
                    .format(sortedMean, unsortedMean) +
                    "the factor of two that would make the sort worth doing"
            }
            "mean %.2e sorted vs %.2e unsorted (%.1fx tighter); worst %.2e vs %.2e, which the sort "
                .format(sortedMean, unsortedMean, unsortedMean / sortedMean, sortedWorst, unsortedWorst) +
                "cannot improve and is not asked to"
        }
    }

    /**
     * [CompressedSplatPlyWriter.quantize] and a round trip through [CompressedSplatPlyWriter.write]
     * produce identical payloads, so a CPU preview of an edited cloud is the same picture as the
     * file that gets saved.
     */
    private fun checkQuantizeMatchesWrite(): Boolean {
        print("quantize() == write() then readRaw()... ")
        return guard {
            val cloud = cloudOf(fixtureSplats(900))
            val direct = cloud.toRawSplats()
            val file = File(testDir, "quantize.compressed.ply")
            cloud.write(file)
            val viaDisk = CompressedSplatPly.readRaw(file.absolutePath)

            check(direct.count == viaDisk.count) { "${direct.count} vs ${viaDisk.count} splats" }
            check(direct.hasChunkColors == viaDisk.hasChunkColors) { "chunk colour flags differ" }
            check(direct.packed.contentEquals(viaDisk.packed)) {
                val at = direct.packed.indices.first { direct.packed[it] != viaDisk.packed[it] }
                "packed words differ at splat ${at / 4} word ${at % 4}"
            }
            check(direct.chunkRanges.contentEquals(viaDisk.chunkRanges)) { "chunk ranges differ" }
            "${direct.count} splats, ${direct.packed.size} words and " +
                "${direct.chunkRanges.size} ranges identical"
        }
    }

    // ------------------------------------------------------------------------
    // 3. The edit operations
    // ------------------------------------------------------------------------

    /** Crop, filter, decimate and merge do what they say, including at the boundaries. */
    private fun checkEditOperations(): Boolean {
        print("crop / filter / decimate / merge... ")
        return guard {
            val cloud = cloudOf(fixtureSplats(1000))
            val notes = mutableListOf<String>()

            val box = cloud.cropped(Vector3f(-0.3f, -0.3f, -0.3f), Vector3f(0.3f, 0.3f, 0.3f))
            check(box.count in 1 until cloud.count) {
                "cropping to a small box kept ${box.count} of ${cloud.count}, which is not a crop"
            }
            for (splat in 0 until box.count) {
                for (axis in 0..2) {
                    val value = box.positions[splat * 3 + axis]
                    check(value >= -0.3f && value <= 0.3f) { "cropped splat $splat axis $axis at $value" }
                }
            }
            notes += "crop ${cloud.count} -> ${box.count}"

            val stats = cloud.stats()
            val opaque = cloud.filtered(minAlpha = stats.alphaPercentiles[2])
            check(opaque.count in 1 until cloud.count) {
                "filtering at the median opacity kept ${opaque.count} of ${cloud.count}"
            }
            check(opaque.alphas.take(opaque.count).all { it >= stats.alphaPercentiles[2] }) {
                "a filtered splat is below the threshold"
            }
            notes += "filter@p50 ${cloud.count} -> ${opaque.count}"

            val half = cloud.decimated(0.5f)
            check(abs(half.count - cloud.count / 2) <= 1) {
                "decimating by half gave ${half.count} of ${cloud.count}"
            }
            check(cloud.decimated(1f).count == cloud.count) { "decimating by 1.0 changed the count" }
            notes += "decimate ${cloud.count} -> ${half.count}"

            val merged = SplatCloud.merge(listOf(box, half, opaque))
            check(merged.count == box.count + half.count + opaque.count) {
                "merge gave ${merged.count}, expected ${box.count + half.count + opaque.count}"
            }
            // The first cloud's splats must be verbatim at the front, or an order-dependent draw of a
            // merged cloud is composited from data that moved.
            for (splat in 0 until box.count) {
                check(merged.positions[splat * 3] == box.positions[splat * 3]) {
                    "merge disturbed the first cloud at splat $splat"
                }
            }
            notes += "merge -> ${merged.count}"

            val empty = cloud.cropped(Vector3f(90f, 90f, 90f), Vector3f(91f, 91f, 91f))
            check(empty.count == 0) { "cropping to an empty box kept ${empty.count}" }
            notes += "empty crop -> 0"

            notes.joinToString(", ")
        }
    }

    // ------------------------------------------------------------------------
    // 4. The claim that needs pixels
    // ------------------------------------------------------------------------

    /**
     * [SplatCloud.transformed] and `packInto`'s transform bake are the same transform.
     *
     * They arrive by completely different routes. `packInto` folds the matrix into the covariance
     * congruence `(L·A)·S²·(L·A)ᵀ` and never represents the result as a rotation at all;
     * [SplatCloud.transformed] has to produce a rotation and three scales, because that is all a
     * `.compressed.ply` can hold, so it multiplies, Gram-Schmidts, and converts back to a quaternion.
     * A sign error in the quaternion recovery, or a row/column transposition in either, gives a
     * cloud that is subtly wrong in a way no float comparison inside either path can see.
     *
     * So both go through [SplatRaster] and the images are compared. The control is the untransformed
     * cloud: if that also matched, the transform would be doing nothing and this would prove nothing.
     */
    private fun checkTransformMatchesGpuBake(): Boolean {
        print("CPU transform == packInto's bake, in pixels... ")
        return guard {
            val size = 200
            val file = File(testDir, "transform.compressed.ply")
            CompressedSplatPlyFixture.write(file, fixtureSplats(1500))
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val cloud = SplatCloud.readCompressed(file.absolutePath)

            // A rotation about a tilted axis, a uniform scale and a translation: everything the file
            // format can hold exactly, so any disagreement is a bug and not a representation limit.
            val axis = Vector3f(0.3f, 0.8f, 0.5f).normalize()
            val uniform = Matrix4f()
                .rotate(0.7f, axis)
                .scale(1.3f)
                .setTranslation(0.25f, -0.1f, 0.4f)

            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0.4f, 0.3f, 3.2f), target = Vector3f(0.25f, -0.1f, 0.4f),
                width = size, height = size
            )
            // Both depth-sorted, so the Morton reorder that toRawSplats performs cannot show up as a
            // compositing-order difference and confuse the comparison with a real one.
            val config = SplatRaster.RasterConfig(depthOrder = SplatRaster.DepthOrder.BackToFront)

            val gpuWay = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(raw, uniform)), view, config
            )
            val cpuWay = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(cloud.transformed(uniform).toRawSplats())),
                view, config
            )
            val control = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(raw)), view, config
            )
            dump("transform_bake", gpuWay.image)
            dump("transform_edit", cpuWay.image)

            check(gpuWay.stats.drawn > 800) {
                "only ${gpuWay.stats.drawn} splats in frame; too empty to compare"
            }
            val agree = meanAbsDelta(gpuWay.image.toArgb(), cpuWay.image.toArgb())
            val disagree = meanAbsDelta(gpuWay.image.toArgb(), control.image.toArgb())
            // Measured 0.42 against a control of 27.5. The floor is 2.0 code values per channel,
            // which is the quantization cost of the round trip through toRawSplats and nothing more.
            check(agree < 2.0) {
                "the CPU transform and packInto's bake differ by %.3f code values per channel, ".format(agree) +
                    "expected < 2.0"
            }
            check(disagree > 10.0 * max(agree, 0.01)) {
                "the untransformed control is only %.3f off against the transformed %.3f, so this "
                    .format(disagree, agree) + "comparison is not testing the transform"
            }
            "%.3f code values per channel vs a control of %.3f, ${gpuWay.stats.drawn} splats"
                .format(agree, disagree)
        }
    }

    /**
     * A shear is reported as lost rather than silently absorbed.
     *
     * The `.compressed.ply` format has no way to store a sheared covariance, so
     * [SplatCloud.transformed] approximates it with the nearest rotate-and-scale. That is a real loss
     * and the caller is entitled to know: [SplatCloud.shearLoss] is non-zero after a shear and zero
     * after a transform the format can hold. The second half is the important half — a "loss"
     * indicator that is always on says nothing.
     */
    private fun checkShearIsReported(): Boolean {
        print("Shear is reported, rigid transforms are not... ")
        return guard {
            val cloud = cloudOf(fixtureSplats(400))
            val rigid = Matrix4f().rotate(0.5f, Vector3f(0f, 1f, 0f)).setTranslation(0.2f, 0f, 0f)
            val rigidLoss = cloud.transformed(rigid).shearLoss
            check(rigidLoss < 1e-5f) {
                "a rotation and a translation reported a shear loss of %.3e; the indicator is always on"
                    .format(rigidLoss)
            }
            // A shear: x picks up 0.6 of y. Matrix4f is column-major with m<column><row>, so the
            // entry that maps y into x is column 1, row 0.
            val shear = Matrix4f().apply { m10 = 0.6f }
            val shearLoss = cloud.transformed(shear).shearLoss
            check(shearLoss > 0.1f) {
                "a shear of 0.6 reported a loss of only %.3e; it is not being noticed".format(shearLoss)
            }
            "rigid %.2e, shear %.3f".format(rigidLoss, shearLoss)
        }
    }

    // ------------------------------------------------------------------------
    // 5. The whole loop
    // ------------------------------------------------------------------------

    /** Read, query, edit, preview, write — and the written file reads back and previews too. */
    private fun checkEndToEnd(): Boolean {
        print("read -> query -> edit -> preview -> write -> read... ")
        return guard {
            val source = File(testDir, "endtoend_in.compressed.ply")
            CompressedSplatPlyFixture.write(source, fixtureSplats(2000))

            val cloud = SplatCloud.readCompressed(source.absolutePath)
            val stats = cloud.stats()
            check(stats.count == 2000) { "read ${stats.count} splats" }
            check(stats.nonFinite == 0) { "${stats.nonFinite} non-finite splats in a clean fixture" }

            val edited = cloud
                .cropped(Vector3f(-0.6f, -0.6f, -0.6f), Vector3f(0.6f, 0.6f, 0.6f))
                .filtered(minAlpha = 0.2f)
                .decimated(0.7f)
                .withScale(1.4f)
            check(edited.count in 1 until cloud.count) { "the edit kept ${edited.count} of ${cloud.count}" }

            val view = SplatRaster.View.lookAt(
                eye = Vector3f(0f, 0f, 2.6f), target = Vector3f(0f, 0f, 0f), width = 240, height = 160
            )
            val before = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(cloud.toRawSplats())), view
            )
            val after = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(edited.toRawSplats())), view
            )
            dump("endtoend_before", before.image)
            dump("endtoend_after", after.image)
            check(!before.image.stats().blank) { "the preview before editing is blank" }
            check(!after.image.stats().blank) { "the preview after editing is blank" }
            val moved = meanAbsDelta(before.image.toArgb(), after.image.toArgb())
            check(moved > 2.0) {
                "the edit changed the picture by only %.3f code values per channel; either the edit ".format(moved) +
                    "did nothing or the preview is not looking at it"
            }

            val out = File(testDir, "endtoend_out.compressed.ply")
            edited.write(out)
            val reread = SplatCloud.readCompressed(out.absolutePath)
            check(reread.count == edited.count) { "wrote ${edited.count}, read back ${reread.count}" }
            val reraster = SplatRaster.rasterize(
                listOf(GaussianSplatRenderer.SplatSource(reread.toRawSplats())), view
            )
            val afterSave = meanAbsDelta(after.image.toArgb(), reraster.image.toArgb())
            // Saving and reloading must not change the picture. One quantization generation costs
            // well under a code value on this data; the floor is 1.5.
            check(afterSave < 1.5) {
                "saving and reloading moved the picture by %.3f code values per channel".format(afterSave)
            }
            "${cloud.count} -> ${edited.count} splats, edit moved %.2f, save/reload moved %.3f, " .format(moved, afterSave) +
                "${out.length()} bytes out"
        }
    }

    // ------------------------------------------------------------------------
    // Fixtures and plumbing
    // ------------------------------------------------------------------------

    /**
     * A cheap spatial index over the original cloud, so a round-trip comparison can pair a
     * re-sorted splat with the one it came from.
     *
     * A uniform grid rather than a k-d tree: the clouds here are thousands of splats and the query is
     * always "the same splat, moved by a quantization step", so a grid whose cell is far larger than
     * that step finds it in the first bucket it looks in.
     */
    private class NearestIndex(val cloud: SplatCloud) {
        private val resolution = 32
        private var minX = Float.MAX_VALUE; private var minY = Float.MAX_VALUE; private var minZ = Float.MAX_VALUE
        private var spanX = 1f; private var spanY = 1f; private var spanZ = 1f
        private val buckets = HashMap<Int, MutableList<Int>>()

        init {
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (splat in 0 until cloud.count) {
                minX = minOf(minX, cloud.positions[splat * 3]); maxX = maxOf(maxX, cloud.positions[splat * 3])
                minY = minOf(minY, cloud.positions[splat * 3 + 1]); maxY = maxOf(maxY, cloud.positions[splat * 3 + 1])
                minZ = minOf(minZ, cloud.positions[splat * 3 + 2]); maxZ = maxOf(maxZ, cloud.positions[splat * 3 + 2])
            }
            spanX = if (maxX > minX) maxX - minX else 1f
            spanY = if (maxY > minY) maxY - minY else 1f
            spanZ = if (maxZ > minZ) maxZ - minZ else 1f
            for (splat in 0 until cloud.count) {
                buckets.getOrPut(
                    cell(cloud.positions[splat * 3], cloud.positions[splat * 3 + 1], cloud.positions[splat * 3 + 2])
                ) { mutableListOf() }.add(splat)
            }
        }

        private fun cell(x: Float, y: Float, z: Float): Int {
            val cx = (((x - minX) / spanX) * resolution).toInt().coerceIn(0, resolution - 1)
            val cy = (((y - minY) / spanY) * resolution).toInt().coerceIn(0, resolution - 1)
            val cz = (((z - minZ) / spanZ) * resolution).toInt().coerceIn(0, resolution - 1)
            return (cx * resolution + cy) * resolution + cz
        }

        fun nearest(x: Float, y: Float, z: Float): Int {
            val cx = (((x - minX) / spanX) * resolution).toInt().coerceIn(0, resolution - 1)
            val cy = (((y - minY) / spanY) * resolution).toInt().coerceIn(0, resolution - 1)
            val cz = (((z - minZ) / spanZ) * resolution).toInt().coerceIn(0, resolution - 1)
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val ix = cx + dx; val iy = cy + dy; val iz = cz + dz
                if (ix < 0 || iy < 0 || iz < 0 || ix >= resolution || iy >= resolution || iz >= resolution) continue
                val bucket = buckets[(ix * resolution + iy) * resolution + iz] ?: continue
                for (splat in bucket) {
                    val ddx = cloud.positions[splat * 3] - x
                    val ddy = cloud.positions[splat * 3 + 1] - y
                    val ddz = cloud.positions[splat * 3 + 2] - z
                    val distance = ddx * ddx + ddy * ddy + ddz * ddz
                    if (distance < bestDistance) { bestDistance = distance; best = splat }
                }
            }
            check(best >= 0) { "no original splat near ($x, $y, $z)" }
            return best
        }
    }

    /** [CompressedSplatPlyFixture.Splat]s as a [SplatCloud], converting conventions and nothing else. */
    private fun cloudOf(splats: List<CompressedSplatPlyFixture.Splat>): SplatCloud {
        val cloud = SplatCloud(
            FloatArray(splats.size * 3), FloatArray(splats.size * 3), FloatArray(splats.size),
            FloatArray(splats.size * 4), FloatArray(splats.size * 3), splats.size
        )
        splats.forEachIndexed { splat, source ->
            cloud.positions[splat * 3] = source.x
            cloud.positions[splat * 3 + 1] = source.y
            cloud.positions[splat * 3 + 2] = source.z
            for (channel in 0..2) {
                cloud.colors[splat * 3 + channel] =
                    source.fDc[channel] * CompressedSplatPly.SH_C0 + 0.5f
            }
            cloud.alphas[splat] = SplatCloud.sigmoid(source.opacity)
            for (component in 0..3) cloud.rotations[splat * 4 + component] = source.rot[component]
            for (axis in 0..2) {
                cloud.scales[splat * 3 + axis] =
                    kotlin.math.exp(source.scale[axis].toDouble()).toFloat()
            }
        }
        return cloud
    }

    /**
     * A deterministic cloud with unit quaternions and in-range colours.
     *
     * Colours stay inside 0..1 and scales inside a decade, because the round-trip assertions are about
     * quantization error and a colour the format has to clamp would show up as one.
     */
    private fun fixtureSplats(count: Int): List<CompressedSplatPlyFixture.Splat> =
        (0 until count).map { index ->
            val t = index.toDouble()
            val rot = floatArrayOf(
                cos(t * 0.31).toFloat(), sin(t * 0.47).toFloat(),
                cos(t * 0.73).toFloat(), sin(t * 0.19).toFloat()
            )
            val length = kotlin.math.sqrt(rot.sumOf { (it * it).toDouble() }).toFloat()
            CompressedSplatPlyFixture.Splat(
                x = (sin(t * 1.31) * 0.7).toFloat(),
                y = (cos(t * 0.77) * 0.7).toFloat(),
                z = (sin(t * 0.41) * 0.7).toFloat(),
                fDc = FloatArray(3) { channel ->
                    (0.2f + 0.6f * (((index + channel * 37) % 91) / 91f) - 0.5f) / CompressedSplatPly.SH_C0
                },
                opacity = -1.5f + 3f * ((index % 13) / 13f),
                rot = FloatArray(4) { rot[it] / length },
                scale = FloatArray(3) { axis ->
                    ln((0.02 + 0.05 * (((index + axis * 11) % 17) / 17.0))).toFloat()
                }
            )
        }

    private fun chunkBytes(splatCount: Int): Int {
        val chunks = (splatCount + CompressedSplatPly.CHUNK_SIZE - 1) / CompressedSplatPly.CHUNK_SIZE
        return chunks * 18 * 4
    }

    private fun meanAbsDelta(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (pixel in a.indices) for (shift in intArrayOf(16, 8, 0)) {
            sum += abs(((a[pixel] shr shift) and 255) - ((b[pixel] shr shift) and 255))
        }
        return sum.toDouble() / (a.size * 3)
    }

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
    System.exit(if (SplatCloudEditTest.runTests()) 0 else 1)
}

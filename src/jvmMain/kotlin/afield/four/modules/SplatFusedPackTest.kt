package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
FIELD_TEST
id: four.splat-fused-pack.smoke
runner: jvm-main
entrypoint: afield.four.modules.SplatFusedPackTestKt
tags: [smoke, four, ply, splat, io, performance]
timeoutSec: 120
expect:
  exitCode: 0
  stdoutContains:
    - "fused pack tests PASSED"
*/
/**
 * Holds [GaussianSplatRenderer.packInto] — the fused compressed-file-to-GPU-layout path — against
 * [GaussianSplatRenderer.pack] over the [PlyFloatTable], which is the same computation the slow way.
 *
 * The two are *not* bit-identical on purpose. The compressed format stores colour as linear 0..1 and
 * opacity post-sigmoid, which is exactly what the shader consumes, so the fused path skips the
 * `f_dc = (c - 0.5) / SH_C0` and logit round trips that the table path performs and undoes. Every
 * other quantity — position, scale, rotation, covariance — is computed by the same expressions in
 * the same order and to the same spelling, so the tolerances below are tight enough that a swapped
 * field or a wrong bit shift cannot pass.
 */
object SplatFusedPackTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "splat_fused_test")
    private const val STRIDE = GaussianSplatRenderer.SPLAT_FLOAT_STRIDE

    /** Per-field tolerance, indexed by position within a splat's 16 floats. */
    private fun tolerance(field: Int): Float = when (field) {
        0, 1, 2 -> 0f                 // position: identical expressions, so identical bits
        3 -> 3e-6f                    // alpha: the sigmoid/logit round trip the fused path skips
        4, 5, 6 -> 2e-5f              // colour: the f_dc round trip, magnified by 1/SH_C0 and back
        7, 14, 15 -> 0f               // padding
        else -> 3e-5f                 // covariance: pack renormalises an already-unit quaternion
    }

    fun runTests(): Boolean {
        var passed = true
        testDir.mkdirs()
        println("Fused splat pack tests")
        println("=".repeat(60))

        val splats = CompressedSplatPlyFixture.let { _ -> syntheticSplats(600) }
        val file = File(testDir, "fused_600.compressed.ply")
        CompressedSplatPlyFixture.write(file, splats, withChunkColors = true)
        val noColorFile = File(testDir, "fused_600_nocolor.compressed.ply")
        CompressedSplatPlyFixture.write(noColorFile, splats, withChunkColors = false)

        passed = check1(file) && passed
        passed = check1(noColorFile, "12-property chunks") && passed
        passed = checkMultiSource(file) && passed
        passed = checkSort(file) && passed
        passed = checkTransforms(file) && passed
        passed = checkCapacityGuard(file) && passed
        report(file)

        println("=".repeat(60))
        println(if (passed) "fused pack tests PASSED" else "fused pack tests FAILED")
        return passed
    }

    /** The core claim: fused output equals the table path, field by field, splat by splat. */
    private fun check1(file: File, label: String = "18-property chunks"): Boolean {
        print("Fused == pack ($label)... ")
        return guard {
            val reference = GaussianSplatRenderer.pack(readCompressedSplatPly(file.absolutePath))
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            check(raw.count == reference.count) { "readRaw saw ${raw.count}, pack saw ${reference.count}" }

            val dest = floatBuffer(raw.count)
            val frame = GaussianSplatRenderer.packInto(raw, dest)
            check(frame.count == reference.count) { "packInto wrote ${frame.count}" }
            check(frame.spans?.single()?.offset == 0) { "single source should span from 0" }

            var worst = 0f; var worstAt = -1
            for (i in 0 until raw.count * STRIDE) {
                val a = dest.get(i)
                val b = reference.floats[i]
                val diff = Math.abs(a - b)
                val scale = maxOf(1f, Math.abs(b))
                val relative = diff / scale
                if (relative > tolerance(i % STRIDE)) {
                    error("splat ${i / STRIDE} field ${i % STRIDE}: fused $a vs pack $b (rel $relative)")
                }
                if (relative > worst) { worst = relative; worstAt = i }
            }
            check(near(frame.boundsMin, reference.boundsMin)) { "boundsMin ${frame.boundsMin} vs ${reference.boundsMin}" }
            check(near(frame.boundsMax, reference.boundsMax)) { "boundsMax ${frame.boundsMax} vs ${reference.boundsMax}" }
            "worst %.2e at field %d".format(worst, if (worstAt < 0) -1 else worstAt % STRIDE)
        }
    }

    /** Fifteen files into one buffer: the streaming shape. */
    private fun checkMultiSource(file: File): Boolean {
        print("One destination, 15 sources... ")
        return guard {
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val sources = List(15) { GaussianSplatRenderer.SplatSource(raw) }
            val total = raw.count * 15
            val dest = floatBuffer(total)
            val frame = GaussianSplatRenderer.packInto(sources, dest)
            check(frame.count == total) { "expected $total splats, got ${frame.count}" }
            val spans = requireNotNull(frame.spans) { "an unsorted pack should report spans" }
            check(spans.size == 15) { "expected 15 spans" }
            spans.forEachIndexed { index, span ->
                check(span.offset == index * raw.count) { "span $index at ${span.offset}" }
                check(span.count == raw.count) { "span $index count ${span.count}" }
            }
            // Every slot is byte-identical to the single-source pack of the same file, EXCEPT the
            // source-index float, which is the whole point of it being there.
            val one = floatBuffer(raw.count)
            GaussianSplatRenderer.packInto(raw, one)
            val sourceField = GaussianSplatRenderer.SPLAT_SOURCE_FLOAT
            for (slot in 0 until 15) {
                val base = slot * raw.count * STRIDE
                for (i in 0 until raw.count * STRIDE) {
                    if (i % STRIDE == sourceField) {
                        check(dest.get(base + i) == slot.toFloat()) {
                            "slot $slot splat ${i / STRIDE} carries source ${dest.get(base + i)}"
                        }
                    } else {
                        check(dest.get(base + i) == one.get(i)) { "slot $slot diverged at float $i" }
                    }
                }
            }
            "15 x ${raw.count} = $total, slots identical and each carries its source index"
        }
    }

    /** Sorting must put the far splats down first, and must not lose or duplicate any. */
    private fun checkSort(file: File): Boolean {
        print("Back-to-front CPU sort... ")
        return guard {
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val sources = listOf(raw, raw, raw).map { GaussianSplatRenderer.SplatSource(it) }
            val total = raw.count * 3
            val unsorted = floatBuffer(total)
            GaussianSplatRenderer.packInto(sources, unsorted)

            val along = Vector3f(0f, 0f, 1f)
            val sorted = floatBuffer(total)
            val frame = GaussianSplatRenderer.packInto(sources, sorted, sortAlong = along)
            check(frame.count == total) { "sorted pack wrote ${frame.count}" }
            check(frame.spans == null) { "a sorted pack interleaves sources, so spans must be null" }

            var previous = Float.POSITIVE_INFINITY
            for (splat in 0 until total) {
                val z = sorted.get(splat * STRIDE + 2)
                check(z <= previous + 1e-6f) { "splat $splat z=$z rose above the previous $previous" }
                previous = z
            }
            // Sorting interleaves the sources, so the source index is the only way a shader can
            // still tell them apart -- check every value is a source that actually contributed.
            val seen = IntArray(sources.size)
            for (splat in 0 until total) {
                val carried = sorted.get(splat * STRIDE + GaussianSplatRenderer.SPLAT_SOURCE_FLOAT)
                val index = carried.toInt()
                check(carried == index.toFloat() && index in sources.indices) {
                    "splat $splat carries source $carried, not one of 0..${sources.size - 1}"
                }
                seen[index]++
            }
            check(seen.all { it == raw.count }) {
                "each source should contribute ${raw.count} splats, got ${seen.toList()}"
            }
            // A sort is a permutation: the multiset of positions must be unchanged.
            check(positionHash(unsorted, total) == positionHash(sorted, total)) {
                "sorting changed the set of splats, it must only reorder them"
            }
            check(near(frame.boundsMin, GaussianSplatRenderer.packInto(sources, unsorted).boundsMin)) {
                "bounds must not depend on ordering"
            }
            "$total splats, monotone in z, permutation verified"
        }
    }

    /**
     * A per-source transform must move the splats *as ellipsoids*: the centre by the affine map, and
     * the covariance by the matching congruence. Checked against the definition rather than against
     * a second implementation — a translation must leave the covariance alone, a rotation must
     * conjugate it, and a uniform scale must scale it by the square.
     */
    private fun checkTransforms(file: File): Boolean {
        print("Per-source transforms... ")
        return guard {
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val n = raw.count
            val plain = floatBuffer(n)
            GaussianSplatRenderer.packInto(raw, plain)

            // Identity must be indistinguishable from no transform at all.
            val identity = floatBuffer(n)
            GaussianSplatRenderer.packInto(raw, identity, transform = Matrix4f())
            for (i in 0 until n * STRIDE) {
                check(identity.get(i) == plain.get(i)) { "identity transform changed float $i" }
            }

            // Pure translation: centres shift, covariance untouched.
            val shifted = floatBuffer(n)
            GaussianSplatRenderer.packInto(raw, shifted, transform = Matrix4f().translation(3f, -2f, 7f))
            for (splat in 0 until n) {
                val at = splat * STRIDE
                check(Math.abs(shifted.get(at) - (plain.get(at) + 3f)) < 1e-4f) { "splat $splat x" }
                check(Math.abs(shifted.get(at + 1) - (plain.get(at + 1) - 2f)) < 1e-4f) { "splat $splat y" }
                check(Math.abs(shifted.get(at + 2) - (plain.get(at + 2) + 7f)) < 1e-4f) { "splat $splat z" }
                for (field in 8..13) {
                    check(Math.abs(shifted.get(at + field) - plain.get(at + field)) < 1e-9f) {
                        "translation must not touch the covariance, but field $field moved"
                    }
                }
            }

            // Uniform scale: covariance scales by the square, so its trace does too.
            val scaled = floatBuffer(n)
            val k = 2.5f
            GaussianSplatRenderer.packInto(raw, scaled, transform = Matrix4f().scaling(k, k, k))
            for (splat in 0 until n) {
                val at = splat * STRIDE
                val traceBefore = plain.get(at + 8) + plain.get(at + 11) + plain.get(at + 13)
                val traceAfter = scaled.get(at + 8) + scaled.get(at + 11) + scaled.get(at + 13)
                check(Math.abs(traceAfter - traceBefore * k * k) <= 1e-4f * maxOf(1f, traceAfter)) {
                    "splat $splat covariance trace $traceAfter, expected ${traceBefore * k * k}"
                }
                check(Math.abs(scaled.get(at) - plain.get(at) * k) < 1e-4f) { "splat $splat x not scaled" }
            }

            // A rotation preserves the ellipsoid's volume, so det(covariance) is invariant.
            val rotated = floatBuffer(n)
            val turn = rotationMatrix(0.7f, 0.3f, 0.5f, 0.81f)
            GaussianSplatRenderer.packInto(raw, rotated, transform = turn)
            var worstDet = 0f
            for (splat in 0 until n) {
                val a = determinant(plain, splat)
                val b = determinant(rotated, splat)
                val relative = Math.abs(a - b) / maxOf(1e-20f, Math.abs(a))
                if (relative > worstDet) worstDet = relative
                check(relative < 2e-2f) { "splat $splat covariance det $b vs $a under rotation" }
            }

            // Different transforms per source must not bleed into each other.
            val mixed = floatBuffer(n * 2)
            GaussianSplatRenderer.packInto(
                listOf(
                    GaussianSplatRenderer.SplatSource(raw, Matrix4f().translation(10f, 0f, 0f)),
                    GaussianSplatRenderer.SplatSource(raw, Matrix4f().translation(-10f, 0f, 0f))
                ),
                mixed
            )
            for (splat in 0 until n) {
                val a = mixed.get(splat * STRIDE)
                val b = mixed.get((n + splat) * STRIDE)
                check(Math.abs((a - b) - 20f) < 1e-3f) { "per-source transforms bled: $a vs $b" }
            }
            "identity/translate/scale/rotate hold; worst det drift %.1e".format(worstDet)
        }
    }

    /**
     * A rotation matrix from a quaternion, built here rather than via Matrix4f.rotation so the test
     * does not depend on a Quaternionf conversion it is not trying to exercise. Matrix4f is
     * column-major with `m<column><row>`, so each `set` group of four is a COLUMN.
     */
    private fun rotationMatrix(qw: Float, qx: Float, qy: Float, qz: Float): Matrix4f {
        val n = kotlin.math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        val w = qw / n; val x = qx / n; val y = qy / n; val z = qz / n
        val r00 = 1 - 2 * (y * y + z * z); val r01 = 2 * (x * y - w * z); val r02 = 2 * (x * z + w * y)
        val r10 = 2 * (x * y + w * z); val r11 = 1 - 2 * (x * x + z * z); val r12 = 2 * (y * z - w * x)
        val r20 = 2 * (x * z - w * y); val r21 = 2 * (y * z + w * x); val r22 = 1 - 2 * (x * x + y * y)
        return Matrix4f().set(
            r00, r10, r20, 0f,
            r01, r11, r21, 0f,
            r02, r12, r22, 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** det of the symmetric covariance stored at fields 8..13 as xx xy xz yy yz zz. */
    private fun determinant(buffer: java.nio.FloatBuffer, splat: Int): Float {
        val at = splat * STRIDE
        val xx = buffer.get(at + 8); val xy = buffer.get(at + 9); val xz = buffer.get(at + 10)
        val yy = buffer.get(at + 11); val yz = buffer.get(at + 12); val zz = buffer.get(at + 13)
        return xx * (yy * zz - yz * yz) - xy * (xy * zz - yz * xz) + xz * (xy * yz - yy * xz)
    }

    private fun checkCapacityGuard(file: File): Boolean {
        print("Undersized destination refuses... ")
        return guard {
            val raw = CompressedSplatPly.readRaw(file.absolutePath)
            val tooSmall = floatBuffer(raw.count - 1)
            val message = runCatching { GaussianSplatRenderer.packInto(raw, tooSmall) }
                .exceptionOrNull()?.message ?: error("an undersized destination should have thrown")
            check(message.contains("need ${raw.count}")) { "unhelpful message: $message" }
            "says what it needed"
        }
    }

    /** Throughput, printed not asserted -- a timing floor in a smoke test is a flake generator. */
    private fun report(file: File) {
        val raw = CompressedSplatPly.readRaw(file.absolutePath)
        val sources = List(15) { GaussianSplatRenderer.SplatSource(raw) }
        val total = raw.count * 15
        val dest = floatBuffer(total)
        val scratch = GaussianSplatRenderer.PackScratch()
        val along = Vector3f(0f, 0f, 1f)

        fun bench(label: String, body: () -> Unit) {
            repeat(20) { body() }
            val t0 = System.nanoTime()
            repeat(50) { body() }
            val ns = (System.nanoTime() - t0) / 50
            println("  %-38s %6.2f ms/frame  %7.2f M splats/s".format(label, ns / 1e6, total * 1e9 / ns / 1e6))
        }
        println("\n  one frame = 15 sources x ${raw.count} splats = $total")
        bench("fused, unsorted") { GaussianSplatRenderer.packInto(sources, dest, scratch = scratch) }
        bench("fused, CPU back-to-front sort") {
            GaussianSplatRenderer.packInto(sources, dest, sortAlong = along, scratch = scratch)
        }
        val table = readCompressedSplatPly(file.absolutePath)
        bench("table path (decompress once + pack x15)") {
            repeat(15) { GaussianSplatRenderer.pack(table) }
        }
        println()
    }

    // ------------------------------------------------------------------------

    private fun floatBuffer(splats: Int) = ByteBuffer
        .allocateDirect(maxOf(1, splats) * STRIDE * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun positionHash(buffer: java.nio.FloatBuffer, count: Int): Long {
        var hash = 0L
        for (splat in 0 until count) {
            for (field in 0..2) {
                hash += java.lang.Float.floatToRawIntBits(buffer.get(splat * STRIDE + field)).toLong() * 31L
            }
        }
        return hash
    }

    private fun near(a: Vector3f, b: Vector3f) =
        Math.abs(a.x - b.x) < 1e-5f && Math.abs(a.y - b.y) < 1e-5f && Math.abs(a.z - b.z) < 1e-5f

    private inline fun guard(body: () -> String): Boolean =
        try {
            println("OK  ${body()}")
            true
        } catch (e: Throwable) {
            println("FAIL: ${e.message}")
            false
        }

    private fun syntheticSplats(count: Int): List<CompressedSplatPlyFixture.Splat> {
        var state = 0x5DEECE66DL
        fun next(): Float {
            state = (state * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFFFFFFFFFFL
            return ((state ushr 24) and 0xFFFFFF).toFloat() / 0xFFFFFF.toFloat()
        }
        fun span(low: Float, high: Float) = low + next() * (high - low)
        return (0 until count).map {
            val rot = floatArrayOf(span(-1f, 1f), span(-1f, 1f), span(-1f, 1f), span(-1f, 1f))
            val length = kotlin.math.sqrt(rot.sumOf { (it * it).toDouble() }).toFloat()
            CompressedSplatPlyFixture.Splat(
                x = span(-1f, 1f), y = span(-1f, 1f), z = span(0.5f, 3f),
                fDc = FloatArray(3) { span(-1.5f, 1.5f) },
                opacity = span(-6f, 6f),
                rot = FloatArray(4) { rot[it] / length },
                scale = FloatArray(3) { span(-5f, -2f) }
            )
        }
    }
}

fun main() {
    System.exit(if (SplatFusedPackTest.runTests()) 0 else 1)
}

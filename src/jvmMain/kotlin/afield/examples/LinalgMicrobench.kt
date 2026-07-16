package afield.examples

import afield.stdlib.linalg.Vector3d
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.arena.FrameArena
import afield.stdlib.linalg.storage.SegmentVec3Buffer
import afield.stdlib.linalg.storage.Vec3StorageLayout
import afield.stdlib.vec
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer

/*
Screening microbenchmark for the generated linear-algebra backends.

This is deliberately NOT a rigorous JMH harness. A short in-process loop cannot reliably
defeat HotSpot escape analysis, so absolute nanoseconds are indicative only. The purpose is
to screen for gross mistakes: a backend that is an order of magnitude off its siblings, an
allocation storm hiding behind a "destination" API, or arena validation that costs far more
than the arithmetic it guards. For real numbers, promote the winning cases to a JMH module.

Run:
  run-jvm-main --timeout 120 afield.examples.LinalgMicrobench
or set FIELD_MICROBENCH_ITERS / FIELD_MICROBENCH_WARMUP to override the loop sizes.

FIELD_TEST
id: examples.linalg-microbench
runner: jvm-main
entrypoint: afield.examples.LinalgMicrobench
tags: [bench, linalg]
timeoutSec: 180
expect:
  exitCode: 0
  stdoutContains:
    - "Generated linalg microbenchmark completed"
*/
object LinalgMicrobench {
    private const val SCALE = 1.0001f
    private const val SCALE_D = 1.0001

    @JvmStatic
    fun main(args: Array<String>) {
        val iters = (System.getenv("FIELD_MICROBENCH_ITERS")?.toIntOrNull() ?: 5_000_000)
        val warmup = (System.getenv("FIELD_MICROBENCH_WARMUP")?.toIntOrNull() ?: 2_000_000)
        println("Linalg microbenchmark — warmup=$warmup iters=$iters")
        println("arena checks compiled in: ${FrameArena.checksCompiledIn}")
        println()

        val results = mutableListOf<Row>()
        results += measure("heap Vector3f (alloc per op)", warmup, iters, ::heapAllocKernel)
        results += measure("heap Vector3f (reused dest)", warmup, iters, ::heapReuseKernel)
        results += measure("arena ArenaVec3f (checks ON)", warmup, iters) { n -> arenaKernel(n, debugChecks = true) }
        results += measure("arena ArenaVec3f (checks OFF)", warmup, iters) { n -> arenaKernel(n, debugChecks = false) }
        results += measure("segment PACKED_12", warmup, iters) { n -> segmentKernel(n, Vec3StorageLayout.PACKED_12) }
        results += measure("segment ALIGNED_16", warmup, iters) { n -> segmentKernel(n, Vec3StorageLayout.ALIGNED_16) }
        results += measure("heap Vector3d (reused dest)", warmup, iters, ::doubleReuseKernel)

        report("Vector3 kernel (add, scalar-mul, cross, dot, normalize)", results, iters)

        // The float backends run identical arithmetic; their checksums must agree bit-for-bit.
        val floatChecks = results.dropLast(1).map { it.checksum }
        val agree = floatChecks.all { it == floatChecks.first() }
        println("float backends checksum agreement: ${if (agree) "OK (${floatChecks.first()})" else "MISMATCH $floatChecks"}")
        println()

        val ctor = mutableListOf<Row>()
        ctor += measure("Vector3f(x, y, z)", warmup, iters, ::ctorRawKernel)
        ctor += measure("vec(x, y, z)", warmup, iters, ::ctorVecKernel)
        ctor += measure("Vector3f.of(x, y, z)  [literal]", warmup, iters, ::ctorLiteralKernel)
        report("Construction", ctor, iters)

        check(agree) { "Float backends disagree — a storage lowering is wrong, not just slow." }
        println("Generated linalg microbenchmark completed")
    }

    private data class Row(val name: String, val nanos: Long, val gcCount: Long, val checksum: Double)

    private fun measure(name: String, warmup: Int, iters: Int, kernel: (Int) -> Double): Row {
        kernel(warmup)
        val gcBefore = gcCount()
        val start = System.nanoTime()
        val checksum = kernel(iters)
        val elapsed = System.nanoTime() - start
        return Row(name, elapsed, gcCount() - gcBefore, checksum)
    }

    private fun report(title: String, rows: List<Row>, iters: Int) {
        val baseline = rows.first().nanos.toDouble()
        println(title)
        println("  %-34s %12s %10s %8s  %s".format("backend", "ns/op", "vs base", "GCs", "checksum"))
        rows.forEach { row ->
            val nsPerOp = row.nanos.toDouble() / iters
            val ratio = row.nanos / baseline
            println(
                "  %-34s %12.3f %9.2fx %8d  %s".format(
                    row.name, nsPerOp, ratio, row.gcCount, "%.4g".format(row.checksum)
                )
            )
        }
        println()
    }

    private fun gcCount(): Long =
        ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(it.collectionCount, 0) }

    // --- kernels: identical math, different storage ---------------------------------------

    private fun heapAllocKernel(n: Int): Double {
        val a = Vector3f(1f, 2f, 3f)
        val b = Vector3f(4f, 5f, 6f)
        var acc = 0.0
        for (i in 0 until n) {
            a.x += 1e-3f * (i and 1023)
            val c = a.add(b, Vector3f()).mul(SCALE, Vector3f())
            val d = a.cross(b, Vector3f())
            val cn = c.normalize(Vector3f())
            acc += (cn.dot(b) + d.x).toDouble()
        }
        return acc
    }

    private fun heapReuseKernel(n: Int): Double {
        val a = Vector3f(1f, 2f, 3f)
        val b = Vector3f(4f, 5f, 6f)
        val c = Vector3f()
        val d = Vector3f()
        var acc = 0.0
        for (i in 0 until n) {
            a.x += 1e-3f * (i and 1023)
            a.add(b, c); c.mul(SCALE, c)
            a.cross(b, d)
            c.normalize(c)
            acc += (c.dot(b) + d.x).toDouble()
        }
        return acc
    }

    private fun doubleReuseKernel(n: Int): Double {
        val a = Vector3d(1.0, 2.0, 3.0)
        val b = Vector3d(4.0, 5.0, 6.0)
        val c = Vector3d()
        val d = Vector3d()
        var acc = 0.0
        for (i in 0 until n) {
            a.x += 1e-3 * (i and 1023)
            a.add(b, c); c.mul(SCALE_D, c)
            a.cross(b, d)
            c.normalize(c)
            acc += c.dot(b) + d.x
        }
        return acc
    }

    private fun arenaKernel(n: Int, debugChecks: Boolean): Double {
        var acc = 0.0
        FrameArena(256, debugChecks = debugChecks).use { arena ->
            arena.scoped {
                val a = vec3(1f, 2f, 3f)
                val b = vec3(4f, 5f, 6f)
                val c = vec3()
                val d = vec3()
                for (i in 0 until n) {
                    a.x += 1e-3f * (i and 1023)
                    a.add(b, c); c.mul(SCALE, c)
                    a.cross(b, d)
                    c.normalize(c)
                    acc += (c.dot(b) + d.x).toDouble()
                }
            }
        }
        return acc
    }

    private fun segmentKernel(n: Int, layout: Vec3StorageLayout): Double {
        var acc = 0.0
        val buffer = SegmentVec3Buffer.of(ByteBuffer.allocateDirect(4 * 16), 4, layout)
        with(buffer) {
            val a = buffer[0]; val b = buffer[1]; val c = buffer[2]; val d = buffer[3]
            a.set(1f, 2f, 3f); b.set(4f, 5f, 6f)
            for (i in 0 until n) {
                a.x += 1e-3f * (i and 1023)
                a.add(b, c); c.mul(SCALE, c)
                a.cross(b, d)
                c.normalize(c)
                acc += (c.dot(b) + d.x).toDouble()
            }
        }
        return acc
    }

    // --- construction kernels -------------------------------------------------------------

    private fun ctorRawKernel(n: Int): Double {
        var acc = 0.0
        for (i in 0 until n) {
            val v = Vector3f(i.toFloat(), (i + 1).toFloat(), (i + 2).toFloat())
            acc += v.x.toDouble()
        }
        return acc
    }

    private fun ctorVecKernel(n: Int): Double {
        var acc = 0.0
        for (i in 0 until n) {
            val v = vec(i, i + 1, i + 2)
            acc += v.x.toDouble()
        }
        return acc
    }

    private fun ctorLiteralKernel(n: Int): Double {
        var acc = 0.0
        for (i in 0 until n) {
            val v = Vector3f.of(i, i + 1, i + 2)
            acc += v.x.toDouble()
        }
        return acc
    }
}

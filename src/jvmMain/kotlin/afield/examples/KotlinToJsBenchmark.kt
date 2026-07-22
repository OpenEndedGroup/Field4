package afield.examples

import afield.remotejs.KotlinToJsCompiler
import afield.remotejs.KotlinToJsCompiler.Profile
import java.nio.file.Files

/*
FIELD_TEST
id: remotejs.kotlin-to-js-benchmark
runner: jvm-main
entrypoint: afield.examples.KotlinToJsBenchmark
tags: [remotejs, kotlinjs, benchmark]
timeoutSec: 300
expect:
  exitCode: 0
  stdoutContains:
    - "KotlinToJsBenchmark ok"
*/

/**
 * Measures warm, in-process compile latency for the two output profiles, to answer whether the
 * whole-program DCE pass (PLAIN_DCE) adds meaningful per-edit latency versus per-module ES output
 * that ships the stdlib once (ESM_SHARED). Prints median ms + output size for a trivial and a
 * medium program. Not a pass/fail latency gate — just numbers.
 */
object KotlinToJsBenchmark {

    private val TRIVIAL = """
        external val console: dynamic
        fun main() { console.log("hi") }
    """.trimIndent()

    // Exercises more of the stdlib (collections, sequences, math, strings) so DCE has real work.
    private val MEDIUM = """
        external val console: dynamic
        data class P(val x: Double, val y: Double) {
            operator fun plus(o: P) = P(x + o.x, y + o.y)
            operator fun times(k: Double) = P(x * k, y * k)
            fun len() = kotlin.math.sqrt(x * x + y * y)
        }
        fun ring(n: Int, r: Double): List<P> =
            (0 until n).map { i ->
                val a = 2.0 * kotlin.math.PI * i / n
                P(kotlin.math.cos(a) * r, kotlin.math.sin(a) * r)
            }
        fun main() {
            val pts = ring(64, 10.0)
            val centroid = pts.reduce { a, b -> a + b } * (1.0 / pts.size)
            val total = pts.asSequence().map { (it + centroid).len() }.sum()
            val labels = pts.mapIndexed { i, p -> "p" + i + "=" + p.x.toString().take(5) }
            console.log("centroid=" + centroid + " total=" + total + " " + labels.first())
        }
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val iters = 8

        // Prove both profiles produce working output first.
        for (p in Profile.entries) {
            val r = KotlinToJsCompiler.compile(MEDIUM, moduleName = "warm_${p.name.lowercase()}", profile = p)
            require(r.success && r.js != null) { "$p failed to compile MEDIUM:\n${r.rawLog.takeLast(1500)}" }
            require(r.mode == KotlinToJsCompiler.Mode.IN_PROCESS) { "$p fell back to ${r.mode}" }
        }

        println("profile        program   medianMs   minMs    outBytes   (warm, in-process, n=$iters)")
        for (program in listOf("trivial" to TRIVIAL, "medium" to MEDIUM)) {
            for (p in Profile.entries) {
                val times = ArrayList<Long>(iters)
                var bytes = 0
                repeat(iters) { k ->
                    val t0 = System.nanoTime()
                    val r = KotlinToJsCompiler.compile(
                        program.second, moduleName = "bench_${program.first}_${p.name.lowercase()}_$k", profile = p,
                    )
                    val dtMs = (System.nanoTime() - t0) / 1_000_000
                    require(r.success) { "compile failed mid-benchmark ($p/${program.first})" }
                    times.add(dtMs)
                    bytes = r.js!!.length
                }
                times.sort()
                println("%-14s %-9s %8d   %6d   %8d".format(p.name, program.first, times[times.size / 2], times.first(), bytes))
            }
        }

        // The latency lever: a persistent IC cache, re-editing ONE box (stable module name) so both
        // the stdlib cache and the box cache are reused. Iteration 0 populates; report warm median.
        // NOTE: IC and DCE are mutually exclusive — the IC path ships the full stdlib. So the
        // interesting combination is ESM_SHARED+IC: fast compile AND a tiny box module (stdlib is
        // shipped to the head once, separately), which is what emitSharedStdlibMjs() is for.
        for (p in listOf(Profile.PLAIN_DCE, Profile.ESM_SHARED)) {
            val cacheDir = Files.createTempDirectory("field-ktjs-iccache").toFile()
            try {
                val warm = ArrayList<Long>(iters)
                var firstMs = 0L
                var bytes = 0
                repeat(iters + 1) { k ->
                    val t0 = System.nanoTime()
                    val r = KotlinToJsCompiler.compile(
                        MEDIUM, moduleName = "bench_cached_box", profile = p, cacheDir = cacheDir,
                    )
                    val dtMs = (System.nanoTime() - t0) / 1_000_000
                    require(r.success) { "cached compile failed ($p): ${r.rawLog.takeLast(1200)}" }
                    if (k == 0) firstMs = dtMs else warm.add(dtMs)
                    bytes = r.js!!.length   // ESM_SHARED: this is the BOX module only (stdlib shipped once)
                }
                warm.sort()
                println("%-14s %-9s %8d   %6d   %8d   (cold populate=${firstMs}ms)".format("${p.name}+IC", "medium", warm[warm.size / 2], warm.first(), bytes))
            } finally {
                cacheDir.deleteRecursively()
            }
        }

        println("KotlinToJsBenchmark ok")
    }
}

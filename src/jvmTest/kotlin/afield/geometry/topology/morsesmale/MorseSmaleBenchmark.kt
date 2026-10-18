package afield.geometry.topology.morsesmale

import afield.media.image.Img
import afield.media.image.gaussianBlur
import afield.media.image.imgFromFile
import afield.media.image.luma
import afield.media.image.resize
import kotlin.test.Test

/**
 * Stage timings at 512², 1024² and 2048². Off by default; run with
 *
 *     AFIELD_MORSE_SMALE_BENCHMARK=1 ./gradlew jvmTest --tests 'afield.geometry.topology.morsesmale.MorseSmaleBenchmark'
 *
 * and read the table it prints (also written to build/morse-smale-benchmark.md).
 */
class MorseSmaleBenchmark {
    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6

    @Test
    fun stages() {
        if (System.getenv("AFIELD_MORSE_SMALE_BENCHMARK") != "1" || !MorseSmaleLibrary.isAvailable) {
            println("SKIPPED Morse–Smale benchmark (set AFIELD_MORSE_SMALE_BENCHMARK=1; ${MorseSmaleLibrary.describe()})")
            return
        }
        val photo = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3)
        val rows = mutableListOf(
            "| field | size | preprocess | gradient | separatrices | hierarchy | persistence | simplify (median of 5 thresholds) | network | FLines | critical points | surviving at 1% |",
            "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        )
        // warm up the JIT and the native library
        MorseSmale.compute(Img(64, 64, 1, FloatArray(64 * 64) { (it * 7919 % 64).toFloat() }), 0.0)
        for (size in listOf(512, 1024, 2048)) {
            for (kind in listOf("photo luminance, blur 2", "white noise")) {
                var t = System.nanoTime()
                val field = if (kind.startsWith("photo")) photo.resize(size, size).luma().gaussianBlur(2.0)
                else java.util.Random(1).let { r -> Img(size, size, 1, FloatArray(size * size) { r.nextFloat() }) }
                val pre = ms(t)
                MorseSmale.build(field).use { c ->
                    val range = field.data.max() - field.data.min()
                    val simplifyMs = listOf(0.001, 0.005, 0.01, 0.05, 0.1).map { p ->
                        t = System.nanoTime(); c.simplify(p * range); ms(t)
                    }.sorted()[2]
                    val r = c.simplify(0.01 * range)
                    val tm = c.timings
                    // Past about a million separatrices the Kotlin objects outgrow the 4 GB test heap.
                    val (net, fl) = if (r.separatrixCount > 1_000_000) "skipped" to "skipped" else {
                        t = System.nanoTime()
                        r.network
                        val n = ms(t)
                        t = System.nanoTime()
                        r.toFLines()
                        "%.0f".format(n) to "%.0f".format(ms(t))
                    }
                    rows += "| $kind | $size² | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | $net | $fl | ${c.criticalPoints.size} | ${r.criticalPoints.size} (${r.separatrixCount} curves) |"
                        .format(pre, tm.gradientMs, tm.separatricesMs, tm.hierarchyMs, tm.persistenceMs, simplifyMs)
                    println(rows.last())
                }
            }
        }
        val table = rows.joinToString("\n")
        println(table)
        java.io.File("build/morse-smale-benchmark.md").writeText(table + "\n")
    }
}

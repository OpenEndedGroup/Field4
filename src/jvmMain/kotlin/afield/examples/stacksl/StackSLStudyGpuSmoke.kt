package afield.examples.stacksl

import afield.stdlib.StudyEvidence
import afield.stacksl.cpu.AgreementTier
import afield.stacksl.cpu.GalleryAgreement
import afield.stacksl.cpu.StackSLCpu
import afield.stacksl.cpu.StackSLCpuGallery
import afield.stacksl.study.CpuStackSLRasterizer
import afield.stacksl.study.StackSLRasterizer
import afield.stacksl.study.StackSLStudyShader
import afield.stacksl.study.renderAll
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/*
FIELD_TEST
id: stacksl.study.gpu-agreement
runner: backdoor-box
graphicsContext: true
tags: [stacksl, study, gpu, graphics]
timeoutSec: 180
execution: launch
waitSec: 40.0
expect:
  allowMissingOutput: true
  image:
    path: /tmp/stacksl-study-gpu-agreement.png
*/
private val GPU_AGREEMENT_SNIPPET = """
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.examples.stacksl.StackSLStudyGpuSmoke
import afield.stacksl.study.StackSLGpuRasterizer
import java.util.concurrent.CompletableFuture

// The GPU study renderer must wait on frames the main thread draws, so it runs off that thread.
val renderer: Renderer = fromContext()
val sheet = java.io.File("/tmp/stacksl-study-gpu-agreement.png").also { it.delete() }
val work = CompletableFuture.supplyAsync { StackSLStudyGpuSmoke.run(StackSLGpuRasterizer({ renderer }), sheet) }
loop { if (work.isDone) println(work.get()) }
""".trimIndent()

/**
 * Proof that a StackSL study's GPU frames are the program's frames: the same programs rendered by
 * [afield.stacksl.study.StackSLGpuRasterizer] on a live renderer and by the CPU reference. Exact
 * arithmetic, smooth-transcendental tiers, and the 2D fBm palette have hard bounds; the 4D fbm4
 * hash comparison remains diagnostic because tiny differences in its warped coordinates can
 * cross hash lattice boundaries. `uv saturate` must be bit-identical, which catches a flipped image.
 *
 * It also times an observation-sized render (four 256×256 frames of a five-octave volumetric program),
 * since the reason the GPU path exists is that the CPU reference takes minutes for it.
 *
 * [run] writes the evidence sheet — GPU above, CPU below — after the hard checks pass, retaining
 * remaining hash21 differences as visible evidence without treating them as a portable bound.
 */
object StackSLStudyGpuSmoke {

    /** Programs spanning the four single-pass agreement tiers. */
    val PROGRAMS = listOf(
        "uv saturate",
        "uv 0.5 * 0.25 + [1,0.5,0.2] *",
        "p length 0.3 0.05 pulse white *",
        "p polar dup y 0.2t + >y cart length 0.6 * palette saturate",
        "uv 5 * 0.1t + fbm palette saturate",
        "p [3,3,0,0] * t [0,0,0,0.1] * + fbm4 palette saturate"
    )

    private const val DIM = 64
    private val TIMES = listOf(0.0, 1.75)

    /** Compare, time and — if everything holds — write [sheet]. Returns a one-line summary; throws on a failure. */
    fun run(rasterizer: StackSLRasterizer, sheet: File): String {
        val rows = mutableListOf<Pair<IntArray, IntArray>>()
        val lines = PROGRAMS.map { program ->
            val tier = AgreementTier.of(StackSLCpu.compile(program).glsl)
            val gpu = rasterizer.renderAll(program, DIM, TIMES)
            check(rasterizer.description.startsWith("GPU")) { "expected a GPU render, got: ${rasterizer.description}" }
            val cpu = CpuStackSLRasterizer.renderAll(program, DIM, TIMES)
            TIMES.indices.map { i ->
                // Both in 8-bit sRGB, as StackSLCpuGallery.compare measures and the window shows.
                val g = StudyEvidence.displayed(gpu[i]).toSrgbArgb()
                val c = StudyEvidence.displayed(cpu[i])
                rows += g to StackSLCpuGallery.galleryArgb(c)
                val agreement = StackSLCpuGallery.compare(program, tier, c, g, DIM)
                holds(program, tier, agreement)
                agreement
            }.maxBy { it.meanLevels }
        }
        val heavy = PROGRAMS.last()
        val start = System.nanoTime()
        rasterizer.renderAll(heavy, 256, listOf(0.0, 3.5, 7.0, 10.5))
        val seconds = (System.nanoTime() - start) / 1e9
        check(seconds < 10.0) { "four 256x256 frames took ${"%.1f".format(seconds)}s on the GPU; the point of the GPU path is that they do not" }
        writeSheet(rows, sheet)
        return "stacksl study GPU agreement: ${PROGRAMS.size} programs at ${TIMES.size} times; " +
            "exact, smooth, and 2D fBm tiers hold bounds; 4D fbm4 hash remains diagnostic; " +
            "four 256x256 frames of '$heavy' in ${"%.2f".format(seconds)}s on ${rasterizer.description}; " +
            PROGRAMS.zip(lines).joinToString("; ") { (program, agreement) ->
                val label = when {
                    agreement.tier != AgreementTier.HASH21_AMPLIFIED -> agreement.tier.label
                    program == "uv 5 * 0.1t + fbm palette saturate" -> "2D fBm"
                    else -> "${agreement.tier.label} (4D diagnostic)"
                }
                "$label mean=${"%.3f".format(agreement.meanLevels)} max=${agreement.maxLevels}"
            }
    }

    /** The bounds `StackSLCpuGalleryTest` asserts between the CPU backend and committed GPU stills. */
    private fun holds(program: String, tier: AgreementTier, a: GalleryAgreement) {
        if (program == "uv saturate") check(a.maxLevels == 0) { "uv saturate must be bit-identical on the GPU (a flip or an offset): $a" }
        when (tier) {
            AgreementTier.EXACT_OPS, AgreementTier.SMOOTH_TRANSCENDENTAL ->
                check(a.maxLevels <= 1) { "$tier programs agree to within a level: $a" }
            AgreementTier.HASH21_AMPLIFIED -> when (program) {
                "uv 5 * 0.1t + fbm palette saturate" -> check(a.meanLevels < 0.1 && a.maxLevels <= 1) {
                    "2D fBm should remain visually CPU/GPU-equivalent: $a"
                }
                // Keep fbm4 as evidence only until its 4D arithmetic is stabilized separately.
                else -> Unit
            }
            AgreementTier.HASH11_UNBOUNDED, AgreementTier.FEEDBACK -> Unit
        }
    }

    private fun writeSheet(rows: List<Pair<IntArray, IntArray>>, sheet: File) {
        val image = BufferedImage(rows.size * (DIM + 4), 2 * DIM + 4, BufferedImage.TYPE_INT_RGB)
        rows.forEachIndexed { i, (gpu, cpu) ->
            image.setRGB(i * (DIM + 4), 0, DIM, DIM, gpu, 0, DIM)
            image.setRGB(i * (DIM + 4), DIM + 4, DIM, DIM, cpu, 0, DIM)
        }
        sheet.absoluteFile.parentFile.mkdirs()
        ImageIO.write(image, "png", sheet)
    }
}

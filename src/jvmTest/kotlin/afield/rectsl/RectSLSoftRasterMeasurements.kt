package afield.rectsl

import afield.Color4
import afield.stdlib.Functions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The full bias sweep, and the figures — the opt-in measurement fixture behind
 * `dev_docs/RECTSL_SOFT_RASTER.md`.
 *
 * `RectSLSoftRasterTest` is the *ratchet*: a handful of rows with two-sided floors, fast enough to sit
 * in `check`. This is the *survey*: every kernel against every band width against every geometry, plus
 * a pass over the real gallery corpus, printed as tables a docs page can quote. It matches
 * `build.gradle.kts`'s `Measurements.class` include pattern and therefore runs only under
 * `./gradlew jvmMeasurements`, deliberately out of the deterministic suite — the gallery pass in
 * particular is minutes, not seconds.
 *
 *     ./gradlew jvmMeasurements --tests '*RectSLSoftRasterMeasurements*' \
 *         -Dafield.rectsl.softRasterOut=dev_docs/assets/rectsl_soft_raster
 *
 * Without that property the figures go to a temp directory and nothing in the repository is touched,
 * which is the rule this project holds tests to: a test that writes a committed asset can make a
 * stale asset look fresh.
 */
class RectSLSoftRasterMeasurements {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    /** Where figures go. A repository path only when the caller asks for one by name. */
    private val outDir: Path by lazy {
        val requested = System.getProperty("afield.rectsl.softRasterOut")
        val dir = if (requested == null) {
            Files.createTempDirectory("rectsl-soft-raster")
        } else {
            repoRoot().resolve(requested).also { Files.createDirectories(it) }
        }
        println("figures -> $dir")
        dir
    }

    private val PIXEL = 16.0 / 128.0
    private val JITTER_X = 0.37 * PIXEL
    private val JITTER_Y = 0.21 * PIXEL

    /**
     * Obliquity bias against band width, for all three kernels.
     *
     * The interesting result is not monotone in `beta`: the clamped ramp is *exactly* right at one
     * pixel and worse on both sides of it, because at a narrower band it clips coverage that is really
     * there and at a wider one it spreads coverage that is not.
     */
    @Test
    fun `bias against band width and obliquity`() {
        println("== obliquity x band width, worst edge bias (corners excluded) ==")
        println("kernel      beta/px   0deg      15deg     30deg     45deg     reach/px")
        for (kernel in CoverageKernel.entries) {
            for (betaPixels in listOf(0.5, 1.0, 2.0, 4.0)) {
                val cells = listOf(0.0, 15.0, 30.0, 45.0).map { degrees ->
                    val program = squareScene(Math.toRadians(degrees), 7.83)
                    val field = program.toAdField()
                    measureCoverageBias(field, spec(program, betaPixels, kernel), 0.0)
                }
                println(
                    "%-11s %5.2f    %s  reach=%.2f".format(
                        kernel, betaPixels,
                        cells.joinToString("  ") { "%.3e".format(it.worstEdgeBias) },
                        cells.first().reachInPixels
                    )
                )
                cells.forEach {
                    assertTrue(it.edgePixels >= 50, "only ${it.edgePixels} edge pixels for $kernel at $betaPixels px")
                }
            }
        }
    }

    /** Corner bias against interior angle — the source that turned out to dominate. */
    @Test
    fun `corner bias against angle`() {
        println("== corner bias by rotation, LINEAR, beta = 1px ==")
        for (degrees in 0..45 step 5) {
            val program = squareScene(Math.toRadians(degrees.toDouble()), 7.83)
            val field = program.toAdField()
            val row = measureCoverageBias(field, spec(program, 1.0, CoverageKernel.LINEAR), 0.0)
            println(
                "%3d deg  corner=%.4f (%d px)   edge=%.4e (%d px)".format(
                    degrees, row.worstCornerBias, row.cornerPixels, row.worstEdgeBias, row.edgePixels
                )
            )
            assertTrue(row.partialPixels >= 100, "only ${row.partialPixels} partial pixels at $degrees deg")
        }
    }

    /** Feature-scale bias, finely swept, for both composites. */
    @Test
    fun `bias against feature scale`() {
        println("== feature scale, LINEAR, beta = 1px ==")
        println("width/px  worst      edge       mean       partial")
        for (widthPixels in listOf(0.125, 0.25, 0.375, 0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 4.0, 8.0, 16.0)) {
            val program = wallScene(bar("bar", JITTER_X, JITTER_Y, widthPixels * PIXEL, 10.0 + JITTER_Y))
            val field = program.toAdField()
            val row = measureCoverageBias(field, spec(program, 1.0, CoverageKernel.LINEAR), 0.0)
            println(
                "%8.3f  %.3e  %.3e  %.3e  %d".format(
                    widthPixels, row.worstBias, row.worstEdgeBias, row.meanPartialBias, row.partialPixels
                )
            )
        }
    }

    /** Seam bias against the gap between two rects, from overlapping to clearly separated. */
    @Test
    fun `bias against seam gap`() {
        println("== two rects, gap in pixels, LINEAR beta = 1px ==")
        println("gap/px   union-sdf   over        ratio")
        for (gapPixels in listOf(-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0, 4.0)) {
            val half = 2.0 + 0.5 * gapPixels * PIXEL
            val program = wallScene(
                bar("left", -half + JITTER_X, JITTER_Y, 4.0, 8.0 + JITTER_Y),
                bar("right", half + JITTER_X, JITTER_Y, 4.0, 8.0 + JITTER_Y)
            )
            val field = program.toAdField()
            val union = measureCoverageBias(field, spec(program, 1.0, CoverageKernel.LINEAR, CoverageComposite.UNION_SDF), 0.0)
            val over = measureCoverageBias(field, spec(program, 1.0, CoverageKernel.LINEAR, CoverageComposite.OVER), 0.0)
            println(
                "%6.2f   %.3e   %.3e   %.2f".format(
                    gapPixels, union.worstBias, over.worstBias,
                    if (over.worstBias > 0.0) union.worstBias / over.worstBias else Double.NaN
                )
            )
        }
    }

    /**
     * The bias over the **real** corpus, not over fixtures — the honest end of the measurement.
     *
     * A gallery scene is not built to be measurable: most have alpha tracks, and area coverage is
     * undefined for translucent geometry, so those are counted as refused rather than quietly
     * compared against a different quantity. What survives is reported as a distribution, and the
     * count of what did not is reported beside it.
     */
    @Test
    fun `bias over the gallery corpus`() {
        println("== gallery corpus, LINEAR beta = 1px, 64x64 ==")
        var measured = 0
        var translucent = 0
        var noRect = 0
        val worsts = mutableListOf<Pair<String, Double>>()
        for ((name, source) in gallerySources()) {
            val program = RectAnimationOracle.compile(source).programOrNull ?: continue
            val field = runCatching { program.toAdField() }.getOrNull() ?: run { noRect++; continue }
            val time = program.duration * 0.5
            val spec = SoftRasterSpec(
                wall = program.wall,
                width = 64,
                height = 64,
                beta = program.wall.width / 64.0,
                kernel = CoverageKernel.LINEAR
            )
            val row = runCatching { measureCoverageBias(field, spec, time) }.getOrNull()
            if (row == null) {
                translucent++
                continue
            }
            if (row.partialPixels == 0) continue
            measured++
            worsts += name to row.worstBias
            println(
                "%-22s %s  skipped=%d/%d".format(
                    name, row, field.skipped.size, field.skipped.size + field.rectCount
                )
            )
        }
        val sorted = worsts.map { it.second }.sorted()
        println(
            "measured $measured scenes, $translucent refused as translucent, $noRect with no " +
                "differentiable rect; worst bias median ${sorted.getOrNull(sorted.size / 2)}, " +
                "max ${sorted.lastOrNull()} (${worsts.maxByOrNull { it.second }?.first})"
        )
        assertTrue(measured >= 5, "only $measured gallery scenes were measurable at all")
    }

    /**
     * Writes the figures the docs page embeds, and asserts each one is a picture rather than a
     * uniform field — the check that catches an ENOSPC-truncated or all-black PNG at the point it is
     * produced rather than when a human opens the page.
     */
    @Test
    fun `write the docs figures`() {
        val written = mutableListOf<Path>()

        fun write(name: String, bytes: ByteArray) {
            val path = outDir.resolve(name)
            Files.write(path, bytes)
            assertTrue(Files.size(path) > 2000, "$name is suspiciously small: ${Files.size(path)} bytes")
            written.add(path)
            println("wrote $path (${Files.size(path)} bytes)")
        }

        // 1. The three-panel bias figure at 24 degrees: soft, exact, and the difference.
        run {
            val program = squareScene(Math.toRadians(24.0), 7.83)
            val field = program.toAdField()
            write(
                "rectsl-soft-raster-oblique.png",
                RectSLSoftRasterFigure.renderBias(field, spec(program, 1.5, CoverageKernel.LINEAR), 0.0)
            )
        }
        // 2. The union seam, both composites, against exact — where the worst bias lives.
        run {
            val program = wallScene(
                bar("left", -2.0 + JITTER_X, JITTER_Y, 4.0, 8.0 + JITTER_Y),
                bar("right", 2.0 + JITTER_X, JITTER_Y, 4.0, 8.0 + JITTER_Y)
            )
            val field = program.toAdField()
            val unionSpec = spec(program, 1.0, CoverageKernel.LINEAR, CoverageComposite.UNION_SDF)
            val overSpec = spec(program, 1.0, CoverageKernel.LINEAR, CoverageComposite.OVER)
            val exact = exactRaster(field, unionSpec, 0.0)
            val union = softRaster(field, unionSpec, 0.0)
            val over = softRaster(field, overSpec, 0.0)
            val unionDiff = DoubleArray(exact.size) { abs(union[it] - exact[it]) }
            val overDiff = DoubleArray(exact.size) { abs(over[it] - exact[it]) }
            val peak = maxOf(unionDiff.max(), overDiff.max())
            write(
                "rectsl-soft-raster-seam.png",
                RectSLSoftRasterFigure.renderPanels(
                    listOf(
                        RectSLSoftRasterFigure.Panel("exact area", exact),
                        RectSLSoftRasterFigure.Panel(
                            "min-sdf diff %.3f".format(unionDiff.max()), unionDiff,
                            heat = true, gain = 1.0 / peak
                        ),
                        RectSLSoftRasterFigure.Panel(
                            "over diff %.3f".format(overDiff.max()), overDiff,
                            heat = true, gain = 1.0 / peak
                        )
                    ),
                    unionSpec.width, unionSpec.height
                )
            )
        }
        // 3. The image fit: target, seed, solved. The counterfactual is the picture, not a sentence.
        run {
            val program = fitScene(0.0, 0.0, 3.0)
            val field = program.toAdField()
            val spec = spec(program, 2.0, CoverageKernel.SIGMOID)
            val truth = mapOf("bar-x" to 1.6, "bar-y" to -1.1, "bar-w" to 4.2)
            val target = softRaster(field, spec, 0.0, field.withFreeParameters(truth))
            val seed = softRaster(field, spec, 0.0)
            val seedLoss = softRasterLossAndGradient(field, spec, 0.0, target).loss
            val theta = descend(field, spec, target, 320)
            val solved = softRaster(field, spec, 0.0, theta)
            val finalLoss = softRasterLossAndGradient(field, spec, 0.0, target, theta).loss
            write(
                "rectsl-soft-raster-fit.png",
                RectSLSoftRasterFigure.renderPanels(
                    listOf(
                        RectSLSoftRasterFigure.Panel("target", target),
                        RectSLSoftRasterFigure.Panel("seed loss %.1e".format(seedLoss).replace("+", ""), seed),
                        RectSLSoftRasterFigure.Panel("solved loss %.1e".format(finalLoss).replace("+", ""), solved)
                    ),
                    spec.width, spec.height
                )
            )
            println("fit solved ${field.freeParameters(theta)} against $truth")
        }
        assertTrue(written.size == 3, "expected three figures, wrote ${written.size}")
    }

    /** Adam over the scene's free parameters against a target raster — the fit, factored out. */
    private fun descend(
        field: SceneAdField,
        spec: SoftRasterSpec,
        target: DoubleArray,
        iterations: Int
    ): DoubleArray {
        val names = field.freeParameterNames
        val theta = field.theta.copyOf()
        val m = DoubleArray(names.size)
        val v = DoubleArray(names.size)
        for (iteration in 1..iterations) {
            val step = softRasterLossAndGradient(field, spec, 0.0, target, theta)
            for (n in names.indices) {
                val slots = field.freeParameterSlots.getValue(names[n])
                val g = slots.sumOf { step.gradient[it] }
                if (!g.isFinite()) continue
                m[n] = 0.9 * m[n] + 0.1 * g
                v[n] = 0.999 * v[n] + 0.001 * g * g
                val mHat = m[n] / (1.0 - Math.pow(0.9, iteration.toDouble()))
                val vHat = v[n] / (1.0 - Math.pow(0.999, iteration.toDouble()))
                val update = 0.05 * mHat / (kotlin.math.sqrt(vHat) + 1e-8)
                if (update.isFinite()) for (slot in slots) theta[slot] -= update
            }
        }
        return theta
    }

    private fun spec(
        program: SceneProgram,
        betaPixels: Double,
        kernel: CoverageKernel,
        composite: CoverageComposite = CoverageComposite.UNION_SDF,
        size: Int = 128
    ): SoftRasterSpec = SoftRasterSpec(
        wall = program.wall,
        width = size,
        height = size,
        beta = betaPixels * program.wall.width / size,
        kernel = kernel,
        composite = composite
    )

    private fun bar(
        id: String,
        cx: Double,
        cy: Double,
        w: Double,
        h: Double,
        rotation: Track<Double> = constantTrack(0.0),
        x: Track<Double> = constantTrack(cx),
        y: Track<Double> = constantTrack(cy),
        width: Track<Double> = constantTrack(w)
    ): RectNode = RectNode(
        id = id,
        x = x,
        y = y,
        w = width,
        h = constantTrack(h),
        rotation = rotation,
        scaleX = constantTrack(1.0),
        scaleY = constantTrack(1.0),
        alpha = constantTrack(1.0),
        z = constantTrack(0.0),
        fill = constantPaintTrack(PaintSpec.Solid(Color4(1.0, 1.0, 1.0))),
        material = MaterialBinding(0),
        tags = emptySet()
    )

    private fun wallScene(vararg rects: RectNode): SceneProgram = SceneProgram(
        wall = WallSpec(16.0, 16.0, UnitKind.Meters),
        duration = 1.0,
        fps = 30.0,
        nodes = rects.toList()
    )

    private fun squareScene(rotation: Double, size: Double): SceneProgram =
        wallScene(bar("square", JITTER_X, JITTER_Y, size, size, rotation = constantTrack(rotation)))

    private fun fitScene(offsetX: Double, offsetY: Double, width: Double): SceneProgram = wallScene(
        bar(
            "solved", 0.0, 0.0, width, 5.0 + JITTER_Y,
            x = FreeParamTrack("bar-x", offsetX + JITTER_X),
            y = FreeParamTrack("bar-y", offsetY + JITTER_Y),
            width = FreeParamTrack("bar-w", width)
        )
    )

    private fun gallerySources(): List<Pair<String, String>> =
        Files.list(galleryDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".rectsl") }
                .map { it.fileName.toString().removeSuffix(".rectsl").removeSuffix("_rectsl_") to it.readText() }
                .toList()
        }.sortedBy { it.first }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root")
        }
        return dir
    }
}

package afield.rectsl

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A golden dump of what every gallery program actually samples to.
 *
 * The track IR is the one part of RectSL that both the CPU sampler and (soon) a
 * GLSL backend have to agree on, so it is the part where a "harmless" refactor
 * can quietly move a rectangle by a thousandth of a wall unit and nobody
 * notices until a docs capture churns. This test pins the sampled scene of the
 * whole gallery — every rect's position, size, rotation, scale, alpha, z, and
 * fill, at times spread across (and past) each program's duration — to full
 * double precision, so a change of even one ulp fails loudly.
 *
 * Regenerate deliberately, never reflexively:
 *
 *     ./gradlew jvmTest --tests '*RectSLTrackCharacterization*' \
 *         -Dafield.rectsl.characterization=write
 *
 * and read the diff before committing it. A diff here means sampling changed.
 */
class RectSLTrackCharacterizationTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")
    private val goldenPath: Path =
        repoRoot().resolve("src/jvmTest/resources/rectsl/track_characterization.txt")

    @Test
    fun `gallery programs sample identically`() {
        val actual = dumpGallery()
        val write = System.getProperty("afield.rectsl.characterization") == "write"

        // Read the golden off the classpath, not off disk: that makes it a
        // declared input of the test task, so editing it re-runs the test
        // instead of leaving a stale UP-TO-DATE pass.
        val onClasspath = javaClass.getResource("/rectsl/track_characterization.txt")

        if (write || onClasspath == null) {
            goldenPath.parent.createDirectories()
            goldenPath.writeText(actual)
            println("wrote golden: $goldenPath (${actual.lines().size} lines)")
            return
        }

        val expected = onClasspath.readText()
        if (expected != actual) {
            val diff = firstDifference(expected, actual)
            Files.write(repoRoot().resolve("build/rectsl_characterization_actual.txt"), actual.toByteArray())
            assertEquals(
                expected,
                actual,
                "sampled scenes changed — first difference:\n$diff\n" +
                    "full actual written to build/rectsl_characterization_actual.txt"
            )
        }
    }

    /**
     * The gallery is the corpus, so an empty or half-loaded gallery must not read
     * as a pass — that is exactly how a characterization test rots into a no-op.
     */
    @Test
    fun `gallery corpus is present and compiles`() {
        val programs = gallerySources()
        assertTrue(programs.size >= 30, "expected the full rectsl gallery, found ${programs.size}")
        val failures = programs.mapNotNull { (name, source) ->
            val result = RectAnimationOracle.compile(source)
            if (result.programOrNull == null) "$name: ${result.diagnostics.joinToString("; ")}" else null
        }
        // `inline-functions` needs a live Kotlin engine for its {{ }} escapes and
        // cannot compile in a plain unit test; everything else must.
        val unexpected = failures.filterNot { it.startsWith("inline-functions") }
        assertTrue(unexpected.isEmpty(), "gallery programs failed to compile:\n${unexpected.joinToString("\n")}")
    }

    private fun gallerySources(): List<Pair<String, String>> =
        Files.list(galleryDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".rectsl") }
                .map { it.fileName.toString().removeSuffix(".rectsl").removeSuffix("_rectsl_") to it.readText() }
                .toList()
        }.sortedBy { it.first }

    private fun dumpGallery(): String = buildString {
        for ((name, source) in gallerySources()) {
            val program = RectAnimationOracle.compile(source).programOrNull
            if (program == null) {
                appendLine("# $name: does not compile without a Kotlin engine")
                continue
            }
            appendLine("# $name  duration=${program.duration} fps=${program.fps} nodes=${program.nodes.size}")
            for (time in sampleTimes(program.duration)) {
                val scene = program.sample(time)
                for (rect in scene.rects) {
                    appendLine(
                        listOf(
                            name,
                            time.toString(),
                            rect.id,
                            rect.center.x.toString(),
                            rect.center.y.toString(),
                            rect.size.x.toString(),
                            rect.size.y.toString(),
                            rect.rotation.toString(),
                            rect.scale.x.toString(),
                            rect.scale.y.toString(),
                            rect.alpha.toString(),
                            rect.z.toString(),
                            describeFill(rect.fill)
                        ).joinToString("\t")
                    )
                }
            }
        }
    }

    /**
     * 41 times spread over 1.25x the duration: dense enough to land inside every
     * key segment, and deliberately past the end so time warps, gates, and
     * `->live` windows are exercised outside their nominal range too.
     */
    private fun sampleTimes(duration: Double): List<Double> {
        val span = (if (duration > 0.0) duration else 1.0) * 1.25
        return (0..40).map { it * span / 40.0 }
    }

    private fun describeFill(paint: PaintSpec): String = when (paint) {
        is PaintSpec.Solid -> "solid(${paint.color.r},${paint.color.g},${paint.color.b},${paint.color.a})"
        is PaintSpec.LinearGradient ->
            "linear(${paint.c0.r},${paint.c0.g},${paint.c0.b},${paint.c0.a};" +
                "${paint.c1.r},${paint.c1.g},${paint.c1.b},${paint.c1.a};" +
                "${paint.p0.x},${paint.p0.y};${paint.p1.x},${paint.p1.y};${paint.space})"
    }

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) {
            val el = e.getOrNull(i)
            val al = a.getOrNull(i)
            if (el != al) return "line ${i + 1}\n  expected: $el\n  actual:   $al"
        }
        return "(files differ only in trailing content)"
    }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root from ${Path.of("").toAbsolutePath()}")
        }
        return dir
    }
}

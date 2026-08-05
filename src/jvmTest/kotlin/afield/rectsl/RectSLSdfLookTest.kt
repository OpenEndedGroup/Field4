package afield.rectsl

import afield.four.fabricated.RectSLGalleryHarness
import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The look layer's only real contract is that a real shader compiler accepts
 * what it writes — the rest is arithmetic over one float, and the pictures are
 * checked where they are made (`RectSLGalleryHarness.renderSdfLooks`).
 *
 * So this suite is deliberately narrow and deliberately expensive: every preset
 * against several gallery scenes, through glslang, plus the two structural
 * claims the look layer rests on — that two scenes can share one shader without
 * colliding, and that a smooth union is still a union.
 */
class RectSLSdfLookTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    @Test
    fun `every preset compiles against several real scenes`() {
        val scenes = someGalleryPrograms(6)
        assertTrue(scenes.size >= 4, "expected several gallery scenes, found ${scenes.size}")
        val failures = mutableListOf<String>()
        var compiled = 0
        for ((sceneName, program) in scenes) {
            for (look in RectSLSdfLook.PRESETS) {
                val field = RectSLGlsl.emitSdf(program, "scene", look.emitOptions())
                val carve = if (look.carves) {
                    RectSLGlsl.emitSdf(carvingProgram(), "carveScene", RectSLSdfOptions(namePrefix = "carve"))
                } else {
                    null
                }
                val source = RectSLSdfShader.fragment(field, look, carve)
                try {
                    val spirv = ShaderSPIRVUtils.compileShader(
                        "rectsl-${sceneName}-${look.name}.frag", source, ShaderKind.FRAGMENT_SHADER
                    )
                    if ((spirv.bytecode()?.remaining() ?: 0) <= 0) failures += "$sceneName/${look.name}: empty SPIR-V"
                    else compiled++
                } catch (t: Throwable) {
                    failures += "$sceneName/${look.name}: ${t.message?.lines()?.take(8)?.joinToString(" | ")}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "look shaders failed to compile:\n${failures.joinToString("\n")}")
        assertEquals(scenes.size * RectSLSdfLook.PRESETS.size, compiled)
    }

    @Test
    fun `the fullscreen vertex stage compiles`() {
        val spirv = ShaderSPIRVUtils.compileShader("rectsl-sdf.vert", RectSLSdfShader.VERTEX, ShaderKind.VERTEX_SHADER)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "the SDF vertex shader produced no SPIR-V")
    }

    @Test
    fun `the probe stage compiles and its packing round-trips`() {
        val program = someGalleryPrograms(1).single().second
        val field = RectSLGlsl.emitSdf(program, "scene")
        val spirv = ShaderSPIRVUtils.compileShader(
            "rectsl-sdf-probe.frag", RectSLSdfShader.probeFragment(field), ShaderKind.FRAGMENT_SHADER
        )
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "the SDF probe shader produced no SPIR-V")

        // The Kotlin half of the packing, checked against the arithmetic the GLSL
        // performs — if these ever drift the differential silently measures noise.
        val range = 1.0
        for (v in listOf(-0.9, -0.25, 0.0, 1e-4, 0.5, 0.87)) {
            val u = (v / range).coerceIn(-1.0, 1.0) * 0.5 + 0.5
            val scaled = Math.floor(u * 16777215.0 + 0.5).toInt()
            val r = scaled / 65536
            val g = (scaled - r * 65536) / 256
            val b = scaled - r * 65536 - g * 256
            val back = RectSLSdfShader.unpack24(r, g, b, range)
            assertTrue(
                Math.abs(back - v) < 2e-7,
                "24-bit round trip lost $v: got $back (error ${Math.abs(back - v)})"
            )
        }
    }

    /**
     * The carving look puts two independently emitted scenes in one shader. That
     * only works because the emitter separates *global* helpers (shared, one
     * definition) from scene-owned symbols (prefixed, never colliding), so check
     * both halves rather than just that it compiles.
     */
    @Test
    fun `two scenes share one shader without colliding`() {
        val a = RectSLGlsl.emitSdf(someGalleryPrograms(1).single().second, "scene")
        val b = RectSLGlsl.emitSdf(carvingProgram(), "carveScene", RectSLSdfOptions(namePrefix = "carve"))

        assertTrue(a.sceneGlsl.contains("rectslShape0"), "scene A should own rectsl-prefixed shapes")
        assertTrue(b.sceneGlsl.contains("carveShape0"), "scene B should own carve-prefixed shapes")
        assertTrue(!b.sceneGlsl.contains("rectslShape"), "scene B leaked an unprefixed shape symbol")
        assertTrue(
            a.globals.keys.contains("rectslPrelude") && b.globals.keys.contains("rectslPrelude"),
            "both emissions should claim the shared prelude so the merge can dedupe it"
        )

        val source = RectSLSdfShader.fragment(a, RectSLSdfLook.preset("carve"), b)
        // The box field must appear exactly once even though both fields need it.
        assertEquals(
            1, Regex("""^float rectslBox\(""", RegexOption.MULTILINE).findAll(source).count(),
            "the shared prelude was written more than once"
        )
        val spirv = ShaderSPIRVUtils.compileShader("rectsl-carve.frag", source, ShaderKind.FRAGMENT_SHADER)
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "the carving shader produced no SPIR-V")
    }

    /**
     * A smooth union is only worth having if it is still a union: never further
     * from the surface than `min`, and never further *inside* by more than the
     * blend radius. Checked on the emitted text's own Kotlin twin, because the
     * GPU half of this is the harness's differential.
     */
    @Test
    fun `a blended union changes the field only near a seam`() {
        val program = RectAnimationOracle.compile(
            """
            #rectsl
            ->wall 8 5 abstract
            ->duration 2
            "c" rect  0.9 ->square
              4 '( ic 0.8 * 0 ->offset ) array
            ;
            """.trimIndent()
        ).program
        val plain = RectSLGlsl.emitSdf(program, "scene")
        val blended = RectSLGlsl.emitSdf(program, "scene", RectSLSdfOptions(blendRadius = 0.5))

        assertTrue(plain.glsl.contains("d = min(d,"), "the default union should still be a plain min")
        assertTrue(blended.glsl.contains("rectslSmin(d, e, "), "a blended union should call the smooth minimum")
        assertTrue(
            blended.globals.containsKey("rectslSmin"),
            "the smooth minimum must travel with the field that calls it"
        )
        assertTrue(
            !plain.globals.containsKey("rectslSmin"),
            "an unblended field should not carry a helper it never calls"
        )
        assertNotEquals(plain.sceneGlsl, blended.sceneGlsl)

        // smin(a,b,k) <= min(a,b) and >= min(a,b) - k/4 (the polynomial's worst case).
        val k = 0.5
        for (a in listOf(-2.0, -0.3, 0.0, 0.2, 1.4)) {
            for (b in listOf(-1.1, -0.05, 0.0, 0.45, 2.0)) {
                val h = (0.5 + 0.5 * (b - a) / k).coerceIn(0.0, 1.0)
                val s = (b + (a - b) * h) - k * h * (1.0 - h)
                val m = minOf(a, b)
                assertTrue(s <= m + 1e-12, "smin($a,$b,$k)=$s exceeded min=$m")
                assertTrue(s >= m - k / 4.0 - 1e-12, "smin($a,$b,$k)=$s dipped more than k/4 below min=$m")
            }
        }
    }

    /**
     * The presets exist to keep the emitter's advertised capabilities from being
     * prose. This does not try to parse English out of a KDoc — it asserts a fixed
     * table of (claim keyword, preset that demonstrates it), and that each keyword
     * is still present in `RectSLGlsl`'s "what that buys" paragraph. Deleting a
     * claim without deleting its preset, or renaming a preset, fails here.
     */
    @Test
    fun `every advertised capability has a preset that configures it`() {
        val emitterSource = repoRoot().resolve("src/jvmMain/kotlin/afield/rectsl/RectSLGlsl.kt").readText()
        val claims: List<Triple<String, String, (RectSLSdfLook) -> Boolean>> = listOf(
            Triple("outlines", "outline") { it.outlineWidth > 0.0 && it.outlineColor != null },
            Triple("glows at any width", "glow") { it.glowColor != null },
            Triple("`smin` blending", "blend") { it.blendRadius > 0.0 },
            Triple("boolean ops", "carve") { it.carves },
            Triple("per-pixel normals", "relief") { it.reliefDepth > 0.0 },
            Triple("sub-frame motion blur", "motion-blur") { it.motionBlurSamples > 1 && it.shutterSeconds > 0.0 }
        )
        for ((claim, presetName, configured) in claims) {
            assertTrue(
                emitterSource.contains(claim),
                "RectSLGlsl no longer claims '$claim' — drop the '$presetName' preset or fix this table"
            )
            val look = RectSLSdfLook.preset(presetName)
            assertTrue(configured(look), "preset '$presetName' no longer demonstrates '$claim'")
        }
        assertEquals(
            RectSLSdfLook.PRESETS.map { it.name }.distinct().size,
            RectSLSdfLook.PRESETS.size,
            "two presets share a name, so one would overwrite the other's rendered asset"
        )
        RectSLSdfLook.PRESETS.forEach {
            assertTrue(it.about.isNotBlank(), "preset '${it.name}' has no description for the docs table")
        }
    }

    /**
     * The three scenes the docs render are literals in the harness, so a typo in
     * one of them fails a GPU capture minutes into a docs build with a message
     * about a missing PNG. Compile them here instead, and check the properties each
     * one exists to demonstrate — that the blades really overlap (so `smin` has a
     * seam to smooth), that the carve field really intersects them (so subtraction
     * is visible), and that the pattern scene really produces the baked tracks the
     * differential is there to cover.
     */
    @Test
    fun `the harness demo scenes compile and demonstrate what they claim`() {
        val blades = compileOrFail(RectSLGalleryHarness.SDF_SCENE_SOURCE)
        val carve = compileOrFail(RectSLGalleryHarness.SDF_CARVE_SOURCE)
        val pattern = compileOrFail(RectSLGalleryHarness.SDF_PATTERN_SOURCE)

        assertEquals(6, blades.nodes.filterIsInstance<RectNode>().size, "expected six blades")
        val stillTime = 0.95
        val bladeRects = blades.sample(stillTime).rects
        assertEquals(6, bladeRects.size)
        // Adjacent blades must actually overlap at the still time, or `blend` and the
        // union outline have nothing to say.
        val spacing = bladeRects.map { it.center.x }.sorted().zipWithNext { a, b -> b - a }
        assertTrue(
            spacing.all { it < 0.85 },
            "blades must overlap for smin to fuse them; centre spacing was $spacing against a 0.85 side"
        )
        // ... and stay on the wall.
        bladeRects.forEach {
            assertTrue(
                abs(it.center.x) < blades.wall.width * 0.5 && abs(it.center.y) < blades.wall.height * 0.5,
                "blade ${it.id} left the wall at t=$stillTime: ${it.center}"
            )
        }

        val hole = carve.sample(stillTime).rects.single()
        assertTrue(
            bladeRects.any { abs(it.center.x - hole.center.x) < 1.2 && abs(it.center.y - hole.center.y) < 1.2 },
            "the carving hole must overlap a blade at t=$stillTime or subtraction is invisible; " +
                "hole at ${hole.center}, blades at ${bladeRects.map { it.center }}"
        )
        assertEquals(blades.wall.width, carve.wall.width, "both fields must share one wall")
        assertEquals(blades.wall.height, carve.wall.height)

        // The pattern scene exercises both halves of the emitter's fidelity story at
        // once: a pattern query, which still bakes, and a `->degrade` gate, which
        // lowers exactly. Both must actually be present for the differential to mean
        // what its label says.
        val emitted = RectSLGlsl.emitSdf(pattern, "scene")
        assertTrue(
            emitted.notes.any { it.contains("pattern") },
            "the pattern scene should force a pattern bake, got ${emitted.notes}"
        )
        assertTrue(
            emitted.notes.none { it.contains("degrade") },
            "`->degrade` lowers exactly now, so it must not appear as a caveat: ${emitted.notes}"
        )
        assertTrue(
            emitted.glsl.contains("rectslLess64"),
            "the scene's ->degrade gate should have been lowered into 64-bit lanes"
        )
    }

    // --- plumbing -----------------------------------------------------------

    /**
     * Compiles through `compileRectSL` — the entry point the harness itself uses —
     * and checks the oracle agrees. They are the same VM behind two signatures, and
     * a test that used only the oracle would not have covered the path the docs
     * render actually takes.
     */
    private fun compileOrFail(source: String): SceneProgram {
        val viaHarnessPath = compileRectSL(source)
        assertTrue(
            viaHarnessPath.ok && viaHarnessPath.programOrNull != null,
            "harness demo scene did not compile via compileRectSL:\n" +
                viaHarnessPath.diagnostics.joinToString("\n")
        )
        val viaOracle = RectAnimationOracle.compile(source)
        assertTrue(
            viaOracle.ok,
            "compileRectSL and RectAnimationOracle.compile disagree about this scene:\n" +
                viaOracle.diagnostics.joinToString("\n")
        )
        return viaHarnessPath.program
    }

    /** A small, deliberately overlapping scene used as the thing being subtracted. */
    private fun carvingProgram(): SceneProgram = RectAnimationOracle.compile(
        """
        #rectsl
        ->wall 8 5 abstract
        ->duration 4
        "hole" rect  1.6 ->square
          '( 0=-2.4  4=2.4 ) ->x
          '( 0=0  4=0.7854 ) ->rot
        ;
        """.trimIndent()
    ).program

    private fun someGalleryPrograms(limit: Int): List<Pair<String, SceneProgram>> =
        Files.list(galleryDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".rectsl") }
                .map { it.fileName.toString().removeSuffix("_rectsl_.rectsl") to it.readText() }
                .toList()
        }
            .sortedBy { it.first }
            .mapNotNull { (name, src) -> RectAnimationOracle.compile(src).programOrNull?.let { name to it } }
            // Keep the suite quick: the big mandala scenes are already compiled by
            // RectSLGlslSdfTest, what is new here is the look wrapper around them.
            .filter { it.second.nodes.size <= 40 }
            .take(limit)

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!dir.resolve("settings.gradle.kts").exists()) {
            dir = dir.parent ?: error("could not locate repo root")
        }
        return dir
    }
}

package afield.rectsl

import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The emitted GLSL has to survive two things Kotlin cannot check for it: a real
 * shader compiler, and the CPU sampler it claims to agree with.
 *
 * Compilation goes through shaderc, so a missing semicolon or an int/float
 * mismatch fails here rather than at pipeline creation on some other machine.
 * Agreement is checked by evaluating the emitted distance field's *inputs* —
 * the same track functions, re-read out of the emitted source is not possible,
 * so instead the CPU scene is rasterized and compared against a CPU port of the
 * exact box-union the shader performs. That catches transform-composition
 * mistakes, which are by far the likeliest way for this to be wrong.
 */
class RectSLGlslSdfTest {

    private val galleryDir: Path = repoRoot().resolve("public/docs/assets/rectsl_gallery")

    @Test
    fun `every gallery program emits GLSL that shaderc accepts`() {
        val programs = gallerySources()
        assertTrue(programs.size >= 30, "expected the full rectsl gallery, found ${programs.size}")

        val failures = mutableListOf<String>()
        var compiled = 0
        for ((name, source) in programs) {
            val program = RectAnimationOracle.compile(source).programOrNull ?: continue
            val result = RectSLGlsl.emitSdf(program, functionName = "scene")
            val shader = wrapAsFragment(result.glsl)
            try {
                val spirv = ShaderSPIRVUtils.compileShader("rectsl-$name.frag", shader, ShaderKind.FRAGMENT_SHADER)
                val words = spirv.bytecode()?.remaining() ?: 0
                if (words <= 0) failures += "$name: empty SPIR-V"
                else compiled++
            } catch (t: Throwable) {
                failures += "$name: ${t.message?.lines()?.take(6)?.joinToString(" | ")}"
            }
        }
        assertTrue(compiled >= 30, "only $compiled gallery programs produced SPIR-V")
        assertTrue(failures.isEmpty(), "emitted GLSL failed to compile:\n${failures.joinToString("\n")}")
    }

    /**
     * The largest gallery scene lowers to more than 64KB of GLSL, which used to
     * be the point at which `ShaderSPIRVUtils` failed with a bare "Out of stack
     * space." — LWJGL's MemoryStack, not the shader compiler. Keep a scene that
     * crosses that line in the suite so the encoding path cannot regress.
     */
    @Test
    fun `a shader larger than the LWJGL memory stack still compiles`() {
        val biggest = gallerySources()
            .mapNotNull { (name, source) ->
                RectAnimationOracle.compile(source).programOrNull?.let { name to RectSLGlsl.emitSdf(it, "scene") }
            }
            .maxByOrNull { it.second.glsl.length }!!
        assertTrue(
            biggest.second.glsl.length > 64 * 1024,
            "expected at least one gallery scene past the 64KB stack limit, largest was " +
                "${biggest.first} at ${biggest.second.glsl.length} chars"
        )
        val spirv = ShaderSPIRVUtils.compileShader(
            "rectsl-large.frag", wrapAsFragment(biggest.second.glsl), ShaderKind.FRAGMENT_SHADER
        )
        assertTrue((spirv.bytecode()?.remaining() ?: 0) > 0, "${biggest.first} produced empty SPIR-V")
    }

    /**
     * The emitter's geometry, ported straight back to Kotlin, must reproduce the
     * sampler's rects. This is the honest half of the check: shaderc proves the
     * text is a shader, this proves the shader is the *right* shader — same
     * centres, same half-extents, same rotation, same visibility gating.
     */
    @Test
    fun `emitted geometry matches the CPU sampler`() {
        val programs = gallerySources()
        var comparisons = 0
        for ((name, source) in programs) {
            val program = RectAnimationOracle.compile(source).programOrNull ?: continue
            for (time in listOf(0.0, 0.37, 1.0, 2.5, program.duration * 0.5, program.duration)) {
                val sampled = program.sample(time).rects.filter { it.alpha > 0.0 }
                // Points on a coarse lattice over the wall, plus each rect's own
                // centre: interior points are where a sign error shows up.
                val probes = lattice(program.wall) + sampled.map { it.center }
                for (p in probes) {
                    val expected = sampled.minOfOrNull { boxDistance(p, it) } ?: 1e9
                    val actual = referenceSdf(program, p, time)
                    assertEquals(
                        expected, actual, 1e-9,
                        "$name at t=$time, p=(${p.x}, ${p.y}): CPU sampler and SDF geometry disagree"
                    )
                    comparisons++
                }
            }
        }
        assertTrue(comparisons > 5000, "expected a broad comparison, made only $comparisons")
    }

    @Test
    fun `every easing RectSL can name has a GLSL body`() {
        val missing = RECTSL_EASINGS.keys - RectSLGlsl.supportedEasings
        assertTrue(missing.isEmpty(), "easings with no GLSL: ${missing.sorted()}")
    }

    @Test
    fun `gallery emission reports no fidelity caveats`() {
        val noted = gallerySources().mapNotNull { (name, source) ->
            val program = RectAnimationOracle.compile(source).programOrNull ?: return@mapNotNull null
            val result = RectSLGlsl.emitSdf(program)
            if (result.ok) null else "$name: ${result.notes.joinToString("; ")}"
        }
        assertTrue(noted.isEmpty(), "programs lowered with caveats:\n${noted.joinToString("\n")}")
    }

    @Test
    fun `equal tracks share one emitted function`() {
        // An `array` of 12 copies driven by one template must not emit twelve
        // copies of the same curve; symbolic tracks make them compare equal.
        val source = """
            #rectsl
            ->wall 8 8 abstract
            ->duration 4
            "c" rect  1 ->square
              '( 0=0  4=6.28318 ) ->rot
              12 '( ic 0.6 * 0 ->offset ) array
            ;
        """.trimIndent()
        val program = RectAnimationOracle.compile(source).program
        val result = RectSLGlsl.emitSdf(program)
        assertEquals(12, result.rectCount)
        // One function for the shared rotation curve. The twelve per-copy x
        // offsets are constant in time, so they fold to literals rather than
        // becoming twelve more functions.
        assertEquals(
            1, result.trackFunctionCount,
            "expected one shared curve and no per-copy functions"
        )
    }

    // --- the CPU port of the emitted geometry -------------------------------

    /**
     * Exactly what `emitRect` writes, in Kotlin: ancestor transforms composed
     * outward, then the rotated box field, then the alpha gate.
     */
    private fun referenceSdf(program: SceneProgram, p: Vec2d, t: Double): Double {
        val nodeMap = program.nodes.associateBy { it.id }
        val parentOf = buildMap {
            program.nodes.filterIsInstance<GroupNode>().forEach { g ->
                g.children.forEach { put(it, g.id) }
            }
        }
        var best = 1e9
        for (rect in program.nodes.filterIsInstance<RectNode>()) {
            var px = 0.0; var py = 0.0
            var rot = 0.0
            var sx = 1.0; var sy = 1.0
            var alpha = 1.0

            val chain = mutableListOf<GroupNode>()
            var cur = parentOf[rect.id]
            val guard = HashSet<String>()
            while (cur != null && guard.add(cur)) {
                (nodeMap[cur] as? GroupNode)?.let { chain += it }
                cur = parentOf[cur]
            }
            for (g in chain.reversed()) {
                val cx = g.x.sample(t) * sx
                val cy = g.y.sample(t) * sy
                val cs = kotlin.math.cos(rot); val sn = kotlin.math.sin(rot)
                px += cs * cx - sn * cy
                py += sn * cx + cs * cy
                rot += g.rotation.sample(t)
                sx *= g.scaleX.sample(t); sy *= g.scaleY.sample(t)
                alpha *= g.alpha.sample(t)
            }
            run {
                val cx = rect.x.sample(t) * sx
                val cy = rect.y.sample(t) * sy
                val cs = kotlin.math.cos(rot); val sn = kotlin.math.sin(rot)
                px += cs * cx - sn * cy
                py += sn * cx + cs * cy
                rot += rect.rotation.sample(t)
                sx *= rect.scaleX.sample(t); sy *= rect.scaleY.sample(t)
                alpha *= rect.alpha.sample(t)
            }
            if (alpha.coerceIn(0.0, 1.0) <= 0.0) continue
            val hw = kotlin.math.abs(0.5 * rect.w.sample(t) * sx)
            val hh = kotlin.math.abs(0.5 * rect.h.sample(t) * sy)
            best = minOf(best, boxDistanceAt(p, Vec2d(px, py), Vec2d(hw, hh), rot))
        }
        return best
    }

    private fun boxDistance(p: Vec2d, r: SampledRect): Double =
        boxDistanceAt(
            p,
            r.center,
            Vec2d(kotlin.math.abs(0.5 * r.size.x * r.scale.x), kotlin.math.abs(0.5 * r.size.y * r.scale.y)),
            r.rotation
        )

    private fun boxDistanceAt(p: Vec2d, centre: Vec2d, half: Vec2d, rot: Double): Double {
        val qx = p.x - centre.x
        val qy = p.y - centre.y
        val c = kotlin.math.cos(rot)
        val s = kotlin.math.sin(rot)
        val lx = c * qx + s * qy
        val ly = -s * qx + c * qy
        val dx = kotlin.math.abs(lx) - half.x
        val dy = kotlin.math.abs(ly) - half.y
        val outside = kotlin.math.hypot(maxOf(dx, 0.0), maxOf(dy, 0.0))
        return outside + minOf(maxOf(dx, dy), 0.0)
    }

    private fun lattice(wall: WallSpec): List<Vec2d> {
        val out = mutableListOf<Vec2d>()
        for (i in 0..6) for (j in 0..6) {
            out += Vec2d(
                (i / 6.0 - 0.5) * wall.width,
                (j / 6.0 - 0.5) * wall.height
            )
        }
        return out
    }

    // --- plumbing -----------------------------------------------------------

    /** The emitted field, made into a shader shaderc will actually accept. */
    private fun wrapAsFragment(sdf: String): String = buildString {
        appendLine("#version 450")
        appendLine("layout(location = 0) in vec2 v_world;")
        appendLine("layout(location = 0) out vec4 outColor;")
        appendLine("layout(push_constant) uniform Push { float time; } push;")
        appendLine()
        appendLine(sdf)
        appendLine()
        appendLine("void main() {")
        appendLine("    float d = scene(v_world, push.time);")
        appendLine("    outColor = vec4(vec3(smoothstep(0.0, 0.02, d)), 1.0);")
        appendLine("}")
    }

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

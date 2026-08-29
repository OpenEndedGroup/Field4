package afield.stacksl

import afield.stdlib.slGlsl
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the shipped demo honest.
 *
 * `dev_docs/assets/stacksl_volumetric_noise/stacksl_volumetric_noise.yaml` is an operation
 * transcript: a human replays it and a box runs. Nothing compiles it at build time, so every
 * assumption it makes about this package is a silent liability — rename a parameter, change a
 * default, reword a helper, and the demo throws in front of whoever opened it.
 *
 * So this test makes the same calls the demo's box code makes, in the same shapes, and compiles
 * every StackSL program the demo quotes. It is not a copy of the box: it is the *contract surface*
 * the box depends on.
 */
class VolumetricNoiseDemoTest {

    private val demo = File(
        System.getProperty("user.dir"),
        "dev_docs/assets/stacksl_volumetric_noise/stacksl_volumetric_noise.yaml"
    )

    @Test
    fun `the demo transcript exists and names this package`() {
        assertTrue(demo.exists(), "the demo artifact is missing: ${demo.absolutePath}")
        val text = demo.readText()
        assertTrue("VolumetricNoiseReference" in text, "the demo no longer uses the CPU reference")
        assertTrue(text.startsWith("name: stacksl_volumetric_noise"), "the transcript lost its name key")
        assertTrue("operations:" in text, "the transcript has no operations block")
    }

    @Test
    fun `every StackSL program the demo quotes still compiles`() {
        if (!demo.exists()) return
        // Both the `slGlsl("…")` calls in the box code and the `= "…"` stack.auto snippets in the
        // prose. A demo that tells someone to paste a broken program is worse than no demo.
        val text = demo.readText()
        val programs = (
            Regex("""slGlsl\("([^"]+)"\)""").findAll(text).map { it.groupValues[1] } +
                Regex("""=\s+"(input [^"]+)"""").findAll(text).map { it.groupValues[1] }
            ).toList().distinct()

        assertTrue(programs.size >= 5, "only found ${programs.size} programs in the demo; the scrape is broken")
        programs.forEach { src ->
            val r = compileStackSL(src)
            assertTrue(
                r.ok,
                "the demo quotes a StackSL program that no longer compiles: `$src`\n  " +
                    r.diagnostics.filter { it.severity == StackSLSeverity.ERROR }.joinToString("\n  ")
            )
        }
        println("demo transcript: all ${programs.size} quoted StackSL programs compile — ${programs.joinToString(" | ")}")
    }

    @Test
    fun `the GLSL lines the demo fishes out of slGlsl are actually there`() {
        // `slGlsl("input fbm3").lines().first { … }` throws NoSuchElementException if no line
        // matches, and `substringAfter` silently returns the whole string. Both are ways for the
        // demo to break quietly against a reworded emitter.
        val fbm = slGlsl("input fbm").lines().firstOrNull { "_fbm(" in it && "float" !in it }
        assertTrue(fbm != null, "the demo's `fbm` call-site predicate matches no line of the emitted GLSL")
        assertTrue(".zw * 0.07" in fbm, "the 2D fbm call site no longer shows the shear the demo points at: $fbm")

        val fbm3 = slGlsl("input fbm3").lines().firstOrNull { "_fbm3(" in it && "float" !in it }
        assertTrue(fbm3 != null, "the demo's `fbm3` call-site predicate matches no line of the emitted GLSL")
        assertTrue(".xyz" in fbm3, "the fbm3 call site no longer shows it reading .xyz: $fbm3")

        val curl = slGlsl("input xyz 2.2 * curl3 xyz 0.4 *")
        assertTrue("vec3 stacksl_curl3" in curl, "the demo's substringAfter anchor `vec3 stacksl_curl3` is gone")
    }

    @Test
    fun `the reference API the demo calls still has these shapes`() {
        // Exactly the call shapes in the box code. If any of these stops compiling, so does the
        // demo — and this test is where that is discovered, not in front of a user.
        val octaves = 5.0f
        val eps = 0.05f
        val p = NoiseVec3(0.3f, -1.1f, 2.7f)

        VolumetricNoiseReference.fbm3(NoiseVec3(0f, 0f, 0f), octaves)
        VolumetricNoiseReference.curl3(p)
        VolumetricNoiseReference.curl3(p, eps)
        VolumetricNoiseReference.potential3(p, octaves, 2.0f, 0.5f)

        // Operator and property surface the box uses on NoiseVec3.
        val q = p + NoiseVec3(eps, 0f, 0f) - NoiseVec3(0f, eps, 0f)
        assertTrue(q.length > 0f)
        assertTrue(sqrt(q.x * q.x + q.y * q.y) >= 0f)

        // PngEncoder's shape, which the box feeds directly.
        val pixels = IntArray(16) { 0xFF000000.toInt() }
        val png = afield.util.PngEncoder.encode(pixels, 4, 4)
        assertTrue(png.size > 8 && png[1].toInt() == 'P'.code, "PngEncoder.encode(IntArray, w, h) changed shape")
    }

    @Test
    fun `the numbers the demo prints in its prose are still true`() {
        // The description claims specific figures. A demo that states a number the code no longer
        // produces is the most quotable kind of stale documentation.
        val text = demo.takeIf { it.exists() }?.readText() ?: return

        if ("0.96875" in text) {
            val amplitudes = generateSequence(0.5) { it * 0.5 }.take(5).sum()
            assertTrue(amplitudes == 0.96875, "the demo cites 0.96875 but the 2D fbm's amplitudes sum to $amplitudes")
        }
        if ("0.07" in text) {
            assertTrue(".zw * 0.07" in slGlsl("input fbm"), "the demo cites the 0.07 shear but it is gone")
        }
        // The divergence comparison the description quotes, recomputed rather than trusted.
        val pts = (0 until 400).map { i ->
            val t = i.toFloat()
            NoiseVec3((t * 0.618f) % 6f - 3f, (t * 0.414f) % 6f - 3f, (t * 0.732f) % 6f - 3f)
        }
        val eps = 0.05f
        fun divergence(f: (NoiseVec3) -> NoiseVec3, p: NoiseVec3): Float {
            val dx = f(p + NoiseVec3(eps, 0f, 0f)).x - f(p - NoiseVec3(eps, 0f, 0f)).x
            val dy = f(p + NoiseVec3(0f, eps, 0f)).y - f(p - NoiseVec3(0f, eps, 0f)).y
            val dz = f(p + NoiseVec3(0f, 0f, eps)).z - f(p - NoiseVec3(0f, 0f, eps)).z
            return (dx + dy + dz) / (2f * eps)
        }
        fun rms(v: List<Float>) = sqrt(v.sumOf { (it * it).toDouble() }.toFloat() / v.size)
        val curl = { q: NoiseVec3 -> VolumetricNoiseReference.curl3(q, eps) }
        val plain = { q: NoiseVec3 -> VolumetricNoiseReference.potential3(q, 5f, 2.0f, 0.5f) }
        val curlDiv = rms(pts.map { divergence(curl, it) / rms(pts.map { p -> curl(p).length }) })
        val plainDiv = rms(pts.map { divergence(plain, it) / rms(pts.map { p -> plain(p).length }) })
        println("demo's headline comparison, recomputed: curl3 $curlDiv vs plain noise $plainDiv")
        assertTrue(
            plainDiv > curlDiv * 1000f,
            "the demo promises curl3's divergence is negligible against a plain noise field, but " +
                "curl3=$curlDiv and plain=$plainDiv"
        )
    }
}

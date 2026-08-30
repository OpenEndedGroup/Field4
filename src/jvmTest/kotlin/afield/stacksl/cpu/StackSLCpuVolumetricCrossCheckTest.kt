package afield.stacksl.cpu

import afield.stacksl.NoiseVec3
import afield.stacksl.NoiseVec4
import afield.stacksl.VolumetricNoiseConstants
import afield.stacksl.VolumetricNoiseReference
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two independently written CPU implementations of the same shader, checking each other.
 *
 * `VolumetricNoiseReference` is a hand transliteration of the GLSL that `StackSL.kt` emits for
 * `noise3`, `fbm3`, `curl3`, `worley3` and the rest. It was written *from* that shader by a person,
 * and the branch that added it named the duplication as its own top risk — with good reason: a
 * hand-written CPU twin that quietly disagrees with the shader it mirrors is precisely the defect
 * that started this whole thread, when a committed figure "illustrating GLSL behaviour" turned out
 * to have been rendered by a transliteration rather than by GLSL.
 *
 * The CPU backend can now close that loop, and it is worth being precise about why it counts. This
 * test does not compare the reference against a *description* of the shader. It parses the emitted
 * GLSL — the same text a GPU is handed — and evaluates it. So the two sides share no code and no
 * author: one is a person reading GLSL and writing Kotlin, the other is a machine reading GLSL and
 * executing it. Agreement between them is real evidence; disagreement localises to a line.
 *
 * WHAT IT FOUND. `hash31`, `hash33` and `hash41` were already **bit-exact**, 20000 samples each.
 * `noise3` was not: it differed by up to 1.8e-7, on 49.5% of samples. The cause is one line, and it
 * is pinned by [the mix form test][`the residual was the algebraic form of mix, and nothing else`]
 * below: GLSL defines `mix(x, y, a)` as `x * (1 - a) + y * a`, and the reference had written the
 * algebraically identical `a + (b - a) * t`, which is a different floating-point expression. Fixing
 * that one line made every function here bit-exact — which is what `VolumetricNoiseReference`'s own
 * header claims it is, and now is.
 */
class StackSLCpuVolumetricCrossCheckTest {

    private val ref = VolumetricNoiseReference
    private val K = VolumetricNoiseConstants

    /** One invocation per word, so the emitted helpers can be called directly by name. */
    private fun invocationFor(word: String): GlslInvocation =
        parseGlslShader(StackSLCpu.compile("input $word").glsl).newInvocation()

    private fun vec3(x: Float, y: Float, z: Float) = GlslValue.vec3(x, y, z)

    /** A deterministic spread well away from the origin and off the integer lattice. */
    private fun points(n: Int, spread: Float = 40f, seed: Int = 4242): List<NoiseVec3> {
        val rng = Random(seed)
        return List(n) {
            NoiseVec3(
                (rng.nextFloat() - 0.5f) * 2f * spread,
                (rng.nextFloat() - 0.5f) * 2f * spread,
                (rng.nextFloat() - 0.5f) * 2f * spread
            )
        }
    }

    /**
     * Compare one scalar-valued emitted function against its Kotlin twin over [n] samples, and
     * report how many were bit-identical rather than only how close they were.
     *
     * "Close" is the wrong summary for this. A one-ulp difference in a hash argument becomes O(1)
     * after a `fract`, so a mean error of 1e-8 in one function is not evidence that a program built
     * on it agrees. Only bit-exactness composes.
     */
    private fun assertBitExact(
        label: String,
        n: Int,
        samples: List<NoiseVec3>,
        emitted: (NoiseVec3) -> Float,
        reference: (NoiseVec3) -> Float
    ) {
        var exact = 0
        var maxAbs = 0f
        var worst: NoiseVec3? = null
        samples.take(n).forEach { p ->
            val a = emitted(p)
            val b = reference(p)
            if (a == b) exact++ else {
                val d = abs(a - b)
                if (d > maxAbs) { maxAbs = d; worst = p }
            }
        }
        println("$label: $exact/$n bit-exact, worst |delta| = $maxAbs at $worst")
        assertEquals(n, exact, "$label disagreed on ${n - exact} of $n samples, worst |delta| $maxAbs at $worst")
    }

    @Test
    fun `the volumetric hashes are bit-exact against the emitted GLSL`() {
        val hash = invocationFor("noise3")
        val samples = points(20_000)
        assertBitExact("hash31", 20_000, samples,
            { hash.call("program_hash31", listOf(vec3(it.x, it.y, it.z))).asFloat },
            { ref.hash31(it.x, it.y, it.z) })

        val worley = invocationFor("worley3")
        var exact33 = 0
        samples.take(5_000).forEach { p ->
            val g = worley.call("program_hash33", listOf(vec3(p.x, p.y, p.z))).c
            val r = ref.hash33(p.x, p.y, p.z)
            if (g[0] == r.x && g[1] == r.y && g[2] == r.z) exact33++
        }
        println("hash33: $exact33/5000 bit-exact")
        assertEquals(5_000, exact33, "hash33 disagreed with the emitted GLSL")

        val noise4 = invocationFor("noise4")
        var exact41 = 0
        samples.take(5_000).forEach { p ->
            val g = noise4.call("program_hash41", listOf(GlslValue.vec4(p.x, p.y, p.z, p.x - p.z))).asFloat
            if (g == ref.hash41(p.x, p.y, p.z, p.x - p.z)) exact41++
        }
        println("hash41: $exact41/5000 bit-exact")
        assertEquals(5_000, exact41, "hash41 disagreed with the emitted GLSL")
    }

    @Test
    fun `noise3 and fbm3 are bit-exact against the emitted GLSL`() {
        val samples = points(4_000)
        val noise = invocationFor("noise3")
        assertBitExact("noise3", 4_000, samples,
            { noise.call("program_noise3", listOf(vec3(it.x, it.y, it.z))).asFloat },
            { ref.noise3(it) })

        val fbm = invocationFor("fbm3")
        assertBitExact("fbm3", 500, samples,
            {
                fbm.call(
                    "program_fbm3",
                    listOf(
                        vec3(it.x, it.y, it.z),
                        GlslValue.float(K.DEFAULT_OCTAVES),
                        GlslValue.float(K.DEFAULT_LACUNARITY),
                        GlslValue.float(K.DEFAULT_GAIN)
                    )
                ).asFloat
            },
            { ref.fbm3(it) })
    }

    @Test
    fun `noise4 and fbm4 are bit-exact against the emitted GLSL`() {
        val samples = points(2_000)
        val noise4 = invocationFor("noise4")
        var exact = 0
        var maxAbs = 0f
        samples.take(2_000).forEach { p ->
            val q = NoiseVec4(p.x, p.y, p.z, p.y - p.x)
            val g = noise4.call("program_noise4", listOf(GlslValue.vec4(q.x, q.y, q.z, q.w))).asFloat
            val r = ref.noise4(q)
            if (g == r) exact++ else maxAbs = maxOf(maxAbs, abs(g - r))
        }
        println("noise4: $exact/2000 bit-exact, worst |delta| = $maxAbs")
        assertEquals(2_000, exact, "noise4 disagreed, worst |delta| $maxAbs")

        val fbm4 = invocationFor("fbm4")
        var exact4 = 0
        samples.take(300).forEach { p ->
            val q = NoiseVec4(p.x, p.y, p.z, p.y - p.x)
            val g = fbm4.call(
                "program_fbm4",
                listOf(
                    GlslValue.vec4(q.x, q.y, q.z, q.w),
                    GlslValue.float(K.DEFAULT_OCTAVES),
                    GlslValue.float(K.DEFAULT_LACUNARITY),
                    GlslValue.float(K.DEFAULT_GAIN)
                )
            ).asFloat
            if (g == ref.fbm4(q)) exact4++
        }
        println("fbm4: $exact4/300 bit-exact")
        assertEquals(300, exact4, "fbm4 disagreed with the emitted GLSL")
    }

    @Test
    fun `worley3, curl3, fbmd3 and warp3 are bit-exact against the emitted GLSL`() {
        val samples = points(300)

        val worley = invocationFor("worley3")
        var exactW = 0
        samples.forEach { p ->
            val g = worley.call("program_worley3", listOf(vec3(p.x, p.y, p.z))).c
            val r = ref.worley3(p)
            if (g[0] == r.x && g[1] == r.y && g[2] == r.z && g[3] == r.w) exactW++
        }
        println("worley3: $exactW/${samples.size} bit-exact")
        assertEquals(samples.size, exactW, "worley3 disagreed with the emitted GLSL")

        val curl = invocationFor("curl3")
        var exactC = 0
        samples.forEach { p ->
            val g = curl.call(
                "program_curl3",
                listOf(
                    vec3(p.x, p.y, p.z),
                    GlslValue.float(K.DEFAULT_CURL_EPSILON),
                    GlslValue.float(K.DEFAULT_CURL_OCTAVES),
                    GlslValue.float(K.DEFAULT_LACUNARITY),
                    GlslValue.float(K.DEFAULT_GAIN)
                )
            ).c
            val r = ref.curl3(p)
            if (g[0] == r.x && g[1] == r.y && g[2] == r.z) exactC++
        }
        println("curl3: $exactC/${samples.size} bit-exact")
        assertEquals(samples.size, exactC, "curl3 disagreed with the emitted GLSL")

        val fbmd = invocationFor("fbmd3")
        var exactD = 0
        samples.forEach { p ->
            val g = fbmd.call(
                "program_fbmd3",
                listOf(
                    vec3(p.x, p.y, p.z),
                    GlslValue.float(K.DEFAULT_GRADIENT_EPSILON),
                    GlslValue.float(K.DEFAULT_OCTAVES),
                    GlslValue.float(K.DEFAULT_LACUNARITY),
                    GlslValue.float(K.DEFAULT_GAIN)
                )
            ).c
            val r = ref.fbmd3(p)
            if (g[0] == r.x && g[1] == r.y && g[2] == r.z && g[3] == r.w) exactD++
        }
        println("fbmd3: $exactD/${samples.size} bit-exact")
        assertEquals(samples.size, exactD, "fbmd3 disagreed with the emitted GLSL")

        val warp = invocationFor("warp3")
        assertBitExact("warp3", samples.size, samples,
            {
                warp.call(
                    "program_warp3",
                    listOf(
                        vec3(it.x, it.y, it.z),
                        GlslValue.float(K.DEFAULT_WARP_STRENGTH),
                        GlslValue.float(K.DEFAULT_OCTAVES),
                        GlslValue.float(K.DEFAULT_LACUNARITY),
                        GlslValue.float(K.DEFAULT_GAIN)
                    )
                ).asFloat
            },
            { ref.warp3(it) })
    }

    /**
     * The residual was the algebraic form of `mix`, and nothing else.
     *
     * This is the experiment that turned "they differ by 1.8e-7, probably rounding" into a located
     * cause. `noise3` is reimplemented here TWICE, differing only in how `mix` is spelled, and each
     * version is compared against one of the two implementations under test. Both matched at
     * 20000/20000 before the fix — which is what proves the difference was the `mix` form and not
     * an accumulation of unrelated rounding, and what would have made a tolerance the wrong answer.
     *
     * Kept after the fix because it is now also the regression test for it: rewrite `mix` either
     * side of this and the mapping breaks.
     */
    @Test
    fun `the residual was the algebraic form of mix, and nothing else`() {
        val hash = invocationFor("noise3")
        fun hash31(x: Float, y: Float, z: Float) = hash.call("program_hash31", listOf(vec3(x, y, z))).asFloat
        fun smooth(f: Float) = f * f * (3f - 2f * f)

        // The GLSL specification's definition of mix, and the rewrite the reference used to have.
        fun mixSpec(x: Float, y: Float, a: Float) = x * (1f - a) + y * a
        fun mixRewrite(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun noise3(p: NoiseVec3, spec: Boolean): Float {
            val ix = kotlin.math.floor(p.x)
            val iy = kotlin.math.floor(p.y)
            val iz = kotlin.math.floor(p.z)
            val ux = smooth(p.x - ix)
            val uy = smooth(p.y - iy)
            val uz = smooth(p.z - iz)
            val c000 = hash31(ix, iy, iz)
            val c100 = hash31(ix + 1f, iy, iz)
            val c010 = hash31(ix, iy + 1f, iz)
            val c110 = hash31(ix + 1f, iy + 1f, iz)
            val c001 = hash31(ix, iy, iz + 1f)
            val c101 = hash31(ix + 1f, iy, iz + 1f)
            val c011 = hash31(ix, iy + 1f, iz + 1f)
            val c111 = hash31(ix + 1f, iy + 1f, iz + 1f)
            val m = if (spec) ::mixSpec else ::mixRewrite
            val z0 = m(m(c000, c100, ux), m(c010, c110, ux), uy)
            val z1 = m(m(c001, c101, ux), m(c011, c111, ux), uy)
            return m(z0, z1, uz)
        }

        val samples = points(4_000)
        var matchesSpec = 0
        var differByForm = 0
        samples.forEach { p ->
            val evaluated = hash.call("program_noise3", listOf(vec3(p.x, p.y, p.z))).asFloat
            if (evaluated == noise3(p, spec = true)) matchesSpec++
            if (noise3(p, spec = true) != noise3(p, spec = false)) differByForm++
        }
        println("noise3 evaluated == spec-form reimplementation: $matchesSpec/${samples.size}")
        println("samples where the two mix forms differ at all:  $differByForm/${samples.size}")

        // The evaluator computes the spec's form, exactly.
        assertEquals(samples.size, matchesSpec, "the interpreter is not computing GLSL's mix")
        // And the two forms really do differ often enough for that to be a meaningful statement —
        // measured 1946 of 4000, so a floor of a third of the samples is well inside it.
        assertTrue(
            differByForm > samples.size / 3,
            "the two mix forms barely differ on this sample, so the test proves little: $differByForm"
        )
    }
}

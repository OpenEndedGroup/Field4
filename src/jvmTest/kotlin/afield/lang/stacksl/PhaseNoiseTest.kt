package afield.lang.stacksl

import afield.lang.stacksl.cpu.GlslInvocation
import afield.lang.stacksl.cpu.GlslValue
import afield.lang.stacksl.cpu.StackSLCpu
import afield.lang.stacksl.cpu.newInvocation
import afield.lang.stacksl.cpu.parseGlslShader
import afield.graphics.shader.stackAutoShaderReport
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phase noise family (`qnoise`, `phnoise`, `phrot`, PhaseNoise.kt), checked by running the
 * GLSL the compiler emits through the CPU backend — the same text a GPU is handed.
 *
 * The claims worth pinning are algebraic, so most of these are identities rather than tolerances on
 * a picture: rotating the phase of every kernel is the same as rotating the quadrature pair; a
 * per-octave phase that is the same everywhere is a uniform rotation; a phase response sees the
 * kernel's frequency, octave and time where the documentation says it does. The two statistical
 * checks — correlation `cos(2πθ)`, and how much two fields share when only their fine octaves are
 * rotated — are the reason the family exists, so they are pinned too, at tolerances the sample
 * count supports.
 */
class PhaseNoiseTest {

    private fun invocation(program: String): GlslInvocation {
        val result = StackSLCpu.compile(program)
        assertTrue(result.ok, "`$program` did not compile: ${result.diagnostics}")
        return parseGlslShader(result.glsl).newInvocation()
    }

    private fun GlslInvocation.at(x: Float, y: Float, w: Float = 0f): FloatArray =
        call("program", listOf(GlslValue.vec4(x, y, 0f, w))).c

    private fun points(n: Int, spread: Float = 30f, seed: Int = 1907): List<Pair<Float, Float>> {
        val rng = Random(seed)
        return List(n) { (rng.nextFloat() - 0.5f) * 2f * spread to (rng.nextFloat() - 0.5f) * 2f * spread }
    }

    private fun assertSameField(label: String, a: GlslInvocation, b: GlslInvocation, n: Int = 300, tolerance: Float = 2e-5f, w: Float = 0f) {
        var worst = 0f
        points(n).forEach { (x, y) ->
            val u = a.at(x, y, w)
            val v = b.at(x, y, w)
            for (c in 0..2) worst = maxOf(worst, abs(u[c] - v[c]))
        }
        assertTrue(worst <= tolerance, "$label: worst |delta| $worst > $tolerance")
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in a.indices) {
            sab += (a[i] - ma) * (b[i] - mb)
            saa += (a[i] - ma) * (a[i] - ma)
            sbb += (b[i] - mb) * (b[i] - mb)
        }
        return sab / sqrt(saa * sbb)
    }

    private fun channel(inv: GlslInvocation, samples: List<Pair<Float, Float>>, c: Int = 0) =
        DoubleArray(samples.size) { inv.at(samples[it].first, samples[it].second)[c].toDouble() }

    @Test
    fun `a constant phase response is exactly phrot`() {
        val rotated = invocation("input qnoise 0.3 phrot")
        val responded = invocation(": third >k 0.3 ;\n'third input qnoise")
        assertSameField("'third qnoise vs qnoise 0.3 phrot", rotated, responded)
        // The reference may also come after the arguments.
        assertSameField("trailing reference", responded, invocation(": third >k 0.3 ;\ninput 'third qnoise"))
    }

    @Test
    fun `a uniform per-octave phase is a uniform rotation, and zero is plain qnoise`() {
        assertSameField("phnoise [0.2 x4] vs phrot 0.2", invocation("input qnoise 0.2 phrot"), invocation("input [0.2,0.2,0.2,0.2] phnoise"))
        assertSameField("phnoise [0 x4] vs qnoise", invocation("input qnoise"), invocation("input [0,0,0,0] phnoise"), tolerance = 0f)
        assertSameField("short form", invocation("input qnoise"), invocation("input phnoise"), tolerance = 0f)
    }

    @Test
    fun `phases for octaves that are not summed change nothing`() {
        assertSameField(
            "two octaves ignore phases[2] and phases[3]",
            invocation("input [0,0,0,0] 2 phnoise.3"),
            invocation("input [0,0,0.4,0.9] 2 phnoise.3"),
            tolerance = 0f
        )
    }

    @Test
    fun `a phase response sees frequency in x, octave in y and time in w`() {
        val perOctave = invocation("input [0,0,0.5,0.5] phnoise")
        // Octave 1's carriers lie in 1.68..2.38 cycles per unit and octave 2's in 3.36..4.76, so a
        // step at 3 on the frequency selects exactly the octaves that phases[2..3] do.
        assertSameField("frequency", perOctave, invocation(": fine >k 3 <k x step 0.5 * ;\n'fine input qnoise"))
        assertSameField("octave", perOctave, invocation(": fine >k 1.5 <k y step 0.5 * ;\n'fine input qnoise"))
        assertSameField(
            "time",
            invocation("input qnoise 0.25 phrot"),
            invocation(": drift >k <k w ;\n'drift input qnoise"),
            w = 0.25f
        )
    }

    @Test
    fun `phrot passes the envelope and alpha through`() {
        val plain = invocation("input qnoise")
        val rotated = invocation("input qnoise 0.37 phrot")
        points(50).forEach { (x, y) ->
            val a = plain.at(x, y)
            val b = rotated.at(x, y)
            assertEquals(a[2], b[2], "envelope at $x,$y")
            assertEquals(1f, b[3])
            assertEquals(a[2].toDouble(), sqrt((b[0] * b[0] + b[1] * b[1]).toDouble()), 1e-5)
        }
    }

    @Test
    fun `the pair is zero-mean, uncorrelated and mostly inside -1 to 1`() {
        val samples = points(4000, spread = 200f)
        val inv = invocation("input qnoise")
        val c = channel(inv, samples, 0)
        val s = channel(inv, samples, 1)
        listOf("Nc" to c, "Ns" to s).forEach { (name, v) ->
            val mean = v.average()
            val rms = sqrt(v.sumOf { it * it } / v.size)
            val outside = v.count { abs(it) > 1.0 }.toDouble() / v.size
            println("$name: mean $mean rms $rms outside(-1,1) $outside")
            assertTrue(abs(mean) < 0.03, "$name mean $mean")
            assertTrue(rms in 0.25..0.45, "$name rms $rms")
            assertTrue(outside < 0.01, "$name has ${outside * 100}% of samples outside -1..1")
        }
        val r = correlation(c, s)
        println("corr(Nc, Ns) = $r")
        assertTrue(abs(r) < 0.05, "Nc and Ns should be uncorrelated, got $r")
    }

    @Test
    fun `two fields rotated by theta correlate by cos 2 pi theta`() {
        val samples = points(3000, spread = 200f)
        val base = channel(invocation("input qnoise"), samples)
        listOf(0.125, 0.25, 0.375, 0.5).forEach { theta ->
            val r = correlation(base, channel(invocation("input qnoise $theta phrot"), samples))
            val expected = cos(2 * PI * theta)
            println("theta $theta: corr $r, expected $expected")
            assertEquals(expected, r, 0.06, "corr at theta $theta")
        }
    }

    @Test
    fun `rotating only the fine octaves keeps the coarse structure`() {
        val samples = points(3000, spread = 200f)
        val base = channel(invocation("input qnoise"), samples)
        // At gain 0.5 the octave energies are 1, 1/4, 1/16, 1/64, 1/256. Inverting octaves 2..4
        // leaves a correlation of (E0 + E1 - E2 - E3 - E4) / total.
        val energies = List(5) { o -> Math.pow(0.25, o.toDouble()) }
        val expected = (energies[0] + energies[1] - energies.drop(2).sum()) / energies.sum()
        val fine = correlation(base, channel(invocation("input [0,0,0.5,0.5] phnoise"), samples))
        val coarse = correlation(base, channel(invocation("input [0.5,0.5,0,0] phnoise"), samples))
        println("fine-only inversion corr $fine, coarse-only $coarse, expected ±$expected")
        assertEquals(expected, fine, 0.05)
        assertEquals(-expected, coarse, 0.05)
    }

    @Test
    fun `the documented programs are quiet`() {
        listOf(
            "input 8 * qnoise x 0.5 * 0.5 +",
            "input 8 * qnoise >n <n x <n 0.33 phrot x <n 0.67 phrot x vec3 0.5 * 0.5 +",
            "input 8 * [0,0,0.1,0.3] t * phnoise x 0.5 * 0.5 +",
            ": fine >k 0.5 4 <k x smoothstep 0.5 * ;\n'fine input 8 * qnoise x",
            ": churn >k <k z 0.5 - <k w * ;\n'churn input 8 * qnoise x 0.5 * 0.5 +"
        ).forEach { program ->
            val report = stackAutoShaderReport(program, "program")
            assertTrue(report.compiled && report.quiet, "`$program` is not quiet: $report")
        }
    }

    @Test
    fun `an unbalanced phase response is reported, and a builtin is refused`() {
        val unbalanced = stackAutoShaderReport(": leaky =k <k x ;\n'leaky input qnoise", "program")
        assertTrue(unbalanced.problems.isNotEmpty(), "a response that leaves two values should be a problem: $unbalanced")
        assertFalse(StackSLCpu.compile("'sin input qnoise").ok, "a builtin is not a phase response")
        assertFalse(StackSLCpu.compile(": f >k 0.1 ;\n'f input phnoise").ok, "phnoise takes no response")
    }

    @Test
    fun `every phase noise program compiles to SPIR-V`() {
        val validator = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .plus(listOf("/opt/homebrew/bin", "/usr/local/bin"))
            .map { File(it, "glslangValidator") }
            .firstOrNull { it.canExecute() }
        if (validator == null) {
            println("SKIPPED: glslangValidator not found; the phase noise GLSL was NOT checked by a shader compiler.")
            return
        }
        val programs = listOf(
            "input qnoise",
            "input 4 2.1 0.6 qnoise.4",
            "input [0,0,0.2,0.5] phnoise",
            "input [0,0,0.2,0.5] 3 1.9 0.55 phnoise.5",
            "input qnoise 0.3 phrot",
            ": fine >k 0.5 4 <k x smoothstep 0.5 * ;\n'fine input qnoise =a <a x <a phacelle x + input fbm3 +"
        )
        val dir = java.nio.file.Files.createTempDirectory("phase-noise-glsl").toFile()
        try {
            val failures = programs.mapIndexedNotNull { i, program ->
                val r = compileStackSL(program, StackSLCompileOptions(prefix = "sl"))
                assertTrue(r.ok, "`$program`: ${r.diagnostics}")
                val f = File(dir, "p$i.frag")
                f.writeText(
                    "#version 450\nlayout(location = 0) in vec4 vIn;\nlayout(location = 0) out vec4 fragColor;\n\n" +
                        r.glsl + "\nvoid main() { fragColor = sl(vIn); }\n"
                )
                val p = ProcessBuilder(validator.absolutePath, "-V", "--target-env", "vulkan1.2", "-S", "frag", f.absolutePath, "-o", File(dir, "p$i.spv").absolutePath)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor(120, TimeUnit.SECONDS)
                if (p.exitValue() != 0) "--- `$program` ---\n$out" else null
            }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

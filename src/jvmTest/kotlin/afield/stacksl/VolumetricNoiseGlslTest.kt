package afield.stacksl

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the emitted GLSL through a real shader compiler.
 *
 * "It compiled in Kotlin" says nothing about whether the text StackSL hands the GPU is valid, and
 * StackSL has no other check that it is — a malformed swizzle or a wrong argument count reaches a
 * device at run time, inside a background pipeline patch, where it turns into a silently unchanged
 * binding rather than an error anyone sees.
 *
 * `glslangValidator` ships with the Vulkan SDK and is the same front end the engine uses, so this
 * is a genuine gate and not a lint. It needs no device: SPIR-V generation is pure translation.
 *
 * If the binary is absent the test SKIPS — but loudly, and it says so in the build log, because a
 * quietly-skipped gate is worse than no gate.
 */
class VolumetricNoiseGlslTest {

    private fun findValidator(): File? =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .plus(listOf("/opt/homebrew/bin", "/usr/local/bin"))
            .map { File(it, "glslangValidator") }
            .firstOrNull { it.canExecute() }

    /** Wrap a compiled StackSL root in the smallest legal Vulkan fragment shader. */
    private fun wrap(glsl: String, prefix: String) = buildString {
        appendLine("#version 450")
        appendLine("layout(location = 0) in vec4 vIn;")
        appendLine("layout(location = 0) out vec4 fragColor;")
        appendLine()
        append(glsl)
        appendLine()
        appendLine("void main() { fragColor = $prefix(vIn); }")
    }

    private fun validate(validator: File, dir: File, name: String, source: String): Pair<Boolean, String> {
        val f = File(dir, "$name.frag")
        f.writeText(source)
        val p = ProcessBuilder(
            validator.absolutePath, "-V", "--target-env", "vulkan1.2", "-S", "frag",
            f.absolutePath, "-o", File(dir, "$name.spv").absolutePath
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val finished = p.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            return false to "glslangValidator timed out"
        }
        return (p.exitValue() == 0) to out
    }

    /** One program per word, plus the arity variants and a program that uses the whole family. */
    private val programs = linkedMapOf(
        "noise3" to "input 3 * noise3",
        "noise4" to "input noise4",
        "fbm3" to "input 2.2 * fbm3",
        "fbm3_arity2" to "input 8 fbm3.2",
        "fbm3_arity4" to "input 8 2.3 0.55 fbm3.4",
        "fbm4" to "input 1.5 * fbm4.2",
        "curl3" to "input 2.2 * curl3 xyz 0.4 *",
        "curl3_arity3" to "input 0.03 3 curl3.3",
        "fbmd3" to "input fbmd3 yzw normalize",
        "warp3" to "input 1.7 * warp3",
        "worley3" to "input 6 * worley3 z 1- 8 pow",
        "voronoi3" to "input voronoi3 w",
        // Every new word in one shader, which is also the worst case for helper emission order.
        "whole_family" to "input =q <q fbm3 <q curl3 x + <q worley3 z + <q warp3 + " +
            "<q fbmd3 x + <q noise3 + <q noise4 + <q fbm4 +",
        // Mixed with the pre-existing 2D words, so both hash families coexist in one translation unit.
        "mixed_with_2d" to "input =q <q fbm <q noise3 + <q fbm3 + <q curl3 x +"
    )

    @Test
    fun `every volumetric word compiles to SPIR-V`() {
        val validator = findValidator()
        if (validator == null) {
            println(
                "SKIPPED VolumetricNoiseGlslTest: glslangValidator not found on PATH or in " +
                    "/opt/homebrew/bin. The GLSL this suite emits was NOT checked by a shader compiler."
            )
            return
        }
        println("validating StackSL output with ${validator.absolutePath}")

        val dir = createTempDir()
        try {
            // Guard the guard. If a deliberately broken shader passes, the validator is not
            // actually looking at anything and every OK below is worthless.
            val (badOk, _) = validate(
                validator, dir, "negative_control",
                "#version 450\nvoid main() { this is not glsl; }\n"
            )
            assertTrue(!badOk, "the negative control compiled, so glslangValidator is not really validating")

            val failures = mutableListOf<String>()
            programs.forEach { (name, src) ->
                val r = compileStackSL(src, StackSLCompileOptions(prefix = "sl"))
                assertTrue(r.ok, "`$src` did not compile in StackSL: ${r.diagnostics}")
                val (ok, out) = validate(validator, dir, name, wrap(r.glsl, "sl"))
                if (!ok) failures += "--- $name (`$src`) ---\n$out"
            }
            assertTrue(
                failures.isEmpty(),
                "these programs produced GLSL a shader compiler rejected:\n" + failures.joinToString("\n")
            )
            println("glslangValidator accepted all ${programs.size} volumetric-noise programs")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("stacksl-glsl").toFile()
}

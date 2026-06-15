package afield.examples.stacksl

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity

/*
FIELD_TEST
id: stacksl.compiler.smoke
runner: jvm-main
entrypoint: afield.examples.stacksl.StackSLSmokeTest
tags: [smoke, examples, stacksl, shaders]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "StackSL compiler smoke tests passed"
*/
object StackSLSmokeTest {
    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "StackSL compiler smoke tests failed" }
    }

    fun runTests(): Boolean {
        return try {
            compilesCreativeProgramWithPrefixedDefinitions()
            prunesDroppedValues()
            reportsStrictAndTolerantUnknownWords()
            keepsShortMutatedProgramsValid()
            emitsSamplerEnvironment()
            lengthUsesSpatialChannelsByDefault()
            emitsColorSpaceHelpers()
            compilesCenteredModifierProgram()
            externalizesDanglingWires()
            compilesStoreReadWriteWords()
            inlinesRepeatedDefinitionForCse()
            compilesExplicitArityVariants()
            compilesStackBlastOperators()
            compilesFMChirpOperator()
            compilesBitGlitchOperators()
            println("StackSL compiler smoke tests passed")
            true
        } catch (t: Throwable) {
            t.printStackTrace(System.err)
            false
        }
    }

    private fun compilesCreativeProgramWithPrefixedDefinitions() {
        val result = StackSLCompiler.compile(
            """
            : warp
              uv 8 * time + sin
              fbm palette saturate
            ;

            warp 1.15 contrast tonemap
            """.trimIndent(),
            StackSLCompileOptions(prefix = "fx")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("vec4 fx(vec4 fx_in)" in result.glsl)
        require("vec4 fx_warp(vec4 fx_in)" in result.glsl)
        require("fx_palette" in result.glsl)
        require("fx_tonemap" in result.glsl)
        require("vec4 warp(" !in result.glsl)
    }

    private fun prunesDroppedValues() {
        val result = StackSLCompiler.compile(
            """
            1 2 + drop
            uv fbm palette
            """.trimIndent(),
            StackSLCompileOptions(prefix = "prune")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require(result.analysis.prunedValues >= 1) { "Expected at least one pruned value: ${result.analysis}" }
        require("vec4(1.0) + vec4(2.0)" !in result.glsl) { "Dropped expression should not be emitted:\n${result.glsl}" }
    }

    private fun reportsStrictAndTolerantUnknownWords() {
        val strict = StackSLCompiler.compile("uv shimmerize", StackSLCompileOptions(prefix = "strict"))
        require(!strict.ok)
        require(strict.diagnostics.any { it.severity == StackSLSeverity.ERROR && "Unknown StackSL word" in it.message })

        val tolerant = StackSLCompiler.compile(
            "uv shimmerize",
            StackSLCompileOptions(prefix = "loose", tolerant = true)
        )
        require(tolerant.ok) { tolerant.diagnostics.joinToString("\n") }
        require(tolerant.diagnostics.any { it.severity == StackSLSeverity.WARNING && "Treating it as noop" in it.message })
    }

    private fun keepsShortMutatedProgramsValid() {
        val result = StackSLCompiler.compile(
            """
            drop
            1 +
            ema-diff
            """.trimIndent(),
            StackSLCompileOptions(prefix = "mutant", tolerant = true)
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("vec4 mutant(vec4 mutant_in)" in result.glsl)
        require("mutant_ema" in result.glsl)
        require(result.diagnostics.any { "wrapped stack values" in it.message || "Stack underflow" in it.message })
    }

    private fun emitsSamplerEnvironment() {
        val result = StackSLCompiler.compile(
            """
            uv 0.015 * sample
            ema saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "ping", sampleTextures = listOf("state"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uniform sampler2D state;" in result.glsl)
        require("uniform sampler2D ping_state;" !in result.glsl)
        require("ping_sample(state, ping_in" in result.glsl)
        require(result.analysis.requiredSamplers == setOf("state")) { result.analysis.requiredSamplers.toString() }
    }

    private fun lengthUsesSpatialChannelsByDefault() {
        val result = StackSLCompiler.compile(
            "uv 0.5 - length pulse white *",
            StackSLCompileOptions(prefix = "ring")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("vec4(length(_ring_v3.xy))" in result.glsl) { result.glsl }
        require("vec4(length(_ring_v3))" !in result.glsl) { result.glsl }
    }

    private fun emitsColorSpaceHelpers() {
        val result = StackSLCompiler.compile(
            """
            uv ryb
            rgb2ryb ryb2rgb
            red rgb2oklab
            blue rgb2oklab
            0.5 mix
            oklab2rgb
            +
            saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "color")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("color_ryb2rgb" in result.glsl) { result.glsl }
        require("color_rgb2ryb" in result.glsl) { result.glsl }
        require("color_rgb2oklab" in result.glsl) { result.glsl }
        require("color_oklab2rgb" in result.glsl) { result.glsl }
    }

    private fun compilesCenteredModifierProgram() {
        val result = StackSLCompiler.compile(
            """
            p ]wr18
            0.4sh4p0.1 +
            0.35n2 +
            0.2grain80 +
            palette
            0.25 |fbm3 +
            p length ]sh20 +
            p fbm ]c2p0.2 +
            0.006_0.09 median +
            saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "mods", sampleTextures = listOf("state"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("mods_valueNoise1" in result.glsl) { result.glsl }
        require("mods_hash11(floor" in result.glsl) { result.glsl }
        require("mods_fbm" in result.glsl) { result.glsl }
        require("mods_palette" in result.glsl) { result.glsl }
        require("mods_median4" in result.glsl) { result.glsl }
        require("vec4(mods_in.xy - vec2(0.5), 0.0, 1.0)" in result.glsl) { result.glsl }
    }

    private fun externalizesDanglingWires() {
        val result = StackSLCompiler.compile(
            "p length 0.5 0.08 pulse white *",
            StackSLCompileOptions(prefix = "wires", externalizeConstants = true)
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require(result.analysis.danglingWires.isNotEmpty()) { result.analysis.toString() }
        require("const int wires_wire_count" in result.glsl) { result.glsl }
        require("void wires_init_wires" in result.glsl) { result.glsl }
        require("float wires_wires[wires_wire_count]" in result.glsl) { result.glsl }
        require("return wires(wires_in, wires_wires);" in result.glsl) { result.glsl }
    }

    private fun compilesStoreReadWriteWords() {
        val result = StackSLCompiler.compile(
            """
            0-> 0.985 *
            p length 0.5 0.08 pulse
            +
            dup ->0
            dup 0.65 * ->2
            saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "store", sampleTextures = listOf("state"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uniform sampler2D state;" in result.glsl) { result.glsl }
        require("store_sample(state, store_in, vec4(0.0))" in result.glsl) { result.glsl }
        require("vec4 _store_out =" in result.glsl) { result.glsl }
        require("_store_out.x =" in result.glsl) { result.glsl }
        require("_store_out.z =" in result.glsl) { result.glsl }
        require(result.analysis.functions.last().channelWrites == setOf(0, 2)) {
            result.analysis.functions.last().toString()
        }
    }

    private fun inlinesRepeatedDefinitionForCse() {
        val result = StackSLCompiler.compile(
            """
            : ring
              0-> 0.982 *
              p length 0.42 0.035 pulse 0.08 *
              +
            ;

            drop
            input ring
            input ring
            + 0.5 *
            """.trimIndent(),
            StackSLCompileOptions(prefix = "cse", sampleTextures = listOf("state"), externalizeConstants = true)
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        val rootSource = result.glsl.substringAfter("vec4 cse(vec4 cse_in, float cse_wires[cse_wire_count]) {")
        val sharedSample = "cse_sample(state, cse_in, vec4(0.0))"
        require(rootSource.indexOf(sharedSample) >= 0) { result.glsl }
        require(rootSource.indexOf(sharedSample) == rootSource.lastIndexOf(sharedSample)) { result.glsl }
        require(result.analysis.danglingWires.map { it.initial } == listOf("0.982", "0.42", "0.035", "0.08", "0.5")) {
            result.analysis.danglingWires.toString()
        }
    }

    private fun compilesExplicitArityVariants() {
        val result = StackSLCompiler.compile(
            """
            time
            uv 0.4 pulse.2
            +
            time
            uv 0.1 1.4 grade.3
            +
            time
            uv .x 2 fmchirp.2
            +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "arity_variants")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require(result.diagnostics.isEmpty()) { result.diagnostics.joinToString("\n") }
    }

    private fun compilesStackBlastOperators() {
        val result = StackSLCompiler.compile(
            """
            1 2 3 4 mean.4
            5 6 7 median.3
            8 9 10 min.3
            11 12 13 max.3
            sort.4
            intermediate.3
            mean.all
            """.trimIndent(),
            StackSLCompileOptions(prefix = "blast")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("min(" in result.glsl) { result.glsl }
        require("max(" in result.glsl) { result.glsl }
        require("vec4(0.5)" in result.glsl) { result.glsl }
    }

    private fun compilesFMChirpOperator() {
        val short = StackSLCompiler.compile(
            "uv .x fmchirp.1",
            StackSLCompileOptions(prefix = "chirp_short")
        )

        require(short.ok) { short.diagnostics.joinToString("\n") }
        require("chirp_short_fmchirpSignedPow" in short.glsl) { short.glsl }
        require("chirp_short_fmchirp(" in short.glsl) { short.glsl }
        require("chirp_short_fmchirp(_chirp_short_v" in short.glsl) { short.glsl }

        val full = StackSLCompiler.compile(
            """
            uv .x
            2
            10
            0
            0
            0.25
            -0.5
            1
            0.8
            fmchirp
            """.trimIndent(),
            StackSLCompileOptions(prefix = "chirp_full")
        )

        require(full.ok) { full.diagnostics.joinToString("\n") }
        require("chirp_full_fmchirpSignedPow" in full.glsl) { full.glsl }
        require("chirp_full_fmchirp(" in full.glsl) { full.glsl }
        require("chirp_full_fmchirp(_chirp_full_v" in full.glsl) { full.glsl }
        require("vec4(2.0), vec4(10.0), vec4(0.0), vec4(0.0), vec4(0.25), vec4(-0.5), vec4(1.0), vec4(0.8))" in full.glsl) {
            full.glsl
        }

        val eightParam = StackSLCompiler.compile(
            """
            2
            10
            0
            0
            0.25
            -0.5
            1
            0.8
            fmchirp.8
            """.trimIndent(),
            StackSLCompileOptions(prefix = "chirp_params")
        )

        require(eightParam.ok) { eightParam.diagnostics.joinToString("\n") }
        require("vec4(chirp_params_in.x)" in eightParam.glsl) { eightParam.glsl }
        require("chirp_params_fmchirp(_chirp_params_v" in eightParam.glsl) { eightParam.glsl }
        require("vec4(2.0), vec4(10.0), vec4(0.0), vec4(0.0), vec4(0.25), vec4(-0.5), vec4(1.0), vec4(0.8))" in eightParam.glsl) {
            eightParam.glsl
        }

        val eightParamWithPriorStack = StackSLCompiler.compile(
            """
            uv
            2
            10
            0
            0
            0.25
            -0.5
            1
            0.8
            fmchirp.8
            +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "chirp_params_plus")
        )

        require(eightParamWithPriorStack.ok) { eightParamWithPriorStack.diagnostics.joinToString("\n") }
    }

    private fun compilesBitGlitchOperators() {
        val result = StackSLCompiler.compile(
            """
            p length
            time gray-code
            xor
            dup gray-decode
            lfsr
            bitxor
            4 bitcrush.2
            bitcrush.1
            saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "bits")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uvec4 bits_toBits16" in result.glsl) { result.glsl }
        require("vec4 bits_xor" in result.glsl) { result.glsl }
        require("vec4 bits_grayCode" in result.glsl) { result.glsl }
        require("vec4 bits_grayDecode" in result.glsl) { result.glsl }
        require("vec4 bits_lfsr" in result.glsl) { result.glsl }
        require("vec4 bits_bitcrush" in result.glsl) { result.glsl }
        require("bits_xor(" in result.glsl) { result.glsl }
        require("bits_grayCode(" in result.glsl) { result.glsl }
        require("bits_grayDecode(" in result.glsl) { result.glsl }
        require("bits_lfsr(" in result.glsl) { result.glsl }
        require("bits_bitcrush(" in result.glsl) { result.glsl }
    }
}

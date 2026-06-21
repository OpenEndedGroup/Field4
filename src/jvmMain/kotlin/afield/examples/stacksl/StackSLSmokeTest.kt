package afield.examples.stacksl

import afield.Box
import afield.four.modules.ShaderKind
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
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
            emitsNeighborhoodSamplerHelpers()
            usesXyzHashTimeInputConvention()
            lengthUsesSpatialChannelsByDefault()
            emitsColorSpaceHelpers()
            compilesCenteredModifierProgram()
            compilesStackedSuffixModifiers()
            externalizesDanglingWires()
            compilesStoreReadWriteWords()
            inlinesRepeatedDefinitionForCse()
            compilesExplicitArityVariants()
            compilesStackBlastOperators()
            compilesFMChirpOperator()
            compilesBitGlitchOperators()
            compilesOneMinusOperator()
            compilesGeometryOperatorsAndVecLiterals()
            compilesShapingDomainAndVectorUtilityOperators()
            compilesNeighborhoodFieldOperators()
            compilesLocalNamedMarks()
            compilesExternalHostGlslEscapeHatches()
            expandsStackAutoSamplerOverrides()
            compilesCreativeFreedomVariants()
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

    private fun emitsNeighborhoodSamplerHelpers() {
        val result = StackSLCompiler.compile(
            """
            uv 0.01 crossSample
            uv 0.02 rCrossSample.r
            +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "cross", sampleTextures = listOf("state"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uniform sampler2D state;" in result.glsl) { result.glsl }
        require("cross_crossSample(state" in result.glsl) { result.glsl }
        require("cross_randomCrossSampleChannel(state" in result.glsl) { result.glsl }
        require("cross_hash21" in result.glsl) { result.glsl }
        require(result.analysis.requiredSamplers == setOf("state")) { result.analysis.requiredSamplers.toString() }
    }

    private fun usesXyzHashTimeInputConvention() {
        val result = StackSLCompiler.compile(
            """
            time
            hash +
            0.5t +
            0.25c +
            0.35hash +
            0.2grain80 +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "input_convention")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("vec4(input_convention_in.w)" in result.glsl) { result.glsl }
        require("vec4(input_convention_in.z)" in result.glsl) { result.glsl }
        require("vec4(0.5) * vec4(input_convention_in.w)" in result.glsl) { result.glsl }
        require("input_convention_in.w * 1.0" in result.glsl) { result.glsl }
        require("input_convention_in.z) * 1.0" in result.glsl) { result.glsl }
        require("input_convention_hash21((input_convention_in.xy" in result.glsl && "+ vec2(input_convention_in.w)" in result.glsl) {
            result.glsl
        }
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

    private fun compilesStackedSuffixModifiers() {
        val result = StackSLCompiler.compile(
            """
            0.4s3q8
            0.2 |s5q4 +
            p ]fbm4q8 +
            saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "suffix_stack")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("sin((suffix_stack_in.w * 3.0 + 0.0))" in result.glsl) { result.glsl }
        require("floor(clamp(vec4(((sin((suffix_stack_in.w * 3.0 + 0.0)) + 1.0) * 0.5))" in result.glsl) {
            result.glsl
        }
        require("vec4(max(abs(8.0), 2.0) - 1.0)" in result.glsl) { result.glsl }
        require("suffix_stack_fbm" in result.glsl) { result.glsl }
        require(result.analysis.functions.last().finalStackDepth >= 1) { result.analysis.functions.last().toString() }
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

    private fun compilesOneMinusOperator() {
        val result = StackSLCompiler.compile(
            "uv 1-",
            StackSLCompileOptions(prefix = "one_minus")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("(vec4(1.0) - " in result.glsl) { result.glsl }
    }

    private fun compilesGeometryOperatorsAndVecLiterals() {
        val result = StackSLCompiler.compile(
            """
            [1,2,3,4]
            [0.25,0.5]
            rotate
            [0.5,0.5,0.25,0.25]
            [0,0,1,1]
            rotates
            [9,8,7,6] >z
            [1,2,3,4] [2,4,6,8] distance2
            [1,2,3,4] [2,4,6,8] distance3
            +
            [1,2,3,4] [2,4,6,8] distance
            +
            [3,4,5,6] [1,1,1,1] 2 standoff
            +
            [3,4,5,6] [1,1,1,1] 2 standoff2
            +
            [3,4,5,6] [1,1,1,1] 2 standoff3
            +
            +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "geom")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("vec4(1.0, 2.0, 3.0, 4.0)" in result.glsl) { result.glsl }
        require("vec4(0.25, 0.5, 0.0, 1.0)" in result.glsl) { result.glsl }
        require("cos(" in result.glsl && "sin(" in result.glsl) { result.glsl }
        require(".z" in result.glsl) { result.glsl }
        require("distance(" in result.glsl && ".xy" in result.glsl && ".xyz" in result.glsl) { result.glsl }
        require("vec4 geom_safeNormalize(vec4 value)" in result.glsl) { result.glsl }
        require("vec4 geom_safeNormalize2(vec4 value)" in result.glsl) { result.glsl }
        require("vec4 geom_safeNormalize3(vec4 value)" in result.glsl) { result.glsl }
        require("geom_safeNormalize(" in result.glsl && "geom_safeNormalize2(" in result.glsl && "geom_safeNormalize3(" in result.glsl) {
            result.glsl
        }
    }

    private fun compilesShapingDomainAndVectorUtilityOperators() {
        val result = StackSLCompiler.compile(
            """
            uv 8 tile
            p 6 kaleido
            3 twist
            2 spiral
            polar cart
            fbm
            smooth
            0.62 bias
            0.35 gain
            sigmoid
            5 softplus
            0.45 threshold
            0.5 0.12 band
            0.2 0.8 ramp
            7 posterize
            dup sum
            over prod +
            over any +
            over all +
            over x+y +
            over xylen +
            rgbnorm
            saturate
            """.trimIndent(),
            StackSLCompileOptions(prefix = "new_ops")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("new_ops_bias" in result.glsl) { result.glsl }
        require("new_ops_gain" in result.glsl) { result.glsl }
        require("new_ops_kaleido" in result.glsl) { result.glsl }
        require("new_ops_twist" in result.glsl) { result.glsl }
        require("new_ops_spiral" in result.glsl) { result.glsl }
        require("new_ops_polar" in result.glsl && "new_ops_cart" in result.glsl) { result.glsl }
        require("new_ops_sum4" in result.glsl) { result.glsl }
        require("new_ops_prod4" in result.glsl) { result.glsl }
        require("new_ops_any4" in result.glsl) { result.glsl }
        require("new_ops_all4" in result.glsl) { result.glsl }
    }

    private fun compilesNeighborhoodFieldOperators() {
        val result = StackSLCompiler.compile(
            """
            laplace
            0.002 blur
            p 0.003 blur9
            p 0.004 sobel
            p 0.005 gradient
            p 0.006 divergence
            p 0.007 curl
            + + + + + +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "field_ops", sampleTextures = listOf("state"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uniform sampler2D state;" in result.glsl) { result.glsl }
        require("field_ops_laplace(state" in result.glsl) { result.glsl }
        require("field_ops_blur5(state" in result.glsl) { result.glsl }
        require("field_ops_blur9(state" in result.glsl) { result.glsl }
        require("field_ops_sobel(state" in result.glsl) { result.glsl }
        require("field_ops_gradient(state" in result.glsl) { result.glsl }
        require("field_ops_divergence(state" in result.glsl) { result.glsl }
        require("field_ops_curl(state" in result.glsl) { result.glsl }
        require(result.analysis.requiredSamplers == setOf("state")) { result.analysis.requiredSamplers.toString() }
    }

    private fun compilesLocalNamedMarks() {
        val compact = StackSLCompiler.compile(
            """
            uv 8 * =coord
            coord fbm
            coord 0.2t + fbm
            0.5 mix
            """.trimIndent(),
            StackSLCompileOptions(prefix = "marks_compact")
        )

        require(compact.ok) { compact.diagnostics.joinToString("\n") }
        require("marks_compact_fbm" in compact.glsl) { compact.glsl }
        require("Unknown StackSL" !in compact.diagnostics.joinToString("\n")) { compact.diagnostics.joinToString("\n") }

        val explicit = StackSLCompiler.compile(
            """
            uv 8 * >coord
            <coord fbm
            <coord 0.2t + fbm
            0.5 mix
            """.trimIndent(),
            StackSLCompileOptions(prefix = "marks_explicit")
        )

        require(explicit.ok) { explicit.diagnostics.joinToString("\n") }
        require("marks_explicit_fbm" in explicit.glsl) { explicit.glsl }
        require(explicit.analysis.functions.last().finalStackDepth == 2) { explicit.analysis.functions.last().toString() }

        val strict = StackSLCompiler.compile("<missing fbm", StackSLCompileOptions(prefix = "marks_strict"))
        require(!strict.ok) { strict.glsl }
        require(strict.diagnostics.any { it.severity == StackSLSeverity.ERROR && "Unknown StackSL local mark 'missing'" in it.message }) {
            strict.diagnostics.joinToString("\n")
        }

        val tolerant = StackSLCompiler.compile(
            "<missing fbm",
            StackSLCompileOptions(prefix = "marks_tolerant", tolerant = true)
        )
        require(tolerant.ok) { tolerant.diagnostics.joinToString("\n") }
        require(tolerant.diagnostics.any { it.severity == StackSLSeverity.WARNING && "Unknown StackSL local mark 'missing'" in it.message }) {
            tolerant.diagnostics.joinToString("\n")
        }
    }

    private fun compilesExternalHostGlslEscapeHatches() {
        val result = StackSLCompiler.compile(
            """
            ?pc.foo
            0.5 *
            dup ->?pc.foo
            ?pc.inData.p[idx].posvel.Z +
            dup ->?pc.inData.p[idx].posvel.Z
            ?pc.bar +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "host")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("pc.foo * vec4(0.5)" in result.glsl) { result.glsl }
        require("+ pc.inData.p[idx].posvel.Z" in result.glsl) { result.glsl }
        require("+ pc.bar" in result.glsl) { result.glsl }
        require(Regex("""pc\.foo = _host_v\d+;""").containsMatchIn(result.glsl)) { result.glsl }
        require(Regex("""pc\.inData\.p\[idx]\.posvel\.Z = _host_v\d+;""").containsMatchIn(result.glsl)) { result.glsl }
    }

    private fun expandsStackAutoSamplerOverrides() {
        val box = Box("stack-auto-sampler-smoke")
        box.stackAutoShaderSources["foo"] = """
            : tap 0.2 sample ;
            0.1 sample
        """.trimIndent()

        val direct = preprocess(
            box,
            "{{ stack.auto.foo@noiseFieldView }}",
            ShaderKind.FRAGMENT_SHADER
        )
        require("foo_sample(noiseFieldView" in direct) { direct }
        require("foo_sample(source" !in direct) { direct }

        val entrypoint = preprocess(
            box,
            "{{ stack.auto.foo:tap@noiseFieldView }}",
            ShaderKind.FRAGMENT_SHADER
        )
        require("foo_sample(noiseFieldView" in entrypoint) { entrypoint }
        require("foo_sample(source" !in entrypoint) { entrypoint }
    }

    private fun compilesCreativeFreedomVariants() {
        val rawMix = StackSLCompiler.compile("red blue 1.5 mix", StackSLCompileOptions(prefix = "raw_mix"))
        require(rawMix.ok) { rawMix.diagnostics.joinToString("\n") }
        require("mix(vec4(1.0, 0.0, 0.0, 1.0), vec4(0.0, 0.0, 1.0, 1.0), vec4(1.5))" in rawMix.glsl) {
            rawMix.glsl
        }
        require("clamp(vec4(1.5)" !in rawMix.glsl) { rawMix.glsl }

        val clampedMix = StackSLCompiler.compile("red blue 1.5 mix.c", StackSLCompileOptions(prefix = "clamped_mix"))
        require(clampedMix.ok) { clampedMix.diagnostics.joinToString("\n") }
        require("clamp(vec4(1.5), vec4(0.0), vec4(1.0))" in clampedMix.glsl) { clampedMix.glsl }

        val softMix = StackSLCompiler.compile("red blue 1.5 mix.sc", StackSLCompileOptions(prefix = "soft_mix"))
        require(softMix.ok) { softMix.diagnostics.joinToString("\n") }
        require("soft_mix_softClamp01" in softMix.glsl) { softMix.glsl }

        val select = StackSLCompiler.compile("red blue 1 select", StackSLCompileOptions(prefix = "select_test"))
        require(select.ok) { select.diagnostics.joinToString("\n") }
        require("step(vec4(0.5), abs(vec4(1.0)))" in select.glsl) { select.glsl }

        val rawPow = StackSLCompiler.compile("-2 0.5 pow", StackSLCompileOptions(prefix = "raw_pow"))
        require(rawPow.ok) { rawPow.diagnostics.joinToString("\n") }
        require("pow(vec4(-2.0), vec4(0.5))" in rawPow.glsl) { rawPow.glsl }

        val safePow = StackSLCompiler.compile("-2 0.5 pow.safe", StackSLCompileOptions(prefix = "safe_pow"))
        require(safePow.ok) { safePow.diagnostics.joinToString("\n") }
        require("pow(max(abs(vec4(-2.0)), vec4(0.00001)), vec4(0.5))" in safePow.glsl) { safePow.glsl }

        val rawSample = StackSLCompiler.compile("0.1 sample", StackSLCompileOptions(prefix = "raw_sample", sampleTextures = listOf("state")))
        require(rawSample.ok) { rawSample.diagnostics.joinToString("\n") }
        require("texture(sourceTexture, base.xy + offset.xy)" in rawSample.glsl) { rawSample.glsl }
        require("fract(base.xy + offset.xy)" !in rawSample.glsl) { rawSample.glsl }

        val wrappedSample = StackSLCompiler.compile("0.1 sample.w", StackSLCompileOptions(prefix = "wrap_sample", sampleTextures = listOf("state")))
        require(wrappedSample.ok) { wrappedSample.diagnostics.joinToString("\n") }
        require("wrap_sample_sample_w" in wrappedSample.glsl) { wrappedSample.glsl }
        require("fract(base.xy + offset.xy)" in wrappedSample.glsl) { wrappedSample.glsl }

        val rawEma = StackSLCompiler.compile("input prev 1.25 ema.3", StackSLCompileOptions(prefix = "raw_ema", sampleTextures = listOf("state")))
        require(rawEma.ok) { rawEma.diagnostics.joinToString("\n") }
        require("return mix(previous, current, alpha);" in rawEma.glsl) { rawEma.glsl }

        val clampedEma = StackSLCompiler.compile("input prev 1.25 ema.c.3", StackSLCompileOptions(prefix = "clamped_ema", sampleTextures = listOf("state")))
        require(clampedEma.ok) { clampedEma.diagnostics.joinToString("\n") }
        require("clamp(vec4(1.25), vec4(0.0), vec4(1.0))" in clampedEma.glsl) { clampedEma.glsl }

        val softEma = StackSLCompiler.compile("input prev 1.25 ema.sc.3", StackSLCompileOptions(prefix = "soft_ema", sampleTextures = listOf("state")))
        require(softEma.ok) { softEma.diagnostics.joinToString("\n") }
        require("soft_ema_softClamp01" in softEma.glsl) { softEma.glsl }

        val rawAlpha = StackSLCompiler.compile("-0.08 0.5 8 alpha", StackSLCompileOptions(prefix = "raw_alpha"))
        require(rawAlpha.ok) { rawAlpha.diagnostics.joinToString("\n") }
        require("raw_alpha_alpha" in rawAlpha.glsl) { rawAlpha.glsl }
        require("lessThan(baseAlpha, vec4(0.0))" in rawAlpha.glsl) { rawAlpha.glsl }
        require("max(abs(baseAlpha), vec4(0.000001))" in rawAlpha.glsl) { rawAlpha.glsl }

        val clampedAlpha = StackSLCompiler.compile("-0.08 2 8 alpha.c", StackSLCompileOptions(prefix = "clamped_alpha"))
        require(clampedAlpha.ok) { clampedAlpha.diagnostics.joinToString("\n") }
        require("clamped_alpha_alpha_c" in clampedAlpha.glsl) { clampedAlpha.glsl }
        require("clamp(baseAlpha, vec4(0.000001), vec4(0.999999))" in clampedAlpha.glsl) { clampedAlpha.glsl }
        require("clamp(modulation, vec4(-1.0), vec4(1.0))" in clampedAlpha.glsl) { clampedAlpha.glsl }

        val softAlpha = StackSLCompiler.compile("0.08 2 8 alpha.sc", StackSLCompileOptions(prefix = "soft_alpha"))
        require(softAlpha.ok) { softAlpha.diagnostics.joinToString("\n") }
        require("soft_alpha_alpha_sc" in softAlpha.glsl) { softAlpha.glsl }
        require("soft_alpha_softClamp01" in softAlpha.glsl) { softAlpha.glsl }

        val rawRyb = StackSLCompiler.compile("[1.2,-0.1,0.5,1] ryb", StackSLCompileOptions(prefix = "raw_ryb"))
        require(rawRyb.ok) { rawRyb.diagnostics.joinToString("\n") }
        require("vec3 c = color.rgb;" in rawRyb.glsl) { rawRyb.glsl }
        require("vec3 c = clamp(color.rgb" !in rawRyb.glsl) { rawRyb.glsl }

        val clampedRyb = StackSLCompiler.compile("[1.2,-0.1,0.5,1] ryb.c", StackSLCompileOptions(prefix = "clamped_ryb"))
        require(clampedRyb.ok) { clampedRyb.diagnostics.joinToString("\n") }
        require("clamped_ryb_ryb2rgb_c" in clampedRyb.glsl) { clampedRyb.glsl }
        require("clamp(color.rgb, vec3(0.0), vec3(1.0))" in clampedRyb.glsl) { clampedRyb.glsl }
    }
}

package afield.examples.stacksl

import afield.Box
import afield.four.modules.ShaderKind
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderReport
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
            compilesGlslStyleMinMaxClampAndStackSelectors()
            compilesGeometryOperatorsAndVecLiterals()
            compilesShapingDomainAndVectorUtilityOperators()
            compilesNeighborhoodFieldOperators()
            compilesSamplerSizeAndChannelGradientOperators()
            compilesLocalNamedMarks()
            compilesWordReferences()
            compilesHigherOrderPhacelleNoise()
            compilesExternalHostGlslEscapeHatches()
            expandsStackAutoSamplerOverrides()
            reportsAStackAutoProgramsArityAndWarnings()
            compilesDocsGalleryPrograms()
            loadsGalleryWorkspaceMetadataEdn()
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

    private fun compilesGlslStyleMinMaxClampAndStackSelectors() {
        val result = StackSLCompiler.compile(
            """
            1 2 max
            3 min.1
            +
            0 0.25 1 clamp
            +
            10 20 30 40 [3x,2x,1x,0x]
            +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "glsl_edges")
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("max(vec4(1.0), vec4(2.0))" in result.glsl) { result.glsl }
        require("glsl_edges_min4(vec4(3.0))" in result.glsl) { result.glsl }
        require("clamp(vec4(0.0), vec4(0.25), vec4(1.0))" in result.glsl) { result.glsl }
        require("vec4(vec4(10.0).x, vec4(20.0).x, vec4(30.0).x, vec4(40.0).x)" in result.glsl) { result.glsl }
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

    private fun compilesSamplerSizeAndChannelGradientOperators() {
        val result = StackSLCompiler.compile(
            """
            texelSize gradient.r
            uv texelSize gradient.g.2
            +
            texelSize1 +
            textureSize0 0.0 * +
            """.trimIndent(),
            StackSLCompileOptions(prefix = "sizes", sampleTextures = listOf("state", "other"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uniform sampler2D state;" in result.glsl) { result.glsl }
        require("uniform sampler2D other;" in result.glsl) { result.glsl }
        require("textureSize(state, 0)" in result.glsl) { result.glsl }
        require("textureSize(other, 0)" in result.glsl) { result.glsl }
        require("sizes_gradientChannel(state" in result.glsl) { result.glsl }
        require("sizes_gradient(state" !in result.glsl) { result.glsl }
        require(result.analysis.requiredSamplers == setOf("state", "other")) { result.analysis.requiredSamplers.toString() }
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

    private fun compilesWordReferences() {
        val direct = StackSLCompiler.compile(
            """
            : pattern
              normalize
            ;

            'pattern p apply
            """.trimIndent(),
            StackSLCompileOptions(prefix = "wordref_direct")
        )

        require(direct.ok) { direct.diagnostics.joinToString("\n") }
        require(Regex("""vec4 _wordref_direct_v\d+ = normalize\(_wordref_direct_v\d+\);""").containsMatchIn(direct.glsl)) {
            direct.glsl
        }
        require(direct.analysis.functions.last().calls == setOf("pattern")) { direct.analysis.functions.last().toString() }

        val passedThrough = StackSLCompiler.compile(
            """
            : pattern
              xylen
            ;

            : mapped
              apply
            ;

            'pattern uv mapped
            """.trimIndent(),
            StackSLCompileOptions(prefix = "wordref_map")
        )

        require(passedThrough.ok) { passedThrough.diagnostics.joinToString("\n") }
        require(Regex("""vec4 _wordref_map_v\d+ = vec4\(length\(_wordref_map_v\d+\.xy\)\);""").containsMatchIn(passedThrough.glsl)) {
            passedThrough.glsl
        }
        require(passedThrough.analysis.functions.last().calls.containsAll(setOf("mapped", "pattern"))) {
            passedThrough.analysis.functions.last().toString()
        }

        val builtin = StackSLCompiler.compile(
            "'normalize p apply",
            StackSLCompileOptions(prefix = "wordref_builtin")
        )

        require(builtin.ok) { builtin.diagnostics.joinToString("\n") }
        require(Regex("""vec4 _wordref_builtin_v\d+ = normalize\(_wordref_builtin_v\d+\);""").containsMatchIn(builtin.glsl)) {
            builtin.glsl
        }

        val unknown = StackSLCompiler.compile("'missing p apply", StackSLCompileOptions(prefix = "wordref_strict"))
        require(!unknown.ok) { unknown.glsl }
        require(unknown.diagnostics.any { it.severity == StackSLSeverity.ERROR && "Unknown StackSL word reference 'missing'" in it.message }) {
            unknown.diagnostics.joinToString("\n")
        }
    }

    private fun compilesHigherOrderPhacelleNoise() {
        val result = StackSLCompiler.compile(
            """
            : pattern
              input sample-abs normalize
            ;

            'pattern p 100 * 1.5 0.2 1 phacelle.4
            """.trimIndent(),
            StackSLCompileOptions(prefix = "phacelle_ref", sampleTextures = listOf("state"))
        )

        require(result.ok) { result.diagnostics.joinToString("\n") }
        require("uniform sampler2D state;" in result.glsl) { result.glsl }
        require("vec4 phacelle_ref_phacelle_pattern(vec4 pValue, float freq, float offset, float normalization)" in result.glsl) {
            result.glsl
        }
        require("for (int i = -1; i <= 2; i++)" in result.glsl) { result.glsl }
        require("phacelle_ref_pattern(vec4(cellPoint, pValue.zw))" in result.glsl) { result.glsl }
        require("phacelle_ref_sampleAbs(state" in result.glsl) { result.glsl }
        require(result.analysis.functions.last().calls.contains("pattern")) { result.analysis.functions.last().toString() }

        val trailingReference = StackSLCompiler.compile(
            """
            : pattern
              normalize
            ;

            p 4 * 1.5 0.2 1 'pattern directional-noise.4
            """.trimIndent(),
            StackSLCompileOptions(prefix = "phacelle_trailing")
        )

        require(trailingReference.ok) { trailingReference.diagnostics.joinToString("\n") }
        require("phacelle_trailing_phacelle_pattern" in trailingReference.glsl) { trailingReference.glsl }

        val defaultPattern = StackSLCompiler.compile(
            "p 4 * phacelle",
            StackSLCompileOptions(prefix = "phacelle_default")
        )

        require(defaultPattern.ok) { defaultPattern.diagnostics.joinToString("\n") }
        require("phacelle_default_phacelle_default" in defaultPattern.glsl) { defaultPattern.glsl }
        require("normalize(vec4(cellPoint, pValue.zw))" in defaultPattern.glsl) { defaultPattern.glsl }
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

    /**
     * `stack.auto` must report what a successful compile does not say.
     *
     * `StackSLCompileResult.ok` is errors only, and a stack language's characteristic mistake is not an
     * error: a definition opened with `=name` (which *copies*, `a -- a`) instead of `>name` (which
     * *moves*, `a --`) leaves its input under its result, so every call pushes two values and everything
     * downstream takes the wrong operand. The compiler prunes the surplus and says nothing.
     *
     * That cost a real debugging session, so this pins both halves: the broken shape is reported, and a
     * correct program stays quiet. The second half is what keeps the report worth reading.
     */
    private fun reportsAStackAutoProgramsArityAndWarnings() {
        // `=c` copies, so `half` leaves its input under its result: net +1 instead of net 0.
        val unbalanced = """
            : half
              =c
              <c 0.5 *
            ;

            input half
        """.trimIndent()

        val bad = stackAutoShaderReport(unbalanced, "noiseForce")
        require(bad.compiled) { "the unbalanced program should still compile; that is the whole problem" }
        require(!bad.quiet) { "an unbalanced definition must not be reported as clean: $bad" }
        require(bad.problems.size == 1) { "expected exactly one problem, got ${bad.problems}" }
        require("`half`" in bad.problems[0]) { "the problem should name the definition: ${bad.problems[0]}" }
        require("2 values" in bad.problems[0]) { "the problem should state the depth: ${bad.problems[0]}" }
        require(">mark" in bad.problems[0]) { "the problem should name the fix: ${bad.problems[0]}" }
        // And it renders, because an output area is where this is actually read.
        require("problem" in bad.toHTML()) { bad.toHTML() }

        // `>c` moves, so the same definition balances.
        val good = stackAutoShaderReport(unbalanced.replace("=c", ">c"), "noiseForce")
        require(good.quiet) { "the balanced program should be clean, got: $good" }
        require("clean" in good.toString()) { good.toString() }
        require(good.problems.isEmpty() && good.warnings.isEmpty()) { "$good" }

        // A blank slot compiles to the stable no-op fallback and has nothing to report.
        require(stackAutoShaderReport("", "noiseForce").quiet) { "a blank slot should be quiet" }

        // Non-ERROR diagnostics have to survive to the report: they were dropped on the success path,
        // which is how a second bug in the same session went unseen. A root's stack starts with the
        // program's input on it, so a binary word on its own finds one value where it needs two.
        val underflow = stackAutoShaderReport("*", "noiseForce")
        require(underflow.compiled) { "an arity underflow is a warning, not an error: $underflow" }
        require(underflow.warnings.isNotEmpty()) {
            "the compiler's non-error diagnostics must reach the report, got: $underflow"
        }
        require(!underflow.quiet) { "a program with warnings is not clean: $underflow" }

        // A root legitimately ends deeper than 1 — its stack starts with the program's input on it — so
        // depth is only checked for definitions. Without this the check would fire on `"input x 12 *"`.
        val root = stackAutoShaderReport("input x 12 *", "edgeForce")
        require(root.quiet) { "a root that pushes a second copy of its input is not a defect: $root" }

        // And a definition nothing calls is not a claim about behaviour. This shape exists to be used as
        // an *entrypoint* (`{{ stack.auto.foo:tap }}`), where it gets root semantics and so ends at 2.
        // Flagging it was this check's first false positive, caught by expandsStackAutoSamplerOverrides.
        val entrypointOnly = stackAutoShaderReport(
            """
            : tap 0.2 sample ;
            0.1 sample
            """.trimIndent(),
            "foo"
        )
        require(entrypointOnly.quiet) {
            "an uncalled definition must not be flagged; its shape constrains nothing: $entrypointOnly"
        }
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

    private fun compilesDocsGalleryPrograms() {
        val gallery = afield.four.fabricated.StackSLGalleryHarness
        val examples = gallery.loadExamples()
        require(examples.isNotEmpty()) { "StackSL docs gallery has no examples" }

        val box = Box("stacksl-gallery-smoke")
        examples.forEach { example ->
            val result = StackSLCompiler.compile(
                example.program,
                StackSLCompileOptions(
                    prefix = "program",
                    includeHeader = false,
                    declareSamplers = false,
                    sampleTextures = listOf("source") // matches the {{ stack.auto.program }} default sampler
                )
            )
            require(result.ok) {
                "StackSL gallery program '${example.name}' failed to compile:\n" +
                    result.diagnostics.filter { it.severity == StackSLSeverity.ERROR }.joinToString("\n")
            }
            // The gallery injects each program through the documented stack.auto path,
            // so make sure the template actually emits `vec4 program(...)`.
            box.stackAutoShaderSources["program"] = example.program
            val expanded = preprocess(box, "{{ stack.auto.program }}", ShaderKind.FRAGMENT_SHADER)
            require("vec4 program(vec4 program_in)" in expanded) {
                "stack.auto expansion for gallery program '${example.name}' missing entrypoint:\n$expanded"
            }
        }
    }

    private fun loadsGalleryWorkspaceMetadataEdn() {
        // The gallery workspace is a real canonical_graphics-style Field document.
        // Parse its metadata.edn through Field's own EDN reader so a malformed
        // document (or a dropped stackSL property) fails here rather than only in
        // the offline viewer.
        val edn = java.io.File(
            System.getProperty("user.dir"),
            "${afield.four.fabricated.StackSLGalleryHarness.WORKSPACE_DIR}/metadata.edn"
        )
        require(edn.exists()) { "gallery workspace metadata.edn missing: ${edn.absolutePath}" }

        val transfers = afield.SavingContext().readEdnTransfersFromString(edn.readText())
        // Exclude the window/renderer parent box: it owns the graphics context the
        // example boxes inherit and carries no stackSL of its own.
        val boxes = transfers.filter { it.id != "root" && it.id != "renderer" }
        val expected = afield.four.fabricated.StackSLGalleryHarness.loadExamples().map { it.name }.toSet()
        val boxIds = boxes.map { it.id }.toSet()
        require(boxIds.containsAll(expected)) {
            "metadata.edn is missing boxes for workspace examples: ${expected - boxIds}"
        }
        val missingStackSL = boxes.filter { "stackSL" !in it.properties.keys }.map { it.id }
        require(missingStackSL.isEmpty()) {
            "gallery workspace boxes lack a stackSL property: $missingStackSL"
        }
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

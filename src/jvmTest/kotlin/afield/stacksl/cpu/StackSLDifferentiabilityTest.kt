package afield.stacksl.cpu

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler
import afield.stacksl.stackSLDocumentableWords
import afield.stdlib.ADTape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much of StackSL is differentiable — counted, partitioned, and floored.
 *
 * Two populations, because they answer two different questions and a single percentage would
 * blur them:
 *
 *  1. **The GLSL builtins the emitter actually produces.** Discovered by compiling every one of
 *     the compiler's own documentable words and scanning the emitted GLSL for calls, so the
 *     population is the language's, not a hand-kept sample. Each discovered builtin is then
 *     *probed through [GlslAdScalars]* — called with seeded arguments, swept backward, and
 *     classified by whether any adjoint is non-zero. The classification is a measurement, not a
 *     list, so it cannot drift from the code it describes.
 *  2. **The words a fit could move a parameter through.** `input 1.0 * <word>` puts one wire
 *     upstream of the word; if the word passes a derivative, that wire's gradient is non-zero.
 *     This is the number that answers "can I fit this program", and the blocked list has to be
 *     read before the ratio is: most blocked words do not *lack* a derivative, they **discard the
 *     value**. `uv`, `p`, `time`, `pi`, `tau`, `e`, `input`, `drop`, `swap` and the twelve named
 *     colours replace what was on the stack, so a wire upstream of them cannot reach the image by
 *     construction. The genuinely non-differentiable ones are the shapers and the predicates:
 *     `floor`, `ceil`, `round`, `step`, `threshold`, `posterize`, `hash`, `bit-crush`, `select`,
 *     `if` and the comparisons.
 *
 * #### The three categories, and why the zero one is not a gap
 *
 *  - **true derivative** — the adjoint is non-zero, so a gradient flows.
 *  - **zero by convention** — `floor`, `ceil`, `round`, `roundEven`, `trunc`, `sign`, `step` and
 *    every comparison/reduction carry **exactly zero**, which is the correct derivative of a
 *    locally constant function and the same convention `autodiff_forward.kt` documents. It is not
 *    a missing feature; it is the honest answer, and it is why a program that `threshold`s cannot
 *    be fitted above its threshold.
 *  - **unsupported** — refused with [GlslUnsupported] rather than answered wrongly. At the builtin
 *    level that is exactly the sampler family (`texture`, `textureSize`, `textureGather`), because
 *    the feedback programs it serves are excluded from every asserted comparison in this backend,
 *    so a derivative through it would have no oracle. At the word level it is those plus the bit
 *    operators, which reach the image only through `toBits16`/`fromBits16`.
 *
 * Every count below is asserted with the population named. A printed percentage behind a loose
 * floor is this project's most-recorded weakness, so the floors are two-sided where a two-sided
 * floor means anything: if the differentiable count *rises*, this test says so and asks for the
 * number to be raised rather than quietly banking the improvement.
 */
class StackSLDifferentiabilityTest {

    private fun wiredOptions() = StackSLCompileOptions(
        prefix = "program",
        includeHeader = false,
        declareSamplers = false,
        externalizeConstants = true,
        sampleTextures = listOf("source", "source", "source", "source")
    )

    // -- population 1: the emitted GLSL builtins ---------------------------------------------------

    @Test
    fun `every GLSL builtin the emitter produces is classified by probing the AD path`() {
        val words = stackSLDocumentableWords.sorted()
        assertTrue(words.size > 300, "expected the whole vocabulary, got ${words.size} words")

        val emitted = sortedSetOf<String>()
        words.forEach { word ->
            val result = StackSLCompiler.compile("input $word", wiredOptions())
            if (!result.ok) return@forEach
            GlslBuiltins.names.forEach { builtin ->
                if (Regex("""\b${Regex.escape(builtin)}\s*\(""").containsMatchIn(result.glsl)) {
                    emitted += builtin
                }
            }
        }

        val trueDerivative = sortedSetOf<String>()
        val zeroByConvention = sortedSetOf<String>()
        val unsupported = sortedSetOf<String>()
        val unprobed = sortedSetOf<String>()

        emitted.forEach { builtin ->
            when (classify(builtin)) {
                Verdict.TRUE_DERIVATIVE -> trueDerivative += builtin
                Verdict.ZERO -> zeroByConvention += builtin
                Verdict.UNSUPPORTED -> unsupported += builtin
                Verdict.NO_PROBE -> unprobed += builtin
            }
        }

        println("emitted GLSL builtins (${emitted.size}): $emitted")
        println("  true derivative (${trueDerivative.size}): $trueDerivative")
        println("  zero by convention (${zeroByConvention.size}): $zeroByConvention")
        println("  unsupported (${unsupported.size}): $unsupported")
        println("  no probe shape (${unprobed.size}): $unprobed")

        assertEquals(
            emptySet<String>(), unprobed.toSet(),
            "no argument shape in the probe list fits these builtins, so they are unclassified — " +
                "add a shape rather than letting them fall out of the count"
        )
        assertEquals(
            emitted.size, trueDerivative.size + zeroByConvention.size + unsupported.size,
            "the partition must cover the population exactly"
        )
        assertTrue(
            emitted.size >= MIN_EMITTED,
            "only ${emitted.size} GLSL builtins were found in the emitter's output, expected at " +
                "least $MIN_EMITTED — the discovery scan may have stopped working"
        )
        assertTrue(
            trueDerivative.size >= MIN_TRUE_DERIVATIVE,
            "only ${trueDerivative.size} of ${emitted.size} emitted builtins carry a true " +
                "derivative, expected at least $MIN_TRUE_DERIVATIVE"
        )
        assertTrue(
            trueDerivative.size <= MAX_TRUE_DERIVATIVE,
            "${trueDerivative.size} emitted builtins now carry a true derivative, above the " +
                "recorded $MAX_TRUE_DERIVATIVE — raise the floor rather than banking the gain"
        )
        assertTrue(
            zeroByConvention.size >= MIN_ZERO_BY_CONVENTION,
            "only ${zeroByConvention.size} builtins are zero by convention, expected at least " +
                "$MIN_ZERO_BY_CONVENTION (floor, ceil, round, roundEven, trunc, sign, step and " +
                "the comparisons)"
        )
        assertTrue(
            unsupported.size <= MAX_UNSUPPORTED,
            "${unsupported.size} emitted builtins are unsupported, above the recorded " +
                "$MAX_UNSUPPORTED: $unsupported"
        )
        // The sampler family is the recorded reason for an unsupported entry; if it stops being
        // one, the KDoc above is wrong and should be rewritten rather than left standing.
        assertTrue(
            unsupported.any { it.startsWith("texture") },
            "the sampler family is supposed to be refused, and is not: $unsupported"
        )
    }

    // -- population 2: the words a fit could move a parameter through -------------------------------

    @Test
    fun `half the vocabulary passes a derivative from a wire upstream of it`() {
        val words = stackSLDocumentableWords.sorted()
        val passes = sortedSetOf<String>()
        val blocks = sortedSetOf<String>()
        val refused = sortedMapOf<String, String>()
        val notCompiled = sortedSetOf<String>()

        val target = CpuRaster(DIM, DIM, FloatArray(DIM * DIM * 4) { if (it % 4 == 3) 1f else 0.3f })
        val template = StackSLCpu.stillTemplate()

        words.forEach { word ->
            val program = "input 1.0 * $word"
            val wired = runCatching { StackSLWires.lift(program) }.getOrNull()
            if (wired == null) { notCompiled += word; return@forEach }
            val literal = wired.literalWires.firstOrNull { it.token == "1.0" }
            if (literal == null) { notCompiled += word; return@forEach }
            runCatching {
                StackSLImageLoss(wired, template, DIM, target, parallel = false)
                    .valueAndGradient(wired.initial).gradient[literal.index]
            }.onSuccess { g ->
                if (g != 0.0) passes += word else blocks += word
            }.onFailure { t -> refused[word] = "${t::class.simpleName}: ${t.message}" }
        }

        val considered = passes.size + blocks.size + refused.size
        println(
            "StackSL words as derivative paths: ${words.size} documentable, $considered probed with " +
                "a wire upstream, ${passes.size} pass a derivative, ${blocks.size} block it, " +
                "${refused.size} refused, ${notCompiled.size} did not compile as 'input 1.0 * word'"
        )
        println("  blocked (${blocks.size}): $blocks")
        println("  refused (${refused.size}): ${refused.keys}")

        assertTrue(
            considered >= MIN_WORDS_PROBED,
            "only $considered words could be probed with a wire upstream, expected at least " +
                "$MIN_WORDS_PROBED of ${words.size}"
        )
        assertTrue(
            passes.size >= MIN_WORDS_PASSING,
            "only ${passes.size} of $considered probed words pass a derivative, expected at least " +
                "$MIN_WORDS_PASSING"
        )
        assertTrue(
            passes.size <= MAX_WORDS_PASSING,
            "${passes.size} words now pass a derivative, above the recorded $MAX_WORDS_PASSING — " +
                "raise the floor rather than banking the gain"
        )
        assertTrue(
            refused.size <= MAX_WORDS_REFUSED,
            "${refused.size} words threw while being differentiated, above the recorded " +
                "$MAX_WORDS_REFUSED: $refused"
        )
    }

    // -- the probe ---------------------------------------------------------------------------------

    private enum class Verdict { TRUE_DERIVATIVE, ZERO, UNSUPPORTED, NO_PROBE }

    /**
     * Call [builtin] through [GlslAdScalars] with seeded arguments and read the adjoint.
     *
     * The argument *shapes* are a list rather than a per-name table on purpose: a shape list
     * cannot encode the conclusion, where a per-name table of expected verdicts could. The first
     * shape the builtin accepts without a shape error is the one used. The seeded values avoid
     * zeros and exact ties, since a tie is precisely where `min`/`max`/`abs` split their
     * derivative and a zero would make a true derivative look like a zero one.
     */
    private fun classify(builtin: String): Verdict {
        var sawShapeError = false
        PROBE_SHAPES.forEach { shape ->
            val tape = ADTape()
            val scalars = GlslAdScalars(tape)
            val seeds = ArrayList<afield.stdlib.AD>()
            val args = shape.map { type ->
                val components = Array(type.components) { i ->
                    scalars.variable(SEEDS[(seeds.size + i) % SEEDS.size]).also { seeds += it }
                }
                AdValue(type, components)
            }
            val out = try {
                scalars.callBuiltin(builtin, args, GlslSamplers.REFUSE) ?: return Verdict.NO_PROBE
            } catch (e: GlslUnsupported) {
                return Verdict.UNSUPPORTED
            } catch (e: GlslEvalError) {
                sawShapeError = true
                return@forEach
            } catch (e: IndexOutOfBoundsException) {
                sawShapeError = true
                return@forEach
            }
            var sum = tape.constant(0.0)
            out.c.forEach { sum += it }
            val adjoint = tape.backward(sum)
            return if (seeds.any { adjoint[it.index] != 0.0 }) Verdict.TRUE_DERIVATIVE else Verdict.ZERO
        }
        return if (sawShapeError) Verdict.NO_PROBE else Verdict.NO_PROBE
    }

    private companion object {
        /** 6 square: this asks a yes/no question about the gradient 325 times. */
        const val DIM = 6

        /** Seeds that avoid zeros and exact ties; see [classify]. */
        val SEEDS = doubleArrayOf(0.37, 0.61, 0.83, 0.29, 0.53, 0.71, 0.41, 0.67, 0.19)

        /** Argument shapes, most-specific first; the first one a builtin accepts is used. */
        val PROBE_SHAPES: List<List<GlslType>> = listOf(
            listOf(GlslType.VEC3),
            listOf(GlslType.VEC3, GlslType.VEC3),
            listOf(GlslType.VEC3, GlslType.VEC3, GlslType.VEC3),
            listOf(GlslType.FLOAT),
            listOf(GlslType.FLOAT, GlslType.FLOAT),
            listOf(GlslType.FLOAT, GlslType.FLOAT, GlslType.FLOAT),
            listOf(GlslType.MAT3),
            listOf(GlslType.MAT3, GlslType.MAT3)
        )

        // Measured 2026-09-16: 39 emitted builtins = 25 true derivative + 11 zero by convention
        // + 3 unsupported (the sampler family). The floors sit one or two either side, close
        // enough that a real change moves them.
        const val MIN_EMITTED = 37
        const val MIN_TRUE_DERIVATIVE = 24
        const val MAX_TRUE_DERIVATIVE = 27
        const val MIN_ZERO_BY_CONVENTION = 10
        const val MAX_UNSUPPORTED = 4

        // Measured 2026-09-16: of 325 documentable words, 295 accept a wire upstream, and of those
        // 154 pass a derivative, 74 block it and 67 refuse outright.
        const val MIN_WORDS_PROBED = 290
        const val MIN_WORDS_PASSING = 150
        const val MAX_WORDS_PASSING = 165
        const val MAX_WORDS_REFUSED = 70
    }
}

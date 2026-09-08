@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.ToHTML
import afield.stacksl.StackSLNumericFormKind
import afield.stacksl.stackSLNumericForm
import afield.stdlib.linalg.DualQuaternionf
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Vector4fc
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Blending two *code* keyframes by finding the structure they share and interpolating the numbers
 * they do not.
 *
 * A [KeyframeScope] can snapshot code as well as values: a `stack.auto` StackSL program through
 * [KeyframeScope.stackAuto], and a Kotlin expression through the `sourceBlock { }` macro. Two such
 * snapshots have no midpoint in general — half a program is not a program — so
 * [KeyframeScope.interpolate] steps them, taking the right-hand source once `t >= 1`. That is all
 * that is available when the two programs disagree about what they do.
 *
 * When they *agree* about what they do and differ only in numbers, there is a real midpoint, and
 * this file finds it. Both sources are lexed, the two token streams are aligned, and every place
 * where the alignment pairs a number with a number becomes a [CodeBlendSite]. Anything the
 * alignment cannot pair is a [CodeBlendDivergence], and one divergence is enough to put the whole
 * slot back on the stepping path: a program built from the left structure with the right numbers,
 * where the two structures differ, is neither keyframe at either end.
 *
 * ## The two ways to spend the blend
 *
 * [CodeBlendMode.STATIC] writes the interpolated numbers into the source and hands back a new
 * program, which the slot then compiles. It reaches every site — including StackSL's modifier chains
 * and random ranges, whose grammar demands literals — and it costs a shader compile (or, for Kotlin,
 * a K2 compile) per value of `t`. Scrubbing a timeline or stepping between two looks is what it is
 * for; sixty times a second is not.
 *
 * [CodeBlendMode.RUNTIME] emits *one* program in which each moving site reads the blend from
 * outside. The program is then invariant in `t`, so it compiles once and animates for free. StackSL
 * gets there by rewriting `0.5` as `0.2 0.9 ?pc.keyframeBlend .x mix` — a push of each endpoint and
 * the builtin `mix`, which leaves one value on the stack exactly as the literal it replaced did, so
 * the surrounding stack arithmetic is untouched. Kotlin gets there by rewriting `0.5f` as
 * `afield.stdlib.kfMix(0.2f, 0.9f, ...)`. Sites a mix expression cannot reach — see
 * [StackSLNumericFormKind] — make the plan report [CodeBlendPlan.runtimeCapable] false, and a
 * RUNTIME request over one of those falls back to STATIC and says so.
 *
 * ## The push-constant convention
 *
 * A StackSL program can only read what the host has already put in the shader, and a `stack.auto`
 * slot cannot declare a uniform of its own. So the blend parameter arrives by convention: declare a
 * `vec4` called `keyframeBlend` in the shader's push-constant block, and register the matching
 * keyframe lane so the scope can write it.
 *
 * ```
 * layout(push_constant) uniform Controls {
 *     vec4 keyframeBlend;
 * } pc;
 * ```
 * ```kotlin
 * scope.target("keyframeBlend") { module.current.liveUniformStructures["pc"] }
 * scope.codeBlend = CodeBlendOptions(mode = CodeBlendMode.RUNTIME)
 * ```
 *
 * [KeyframeScope.interpolate] then fills that lane with [keyframeBlendVector] on every blend, and
 * the four components are four shapes of the same parameter — see [CodeBlendLane]. A vec4 rather
 * than a float because a push-constant block is padded to vec4 boundaries anyway, and because a
 * program that wants the eased blend for one number and the raw one for another should not need a
 * second uniform to say so.
 *
 * The names are defaults ([keyframeBlendPushConstant], [keyframeBlendKey]); a shader whose block
 * instance is called something other than `pc` sets [CodeBlendOptions.stackSLRead] to match.
 */

/** Which of the two languages a [CodeBlendPlan] was built from. */
enum class CodeBlendLanguage {
    /** A `stack.auto` StackSL program, held in a keyframe as a [StackSLString]. */
    STACKSL,

    /** A Kotlin expression, held in a keyframe as a `SourceBackedBlock`. */
    KOTLIN,
}

/** What [KeyframeScope.interpolate] should do when both sides of a slot are code. */
enum class CodeBlendMode {
    /**
     * Take the right-hand source once `t >= 1` and the left one before that — the behaviour a scope
     * has with no code blend configured, and the fallback whenever the two sources disagree
     * structurally.
     */
    STEP,

    /**
     * Write the interpolated numbers into the source, producing a different program for every `t`.
     * Reaches every numeric site; costs a compile per value of `t`.
     */
    STATIC,

    /**
     * Emit one program that reads the blend parameter at runtime. Compiles once; needs the
     * push-constant convention for StackSL, and cannot reach sites whose grammar demands a literal.
     *
     * For a Kotlin blend the emitted block is a *rehydrating* `SourceBackedBlock`: it compiles its
     * generated source in the box the two keyframes came from. Invoke it from a frame loop, a
     * control callback or another box, and not from inside that box's own running code — see
     * [SourceBackedBlock] for what happens if you do.
     */
    RUNTIME,
}

/**
 * Which component of the blend `vec4` a runtime-blended program reads.
 *
 * All four carry the same `t`, differently shaped, so a program can pick per number: [RAW] to match
 * what the numeric lanes of the same keyframe are doing, [CLAMPED] for a value that must not
 * overshoot, [SMOOTH] for one that should ease, [COMPLEMENT] to write a mix the other way round
 * without a `1-`.
 */
enum class CodeBlendLane(
    /** The component index in the blend vec4, and the offset [keyframeBlendAmount] reads. */
    val component: Int,
    /** The StackSL swizzle word that selects this component. */
    val swizzle: String
) {
    /** `t` exactly as passed, unclamped — so a blend past either end extrapolates, as numbers do. */
    RAW(0, ".x"),

    /** `t` clamped to `0..1`. */
    CLAMPED(1, ".y"),

    /** `smoothstep(0, 1, t)`: clamped, and eased at both ends. */
    SMOOTH(2, ".z"),

    /** `1 - clamp(t)`, the complement of [CLAMPED]. */
    COMPLEMENT(3, ".w"),
}

/**
 * The conventional StackSL read expression for the blend push constant: a `vec4 keyframeBlend`
 * member of a push-constant block whose instance is called `pc`.
 *
 * It is the default of [CodeBlendOptions.stackSLRead] and it is text, not a binding — StackSL emits
 * an external read verbatim into the GLSL it generates, so this string has to name something the
 * surrounding shader really declares. A shader that does not gets a GLSL compile error naming
 * `pc.keyframeBlend`, reported on the box the way any other shader error is.
 */
const val keyframeBlendPushConstant: String = "pc.keyframeBlend"

/**
 * The default keyframe lane name the blend vec4 is written to: `keyframeBlend`.
 *
 * A scope only writes it when a target of this name is registered, so the convention costs nothing
 * until it is used and never invents a slot.
 */
const val keyframeBlendKey: String = "keyframeBlend"

/**
 * How a scope should blend code: which [CodeBlendMode], which [CodeBlendLane], and the two names the
 * push-constant convention needs.
 *
 * A data class because a [KeyframeScope]'s plan cache is keyed on it — changing any option
 * invalidates the cached plans rather than leaving a program built under the old ones running.
 */
data class CodeBlendOptions(
    /** What to do with two code values. Defaults to [CodeBlendMode.STEP], the pre-existing behaviour. */
    val mode: CodeBlendMode = CodeBlendMode.STEP,
    /** Which component of the blend vec4 a runtime-blended program reads. */
    val lane: CodeBlendLane = CodeBlendLane.RAW,
    /** The GLSL expression a runtime-blended StackSL program reads the blend from. */
    val stackSLRead: String = keyframeBlendPushConstant,
    /** The keyframe lane [KeyframeScope.interpolate] writes [keyframeBlendVector] to, when registered. */
    val blendKey: String = keyframeBlendKey,
)

/** How the numbers of one [CodeBlendSite] are interpolated. */
enum class CodeBlendInterpolation {
    /** Component by component, linearly. */
    LERP,

    /** As a quaternion, along the shorter arc. */
    SLERP,

    /** As a dual quaternion — a screw motion, rotation and translation together. */
    SCLERP,
}

/**
 * What kind of thing one blended site is, which decides both how its numbers move and whether a
 * runtime blend can reach it.
 */
enum class CodeBlendSiteKind(
    /** How this site's numbers are interpolated. */
    val interpolation: CodeBlendInterpolation,
    /** Whether the site can be rewritten as a mix expression the program evaluates every frame. */
    val runtimeCapable: Boolean
) {
    /** A single number: `0.5`, `-2`, `1.5f`. */
    NUMBER(CodeBlendInterpolation.LERP, true),

    /** A vector: StackSL's `[0.2,0.4]`, Kotlin's `Vector3f(...)` or `vec(...)`. */
    VECTOR(CodeBlendInterpolation.LERP, true),

    /** A `Quaternionf(x, y, z, w)`, blended along the shorter arc rather than component by component. */
    QUATERNION(CodeBlendInterpolation.SLERP, true),

    /** A `DualQuaternionf(Quaternionf(...), Quaternionf(...))`, blended as a screw motion. */
    DUAL_QUATERNION(CodeBlendInterpolation.SCLERP, true),

    /** StackSL's random-range literal `0.2_0.8`, whose grammar demands two literals. */
    RANGE(CodeBlendInterpolation.LERP, false),

    /** A StackSL modifier chain such as `0.5s2` or `|n4p0.25`, likewise literal-only. */
    MODIFIER(CodeBlendInterpolation.LERP, false),
}

/**
 * One place two code keyframes hold numbers in the same position — the unit a code blend
 * interpolates.
 *
 * [start] and [end] are a span in the *left* source, because that is the text a blended program is
 * rebuilt from: everything outside the sites is copied through byte for byte, so a blended program
 * keeps the left one's comments, spacing and layout.
 */
class CodeBlendSite internal constructor(
    /** What the site is, and so how it blends. */
    val kind: CodeBlendSiteKind,
    /** Start of the site's span in the left source, inclusive. */
    val start: Int,
    /** End of the site's span in the left source, exclusive. */
    val end: Int,
    /** The site exactly as the left source writes it. */
    val leftText: String,
    /** The site exactly as the right source writes it. */
    val rightText: String,
    /** The numbers the left source holds, in source order. */
    val left: List<Double>,
    /** The numbers the right source holds, in the same order. */
    val right: List<Double>,
    /** The left text split at its numeric spans; one longer than [left], and interleaving with it. */
    internal val skeleton: List<String>,
    /** Per number, whether it must be written back without a decimal point (a Kotlin `Int` or `Long`). */
    internal val integral: List<Boolean>,
    /** Each of the left source's numbers exactly as it spells it, for the ones a blend leaves alone. */
    internal val numberTexts: List<String>,
) {
    /** True when the two sides really differ, and so when a blend has anything to do here. */
    val moving: Boolean =
        left.size != right.size || left.indices.any { abs(left[it] - right[it]) > 0.0 }

    /** Whether a runtime blend can reach this site; false for the literal-only StackSL forms. */
    val runtimeCapable: Boolean get() = kind.runtimeCapable

    /** The site's numbers blended at [t], by [kind]'s interpolation. */
    fun blend(t: Double): List<Double> = when {
        left.size != right.size -> left
        kind.interpolation == CodeBlendInterpolation.SLERP && left.size == 4 -> slerpNumbers(left, right, t)
        kind.interpolation == CodeBlendInterpolation.SCLERP && left.size == 8 -> sclerpNumbers(left, right, t)
        else -> left.indices.map { left[it] + (right[it] - left[it]) * t }
    }

    /**
     * The site rewritten with its numbers blended at [t] — the [CodeBlendMode.STATIC] substitution.
     *
     * A number the two sides agree on is written back as the left source spelled it rather than
     * re-formatted, so blending the amount of `0.5s2` gives `0.7s2` and not `0.7s2.0`. A site is
     * atomic — one changed component rewrites the whole token — and this is what keeps that from
     * showing.
     */
    fun staticText(t: Double): String {
        val values = blend(t)
        return buildString {
            skeleton.forEachIndexed { i, piece ->
                append(piece)
                if (i >= values.size) return@forEachIndexed
                val unchanged = i < right.size && left[i] == right[i]
                val original = numberTexts.getOrNull(i)
                if (unchanged && original != null) {
                    append(original)
                } else {
                    append(formatBlendNumber(values[i], integral.getOrElse(i) { false }))
                }
            }
        }
    }

    /** The left text, an arrow, the right text — one line of a breakdown. */
    override fun toString(): String = "$leftText -> $rightText"
}

/**
 * One place the alignment could not pair the two sources — the reason a slot is not blendable.
 *
 * A substitution arrives as a single divergence with both sides filled in; an insertion or a
 * deletion leaves the missing side blank. [line] is where to look in the left source, or 0 for a
 * token only the right source has.
 */
class CodeBlendDivergence internal constructor(
    /** Token index in the left stream where the two sources part company. */
    val index: Int,
    /** Line number in the left source, 1-based, or 0 for a token only the right source has. */
    val line: Int,
    /** What the left source has here, or "" when it has nothing. */
    val left: String,
    /** What the right source has here, or "" when it has nothing. */
    val right: String,
) {
    override fun toString(): String = when {
        left.isEmpty() -> "line $line: only the right source has `$right`"
        right.isEmpty() -> "line $line: only the left source has `$left`"
        else -> "line $line: `$left` vs `$right`"
    }
}

/**
 * The alignment of two code keyframes: what they share, where they differ numerically, and where
 * they differ structurally.
 *
 * A plan is a *pure* reading of two strings — building one touches no shader, compiles nothing and
 * writes nothing. [bake] and [runtimeSource] turn it back into program text; [KeyframeScope] caches
 * plans so that the runtime path emits the same text (and, for Kotlin, the same `SourceBackedBlock`)
 * for every `t`, which is the reason that path is cheap.
 */
class CodeBlendPlan internal constructor(
    /** Which language was aligned. */
    val language: CodeBlendLanguage,
    /** The options the plan was built under; a scope's plan cache is keyed on these. */
    val options: CodeBlendOptions,
    /** The left source, verbatim — what [bake] and [runtimeSource] rebuild from. */
    val leftSource: String,
    /** The right source, verbatim. */
    val rightSource: String,
    /** Every numeric site the alignment paired, moving or not, in left-source order. */
    val sites: List<CodeBlendSite>,
    /** Every token pair the alignment could not reconcile. Empty means the two structures agree. */
    val divergences: List<CodeBlendDivergence>,
) : ToHTML {

    /** The sites that actually differ between the two sources. */
    val movingSites: List<CodeBlendSite> = sites.filter { it.moving }

    /** True when the two sources share one structure, so that a blend between them exists at all. */
    val blendable: Boolean = divergences.isEmpty()

    /** True when every moving site can be rewritten as a mix, so [CodeBlendMode.RUNTIME] is available. */
    val runtimeCapable: Boolean = blendable && movingSites.all { it.runtimeCapable }

    /**
     * The mode this plan can actually deliver, which is [CodeBlendOptions.mode] unless something
     * stopped it: a structural divergence drops any mode to [CodeBlendMode.STEP], and a
     * literal-only site drops [CodeBlendMode.RUNTIME] to [CodeBlendMode.STATIC].
     */
    val effectiveMode: CodeBlendMode = when {
        options.mode == CodeBlendMode.STEP -> CodeBlendMode.STEP
        !blendable -> CodeBlendMode.STEP
        options.mode == CodeBlendMode.RUNTIME && !runtimeCapable -> CodeBlendMode.STATIC
        else -> options.mode
    }

    /** True when [effectiveMode] is not what was asked for, and so when the breakdown is worth reading. */
    val downgraded: Boolean get() = effectiveMode != options.mode

    /** Why [effectiveMode] is not [CodeBlendOptions.mode], or "" when it is. */
    val downgradeReason: String get() = when {
        !downgraded -> ""
        !blendable -> "the two sources differ structurally in ${divergences.size} place(s); stepping instead"
        else -> "${movingSites.count { !it.runtimeCapable }} moving site(s) can only be blended by " +
            "rewriting the source, so this recompiles per t"
    }

    /**
     * The left source with every moving site's numbers interpolated at [t] — a different program for
     * every `t`, and the only mode that can reach a literal-only site.
     *
     * Text outside the sites is copied through unchanged, so the result reads like the left program
     * with different numbers in it. At `t == 0` the numbers are the left ones again, though a number
     * written `.5` comes back as `0.5`: it is re-formatted from its value, not restored from its text.
     */
    fun bake(t: Double): String = rewrite { site -> site.staticText(t) }

    /**
     * The one program a [CodeBlendMode.RUNTIME] blend runs: every moving site replaced by a mix of
     * the two endpoints against the blend parameter.
     *
     * Invariant in `t`, which is the point — a slot handed this text twice sees no change and does
     * not recompile. For [CodeBlendLanguage.STACKSL] the parameter is the push-constant read named by
     * [CodeBlendOptions.stackSLRead]; for [CodeBlendLanguage.KOTLIN] it is [keyframeBlendAmount]
     * against this plan's own [runtimeId], which [drive] writes.
     */
    val runtimeSource: String by lazy {
        rewrite { site ->
            when (language) {
                CodeBlendLanguage.STACKSL ->
                    "${site.leftText} ${site.rightText} ?${options.stackSLRead} ${options.lane.swizzle} mix"
                CodeBlendLanguage.KOTLIN ->
                    "afield.stdlib.kfMix(${site.leftText}, ${site.rightText}, " +
                        "afield.stdlib.keyframeBlendAmount($runtimeId, ${options.lane.component}))"
            }
        }
    }

    /**
     * The registry slot a Kotlin runtime blend reads its parameter from, allocated on first use.
     *
     * One per plan, so two blends running at once do not read each other's `t`. Slots are never
     * released: a plan lives as long as the [KeyframeScope] cache entry holding it, and that cache is
     * keyed on the two sources, so the count is the number of distinct code pairs a session blends
     * rather than a per-frame leak.
     */
    val runtimeId: Int by lazy { BlendRegistry.allocate() }

    /**
     * Publish [t] as this plan's runtime blend parameter, for a [CodeBlendLanguage.KOTLIN] blend to
     * read on its next invocation. A no-op for StackSL, whose parameter travels as a push constant.
     */
    fun drive(t: Double) {
        if (language == CodeBlendLanguage.KOTLIN) BlendRegistry.write(runtimeId, t)
    }

    /** The plan as text: the counts, the downgrade if any, then a line per moving site and divergence. */
    fun describe(): String = buildString {
        appendLine(
            "${language.name.lowercase()} blend: ${movingSites.size} moving site(s) of ${sites.size}, " +
                "${divergences.size} divergence(s) — ${options.mode} -> $effectiveMode"
        )
        if (downgraded) appendLine("  downgraded: $downgradeReason")
        movingSites.forEach { site ->
            appendLine("  ${site.kind.name.lowercase()}${if (site.runtimeCapable) "" else " (literal-only)"}: $site")
        }
        divergences.forEach { appendLine("  divergence: $it") }
    }

    /**
     * The breakdown as HTML: the header counts, a row per moving site with its midpoint, and a row
     * per divergence.
     *
     * This is what the keyframe browser shows for a pair of keys, and what a box gets from
     * `display(plan)` — the point of both being to see *why* a blend did or did not happen before
     * wondering why the picture is not moving.
     */
    override fun toHTML(): String = codeBlendPlanHtml(this)

    /** One line: what was asked for, what will happen, and how many sites are in play. */
    override fun toString(): String =
        "CodeBlendPlan(${language.name.lowercase()}, ${options.mode}->$effectiveMode, " +
            "${movingSites.size}/${sites.size} moving, ${divergences.size} divergent)"

    private fun rewrite(replacement: (CodeBlendSite) -> String): String {
        if (movingSites.isEmpty()) return leftSource
        val out = StringBuilder(leftSource.length)
        var cursor = 0
        movingSites.forEach { site ->
            out.append(leftSource, cursor, site.start)
            out.append(replacement(site))
            cursor = site.end
        }
        out.append(leftSource, cursor, leftSource.length)
        return out.toString()
    }
}

/**
 * The blend parameter as the `vec4` the push-constant convention expects: the same `t` in the four
 * shapes of [CodeBlendLane].
 *
 * `x` is `t` untouched — unclamped, because the numeric lanes of the same keyframe extrapolate too,
 * and a code blend that silently stopped at the ends would disagree with them.
 */
fun keyframeBlendVector(t: Double): Vector4f {
    val clamped = t.coerceIn(0.0, 1.0)
    val smooth = clamped * clamped * (3.0 - 2.0 * clamped)
    return Vector4f(t.toFloat(), clamped.toFloat(), smooth.toFloat(), (1.0 - clamped).toFloat())
}

/**
 * Read blend slot [id]'s parameter on component [component] — the function a
 * [CodeBlendMode.RUNTIME] Kotlin blend calls, once per number, every time the block runs.
 *
 * Emitted into generated source fully qualified, so it resolves inside a `sourceBlock` body without
 * the box having imported anything. An unknown [id] reads 0, which makes a blend whose plan has gone
 * behave as its left-hand keyframe rather than throw inside somebody's draw loop.
 */
fun keyframeBlendAmount(id: Int, component: Int = CodeBlendLane.RAW.component): Double =
    BlendRegistry.read(id, component)

/** Blend two `Double`s linearly — the generated call behind a blended Kotlin `Double` literal. */
fun kfMix(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Blend two `Float`s linearly, staying a `Float` so a blended `1.5f` still fits a `Float` uniform. */
fun kfMix(a: Float, b: Float, t: Double): Float = (a + (b - a) * t).toFloat()

/** Blend two `Int`s and round, so a blended `Int` literal is still an `Int`. */
fun kfMix(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).roundToLong().toInt()

/** Blend two `Long`s and round, for a literal written with an `L`. */
fun kfMix(a: Long, b: Long, t: Double): Long = (a + (b - a) * t).roundToLong()

/** Blend two vec2s component by component, into a fresh vector. */
fun kfMix(a: Vector2fc, b: Vector2fc, t: Double): Vector2f = Vector2f(a).lerp(b, t.toFloat())

/** Blend two vec3s component by component, into a fresh vector. */
fun kfMix(a: Vector3fc, b: Vector3fc, t: Double): Vector3f = Vector3f(a).lerp(b, t.toFloat())

/** Blend two vec4s component by component, into a fresh vector. */
fun kfMix(a: Vector4fc, b: Vector4fc, t: Double): Vector4f = Vector4f(a).lerp(b, t.toFloat())

/** Blend two quaternions along the shorter arc, into a fresh quaternion. */
fun kfMix(a: Quaternionf, b: Quaternionf, t: Double): Quaternionf = Quaternionf(a).slerp(b, t.toFloat())

/** Blend two rigid transforms as a screw motion, into a fresh dual quaternion. */
fun kfMix(a: DualQuaternionf, b: DualQuaternionf, t: Double): DualQuaternionf =
    DualQuaternionf(a).sclerp(b, t.toFloat())

/**
 * Align two StackSL programs and report what a blend between them would do.
 *
 * The whole token stream is aligned in one pass, `:` and `;` included, so a program that defines the
 * same words in a different order reads as divergent rather than as a reordering. That is the
 * conservative direction: matching definitions by name and blending each body would have to decide
 * what to do with a definition only one side has, and no answer to that is still both keyframes at
 * the ends.
 */
fun planStackSLCodeBlend(
    left: String,
    right: String,
    options: CodeBlendOptions = CodeBlendOptions()
): CodeBlendPlan =
    buildPlan(CodeBlendLanguage.STACKSL, left, right, options, stackSLBlendTokens(left), stackSLBlendTokens(right))

/**
 * Align two Kotlin expressions and report what a blend between them would do.
 *
 * The lexer is deliberately coarse — comments and whitespace dropped, string and character literals
 * kept whole, numbers and identifiers separated, everything else one character at a time. It knows
 * two things beyond that, and both earn their place: a `+` or `-` in prefix position belongs to the
 * number after it, so `vec(-1f, 2f)` and `vec(1f, 2f)` differ by a number rather than by a token;
 * and a call to one of the vector, quaternion or dual-quaternion constructors whose arguments are
 * all numeric literals is one site rather than several, so a `Quaternionf` blends along the shorter
 * arc instead of drifting through the inside of the sphere component by component.
 *
 * Hexadecimal, binary and unsigned literals stay opaque tokens: there is no `kfMix` for a `UInt`,
 * and an interpolated bitmask is not a thing anybody asked for.
 */
fun planKotlinCodeBlend(
    left: String,
    right: String,
    options: CodeBlendOptions = CodeBlendOptions()
): CodeBlendPlan =
    buildPlan(CodeBlendLanguage.KOTLIN, left, right, options, kotlinBlendTokens(left), kotlinBlendTokens(right))

// ---------------------------------------------------------------------------------------------
// Lexing
// ---------------------------------------------------------------------------------------------

/**
 * One token of either language, with the span it occupies and — when it carries numbers a blend may
 * move — the pieces those numbers sit between.
 *
 * [shape] is what the alignment compares: the token text for an opaque token, and a skeleton with
 * the numbers punched out for a numeric one, so that `0.2` and `0.9` are the same shape and pair up
 * while `fbm` and `noise` do not.
 */
private class BlendToken(
    val text: String,
    val start: Int,
    val end: Int,
    val line: Int,
    val shape: String,
    val numbers: List<Double> = emptyList(),
    val skeleton: List<String> = emptyList(),
    val integral: List<Boolean> = emptyList(),
    val numberTexts: List<String> = emptyList(),
    val kind: CodeBlendSiteKind? = null,
)

/** Line number of [index] in [source], 1-based. */
private fun lineOf(source: String, index: Int): Int {
    var line = 1
    for (i in 0 until index.coerceAtMost(source.length)) if (source[i] == '\n') line++
    return line
}

/**
 * StackSL's own tokenisation, with spans kept.
 *
 * Comment handling mirrors the compiler exactly — `#` and `//` to end of line, `(` to the next `)` —
 * because a blender that disagreed with the compiler about where a token starts would align against
 * a program nobody is running.
 */
private fun stackSLBlendTokens(source: String): List<BlendToken> {
    val tokens = mutableListOf<BlendToken>()
    var i = 0
    while (i < source.length) {
        val ch = source[i]
        when {
            ch.isWhitespace() -> i++
            ch == '#' -> while (i < source.length && source[i] != '\n') i++
            ch == '/' && i + 1 < source.length && source[i + 1] == '/' ->
                while (i < source.length && source[i] != '\n') i++
            ch == '(' -> {
                while (i < source.length && source[i] != ')') i++
                if (i < source.length) i++
            }
            else -> {
                val start = i
                while (i < source.length) {
                    val c = source[i]
                    if (c.isWhitespace() || c == '#' || c == '(') break
                    if (c == '/' && i + 1 < source.length && source[i + 1] == '/') break
                    i++
                }
                if (i > start) tokens += stackSLBlendToken(source, start, i)
            }
        }
    }
    return tokens
}

/** One StackSL token, numeric or opaque, as the alignment wants it. */
private fun stackSLBlendToken(source: String, start: Int, end: Int): BlendToken {
    val text = source.substring(start, end)
    val form = stackSLNumericForm(text)
    if (form == null || form.numbers.isEmpty()) {
        return BlendToken(text, start, end, lineOf(source, start), text)
    }
    val kind = when (form.kind) {
        StackSLNumericFormKind.NUMBER -> CodeBlendSiteKind.NUMBER
        StackSLNumericFormKind.VEC -> CodeBlendSiteKind.VECTOR
        StackSLNumericFormKind.RANGE -> CodeBlendSiteKind.RANGE
        StackSLNumericFormKind.MODIFIER -> CodeBlendSiteKind.MODIFIER
    }
    // A StackSL number is a float however it was written, so `2` may blend to `2.5`; nothing here is
    // ever written back as an integer.
    return BlendToken(
        text = text,
        start = start,
        end = end,
        line = lineOf(source, start),
        shape = form.skeleton.joinToString(" ") + "|" + kind.name,
        numbers = form.numbers,
        skeleton = form.skeleton,
        integral = form.numbers.map { false },
        numberTexts = form.texts,
        kind = kind,
    )
}

/** The Kotlin vector constructors whose all-numeric calls collapse into one typed site. */
private val kotlinVectorConstructors = mapOf(
    "Vector2f" to 2, "Vector3f" to 3, "Vector4f" to 4,
)

/** Lex Kotlin, fold prefix signs into numbers, then collapse the typed constructor calls. */
private fun kotlinBlendTokens(source: String): List<BlendToken> =
    collapseKotlinComposites(source, foldKotlinSigns(rawKotlinTokens(source)))

/**
 * Kotlin, lexed as coarsely as the alignment can afford: trivia dropped, literals whole, numbers and
 * identifiers separated, every other character its own token.
 *
 * Block comments nest, which is Kotlin's rule and not C's: an opener inside a block comment needs
 * its own closer, and getting that wrong would swallow the rest of an expression.
 */
private fun rawKotlinTokens(source: String): List<BlendToken> {
    val tokens = mutableListOf<BlendToken>()
    var i = 0

    fun push(start: Int, end: Int) {
        val text = source.substring(start, end)
        tokens += BlendToken(text, start, end, lineOf(source, start), text)
    }

    while (i < source.length) {
        val c = source[i]
        when {
            c.isWhitespace() -> i++

            c == '/' && i + 1 < source.length && source[i + 1] == '/' ->
                while (i < source.length && source[i] != '\n') i++

            c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                var depth = 0
                while (i < source.length) {
                    if (i + 1 < source.length && source[i] == '/' && source[i + 1] == '*') {
                        depth++
                        i += 2
                    } else if (i + 1 < source.length && source[i] == '*' && source[i + 1] == '/') {
                        depth--
                        i += 2
                        if (depth == 0) break
                    } else {
                        i++
                    }
                }
            }

            source.startsWith("\"\"\"", i) -> {
                val start = i
                i += 3
                while (i < source.length && !source.startsWith("\"\"\"", i)) i++
                i = (i + 3).coerceAtMost(source.length)
                push(start, i)
            }

            c == '"' || c == '\'' -> {
                val start = i
                i++
                while (i < source.length && source[i] != c) {
                    if (source[i] == '\\') i++
                    i++
                }
                i = (i + 1).coerceAtMost(source.length)
                push(start, i)
            }

            c == '`' -> {
                val start = i
                i++
                while (i < source.length && source[i] != '`') i++
                i = (i + 1).coerceAtMost(source.length)
                push(start, i)
            }

            c.isDigit() -> {
                val start = i
                i = scanKotlinNumber(source, i)
                tokens += kotlinNumberToken(source, start, i)
            }

            c.isLetter() || c == '_' -> {
                val start = i
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                push(start, i)
            }

            else -> {
                push(i, i + 1)
                i++
            }
        }
    }
    return tokens
}

/** Where the number starting at [from] ends: digits, separators, one exponent, one type suffix. */
private fun scanKotlinNumber(source: String, from: Int): Int {
    var i = from
    if (source.startsWith("0x", i, ignoreCase = true) || source.startsWith("0b", i, ignoreCase = true)) {
        i += 2
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
        return i
    }
    while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
    if (i + 1 < source.length && source[i] == '.' && source[i + 1].isDigit()) {
        i++
        while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
    }
    if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
        val mark = i
        i++
        if (i < source.length && (source[i] == '+' || source[i] == '-')) i++
        if (i < source.length && source[i].isDigit()) {
            while (i < source.length && source[i].isDigit()) i++
        } else {
            i = mark
        }
    }
    while (i < source.length && (
            source[i] == 'f' || source[i] == 'F' || source[i] == 'L' ||
                source[i] == 'u' || source[i] == 'U'
            )
    ) i++
    return i
}

/**
 * A Kotlin numeric literal as a blendable token, or as an opaque one when its value cannot be
 * written back: a hex or binary literal, an unsigned one, or anything `toDouble` cannot read.
 */
private fun kotlinNumberToken(source: String, start: Int, end: Int): BlendToken {
    val text = source.substring(start, end)
    val line = lineOf(source, start)
    val opaque = BlendToken(text, start, end, line, text)
    if (text.startsWith("0x", ignoreCase = true) || text.startsWith("0b", ignoreCase = true)) return opaque
    if (text.endsWith("u", ignoreCase = true) || text.endsWith("uL") || text.endsWith("UL")) return opaque

    val suffix = text.takeLastWhile { it == 'f' || it == 'F' || it == 'L' }
    val digits = text.dropLast(suffix.length)
    val value = digits.replace("_", "").toDoubleOrNull() ?: return opaque
    val integral = !digits.contains('.') && !digits.contains('e', ignoreCase = true) &&
        !suffix.equals("f", ignoreCase = true)
    return BlendToken(
        text = text,
        start = start,
        end = end,
        line = line,
        // The shape carries whether the literal is an integer one, so `1` and `1.0` do not pair:
        // rebuilding from the left source would write the blend back as `1`, and an `Int` literal
        // where a `Double` was written can change which overload the expression resolves to.
        shape = " $suffix|NUMBER|$integral",
        numbers = listOf(value),
        skeleton = listOf("", suffix),
        integral = listOf(integral),
        numberTexts = listOf(digits),
        kind = CodeBlendSiteKind.NUMBER,
    )
}

/**
 * Merge a `+` or `-` in prefix position into the number that follows it.
 *
 * Prefix position means the token before the sign is not something a value can end with — not an
 * identifier, a number, a literal, or a closing bracket. Without this, `vec(-1f, 0f)` against
 * `vec(1f, 0f)` aligns as an unmatched `-` and the whole slot reads as divergent.
 */
private fun foldKotlinSigns(tokens: List<BlendToken>): List<BlendToken> {
    val out = mutableListOf<BlendToken>()
    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val next = tokens.getOrNull(i + 1)
        val isSign = token.text == "-" || token.text == "+"
        val previous = out.lastOrNull()
        val prefix = previous == null || previous.text.last().let { last ->
            !(last.isLetterOrDigit() || last == '_' || last == ')' || last == ']' ||
                last == '"' || last == '\'' || last == '`')
        }
        if (isSign && prefix && next != null && next.kind == CodeBlendSiteKind.NUMBER &&
            next.numbers.isNotEmpty() && next.start == token.end
        ) {
            out += BlendToken(
                text = token.text + next.text,
                start = token.start,
                end = next.end,
                line = token.line,
                shape = next.shape,
                numbers = listOf(if (token.text == "-") -next.numbers[0] else next.numbers[0]),
                skeleton = listOf("", next.skeleton.last()),
                integral = next.integral,
                numberTexts = listOf(token.text + next.numberTexts.first()),
                kind = CodeBlendSiteKind.NUMBER,
            )
            i += 2
        } else {
            out += token
            i++
        }
    }
    return out
}

/**
 * Collapse `Vector3f(1f, 2f, 3f)`, `vec(...)`, `Quaternionf(...)` and
 * `DualQuaternionf(Quaternionf(...), Quaternionf(...))` into one token each.
 *
 * The collapse is what lets a quaternion slerp: four numbers blended independently leave the shorter
 * arc and shrink through the middle of the sphere, and no amount of aligning them separately can
 * notice. Quaternions are collapsed before dual quaternions so the outer form can be recognised from
 * the inner ones.
 */
private fun collapseKotlinComposites(source: String, tokens: List<BlendToken>): List<BlendToken> {
    val vectorsAndQuats = collapsePass(source, tokens) { name, args ->
        when {
            name == "Quaternionf" && args == 4 -> CodeBlendSiteKind.QUATERNION
            name == "vec" && args in 2..4 -> CodeBlendSiteKind.VECTOR
            kotlinVectorConstructors[name] == args -> CodeBlendSiteKind.VECTOR
            else -> null
        }
    }
    return collapsePass(source, vectorsAndQuats) { name, args ->
        if (name == "DualQuaternionf" && args == 2) CodeBlendSiteKind.DUAL_QUATERNION else null
    }
}

/**
 * One collapse pass: find `identifier ( site , site ... )` where every argument is already a
 * blendable token, and replace the run with a single token carrying every argument's numbers in
 * order.
 *
 * [classify] is handed the identifier and the argument count and decides whether the call is one of
 * the forms worth collapsing — and which kind it becomes, which is what fixes the interpolation.
 */
private fun collapsePass(
    source: String,
    tokens: List<BlendToken>,
    classify: (String, Int) -> CodeBlendSiteKind?
): List<BlendToken> {
    val out = mutableListOf<BlendToken>()
    var i = 0
    while (i < tokens.size) {
        val name = tokens[i]
        val collapsed = if (name.kind == null && tokens.getOrNull(i + 1)?.text == "(") {
            tryCollapse(source, tokens, i, classify)
        } else {
            null
        }
        if (collapsed != null) {
            out += collapsed.first
            i = collapsed.second
        } else {
            out += name
            i++
        }
    }
    return out
}

/** The collapsed token for the call at [at], and the index just past it, or null if it is not one. */
private fun tryCollapse(
    source: String,
    tokens: List<BlendToken>,
    at: Int,
    classify: (String, Int) -> CodeBlendSiteKind?
): Pair<BlendToken, Int>? {
    val name = tokens[at]
    val args = mutableListOf<BlendToken>()
    var i = at + 2
    while (true) {
        val arg = tokens.getOrNull(i) ?: return null
        if (arg.kind == null || arg.numbers.isEmpty()) return null
        args += arg
        i++
        when (tokens.getOrNull(i)?.text) {
            "," -> i++
            ")" -> {
                i++
                break
            }
            else -> return null
        }
    }
    val kind = classify(name.text, args.size) ?: return null
    val end = tokens[i - 1].end
    // The skeleton is the left text with the argument numbers punched out, so a rebuild keeps the
    // constructor's own name, spacing and `f` suffixes.
    val skeleton = mutableListOf<String>()
    val numberTexts = mutableListOf<String>()
    var cursor = name.start
    args.forEach { arg ->
        // Punch out the argument's *number* and leave its type suffix in the skeleton, or a blended
        // `Vector3f(0f, 0f, 0f)` would come back as `Vector3f(0.5, 1.0, 1.5)` and stop compiling.
        val numberEnd = arg.end - arg.skeleton.last().length
        skeleton += source.substring(cursor, arg.start)
        numberTexts += source.substring(arg.start, numberEnd)
        cursor = numberEnd
    }
    skeleton += source.substring(cursor, end)
    return BlendToken(
        text = source.substring(name.start, end),
        start = name.start,
        end = end,
        line = name.line,
        shape = skeleton.joinToString(" ") + "|" + kind.name,
        numbers = args.flatMap { it.numbers },
        skeleton = skeleton,
        integral = args.flatMap { it.integral },
        numberTexts = numberTexts,
        kind = kind,
    ) to i
}

// ---------------------------------------------------------------------------------------------
// Alignment
// ---------------------------------------------------------------------------------------------

/**
 * Align the two token streams on their shapes and turn the result into a plan.
 *
 * The alignment is a longest-common-subsequence walk, which is what a text diff does and for the
 * same reason: it finds the largest set of positions the two streams agree on without needing to
 * know anything about either grammar. Shapes rather than texts is the one change that makes it a
 * *numeric* diff — two numbers in corresponding positions have the same shape and pair up, so they
 * become a site instead of a deletion and an insertion.
 */
private fun buildPlan(
    language: CodeBlendLanguage,
    left: String,
    right: String,
    options: CodeBlendOptions,
    leftTokens: List<BlendToken>,
    rightTokens: List<BlendToken>
): CodeBlendPlan {
    val sites = mutableListOf<CodeBlendSite>()
    val divergences = mutableListOf<CodeBlendDivergence>()

    val dropped = mutableListOf<BlendToken>()   // left tokens with no partner
    val added = mutableListOf<BlendToken>()     // right tokens with no partner

    fun flush(index: Int) {
        val pairs = minOf(dropped.size, added.size)
        for (k in 0 until pairs) {
            divergences += CodeBlendDivergence(index, dropped[k].line, dropped[k].text, added[k].text)
        }
        for (k in pairs until dropped.size) {
            divergences += CodeBlendDivergence(index, dropped[k].line, dropped[k].text, "")
        }
        for (k in pairs until added.size) {
            divergences += CodeBlendDivergence(index, 0, "", added[k].text)
        }
        dropped.clear()
        added.clear()
    }

    var i = 0
    var j = 0
    for ((mi, mj) in lcsMatches(leftTokens.map { it.shape }, rightTokens.map { it.shape })) {
        while (i < mi) dropped += leftTokens[i++]
        while (j < mj) added += rightTokens[j++]
        flush(mi)

        val a = leftTokens[i]
        val b = rightTokens[j]
        if (a.kind != null && a.numbers.isNotEmpty() && a.numbers.size == b.numbers.size) {
            sites += CodeBlendSite(
                kind = a.kind,
                start = a.start,
                end = a.end,
                leftText = a.text,
                rightText = b.text,
                left = a.numbers,
                right = b.numbers,
                skeleton = a.skeleton,
                integral = a.integral,
                numberTexts = a.numberTexts,
            )
        }
        i++
        j++
    }
    while (i < leftTokens.size) dropped += leftTokens[i++]
    while (j < rightTokens.size) added += rightTokens[j++]
    flush(leftTokens.size)

    return CodeBlendPlan(language, options, left, right, sites, divergences)
}

/**
 * The index pairs of one longest common subsequence of [a] and [b].
 *
 * Plain quadratic dynamic programming: a `stack.auto` program is tens of tokens and a `sourceBlock`
 * body is hundreds, so the table is small and the clarity is worth more than the space.
 */
private fun lcsMatches(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
    val n = a.size
    val m = b.size
    if (n == 0 || m == 0) return emptyList()
    val table = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            table[i][j] = if (a[i] == b[j]) {
                table[i + 1][j + 1] + 1
            } else {
                maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
    }
    val out = mutableListOf<Pair<Int, Int>>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                out += i to j
                i++
                j++
            }
            table[i + 1][j] >= table[i][j + 1] -> i++
            else -> j++
        }
    }
    return out
}

// ---------------------------------------------------------------------------------------------
// Numbers in and out of source text
// ---------------------------------------------------------------------------------------------

/**
 * Write [value] back into source text: rounded and without a point when the literal it replaces was
 * an integer one, and otherwise as a decimal.
 *
 * Six decimal places, trailing zeros trimmed. A blended number is an interpolation rather than a
 * measurement, so more digits than that only make the source harder to read; fewer would visibly
 * quantise a slow move.
 */
private fun formatBlendNumber(value: Double, integral: Boolean): String {
    if (!value.isFinite()) return if (integral) "0" else "0.0"
    if (integral) return value.roundToLong().toString()
    val text = String.format(java.util.Locale.US, "%.6f", value).trimEnd('0')
    return if (text.endsWith(".")) text + "0" else text
}

/** Four numbers as a quaternion, slerped, back as four numbers. */
private fun slerpNumbers(a: List<Double>, b: List<Double>, t: Double): List<Double> {
    val out = Quaternionf(a[0].toFloat(), a[1].toFloat(), a[2].toFloat(), a[3].toFloat())
        .slerp(Quaternionf(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat()), t.toFloat())
    return listOf(out.x.toDouble(), out.y.toDouble(), out.z.toDouble(), out.w.toDouble())
}

/** Eight numbers as a dual quaternion — real half then dual half — sclerped, back as eight numbers. */
private fun sclerpNumbers(a: List<Double>, b: List<Double>, t: Double): List<Double> {
    fun dq(v: List<Double>) = DualQuaternionf(
        Quaternionf(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), v[3].toFloat()),
        Quaternionf(v[4].toFloat(), v[5].toFloat(), v[6].toFloat(), v[7].toFloat())
    )
    val out = dq(a).sclerp(dq(b), t.toFloat())
    return listOf(
        out.real.x.toDouble(), out.real.y.toDouble(), out.real.z.toDouble(), out.real.w.toDouble(),
        out.dual.x.toDouble(), out.dual.y.toDouble(), out.dual.z.toDouble(), out.dual.w.toDouble()
    )
}

// ---------------------------------------------------------------------------------------------
// The runtime parameter registry, for Kotlin blends
// ---------------------------------------------------------------------------------------------

/**
 * Where a [CodeBlendMode.RUNTIME] Kotlin blend keeps its parameter between
 * [KeyframeScope.interpolate] writing it and the compiled block reading it.
 *
 * A generated `sourceBlock` body cannot close over a Kotlin value — it is text that gets compiled
 * inside a box — so the parameter has to travel through something addressable by an integer written
 * into that text. This is the Kotlin half of the push-constant convention, and it is a registry for
 * the same reason the shader half is a uniform: one program, many values of `t`.
 */
private object BlendRegistry {
    private val next = AtomicInteger(1)
    private val lanes = ConcurrentHashMap<Int, FloatArray>()

    /** A fresh slot, reading 0 on every component until something writes it. */
    fun allocate(): Int = next.getAndIncrement().also { lanes[it] = FloatArray(4) }

    /** Publish [t] on slot [id], in all four [CodeBlendLane] shapes. */
    fun write(id: Int, t: Double) {
        val v = keyframeBlendVector(t)
        lanes[id] = floatArrayOf(v.x, v.y, v.z, v.w)
    }

    /** Slot [id]'s [component], or 0 for an unknown slot or component. */
    fun read(id: Int, component: Int): Double =
        lanes[id]?.getOrNull(component)?.toDouble() ?: 0.0
}

// ---------------------------------------------------------------------------------------------
// The breakdown, as HTML
// ---------------------------------------------------------------------------------------------

/** The five characters that cannot go into HTML text as themselves. */
private fun blendEsc(s: String): String = buildString(s.length) {
    s.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(it)
        }
    }
}

/** [CodeBlendPlan.toHTML], kept out of the class so the class reads as its own vocabulary. */
private fun codeBlendPlanHtml(plan: CodeBlendPlan): String = buildString {
    val mono = "font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px"
    append("<div style=\"$mono;color:#ddd\">")

    val verdict = when {
        !plan.blendable && plan.options.mode != CodeBlendMode.STEP -> "#e08a6a"
        plan.downgraded -> "#e0c46a"
        plan.effectiveMode == CodeBlendMode.STEP -> "#999"
        else -> "#8ac48a"
    }
    append("<div style=\"margin-bottom:6px\">")
    append("<span style=\"color:$verdict\">${blendEsc(plan.language.name.lowercase())} &middot; ")
    append("${plan.options.mode} &rarr; ${plan.effectiveMode}</span>")
    append(" <span style=\"color:#888\">${plan.movingSites.size} moving of ${plan.sites.size} paired site(s)")
    if (plan.divergences.isNotEmpty()) append(", ${plan.divergences.size} divergence(s)")
    append("</span></div>")

    if (plan.downgraded) {
        append("<div style=\"color:#e0c46a;margin-bottom:6px\">${blendEsc(plan.downgradeReason)}</div>")
    }

    if (plan.movingSites.isNotEmpty()) {
        append("<table style=\"border-collapse:collapse;$mono\">")
        append("<tr style=\"color:#888\"><td style=\"padding:1px 8px 1px 0\">site</td>")
        append("<td style=\"padding:1px 8px 1px 0\">left</td><td style=\"padding:1px 8px 1px 0\">right</td>")
        append("<td style=\"padding:1px 8px 1px 0\">at t=0.5</td></tr>")
        plan.movingSites.forEach { site ->
            val label = site.kind.name.lowercase().replace('_', ' ') +
                if (site.runtimeCapable) "" else " (literal-only)"
            val labelColor = if (site.runtimeCapable) "#7fa8d0" else "#e0c46a"
            append("<tr>")
            append("<td style=\"padding:1px 8px 1px 0;color:$labelColor\">${blendEsc(label)}</td>")
            append("<td style=\"padding:1px 8px 1px 0\">${blendEsc(site.leftText)}</td>")
            append("<td style=\"padding:1px 8px 1px 0\">${blendEsc(site.rightText)}</td>")
            append("<td style=\"padding:1px 8px 1px 0;color:#8ac48a\">${blendEsc(site.staticText(0.5))}</td>")
            append("</tr>")
        }
        append("</table>")
    } else if (plan.blendable) {
        append("<div style=\"color:#888\">the two sources hold the same numbers &mdash; nothing to blend</div>")
    }

    if (plan.divergences.isNotEmpty()) {
        append("<div style=\"margin-top:6px;color:#e08a6a\">")
        plan.divergences.take(24).forEach { append("<div>${blendEsc(it.toString())}</div>") }
        if (plan.divergences.size > 24) {
            append("<div style=\"color:#888\">and ${plan.divergences.size - 24} more</div>")
        }
        append("</div>")
    }

    val program = when (plan.effectiveMode) {
        CodeBlendMode.RUNTIME -> "runtime program" to plan.runtimeSource
        CodeBlendMode.STATIC -> "program at t=0.5" to plan.bake(0.5)
        CodeBlendMode.STEP -> null
    }
    if (program != null) {
        append("<details style=\"margin-top:6px\">")
        append("<summary style=\"color:#888;cursor:pointer\">${program.first}</summary>")
        append("<pre style=\"$mono;white-space:pre-wrap;color:#cfcfcf\">${blendEsc(program.second)}</pre>")
        append("</details>")
    }

    append("</div>")
}

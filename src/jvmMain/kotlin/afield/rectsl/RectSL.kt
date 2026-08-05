package afield.rectsl

import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompileResult
import afield.stacksl.StackSLCompiler
import afield.stacksl.StackSLSeverity
import afield.four.Renderer
import afield.four.modules.Module
import afield.four.modules.Mesh
import afield.four.modules.fromContext
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.RenderTarget
import afield.four.targets.RenderToTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import afield.Box
import afield.Color4
import afield.Colormaps
import afield.KotlinScriptEngine
import afield.stdlib.Functions
import afield.stdlib.linalg.Matrix4f
import kotlin.script.experimental.api.ResultValue
import org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec2d(val x: Double, val y: Double) {
    operator fun plus(other: Vec2d): Vec2d = Vec2d(x + other.x, y + other.y)
    operator fun minus(other: Vec2d): Vec2d = Vec2d(x - other.x, y - other.y)
    operator fun times(scale: Double): Vec2d = Vec2d(x * scale, y * scale)
    infix fun dot(other: Vec2d): Double = x * other.x + y * other.y
    fun length(): Double = sqrt(this dot this)
    fun normalized(): Vec2d {
        val l = length()
        return if (l <= 1e-12) Vec2d(1.0, 0.0) else Vec2d(x / l, y / l)
    }
}

data class Vec4f(
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
) {
    companion object {
        val ZERO = Vec4f(0f, 0f, 0f, 0f)
    }
}

/**
 * Bridge a [Color4] to RectSL's local [Vec4f]. [Color4] and [Colormaps] moved to `package afield`
 * (2026-07-22) so general colormap/visualization code no longer depends on this microlanguage; this
 * extension keeps the RGBA→Vec4f conversion where its only caller (the renderer path) lives.
 */
fun Color4.toVec4f(): Vec4f = Vec4f(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())

/** Map a numeric list to `[0,1]` by its own min/max; a flat list maps to all `0.5`. */
internal fun normalizeValues(values: List<Double>): List<Double> {
    if (values.isEmpty()) return values
    val lo = values.minOrNull()!!
    val hi = values.maxOrNull()!!
    val range = hi - lo
    return if (abs(range) <= 1e-12) values.map { 0.5 } else values.map { (it - lo) / range }
}

sealed interface Token {
    data class Word(val name: String) : Token
    data class Number(val value: Double) : Token
    data class StringLit(val value: String) : Token
    data class ColorLit(val color: Color4) : Token
    data class Quote(val tokens: List<Token>, val source: String) : Token

    /** `{{ kotlin }}` — a Kotlin expression evaluated in a `with(Functions){…}` scope. */
    data class KotlinEscape(val source: String) : Token
}

class RectLangException(message: String) : IllegalArgumentException(message)

/**
 * The array/loop context a `{{ … }}` Kotlin escape sees, so escaped Kotlin can vary
 * per copy: `i` (index), `n` (count), `f` (fraction 0..1), `f0` (cyclic), `fc`
 * (centered -1..1), `ic` (centered index). All zero (n=1) outside a loop.
 */
class RectEscapeContext(
    @JvmField val i: Double,
    @JvmField val n: Double,
    @JvmField val f: Double,
    @JvmField val f0: Double,
    @JvmField val fc: Double,
    @JvmField val ic: Double
)

sealed interface Value
data class VNum(val x: Double) : Value
data class VStr(val x: String) : Value
data class VColor(val rgba: Color4) : Value
data class VVec2(val x: Double, val y: Double) : Value
data class VPaint(val paint: PaintSpec) : Value
data class VTrack<T>(val track: Track<T>) : Value
data class VRect(val id: String) : Value
data class VGroup(val id: String) : Value
data class VSelection(val refs: List<NodeRef>) : Value
data class VQuote(val tokens: List<Token>, val source: String) : Value
data class VMaterialRef(val name: String) : Value
data class VFn(val fn: afield.stdlib.Functions.Fn) : Value
data class VData(val values: List<Double>) : Value

private fun Value.describe(): String = when (this) {
    is VNum -> "Number"
    is VStr -> "String"
    is VColor -> "Color"
    is VVec2 -> "Vec2"
    is VData -> "Data"
    is VPaint -> "Paint"
    is VTrack<*> -> "Track<${track.kind}>"
    is VRect -> "Rect"
    is VGroup -> "Group"
    is VSelection -> "Selection"
    is VQuote -> "Quote"
    is VMaterialRef -> "Material"
    is VFn -> "Fn"
}

class RectLangTokenizer(private val source: String) {
    private var i = 0

    private companion object {
        // Longest match first so multi-char operators aren't split by their prefixes.
        private val OPERATORS = listOf("<=", ">=", "!=", "<>", "<", ">", "=")
    }

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipWhitespaceAndComments()
            if (i >= source.length) break
            if (source.startsWith("'(", i)) {
                tokens += parseQuote()
            } else if (source.startsWith("{{", i)) {
                tokens += parseKotlinEscape()
            } else {
                tokens += parseSingleToken()
            }
        }
        return tokens
    }

    private fun parseKotlinEscape(): Token.KotlinEscape {
        i += 2 // consume "{{"
        val start = i
        var depth = 0
        while (i < source.length) {
            if (depth == 0 && source.startsWith("}}", i)) {
                val raw = source.substring(start, i)
                i += 2
                return Token.KotlinEscape(raw.trim())
            }
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        throw RectLangException("Unterminated {{ … }} Kotlin escape")
    }

    private fun parseQuote(): Token.Quote {
        val contentStart = i + 2
        i += 2
        val tokens = mutableListOf<Token>()
        while (true) {
            skipWhitespaceAndComments()
            if (i >= source.length) throw RectLangException("Unterminated quote literal")
            if (source[i] == ')') {
                val raw = source.substring(contentStart, i)
                i++
                return Token.Quote(tokens, raw.trim())
            }
            if (source.startsWith("'(", i)) {
                tokens += parseQuote()
            } else if (source.startsWith("{{", i)) {
                tokens += parseKotlinEscape()
            } else {
                tokens += parseSingleToken(inQuote = true)
            }
        }
    }

    private fun parseSingleToken(inQuote: Boolean = false): Token {
        val ch = source[i]
        if (ch == '"') return parseString()
        if (ch == ':' || ch == ';') {
            i++
            return Token.Word(ch.toString())
        }
        if (ch == ')' && inQuote) throw RectLangException("Unexpected ')' in quote")

        // Comparison operators and the track-pair separator `=` are punctuation.
        // Recognise them explicitly (longest match first) so `0=0.2` splits into
        // `0` `=` `0.2` while `<=`, `>=`, `!=`, `<>` stay whole. `=` is also a
        // break character below, so it separates operands even when glued to them.
        matchOperator()?.let { return it }

        val start = i
        while (i < source.length) {
            val c = source[i]
            if (c.isWhitespace() || c == '#' || c == '"' || c == ';' || c == ':' || c == '=') break
            if (inQuote && c == ')') break
            if (source.startsWith("'(", i)) break
            i++
        }
        if (start == i) throw RectLangException("Unexpected character '${source[i]}'")
        val raw = source.substring(start, i)
        return atom(raw)
    }

    private fun matchOperator(): Token.Word? {
        for (op in OPERATORS) {
            if (source.startsWith(op, i)) {
                i += op.length
                return Token.Word(op)
            }
        }
        return null
    }

    private fun parseString(): Token.StringLit {
        val start = i
        i++
        val text = buildString {
            while (i < source.length) {
                val c = source[i++]
                if (c == '"') return Token.StringLit(toString())
                if (c == '\\' && i < source.length) {
                    val escaped = source[i++]
                    append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            '"' -> '"'
                            '\\' -> '\\'
                            else -> escaped
                        }
                    )
                } else {
                    append(c)
                }
            }
        }
        throw RectLangException(unterminatedStringMessage(start))
    }

    private fun unterminatedStringMessage(start: Int): String {
        val (line, column) = locationAt(start)
        val trailingQuoteHint = if (source.substring(start).trim() == "\"") {
            " This looks like a single trailing quote; if this source came from a Kotlin raw string, " +
                "close it with exactly three quotes (`\"\"\"`), not four (`\"\"\"\"`)."
        } else {
            ""
        }
        return "Unterminated string literal at line $line, column $column.$trailingQuoteHint"
    }

    private fun locationAt(index: Int): Pair<Int, Int> {
        var line = 1
        var column = 1
        for (n in 0 until index.coerceIn(0, source.length)) {
            if (source[n] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
        }
        return line to column
    }

    private fun atom(raw: String): Token {
        val numberish = raw.trimEnd(',')
        val lower = numberish.lowercase()
        if (lower.startsWith("0x") && lower.length in 8..10) {
            val hex = lower.drop(2).toLongOrNull(16)
            if (hex != null) return Token.ColorLit(Color4.fromHex(hex))
        }
        numberish.toDoubleOrNull()?.let { return Token.Number(it) }
        return Token.Word(raw)
    }

    private fun skipWhitespaceAndComments() {
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c == '#' -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                else -> return
            }
        }
    }
}

enum class TrackValueKind {
    Double,
    Paint
}

/**
 * A Double as a GLSL `float` literal: always with a decimal point, so `2` does
 * not silently become an int and drag an expression into integer arithmetic.
 * Non-finite values cannot be spelled in GLSL at all, so they are pinned to the
 * float range rather than emitted as something that will not compile.
 */
internal fun Double.glslFloat(): String = when {
    isNaN() -> "0.0"
    this == Double.POSITIVE_INFINITY -> "3.402823466e38"
    this == Double.NEGATIVE_INFINITY -> "-3.402823466e38"
    else -> {
        val text = toString()
        if (text.contains('.') || text.contains('e') || text.contains('E')) text else "$text.0"
    }
}

/**
 * The closed vocabulary of scalar operations that can appear inside a track.
 *
 * Tracks used to carry bare `(Double) -> Double` closures. That is perfectly
 * good on the JVM and completely opaque to everything else: you cannot print a
 * closure, serialize it, rewrite it, or emit it as GLSL. Naming the ops keeps
 * the CPU behaviour identical while turning the track IR into a symbolic graph,
 * so each op owns both halves — how it evaluates, and how it spells itself in a
 * shader.
 *
 * The GLSL is written to match the Kotlin exactly rather than to be idiomatic:
 * the divide-by-zero guards are reproduced, and [MOD] spells out Kotlin's
 * truncated `%` instead of reaching for GLSL's `mod()`, which floors and so
 * disagrees for negative operands.
 */
enum class TrackOp1(val eval: (Double) -> Double, val glsl: (String) -> String) {
    NEG({ -it }, { "-($it)" }),
    ABS({ abs(it) }, { "abs($it)" }),
    FLOOR({ floor(it) }, { "floor($it)" }),
    CEIL({ ceil(it) }, { "ceil($it)" }),
    FRACT({ it - floor(it) }, { "fract($it)" }),
    SQRT({ sqrt(max(it, 0.0)) }, { "sqrt(max($it, 0.0))" }),
    SIN({ sin(it) }, { "sin($it)" }),
    COS({ cos(it) }, { "cos($it)" }),
    SATURATE({ it.coerceIn(0.0, 1.0) }, { "clamp($it, 0.0, 1.0)" })
}

enum class TrackOp2(
    val eval: (Double, Double) -> Double,
    val glsl: (String, String) -> String
) {
    ADD({ a, b -> a + b }, { a, b -> "($a + $b)" }),
    SUB({ a, b -> a - b }, { a, b -> "($a - $b)" }),
    MUL({ a, b -> a * b }, { a, b -> "($a * $b)" }),
    DIV(
        { a, b -> if (abs(b) <= 1e-12) 0.0 else a / b },
        { a, b -> "(abs($b) <= 1e-12 ? 0.0 : $a / $b)" }
    ),
    MOD(
        { a, b -> if (abs(b) <= 1e-12) 0.0 else a % b },
        { a, b -> "(abs($b) <= 1e-12 ? 0.0 : $a - $b * trunc($a / $b))" }
    ),
    POW({ a, b -> a.pow(b) }, { a, b -> "pow($a, $b)" }),
    MIN({ a, b -> min(a, b) }, { a, b -> "min($a, $b)" }),
    MAX({ a, b -> max(a, b) }, { a, b -> "max($a, $b)" })
}

enum class TrackOp3(
    val eval: (Double, Double, Double) -> Double,
    val glsl: (String, String, String) -> String
) {
    /** Order-insensitive bounds, matching `coerceIn(min(lo, hi), max(lo, hi))`. */
    CLAMP(
        { x, lo, hi -> x.coerceIn(min(lo, hi), max(lo, hi)) },
        { x, lo, hi -> "clamp($x, min($lo, $hi), max($lo, $hi))" }
    ),
    MIX({ a, b, t -> a + (b - a) * t }, { a, b, t -> "mix($a, $b, $t)" }),
    SMOOTHSTEP(
        { low, high, x ->
            val denom = high - low
            val u = if (abs(denom) <= 1e-12) 0.0 else ((x - low) / denom).coerceIn(0.0, 1.0)
            u * u * (3.0 - 2.0 * u)
        },
        { low, high, x -> "(abs($high - $low) <= 1e-12 ? 0.0 : smoothstep($low, $high, $x))" }
    )
}

/**
 * How a [TimeWarpTrack] rewrites the time it hands to its source. Each kind
 * takes one scalar parameter, fixed when the warp is built, so the whole warp is
 * `(kind, amount)` rather than a closure over that amount.
 */
enum class TimeWarpKind(
    val eval: (Double, Double) -> Double,
    val glsl: (String, Double) -> String
) {
    /** `delay-time` / `time+` / `->live`: play the source `amount` seconds later. */
    SHIFT({ t, amount -> t - amount }, { t, amount -> "($t - ${amount.glslFloat()})" }),

    /** `scale-time` / `time*`: stretch the source's clock by `amount`. */
    SCALE({ t, amount -> t / amount }, { t, amount -> "($t / ${amount.glslFloat()})" }),

    /** `reverse-time`: `amount` is the scene duration, not a factor. */
    REVERSE({ t, amount -> amount - t }, { t, amount -> "(${amount.glslFloat()} - $t)" }),

    /** `loop-time`: wrap into `[0, amount)`. */
    LOOP(
        { t, amount -> positiveModulo(t, amount) },
        { t, amount ->
            if (amount <= 1e-12) "($t)" else "mod($t, ${amount.glslFloat()})"
        }
    ),

    /** `pingpong-time`: triangle wave over `[0, amount]`, folded at both ends. */
    PINGPONG(
        { t, amount ->
            val p = positiveModulo(t, amount * 2.0)
            if (p <= amount) p else amount * 2.0 - p
        },
        { t, amount ->
            if (amount * 2.0 <= 1e-12) "($t)"
            else "(${amount.glslFloat()} - abs(mod($t, ${(amount * 2.0).glslFloat()}) - ${amount.glslFloat()}))"
        }
    )
}

sealed interface Track<out T> {
    val kind: TrackValueKind
    fun sample(t: Double): T
}

data class ConstantTrack<T>(
    val value: T,
    override val kind: TrackValueKind
) : Track<T> {
    override fun sample(t: Double): T = value
}

data class TcbKey(
    val time: Double,
    val value: Double,
    val tension: Double? = null,
    val continuity: Double? = null,
    val bias: Double? = null
)

data class TcbTrack(
    val keys: List<TcbKey>,
    val defaultTension: Double = 0.0,
    val defaultContinuity: Double = 0.0,
    val defaultBias: Double = 0.0
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    private val sorted = keys.sortedBy { it.time }

    init {
        require(sorted.isNotEmpty()) { "TCB track needs at least one key" }
    }

    override fun sample(t: Double): Double {
        if (sorted.size == 1) return sorted.first().value
        if (t <= sorted.first().time) return sorted.first().value
        if (t >= sorted.last().time) return sorted.last().value
        val hi = sorted.indexOfFirst { it.time >= t }.coerceAtLeast(1)
        val lo = hi - 1
        val k0 = sorted[lo]
        val k1 = sorted[hi]
        val dt = (k1.time - k0.time).takeIf { abs(it) > 1e-12 } ?: return k1.value
        val u = ((t - k0.time) / dt).coerceIn(0.0, 1.0)
        val m0 = outgoingSlope(lo)
        val m1 = incomingSlope(hi)
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2.0 * u3 - 3.0 * u2 + 1.0
        val h10 = u3 - 2.0 * u2 + u
        val h01 = -2.0 * u3 + 3.0 * u2
        val h11 = u3 - u2
        return h00 * k0.value + h10 * dt * m0 + h01 * k1.value + h11 * dt * m1
    }

    fun withTcb(tension: Double, continuity: Double, bias: Double): TcbTrack =
        copy(defaultTension = tension, defaultContinuity = continuity, defaultBias = bias)

    private fun outgoingSlope(index: Int): Double {
        val key = sorted[index]
        val t = key.tension ?: defaultTension
        val c = key.continuity ?: defaultContinuity
        val b = key.bias ?: defaultBias
        val prev = slope(index - 1, index)
        val next = slope(index, index + 1)
        return 0.5 * (1.0 - t) * ((1.0 + c) * (1.0 + b) * prev + (1.0 - c) * (1.0 - b) * next)
    }

    private fun incomingSlope(index: Int): Double {
        val key = sorted[index]
        val t = key.tension ?: defaultTension
        val c = key.continuity ?: defaultContinuity
        val b = key.bias ?: defaultBias
        val prev = slope(index - 1, index)
        val next = slope(index, index + 1)
        return 0.5 * (1.0 - t) * ((1.0 - c) * (1.0 + b) * prev + (1.0 + c) * (1.0 - b) * next)
    }

    private fun slope(a: Int, b: Int): Double {
        val ia = a.coerceIn(sorted.indices)
        val ib = b.coerceIn(sorted.indices)
        if (ia == ib) return 0.0
        val ka = sorted[ia]
        val kb = sorted[ib]
        val dt = kb.time - ka.time
        return if (abs(dt) <= 1e-12) 0.0 else (kb.value - ka.value) / dt
    }
}

data class LinearScalarTrack(val keys: List<TcbKey>) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    private val sorted = keys.sortedBy { it.time }

    init {
        require(sorted.isNotEmpty()) { "Linear track needs at least one key" }
    }

    override fun sample(t: Double): Double {
        if (sorted.size == 1) return sorted.first().value
        if (t <= sorted.first().time) return sorted.first().value
        if (t >= sorted.last().time) return sorted.last().value
        val hi = sorted.indexOfFirst { it.time >= t }.coerceAtLeast(1)
        val lo = hi - 1
        val a = sorted[lo]
        val b = sorted[hi]
        val u = ((t - a.time) / (b.time - a.time)).coerceIn(0.0, 1.0)
        return a.value + (b.value - a.value) * u
    }
}

data class HoldScalarTrack(val keys: List<TcbKey>) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    private val sorted = keys.sortedBy { it.time }

    init {
        require(sorted.isNotEmpty()) { "Hold track needs at least one key" }
    }

    override fun sample(t: Double): Double =
        sorted.lastOrNull { it.time <= t }?.value ?: sorted.first().value
}

/**
 * A keyed track whose segment parameter is shaped by a named easing.
 *
 * The curve is looked up from [easeName] rather than carried as a closure, so
 * two tracks with the same keys and the same easing compare equal, and a
 * backend can ask *which* curve this is instead of only being able to call it.
 */
data class EasedScalarTrack(
    val keys: List<TcbKey>,
    val easeName: String
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    private val sorted = keys.sortedBy { it.time }
    val ease: (Double) -> Double = RECTSL_EASINGS[easeName]
        ?: throw RectLangException("unknown easing '$easeName'")

    init {
        require(sorted.isNotEmpty()) { "$easeName track needs at least one key" }
    }

    override fun sample(t: Double): Double {
        if (sorted.size == 1) return sorted.first().value
        if (t <= sorted.first().time) return sorted.first().value
        if (t >= sorted.last().time) return sorted.last().value
        val hi = sorted.indexOfFirst { it.time >= t }.coerceAtLeast(1)
        val lo = hi - 1
        val a = sorted[lo]
        val b = sorted[hi]
        val dt = (b.time - a.time).takeIf { abs(it) > 1e-12 } ?: return b.value
        val u = ease(((t - a.time) / dt).coerceIn(0.0, 1.0))
        return a.value + (b.value - a.value) * u
    }
}

private fun Track<Double>.mapKeys(
    word: String,
    transform: (TcbKey) -> TcbKey
): Track<Double> =
    when (this) {
        is TcbTrack -> copy(keys = keys.map(transform))
        is LinearScalarTrack -> copy(keys = keys.map(transform))
        is HoldScalarTrack -> copy(keys = keys.map(transform))
        is EasedScalarTrack -> copy(keys = keys.map(transform))
        else -> throw RectLangException("$word needs a direct keyed scalar track")
    }

private fun Track<Double>.filterKeys(
    word: String,
    predicate: (TcbKey) -> Boolean
): Track<Double> =
    when (this) {
        is TcbTrack -> {
            val filtered = keys.filter(predicate)
            if (filtered.isEmpty()) throw RectLangException("$word removed every key from TCB track")
            copy(keys = filtered)
        }
        is LinearScalarTrack -> {
            val filtered = keys.filter(predicate)
            if (filtered.isEmpty()) throw RectLangException("$word removed every key from linear track")
            copy(keys = filtered)
        }
        is HoldScalarTrack -> {
            val filtered = keys.filter(predicate)
            if (filtered.isEmpty()) throw RectLangException("$word removed every key from hold track")
            copy(keys = filtered)
        }
        is EasedScalarTrack -> {
            val filtered = keys.filter(predicate)
            if (filtered.isEmpty()) throw RectLangException("$word removed every key from eased track")
            copy(keys = filtered)
        }
        else -> throw RectLangException("$word needs a direct keyed scalar track")
    }

/**
 * Fold a value transform into a directly-keyed track's key values, or — when the
 * track is a computed node with no keys to reach — wrap it in the equivalent
 * symbolic op. Callers supply both spellings because the keyed path wants a
 * plain arithmetic transform and the wrapped path wants an IR node; they must
 * agree, and each caller states the pair on one line so it is obvious when they
 * do not.
 */
private fun Track<Double>.mapKeysOrSamples(
    word: String,
    transform: (Double) -> Double,
    wrap: (Track<Double>) -> Track<Double>
): Track<Double> =
    when (this) {
        is TcbTrack, is LinearScalarTrack, is HoldScalarTrack, is EasedScalarTrack ->
            mapKeys(word) { it.copy(value = transform(it.value)) }
        else -> wrap(this)
    }

private fun shiftTrackKeysOrWarp(track: Track<Double>, amount: Double): Track<Double> =
    when (track) {
        is TcbTrack, is LinearScalarTrack, is HoldScalarTrack, is EasedScalarTrack ->
            track.mapKeys("time+") { it.copy(time = it.time + amount) }
        else -> TimeWarpTrack(track, TimeWarpKind.SHIFT, amount)
    }

private fun scaleTrackKeysOrWarp(track: Track<Double>, factor: Double): Track<Double> {
    if (abs(factor) <= 1e-12) throw RectLangException("time* factor must be non-zero")
    return when (track) {
        is TcbTrack, is LinearScalarTrack, is HoldScalarTrack, is EasedScalarTrack ->
            track.mapKeys("time*") { it.copy(time = it.time * factor) }
        else -> TimeWarpTrack(track, TimeWarpKind.SCALE, factor)
    }
}

private fun Track<Double>.keyedValues(word: String): List<TcbKey> =
    when (this) {
        is TcbTrack -> keys
        is LinearScalarTrack -> keys
        is HoldScalarTrack -> keys
        is EasedScalarTrack -> keys
        else -> throw RectLangException("$word needs a direct keyed scalar track")
    }

private fun easeIn(u: Double): Double = u * u
private fun easeOut(u: Double): Double = 1.0 - (1.0 - u) * (1.0 - u)
private fun easeInOut(u: Double): Double {
    val v = -2.0 * u + 2.0
    return if (u < 0.5) 2.0 * u * u else 1.0 - v * v * 0.5
}
private fun smoothstep(u: Double): Double = u * u * (3.0 - 2.0 * u)

private fun easedTrack(word: String, track: Track<Double>, name: String = word): Track<Double> =
    EasedScalarTrack(track.keyedValues(word), name)

/**
 * The full Penner easing family, keyed by kebab-case name (e.g. "back-out",
 * "elastic-in-out", "smootherstep"). The math is borrowed from
 * `afield.stdlib.Functions` so RectSL's `ease` reads identically to the rest of
 * the procedural-animation toolkit rather than re-deriving the curves here.
 */
private val PENNER_EASINGS: Map<String, (Double) -> Double> = buildMap {
    with(Functions) {
        for (e in Functions.Easing.values()) {
            val curve = identity().ease(e)
            put(e.name.lowercase().replace('_', '-')) { x: Double -> curve.eval(x) }
        }
    }
}

/**
 * Every easing an [EasedScalarTrack] can name: the Penner family plus the four
 * shorthand words (`ease-in`, `ease-out`, `ease-in-out`, `ease-smoothstep` /
 * `smooth`) that predate it. One registry means an easing track is always
 * reconstructible from its name alone.
 */
internal val RECTSL_EASINGS: Map<String, (Double) -> Double> = buildMap {
    putAll(PENNER_EASINGS)
    put("ease-in", ::easeIn)
    put("ease-out", ::easeOut)
    put("ease-in-out", ::easeInOut)
    put("ease-smoothstep", ::smoothstep)
    put("smooth", ::smoothstep)
}

data class MappedDoubleTrack(
    val source: Track<Double>,
    val op: TrackOp1
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double = op.eval(source.sample(t))
}

data class BinaryDoubleTrack(
    val a: Track<Double>,
    val b: Track<Double>,
    val op: TrackOp2
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double = op.eval(a.sample(t), b.sample(t))
}

data class TernaryDoubleTrack(
    val a: Track<Double>,
    val b: Track<Double>,
    val c: Track<Double>,
    val op: TrackOp3
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double = op.eval(a.sample(t), b.sample(t), c.sample(t))
}

data class TimeWarpTrack(
    val source: Track<Double>,
    val warp: TimeWarpKind,
    val amount: Double
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double = source.sample(warp.eval(t, amount))
}

/**
 * A hard visibility window — 1.0 while `lo <= t < hi`, else 0.0. Multiplied into an
 * object's alpha, it makes the object pop into and out of existence at fixed times
 * (bullet spawn/despawn). `lo`/`hi` default to ±infinity for one-sided gates.
 */
data class GateTrack(
    val lo: Double = Double.NEGATIVE_INFINITY,
    val hi: Double = Double.POSITIVE_INFINITY
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double = if (t >= lo && t < hi) 1.0 else 0.0
}

/**
 * Rotation derived from a pair of position tracks: the heading (direction of
 * travel) of the point (x(t), y(t)). Because RectSL tracks are pure closed-form
 * functions of time, the velocity is recovered by central finite difference — no
 * simulation state — so a rect can auto-orient to its own trajectory. `offset`
 * corrects for how the rect's art is drawn (0 = local +x points along travel).
 *
 * At a velocity zero-crossing the heading is undefined; rather than pop, we widen
 * the sampling window a few times looking for real motion, and fall back to the
 * bare offset only if the point is genuinely stationary across the whole window.
 */
data class HeadingTrack(
    val x: Track<Double>,
    val y: Track<Double>,
    val eps: Double = 1.0 / 240.0,
    val offset: Double = 0.0
) : Track<Double> {
    override val kind: TrackValueKind = TrackValueKind.Double
    override fun sample(t: Double): Double {
        var h = eps
        repeat(6) {
            val dx = x.sample(t + h) - x.sample(t - h)
            val dy = y.sample(t + h) - y.sample(t - h)
            if (hypot(dx, dy) > 1e-9) return atan2(dy, dx) + offset
            h *= 4.0
        }
        return offset
    }
}

fun constantTrack(x: Double): Track<Double> = ConstantTrack(x, TrackValueKind.Double)
fun constantPaintTrack(x: PaintSpec): Track<PaintSpec> = ConstantTrack(x, TrackValueKind.Paint)

enum class UnitKind {
    Meters,
    Pixels,
    Abstract
}

enum class Origin {
    Center,
    TopLeft
}

enum class FitPolicy {
    Contain,
    Cover,
    Stretch
}

data class WallSpec(
    val width: Double,
    val height: Double,
    val unit: UnitKind,
    val origin: Origin = Origin.Center
)

sealed interface PaintSpec {
    data class Solid(val color: Color4) : PaintSpec

    data class LinearGradient(
        val c0: Color4,
        val c1: Color4,
        val p0: Vec2d,
        val p1: Vec2d,
        val space: GradientSpace = GradientSpace.Local
    ) : PaintSpec
}

enum class GradientSpace {
    Local,
    World
}

data class MaterialBinding(
    val materialId: Int,
    val shaderData: Vec4f = Vec4f.ZERO,
    val paramBase: Int = -1,
    val flags: Int = 0,
    val name: String = "plain"
)

sealed interface Node {
    val id: String
}

data class RectNode(
    override val id: String,
    val x: Track<Double>,
    val y: Track<Double>,
    val w: Track<Double>,
    val h: Track<Double>,
    val rotation: Track<Double>,
    val scaleX: Track<Double>,
    val scaleY: Track<Double>,
    val alpha: Track<Double>,
    val z: Track<Double>,
    val fill: Track<PaintSpec>,
    val material: MaterialBinding,
    val tags: Set<String>
) : Node

data class GroupNode(
    override val id: String,
    val children: List<String>,
    val x: Track<Double> = constantTrack(0.0),
    val y: Track<Double> = constantTrack(0.0),
    val rotation: Track<Double> = constantTrack(0.0),
    val scaleX: Track<Double> = constantTrack(1.0),
    val scaleY: Track<Double> = constantTrack(1.0),
    val alpha: Track<Double> = constantTrack(1.0),
    val tags: Set<String> = emptySet()
) : Node

data class ShaderModuleSpec(
    val name: String,
    val materialId: Int,
    val source: String,
    val prefix: String,
    val glsl: String? = null,
    val diagnostics: List<String> = emptyList()
)

data class MaterialTable(
    val modules: List<ShaderModuleSpec> = emptyList(),
    val params: FloatArray = FloatArray(0)
)

data class SceneProgram(
    val wall: WallSpec,
    val duration: Double,
    val fps: Double,
    val nodes: List<Node>,
    val fit: FitPolicy = FitPolicy.Contain,
    val background: Color4 = Color4.BLACK,
    val materialTable: MaterialTable = MaterialTable()
) {
    private val nodeMap: Map<String, Node> = nodes.associateBy { it.id }
    private val rectOrder: Map<String, Int> = nodes.filterIsInstance<RectNode>().mapIndexed { index, node -> node.id to index }.toMap()

    fun sample(t: Double): SampledScene {
        val childIds = nodes.filterIsInstance<GroupNode>().flatMap { it.children }.toSet()
        val roots = nodes.filter { it.id !in childIds }
        val rects = ArrayList<SampledRect>()
        roots.forEach { sampleNode(it, t, Transform2d.IDENTITY, rects) }
        return SampledScene(t, rects.sortedBy { it.z })
    }

    private fun sampleNode(node: Node, t: Double, parent: Transform2d, out: MutableList<SampledRect>) {
        when (node) {
            is RectNode -> {
                val local = Transform2d(
                    translation = Vec2d(node.x.sample(t), node.y.sample(t)),
                    rotation = node.rotation.sample(t),
                    scale = Vec2d(node.scaleX.sample(t), node.scaleY.sample(t)),
                    alpha = node.alpha.sample(t)
                )
                val world = parent then local
                out += SampledRect(
                    id = node.id,
                    objectId = rectOrder.getValue(node.id),
                    center = world.translation,
                    size = Vec2d(node.w.sample(t), node.h.sample(t)),
                    rotation = world.rotation,
                    scale = world.scale,
                    alpha = world.alpha.coerceIn(0.0, 1.0),
                    z = node.z.sample(t),
                    fill = node.fill.sample(t),
                    material = node.material,
                    tags = node.tags
                )
            }
            is GroupNode -> {
                val local = Transform2d(
                    translation = Vec2d(node.x.sample(t), node.y.sample(t)),
                    rotation = node.rotation.sample(t),
                    scale = Vec2d(node.scaleX.sample(t), node.scaleY.sample(t)),
                    alpha = node.alpha.sample(t)
                )
                val world = parent then local
                node.children.mapNotNull { nodeMap[it] }.forEach { sampleNode(it, t, world, out) }
            }
        }
    }
}

data class Transform2d(
    val translation: Vec2d,
    val rotation: Double,
    val scale: Vec2d,
    val alpha: Double
) {
    infix fun then(child: Transform2d): Transform2d {
        val scaled = Vec2d(child.translation.x * scale.x, child.translation.y * scale.y)
        val rotated = rotate(scaled, rotation)
        return Transform2d(
            translation = translation + rotated,
            rotation = rotation + child.rotation,
            scale = Vec2d(scale.x * child.scale.x, scale.y * child.scale.y),
            alpha = alpha * child.alpha
        )
    }

    companion object {
        val IDENTITY = Transform2d(Vec2d(0.0, 0.0), 0.0, Vec2d(1.0, 1.0), 1.0)
    }
}

data class SampledScene(
    val time: Double,
    val rects: List<SampledRect>
)

data class SampledRect(
    val id: String,
    val objectId: Int,
    val center: Vec2d,
    val size: Vec2d,
    val rotation: Double,
    val scale: Vec2d,
    val alpha: Double,
    val z: Double,
    val fill: PaintSpec,
    val material: MaterialBinding,
    val tags: Set<String>
)

data class RectVertex(
    val position: Vec4f,
    val color: Vec4f,
    val material: Vec4f,
    val shaderData: Vec4f
)

data class RectMesh(
    val vertices: List<RectVertex>,
    val indices: IntArray
)

data class RectMeshAttributeNames(
    val position: String = "a_position",
    val color: String = "a_color",
    val material: String = "a_material",
    val shaderData: String = "a_shader"
)

data class RectMaterialShaderCode(
    val insertion: String,
    val exampleFragmentShader: String,
    val compiledMaterials: List<StackSLRectMaterialCompileResult>
) {
    val ok: Boolean get() = compiledMaterials.all { it.ok }
    val diagnostics: List<String>
        get() = compiledMaterials.flatMap { result ->
            result.stackSL.diagnostics.map { "${result.module.name}: $it" }
        }
}

data class FramePacket(
    val time: Double,
    val mesh: RectMesh,
    val materialTable: MaterialTable,
    val semantic: SemanticFrame
)

data class SemanticFrame(
    val rects: List<SampledRect>,
    val contacts: List<ContactPair>,
    val metrics: FrameSemanticMetrics
)

data class RectCollider(
    val id: String,
    val corners: Array<Vec2d>,
    val center: Vec2d,
    val axes: Array<Vec2d>,
    val halfExtents: Vec2d
)

data class ContactPair(
    val a: String,
    val b: String,
    val overlapDepth: Double
)

sealed interface VisualEvent {
    val time: Double
    val confidence: Double

    data class TouchStart(
        override val time: Double,
        val a: String,
        val b: String,
        override val confidence: Double
    ) : VisualEvent

    data class TouchEnd(
        override val time: Double,
        val a: String,
        val b: String,
        override val confidence: Double
    ) : VisualEvent

    data class DirectionChange(
        override val time: Double,
        val id: String,
        val fromAngle: Double,
        val toAngle: Double,
        override val confidence: Double
    ) : VisualEvent

    data class Stop(
        override val time: Double,
        val id: String,
        override val confidence: Double
    ) : VisualEvent

    data class StartMoving(
        override val time: Double,
        val id: String,
        override val confidence: Double
    ) : VisualEvent

    data class EnterScreen(
        override val time: Double,
        val id: String,
        override val confidence: Double
    ) : VisualEvent

    data class LeaveScreen(
        override val time: Double,
        val id: String,
        override val confidence: Double
    ) : VisualEvent

    data class RotationReversal(
        override val time: Double,
        val id: String,
        override val confidence: Double
    ) : VisualEvent

    data class OpacityCrossing(
        override val time: Double,
        val id: String,
        val threshold: Double,
        val rising: Boolean,
        override val confidence: Double
    ) : VisualEvent
}

data class MotionMetrics(
    val objectId: String,
    val time0: Double,
    val time1: Double,
    val dxMeters: Double,
    val dyMeters: Double,
    val speedMetersPerSecond: Double,
    val directionRadians: Double,
    val dRotationRadians: Double,
    val angularVelocityRadiansPerSecond: Double,
    val visibleAreaSquareMeters: Double,
    val opacityChange: Double
)

data class FrameSemanticMetrics(
    val time: Double,
    val visibleObjectCount: Int,
    val totalVisibleAreaSquareMeters: Double,
    val averageVelocity: Vec2d,
    val averageSpeedMetersPerSecond: Double,
    val averageAngularVelocityRadiansPerSecond: Double,
    val totalOpacityWeightedArea: Double
)

data class AnimationReport(
    val duration: Double,
    val frameMetrics: List<FrameSemanticMetrics>,
    val objectMetrics: Map<String, List<MotionMetrics>>,
    val events: List<VisualEvent>,
    val warnings: List<AnimationWarning>
)

sealed interface AnimationWarning {
    data class MostlyBlank(val start: Double, val end: Double) : AnimationWarning
    data class TooMuchChange(val time: Double, val amount: Double) : AnimationWarning
    data class ObjectNeverVisible(val id: String) : AnimationWarning
    data class ObjectLeavesTooEarly(val id: String, val time: Double) : AnimationWarning
    data class ExcessiveOverlap(val time: Double, val ids: List<String>) : AnimationWarning
    data class AspectMismatch(val wallAspect: Double, val renderAspect: Double) : AnimationWarning
}

data class AnalysisSettings(
    val fps: Double? = null,
    val renderWidth: Double? = null,
    val renderHeight: Double? = null,
    val blankAreaThreshold: Double = 1e-4,
    val tooMuchChangeSpeed: Double = 8.0,
    val stopSpeed: Double = 0.002,
    val startMovingSpeed: Double = 0.006,
    val directionChangeRadians: Double = PI / 3.0,
    val opacityThresholds: List<Double> = listOf(0.01, 0.5)
)

data class ParseResult(
    val tokens: List<Token>,
    val diagnostics: List<String> = emptyList()
) {
    val ok: Boolean get() = diagnostics.isEmpty()
}

data class CompileResult(
    val programOrNull: SceneProgram?,
    val diagnostics: List<String> = emptyList()
) {
    val ok: Boolean get() = programOrNull != null && diagnostics.isEmpty()

    /**
     * The compiled program, or a [RectLangException] carrying the diagnostics if
     * compilation failed. Prefer this (and the copy-paste-friendly `.program!!`,
     * where the `!!` is now a harmless no-op) over reaching for [programOrNull]:
     * a failed compile reports *why* — e.g. "Unknown word: arra" — instead of an
     * opaque NullPointerException. Inspect [ok]/[diagnostics] first if you'd rather
     * handle failure than throw.
     */
    val program: SceneProgram
        get() = programOrNull ?: throw RectLangException(
            if (diagnostics.isEmpty()) "RectSL compilation failed"
            else "RectSL compilation failed:\n" + diagnostics.joinToString("\n")
        )
}

interface AnimationOracle {
    fun parse(source: String): ParseResult
    fun compile(source: String): CompileResult
    fun sample(program: SceneProgram, time: Double): SampledScene
    fun lower(scene: SampledScene): RectMesh
    fun analyze(program: SceneProgram, settings: AnalysisSettings = AnalysisSettings()): AnimationReport
    fun mutate(program: SceneProgram, edit: ProgramEdit): SceneProgram
}

sealed interface ProgramEdit

object RectAnimationOracle : AnimationOracle {
    override fun parse(source: String): ParseResult =
        try {
            ParseResult(RectLangTokenizer(source).tokenize())
        } catch (t: Throwable) {
            ParseResult(emptyList(), listOf(t.message ?: t.toString()))
        }

    override fun compile(source: String): CompileResult =
        try {
            val tokens = RectLangTokenizer(source).tokenize()
            CompileResult(RectLangVm().eval(tokens))
        } catch (t: Throwable) {
            CompileResult(null, listOf(t.message ?: t.toString()))
        }

    override fun sample(program: SceneProgram, time: Double): SampledScene = program.sample(time)
    override fun lower(scene: SampledScene): RectMesh = lowerSampledScene(scene)
    override fun analyze(program: SceneProgram, settings: AnalysisSettings): AnimationReport = analyzeAnimation(program, settings)
    override fun mutate(program: SceneProgram, edit: ProgramEdit): SceneProgram = program
}

/**
 * Compile a RectSL program with host-supplied distribution curves bound by name.
 * Each entry becomes a bare word that pushes its [Functions.Fn] (like a named
 * track), so a program can `sample` a curve the host built — including one that
 * `fitBest`/`evolve` optimised toward a target. This is the Functions→RectSL half
 * of the bridge: the host searches curve-space, RectSL lays out the array from the
 * winner.
 */
fun compileRectSL(
    source: String,
    curves: Map<String, Functions.Fn> = emptyMap(),
    engine: KotlinScriptEngine? = null,
    precompiledEscapes: Map<String, (RectEscapeContext) -> Any?> = emptyMap()
): CompileResult =
    try {
        CompileResult(
            RectLangVm(namedCurves = curves, escapeEngine = engine, precompiledEscapes = precompiledEscapes)
                .eval(RectLangTokenizer(source).tokenize())
        )
    } catch (t: Throwable) {
        CompileResult(null, listOf(t.message ?: t.toString()))
    }

private fun escapeWrappedSource(source: String): String =
    "{ ctx: afield.rectsl.RectEscapeContext -> with(afield.stdlib.Functions){ with(ctx){\n$source\n} } }"

@Suppress("UNCHECKED_CAST")
private fun castEscapeLambda(value: Any?, source: String): (RectEscapeContext) -> Any? =
    (value as? Function1<RectEscapeContext, Any?>)
        ?: throw RectLangException("{{ … }} did not evaluate to a lambda (got ${value?.javaClass?.name}): ${source.take(60)}")

private fun escapeSnippetValue(result: ResultValue?, source: String): Any? = when (result) {
    is ResultValue.Value -> result.value
    is ResultValue.Error -> throw RectLangException("{{ … }} threw while building: ${result.error.message}")
    else -> throw RectLangException("{{ … }} produced no lambda: ${source.take(60)}")
}

/** All distinct `{{ … }}` escape sources in a token stream, recursing into quotes. */
private fun collectEscapeSources(tokens: List<Token>): List<String> {
    val out = LinkedHashSet<String>()
    fun walk(ts: List<Token>) {
        ts.forEach { t ->
            when (t) {
                is Token.KotlinEscape -> out += t.source
                is Token.Quote -> walk(t.tokens)
                else -> {}
            }
        }
    }
    walk(tokens)
    return out.toList()
}

/**
 * Compile every `{{ … }}` escape in [source] to its lambda **ahead of time**, using
 * the engine's *suspend* API so the compile runs off the caller's thread (on the
 * compilation dispatcher) rather than blocking it. Call this before a graphics render
 * loop — whose runloop is non-reentrant, so a lazy `compileSnippetAndWait` on first
 * use *during* the render would deadlock — then hand the result to [compileRectSL] as
 * `precompiledEscapes`, so the render-time compile is pure (only cached lambda invokes).
 */
suspend fun prewarmRectSLEscapes(
    source: String,
    engine: KotlinScriptEngine
): Map<String, (RectEscapeContext) -> Any?> {
    val out = LinkedHashMap<String, (RectEscapeContext) -> Any?>()
    collectEscapeSources(RectLangTokenizer(source).tokenize()).forEach { src ->
        val diagnostics = mutableListOf<String>()
        val snippet = engine.compileSnippet(escapeWrappedSource(src)) { diags -> diags.forEach { diagnostics += it.message } }
            ?: throw RectLangException("{{ … }} did not compile: ${diagnostics.joinToString("; ").ifBlank { src.take(60) }}")
        out[src] = castEscapeLambda(escapeSnippetValue(snippet.eval(onEvalException = { throw it }), src), src)
    }
    return out
}

enum class RectWordEffect {
    Ordinary,
    Mutator
}

data class RectWordMetadata(
    val spelling: String,
    val effect: RectWordEffect
)

class Word(
    val metadata: RectWordMetadata,
    private val action: (RectLangVm) -> Unit
) {
    fun apply(vm: RectLangVm) = action(vm)
}

data class LoopFrame(
    val index: Int,
    val count: Int,
    val ordinal: Int = index,
    val col: Int = -1,
    val row: Int = -1,
    val cols: Int = -1,
    val rows: Int = -1
)

class RectLangVm(
    private val words: MutableMap<String, Word> = mutableMapOf(),
    private val scene: SceneBuilder = SceneBuilder(),
    private val namedCurves: Map<String, Functions.Fn> = emptyMap(),
    private val escapeEngine: KotlinScriptEngine? = null,
    precompiledEscapes: Map<String, (RectEscapeContext) -> Any?> = emptyMap()
) {
    private val stack = ArrayDeque<Value>()
    private val definitions = linkedMapOf<String, List<Token>>()
    private val namedTracks = linkedMapOf<String, Track<Double>>()
    private val escapeLambdas = linkedMapOf<String, (RectEscapeContext) -> Any?>().apply { putAll(precompiledEscapes) }
    private val loopStack = ArrayDeque<LoopFrame>()
    private var tokens: List<Token> = emptyList()
    private var pc: Int = 0

    // --- Plotting layer (rectsl-plot-axes) --------------------------------------
    // A shared data→world coordinate frame so several series overlay in one domain,
    // with axes/ticks/labels/legends. All fields default to null/0, so they are
    // invisible to the classic chart words (`bars`/`scatter`/`heat`), which are left
    // byte-identical — a hard compatibility requirement. See [plotFrame], the
    // `frame`/`margins`/`domain` words, and `axes`/`line`/`area`/`points`/`colorbar`.
    private var plotXDomain: Pair<Double, Double>? = null
    private var plotYDomain: Pair<Double, Double>? = null
    private var plotMarginL: Double = 0.0
    private var plotMarginR: Double = 0.0
    private var plotMarginB: Double = 0.0
    private var plotMarginT: Double = 0.0

    init {
        installCoreWords()
    }

    fun wordMetadata(): List<RectWordMetadata> =
        words.values.map { it.metadata }.distinctBy { it.spelling }.sortedBy { it.spelling }

    fun eval(tokens: List<Token>): SceneProgram {
        evalBlock(tokens)
        return scene.build()
    }

    fun evalBlock(block: List<Token>) {
        val oldTokens = tokens
        val oldPc = pc
        tokens = block
        pc = 0
        try {
            while (pc < tokens.size) evalToken(tokens[pc++])
        } finally {
            tokens = oldTokens
            pc = oldPc
        }
    }

    private fun evalToken(token: Token) {
        when (token) {
            is Token.Number -> push(VNum(token.value))
            is Token.StringLit -> push(VStr(token.value))
            is Token.ColorLit -> push(VColor(token.color))
            is Token.Quote -> push(VQuote(token.tokens, token.source))
            is Token.KotlinEscape -> push(evalKotlinEscape(token.source))
            is Token.Word -> evalWord(token.name)
        }
    }

    /**
     * Evaluate a `{{ … }}` escape and push the result. Each unique escape is compiled
     * to a `(RectEscapeContext) -> Any?` lambda exactly **once** (a ~76 ms K2 compile +
     * a ~5 ms eval-to-lambda), cached, then **invoked per use** as a plain JVM call —
     * so an escape inside an `array` body pays the compile once, not per copy. Because
     * it takes the array context, the Kotlin can read `i`/`n`/`f`/`f0`/`fc`/`ic` and
     * vary per copy. Runs in a `with(Functions){…}` scope; the result maps to a Number,
     * an `Fn`, or a `Track<Double>`. Needs a live Kotlin engine (the escapeEngine, or
     * the current box's when run inside Field).
     */
    private fun evalKotlinEscape(source: String): Value {
        val lambda = escapeLambdas.getOrPut(source) { compileEscape(source) }
        val result = try {
            lambda(currentEscapeContext())
        } catch (t: Throwable) {
            throw RectLangException("{{ … }} threw: ${t.message ?: t.toString()} — ${source.take(60)}")
        }
        return mapEscapeValue(result, source)
    }

    // Lazy (synchronous) compile — safe off a render loop (live editing, jvm-main, the
    // smoke check). Inside a graphics render loop the runBlocking here would deadlock the
    // non-reentrant runloop, so those paths must pre-warm via prewarmRectSLEscapes instead.
    private fun compileEscape(source: String): (RectEscapeContext) -> Any? {
        val engine = escapeEngine ?: Box.context.get()?.ki
            ?: throw RectLangException(
                "{{ … }} Kotlin escape needs a live Kotlin engine — run inside a Field box, or use " +
                    "compileRectSL(source, engine = …). Escape: ${source.take(60)}"
            )
        val diagnostics = mutableListOf<String>()
        val snippet = engine.compileSnippetAndWait(escapeWrappedSource(source)) { diags -> diags.forEach { diagnostics += it.message } }
            ?: throw RectLangException(
                "{{ … }} did not compile: ${diagnostics.joinToString("; ").ifBlank { source.take(60) }}"
            )
        return castEscapeLambda(escapeSnippetValue(snippet.evalAndWait(onEvalException = { throw it }), source), source)
    }

    private fun currentEscapeContext(): RectEscapeContext {
        val frame = loopStack.lastOrNull() ?: return RectEscapeContext(0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        val n = frame.count
        val fr = if (n <= 1) 0.0 else frame.ordinal.toDouble() / (n - 1).toDouble()
        return RectEscapeContext(
            i = frame.index.toDouble(),
            n = n.toDouble(),
            f = fr,
            f0 = if (n <= 0) 0.0 else frame.ordinal.toDouble() / n.toDouble(),
            fc = if (n <= 1) 0.0 else fr * 2.0 - 1.0,
            ic = frame.ordinal - (n - 1) / 2.0
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapEscapeValue(value: Any?, source: String): Value = when (value) {
        is Functions.Fn -> VFn(value)
        is Number -> VNum(value.toDouble())
        is Track<*> -> if (value.kind == TrackValueKind.Double) VTrack(value as Track<Double>)
        else throw RectLangException("{{ … }} returned Track<${value.kind}>, expected Track<Double>")
        else -> throw RectLangException(
            "{{ … }} must return a Number, an Fn, or a Track<Double>, but returned " +
                "${value?.let { it::class.simpleName ?: it.javaClass.name } ?: "null"}: ${source.take(60)}"
        )
    }

    private fun evalWord(name: String) {
        if (name == ":") {
            readDefinition()
            return
        }

        definitions[name]?.let {
            evalBlock(it)
            return
        }

        namedTracks[name]?.let {
            push(VTrack(it))
            return
        }

        namedCurves[name]?.let {
            push(VFn(it))
            return
        }

        if (name !in words && scene.hasMaterial(name)) {
            push(VMaterialRef(name))
            return
        }

        val word = words[name] ?: throw RectLangException(unknownWordMessage(name))
        word.apply(this)
    }

    private fun unknownWordMessage(name: String): String {
        val arrowed = words["->$name"]
        if (arrowed?.metadata?.effect == RectWordEffect.Mutator) {
            return "Unknown word: $name (mutators use '->'; did you mean '->$name'?)"
        }
        return "Unknown word: $name" + suggestionFor(name)
    }

    /**
     * A "did you mean …?" tail for an unknown word — the vocabulary entries closest
     * (by Levenshtein distance) to [name]. Catches the common typo, e.g. `arra`
     * → `array`. Returns "" when nothing is near enough to be worth guessing.
     */
    private fun suggestionFor(name: String): String {
        val vocabulary = words.keys + definitions.keys + namedTracks.keys + namedCurves.keys
        val threshold = if (name.length <= 4) 1 else 2
        val near = vocabulary
            .map { it to levenshtein(name, it, threshold) }
            .filter { it.second <= threshold }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .map { it.first }
            .take(3)
        return if (near.isEmpty()) "" else " (did you mean ${near.joinToString(" or ") { "'$it'" }}?)"
    }

    /** Edit distance between [a] and [b], short-circuiting once it exceeds [max]. */
    private fun levenshtein(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    fun push(v: Value) {
        stack.addLast(v)
    }

    fun pop(): Value =
        if (stack.isEmpty()) throw RectLangException("Stack underflow") else stack.removeLast()

    fun peek(): Value =
        if (stack.isEmpty()) throw RectLangException("Stack underflow") else stack.last()

    fun peekOrNull(): Value? = stack.lastOrNull()

    /** The stack after evaluation — lets tooling/tests extract values (e.g. a built [VFn]). */
    fun stackValues(): List<Value> = stack.toList()

    fun replaceStackRefs(oldId: String, newRef: Value) {
        val next = ArrayDeque<Value>()
        while (stack.isNotEmpty()) {
            next.addLast(
                when (val value = stack.removeFirst()) {
                    is VRect -> if (value.id == oldId && newRef is VRect) newRef else value
                    is VGroup -> if (value.id == oldId && newRef is VGroup) newRef else value
                    is VSelection -> VSelection(value.refs.map { ref ->
                        if (ref.id == oldId) {
                            when (newRef) {
                                is VRect -> NodeRef.Rect(newRef.id)
                                is VGroup -> NodeRef.Group(newRef.id)
                                else -> ref
                            }
                        } else ref
                    })
                    else -> value
                }
            )
        }
        stack.addAll(next)
    }

    fun popNum(): Double =
        (pop() as? VNum)?.x ?: throw RectLangException("Expected number")

    fun popString(): String =
        (pop() as? VStr)?.x ?: throw RectLangException("Expected string")

    fun popQuote(): VQuote =
        pop() as? VQuote ?: throw RectLangException("Expected quote")

    fun popFn(label: String): Functions.Fn =
        (pop() as? VFn)?.fn ?: throw RectLangException("$label expected an Fn (curve)")

    fun popData(label: String): List<Double> =
        (pop() as? VData)?.values ?: throw RectLangException("$label expected Data (a numeric list — build one with `data`, `linspace`, or `normalize`)")

    /** Current stack depth — mark it before an [evalBlock], then [drainNumbersAbove] the mark. */
    fun stackDepth(): Int = stack.size

    /** Pop everything pushed above [mark] as a list of Numbers, preserving push order. */
    fun drainNumbersAbove(mark: Int, label: String): List<Double> {
        val out = ArrayDeque<Double>()
        while (stack.size > mark) {
            val v = stack.removeLast()
            out.addFirst((v as? VNum)?.x
                ?: throw RectLangException("$label expected the quote to leave only Numbers, found ${v.describe()}"))
        }
        return out.toList()
    }

    fun popColor(): Color4 = when (val v = pop()) {
        is VColor -> v.rgba
        is VNum -> Color4.fromHex(v.x.toLong())
        else -> throw RectLangException("Expected Color, found ${v.describe()}")
    }

    fun popPaint(): PaintSpec = when (val v = pop()) {
        is VPaint -> v.paint
        else -> throw RectLangException("Expected Paint, found ${v.describe()}")
    }

    fun popScalarTrackOrConstant(word: String): Track<Double> = scalarTrackFrom(pop(), word)

    fun popBoolean(word: String): Boolean = when (val value = pop()) {
        is VNum -> abs(value.x) > 1e-12
        is VRect, is VGroup -> true
        is VSelection -> value.refs.isNotEmpty()
        else -> throw RectLangException("$word expected boolean-like Number, Rect, Group, or Selection, found ${value.describe()}")
    }

    @Suppress("UNCHECKED_CAST")
    fun scalarTrackFrom(value: Value, word: String): Track<Double> =
        when (value) {
            is VNum -> constantTrack(value.x)
            is VQuote -> quoteToScalarTrack(value, TrackMode.Tcb)
            is VTrack<*> -> {
                if (value.track.kind != TrackValueKind.Double) {
                    throw RectLangException("$word expected Number or Track<Double>, found Track<${value.track.kind}>")
                }
                value.track as Track<Double>
            }
            else -> throw RectLangException("$word expected Number or Track<Double>, found ${value.describe()}")
        }

    fun pushUnaryScalar(word: String, op: TrackOp1) {
        when (val value = pop()) {
            is VNum -> push(VNum(op.eval(value.x)))
            is VQuote, is VTrack<*> -> push(VTrack(MappedDoubleTrack(scalarTrackFrom(value, word), op)))
            else -> throw RectLangException("$word expected Number or Track<Double>, found ${value.describe()}")
        }
    }

    fun pushBinaryScalar(word: String, op: TrackOp2) {
        val b = pop()
        val a = pop()
        if (a is VNum && b is VNum) {
            push(VNum(op.eval(a.x, b.x)))
        } else {
            push(VTrack(BinaryDoubleTrack(scalarTrackFrom(a, word), scalarTrackFrom(b, word), op)))
        }
    }

    fun pushTernaryScalar(word: String, op: TrackOp3) {
        val c = pop()
        val b = pop()
        val a = pop()
        if (a is VNum && b is VNum && c is VNum) {
            push(VNum(op.eval(a.x, b.x, c.x)))
        } else {
            val at = scalarTrackFrom(a, word)
            val bt = scalarTrackFrom(b, word)
            val ct = scalarTrackFrom(c, word)
            push(VTrack(TernaryDoubleTrack(at, bt, ct, op)))
        }
    }

    fun pushCompare(word: String, compareNum: (Double, Double) -> Boolean, compareStr: ((String, String) -> Boolean)? = null) {
        val b = pop()
        val a = pop()
        val result = when {
            a is VNum && b is VNum -> compareNum(a.x, b.x)
            a is VStr && b is VStr && compareStr != null -> compareStr(a.x, b.x)
            else -> throw RectLangException("$word expected comparable Numbers${if (compareStr != null) " or Strings" else ""}, found ${a.describe()} and ${b.describe()}")
        }
        push(VNum(if (result) 1.0 else 0.0))
    }

    fun builder(): SceneBuilder = scene

    fun defineTrack(name: String, track: Track<Double>) {
        namedTracks[name] = track
    }

    fun namedTrack(name: String): Track<Double> =
        namedTracks[name] ?: throw RectLangException("Unknown track '$name'")

    fun withLoop(index: Int, count: Int, ordinal: Int = index, block: () -> Unit) {
        loopStack.addLast(LoopFrame(index, count, ordinal))
        try {
            block()
        } finally {
            loopStack.removeLast()
        }
    }

    fun withGrid(index: Int, count: Int, col: Int, row: Int, cols: Int, rows: Int, block: () -> Unit) {
        loopStack.addLast(LoopFrame(index, count, index, col, row, cols, rows))
        try {
            block()
        } finally {
            loopStack.removeLast()
        }
    }

    private fun loopFrame(word: String): LoopFrame =
        loopStack.lastOrNull() ?: throw RectLangException("$word is only available inside repeat/range/array/grid/radial")

    fun loopIndex(): Int = loopFrame("i").index

    fun loopCount(): Int = loopFrame("count").count

    /** Normalized fraction across the array, i/(n-1) in [0,1]; 0 for a single element. */
    fun loopFraction(): Double {
        val f = loopFrame("f")
        return if (f.count <= 1) 0.0 else f.ordinal.toDouble() / (f.count - 1).toDouble()
    }

    /** Cyclic fraction ordinal/n in [0,1); handy for rings (angle = f0 tau *). */
    fun loopFraction0(): Double {
        val f = loopFrame("f0")
        return if (f.count <= 0) 0.0 else f.ordinal.toDouble() / f.count.toDouble()
    }

    /** Centered fraction 2f-1 in [-1,1]; 0 for a single element. */
    fun loopFractionCentered(): Double = if (loopFrame("fc").count <= 1) 0.0 else loopFraction() * 2.0 - 1.0

    /** Centered index ordinal-(n-1)/2, symmetric around 0. */
    fun loopCenteredIndex(): Double {
        val f = loopFrame("ic")
        return f.ordinal - (f.count - 1) / 2.0
    }

    fun gridCol(): Int = loopFrame("col").col.also { if (it < 0) throw RectLangException("col is only available inside grid") }
    fun gridRow(): Int = loopFrame("row").row.also { if (it < 0) throw RectLangException("row is only available inside grid") }
    fun gridCols(): Int = loopFrame("cols").cols.also { if (it < 0) throw RectLangException("cols is only available inside grid") }
    fun gridRows(): Int = loopFrame("rows").rows.also { if (it < 0) throw RectLangException("rows is only available inside grid") }

    /** Template for array/radial: an explicit rect/group/id on the stack, else the active object. */
    fun popTemplateRef(label: String): NodeRef {
        val top = peekOrNull()
        if (top is VRect || top is VGroup || top is VStr) return objectRefFromValue(pop())
        return scene.activeRef() ?: throw RectLangException("$label expected an active object or a rect/group on the stack")
    }

    fun nextTokenOrNull(): Token? = tokens.getOrNull(pc)

    fun consumeToken(): Token =
        tokens.getOrNull(pc++) ?: throw RectLangException("Unexpected end of input")

    fun consumeWordName(label: String): String =
        (consumeToken() as? Token.Word)?.name ?: throw RectLangException("$label expected word")

    fun consumeName(label: String): String =
        when (val token = consumeToken()) {
            is Token.Word -> token.name
            is Token.StringLit -> token.value
            else -> throw RectLangException("$label expected word or string")
        }

    fun consumeNumber(label: String): Double =
        (consumeToken() as? Token.Number)?.value ?: throw RectLangException("$label expected number")

    fun consumeQuote(label: String): VQuote {
        val q = consumeToken() as? Token.Quote ?: throw RectLangException("$label expected quote")
        return VQuote(q.tokens, q.source)
    }

    fun popObjectRefOrActive(): NodeRef =
        scene.activeRef() ?: when (val value = pop()) {
            is VRect -> NodeRef.Rect(value.id)
            is VGroup -> NodeRef.Group(value.id)
            is VStr -> scene.refFor(value.x)
            else -> throw RectLangException("Expected rect or group reference, found ${value.describe()}")
        }

    fun objectRefFromValue(value: Value): NodeRef = when (value) {
        is VRect -> NodeRef.Rect(value.id)
        is VGroup -> NodeRef.Group(value.id)
        is VStr -> scene.refFor(value.x)
        else -> throw RectLangException("Expected rect or group reference, found ${value.describe()}")
    }

    fun popSelectionOrActive(label: String): VSelection {
        val value = peekOrNull()
        if (value is VSelection || value is VRect || value is VGroup || value is VStr) {
            return selectionFrom(pop(), label)
        }
        val active = scene.activeRef() ?: throw RectLangException("$label expected Selection, Rect, Group, String id, or active object")
        return VSelection(listOf(active))
    }

    fun selectionFrom(value: Value, label: String): VSelection =
        when (value) {
            is VSelection -> value
            is VRect -> VSelection(listOf(NodeRef.Rect(value.id)))
            is VGroup -> VSelection(listOf(NodeRef.Group(value.id)))
            is VStr -> VSelection(listOf(scene.refFor(value.x)))
            else -> throw RectLangException("$label expected Selection, Rect, Group, or String id, found ${value.describe()}")
        }

    fun withSelected(ref: NodeRef, block: () -> Unit) {
        scene.withActive(ref, block)
    }

    private fun readDefinition() {
        val name = consumeWordName("Definition")
        val body = mutableListOf<Token>()
        while (pc < tokens.size) {
            val token = consumeToken()
            if (token is Token.Word && token.name == ";") {
                definitions[name] = body
                return
            }
            body += token
        }
        throw RectLangException("Definition '$name' is missing terminating ';'")
    }

    private fun installCoreWords() {
        word(";") { it.builder().endScope() }
        word("apply") { it.evalBlock(it.popQuote().tokens) }
        word("defer") { /* Quote literals are already deferred values. */ }

        word("dup") { vm ->
            val value = vm.peek()
            when (value) {
                is VRect, is VGroup -> vm.push(vm.builder().cloneNode(vm.objectRefFromValue(value)))
                is VSelection -> vm.push(vm.builder().cloneSelection(value.refs, deep = false))
                else -> vm.push(value)
            }
        }
        word("copy") { vm ->
            when (val value = vm.peek()) {
                is VSelection -> vm.push(vm.builder().cloneSelection(value.refs, deep = false))
                else -> vm.push(vm.builder().cloneNode(vm.objectRefFromValue(value)))
            }
        }
        word("clone") { vm ->
            when (val value = vm.peek()) {
                is VSelection -> vm.push(vm.builder().cloneSelection(value.refs, deep = false))
                else -> vm.push(vm.builder().cloneNode(vm.objectRefFromValue(value)))
            }
        }
        word("copy-deep") { vm ->
            when (val value = vm.peek()) {
                is VSelection -> vm.push(vm.builder().cloneSelection(value.refs, deep = true))
                else -> vm.push(vm.builder().cloneNodeDeep(vm.objectRefFromValue(value)))
            }
        }
        word("clone-deep") { vm ->
            when (val value = vm.peek()) {
                is VSelection -> vm.push(vm.builder().cloneSelection(value.refs, deep = true))
                else -> vm.push(vm.builder().cloneNodeDeep(vm.objectRefFromValue(value)))
            }
        }
        word("drop") { it.pop() }
        word("swap") { vm ->
            val a = vm.pop()
            val b = vm.pop()
            vm.push(a)
            vm.push(b)
        }
        word("over") { vm ->
            val a = vm.pop()
            val b = vm.peek()
            vm.push(a)
            vm.push(b)
        }
        word("pi") { it.push(VNum(PI)) }
        word("tau") { it.push(VNum(PI * 2.0)) }
        word("+") { it.pushBinaryScalar("+", TrackOp2.ADD) }
        word("add") { it.pushBinaryScalar("add", TrackOp2.ADD) }
        word("-") { it.pushBinaryScalar("-", TrackOp2.SUB) }
        word("sub") { it.pushBinaryScalar("sub", TrackOp2.SUB) }
        word("*") { it.pushBinaryScalar("*", TrackOp2.MUL) }
        word("mul") { it.pushBinaryScalar("mul", TrackOp2.MUL) }
        word("/") { it.pushBinaryScalar("/", TrackOp2.DIV) }
        word("div") { it.pushBinaryScalar("div", TrackOp2.DIV) }
        word("mod") { it.pushBinaryScalar("mod", TrackOp2.MOD) }
        word("pow") { it.pushBinaryScalar("pow", TrackOp2.POW) }
        word("min") { it.pushBinaryScalar("min", TrackOp2.MIN) }
        word("max") { it.pushBinaryScalar("max", TrackOp2.MAX) }
        word("neg") { it.pushUnaryScalar("neg", TrackOp1.NEG) }
        word("negate") { it.pushUnaryScalar("negate", TrackOp1.NEG) }
        word("abs") { it.pushUnaryScalar("abs", TrackOp1.ABS) }
        word("floor") { it.pushUnaryScalar("floor", TrackOp1.FLOOR) }
        word("ceil") { it.pushUnaryScalar("ceil", TrackOp1.CEIL) }
        word("fract") { it.pushUnaryScalar("fract", TrackOp1.FRACT) }
        word("sqrt") { it.pushUnaryScalar("sqrt", TrackOp1.SQRT) }
        word("sin") { it.pushUnaryScalar("sin", TrackOp1.SIN) }
        word("cos") { it.pushUnaryScalar("cos", TrackOp1.COS) }
        word("clamp") { it.pushTernaryScalar("clamp", TrackOp3.CLAMP) }
        word("sat") { it.pushUnaryScalar("sat", TrackOp1.SATURATE) }
        word("saturate") { it.pushUnaryScalar("saturate", TrackOp1.SATURATE) }
        word("mix") { it.pushTernaryScalar("mix", TrackOp3.MIX) }
        word("lerp") { it.pushTernaryScalar("lerp", TrackOp3.MIX) }
        word("smoothstep") { it.pushTernaryScalar("smoothstep", TrackOp3.SMOOTHSTEP) }
        word("<") { it.pushCompare("<", { a, b -> a < b }) }
        word("lt") { it.pushCompare("lt", { a, b -> a < b }) }
        word("<=") { it.pushCompare("<=", { a, b -> a <= b }) }
        word("lte") { it.pushCompare("lte", { a, b -> a <= b }) }
        word(">") { it.pushCompare(">", { a, b -> a > b }) }
        word("gt") { it.pushCompare("gt", { a, b -> a > b }) }
        word(">=") { it.pushCompare(">=", { a, b -> a >= b }) }
        word("gte") { it.pushCompare("gte", { a, b -> a >= b }) }
        word("=") { it.pushCompare("=", { a, b -> abs(a - b) <= 1e-12 }, { a, b -> a == b }) }
        word("eq") { it.pushCompare("eq", { a, b -> abs(a - b) <= 1e-12 }, { a, b -> a == b }) }
        word("!=") { it.pushCompare("!=", { a, b -> abs(a - b) > 1e-12 }, { a, b -> a != b }) }
        word("<>") { it.pushCompare("<>", { a, b -> abs(a - b) > 1e-12 }, { a, b -> a != b }) }
        word("neq") { it.pushCompare("neq", { a, b -> abs(a - b) > 1e-12 }, { a, b -> a != b }) }
        word("and") { vm ->
            val b = vm.popBoolean("and")
            val a = vm.popBoolean("and")
            vm.push(VNum(if (a && b) 1.0 else 0.0))
        }
        word("or") { vm ->
            val b = vm.popBoolean("or")
            val a = vm.popBoolean("or")
            vm.push(VNum(if (a || b) 1.0 else 0.0))
        }
        word("not") { vm -> vm.push(VNum(if (!vm.popBoolean("not")) 1.0 else 0.0)) }

        mutator("wall") { vm ->
            val width = vm.consumeNumber("wall")
            val height = vm.consumeNumber("wall")
            val unit = when (vm.consumeWordName("wall unit")) {
                "meters", "meter", "m" -> UnitKind.Meters
                "pixels", "pixel", "px" -> UnitKind.Pixels
                "abstract", "units", "unit" -> UnitKind.Abstract
                else -> throw RectLangException("wall expected unit meters, pixels, or abstract")
            }
            vm.builder().wall = WallSpec(width, height, unit)
        }
        mutator("duration") { it.builder().duration = it.consumeNumber("duration") }
        mutator("fps") { it.builder().fps = it.consumeNumber("fps") }
        mutator("background") { vm ->
            val value = vm.nextTokenOrNull()
            val color = if (value is Token.ColorLit) {
                vm.consumeToken()
                value.color
            } else vm.popColor()
            vm.builder().background = color
        }
        mutator("fit") { vm ->
            vm.builder().fit = when (vm.consumeWordName("fit")) {
                "contain" -> FitPolicy.Contain
                "cover" -> FitPolicy.Cover
                "stretch" -> FitPolicy.Stretch
                else -> throw RectLangException("fit expected contain, cover, or stretch")
            }
        }

        word("rect") { vm ->
            val id = (vm.peekOrNull() as? VStr)?.let { vm.popString() }
            vm.push(vm.builder().createRect(id))
        }
        word("group") { vm ->
            val id = (vm.peekOrNull() as? VStr)?.let { vm.popString() }
            vm.push(vm.builder().createGroup(id))
        }
        word("end") { it.builder().endGroup() }
        word("end-group") { it.builder().endGroup() }
        mutator("group-selected") { vm ->
            val id = (vm.peekOrNull() as? VStr)?.let { vm.popString() }
            val selection = vm.popSelectionOrActive("group-selected")
            vm.push(vm.builder().groupSelected(id, selection.refs))
        }
        mutator("select") { vm ->
            val ref = vm.objectRefFromValue(vm.pop())
            vm.builder().select(ref)
            vm.push(ref.toValue())
        }
        mutator("rename") { vm ->
            val newId = vm.popString()
            val ref = vm.builder().activeRef() ?: vm.objectRefFromValue(vm.pop())
            val renamed = vm.builder().rename(ref, newId)
            vm.replaceStackRefs(ref.id, renamed)
            vm.push(renamed)
        }
        mutator("tag") { vm -> vm.builder().tag(vm.popString()) }
        word("id") { vm -> vm.push(VStr(vm.builder().activeRef()?.id ?: throw RectLangException("id needs an active rect or group"))) }
        word("rect?") { vm -> vm.push(VNum(if (vm.builder().activeRef() is NodeRef.Rect) 1.0 else 0.0)) }
        word("group?") { vm -> vm.push(VNum(if (vm.builder().activeRef() is NodeRef.Group) 1.0 else 0.0)) }
        word("tagged?") { vm -> vm.push(VNum(if (vm.builder().hasTag(vm.popString())) 1.0 else 0.0)) }

        word("selected") { vm -> vm.push(VSelection(listOf(vm.builder().activeRef() ?: throw RectLangException("No active rect or group")))) }
        word("all") { vm -> vm.push(VSelection(vm.builder().allRefs())) }
        word("roots") { vm -> vm.push(VSelection(vm.builder().rootRefs())) }
        word("children") { vm -> vm.push(VSelection(vm.builder().childrenOf(vm.popSelectionOrActive("children").refs))) }
        word("descendants") { vm -> vm.push(VSelection(vm.builder().descendantsOf(vm.popSelectionOrActive("descendants").refs))) }
        word("rects") { vm ->
            val base = if (vm.peekOrNull() is VSelection || vm.peekOrNull() is VRect || vm.peekOrNull() is VGroup || vm.peekOrNull() is VStr) {
                vm.popSelectionOrActive("rects").refs
            } else vm.builder().allRefs()
            vm.push(VSelection(base.filterIsInstance<NodeRef.Rect>()))
        }
        word("groups") { vm ->
            val base = if (vm.peekOrNull() is VSelection || vm.peekOrNull() is VRect || vm.peekOrNull() is VGroup || vm.peekOrNull() is VStr) {
                vm.popSelectionOrActive("groups").refs
            } else vm.builder().allRefs()
            vm.push(VSelection(base.filterIsInstance<NodeRef.Group>()))
        }
        word("tagged") { vm ->
            val tag = vm.popString()
            val base = if (vm.peekOrNull() is VSelection || vm.peekOrNull() is VRect || vm.peekOrNull() is VGroup || vm.peekOrNull() is VStr) {
                vm.popSelectionOrActive("tagged").refs
            } else vm.builder().allRefs()
            vm.push(VSelection(base.filter { vm.builder().hasTag(it, tag) }))
        }

        mutator("xy") { vm ->
            val y = vm.popScalarTrackOrConstant("xy")
            val x = vm.popScalarTrackOrConstant("xy")
            vm.builder().setX(x)
            vm.builder().setY(y)
        }
        mutator("nxy") { vm ->
            val ny = vm.popScalarTrackOrConstant("nxy")
            val nx = vm.popScalarTrackOrConstant("nxy")
            val wall = vm.builder().wall
            // (n - 0.5) * extent, spelled with the same association as the scalar
            // form so the symbolic node is bit-identical to the old closure.
            vm.builder().setX(
                BinaryDoubleTrack(
                    BinaryDoubleTrack(nx, constantTrack(0.5), TrackOp2.SUB),
                    constantTrack(wall.width),
                    TrackOp2.MUL
                )
            )
            vm.builder().setY(
                BinaryDoubleTrack(
                    BinaryDoubleTrack(ny, constantTrack(0.5), TrackOp2.SUB),
                    constantTrack(wall.height),
                    TrackOp2.MUL
                )
            )
        }
        mutator("x") { vm -> vm.builder().setX(vm.popScalarTrackOrConstant("->x")) }
        mutator("y") { vm -> vm.builder().setY(vm.popScalarTrackOrConstant("->y")) }
        mutator("wh") { vm ->
            val h = vm.popScalarTrackOrConstant("wh")
            val w = vm.popScalarTrackOrConstant("wh")
            vm.builder().setW(w)
            vm.builder().setH(h)
        }
        mutator("nwh") { vm ->
            val nh = vm.popScalarTrackOrConstant("nwh")
            val nw = vm.popScalarTrackOrConstant("nwh")
            val wall = vm.builder().wall
            vm.builder().setW(BinaryDoubleTrack(nw, constantTrack(wall.width), TrackOp2.MUL))
            vm.builder().setH(BinaryDoubleTrack(nh, constantTrack(wall.height), TrackOp2.MUL))
        }
        mutator("w") { vm -> vm.builder().setW(vm.popScalarTrackOrConstant("->w")) }
        mutator("h") { vm -> vm.builder().setH(vm.popScalarTrackOrConstant("->h")) }
        mutator("square") { vm ->
            val side = vm.popScalarTrackOrConstant("square")
            vm.builder().setW(side)
            vm.builder().setH(side)
        }
        mutator("rot") { vm -> vm.builder().setRotation(vm.popScalarTrackOrConstant("->rot")) }
        mutator("rotation") { vm -> vm.builder().setRotation(vm.popScalarTrackOrConstant("->rotation")) }
        mutator("rotate") { vm -> vm.builder().addRotation(vm.popScalarTrackOrConstant("->rotate")) }
        mutator("face-travel") { vm -> vm.builder().faceTravel(0.0) }
        mutator("face-travel-at") { vm -> vm.builder().faceTravel(vm.popNum()) }
        mutator("scale") { vm ->
            val s = vm.popScalarTrackOrConstant("scale")
            vm.builder().setScaleX(s)
            vm.builder().setScaleY(s)
        }
        mutator("scale-xy") { vm ->
            val sy = vm.popScalarTrackOrConstant("scale-xy")
            val sx = vm.popScalarTrackOrConstant("scale-xy")
            vm.builder().setScaleX(sx)
            vm.builder().setScaleY(sy)
        }
        mutator("alpha") { vm -> vm.builder().setAlpha(vm.popScalarTrackOrConstant("->alpha")) }
        mutator("opacity*") { vm -> vm.builder().multiplyAlpha(vm.popScalarTrackOrConstant("->opacity*")) }
        mutator("appear") { vm -> vm.builder().gateVisibility(vm.popNum(), Double.POSITIVE_INFINITY) }
        mutator("vanish") { vm -> vm.builder().gateVisibility(Double.NEGATIVE_INFINITY, vm.popNum()) }
        mutator("live") { vm ->
            val life = vm.popNum()
            val birth = vm.popNum()
            vm.builder().live(birth, life)
        }
        word("x-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("x"))) }
        word("y-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("y"))) }
        word("w-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("w"))) }
        word("h-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("h"))) }
        word("rot-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("rot"))) }
        word("rotation-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("rot"))) }
        word("scale-x-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("scale-x"))) }
        word("scale-y-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("scale-y"))) }
        word("alpha-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("alpha"))) }
        word("z-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("z"))) }
        word("layer-track") { vm -> vm.push(VTrack(vm.builder().scalarTrack("layer"))) }

        mutator("z") { vm -> vm.builder().setZ(vm.popScalarTrackOrConstant("->z")) }
        mutator("layer") { vm ->
            when (val value = vm.pop()) {
                is VStr -> vm.builder().setLayer(value.x)
                is VNum -> vm.builder().setZ(constantTrack(value.x))
                is VTrack<*> -> vm.builder().setZ(vm.scalarTrackFrom(value, "layer"))
                is VQuote -> vm.builder().setZ(vm.scalarTrackFrom(value, "layer"))
                else -> throw RectLangException("layer expected name, Number, or Track<Double>, found ${value.describe()}")
            }
        }
        mutator("front") { it.builder().moveFront() }
        mutator("back") { it.builder().moveBack() }

        word("track") { vm ->
            val next = vm.nextTokenOrNull()
            if (vm.peekOrNull() == null && (next is Token.Word || next is Token.StringLit)) {
                val name = vm.consumeName("track")
                val quote = vm.consumeQuote("track")
                vm.defineTrack(name, vm.quoteToScalarTrack(quote, TrackMode.Tcb))
            } else {
                val quote = vm.popQuote()
                val name = vm.popString()
                vm.defineTrack(name, vm.quoteToScalarTrack(quote, TrackMode.Tcb))
            }
        }
        word("linear-track") { vm ->
            val name = vm.consumeName("linear-track")
            val quote = vm.consumeQuote("linear-track")
            vm.defineTrack(name, vm.quoteToScalarTrack(quote, TrackMode.Linear))
        }
        word("hold-track") { vm ->
            val name = vm.consumeName("hold-track")
            val quote = vm.consumeQuote("hold-track")
            vm.defineTrack(name, vm.quoteToScalarTrack(quote, TrackMode.Hold))
        }
        word("track-ref") { vm -> vm.push(VTrack(vm.namedTrack(vm.popString()))) }

        word("rgb") { vm ->
            val r = vm.consumeNumber("rgb")
            val g = vm.consumeNumber("rgb")
            val b = vm.consumeNumber("rgb")
            vm.push(VColor(Color4(r, g, b)))
        }
        word("rgba") { vm ->
            val r = vm.consumeNumber("rgba")
            val g = vm.consumeNumber("rgba")
            val b = vm.consumeNumber("rgba")
            val a = vm.consumeNumber("rgba")
            vm.push(VColor(Color4(r, g, b, a)))
        }
        word("solid") { vm -> vm.push(VPaint(PaintSpec.Solid(vm.popColor()))) }
        word("linear") { vm ->
            if (vm.peekOrNull() is VQuote) {
                vm.push(VTrack(vm.quoteToScalarTrack(vm.popQuote(), TrackMode.Linear)))
            } else {
                val y1 = vm.popNum()
                val x1 = vm.popNum()
                val y0 = vm.popNum()
                val x0 = vm.popNum()
                val c1 = vm.popColor()
                val c0 = vm.popColor()
                vm.push(VPaint(PaintSpec.LinearGradient(c0, c1, Vec2d(x0, y0), Vec2d(x1, y1))))
            }
        }
        mutator("fill") { vm -> vm.builder().setFill(constantPaintTrack(vm.popPaint())) }

        word("tcb") { vm -> vm.push(VTrack(vm.quoteToScalarTrack(vm.popQuote(), TrackMode.Tcb))) }
        word("hold") { vm -> vm.push(VTrack(vm.quoteToScalarTrack(vm.popQuote(), TrackMode.Hold))) }
        word("tcb!") { vm ->
            val bias = vm.popNum()
            val continuity = vm.popNum()
            val tension = vm.popNum()
            val track = vm.popScalarTrackOrConstant("tcb!")
            val next = if (track is TcbTrack) track.withTcb(tension, continuity, bias) else track
            vm.push(VTrack(next))
        }
        word("delay-time") { vm ->
            val delay = vm.popNum()
            val track = vm.popScalarTrackOrConstant("delay-time")
            vm.push(VTrack(TimeWarpTrack(track, TimeWarpKind.SHIFT, delay)))
        }
        word("scale-time") { vm ->
            val factor = vm.popNum()
            val track = vm.popScalarTrackOrConstant("scale-time")
            vm.push(VTrack(TimeWarpTrack(track, TimeWarpKind.SCALE, factor)))
        }
        word("reverse-time") { vm ->
            val track = vm.popScalarTrackOrConstant("reverse-time")
            val end = vm.builder().duration
            vm.push(VTrack(TimeWarpTrack(track, TimeWarpKind.REVERSE, end)))
        }
        word("loop-time") { vm ->
            val period = vm.popNum()
            val track = vm.popScalarTrackOrConstant("loop-time")
            vm.push(VTrack(TimeWarpTrack(track, TimeWarpKind.LOOP, period)))
        }
        word("pingpong-time") { vm ->
            val period = vm.popNum()
            val track = vm.popScalarTrackOrConstant("pingpong-time")
            vm.push(VTrack(TimeWarpTrack(track, TimeWarpKind.PINGPONG, period)))
        }
        word("time+") { vm ->
            val amount = vm.popNum()
            val track = vm.popScalarTrackOrConstant("time+")
            vm.push(VTrack(shiftTrackKeysOrWarp(track, amount)))
        }
        word("time*") { vm ->
            val factor = vm.popNum()
            val track = vm.popScalarTrackOrConstant("time*")
            vm.push(VTrack(scaleTrackKeysOrWarp(track, factor)))
        }
        word("value+") { vm ->
            val amount = vm.popNum()
            val track = vm.popScalarTrackOrConstant("value+")
            vm.push(
                VTrack(
                    track.mapKeysOrSamples("value+", { it + amount }) {
                        BinaryDoubleTrack(it, constantTrack(amount), TrackOp2.ADD)
                    }
                )
            )
        }
        word("value*") { vm ->
            val factor = vm.popNum()
            val track = vm.popScalarTrackOrConstant("value*")
            vm.push(
                VTrack(
                    track.mapKeysOrSamples("value*", { it * factor }) {
                        BinaryDoubleTrack(it, constantTrack(factor), TrackOp2.MUL)
                    }
                )
            )
        }
        word("clamp-values") { vm ->
            val high = vm.popNum()
            val low = vm.popNum()
            val track = vm.popScalarTrackOrConstant("clamp-values")
            vm.push(
                VTrack(
                    track.mapKeysOrSamples("clamp-values", { it.coerceIn(min(low, high), max(low, high)) }) {
                        TernaryDoubleTrack(it, constantTrack(low), constantTrack(high), TrackOp3.CLAMP)
                    }
                )
            )
        }
        word("snap-time") { vm ->
            val grid = vm.popNum()
            val track = vm.popScalarTrackOrConstant("snap-time")
            if (abs(grid) <= 1e-12) throw RectLangException("snap-time grid must be non-zero")
            vm.push(VTrack(track.mapKeys("snap-time") { it.copy(time = round(it.time / grid) * grid) }))
        }
        word("keys-between") { vm ->
            val high = vm.popNum()
            val low = vm.popNum()
            val track = vm.popScalarTrackOrConstant("keys-between")
            val a = min(low, high)
            val b = max(low, high)
            vm.push(VTrack(track.filterKeys("keys-between") { it.time in a..b }))
        }
        word("keys-before") { vm ->
            val time = vm.popNum()
            val track = vm.popScalarTrackOrConstant("keys-before")
            vm.push(VTrack(track.filterKeys("keys-before") { it.time <= time }))
        }
        word("keys-after") { vm ->
            val time = vm.popNum()
            val track = vm.popScalarTrackOrConstant("keys-after")
            vm.push(VTrack(track.filterKeys("keys-after") { it.time >= time }))
        }
        word("ease-in") { vm -> vm.push(VTrack(easedTrack("ease-in", vm.popScalarTrackOrConstant("ease-in")))) }
        word("ease-out") { vm -> vm.push(VTrack(easedTrack("ease-out", vm.popScalarTrackOrConstant("ease-out")))) }
        word("ease-in-out") { vm -> vm.push(VTrack(easedTrack("ease-in-out", vm.popScalarTrackOrConstant("ease-in-out")))) }
        word("ease-smoothstep") { vm -> vm.push(VTrack(easedTrack("ease-smoothstep", vm.popScalarTrackOrConstant("ease-smoothstep")))) }
        word("smooth") { vm -> vm.push(VTrack(easedTrack("smooth", vm.popScalarTrackOrConstant("smooth")))) }
        mutator("delay-all-time") { vm ->
            val delay = vm.popNum()
            vm.builder().mapActiveScalarTracks { TimeWarpTrack(it, TimeWarpKind.SHIFT, delay) }
        }
        mutator("scale-all-time") { vm ->
            val factor = vm.popNum()
            vm.builder().mapActiveScalarTracks { TimeWarpTrack(it, TimeWarpKind.SCALE, factor) }
        }
        mutator("reverse-all-time") { vm ->
            val end = vm.builder().duration
            vm.builder().mapActiveScalarTracks { TimeWarpTrack(it, TimeWarpKind.REVERSE, end) }
        }
        mutator("loop-all-time") { vm ->
            val period = vm.popNum()
            vm.builder().mapActiveScalarTracks { TimeWarpTrack(it, TimeWarpKind.LOOP, period) }
        }
        mutator("pingpong-all-time") { vm ->
            val period = vm.popNum()
            vm.builder().mapActiveScalarTracks { TimeWarpTrack(it, TimeWarpKind.PINGPONG, period) }
        }
        mutator("stagger-time") { vm ->
            val step = vm.popNum()
            val selection = vm.popSelectionOrActive("stagger-time")
            vm.builder().staggerTime(selection.refs, step)
            vm.push(selection)
        }

        mutator("mirror-x") { it.builder().mirrorX(0.0) }
        mutator("mirror-y") { it.builder().mirrorY(0.0) }
        mutator("mirror-x-at") { vm -> vm.builder().mirrorX(vm.popNum()) }
        mutator("mirror-y-at") { vm -> vm.builder().mirrorY(vm.popNum()) }
        mutator("translate") { vm ->
            val dy = vm.popScalarTrackOrConstant("translate")
            val dx = vm.popScalarTrackOrConstant("translate")
            vm.builder().translate(dx, dy)
        }
        mutator("offset") { vm ->
            val dy = vm.popScalarTrackOrConstant("offset")
            val dx = vm.popScalarTrackOrConstant("offset")
            vm.builder().translate(dx, dy)
        }

        word("material") { vm ->
            val maybeName = vm.nextTokenOrNull()
            val maybeQuote = tokens.getOrNull(pc + 1)
            if (maybeName is Token.Word && maybeQuote is Token.Quote) {
                val name = vm.consumeWordName("material")
                val quote = vm.consumeQuote("material")
                vm.builder().defineStackSLMaterial(name, quote.source)
            } else {
                throw RectLangException("material assignment is a mutator; use '->material'")
            }
        }
        mutator("material") { vm ->
            val material = when (val v = vm.pop()) {
                is VMaterialRef -> v.name
                is VStr -> v.x
                else -> throw RectLangException("->material expected material name, found ${v.describe()}")
            }
            vm.builder().setMaterial(material)
        }
        mutator("shader") { vm ->
            val w = vm.popNum().toFloat()
            val z = vm.popNum().toFloat()
            val y = vm.popNum().toFloat()
            val x = vm.popNum().toFloat()
            vm.builder().setShaderData(Vec4f(x, y, z, w))
        }

        word("each") { vm ->
            val quote = vm.popQuote()
            val selection = vm.popSelectionOrActive("each")
            selection.refs.forEach { ref ->
                vm.withSelected(ref) { vm.evalBlock(quote.tokens) }
            }
            vm.push(selection)
        }
        word("map") { vm ->
            val quote = vm.popQuote()
            val selection = vm.popSelectionOrActive("map")
            selection.refs.forEach { ref ->
                vm.withSelected(ref) { vm.evalBlock(quote.tokens) }
            }
            vm.push(selection)
        }
        word("filter") { vm ->
            val quote = vm.popQuote()
            val selection = vm.popSelectionOrActive("filter")
            val filtered = selection.refs.filter { ref ->
                vm.withSelected(ref) { vm.evalBlock(quote.tokens) }
                vm.popBoolean("filter predicate")
            }
            vm.push(VSelection(filtered))
        }
        word("where") { vm ->
            val quote = vm.popQuote()
            val selection = vm.popSelectionOrActive("where")
            val filtered = selection.refs.filter { ref ->
                vm.withSelected(ref) { vm.evalBlock(quote.tokens) }
                vm.popBoolean("where predicate")
            }
            vm.push(VSelection(filtered))
        }
        word("times") { vm ->
            val quote = vm.popQuote()
            val count = vm.popNum().toInt().coerceAtLeast(0)
            repeat(count) { index ->
                vm.withLoop(index, count) {
                    vm.push(VNum(index.toDouble()))
                    vm.evalBlock(quote.tokens)
                }
            }
        }
        word("repeat") { vm ->
            val quote = vm.popQuote()
            val count = vm.popNum().toInt().coerceAtLeast(0)
            repeat(count) { index ->
                vm.withLoop(index, count) { vm.evalBlock(quote.tokens) }
            }
        }
        word("range") { vm ->
            val quote = vm.popQuote()
            val end = vm.popNum().toInt()
            val start = vm.popNum().toInt()
            val step = if (end >= start) 1 else -1
            val values = generateSequence(start) { it + step }
                .takeWhile { if (step > 0) it < end else it > end }
                .toList()
            values.forEachIndexed { ordinal, value ->
                vm.withLoop(value, values.size, ordinal) {
                    vm.evalBlock(quote.tokens)
                }
            }
        }
        word("i") { vm -> vm.push(VNum(vm.loopIndex().toDouble())) }
        word("loop-count") { vm -> vm.push(VNum(vm.loopCount().toDouble())) }
        word("n") { vm -> vm.push(VNum(vm.loopCount().toDouble())) }

        // --- Array primitives: reproduce a subexpression with per-copy modification ---
        // Extended per-copy context (valid inside repeat/range/array/grid/radial):
        word("f") { vm -> vm.push(VNum(vm.loopFraction())) }
        word("f0") { vm -> vm.push(VNum(vm.loopFraction0())) }
        word("fc") { vm -> vm.push(VNum(vm.loopFractionCentered())) }
        word("ic") { vm -> vm.push(VNum(vm.loopCenteredIndex())) }
        // Grid context (valid inside grid):
        word("col") { vm -> vm.push(VNum(vm.gridCol().toDouble())) }
        word("row") { vm -> vm.push(VNum(vm.gridRow().toDouble())) }
        word("cols") { vm -> vm.push(VNum(vm.gridCols().toDouble())) }
        word("rows") { vm -> vm.push(VNum(vm.gridRows().toDouble())) }
        word("colc") { vm -> vm.push(VNum(vm.gridCol() - (vm.gridCols() - 1) / 2.0)) }
        word("rowc") { vm -> vm.push(VNum(vm.gridRow() - (vm.gridRows() - 1) / 2.0)) }

        word("array") { vm ->
            val mods = vm.popQuote()
            val count = vm.popNum().toInt().coerceAtLeast(0)
            if (count == 0) {
                vm.push(VSelection(emptyList()))
                return@word
            }
            val template = vm.popTemplateRef("array")
            val refs = vm.builder().instantiateArray(template, count)
            refs.forEachIndexed { ordinal, ref ->
                vm.withSelected(ref) { vm.withLoop(ordinal, count, ordinal) { vm.evalBlock(mods.tokens) } }
            }
            vm.push(VSelection(refs))
        }
        word("radial") { vm ->
            val mods = vm.popQuote()
            val radius = vm.popNum()
            val count = vm.popNum().toInt().coerceAtLeast(0)
            if (count == 0) {
                vm.push(VSelection(emptyList()))
                return@word
            }
            val template = vm.popTemplateRef("radial")
            val refs = vm.builder().instantiateArray(template, count)
            refs.forEachIndexed { ordinal, ref ->
                vm.withSelected(ref) {
                    vm.withLoop(ordinal, count, ordinal) {
                        val angle = ordinal.toDouble() / count.toDouble() * (PI * 2.0)
                        vm.builder().setPolar(angle, radius)
                        vm.evalBlock(mods.tokens)
                    }
                }
            }
            vm.push(VSelection(refs))
        }
        word("arc") { vm ->
            val mods = vm.popQuote()
            val sweep = vm.popNum()
            val start = vm.popNum()
            val radius = vm.popNum()
            vm.placePolarArray("arc", mods) { ordinal, count ->
                val frac = if (count <= 1) 0.0 else ordinal.toDouble() / (count - 1).toDouble()
                (start + sweep * frac) to radius
            }
        }
        word("spiral") { vm ->
            val mods = vm.popQuote()
            val turns = vm.popNum()
            val radius1 = vm.popNum()
            val radius0 = vm.popNum()
            vm.placePolarArray("spiral", mods) { ordinal, count ->
                val frac = if (count <= 1) 0.0 else ordinal.toDouble() / (count - 1).toDouble()
                (frac * turns * PI * 2.0) to (radius0 + (radius1 - radius0) * frac)
            }
        }
        word("phyllotaxis") { vm -> vm.phyllotaxis() }
        word("sunflower") { vm -> vm.phyllotaxis() }
        word("grid") { vm ->
            val cell = vm.popQuote()
            val rows = vm.popNum().toInt().coerceAtLeast(0)
            val cols = vm.popNum().toInt().coerceAtLeast(0)
            val total = cols * rows
            val created = vm.builder().captureNewNodes {
                var index = 0
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        vm.withGrid(index, total, c, r, cols, rows) { vm.evalBlock(cell.tokens) }
                        index++
                    }
                }
            }
            vm.push(VSelection(created))
        }
        mutator("split") { vm ->
            val mods = vm.popQuote()
            val atTime = vm.popNum()
            val count = vm.popNum().toInt().coerceAtLeast(0)
            val parent = vm.popTemplateRef("split")
            val b = vm.builder()
            // Seed shards from the still-pristine parent (before it is gated to death),
            // baking the parent's split-point position via build-time track sampling.
            val point = b.positionAt(parent, atTime)
            val shards = (0 until count).map { b.cloneShardAtOrigin(parent) }
            b.withActive(parent) { b.gateVisibility(Double.NEGATIVE_INFINITY, atTime) }
            val dur = b.duration
            shards.forEachIndexed { ordinal, shard ->
                // Author each shard's flight around the origin, in split-relative local time.
                // The mods may create sub-structure — including a nested `split` — so capture
                // every node they produce.
                val spawned = b.captureNewNodes {
                    vm.withSelected(shard) { vm.withLoop(ordinal, count, ordinal) { vm.evalBlock(mods.tokens) } }
                }
                // The shard's frame — its split point and birth time — applies to the shard
                // AND everything its mods produced. This is what makes `split` recursive: a
                // grand-shard's time adds onto its parent's (t1 then +t2) and its position is
                // offset onto this shard's split point.
                (listOf(shard) + spawned).forEach { node ->
                    b.withActive(node) {
                        b.translate(constantTrack(point.x), constantTrack(point.y))
                        b.live(atTime, dur - atTime)
                    }
                }
            }
            vm.push(VSelection(shards))
        }
        mutator("polar") { vm ->
            val radius = vm.popNum()
            val angle = vm.popNum()
            vm.builder().setPolar(angle, radius)
        }
        mutator("stagger") { vm ->
            val dy = vm.popNum()
            val dx = vm.popNum()
            val selection = vm.popSelectionOrActive("stagger")
            vm.builder().stagger(selection.refs, dx, dy)
            vm.push(selection)
        }

        // --- Functions.kt bridge: eased fractions + sampleable distribution curves ---
        // `ease` shapes a scalar/track through the full Penner family (single source
        // of truth: afield.stdlib.Functions). Curves (VFn) are graph-backed, so the
        // Functions rewrite/evolve tooling can optimise an array's distribution.
        word("ease") { vm ->
            val name = vm.popString().trim().lowercase().replace('_', '-')
            val ease = PENNER_EASINGS[name]
                ?: throw RectLangException(
                    "ease: unknown easing '$name' (try quad-out, cubic-in-out, sine-in-out, expo-out, " +
                        "circ-in-out, back-out, elastic-in-out, bounce-out, smoothstep, smootherstep)"
                )
            // A constant (e.g. the array fraction f) eases straight to a number; a
            // keyed track eases its interpolation like the ease-in/ease-out words.
            when (val v = vm.pop()) {
                is VNum -> vm.push(VNum(ease(v.x)))
                // Name the track with the curve that was actually asked for, not
                // with the word: "ease" alone would not say which curve it is.
                else -> vm.push(VTrack(easedTrack("ease", vm.scalarTrackFrom(v, "ease"), name)))
            }
        }
        // Distribution curves over [0,1] — sample at the array fraction f.
        word("gaussian") { vm -> vm.push(VFn(Functions.gaussian(mean = 0.5, sigma = 0.16, amplitude = 1.0))) }
        word("noise") { vm ->
            val seed = vm.popNum().toLong()
            vm.push(VFn(Functions.valueNoise(seed = seed, frequency = 8.0)))
        }
        word("fbm") { vm ->
            val seed = vm.popNum().toLong()
            vm.push(VFn(Functions.fbm(seed = seed, frequency = 4.0)))
        }
        word("sample") { vm ->
            val x = vm.popNum()
            val fn = vm.popFn("sample")
            vm.push(VNum(fn.eval(x)))
        }
        // Curve → curve transforms borrowed from the Functions pattern ops.
        word("reflect") { vm -> vm.push(VFn(Functions.mirror(0.5).invoke(vm.popFn("reflect")))) }
        word("tile") { vm ->
            val k = vm.popNum().coerceAtLeast(1.0)
            val fn = vm.popFn("tile")
            vm.push(VFn(with(Functions) { fn / repeatEvery(1.0 / k) }))
        }

        // --- Data visualization: perceptually-uniform colormaps ---------------------
        // `colormap`/`cmap` look up a named ramp (viridis, magma, inferno, plasma,
        // turbo, coolwarm, grayscale) at t in [0,1] and push a Color — usable anywhere
        // a colour is (solid/linear/fill/rgb slot). Reuses Color4/PaintSpec, no parallel
        // colour system. Single source of truth for the tables: [Colormaps].
        word("colormap") { vm ->
            val t = vm.popNum()
            val name = vm.popString()
            vm.push(VColor(Colormaps.sample(name, t)))
        }
        word("cmap") { vm ->
            val t = vm.popNum()
            val name = vm.popString()
            vm.push(VColor(Colormaps.sample(name, t)))
        }

        // --- Data ingestion + normalization -----------------------------------------
        // A `VData` is a plain numeric list. `data` runs a quote and collects every
        // Number it leaves (so `'( 3 1 4 1 5 ) data` or a computed `'( 8 times [ i i * ] )
        // data` both work); `linspace` generates evenly spaced values.
        word("data") { vm ->
            val quote = vm.popQuote()
            val mark = vm.stackDepth()
            vm.evalBlock(quote.tokens)
            vm.push(VData(vm.drainNumbersAbove(mark, "data")))
        }
        word("linspace") { vm ->
            val count = vm.popNum().toInt().coerceAtLeast(0)
            val hi = vm.popNum()
            val lo = vm.popNum()
            val values = when {
                count <= 0 -> emptyList()
                count == 1 -> listOf(lo)
                else -> (0 until count).map { lo + (hi - lo) * it / (count - 1).toDouble() }
            }
            vm.push(VData(values))
        }
        word("data-len") { vm -> vm.push(VNum(vm.popData("data-len").size.toDouble())) }
        word("data-at") { vm ->
            val index = vm.popNum().toInt()
            val d = vm.popData("data-at")
            if (d.isEmpty()) throw RectLangException("data-at: empty data")
            vm.push(VNum(d[index.coerceIn(0, d.size - 1)]))
        }
        word("extent") { vm ->
            val d = vm.popData("extent")
            if (d.isEmpty()) throw RectLangException("extent: empty data")
            vm.push(VNum(d.minOrNull()!!))
            vm.push(VNum(d.maxOrNull()!!))
        }
        word("dmin") { vm ->
            val d = vm.popData("dmin")
            vm.push(VNum(d.minOrNull() ?: throw RectLangException("dmin: empty data")))
        }
        word("dmax") { vm ->
            val d = vm.popData("dmax")
            vm.push(VNum(d.maxOrNull() ?: throw RectLangException("dmax: empty data")))
        }
        word("normalize") { vm -> vm.push(VData(normalizeValues(vm.popData("normalize")))) }
        word("data-map") { vm ->
            val quote = vm.popQuote()
            val d = vm.popData("data-map")
            val out = d.mapIndexed { index, value ->
                var result = 0.0
                vm.withLoop(index, d.size, index) {
                    vm.push(VNum(value))
                    vm.evalBlock(quote.tokens)
                    result = vm.popNum()
                }
                result
            }
            vm.push(VData(out))
        }

        // --- Chart idioms: produce plain rects + colormap colours -------------------
        // These are pure RectSL builders — no renderer changes. Values are normalized by
        // their own extent for both size and colour; the returned Selection can be
        // animated/transformed like any other (alpha, stagger-time, group, …).
        word("bars") { vm ->
            val name = vm.popString()
            val data = vm.popData("bars")
            val stops = Colormaps.stops(name)
            val wall = vm.builder().wall
            val n = data.size
            val nv = normalizeValues(data)
            val slot = if (n > 0) wall.width / n else wall.width
            val refs = ArrayList<NodeRef>(n)
            data.indices.forEach { i ->
                val rect = vm.builder().createRect("bar-$i")
                val height = (nv[i] * wall.height * 0.9).coerceAtLeast(wall.height * 0.002)
                vm.builder().setX(constantTrack(-wall.width / 2.0 + slot * (i + 0.5)))
                vm.builder().setY(constantTrack(-wall.height / 2.0 + height / 2.0))
                vm.builder().setW(constantTrack(slot * 0.8))
                vm.builder().setH(constantTrack(height))
                vm.builder().setFill(constantPaintTrack(PaintSpec.Solid(Colormaps.sampleStops(stops, nv[i]))))
                refs += NodeRef.Rect(rect.id)
                vm.builder().endScope()
            }
            vm.push(VSelection(refs))
        }
        word("heat") { vm -> vm.heatmap() }
        word("heatmap") { vm -> vm.heatmap() }
        word("scatter") { vm ->
            val name = vm.popString()
            val ydata = vm.popData("scatter")
            val xdata = vm.popData("scatter")
            val stops = Colormaps.stops(name)
            val wall = vm.builder().wall
            val n = min(xdata.size, ydata.size)
            val nx = normalizeValues(xdata.take(n))
            val ny = normalizeValues(ydata.take(n))
            val dot = min(wall.width, wall.height) * 0.035
            val refs = ArrayList<NodeRef>(n)
            for (i in 0 until n) {
                val rect = vm.builder().createRect("point-$i")
                vm.builder().setX(constantTrack((nx[i] - 0.5) * wall.width * 0.92))
                vm.builder().setY(constantTrack((ny[i] - 0.5) * wall.height * 0.92))
                vm.builder().setW(constantTrack(dot))
                vm.builder().setH(constantTrack(dot))
                vm.builder().setFill(constantPaintTrack(PaintSpec.Solid(Colormaps.sampleStops(stops, ny[i]))))
                refs += NodeRef.Rect(rect.id)
                vm.builder().endScope()
            }
            vm.push(VSelection(refs))
        }

        // --- Plotting layer: shared domain + axes + legend + domain-aware series -----
        // Everything below is a PURE RectSL builder (rects only, no renderer changes),
        // reusing createRect/PaintSpec/Colormaps exactly like the chart words above. The
        // classic bars/heat/scatter words are deliberately left untouched, so existing
        // programs — which never set a domain or margins — are byte-identical. A shared
        // data→world frame lets several series overlay in one coordinate system: the
        // first series (or an explicit `domain`) fixes the domain, later series + `axes`
        // + `colorbar` reuse it. There is no text primitive in RectSL, so numeric tick
        // labels are drawn with a 3x5 dot-matrix numeral font built from rects.
        mutator("margins") { vm ->
            val t = vm.popNum(); val b = vm.popNum(); val r = vm.popNum(); val l = vm.popNum()
            vm.setPlotMargins(l, r, b, t)
        }
        mutator("frame") { vm -> vm.applyDefaultFrame() }
        mutator("domain") { vm ->
            val y1 = vm.popNum(); val y0 = vm.popNum(); val x1 = vm.popNum(); val x0 = vm.popNum()
            vm.setXDomain(x0, x1); vm.setYDomain(y0, y1)
        }
        mutator("xdomain") { vm ->
            val x1 = vm.popNum(); val x0 = vm.popNum(); vm.setXDomain(x0, x1)
        }
        mutator("ydomain") { vm ->
            val y1 = vm.popNum(); val y0 = vm.popNum(); vm.setYDomain(y0, y1)
        }
        word("axes") { vm -> vm.drawAxes() }
        word("line") { vm -> vm.lineSeries() }
        word("plot") { vm -> vm.lineSeries() }
        word("area") { vm -> vm.areaSeries() }
        word("points") { vm -> vm.pointsSeries() }
        word("colorbar") { vm -> vm.drawColorbar() }
        word("legend") { vm -> vm.drawLegend() }
    }

    /**
     * Shared engine for the polar array generators (`arc`, `spiral`, `phyllotaxis`):
     * pop `count` and the template, instantiate the copies, and for each place it at a
     * per-ordinal `(angle, radius)` before running the per-copy `mods` with array context.
     * `radial` predates this and keeps its own inline copy for byte-stability.
     */
    private fun placePolarArray(word: String, mods: VQuote, placement: (ordinal: Int, count: Int) -> Pair<Double, Double>) {
        val count = popNum().toInt().coerceAtLeast(0)
        if (count == 0) {
            push(VSelection(emptyList()))
            return
        }
        val template = popTemplateRef(word)
        val refs = builder().instantiateArray(template, count)
        refs.forEachIndexed { ordinal, ref ->
            withSelected(ref) {
                withLoop(ordinal, count, ordinal) {
                    val (angle, radius) = placement(ordinal, count)
                    builder().setPolar(angle, radius)
                    evalBlock(mods.tokens)
                }
            }
        }
        push(VSelection(refs))
    }

    /** `[template] count scale quote` → golden-angle sunflower: angle = i·137.5°, radius = scale·√i. */
    private fun phyllotaxis() {
        val mods = popQuote()
        val scale = popNum()
        placePolarArray("phyllotaxis", mods) { ordinal, _ ->
            (ordinal * 2.399963229728653) to (scale * sqrt(ordinal.toDouble()))
        }
    }

    /** Grid heatmap: `data cols rows name heat` → row-major cells (row 0 at top). */
    private fun heatmap() {
        val name = popString()
        val rows = popNum().toInt().coerceAtLeast(1)
        val cols = popNum().toInt().coerceAtLeast(1)
        val data = popData("heat")
        val stops = Colormaps.stops(name)
        val wall = builder().wall
        val nv = normalizeValues(data)
        val cellW = wall.width / cols
        val cellH = wall.height / rows
        val refs = ArrayList<NodeRef>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val index = r * cols + c
                val value = if (nv.isEmpty()) 0.0 else nv[index % nv.size]
                val rect = builder().createRect("cell-$c-$r")
                builder().setX(constantTrack(-wall.width / 2.0 + cellW * (c + 0.5)))
                builder().setY(constantTrack(wall.height / 2.0 - cellH * (r + 0.5)))
                builder().setW(constantTrack(cellW * 0.98))
                builder().setH(constantTrack(cellH * 0.98))
                builder().setFill(constantPaintTrack(PaintSpec.Solid(Colormaps.sampleStops(stops, value))))
                refs += NodeRef.Rect(rect.id)
                builder().endScope()
            }
        }
        push(VSelection(refs))
    }

    // ── Plotting layer helpers ───────────────────────────────────────────────────
    // A shared data→world frame + axes/ticks/labels/legends, all built from rects.

    private var plotRectSeq = 0

    /** World-space rectangle of the plotting area; equals the whole wall when no
     *  margins are set (wall is centred at origin, +y up). */
    private fun plotFrame(): PlotFrame {
        val wall = builder().wall
        return PlotFrame(
            left = -wall.width / 2.0 + plotMarginL * wall.width,
            right = wall.width / 2.0 - plotMarginR * wall.width,
            bottom = -wall.height / 2.0 + plotMarginB * wall.height,
            top = wall.height / 2.0 - plotMarginT * wall.height
        )
    }

    private fun safeSpan(lo: Double, hi: Double): Pair<Double, Double> {
        if (!lo.isFinite() || !hi.isFinite()) return 0.0 to 1.0
        if (hi - lo <= 1e-9) { val c = (lo + hi) / 2.0; return (c - 0.5) to (c + 0.5) }
        return lo to hi
    }

    private fun setXDomain(x0: Double, x1: Double) { plotXDomain = safeSpan(min(x0, x1), max(x0, x1)) }
    private fun setYDomain(y0: Double, y1: Double) { plotYDomain = safeSpan(min(y0, y1), max(y0, y1)) }

    /** Resolve the x domain, lazily adopting [fallback] (a series' own extent) so the
     *  first plotted series fixes the shared domain and later series + `axes` reuse it. */
    private fun resolveXDomain(fallback: Pair<Double, Double>): Pair<Double, Double> =
        plotXDomain ?: safeSpan(fallback.first, fallback.second).also { plotXDomain = it }

    private fun resolveYDomain(fallback: Pair<Double, Double>): Pair<Double, Double> =
        plotYDomain ?: safeSpan(fallback.first, fallback.second).also { plotYDomain = it }

    private fun setPlotMargins(l: Double, r: Double, b: Double, t: Double) {
        plotMarginL = l.coerceIn(0.0, 0.45); plotMarginR = r.coerceIn(0.0, 0.45)
        plotMarginB = b.coerceIn(0.0, 0.45); plotMarginT = t.coerceIn(0.0, 0.45)
    }

    /** `frame` — sensible default margins (room for axis labels left/bottom, a colorbar
     *  on the right), applied only when none are set so an explicit `margins` wins. */
    private fun applyDefaultFrame() {
        if (plotMarginL == 0.0 && plotMarginR == 0.0 && plotMarginB == 0.0 && plotMarginT == 0.0) {
            plotMarginL = 0.12; plotMarginR = 0.14; plotMarginB = 0.12; plotMarginT = 0.06
        }
    }

    private fun minMax(v: List<Double>): Pair<Double, Double> =
        if (v.isEmpty()) 0.0 to 1.0 else (v.min() to v.max())

    /** One filled rect in the plot; records its ref and closes the builder scope. */
    private fun plotRect(
        idPrefix: String, cx: Double, cy: Double, w: Double, h: Double,
        color: Color4, refs: MutableList<NodeRef>, rotation: Double = 0.0
    ) {
        if (w <= 0.0 || h <= 0.0 || !cx.isFinite() || !cy.isFinite()) return
        val rect = builder().createRect("$idPrefix-${plotRectSeq++}")
        builder().setX(constantTrack(cx))
        builder().setY(constantTrack(cy))
        builder().setW(constantTrack(w))
        builder().setH(constantTrack(h))
        if (rotation != 0.0) builder().setRotation(constantTrack(rotation))
        builder().setFill(constantPaintTrack(PaintSpec.Solid(color)))
        refs.add(NodeRef.Rect(rect.id))
        builder().endScope()
    }

    // Heckbert's "nice numbers" tick algorithm — a compact, standard choice of round
    // tick values covering [lo, hi] with about `target` ticks.
    private fun niceNum(x: Double, round: Boolean): Double {
        if (x <= 0.0) return 0.0
        val exp = floor(log10(x))
        val f = x / 10.0.pow(exp)
        val nf = if (round) {
            when { f < 1.5 -> 1.0; f < 3.0 -> 2.0; f < 7.0 -> 5.0; else -> 10.0 }
        } else {
            when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 5.0 -> 5.0; else -> 10.0 }
        }
        return nf * 10.0.pow(exp)
    }

    private fun niceTicks(lo: Double, hi: Double, target: Int = 5): List<Double> {
        if (!lo.isFinite() || !hi.isFinite() || hi <= lo) return listOf(lo)
        val step = niceNum(niceNum(hi - lo, false) / (target - 1).coerceAtLeast(1), true)
        if (step <= 0.0 || !step.isFinite()) return listOf(lo, hi)
        val start = ceil(lo / step - 1e-9) * step
        val out = ArrayList<Double>()
        var v = start
        var guard = 0
        while (v <= hi + step * 1e-6 && guard < 10_000) {
            out.add(round(v / step) * step)
            v += step
            guard++
        }
        return if (out.isEmpty()) listOf(lo, hi) else out
    }

    /** Format a tick value with just enough decimals for its step, trailing zeros trimmed. */
    private fun formatTick(v: Double, step: Double): String {
        val absStep = abs(step)
        val decimals = if (absStep <= 0.0 || !absStep.isFinite()) 0
        else max(0, -floor(log10(absStep)).toInt()).coerceAtMost(4)
        var s = String.format("%.${decimals}f", v)
        if (decimals > 0 && '.' in s) s = s.trimEnd('0').trimEnd('.')
        if (s == "-0") s = "0"
        return s
    }

    private fun numeralWidth(text: String, cell: Double): Double =
        if (text.isEmpty()) 0.0 else text.length * 4.0 * cell - cell

    /** Draw [text] (digits, `-`, `.`) centred at ([cx],[cy]) as a 3×5 dot-matrix of
     *  rects — RectSL has no text primitive, so numerals are little rect grids. */
    private fun drawNumeral(text: String, cx: Double, cy: Double, cell: Double, color: Color4, refs: MutableList<NodeRef>) {
        if (cell <= 0.0 || text.isEmpty()) return
        val advance = 4.0 * cell
        val totalW = numeralWidth(text, cell)
        val dot = cell * 0.9
        var glyphCx = cx - totalW / 2.0 + 1.5 * cell
        for (ch in text) {
            val pattern = PLOT_NUMERAL_GLYPHS[ch] ?: PLOT_NUMERAL_GLYPHS[' ']!!
            for (row in 0 until 5) {
                for (col in 0 until 3) {
                    if (pattern[row][col] == '#') {
                        plotRect("glyph", glyphCx + (col - 1) * cell, cy + (2 - row) * cell, dot, dot, color, refs)
                    }
                }
            }
            glyphCx += advance
        }
    }

    /** `axes` — x/y axis rules + nice-number ticks + numeric labels (rect numerals in
     *  the margins) + faint gridlines. Reads the shared domain (defaults to 0..1) and
     *  the frame margins. Draw it BEFORE the series so the series paint on top. */
    private fun drawAxes() {
        val wall = builder().wall
        val frame = plotFrame()
        val (x0, x1) = plotXDomain ?: (0.0 to 1.0)
        val (y0, y1) = plotYDomain ?: (0.0 to 1.0)
        val refs = ArrayList<NodeRef>()
        val axisColor = Color4(0.86, 0.87, 0.92)
        val tickColor = Color4(0.66, 0.67, 0.72)
        val gridColor = Color4(0.80, 0.82, 0.90, 0.10)
        val labelColor = Color4(0.80, 0.81, 0.86)
        val minDim = min(wall.width, wall.height)
        val rule = minDim * 0.005
        val tickLen = minDim * 0.018
        fun mx(v: Double) = frame.left + (v - x0) / (x1 - x0) * frame.width
        fun my(v: Double) = frame.bottom + (v - y0) / (y1 - y0) * frame.height
        val xt = niceTicks(x0, x1)
        val yt = niceTicks(y0, y1)
        val xStep = if (xt.size >= 2) xt[1] - xt[0] else (x1 - x0)
        val yStep = if (yt.size >= 2) yt[1] - yt[0] else (y1 - y0)
        val labelCell = run {
            val avail = listOf(plotMarginB * wall.height, plotMarginL * wall.width).filter { it > 1e-6 }
            if (avail.isEmpty()) 0.0 else (avail.min() * 0.12).coerceAtMost(minDim * 0.028)
        }
        // faint gridlines first, so series drawn afterwards sit on top of them.
        xt.forEach { t -> plotRect("grid-v", mx(t), (frame.bottom + frame.top) / 2.0, rule * 0.6, frame.height, gridColor, refs) }
        yt.forEach { t -> plotRect("grid-h", (frame.left + frame.right) / 2.0, my(t), frame.width, rule * 0.6, gridColor, refs) }
        // axis rules
        plotRect("axis-x", (frame.left + frame.right) / 2.0, frame.bottom, frame.width + rule, rule, axisColor, refs)
        plotRect("axis-y", frame.left, (frame.bottom + frame.top) / 2.0, rule, frame.height + rule, axisColor, refs)
        // x ticks + labels below the axis
        xt.forEach { t ->
            val gx = mx(t)
            plotRect("tick-x", gx, frame.bottom - tickLen / 2.0, rule, tickLen, tickColor, refs)
            if (labelCell > 1e-4) {
                drawNumeral(formatTick(t, xStep), gx, frame.bottom - tickLen - 0.6 * labelCell - 2.5 * labelCell, labelCell, labelColor, refs)
            }
        }
        // y ticks + right-aligned labels left of the axis
        yt.forEach { t ->
            val gy = my(t)
            plotRect("tick-y", frame.left - tickLen / 2.0, gy, tickLen, rule, tickColor, refs)
            if (labelCell > 1e-4) {
                val txt = formatTick(t, yStep)
                val cx = frame.left - tickLen - 0.6 * labelCell - numeralWidth(txt, labelCell) / 2.0
                drawNumeral(txt, cx, gy, labelCell, labelColor, refs)
            }
        }
        push(VSelection(refs))
    }

    /** `line`/`plot` — `xdata ydata name -- selection`. Polyline as rotated thin rects
     *  through the shared domain, coloured along colormap `name` by point order. */
    private fun lineSeries() {
        val name = popString()
        val ydata = popData("line"); val xdata = popData("line")
        val stops = Colormaps.stops(name)
        val n = min(xdata.size, ydata.size)
        if (n < 2) throw RectLangException("line: need at least 2 (x, y) points")
        val xv = xdata.take(n); val yv = ydata.take(n)
        val (x0, x1) = resolveXDomain(minMax(xv))
        val (y0, y1) = resolveYDomain(minMax(yv))
        val frame = plotFrame()
        val thickness = min(frame.width, frame.height) * 0.012
        val refs = ArrayList<NodeRef>()
        fun mx(v: Double) = frame.left + (v - x0) / (x1 - x0) * frame.width
        fun my(v: Double) = frame.bottom + (v - y0) / (y1 - y0) * frame.height
        for (i in 0 until n - 1) {
            val ax = mx(xv[i]); val ay = my(yv[i]); val bx = mx(xv[i + 1]); val by = my(yv[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len = hypot(dx, dy)
            val ang = atan2(dy, dx)
            val t = if (n <= 2) 0.5 else i.toDouble() / (n - 2)
            plotRect("line", (ax + bx) / 2.0, (ay + by) / 2.0, len + thickness, thickness, Colormaps.sampleStops(stops, t), refs, ang)
        }
        push(VSelection(refs))
    }

    /** `area` — `xdata ydata name -- selection`. Column-filled area from the y=0 baseline
     *  to each sample, honouring the shared domain; colour follows normalized y. */
    private fun areaSeries() {
        val name = popString()
        val ydata = popData("area"); val xdata = popData("area")
        val stops = Colormaps.stops(name)
        val n = min(xdata.size, ydata.size)
        if (n < 1) throw RectLangException("area: empty data")
        val xv = xdata.take(n); val yv = ydata.take(n)
        val (x0, x1) = resolveXDomain(minMax(xv))
        val ext = minMax(yv)
        val (y0, y1) = resolveYDomain(min(0.0, ext.first) to max(0.0, ext.second))
        val frame = plotFrame()
        fun mx(v: Double) = frame.left + (v - x0) / (x1 - x0) * frame.width
        fun my(v: Double) = frame.bottom + (v - y0) / (y1 - y0) * frame.height
        val base = my(0.0.coerceIn(min(y0, y1), max(y0, y1)))
        val slot = frame.width / n
        val refs = ArrayList<NodeRef>()
        for (i in 0 until n) {
            val top = my(yv[i])
            val h = abs(top - base)
            val t = if (y1 == y0) 0.5 else ((yv[i] - y0) / (y1 - y0)).coerceIn(0.0, 1.0)
            plotRect("area", mx(xv[i]), (top + base) / 2.0, slot * 0.96, h, Colormaps.sampleStops(stops, t), refs)
        }
        push(VSelection(refs))
    }

    /** `points` — `xdata ydata name -- selection`. Domain-aware scatter (a shared-domain
     *  companion to the classic `scatter`); colour follows normalized y. */
    private fun pointsSeries() {
        val name = popString()
        val ydata = popData("points"); val xdata = popData("points")
        val stops = Colormaps.stops(name)
        val n = min(xdata.size, ydata.size)
        val xv = xdata.take(n); val yv = ydata.take(n)
        val (x0, x1) = resolveXDomain(minMax(xv))
        val (y0, y1) = resolveYDomain(minMax(yv))
        val frame = plotFrame()
        val dot = min(frame.width, frame.height) * 0.028
        val refs = ArrayList<NodeRef>()
        fun mx(v: Double) = frame.left + (v - x0) / (x1 - x0) * frame.width
        fun my(v: Double) = frame.bottom + (v - y0) / (y1 - y0) * frame.height
        for (i in 0 until n) {
            val t = if (y1 == y0) 0.5 else ((yv[i] - y0) / (y1 - y0)).coerceIn(0.0, 1.0)
            plotRect("point", mx(xv[i]), my(yv[i]), dot, dot, Colormaps.sampleStops(stops, t), refs)
        }
        push(VSelection(refs))
    }

    /** `colorbar` — `name -- selection`. A continuous colour ramp for `name` in the right
     *  margin, with min/mid/max numerals read from the y domain (drawn when there is room). */
    private fun drawColorbar() {
        val name = popString()
        val stops = Colormaps.stops(name)
        val wall = builder().wall
        val frame = plotFrame()
        val minDim = min(wall.width, wall.height)
        val rightMargin = plotMarginR * wall.width
        val barW = if (rightMargin > 1e-6) rightMargin * 0.30 else minDim * 0.03
        val barX = if (rightMargin > 1e-6) frame.right + rightMargin * 0.32 else frame.right - barW
        val steps = 40
        val segH = frame.height / steps
        val refs = ArrayList<NodeRef>()
        for (i in 0 until steps) {
            val t = i.toDouble() / (steps - 1)
            plotRect("cbar", barX, frame.bottom + segH * (i + 0.5), barW, segH * 1.04, Colormaps.sampleStops(stops, t), refs)
        }
        val (y0, y1) = plotYDomain ?: (0.0 to 1.0)
        val yStep = niceTicks(y0, y1).let { if (it.size >= 2) it[1] - it[0] else (y1 - y0) }
        val labels = listOf(0.0 to y0, 0.5 to (y0 + y1) / 2.0, 1.0 to y1)
        // Auto-size so the widest label fits between the bar and the wall's right edge
        // (a 3-char "0.5" is much wider than "0"/"1"), rather than clipping it off-wall.
        val room = wall.width / 2.0 - (barX + barW / 2.0)
        val widestUnits = labels.maxOf { numeralWidth(formatTick(it.second, yStep), 1.0) }
        val labelCell = (room / (1.4 + widestUnits)).coerceIn(0.0, minDim * 0.024)
        if (labelCell > 1e-4) {
            val labelColor = Color4(0.80, 0.81, 0.86)
            labels.forEach { (frac, v) ->
                val txt = formatTick(v, yStep)
                val cx = barX + barW / 2.0 + labelCell + numeralWidth(txt, labelCell) / 2.0
                drawNumeral(txt, cx, frame.bottom + frac * frame.height, labelCell, labelColor, refs)
            }
        }
        push(VSelection(refs))
    }

    /** `legend` — `count name -- selection`. A key of `count` discrete colour swatches
     *  sampling colormap `name`, stacked in the top-right of the plot area. */
    private fun drawLegend() {
        val name = popString()
        val count = popNum().toInt().coerceAtLeast(1)
        val stops = Colormaps.stops(name)
        val wall = builder().wall
        val frame = plotFrame()
        val minDim = min(wall.width, wall.height)
        val sw = minDim * 0.05
        val gap = sw * 0.35
        val x = frame.right - sw * 0.7
        val topY = frame.top - sw * 0.7
        val refs = ArrayList<NodeRef>()
        for (i in 0 until count) {
            val t = if (count == 1) 0.5 else i.toDouble() / (count - 1)
            plotRect("legend", x, topY - i * (sw + gap), sw, sw, Colormaps.sampleStops(stops, t), refs)
        }
        push(VSelection(refs))
    }

    private fun word(
        spelling: String,
        effect: RectWordEffect = RectWordEffect.Ordinary,
        apply: (RectLangVm) -> Unit
    ) {
        words[spelling] = Word(RectWordMetadata(spelling, effect), apply)
    }

    private fun mutator(name: String, apply: (RectLangVm) -> Unit) {
        require(!name.startsWith("->")) { "Mutator names are registered without their -> prefix: $name" }
        word("->$name", RectWordEffect.Mutator, apply)
    }

    enum class TrackMode { Tcb, Linear, Hold }

    fun quoteToScalarTrack(quote: VQuote, mode: TrackMode): Track<Double> {
        // `time=value` reads more clearly than a bare space between the two, so the
        // `=` separator is accepted (and ignored) between the numbers of each pair.
        val numbers = quote.tokens
            .filterNot { it is Token.Word && it.name == "=" }
            .map {
                it as? Token.Number ?: throw RectLangException("Track quote expected only numbers, found $it")
            }.map { it.value }
        if (numbers.size < 2 || numbers.size % 2 != 0) {
            throw RectLangException("Track quote expected time/value pairs")
        }
        val keys = numbers.chunked(2).map { TcbKey(it[0], it[1]) }
        return when (mode) {
            TrackMode.Tcb -> TcbTrack(keys)
            TrackMode.Linear -> LinearScalarTrack(keys)
            TrackMode.Hold -> HoldScalarTrack(keys)
        }
    }
}

/**
 * Evaluate a RectSL program and return its residual stack — for tooling and tests
 * that need the values a program leaves behind (e.g. a [VFn] distribution curve
 * built through the Functions bridge, to inspect or evolve its graph).
 */
fun evalRectSLStack(source: String): List<Value> {
    val vm = RectLangVm()
    vm.eval(RectLangTokenizer(source).tokenize())
    return vm.stackValues()
}

sealed interface NodeRef {
    val id: String
    data class Rect(override val id: String) : NodeRef
    data class Group(override val id: String) : NodeRef
}

/** World-space rectangle of a RectSL plotting area (wall centred at origin, +y up). */
internal data class PlotFrame(val left: Double, val right: Double, val bottom: Double, val top: Double) {
    val width: Double get() = right - left
    val height: Double get() = top - bottom
}

/**
 * A 3×5 dot-matrix font for the numerals RectSL's `axes`/`colorbar` tick labels need.
 * RectSL has no text primitive (everything is a rect), so a label is drawn as a little
 * grid of rects — this is a pure builder, not a renderer feature. Rows run top→bottom.
 */
internal val PLOT_NUMERAL_GLYPHS: Map<Char, List<String>> = mapOf(
    '0' to listOf("###", "#.#", "#.#", "#.#", "###"),
    '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
    '2' to listOf("###", "..#", "###", "#..", "###"),
    '3' to listOf("###", "..#", "###", "..#", "###"),
    '4' to listOf("#.#", "#.#", "###", "..#", "..#"),
    '5' to listOf("###", "#..", "###", "..#", "###"),
    '6' to listOf("###", "#..", "###", "#.#", "###"),
    '7' to listOf("###", "..#", "..#", "..#", "..#"),
    '8' to listOf("###", "#.#", "###", "#.#", "###"),
    '9' to listOf("###", "#.#", "###", "..#", "###"),
    '-' to listOf("...", "...", "###", "...", "..."),
    '.' to listOf("...", "...", "...", "...", ".#."),
    ' ' to listOf("...", "...", "...", "...", "...")
)

private fun NodeRef.toValue(): Value = when (this) {
    is NodeRef.Rect -> VRect(id)
    is NodeRef.Group -> VGroup(id)
}

private sealed class NodeDraft {
    abstract var id: String
    abstract val tags: MutableSet<String>
}

private class RectDraft(
    override var id: String
) : NodeDraft() {
    var x: Track<Double> = constantTrack(0.0)
    var y: Track<Double> = constantTrack(0.0)
    var w: Track<Double> = constantTrack(1.0)
    var h: Track<Double> = constantTrack(1.0)
    var rotation: Track<Double> = constantTrack(0.0)
    var scaleX: Track<Double> = constantTrack(1.0)
    var scaleY: Track<Double> = constantTrack(1.0)
    var alpha: Track<Double> = constantTrack(1.0)
    var z: Track<Double> = constantTrack(0.0)
    var fill: Track<PaintSpec> = constantPaintTrack(PaintSpec.Solid(Color4.WHITE))
    var material: MaterialBinding = MaterialBinding(0)
    override val tags: MutableSet<String> = linkedSetOf()

    fun freeze(): RectNode = RectNode(id, x, y, w, h, rotation, scaleX, scaleY, alpha, z, fill, material, tags.toSet())
    fun cloneAs(newId: String): RectDraft =
        RectDraft(newId).also {
            it.x = x
            it.y = y
            it.w = w
            it.h = h
            it.rotation = rotation
            it.scaleX = scaleX
            it.scaleY = scaleY
            it.alpha = alpha
            it.z = z
            it.fill = fill
            it.material = material
            it.tags += tags
        }
}

private class GroupDraft(
    override var id: String
) : NodeDraft() {
    val children: MutableList<String> = ArrayList()
    var x: Track<Double> = constantTrack(0.0)
    var y: Track<Double> = constantTrack(0.0)
    var rotation: Track<Double> = constantTrack(0.0)
    var scaleX: Track<Double> = constantTrack(1.0)
    var scaleY: Track<Double> = constantTrack(1.0)
    var alpha: Track<Double> = constantTrack(1.0)
    override val tags: MutableSet<String> = linkedSetOf()

    fun freeze(): GroupNode = GroupNode(id, children.toList(), x, y, rotation, scaleX, scaleY, alpha, tags.toSet())
    fun cloneShallowAs(newId: String): GroupDraft =
        GroupDraft(newId).also {
            it.children += children
            it.x = x
            it.y = y
            it.rotation = rotation
            it.scaleX = scaleX
            it.scaleY = scaleY
            it.alpha = alpha
            it.tags += tags
        }
}

class SceneBuilder {
    var wall: WallSpec = WallSpec(30.0, 4.0, UnitKind.Meters)
    var duration: Double = 10.0
    var fps: Double = 60.0
    var fit: FitPolicy = FitPolicy.Contain
    var background: Color4 = Color4.BLACK

    private val nodes = linkedMapOf<String, NodeDraft>()
    private val active = ArrayDeque<NodeRef>()
    private val groupStack = ArrayDeque<String>()
    private val materials = linkedMapOf(
        "plain" to MutableMaterialSpec("plain", 0, null),
        "soft-edge" to MutableMaterialSpec("soft-edge", 1, null),
        "diagnostic" to MutableMaterialSpec("diagnostic", 2, null)
    )
    private val layers = linkedMapOf<String, Double>()
    private var nextNodeId = 1
    private var nextMaterialId = 3
    private var nextLayer = 0.0
    private var backLayer = -1.0

    fun build(): SceneProgram {
        val table = MaterialTable(
            modules = materials.values.mapNotNull { it.moduleSpec() },
            params = FloatArray(0)
        )
        return SceneProgram(
            wall = wall,
            duration = duration,
            fps = fps,
            nodes = nodes.values.map {
                when (it) {
                    is RectDraft -> it.freeze()
                    is GroupDraft -> it.freeze()
                }
            },
            fit = fit,
            background = background,
            materialTable = table
        )
    }

    fun hasMaterial(name: String): Boolean = name in materials

    fun createRect(id: String?): VRect {
        val rid = uniqueId(id ?: "rect-${nextNodeId++}")
        val draft = RectDraft(rid)
        nodes[rid] = draft
        groupStack.lastOrNull()?.let { parent -> group(parent).children += rid }
        active.addLast(NodeRef.Rect(rid))
        return VRect(rid)
    }

    fun createGroup(id: String?): VGroup {
        val gid = uniqueId(id ?: "group-${nextNodeId++}")
        val draft = GroupDraft(gid)
        nodes[gid] = draft
        groupStack.lastOrNull()?.let { parent -> group(parent).children += gid }
        groupStack.addLast(gid)
        active.addLast(NodeRef.Group(gid))
        return VGroup(gid)
    }

    fun groupSelected(id: String?, refs: List<NodeRef>): VGroup {
        if (refs.isEmpty()) throw RectLangException("group-selected needs at least one rect or group")
        refs.forEach { requireNode(it.id) }
        val selectedIds = refs.map { it.id }.toSet()
        val gid = uniqueId(id ?: "group-${nextNodeId++}")
        val draft = GroupDraft(gid)
        val parent = commonParentOf(selectedIds)
        val parentInsertAt = parent?.let { parentId ->
            group(parentId).children.indexOfFirst { it in selectedIds }.takeIf { it >= 0 }
        }
        nodes[gid] = draft

        nodes.values.filterIsInstance<GroupDraft>().forEach { group ->
            group.children.removeAll(selectedIds)
        }
        draft.children += refs.map { it.id }

        if (parent != null && parent !in selectedIds) {
            val children = group(parent).children
            val insertAt = parentInsertAt ?: children.size
            children.add(insertAt, gid)
        } else {
            groupStack.lastOrNull()?.takeIf { it !in selectedIds }?.let { group(it).children += gid }
        }

        active.addLast(NodeRef.Group(gid))
        return VGroup(gid)
    }

    fun cloneNode(ref: NodeRef): Value {
        val cloned = cloneNodeRef(ref)
        active.addLast(cloned)
        return cloned.toValue()
    }

    fun cloneNodeDeep(ref: NodeRef): Value {
        val cloned = cloneNodeDeepRef(ref)
        active.addLast(cloned)
        return cloned.toValue()
    }

    fun cloneSelection(refs: List<NodeRef>, deep: Boolean): Value {
        val cloned = refs.map { if (deep) cloneNodeDeepRef(it) else cloneNodeRef(it) }
        cloned.lastOrNull()?.let { active.addLast(it) }
        return VSelection(cloned)
    }

    fun rename(ref: NodeRef, newId: String): Value {
        val oldId = ref.id
        if (oldId == newId) return ref.toValue()
        val node = nodes.remove(oldId) ?: throw RectLangException("Unknown node '$oldId'")
        val fresh = uniqueId(newId)
        node.id = fresh
        nodes[fresh] = node
        nodes.values.filterIsInstance<GroupDraft>().forEach { group ->
            group.children.replaceAll { if (it == oldId) fresh else it }
        }
        replaceActive(oldId, fresh, node)
        for (i in groupStack.indices) {
            if (groupStack[i] == oldId) groupStack[i] = fresh
        }
        return if (node is RectDraft) VRect(fresh) else VGroup(fresh)
    }

    fun select(ref: NodeRef) {
        requireNode(ref.id)
        active.addLast(ref)
    }

    fun endScope() {
        if (active.isNotEmpty()) active.removeLast()
    }

    fun endGroup() {
        val groupId = if (groupStack.isEmpty()) throw RectLangException("No open group to end") else groupStack.removeLast()
        if (active.lastOrNull()?.id == groupId) active.removeLast()
    }

    fun activeRef(): NodeRef? = active.lastOrNull()

    fun withActive(ref: NodeRef, block: () -> Unit) {
        requireNode(ref.id)
        active.addLast(ref)
        try {
            block()
        } finally {
            if (active.lastOrNull() == ref) {
                active.removeLast()
            } else {
                active.remove(ref)
            }
        }
    }

    fun refFor(id: String): NodeRef {
        val node = nodes[id] ?: throw RectLangException("Unknown node '$id'")
        return if (node is RectDraft) NodeRef.Rect(id) else NodeRef.Group(id)
    }

    fun allRefs(): List<NodeRef> =
        nodes.values.map { it.toRef() }

    fun rootRefs(): List<NodeRef> {
        val childIds = nodes.values.filterIsInstance<GroupDraft>().flatMap { it.children }.toSet()
        return nodes.values.filter { it.id !in childIds }.map { it.toRef() }
    }

    fun childrenOf(refs: List<NodeRef>): List<NodeRef> =
        refs.flatMap { ref ->
            when (val node = requireNode(ref.id)) {
                is RectDraft -> emptyList()
                is GroupDraft -> node.children.map { refFor(it) }
            }
        }.distinctBy { it.id }

    fun descendantsOf(refs: List<NodeRef>): List<NodeRef> {
        val out = ArrayList<NodeRef>()
        val seen = linkedSetOf<String>()
        fun visit(ref: NodeRef) {
            when (val node = requireNode(ref.id)) {
                is RectDraft -> Unit
                is GroupDraft -> node.children.forEach { childId ->
                    if (seen.add(childId)) {
                        val child = refFor(childId)
                        out += child
                        visit(child)
                    }
                }
            }
        }
        refs.forEach(::visit)
        return out
    }

    fun tag(tag: String) {
        target().tags += tag
    }

    fun hasTag(tag: String): Boolean = target().tags.contains(tag)

    fun hasTag(ref: NodeRef, tag: String): Boolean =
        requireNode(ref.id).tags.contains(tag)

    fun scalarTrack(name: String): Track<Double> =
        when (val t = target()) {
            is RectDraft -> when (name) {
                "x" -> t.x
                "y" -> t.y
                "w" -> t.w
                "h" -> t.h
                "rot", "rotation" -> t.rotation
                "scale-x" -> t.scaleX
                "scale-y" -> t.scaleY
                "alpha" -> t.alpha
                "z", "layer" -> t.z
                else -> throw RectLangException("Unknown rect scalar track '$name'")
            }
            is GroupDraft -> when (name) {
                "x" -> t.x
                "y" -> t.y
                "rot", "rotation" -> t.rotation
                "scale-x" -> t.scaleX
                "scale-y" -> t.scaleY
                "alpha" -> t.alpha
                "w", "h", "z", "layer" -> throw RectLangException("Groups do not have '$name' tracks")
                else -> throw RectLangException("Unknown group scalar track '$name'")
            }
        }

    fun setX(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.x = track
            is GroupDraft -> t.x = track
        }
    }

    fun setY(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.y = track
            is GroupDraft -> t.y = track
        }
    }

    fun setW(track: Track<Double>) {
        rectTarget().w = track
    }

    fun setH(track: Track<Double>) {
        rectTarget().h = track
    }

    fun setRotation(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.rotation = track
            is GroupDraft -> t.rotation = track
        }
    }

    fun addRotation(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.rotation = t.rotation + track
            is GroupDraft -> t.rotation = t.rotation + track
        }
    }

    /** Set rotation to the heading of the active object's own x/y tracks (+offset). */
    fun faceTravel(offset: Double) {
        when (val t = target()) {
            is RectDraft -> t.rotation = HeadingTrack(t.x, t.y, offset = offset)
            is GroupDraft -> t.rotation = HeadingTrack(t.x, t.y, offset = offset)
        }
    }

    /** Gate the active object's alpha so it is only visible while `lo <= t < hi`. */
    fun gateVisibility(lo: Double, hi: Double) = multiplyAlpha(GateTrack(lo, hi))

    /**
     * The emitter primitive: play the active object's animation from its `birth`
     * (local time 0 becomes `birth`, like `delay-all-time`) and make it visible only
     * during `[birth, birth + life]`. Authoring a trajectory in local time and firing
     * it at `birth` is how one template becomes a staggered stream of bullets.
     */
    fun live(birth: Double, life: Double) {
        mapActiveScalarTracks { TimeWarpTrack(it, TimeWarpKind.SHIFT, birth) }
        multiplyAlpha(GateTrack(birth, birth + life))
    }

    fun setScaleX(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.scaleX = track
            is GroupDraft -> t.scaleX = track
        }
    }

    fun setScaleY(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.scaleY = track
            is GroupDraft -> t.scaleY = track
        }
    }

    fun setAlpha(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.alpha = track
            is GroupDraft -> t.alpha = track
        }
    }

    fun setZ(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.z = track
            is GroupDraft -> descendantsOf(listOf(NodeRef.Group(t.id))).forEach { ref ->
                (requireNode(ref.id) as? RectDraft)?.z = track
            }
        }
    }

    fun setLayer(name: String) {
        setZ(constantTrack(layerValue(name)))
    }

    fun moveFront() {
        nextLayer += 1.0
        setZ(constantTrack(nextLayer))
    }

    fun moveBack() {
        setZ(constantTrack(backLayer))
        backLayer -= 1.0
    }

    fun multiplyAlpha(track: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> t.alpha = t.alpha * track
            is GroupDraft -> t.alpha = t.alpha * track
        }
    }

    fun setFill(track: Track<PaintSpec>) {
        rectTarget().fill = track
    }

    fun setMaterial(name: String) {
        val spec = materials[name] ?: throw RectLangException("Unknown material '$name'")
        val rect = rectTarget()
        rect.material = rect.material.copy(materialId = spec.materialId, name = name)
    }

    fun setShaderData(data: Vec4f) {
        val rect = rectTarget()
        rect.material = rect.material.copy(shaderData = data)
    }

    fun defineStackSLMaterial(name: String, source: String) {
        val id = materials[name]?.materialId ?: nextMaterialId++
        materials[name] = MutableMaterialSpec(name, id, source)
    }

    fun mirrorX(axis: Double) {
        when (val t = target()) {
            is RectDraft -> {
                t.x = reflectAbout(t.x, axis)
                t.rotation = MappedDoubleTrack(t.rotation, TrackOp1.NEG)
            }
            is GroupDraft -> {
                t.x = reflectAbout(t.x, axis)
                t.rotation = MappedDoubleTrack(t.rotation, TrackOp1.NEG)
            }
        }
    }

    fun mirrorY(axis: Double) {
        when (val t = target()) {
            is RectDraft -> {
                t.y = reflectAbout(t.y, axis)
                t.rotation = MappedDoubleTrack(t.rotation, TrackOp1.NEG)
            }
            is GroupDraft -> {
                t.y = reflectAbout(t.y, axis)
                t.rotation = MappedDoubleTrack(t.rotation, TrackOp1.NEG)
            }
        }
    }

    fun translate(dx: Track<Double>, dy: Track<Double>) {
        when (val t = target()) {
            is RectDraft -> {
                t.x = t.x + dx
                t.y = t.y + dy
            }
            is GroupDraft -> {
                t.x = t.x + dx
                t.y = t.y + dy
            }
        }
    }

    fun mapActiveScalarTracks(transform: (Track<Double>) -> Track<Double>) {
        mapScalarTracks(activeRef() ?: throw RectLangException("No active rect or group"), transform)
    }

    fun staggerTime(refs: List<NodeRef>, step: Double) {
        refs.forEachIndexed { index, ref ->
            val delay = step * index
            mapScalarTracks(ref) { TimeWarpTrack(it, TimeWarpKind.SHIFT, delay) }
        }
    }

    /** Element 0 is the template itself; the remaining count-1 are fresh clones (deep for groups). */
    fun instantiateArray(template: NodeRef, count: Int): List<NodeRef> {
        requireNode(template.id)
        val refs = ArrayList<NodeRef>(count)
        refs += template
        repeat(count - 1) {
            refs += when (requireNode(template.id)) {
                is GroupDraft -> cloneNodeDeepRef(template)
                is RectDraft -> cloneNodeRef(template)
            }
        }
        return refs
    }

    /** Run block, then return the nodes it created, in creation order. */
    fun captureNewNodes(block: () -> Unit): List<NodeRef> {
        val before = HashSet(nodes.keys)
        block()
        return nodes.keys.filter { it !in before }.map { refFor(it) }
    }

    /** Increasing positional offset over selection order: element k moves by k*(dx,dy). */
    fun stagger(refs: List<NodeRef>, dx: Double, dy: Double) {
        refs.forEachIndexed { index, ref ->
            val ox = dx * index
            val oy = dy * index
            when (val node = requireNode(ref.id)) {
                is RectDraft -> {
                    node.x = node.x + constantTrack(ox)
                    node.y = node.y + constantTrack(oy)
                }
                is GroupDraft -> {
                    node.x = node.x + constantTrack(ox)
                    node.y = node.y + constantTrack(oy)
                }
            }
        }
    }

    /** Sample a node's own position tracks at time `t` (the split point for `split`). */
    fun positionAt(ref: NodeRef, t: Double): Vec2d = when (val n = requireNode(ref.id)) {
        is RectDraft -> Vec2d(n.x.sample(t), n.y.sample(t))
        is GroupDraft -> Vec2d(n.x.sample(t), n.y.sample(t))
    }

    /**
     * Clone `parent` (deep for groups) and move the clone's origin to (0, 0) so a shard's
     * flight can be authored around the origin, then translated onto the split point. Every
     * other channel (size, fill, scale, rotation) is inherited from the parent.
     */
    fun cloneShardAtOrigin(parent: NodeRef): NodeRef {
        val ref = when (requireNode(parent.id)) {
            is GroupDraft -> cloneNodeDeepRef(parent)
            is RectDraft -> cloneNodeRef(parent)
        }
        when (val n = requireNode(ref.id)) {
            is RectDraft -> { n.x = constantTrack(0.0); n.y = constantTrack(0.0) }
            is GroupDraft -> { n.x = constantTrack(0.0); n.y = constantTrack(0.0) }
        }
        return ref
    }

    /** Place the active object on a circle of the given radius at the given angle (radians). */
    fun setPolar(angle: Double, radius: Double) {
        setX(constantTrack(radius * cos(angle)))
        setY(constantTrack(radius * sin(angle)))
    }

    private fun mapScalarTracks(ref: NodeRef, transform: (Track<Double>) -> Track<Double>) {
        when (val node = requireNode(ref.id)) {
            is RectDraft -> {
                node.x = transform(node.x)
                node.y = transform(node.y)
                node.w = transform(node.w)
                node.h = transform(node.h)
                node.rotation = transform(node.rotation)
                node.scaleX = transform(node.scaleX)
                node.scaleY = transform(node.scaleY)
                node.alpha = transform(node.alpha)
                node.z = transform(node.z)
            }
            is GroupDraft -> {
                node.x = transform(node.x)
                node.y = transform(node.y)
                node.rotation = transform(node.rotation)
                node.scaleX = transform(node.scaleX)
                node.scaleY = transform(node.scaleY)
                node.alpha = transform(node.alpha)
            }
        }
    }

    private fun cloneNodeRef(ref: NodeRef): NodeRef {
        val node = requireNode(ref.id)
        val id = uniqueId("${ref.id}-copy")
        return when (node) {
            is RectDraft -> {
                nodes[id] = node.cloneAs(id)
                NodeRef.Rect(id)
            }
            is GroupDraft -> {
                nodes[id] = node.cloneShallowAs(id)
                NodeRef.Group(id)
            }
        }
    }

    private fun cloneNodeDeepRef(ref: NodeRef): NodeRef {
        val node = requireNode(ref.id)
        val id = uniqueId("${ref.id}-copy")
        return when (node) {
            is RectDraft -> {
                nodes[id] = node.cloneAs(id)
                NodeRef.Rect(id)
            }
            is GroupDraft -> {
                val clone = node.cloneShallowAs(id)
                clone.children.clear()
                nodes[id] = clone
                node.children.forEach { childId ->
                    clone.children += cloneNodeDeepRef(refFor(childId)).id
                }
                NodeRef.Group(id)
            }
        }
    }

    private fun uniqueId(requested: String): String {
        if (requested !in nodes) return requested
        var n = 2
        while ("$requested-$n" in nodes) n++
        return "$requested-$n"
    }

    private fun requireNode(id: String): NodeDraft =
        nodes[id] ?: throw RectLangException("Unknown node '$id'")

    private fun target(): NodeDraft {
        val ref = active.lastOrNull() ?: throw RectLangException("No active rect or group")
        return requireNode(ref.id)
    }

    private fun rectTarget(): RectDraft =
        target() as? RectDraft ?: throw RectLangException("Current target is not a rect")

    private fun group(id: String): GroupDraft =
        nodes[id] as? GroupDraft ?: throw RectLangException("Unknown group '$id'")

    private fun commonParentOf(ids: Set<String>): String? {
        if (ids.isEmpty()) return null
        return nodes.values.filterIsInstance<GroupDraft>().firstOrNull { group ->
            ids.all { it in group.children }
        }?.id
    }

    private fun layerValue(name: String): Double =
        layers.getOrPut(name) { nextLayer++ }

    private fun replaceActive(oldId: String, fresh: String, node: NodeDraft) {
        for (index in active.indices) {
            if (active[index].id == oldId) {
                active[index] = if (node is RectDraft) NodeRef.Rect(fresh) else NodeRef.Group(fresh)
            }
        }
    }

    private fun NodeDraft.toRef(): NodeRef =
        if (this is RectDraft) NodeRef.Rect(id) else NodeRef.Group(id)
}

private data class MutableMaterialSpec(
    val name: String,
    val materialId: Int,
    val stackSLSource: String?
) {
    fun moduleSpec(): ShaderModuleSpec? =
        stackSLSource?.let {
            ShaderModuleSpec(
                name = name,
                materialId = materialId,
                source = it,
                prefix = "rectmat_${sanitizeGlslIdentifier(name)}"
            )
        }
}

private operator fun Track<Double>.plus(other: Track<Double>): Track<Double> = BinaryDoubleTrack(this, other, TrackOp2.ADD)
private operator fun Track<Double>.times(other: Track<Double>): Track<Double> = BinaryDoubleTrack(this, other, TrackOp2.MUL)

/** `2 * axis - track` — a reflection of a coordinate track about a fixed axis. */
private fun reflectAbout(track: Track<Double>, axis: Double): Track<Double> =
    BinaryDoubleTrack(constantTrack(2.0 * axis), track, TrackOp2.SUB)

fun rectCornersWorld(r: SampledRect): Array<Vec2d> {
    val hw = 0.5 * r.size.x * r.scale.x
    val hh = 0.5 * r.size.y * r.scale.y
    val local = arrayOf(
        Vec2d(-hw, -hh),
        Vec2d(hw, -hh),
        Vec2d(hw, hh),
        Vec2d(-hw, hh)
    )
    val c = cos(r.rotation)
    val s = sin(r.rotation)
    return Array(4) { i ->
        val p = local[i]
        Vec2d(
            r.center.x + c * p.x - s * p.y,
            r.center.y + s * p.x + c * p.y
        )
    }
}

private fun rectCornersLocal(r: SampledRect): Array<Vec2d> {
    val hw = 0.5 * r.size.x * r.scale.x
    val hh = 0.5 * r.size.y * r.scale.y
    return arrayOf(
        Vec2d(-hw, -hh),
        Vec2d(hw, -hh),
        Vec2d(hw, hh),
        Vec2d(-hw, hh)
    )
}

fun lowerSampledScene(scene: SampledScene): RectMesh {
    val vertices = ArrayList<RectVertex>(scene.rects.size * 4)
    val indices = ArrayList<Int>(scene.rects.size * 6)
    scene.rects.forEach { rect ->
        val base = vertices.size
        val world = rectCornersWorld(rect)
        val local = rectCornersLocal(rect)
        val colors = vertexColors(rect, world, local)
        for (i in 0 until 4) {
            vertices += RectVertex(
                position = Vec4f(world[i].x.toFloat(), world[i].y.toFloat(), 0f, 1f),
                color = colors[i].toVec4f(),
                material = Vec4f(
                    rect.material.materialId.toFloat(),
                    rect.material.paramBase.toFloat(),
                    rect.objectId.toFloat(),
                    rect.material.flags.toFloat()
                ),
                shaderData = rect.material.shaderData
            )
        }
        indices += listOf(base + 0, base + 1, base + 2, base + 0, base + 2, base + 3)
    }
    return RectMesh(vertices, indices.toIntArray())
}

fun SceneProgram.lowerFrameToMesh(time: Double): RectMesh =
    lowerSampledScene(sample(time))

fun SceneProgram.writeFrameToMesh(
    time: Double,
    mesh: Mesh,
    attributes: RectMeshAttributeNames = RectMeshAttributeNames()
): RectMesh =
    lowerFrameToMesh(time).also { it.writeTo(mesh, attributes) }

fun SampledScene.writeToMesh(
    mesh: Mesh,
    attributes: RectMeshAttributeNames = RectMeshAttributeNames()
): RectMesh =
    lowerSampledScene(this).also { it.writeTo(mesh, attributes) }

fun RectMesh.writeTo(
    mesh: Mesh,
    attributes: RectMeshAttributeNames = RectMeshAttributeNames()
): Mesh {
    require(indices.size % 3 == 0) { "RectMesh indices must contain whole triangles; got ${indices.size} indices" }
    val primitiveSize = try {
        mesh.primitiveSize()
    } catch (t: UninitializedPropertyAccessException) {
        throw RectLangException(
            "Rect animation mesh writer needs a Mesh attached to a MeshModulePointer. " +
                "Construct it with Mesh(renderer, modulePointer), add it to a module first, or call from inside a module context."
        )
    }
    require(primitiveSize == 3) {
        "Rect animation mesh writer needs a triangle-list Mesh module; primitiveSize=$primitiveSize"
    }
    mesh.resize(vertices.size, indices.size / 3)
    writeRequiredVec4Attribute(mesh, attributes.position) { position }
    writeRequiredVec4Attribute(mesh, attributes.color) { color }
    writeRequiredVec4Attribute(mesh, attributes.material) { material }
    writeRequiredVec4Attribute(mesh, attributes.shaderData) { shaderData }
    val indexBuffer = mesh.t ?: throw RectLangException("Mesh did not allocate an index buffer")
    indexBuffer.mapAndWrite_ints {
        indices.forEach { put(it) }
    }
    return mesh
}

private inline fun RectMesh.writeRequiredVec4Attribute(
    mesh: Mesh,
    name: String,
    crossinline value: RectVertex.() -> Vec4f
) {
    try {
        mesh.input(name)
    } catch (t: Throwable) {
        throw RectLangException(
            "Rect animation mesh writer expected vec4 vertex attribute '$name'. " +
                "Use the standard shader contract or pass RectMeshAttributeNames for this shader. " +
                "Original error: ${t.message}"
        )
    }
    mesh.write(name) {
        vertices.forEach { vertex ->
            val v = vertex.value()
            put(v.x).put(v.y).put(v.z).put(v.w)
        }
    }
}

private fun vertexColors(rect: SampledRect, world: Array<Vec2d>, local: Array<Vec2d>): Array<Color4> =
    when (val paint = rect.fill) {
        is PaintSpec.Solid -> Array(4) { paint.color.alphaTimes(rect.alpha) }
        is PaintSpec.LinearGradient -> {
            val points = if (paint.space == GradientSpace.Local) local else world
            Array(4) { i -> paint.c0.mix(paint.c1, gradientT(points[i], paint.p0, paint.p1)).alphaTimes(rect.alpha) }
        }
    }

private fun gradientT(point: Vec2d, p0: Vec2d, p1: Vec2d): Double {
    val axis = p1 - p0
    val denom = axis dot axis
    return if (denom <= 1e-12) 0.0 else ((point - p0) dot axis) / denom
}

fun sampledFramePacket(program: SceneProgram, time: Double): FramePacket {
    val sampled = program.sample(time)
    val contacts = detectContacts(sampled.rects)
    return FramePacket(
        time = time,
        mesh = lowerSampledScene(sampled),
        materialTable = program.materialTable,
        semantic = SemanticFrame(sampled.rects, contacts, frameMetrics(time, sampled.rects, emptyMap()))
    )
}

fun colliderFor(rect: SampledRect): RectCollider {
    val corners = rectCornersWorld(rect)
    val axisX = (corners[1] - corners[0]).normalized()
    val axisY = (corners[3] - corners[0]).normalized()
    return RectCollider(
        id = rect.id,
        corners = corners,
        center = rect.center,
        axes = arrayOf(axisX, axisY),
        halfExtents = Vec2d((corners[1] - corners[0]).length() * 0.5, (corners[3] - corners[0]).length() * 0.5)
    )
}

fun detectContacts(rects: List<SampledRect>): List<ContactPair> {
    val colliders = rects.filter { it.alpha > 0.001 && it.size.x > 0.0 && it.size.y > 0.0 }.map(::colliderFor)
    val contacts = ArrayList<ContactPair>()
    for (i in 0 until colliders.size) {
        for (j in i + 1 until colliders.size) {
            satContact(colliders[i], colliders[j])?.let { contacts += it }
        }
    }
    return contacts
}

private fun satContact(a: RectCollider, b: RectCollider): ContactPair? {
    var minOverlap = Double.POSITIVE_INFINITY
    for (axis in a.axes + b.axes) {
        val pa = project(a.corners, axis)
        val pb = project(b.corners, axis)
        val overlap = min(pa.second, pb.second) - max(pa.first, pb.first)
        if (overlap < 0.0) return null
        minOverlap = min(minOverlap, overlap)
    }
    return ContactPair(a.id, b.id, minOverlap)
}

private fun project(points: Array<Vec2d>, axis: Vec2d): Pair<Double, Double> {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    points.forEach {
        val p = it dot axis
        lo = min(lo, p)
        hi = max(hi, p)
    }
    return lo to hi
}

fun analyzeAnimation(program: SceneProgram, settings: AnalysisSettings = AnalysisSettings()): AnimationReport {
    val fps = settings.fps ?: program.fps
    val dt = 1.0 / fps
    val frameCount = max(1, ceil(program.duration * fps).toInt())
    val sampled = (0..frameCount).map { index ->
        val t = min(program.duration, index * dt)
        program.sample(t)
    }

    val objectMetrics = linkedMapOf<String, MutableList<MotionMetrics>>()
    val frameMetrics = ArrayList<FrameSemanticMetrics>()
    val events = ArrayList<VisualEvent>()
    val warnings = ArrayList<AnimationWarning>()
    val previousContactPairs = mutableSetOf<Pair<String, String>>()
    val visibility = linkedMapOf<String, MutableList<Pair<Double, Boolean>>>()
    val moving = mutableMapOf<String, Boolean>()
    val previousDirection = mutableMapOf<String, Double>()
    val previousAngularSign = mutableMapOf<String, Int>()

    for (i in sampled.indices) {
        val current = sampled[i]
        val previous = sampled.getOrNull(i - 1)
        val previousById = previous?.rects?.associateBy { it.id }.orEmpty()
        val motions = linkedMapOf<String, MotionMetrics>()

        current.rects.forEach { rect ->
            val visible = isVisible(rect, program.wall) && rect.alpha > 0.001
            visibility.getOrPut(rect.id) { ArrayList() } += current.time to visible
            if (previous != null) {
                val old = previousById[rect.id]
                if (old != null) {
                    val metric = motionMetric(old, rect, previous.time, current.time)
                    objectMetrics.getOrPut(rect.id) { ArrayList() } += metric
                    motions[rect.id] = metric
                    val wasMoving = moving[rect.id] ?: false
                    if (!wasMoving && metric.speedMetersPerSecond > settings.startMovingSpeed) {
                        events += VisualEvent.StartMoving(current.time, rect.id, 0.75)
                        moving[rect.id] = true
                    } else if (wasMoving && metric.speedMetersPerSecond < settings.stopSpeed) {
                        events += VisualEvent.Stop(current.time, rect.id, 0.75)
                        moving[rect.id] = false
                    }
                    if (metric.speedMetersPerSecond > settings.startMovingSpeed) {
                        val lastDirection = previousDirection[rect.id]
                        if (lastDirection != null && angleDistance(lastDirection, metric.directionRadians) > settings.directionChangeRadians) {
                            events += VisualEvent.DirectionChange(current.time, rect.id, lastDirection, metric.directionRadians, 0.65)
                        }
                        previousDirection[rect.id] = metric.directionRadians
                    }
                    val sign = metric.angularVelocityRadiansPerSecond.signWithDeadband()
                    val oldSign = previousAngularSign[rect.id]
                    if (sign != 0 && oldSign != null && oldSign != 0 && sign != oldSign) {
                        events += VisualEvent.RotationReversal(current.time, rect.id, 0.65)
                    }
                    if (sign != 0) previousAngularSign[rect.id] = sign
                    settings.opacityThresholds.forEach { threshold ->
                        val a0 = old.alpha
                        val a1 = rect.alpha
                        if (a0 < threshold && a1 >= threshold) {
                            events += VisualEvent.OpacityCrossing(current.time, rect.id, threshold, rising = true, confidence = 0.7)
                        } else if (a0 >= threshold && a1 < threshold) {
                            events += VisualEvent.OpacityCrossing(current.time, rect.id, threshold, rising = false, confidence = 0.7)
                        }
                    }
                    val oldVisible = isVisible(old, program.wall) && old.alpha > 0.001
                    if (!oldVisible && visible) events += VisualEvent.EnterScreen(current.time, rect.id, 0.7)
                    if (oldVisible && !visible) events += VisualEvent.LeaveScreen(current.time, rect.id, 0.7)
                }
            }
        }

        val contacts = detectContacts(current.rects)
        val contactPairs = contacts.map { orderedPair(it.a, it.b) }.toSet()
        (contactPairs - previousContactPairs).forEach { events += VisualEvent.TouchStart(current.time, it.first, it.second, 0.75) }
        (previousContactPairs - contactPairs).forEach { events += VisualEvent.TouchEnd(current.time, it.first, it.second, 0.75) }
        previousContactPairs.clear()
        previousContactPairs += contactPairs

        frameMetrics += frameMetrics(current.time, current.rects, motions)
        if (contacts.size >= 4) warnings += AnimationWarning.ExcessiveOverlap(current.time, contacts.flatMap { listOf(it.a, it.b) }.distinct())
    }

    frameMetrics.filter { it.averageSpeedMetersPerSecond > settings.tooMuchChangeSpeed }.forEach {
        warnings += AnimationWarning.TooMuchChange(it.time, it.averageSpeedMetersPerSecond)
    }

    visibility.forEach { (id, values) ->
        if (values.none { it.second }) warnings += AnimationWarning.ObjectNeverVisible(id)
    }
    blankIntervals(frameMetrics, settings.blankAreaThreshold).forEach { warnings += AnimationWarning.MostlyBlank(it.first, it.second) }
    val renderW = settings.renderWidth
    val renderH = settings.renderHeight
    if (renderW != null && renderH != null && program.fit != FitPolicy.Stretch) {
        val wallAspect = program.wall.width / program.wall.height
        val renderAspect = renderW / renderH
        if (abs(wallAspect - renderAspect) / wallAspect > 0.01) warnings += AnimationWarning.AspectMismatch(wallAspect, renderAspect)
    }

    return AnimationReport(
        duration = program.duration,
        frameMetrics = frameMetrics,
        objectMetrics = objectMetrics.mapValues { it.value.toList() },
        events = events.sortedBy { it.time },
        warnings = warnings
    )
}

private fun motionMetric(a: SampledRect, b: SampledRect, t0: Double, t1: Double): MotionMetrics {
    val dt = (t1 - t0).takeIf { it > 1e-12 } ?: 1.0
    val dx = b.center.x - a.center.x
    val dy = b.center.y - a.center.y
    val speed = sqrt(dx * dx + dy * dy) / dt
    val dRot = unwrapAngle(b.rotation - a.rotation)
    return MotionMetrics(
        objectId = b.id,
        time0 = t0,
        time1 = t1,
        dxMeters = dx,
        dyMeters = dy,
        speedMetersPerSecond = speed,
        directionRadians = atan2(dy, dx),
        dRotationRadians = dRot,
        angularVelocityRadiansPerSecond = dRot / dt,
        visibleAreaSquareMeters = abs(b.size.x * b.scale.x * b.size.y * b.scale.y),
        opacityChange = b.alpha - a.alpha
    )
}

private fun frameMetrics(time: Double, rects: List<SampledRect>, motion: Map<String, MotionMetrics>): FrameSemanticMetrics {
    var count = 0
    var area = 0.0
    var weightedArea = 0.0
    var weightedVx = 0.0
    var weightedVy = 0.0
    var weightedSpeed = 0.0
    var weightedAngular = 0.0
    rects.forEach { rect ->
        val a = abs(rect.size.x * rect.scale.x * rect.size.y * rect.scale.y)
        if (rect.alpha > 0.001 && a > 1e-12) count++
        area += a
        val weight = a * rect.alpha.coerceIn(0.0, 1.0)
        weightedArea += weight
        val m = motion[rect.id]
        if (m != null) {
            val dt = (m.time1 - m.time0).takeIf { it > 1e-12 } ?: 1.0
            weightedVx += weight * m.dxMeters / dt
            weightedVy += weight * m.dyMeters / dt
            weightedSpeed += weight * m.speedMetersPerSecond
            weightedAngular += weight * abs(m.angularVelocityRadiansPerSecond)
        }
    }
    val denom = weightedArea.takeIf { it > 1e-12 } ?: 1.0
    return FrameSemanticMetrics(
        time = time,
        visibleObjectCount = count,
        totalVisibleAreaSquareMeters = area,
        averageVelocity = Vec2d(weightedVx / denom, weightedVy / denom),
        averageSpeedMetersPerSecond = weightedSpeed / denom,
        averageAngularVelocityRadiansPerSecond = weightedAngular / denom,
        totalOpacityWeightedArea = weightedArea
    )
}

private fun blankIntervals(metrics: List<FrameSemanticMetrics>, threshold: Double): List<Pair<Double, Double>> {
    val intervals = ArrayList<Pair<Double, Double>>()
    var start: Double? = null
    metrics.forEach {
        val blank = it.totalOpacityWeightedArea <= threshold
        if (blank && start == null) start = it.time
        val currentStart = start
        if (!blank && currentStart != null) {
            if (it.time - currentStart > 0.25) intervals += currentStart to it.time
            start = null
        }
    }
    val finalStart = start
    if (finalStart != null && metrics.isNotEmpty()) intervals += finalStart to metrics.last().time
    return intervals
}

private fun isVisible(rect: SampledRect, wall: WallSpec): Boolean {
    val corners = rectCornersWorld(rect)
    val minX = corners.minOf { it.x }
    val maxX = corners.maxOf { it.x }
    val minY = corners.minOf { it.y }
    val maxY = corners.maxOf { it.y }
    val halfW = wall.width * 0.5
    val halfH = wall.height * 0.5
    return maxX >= -halfW && minX <= halfW && maxY >= -halfH && minY <= halfH
}

class StackSLRectMaterialCompiler(
    private val convention: StackSLRectMaterialConvention = StackSLRectMaterialConvention()
) {
    fun compile(module: ShaderModuleSpec): StackSLRectMaterialCompileResult {
        val result = StackSLCompiler.compile(
            module.source,
            StackSLCompileOptions(
                prefix = module.prefix,
                includeHeader = false,
                declareSamplers = true,
                externalizeConstants = true
            )
        )
        val wrapper = if (result.ok) convention.wrap(module, result) else null
        return StackSLRectMaterialCompileResult(module, result, wrapper)
    }
}

data class StackSLRectMaterialConvention(
    val inputExpression: String = "vec4(r.uv, r.material.z, r.time)",
    val outputExpression: String = "vec4(stackColor.rgb * r.color.rgb, stackColor.a * r.color.a)"
) {
    fun wrap(module: ShaderModuleSpec, result: StackSLCompileResult): String {
        val functionName = "material_${sanitizeGlslIdentifier(module.name)}"
        return buildString {
            appendLine(result.glsl)
            appendLine("vec4 $functionName(RectFrag r) {")
            appendLine("    vec4 stackInput = $inputExpression;")
            appendLine("    vec4 stackColor = ${module.prefix}(stackInput);")
            appendLine("    return $outputExpression;")
            appendLine("}")
        }
    }
}

data class StackSLRectMaterialCompileResult(
    val module: ShaderModuleSpec,
    val stackSL: StackSLCompileResult,
    val rectFragGlsl: String?
) {
    val ok: Boolean get() = stackSL.diagnostics.none { it.severity == StackSLSeverity.ERROR }
}

fun compileStackSLMaterials(
    program: SceneProgram,
    compiler: StackSLRectMaterialCompiler = StackSLRectMaterialCompiler()
): List<StackSLRectMaterialCompileResult> =
    program.materialTable.modules.map { compiler.compile(it) }

fun rectMaterialShaderCode(
    program: SceneProgram,
    compiler: StackSLRectMaterialCompiler = StackSLRectMaterialCompiler()
): RectMaterialShaderCode {
    val compiled = compileStackSLMaterials(program, compiler)
    val insertion = rectMaterialShaderInsertion(compiled)
    return RectMaterialShaderCode(
        insertion = insertion,
        exampleFragmentShader = rectAnimationFragmentShaderWrapper(insertion),
        compiledMaterials = compiled
    )
}

const val RECT_ANIMATION_VERTEX_SHADER: String = """
#version 450

layout(push_constant) uniform Push {
    mat4 worldToClip;
    float time;
    float wallWidth;
    float wallHeight;
} push;

layout(location = 0) in vec4 a_position;
layout(location = 1) in vec4 a_color;
layout(location = 2) in vec4 a_material;
layout(location = 3) in vec4 a_shader;

layout(location = 0) out vec2 v_uv;
layout(location = 1) out vec2 v_world;
layout(location = 2) out vec4 v_color;
layout(location = 3) out vec4 v_material;
layout(location = 4) out vec4 v_shader;

vec2 cornerUv(uint corner) {
    if (corner == 0u) return vec2(0.0, 0.0);
    if (corner == 1u) return vec2(1.0, 0.0);
    if (corner == 2u) return vec2(1.0, 1.0);
    return vec2(0.0, 1.0);
}

void main() {
    uint corner = uint(gl_VertexIndex) & 3u;
    v_uv = cornerUv(corner);
    v_world = a_position.xy;
    v_color = a_color;
    v_material = a_material;
    v_shader = a_shader;
    gl_Position = push.worldToClip * a_position;
}
"""

const val RECT_MATERIAL_SHADER_INSERTION_PREAMBLE: String = """
struct RectFrag {
    vec2 uv;
    vec2 world;
    vec4 color;
    vec4 material;
    vec4 shaderData;
    float time;
};

vec4 shadePlain(RectFrag r) {
    return r.color;
}

vec4 shadeSoftEdge(RectFrag r) {
    float edge = min(min(r.uv.x, 1.0 - r.uv.x), min(r.uv.y, 1.0 - r.uv.y));
    float softness = max(r.shaderData.x, 0.0001);
    float a = smoothstep(0.0, softness, edge);
    return vec4(r.color.rgb, r.color.a * a);
}

vec4 shadeDiagnostic(RectFrag r) {
    return vec4(r.uv, fract(r.material.z * 0.137), max(r.color.a, 0.35));
}
"""

const val RECT_ANIMATION_FRAGMENT_WRAPPER_PREAMBLE: String = """
#version 450

layout(location = 0) in vec2 v_uv;
layout(location = 1) in vec2 v_world;
layout(location = 2) in vec4 v_color;
layout(location = 3) in vec4 v_material;
layout(location = 4) in vec4 v_shader;

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform Push {
    mat4 worldToClip;
    float time;
    float wallWidth;
    float wallHeight;
} push;
"""

fun rectMaterialShaderInsertion(compiledModules: List<StackSLRectMaterialCompileResult> = emptyList()): String {
    val generated = compiledModules.mapNotNull { it.rectFragGlsl }
    return buildString {
        appendLine(RECT_MATERIAL_SHADER_INSERTION_PREAMBLE.trimIndent())
        generated.forEach {
            appendLine()
            appendLine(it)
        }
        appendLine()
        appendLine("vec4 dispatchMaterial(RectFrag r) {")
        appendLine("    int id = int(r.material.x + 0.5);")
        appendLine("    if (id == 0) return shadePlain(r);")
        appendLine("    if (id == 1) return shadeSoftEdge(r);")
        appendLine("    if (id == 2) return shadeDiagnostic(r);")
        compiledModules.filter { it.ok }.forEach {
            appendLine("    if (id == ${it.module.materialId}) return material_${sanitizeGlslIdentifier(it.module.name)}(r);")
        }
        appendLine("    return vec4(1.0, 0.0, 1.0, 1.0);")
        appendLine("}")
    }
}

fun rectAnimationFragmentShaderWrapper(materialInsertion: String): String =
    buildString {
        appendLine(RECT_ANIMATION_FRAGMENT_WRAPPER_PREAMBLE.trimIndent())
        appendLine()
        appendLine(materialInsertion.trimIndent())
        appendLine()
        appendLine("void main() {")
        appendLine("    RectFrag r;")
        appendLine("    r.uv = v_uv;")
        appendLine("    r.world = v_world;")
        appendLine("    r.color = v_color;")
        appendLine("    r.material = v_material;")
        appendLine("    r.shaderData = v_shader;")
        appendLine("    r.time = push.time;")
        appendLine("    outColor = dispatchMaterial(r);")
        appendLine("}")
    }

fun rectAnimationFragmentShader(compiledModules: List<StackSLRectMaterialCompileResult> = emptyList()): String =
    rectAnimationFragmentShaderWrapper(rectMaterialShaderInsertion(compiledModules))

data class RectSLRenderSettings(
    var pixelsPerMeter: Double,
    var fps: Double,
    var outputDirectory: Path,
    var renderer: Renderer,
    var renderTarget: RenderTarget = renderer.renderList,
    var name: String = "rectsl-${UUID.randomUUID().toString().take(8)}",
    var jpegQuality: Int = 92,
    var includeFinalFrame: Boolean = false,
    var frameName: (Int, Double) -> String = { index, _ -> "frame_%05d.jpg".format(index) },
    var timeoutMsPerFrame: Long = 15_000L,
    var waitPollMs: Long = 8L,
    var driveRendererWhileWaiting: Boolean = false
) {
    fun validate() {
        require(pixelsPerMeter > 0.0) { "pixelsPerMeter must be positive" }
        require(fps > 0.0) { "fps must be positive" }
        require(jpegQuality in 1..100) { "jpegQuality must be in 1..100" }
        require(timeoutMsPerFrame > 0L) { "timeoutMsPerFrame must be positive" }
        require(waitPollMs >= 0L) { "waitPollMs cannot be negative" }
    }
}

data class RectSLRenderResult(
    val program: SceneProgram,
    val width: Int,
    val height: Int,
    val fps: Double,
    val frameTimes: List<Double>,
    val files: List<Path>
) {
    val frameCount: Int get() = files.size
}

fun interface RectSLRenderProgress {
    fun frameSaved(frameIndex: Int, time: Double, file: Path)
}

object RectSLRenderRunner {
    suspend fun renderJpgSequence(
        source: String,
        settings: RectSLRenderSettings,
        progress: RectSLRenderProgress? = null
    ): RectSLRenderResult {
        val compile = RectAnimationOracle.compile(source)
        if (!compile.ok || compile.programOrNull == null) {
            throw RectLangException("RectSL compile failed:\n${compile.diagnostics.joinToString("\n")}")
        }
        return renderJpgSequence(compile.program, settings, progress)
    }

    suspend fun renderJpgSequence(
        program: SceneProgram,
        settings: RectSLRenderSettings,
        progress: RectSLRenderProgress? = null
    ): RectSLRenderResult {
        settings.validate()
        val shaderCode = rectMaterialShaderCode(program)
        if (!shaderCode.ok) {
            val diagnostics = shaderCode.compiledMaterials
                .flatMap { it.stackSL.diagnostics }
                .joinToString("\n") { "${it.severity}: ${it.message}" }
            throw RectLangException("RectSL material compile failed:\n$diagnostics")
        }

        Files.createDirectories(settings.outputDirectory)
        val width = max(1, round(program.wall.width * settings.pixelsPerMeter).toInt())
        val height = max(1, round(program.wall.height * settings.pixelsPerMeter).toInt())
        val frameTimes = rectSLFrameTimes(program.duration, settings.fps, settings.includeFinalFrame)
        val renderer = settings.renderer
        val targetName = "${settings.name}-rtt"
        val moduleName = "${settings.name}-module"
        val meshName = "${settings.name}-mesh"

        var currentTime = 0.0
        val worldToClip = program.wall.worldToClip()
        val rtt = RenderToTexture(renderer) {
            this.width = width
            this.height = height
            this.format = VK_FORMAT_B8G8R8A8_SRGB
            this.useDepth = false
            this.clearColor = afield.stdlib.vec(0f, 0f, 0f, 1f)
            this.colorTransferSrc = true
        }
        val module = Module(parameters(renderer) {
            name = moduleName
            vertex = RECT_ANIMATION_VERTEX_SHADER
            fragment = shaderCode.exampleFragmentShader
            blend = true
            depthTest = false
            primitiveType = triangles
            rtt.apply()
        }).pointer()
        val mesh = Mesh(renderer, module)
        mesh.push["worldToClip"] = worldToClip
        mesh.push.bind("time") { currentTime.toFloat() }
        mesh.push["wallWidth"] = program.wall.width.toFloat()
        mesh.push["wallHeight"] = program.wall.height.toFloat()
        mesh.update["rectsl-frame"] = sequence {
            while (true) {
                program.writeFrameToMesh(currentTime, mesh)
                yield(Unit)
            }
        }
        module.current.meshes[meshName] = mesh
        rtt += moduleName to module
        val previousPreTarget = settings.renderTarget.preTargets.put(targetName, rtt)

        val files = ArrayList<Path>(frameTimes.size)
        try {
            frameTimes.forEachIndexed { index, time ->
                currentTime = time
                val file = settings.outputDirectory.resolve(settings.frameName(index, time))
                Files.createDirectories(file.parent ?: settings.outputDirectory)
                Files.deleteIfExists(file)
                rtt.saveJpgAsync(file.toString(), quality = settings.jpegQuality)
                    .awaitRectSLSave(renderer, settings)
                files.add(file)
                progress?.frameSaved(index, time, file)
            }
        } finally {
            if (previousPreTarget == null) settings.renderTarget.preTargets.remove(targetName)
            else settings.renderTarget.preTargets[targetName] = previousPreTarget
            rtt.modules.remove(moduleName)
            module.current.meshes.remove(meshName)
            mesh.cleanupRectSLBuffers()
            module.current.cleanup()
            rtt.cleanupRectSLResources()
        }

        return RectSLRenderResult(
            program = program,
            width = width,
            height = height,
            fps = settings.fps,
            frameTimes = frameTimes,
            files = files
        )
    }
}

suspend fun renderRectSLJpgSequence(
    source: String,
    outputDirectory: Path,
    pixelsPerMeter: Double,
    fps: Double,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit = {}
): RectSLRenderResult {
    val settings = RectSLRenderSettings(
        pixelsPerMeter = pixelsPerMeter,
        fps = fps,
        outputDirectory = outputDirectory,
        renderer = renderer
    ).apply(configure)
    return RectSLRenderRunner.renderJpgSequence(source, settings)
}

suspend fun renderRectSLJpgSequence(
    source: String,
    outputDirectory: Path,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit
): RectSLRenderResult {
    val settings = RectSLRenderSettings(
        pixelsPerMeter = 100.0,
        fps = 60.0,
        outputDirectory = outputDirectory,
        renderer = renderer
    ).apply(configure)
    return RectSLRenderRunner.renderJpgSequence(source, settings)
}

suspend fun renderRectSLJpgSequence(
    source: String,
    outputDirectory: String,
    pixelsPerMeter: Double,
    fps: Double,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit = {}
): RectSLRenderResult =
    renderRectSLJpgSequence(source, Path.of(outputDirectory), pixelsPerMeter, fps, renderer, configure)

suspend fun renderRectSLJpgSequence(
    source: String,
    outputDirectory: String,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit
): RectSLRenderResult =
    renderRectSLJpgSequence(source, Path.of(outputDirectory), renderer, configure)

fun renderRectSLJpgSequenceBlocking(
    source: String,
    outputDirectory: Path,
    pixelsPerMeter: Double,
    fps: Double,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit = {}
): RectSLRenderResult =
    runBlocking {
        renderRectSLJpgSequence(source, outputDirectory, pixelsPerMeter, fps, renderer, configure)
    }

fun renderRectSLJpgSequenceBlocking(
    source: String,
    outputDirectory: Path,
    renderer: Renderer = fromContext(),
    configure: RectSLRenderSettings.() -> Unit
): RectSLRenderResult =
    runBlocking {
        renderRectSLJpgSequence(source, outputDirectory, renderer, configure)
    }

private fun rectSLFrameTimes(duration: Double, fps: Double, includeFinalFrame: Boolean): List<Double> {
    val baseCount = max(1, ceil(duration * fps).toInt())
    val frameCount = if (includeFinalFrame && duration > 0.0) baseCount + 1 else baseCount
    return List(frameCount) { index ->
        if (includeFinalFrame && frameCount > 1) {
            duration * index.toDouble() / (frameCount - 1).toDouble()
        } else {
            min(duration, index.toDouble() / fps)
        }
    }
}

internal fun WallSpec.worldToClip(): Matrix4f =
    when (origin) {
        Origin.Center -> Matrix4f().ortho(
            (-width * 0.5).toFloat(),
            (width * 0.5).toFloat(),
            (-height * 0.5).toFloat(),
            (height * 0.5).toFloat(),
            -1f,
            1f
        )
        Origin.TopLeft -> Matrix4f().ortho(
            0f,
            width.toFloat(),
            height.toFloat(),
            0f,
            -1f,
            1f
        )
    }

private suspend fun CompletableFuture<String>.awaitRectSLSave(
    renderer: Renderer,
    settings: RectSLRenderSettings
): String {
    val deadline = System.nanoTime() + settings.timeoutMsPerFrame * 1_000_000L
    while (!isDone) {
        if (settings.driveRendererWhileWaiting) {
            renderer.render()
        }
        if (System.nanoTime() > deadline) {
            cancel(true)
            throw RectLangException("Timed out waiting for RectSL JPEG save after ${settings.timeoutMsPerFrame}ms")
        }
        if (settings.waitPollMs > 0L) delay(settings.waitPollMs)
        else kotlinx.coroutines.yield()
    }
    return withContext(Dispatchers.IO) {
        get(1L, TimeUnit.MILLISECONDS)
    }
}

private fun Mesh.cleanupRectSLBuffers() {
    (v.values + vn.values).toSet().forEach { it.cleanup() }
    t?.cleanup()
    v.clear()
    vn.clear()
    t = null
}

private fun RenderToTexture.cleanupRectSLResources() {
    defaultSampler?.cleanup()
    imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }.forEach { it.cleanup() }
    colorImageRefs.filterNotNull().distinctBy { it.vkImage }.forEach { it.cleanup() }
    listOfNotNull(depthSampledImageView, depthImageView).distinctBy { it.vkImageView }.forEach { it.cleanup() }
    depthImage?.cleanup()
    commandBuffers.forEach { it.cleanup() }
    fences.forEach { it.cleanup() }
}

private fun rotate(p: Vec2d, angle: Double): Vec2d {
    val c = cos(angle)
    val s = sin(angle)
    return Vec2d(c * p.x - s * p.y, s * p.x + c * p.y)
}

private fun positiveModulo(x: Double, period: Double): Double {
    if (period <= 1e-12) return x
    val p = x % period
    return if (p < 0.0) p + period else p
}

private fun unwrapAngle(angle: Double): Double {
    var a = angle
    while (a > PI) a -= PI * 2.0
    while (a < -PI) a += PI * 2.0
    return a
}

private fun angleDistance(a: Double, b: Double): Double = abs(unwrapAngle(b - a))

private fun Double.signWithDeadband(deadband: Double = 1e-5): Int =
    when {
        this > deadband -> 1
        this < -deadband -> -1
        else -> 0
    }

private fun orderedPair(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

private fun sanitizeGlslIdentifier(name: String): String =
    buildString {
        name.forEachIndexed { index, c ->
            val ok = c == '_' || c.isLetterOrDigit()
            val next = if (ok) c else '_'
            if (index == 0 && next.isDigit()) append('_')
            append(next)
        }
        if (isEmpty()) append("material")
    }

object RectSLSmokeTest {
    val minimalAcceptanceSource: String = """
        wall 30 4 meters
        duration 10
        fps 60

        "r" rect
          -5 0 xy
          1 square
          0xff3300 solid fill
          '( 0 -5   5 0   10 5 ) x
        ;

        dup "r2" rename
          mirror-x
          0x3333ff solid fill
        ;
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        val result = RectAnimationOracle.compile(minimalAcceptanceSource)
        check(result.ok && result.programOrNull != null) { result.diagnostics.joinToString("\n") }

        val program = result.program
        val rects = program.nodes.filterIsInstance<RectNode>()
        check(rects.map { it.id }.toSet() == setOf("r", "r2")) { rects.map { it.id } }

        val sampled = program.sample(5.0).rects.associateBy { it.id }
        check(sampled.keys == setOf("r", "r2")) { sampled.keys }
        check(abs(sampled.getValue("r").center.x) < 1e-6)
        check(abs(sampled.getValue("r2").center.x) < 1e-6)

        val mesh = lowerSampledScene(program.sample(5.0))
        check(mesh.vertices.size == 8) { "Expected 8 vertices, got ${mesh.vertices.size}" }
        check(mesh.indices.size == 12) { "Expected 12 indices, got ${mesh.indices.size}" }
        check(mesh.vertices.all { it.position.z == 0f }) { "RectSL mesh positions should stay in clip-space z" }
        check(mesh.vertices.map { it.material.z }.toSet() == setOf(0f, 1f)) { "Expected object ids in material.z" }
        println("rectsl smoke passed")
    }
}

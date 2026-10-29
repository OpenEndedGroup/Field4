@file:afield.runtime.completion.CompletionCategory("Search")

package afield.lang.rpn

import afield.media.image.util.PngEncoder
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A reverse-Polish language whose programs are pictures: tokens push numbers and colours onto a
 * stack, the program runs once per pixel of a square, and what is left on the stack is that
 * pixel's colour.
 *
 * [ColorRpn] is the language, and it is small enough to read in one sitting: four variables for
 * where the pixel is, twenty-five words, and no way to fail. Compiling a program gives a
 * [ColorRpnProgram], which reports the colour at one point, renders a square at any size on all
 * cores, or hands back PNG bytes that `display` shows as an image.
 *
 * ```kotlin
 * display("main", ColorRpn.compile("x 9 * sin 0.18 +  y 0.55 +  0.62  hsl").png(256))
 * ```
 *
 * The rest of the family builds a search on top: `ColorRpnGenome.kt` breeds one program from two,
 * and `ColorRpnEvolution.kt` puts a grid of the results in a box for you to pick from. The
 * language is described word by word on [ColorRpn] below.
 */

/**
 * The colour expression language of the madp *GA* demo (`ga.html`): a reverse-Polish program run
 * once per pixel, whose result is a colour. It is the genome of interactive evolution in the manner
 * of Karl Sims' *Genetic Images* (see `ColorRpnGenome` and `afield.study.search.EyeEvolution`), and
 * this file follows the demo's interpreter exactly, so a program draws the same picture here as in
 * the browser.
 *
 * ```kotlin
 * val program = ColorRpn.compile("x 9 * sin 0.18 +  y 0.55 +  0.62  hsl")
 * display("main", program.png(256))
 * ```
 *
 * **Values.** A stack item is a number or a colour (three channels). Variables push numbers:
 * `x` and `y` run 0..1 across the picture (`y` grows downward, as on a canvas), `r` is the distance
 * from the centre divided by the distance to a corner, and `a` is the angle about the centre as a
 * fraction of a turn. Any other token that reads as a number pushes it.
 *
 * **Words.**
 *
 * | word | takes | leaves |
 * | --- | --- | --- |
 * | `dup` | v | v v |
 * | `rgb`, `hsl` | three numbers (a colour counts as its luma) | a colour; `rgb` wraps each into 0..1 |
 * | `sat`, `hue`, `lum` | a colour and a number, either order | the colour with that HSL component set; two numbers average, two colours average |
 * | `+ - * /` | two values | numbers: plain arithmetic, `/` safe near zero; with a colour: per channel, wrapped into 0..1, `-` is the absolute difference |
 * | `exp ln sin cos tan asin acos atan invert` | v | per channel for a colour (then wrapped); `invert` is `1 - v`; all clamp their domain instead of failing |
 * | `perlin` | phase | 1D gradient noise in 0..1 |
 * | `fbm` | phase, lacunarity | four octaves of `perlin` |
 * | `normalize` | v | a number's sign, or a colour scaled to unit length |
 * | `dot` | two values | a number: the product, or the channel dot product |
 * | `if` | a b c | b when c > 0.5, else a (a colour condition counts as its luma) |
 * | `blend` | a number and two colours, any order | the colours mixed by the number; numbers stand in as greys |
 *
 * Every use of `perlin` or `fbm` in one program gets its own seed, cycling 1, 2, 3, 4.
 *
 * **Nothing fails.** Reading an empty stack gives 0.5. Unknown tokens are skipped (and reported in
 * [ColorRpnProgram.unknown]); `//` and `#` start comments. An empty program is `0.5`. Non-finite
 * results become 0.5 and numbers are held within ±1e6. Whatever is left on the stack becomes a
 * colour: a colour on top is the answer; otherwise the two topmost colours are averaged, or the
 * topmost colour is mixed 70/30 with the topmost number, or two numbers make a blue-to-orange
 * colour, or one number is a grey.
 */
object ColorRpn {

    /** The words of the language, lower case, in the demo's order: stack, constructors, setters, binary, unary, special. */
    val operators: List<String> = listOf(
        "dup", "rgb", "hsl", "sat", "hue", "lum", "+", "-", "*", "/", "dot",
        "exp", "ln", "sin", "cos", "tan", "asin", "acos", "atan", "invert", "perlin", "normalize",
        "if", "blend", "fbm",
    )

    /** The four variables. */
    val variables: List<String> = listOf("x", "y", "r", "a")

    /** The tokens of [source]: whitespace separated, with `//` and `#` comments removed. */
    fun tokens(source: String?): List<String> {
        if (source.isNullOrEmpty()) return emptyList()
        return source.lines().joinToString(" ") { line -> line.substringBefore("//").substringBefore("#") }
            .trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    /** True when [token] is one of [operators] or [variables] (case-insensitively). */
    fun isWord(token: String): Boolean = WORDS.containsKey(token.lowercase())

    /** [token] as a number, the way JavaScript's `Number(token)` reads it, or `null` if it is not a finite one. */
    fun number(token: String): Double? {
        val t = token.trim()
        val v = when {
            DECIMAL.matches(t) -> t.toDouble()
            t.length > 2 && t[0] == '0' && t[1] in "xX" -> t.substring(2).toLongOrNull(16)?.toDouble()
            t.length > 2 && t[0] == '0' && t[1] in "bB" -> t.substring(2).toLongOrNull(2)?.toDouble()
            t.length > 2 && t[0] == '0' && t[1] in "oO" -> t.substring(2).toLongOrNull(8)?.toDouble()
            else -> null
        }
        return v?.takeIf { it.isFinite() }
    }

    /** A number as the demo writes it into a program: three decimals below 10, two below 100, one above, trailing zeros dropped. */
    fun format(value: Double): String {
        var n = safe(value)
        if (abs(n) < 1e-6) n = 0.0
        val places = if (abs(n) >= 100) 1 else if (abs(n) >= 10) 2 else 3
        val fixed = BigDecimal(n).setScale(places, RoundingMode.HALF_UP).toPlainString().replace(TRAILING_ZEROS, "")
        return if (fixed == "-0") "0" else fixed
    }

    /**
     * [tokens] laid out as the demo lays out a program: a new line after eight tokens, after 42
     * characters, or after `rgb`, `hsl`, `blend` or `if` once a line has five tokens.
     */
    fun pretty(tokens: List<String>): String {
        if (tokens.isEmpty()) return "0.5"
        val lines = mutableListOf<String>()
        val line = mutableListOf<String>()
        var chars = 0
        for (token in tokens) {
            line += token
            chars += token.length + 1
            val boundary = token == "rgb" || token == "hsl" || token == "blend" || token == "if"
            if (line.size >= 8 || chars >= 42 || (boundary && line.size >= 5)) {
                lines += line.joinToString(" ")
                line.clear()
                chars = 0
            }
        }
        if (line.isNotEmpty()) lines += line.joinToString(" ")
        return lines.joinToString("\n")
    }

    /** Compile [source] into a program that can be evaluated per pixel. Never fails. */
    fun compile(source: String?): ColorRpnProgram {
        val ops = ArrayList<Int>()
        val data = ArrayList<Double>()
        val known = ArrayList<String>()
        val unknown = ArrayList<String>()
        var noiseUses = 0
        for (token in tokens(source)) {
            val num = number(token)
            if (num != null) {
                ops += PUSH; data += num; known += format(num)
                continue
            }
            val lowered = token.lowercase()
            val op = WORDS[lowered]
            if (op == null) {
                unknown += token
                continue
            }
            ops += op
            data += if (op == PERLIN || op == FBM) (1 + noiseUses++ % 4).toDouble() else 0.0
            known += lowered
        }
        if (ops.isEmpty()) {
            ops += PUSH; data += 0.5; known += "0.5"
        }
        return ColorRpnProgram(source ?: "", known, unknown, ops.toIntArray(), data.toDoubleArray())
    }

    /** Wrap [v] into 0..1 the way the demo does (`v % 1`, lifted when negative); non-finite is 0.5. */
    fun wrap01(v: Double): Double {
        if (!v.isFinite()) return 0.5
        val w = v % 1.0
        return if (w < 0) w + 1 else w
    }

    /** Clamp [v] into 0..1; non-finite is 0.5. */
    fun clamp01(v: Double): Double = if (!v.isFinite()) 0.5 else v.coerceIn(0.0, 1.0)

    /** [v] held within ±1e6; non-finite is 0.5. */
    fun safe(v: Double): Double = if (!v.isFinite()) 0.5 else v.coerceIn(-1e6, 1e6)

    /** `a / b`, with `b` kept at least 1e-6 away from zero (keeping its sign), then [safe]. */
    fun safeDiv(a: Double, b: Double): Double {
        val d = if (abs(b) < 1e-6) (if (b < 0) -1e-6 else 1e-6) else b
        return safe(a / d)
    }

    /** 1D gradient noise in 0..1 at [phase], for one of the per-use [seed]s. */
    fun perlin(phase: Double, seed: Int): Double = clamp01(0.5 + perlinSigned(phase, seed) * 0.5)

    /** Four octaves of [perlin]; [lacunarity] at or below 1 is read as 1.5..4, above it is held to 1.05..6. */
    fun fbm(phase: Double, lacunarity: Double, seed: Int): Double {
        val l = abs(safe(lacunarity))
        val lac = if (l <= 1) 1.5 + l * 2.5 else l.coerceIn(1.05, 6.0)
        var freq = 1.0
        var amp = 0.5
        var sum = 0.0
        var norm = 0.0
        for (octave in 0 until 4) {
            sum += perlinSigned(phase * freq, seed + octave * 17) * amp
            norm += amp
            freq *= lac
            amp *= 0.5
        }
        return clamp01(0.5 + 0.5 * safeDiv(sum, if (norm == 0.0) 1.0 else norm))
    }

    /** Rec. 709 luma: what a colour counts as where a word wants a number. */
    fun luma(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

    /** HSL to RGB into [out]: hue wrapped, saturation and lightness clamped. */
    fun hslToRgb(h0: Double, s0: Double, l0: Double, out: DoubleArray): DoubleArray {
        val h = wrap01(h0)
        val s = clamp01(s0)
        val l = clamp01(l0)
        if (s <= 0) {
            out[0] = l; out[1] = l; out[2] = l
            return out
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        out[0] = hueToRgb(p, q, h + 1.0 / 3)
        out[1] = hueToRgb(p, q, h)
        out[2] = hueToRgb(p, q, h - 1.0 / 3)
        return out
    }

    /** RGB (each wrapped into 0..1) to HSL into [out]. */
    fun rgbToHsl(r0: Double, g0: Double, b0: Double, out: DoubleArray): DoubleArray {
        val r = wrap01(r0)
        val g = wrap01(g0)
        val b = wrap01(b0)
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        var h = 0.0
        var s = 0.0
        val l = (mx + mn) * 0.5
        if (mx != mn) {
            val d = mx - mn
            s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
            h = when (mx) {
                r -> (g - b) / d + (if (g < b) 6 else 0)
                g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            }
            h /= 6
        }
        out[0] = h; out[1] = s; out[2] = l
        return out
    }

    private fun hueToRgb(p: Double, q: Double, t0: Double): Double {
        val t = wrap01(t0)
        if (t < 1.0 / 6) return p + (q - p) * 6 * t
        if (t < 1.0 / 2) return q
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6
        return p
    }

    private fun hash(x: Int, seed: Int): Int {
        var h = x xor (seed * 374761393)
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    private fun perlinSigned(phase0: Double, seed: Int): Double {
        val phase = safe(phase0)
        val cell = floor(phase)
        val local = phase - cell
        val c = cell.toInt()
        val g0 = if (hash(c, seed) and 1 != 0) 1.0 else -1.0
        val g1 = if (hash(c + 1, seed) and 1 != 0) 1.0 else -1.0
        val n0 = g0 * local
        val n1 = g1 * (local - 1)
        val fade = local * local * local * (local * (local * 6 - 15) + 10)
        return ((n0 + (n1 - n0) * fade) * 2).coerceIn(-1.0, 1.0)
    }

    internal const val PUSH = 0
    internal const val X = 1
    internal const val Y = 2
    internal const val R = 3
    internal const val A = 4
    internal const val DUP = 5
    internal const val RGB = 6
    internal const val HSL = 7
    internal const val SAT = 8
    internal const val HUE = 9
    internal const val LUM = 10
    internal const val ADD = 11
    internal const val SUB = 12
    internal const val MUL = 13
    internal const val DIV = 14
    internal const val EXP = 15
    internal const val LN = 16
    internal const val SIN = 17
    internal const val COS = 18
    internal const val TAN = 19
    internal const val ASIN = 20
    internal const val ACOS = 21
    internal const val ATAN = 22
    internal const val INVERT = 23
    internal const val PERLIN = 24
    internal const val NORMALIZE = 25
    internal const val IF = 26
    internal const val BLEND = 27
    internal const val DOT = 28
    internal const val FBM = 29

    private val WORDS: Map<String, Int> = mapOf(
        "x" to X, "y" to Y, "r" to R, "a" to A, "dup" to DUP, "rgb" to RGB, "hsl" to HSL,
        "sat" to SAT, "hue" to HUE, "lum" to LUM, "+" to ADD, "-" to SUB, "*" to MUL, "/" to DIV,
        "exp" to EXP, "ln" to LN, "sin" to SIN, "cos" to COS, "tan" to TAN, "asin" to ASIN,
        "acos" to ACOS, "atan" to ATAN, "invert" to INVERT, "perlin" to PERLIN,
        "normalize" to NORMALIZE, "if" to IF, "blend" to BLEND, "dot" to DOT, "fbm" to FBM,
    )

    private val DECIMAL = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val TRAILING_ZEROS = Regex("\\.?0+$")
    internal const val TAU = PI * 2
    internal val DIAGONAL = sqrt(0.5)
}

/**
 * A compiled [ColorRpn] program.
 */
class ColorRpnProgram internal constructor(
    /** The text it was compiled from. */
    val source: String,
    /**
     * The tokens it kept, normalised: lower case, numbers as [ColorRpn.format] writes them. Two
     * programs with the same [normalized] draw the same picture.
     */
    val tokens: List<String>,
    /** The tokens it skipped, in the order they appeared. An unknown word is dropped, not an error. */
    val unknown: List<String>,
    private val ops: IntArray,
    private val data: DoubleArray,
) {
    /** [tokens] joined by single spaces. */
    val normalized: String get() = tokens.joinToString(" ")

    private val capacity = ops.size * 2 + 16

    /** A scratch stack for one thread; [color] allocates one per call, [render] one per row. */
    private inner class Stack {
        val type = IntArray(capacity)
        val a = DoubleArray(capacity)
        val b = DoubleArray(capacity)
        val c = DoubleArray(capacity)
        val hsl = DoubleArray(3)
    }

    /** The colour at one point, as `[r, g, b]` in 0..1; [r] and [a] as [ColorRpn] defines them for the point (x, y). */
    fun color(x: Double, y: Double, r: Double = radius(x, y), a: Double = angle(x, y)): DoubleArray =
        evaluate(Stack(), x, y, r, a, DoubleArray(3))

    /**
     * The picture at [size]×[size], ARGB, row 0 at the top, rendered on all cores. Pixel centres
     * sit at `(i + 0.5) / size`, held to single precision as the demo's coordinate arrays hold them.
     */
    fun render(size: Int): IntArray {
        val px = IntArray(size * size)
        IntStream.range(0, size).parallel().forEach { row ->
            val stack = Stack()
            val out = DoubleArray(3)
            val y = (row + 0.5) / size
            for (col in 0 until size) {
                val x = (col + 0.5) / size
                evaluate(stack, x.toFloat().toDouble(), y.toFloat().toDouble(),
                    radius(x, y).toFloat().toDouble(), angle(x, y).toFloat().toDouble(), out)
                val rr = (ColorRpn.clamp01(out[0]) * 255).roundToInt()
                val gg = (ColorRpn.clamp01(out[1]) * 255).roundToInt()
                val bb = (ColorRpn.clamp01(out[2]) * 255).roundToInt()
                px[row * size + col] = (0xff shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        return px
    }

    /** [render] encoded as PNG bytes, which `display(...)` shows as an image. */
    fun png(size: Int): ByteArray = PngEncoder.encode(render(size), size, size)

    /** The program as [normalized], which is what the demo's captions print. */
    override fun toString(): String = normalized

    private fun radius(x: Double, y: Double): Double {
        val dx = x - 0.5
        val dy = y - 0.5
        return min(1.0, sqrt(dx * dx + dy * dy) / ColorRpn.DIAGONAL)
    }

    private fun angle(x: Double, y: Double): Double = ColorRpn.wrap01(atan2(y - 0.5, x - 0.5) / ColorRpn.TAU)

    private fun unary(op: Int, v0: Double): Double {
        val v = ColorRpn.safe(v0)
        return when (op) {
            ColorRpn.EXP -> exp(v.coerceIn(-8.0, 8.0))
            ColorRpn.LN -> ln(max(1e-6, abs(v)))
            ColorRpn.SIN -> sin(v)
            ColorRpn.COS -> cos(v)
            ColorRpn.TAN -> tan(v).coerceIn(-64.0, 64.0)
            ColorRpn.ASIN -> asin(signedUnit(v))
            ColorRpn.ACOS -> acos(signedUnit(v))
            ColorRpn.ATAN -> atan(v)
            ColorRpn.INVERT -> 1 - v
            else -> v
        }
    }

    private fun signedUnit(v: Double): Double = (ColorRpn.wrap01((v + 1) * 0.5) * 2 - 1).coerceIn(-1.0, 1.0)

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun evaluate(s: Stack, x: Double, y: Double, r: Double, a: Double, out: DoubleArray): DoubleArray {
        val type = s.type
        val sa = s.a
        val sb = s.b
        val sc = s.c
        var sp = 0

        // pop into these; an empty stack reads as the number 0.5
        var t0 = 0; var a0 = 0.0; var b0 = 0.0; var c0 = 0.0
        var t1 = 0; var a1 = 0.0; var b1 = 0.0; var c1 = 0.0
        var t2 = 0; var a2 = 0.0; var b2 = 0.0; var c2 = 0.0

        fun push(t: Int, va: Double, vb: Double = 0.0, vc: Double = 0.0) {
            type[sp] = t; sa[sp] = va; sb[sp] = vb; sc[sp] = vc; sp++
        }
        fun pop0(default: Double = 0.5) {
            if (sp > 0) { sp--; t0 = type[sp]; a0 = sa[sp]; b0 = sb[sp]; c0 = sc[sp] } else { t0 = NUM; a0 = default; b0 = 0.0; c0 = 0.0 }
        }
        fun pop1(default: Double = 0.5) {
            if (sp > 0) { sp--; t1 = type[sp]; a1 = sa[sp]; b1 = sb[sp]; c1 = sc[sp] } else { t1 = NUM; a1 = default; b1 = 0.0; c1 = 0.0 }
        }
        fun pop2() {
            if (sp > 0) { sp--; t2 = type[sp]; a2 = sa[sp]; b2 = sb[sp]; c2 = sc[sp] } else { t2 = NUM; a2 = 0.5; b2 = 0.0; c2 = 0.0 }
        }
        /** Pop one value as a number: a colour counts as its luma. */
        fun popNumber(): Double {
            if (sp <= 0) return 0.5
            sp--
            return if (type[sp] == COLOR) ColorRpn.luma(sa[sp], sb[sp], sc[sp]) else sa[sp]
        }

        for (i in ops.indices) {
            val op = ops[i]
            when (op) {
                ColorRpn.PUSH -> push(NUM, data[i])
                ColorRpn.X -> push(NUM, x)
                ColorRpn.Y -> push(NUM, y)
                ColorRpn.R -> push(NUM, r)
                ColorRpn.A -> push(NUM, a)
                ColorRpn.DUP -> {
                    if (sp <= 0) push(NUM, 0.5)
                    push(type[sp - 1], sa[sp - 1], sb[sp - 1], sc[sp - 1])
                }
                ColorRpn.RGB -> {
                    val c = popNumber(); val b = popNumber(); val aa = popNumber()
                    push(COLOR, ColorRpn.wrap01(aa), ColorRpn.wrap01(b), ColorRpn.wrap01(c))
                }
                ColorRpn.HSL -> {
                    val l = popNumber(); val sat = popNumber(); val h = popNumber()
                    ColorRpn.hslToRgb(h, sat, l, out)
                    push(COLOR, out[0], out[1], out[2])
                }
                ColorRpn.SAT, ColorRpn.HUE, ColorRpn.LUM -> {
                    pop1(); pop0()
                    if (t0 == COLOR || t1 == COLOR) {
                        val n: Double
                        if (t0 == COLOR && t1 == COLOR) {
                            n = (ColorRpn.luma(a0, b0, c0) + ColorRpn.luma(a1, b1, c1)) * 0.5
                            a0 = (a0 + a1) * 0.5; b0 = (b0 + b1) * 0.5; c0 = (c0 + c1) * 0.5
                        } else if (t0 == COLOR) {
                            n = a1
                        } else {
                            n = a0; a0 = a1; b0 = b1; c0 = c1
                        }
                        val hsl = ColorRpn.rgbToHsl(a0, b0, c0, s.hsl)
                        when (op) {
                            ColorRpn.HUE -> hsl[0] = ColorRpn.wrap01(n)
                            ColorRpn.SAT -> hsl[1] = ColorRpn.clamp01(n)
                            else -> hsl[2] = ColorRpn.clamp01(n)
                        }
                        ColorRpn.hslToRgb(hsl[0], hsl[1], hsl[2], out)
                        push(COLOR, out[0], out[1], out[2])
                    } else {
                        push(NUM, ColorRpn.safe((a0 + a1) * 0.5))
                    }
                }
                ColorRpn.ADD, ColorRpn.SUB, ColorRpn.MUL, ColorRpn.DIV -> {
                    pop1(); pop0()
                    if (t0 == NUM && t1 == NUM) {
                        val n = when (op) {
                            ColorRpn.ADD -> a0 + a1
                            ColorRpn.SUB -> a0 - a1
                            ColorRpn.MUL -> a0 * a1
                            else -> ColorRpn.safeDiv(a0, a1)
                        }
                        push(NUM, ColorRpn.safe(n))
                    } else {
                        if (t0 == NUM) { b0 = a0; c0 = a0 }
                        if (t1 == NUM) { b1 = a1; c1 = a1 }
                        when (op) {
                            ColorRpn.ADD -> push(COLOR, ColorRpn.wrap01(a0 + a1), ColorRpn.wrap01(b0 + b1), ColorRpn.wrap01(c0 + c1))
                            ColorRpn.SUB -> push(COLOR, ColorRpn.wrap01(abs(a0 - a1)), ColorRpn.wrap01(abs(b0 - b1)), ColorRpn.wrap01(abs(c0 - c1)))
                            ColorRpn.MUL -> push(COLOR, ColorRpn.wrap01(a0 * a1), ColorRpn.wrap01(b0 * b1), ColorRpn.wrap01(c0 * c1))
                            else -> push(COLOR, ColorRpn.wrap01(ColorRpn.safeDiv(a0, a1)), ColorRpn.wrap01(ColorRpn.safeDiv(b0, b1)), ColorRpn.wrap01(ColorRpn.safeDiv(c0, c1)))
                        }
                    }
                }
                ColorRpn.EXP, ColorRpn.LN, ColorRpn.SIN, ColorRpn.COS, ColorRpn.TAN,
                ColorRpn.ASIN, ColorRpn.ACOS, ColorRpn.ATAN, ColorRpn.INVERT -> {
                    pop0()
                    if (t0 == NUM) push(NUM, ColorRpn.safe(unary(op, a0)))
                    else push(COLOR, ColorRpn.wrap01(unary(op, a0)), ColorRpn.wrap01(unary(op, b0)), ColorRpn.wrap01(unary(op, c0)))
                }
                ColorRpn.PERLIN -> {
                    pop0()
                    val seed = data[i].toInt().takeIf { it != 0 } ?: 1
                    if (t0 == NUM) push(NUM, ColorRpn.perlin(a0, seed))
                    else push(COLOR, ColorRpn.perlin(a0, seed), ColorRpn.perlin(b0, seed), ColorRpn.perlin(c0, seed))
                }
                ColorRpn.NORMALIZE -> {
                    pop0()
                    if (t0 == NUM) {
                        push(NUM, if (a0 == 0.0) 0.0 else ColorRpn.safeDiv(a0, abs(a0)))
                    } else {
                        var len = sqrt(a0 * a0 + b0 * b0 + c0 * c0)
                        if (len < 1e-6) len = 1.0
                        push(COLOR, ColorRpn.clamp01(a0 / len), ColorRpn.clamp01(b0 / len), ColorRpn.clamp01(c0 / len))
                    }
                }
                ColorRpn.IF -> {
                    pop2(); pop1(); pop0()
                    val cond = if (t2 == COLOR) ColorRpn.luma(a2, b2, c2) else a2
                    if (cond > 0.5) push(t1, a1, b1, c1) else push(t0, a0, b0, c0)
                }
                ColorRpn.BLEND -> {
                    pop2(); pop1(); pop0()
                    val nums = doubleArrayOf(0.5, 0.5, 0.5)
                    var numCount = 0
                    val col0 = doubleArrayOf(0.5, 0.5, 0.5)
                    val col1 = doubleArrayOf(0.5, 0.5, 0.5)
                    var colorCount = 0
                    fun absorb(t: Int, va: Double, vb: Double, vc: Double) {
                        if (t == COLOR) {
                            val into = if (colorCount == 0) col0 else if (colorCount == 1) col1 else null
                            if (into != null) { into[0] = va; into[1] = vb; into[2] = vc }
                            colorCount++
                        } else {
                            nums[min(numCount, 2)] = va
                            numCount++
                        }
                    }
                    absorb(t0, a0, b0, c0)
                    absorb(t1, a1, b1, c1)
                    absorb(t2, a2, b2, c2)
                    val t = ColorRpn.clamp01(
                        when (numCount) {
                            0 -> 0.5
                            1 -> nums[0]
                            2 -> (nums[0] + nums[1]) * 0.5
                            else -> (nums[0] + nums[1] + nums[2]) / 3
                        }
                    )
                    if (colorCount == 0) {
                        val g0 = ColorRpn.wrap01(nums[0])
                        val g1 = ColorRpn.wrap01(if (numCount > 1) nums[1] else nums[0])
                        col0.fill(g0); col1.fill(g1)
                    } else if (colorCount == 1) {
                        col1.fill(ColorRpn.wrap01(if (numCount > 0) nums[0] else 0.5))
                    }
                    push(
                        COLOR,
                        ColorRpn.clamp01(col0[0] + (col1[0] - col0[0]) * t),
                        ColorRpn.clamp01(col0[1] + (col1[1] - col0[1]) * t),
                        ColorRpn.clamp01(col0[2] + (col1[2] - col0[2]) * t),
                    )
                }
                ColorRpn.DOT -> {
                    pop1(); pop0()
                    if (t0 == NUM && t1 == NUM) {
                        push(NUM, ColorRpn.safe(a0 * a1))
                    } else {
                        if (t0 == NUM) { b0 = a0; c0 = a0 }
                        if (t1 == NUM) { b1 = a1; c1 = a1 }
                        push(NUM, ColorRpn.safe(a0 * a1 + b0 * b1 + c0 * c1))
                    }
                }
                ColorRpn.FBM -> {
                    pop1(default = 2.0); pop0()
                    val seed = data[i].toInt().takeIf { it != 0 } ?: 1
                    if (t0 == NUM && t1 == NUM) {
                        push(NUM, ColorRpn.fbm(a0, a1, seed))
                    } else {
                        if (t0 == NUM) { b0 = a0; c0 = a0 }
                        if (t1 == NUM) { b1 = a1; c1 = a1 }
                        push(COLOR, ColorRpn.fbm(a0, a1, seed), ColorRpn.fbm(b0, b1, seed), ColorRpn.fbm(c0, c1, seed))
                    }
                }
            }
        }
        return finish(s, sp, out)
    }

    /** What is left on the stack, as a colour: the recovery rules in [ColorRpn]. */
    private fun finish(s: Stack, sp: Int, out: DoubleArray): DoubleArray {
        if (sp <= 0) {
            out.fill(0.5)
            return out
        }
        if (s.type[sp - 1] == COLOR) {
            out[0] = ColorRpn.clamp01(s.a[sp - 1]); out[1] = ColorRpn.clamp01(s.b[sp - 1]); out[2] = ColorRpn.clamp01(s.c[sp - 1])
            return out
        }
        var firstNum: Double? = null
        var secondNum: Double? = null
        var firstColor = -1
        var secondColor = -1
        for (i in sp - 1 downTo 0) {
            if (s.type[i] == COLOR) {
                if (firstColor < 0) firstColor = i else if (secondColor < 0) secondColor = i
            } else {
                if (firstNum == null) firstNum = s.a[i] else if (secondNum == null) secondNum = s.a[i]
            }
            if (secondColor >= 0 && secondNum != null) break
        }
        if (firstColor >= 0 && secondColor >= 0) {
            out[0] = ColorRpn.clamp01((s.a[firstColor] + s.a[secondColor]) * 0.5)
            out[1] = ColorRpn.clamp01((s.b[firstColor] + s.b[secondColor]) * 0.5)
            out[2] = ColorRpn.clamp01((s.c[firstColor] + s.c[secondColor]) * 0.5)
            return out
        }
        if (firstColor >= 0 && firstNum != null) {
            val g = ColorRpn.wrap01(firstNum)
            out[0] = ColorRpn.clamp01(s.a[firstColor] * 0.7 + g * 0.3)
            out[1] = ColorRpn.clamp01(s.b[firstColor] * 0.7 + g * 0.3)
            out[2] = ColorRpn.clamp01(s.c[firstColor] * 0.7 + g * 0.3)
            return out
        }
        if (firstNum != null && secondNum != null) return numberPair(firstNum, secondNum, out)
        out.fill(ColorRpn.wrap01(firstNum ?: 0.5))
        return out
    }

    /** Two numbers as a colour: their mean picks between orange and blue, their difference lifts it. */
    private fun numberPair(a: Double, b: Double, out: DoubleArray): DoubleArray {
        val mix = ColorRpn.wrap01((a + b) * 0.5)
        val lift = 0.32 + ColorRpn.wrap01(abs(a - b)) * 0.26
        out[0] = ColorRpn.clamp01(lift * 0.28 + (0.94 + (0.16 - 0.94) * mix) * 0.72)
        out[1] = ColorRpn.clamp01(lift * 0.28 + (0.53 + (0.42 - 0.53) * mix) * 0.72)
        out[2] = ColorRpn.clamp01(lift * 0.28 + (0.14 + (0.94 - 0.14) * mix) * 0.72)
        return out
    }

    private companion object {
        const val NUM = 0
        const val COLOR = 1
    }
}

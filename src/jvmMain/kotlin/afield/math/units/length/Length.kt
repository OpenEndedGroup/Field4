@file:afield.runtime.completion.CompletionCategory("Math")

package afield.math.units.length

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Typed lengths: `12.7.mm`, `0.5.inch`, `4.ft` and `3.m` are all a [Length], which reads back in
 * any of those units. Write a sketch in inches and draw it in millimetres without multiplying by
 * 25.4 by hand.
 *
 * Nothing here is in a box's default imports, because `.m` and `.ft` on every number would surprise
 * code that does not want them. A box opts in with `import afield.math.units.length.*`.
 *
 * - Build one with `.mm`, `.cm`, `.m`, `.inch`, `.ft` or `.yd` on any number (`.inch`, since `in`
 *   is a Kotlin keyword), or with [LengthUnit.of].
 * - Read it back with [Length.mm], [Length.cm], [Length.m], [Length.inches], [Length.feet] and
 *   [Length.yards], all `Double`s.
 * - Add and subtract lengths, scale them by numbers, and divide one length by another for a plain
 *   ratio. They compare with `<` and sort; [abs], [min], [max] and [sum] work as for numbers.
 * - [Length.format] writes `1 250 mm` or `1.25 m`; [Length.formatFeetInches] writes `4' 6 1/2"`;
 *   [Length.formatInches] writes `3/8"`. [Length.parse] reads all of those back, and more.
 * - [Length.near] compares two lengths to a tolerance, which is what to use after arithmetic.
 *
 * A length is a millimetre count in a `Double` (an inline value class, so no allocation). Unit
 * conversions are exact rationals, 1 in = 127/5 mm, 1 ft = 1524/5 mm, 1 yd = 4572/5 mm, applied to
 * the number as it is written in decimal and rounded once. So `12.inch == 1.ft`,
 * `0.5.inch == 12.7.mm`, `1.234.m == 1234.mm`, `0.1.inch.mm == 2.54` and `3.inch.inches == 3.0`
 * all hold; with a plain `* 25.4` and `/ 25.4`, 3 inches comes back as 2.9999999999999996. A
 * conversion that is not a whole number costs a decimal read, about 0.2 µs. Sums and products are
 * ordinary `Double` arithmetic and round the way doubles do, so compare their results with
 * [Length.near].
 */

/**
 * A unit a [Length] can be written in, formatted in and parsed from. [symbol] is what [Length.format]
 * prints after the number.
 */
enum class LengthUnit(
    /** The short name [Length.format] prints: `mm`, `cm`, `m`, `in`, `ft` or `yd`. */
    val symbol: String,
    private val numerator: Int,
    private val denominator: Int,
) {
    /** Millimetres, the unit a [Length] is kept in. */
    MM("mm", 1, 1),
    /** Centimetres, 10 mm. */
    CM("cm", 10, 1),
    /** Metres, 1000 mm. */
    M("m", 1000, 1),
    /** Inches, exactly 25.4 mm. */
    IN("in", 127, 5),
    /** Feet, 12 inches, exactly 304.8 mm. */
    FT("ft", 1524, 5),
    /** Yards, 3 feet, exactly 914.4 mm. */
    YD("yd", 4572, 5);

    /** [value] of this unit as a [Length]: `LengthUnit.IN.of(3.0)` is `3.inch`. */
    fun of(value: Double): Length = Length(scaleExactly(value, numerator, denominator))

    /** How many of this unit [length] is: `LengthUnit.FT.measure(18.inch)` is 1.5. */
    fun measure(length: Length): Double = scaleExactly(length.mm, denominator, numerator)

    internal fun ofDecimal(value: BigDecimal): Length =
        Length(value.multiply(BigDecimal(numerator)).divide(BigDecimal(denominator), WIDE).toDouble())
}

/**
 * A length, kept in millimetres. Build one with `.mm`, `.cm`, `.m`, `.inch`, `.ft` or `.yd` on a
 * number (`import afield.math.units.length.*`); there is deliberately no public constructor, so a
 * bare number never becomes a length without saying its unit.
 */
@JvmInline
value class Length internal constructor(
    /** This length in millimetres. */
    val mm: Double,
) : Comparable<Length> {

    /** This length in centimetres. */
    val cm: Double get() = LengthUnit.CM.measure(this)

    /** This length in metres. */
    val m: Double get() = LengthUnit.M.measure(this)

    /** This length in inches. */
    val inches: Double get() = LengthUnit.IN.measure(this)

    /** This length in feet. */
    val feet: Double get() = LengthUnit.FT.measure(this)

    /** This length in yards. */
    val yards: Double get() = LengthUnit.YD.measure(this)

    /** This length as a count of [unit]; `x.valueIn(LengthUnit.IN)` is `x.inches`. */
    fun valueIn(unit: LengthUnit): Double = unit.measure(this)

    /** The sum of two lengths. */
    operator fun plus(other: Length): Length = Length(mm + other.mm)

    /** The difference of two lengths. */
    operator fun minus(other: Length): Length = Length(mm - other.mm)

    /** This length pointing the other way. `-(0.mm)` is `0.mm`, not a negative zero. */
    operator fun unaryMinus(): Length = Length(0.0 - mm)

    /** This length, unchanged. */
    operator fun unaryPlus(): Length = this

    /** This length scaled by [k]. */
    operator fun times(k: Double): Length = Length(mm * k)

    /** This length scaled by [k]. */
    operator fun times(k: Int): Length = Length(mm * k)

    /** This length divided by [k]. */
    operator fun div(k: Double): Length = Length(mm / k)

    /** This length divided by [k]. */
    operator fun div(k: Int): Length = Length(mm / k)

    /** How many [other]s fit in this length, as a plain number: `(6.ft / 18.inch)` is 4.0. */
    operator fun div(other: Length): Double = mm / other.mm

    /** Orders by size; `0.mm` and `-(0.0).mm` compare equal. */
    override fun compareTo(other: Length): Int = when {
        mm < other.mm -> -1
        mm > other.mm -> 1
        mm == other.mm -> 0
        else -> mm.compareTo(other.mm) // a NaN: sorts last, as Double does
    }

    /**
     * Whether this length is within [tolerance] of [other]. Use it, not `==`, on the results of
     * arithmetic: `(11.9375.inch + 0.0625.inch).near(1.ft)` is true, while `==` depends on the last
     * bit of a `Double`. The default, a millionth of a millimetre, is far above that rounding for
     * anything up to kilometres and far below anything a tool can cut.
     */
    fun near(other: Length, tolerance: Length = Length(1e-6)): Boolean =
        kotlin.math.abs(mm - other.mm) <= tolerance.mm

    /** True unless this length is infinite or NaN. */
    val isFinite: Boolean get() = mm.isFinite()

    /**
     * This length as a decimal number of [unit]: `1250.mm.format()` is `1 250 mm`,
     * `1250.mm.format(LengthUnit.M)` is `1.25 m`.
     *
     * [decimals] is the number of places kept, rounding halves away from zero. With [trimZeros]
     * (the default) trailing zeros after the point are dropped, so `format(decimals = 2)` prints
     * `1.5 m` rather than `1.50 m`; pass `trimZeros = false` for a fixed number of places. Digits
     * before the point are grouped in threes with [groupSeparator] (a space; `""` turns grouping
     * off). An infinite or NaN length prints as `Infinity mm` or `NaN mm`.
     */
    fun format(
        unit: LengthUnit = LengthUnit.MM,
        decimals: Int = 3,
        trimZeros: Boolean = true,
        groupSeparator: String = " ",
    ): String {
        require(decimals >= 0) { "Length.format: decimals must be 0 or more, not $decimals" }
        if (!mm.isFinite()) return "$mm ${unit.symbol}"
        var rounded = BigDecimal.valueOf(valueIn(unit)).setScale(decimals, RoundingMode.HALF_UP)
        if (trimZeros) rounded = rounded.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        val text = rounded.toPlainString() // BigDecimal has no negative zero, so -0.0001 mm prints 0 mm
        val negative = text.startsWith("-")
        val digits = text.removePrefix("-")
        val whole = digits.substringBefore('.')
        val fraction = digits.substringAfter('.', "")
        val grouped = if (groupSeparator.isEmpty()) whole else whole.reversed().chunked(3).joinToString(groupSeparator).reversed()
        return buildString {
            if (negative) append('-')
            append(grouped)
            if (fraction.isNotEmpty()) append('.').append(fraction)
            append(' ').append(unit.symbol)
        }
    }

    /**
     * This length in feet and inches, the way a dimension is written on a drawing:
     * `54.5.inch.formatFeetInches()` is `4' 6 1/2"`, and `0.1875.inch` is `0' 3/16"`.
     *
     * The inches are rounded to the nearest 1/[denominator] (16ths by default; halves away from
     * zero), and the rounding carries, so `11.99.inch` is `1' 0"`, not `0' 12"`. Fractions are
     * reduced (`8/16` prints as `1/2`). A negative length prints with one leading minus, `-4' 6 1/2"`,
     * and one that rounds to nothing prints `0' 0"`. Infinite and NaN lengths print as [format] does.
     */
    fun formatFeetInches(denominator: Int = 16): String {
        val (negative, units) = roundedInchUnits(denominator) ?: return format()
        val perFoot = 12L * denominator
        val sign = if (negative && units > 0) "-" else ""
        return "$sign${units / perFoot}' ${inchesText(units % perFoot, denominator)}\""
    }

    /**
     * This length in inches with a fraction, for parts measured in inches only:
     * `0.375.inch.formatInches()` is `3/8"`, `54.5.inch` is `54 1/2"`. Rounding is as in
     * [formatFeetInches].
     */
    fun formatInches(denominator: Int = 16): String {
        val (negative, units) = roundedInchUnits(denominator) ?: return format()
        val sign = if (negative && units > 0) "-" else ""
        return "$sign${inchesText(units, denominator)}\""
    }

    private fun roundedInchUnits(denominator: Int): Pair<Boolean, Long>? {
        require(denominator >= 1) { "Length: the inch denominator must be 1 or more, not $denominator" }
        if (!mm.isFinite()) return null
        val inches = this.inches
        return (inches < 0) to Math.round(kotlin.math.abs(inches) * denominator)
    }

    /** Prints as [format] does with its defaults: `12.7 mm`, `1 250 mm`. */
    override fun toString(): String = format()

    companion object {
        /** No length at all. */
        val ZERO: Length = Length(0.0)

        /**
         * Reads a length written the way people write them. Examples that parse:
         *
         * - metric: `1250 mm`, `1 250 mm`, `1,250mm`, `1.25m`, `125 cm`, `-3.5 mm`, `2 metres`;
         * - inches: `6 in`, `6"`, `6 inches`, `3/8"`, `6 1/2"`, `6-1/2 in`, `0.375 in`;
         * - feet and inches: `4'`, `4 ft`, `4' 6 1/2"`, `4'-6 1/2"`, `4' 6`, `4ft 6in`, `-1' 0"`;
         * - yards: `2 yd`.
         *
         * Units are case-insensitive, may be spelled out, and may sit against the number. Typographic
         * primes and curly quotes (`′ ″ ’ ”`) count as `'` and `"`. Spaces, commas and underscores
         * may group digits in threes. Feet may be followed by inches, with or without their `"`;
         * no other two units combine. A number with no unit is an error unless [defaultUnit] says
         * what it is.
         *
         * The decimal you write is converted exactly and rounded once, so `parse("1.234 m")` is
         * exactly `1234.mm`, and anything [format], [formatFeetInches] or [formatInches] prints
         * reads back as the length it shows.
         *
         * @throws IllegalArgumentException naming [text] and the part of it that could not be read.
         */
        fun parse(text: String, defaultUnit: LengthUnit? = null): Length = LengthParser(text, defaultUnit).parse()

        /** [parse], but null where [parse] would throw. */
        fun parseOrNull(text: String, defaultUnit: LengthUnit? = null): Length? =
            try { parse(text, defaultUnit) } catch (_: IllegalArgumentException) { null }
    }
}

/** [this] millimetres. */
val Int.mm: Length get() = LengthUnit.MM.of(toDouble())
/** [this] millimetres. */
val Double.mm: Length get() = LengthUnit.MM.of(this)
/** [this] millimetres. */
val Number.mm: Length get() = LengthUnit.MM.of(toDouble())

/** [this] centimetres. */
val Int.cm: Length get() = LengthUnit.CM.of(toDouble())
/** [this] centimetres. */
val Double.cm: Length get() = LengthUnit.CM.of(this)
/** [this] centimetres. */
val Number.cm: Length get() = LengthUnit.CM.of(toDouble())

/** [this] metres. */
val Int.m: Length get() = LengthUnit.M.of(toDouble())
/** [this] metres. */
val Double.m: Length get() = LengthUnit.M.of(this)
/** [this] metres. */
val Number.m: Length get() = LengthUnit.M.of(toDouble())

/** [this] inches, exactly 25.4 mm each. Spelled `inch` because `in` is a Kotlin keyword. */
val Int.inch: Length get() = LengthUnit.IN.of(toDouble())
/** [this] inches, exactly 25.4 mm each. Spelled `inch` because `in` is a Kotlin keyword. */
val Double.inch: Length get() = LengthUnit.IN.of(this)
/** [this] inches, exactly 25.4 mm each. Spelled `inch` because `in` is a Kotlin keyword. */
val Number.inch: Length get() = LengthUnit.IN.of(toDouble())

/** [this] feet, exactly 304.8 mm each. */
val Int.ft: Length get() = LengthUnit.FT.of(toDouble())
/** [this] feet, exactly 304.8 mm each. */
val Double.ft: Length get() = LengthUnit.FT.of(this)
/** [this] feet, exactly 304.8 mm each. */
val Number.ft: Length get() = LengthUnit.FT.of(toDouble())

/** [this] yards, exactly 914.4 mm each. */
val Int.yd: Length get() = LengthUnit.YD.of(toDouble())
/** [this] yards, exactly 914.4 mm each. */
val Double.yd: Length get() = LengthUnit.YD.of(this)
/** [this] yards, exactly 914.4 mm each. */
val Number.yd: Length get() = LengthUnit.YD.of(toDouble())

/** [length] scaled by this number: `3 * 18.inch` is `54.inch`. */
operator fun Double.times(length: Length): Length = length * this

/** [length] scaled by this number: `3 * 18.inch` is `54.inch`. */
operator fun Int.times(length: Length): Length = length * this

/** The size of [x], whichever way it points. */
fun abs(x: Length): Length = Length(kotlin.math.abs(x.mm))

/** The shorter of [a] and [b]. */
fun min(a: Length, b: Length): Length = Length(kotlin.math.min(a.mm, b.mm))

/** The longer of [a] and [b]. */
fun max(a: Length, b: Length): Length = Length(kotlin.math.max(a.mm, b.mm))

/** The total of these lengths; an empty collection sums to [Length.ZERO]. */
@JvmName("sumOfLengths")
fun Iterable<Length>.sum(): Length {
    var total = 0.0
    for (length in this) total += length.mm
    return Length(total)
}

// ── Exact conversion ────────────────────────────────────────────────────────────────────────────

private val WIDE = MathContext(40)

/** An integer below this times any unit factor's numerator is still an exact `Double`. */
private const val EXACT_INTEGER_LIMIT = 1e9

/** 2^53: every integer below it is an exact `Double`. */
private const val EXACT_LONG_LIMIT = 1L shl 53

private val POWERS_OF_TEN = LongArray(13).also { p -> p[0] = 1; for (i in 1 until p.size) p[i] = p[i - 1] * 10 }

/**
 * `x * numerator / denominator`, with `x` read as the decimal it prints as and the result rounded
 * once.
 *
 * Integers multiply exactly in a `Double`, so one division rounds them correctly. A decimal
 * `digits / 10^scale` with few enough digits becomes the ratio of two integers that are exact
 * `Double`s, and IEEE division of exact operands is correctly rounded: the same answer as
 * `BigDecimal`, for the cost of reading the decimal. Anything longer goes through `BigDecimal`.
 */
private fun scaleExactly(x: Double, numerator: Int, denominator: Int): Double {
    if (numerator == denominator) return x
    if (!x.isFinite()) return x * numerator / denominator
    if (x == kotlin.math.floor(x) && kotlin.math.abs(x) < EXACT_INTEGER_LIMIT) return x * numerator / denominator
    val decimal = BigDecimal.valueOf(x)
    val scale = decimal.scale()
    if (scale in 1 until POWERS_OF_TEN.size && decimal.precision() <= 15) {
        val top = decimal.unscaledValue().toLong() * numerator
        val bottom = POWERS_OF_TEN[scale] * denominator
        if (kotlin.math.abs(top) < EXACT_LONG_LIMIT && bottom < EXACT_LONG_LIMIT) return top.toDouble() / bottom.toDouble()
    }
    return decimal.multiply(BigDecimal(numerator)).divide(BigDecimal(denominator), WIDE).toDouble()
}

// ── Inch fractions ──────────────────────────────────────────────────────────────────────────────

private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

/** [units] 1/[denominator]-inch units as `6 1/2`, `3/16` or `6`, with no inch mark. */
private fun inchesText(units: Long, denominator: Int): String {
    val whole = units / denominator
    val numerator = units % denominator
    if (numerator == 0L) return "$whole"
    val g = gcd(numerator, denominator.toLong())
    val fraction = "${numerator / g}/${denominator / g}"
    return if (whole == 0L) fraction else "$whole $fraction"
}

// ── Parsing ─────────────────────────────────────────────────────────────────────────────────────

/** The spellings each unit accepts, longest first so `mm` wins over `m`. */
private val UNIT_WORDS: List<Pair<String, LengthUnit>> = listOf(
    "millimetres" to LengthUnit.MM, "millimeters" to LengthUnit.MM, "millimetre" to LengthUnit.MM,
    "millimeter" to LengthUnit.MM, "mm" to LengthUnit.MM,
    "centimetres" to LengthUnit.CM, "centimeters" to LengthUnit.CM, "centimetre" to LengthUnit.CM,
    "centimeter" to LengthUnit.CM, "cm" to LengthUnit.CM,
    "metres" to LengthUnit.M, "meters" to LengthUnit.M, "metre" to LengthUnit.M, "meter" to LengthUnit.M,
    "m" to LengthUnit.M,
    "inches" to LengthUnit.IN, "inch" to LengthUnit.IN, "in" to LengthUnit.IN, "\"" to LengthUnit.IN,
    "feet" to LengthUnit.FT, "foot" to LengthUnit.FT, "ft" to LengthUnit.FT, "'" to LengthUnit.FT,
    "yards" to LengthUnit.YD, "yard" to LengthUnit.YD, "yd" to LengthUnit.YD,
).sortedByDescending { it.first.length }

private class LengthParser(private val original: String, private val defaultUnit: LengthUnit?) {
    private val s: String = buildString {
        for (c in original) append(
            when (c) {
                '′', '’', '‘' -> '\''
                '″', '“', '”' -> '"'
                '−', '–' -> '-'
                ' ', ' ', ' ', ' ', '\t' -> ' '
                else -> c
            },
        )
    }.replace("''", "\"")
    private var pos = 0

    private fun fail(reason: String): Nothing =
        throw IllegalArgumentException("Length.parse: cannot read \"$original\": $reason")

    private fun failHere(expected: String): Nothing =
        if (pos >= s.length) fail("it ends where $expected should be")
        else fail("expected $expected at \"${s.substring(pos)}\"")

    private fun skipSpaces() { while (pos < s.length && s[pos] == ' ') pos++ }
    private fun digitAt(i: Int) = i < s.length && s[i] in '0'..'9'
    private fun digitsFrom(i: Int): Int { var j = i; while (digitAt(j)) j++; return j }

    fun parse(): Length {
        skipSpaces()
        if (pos >= s.length) fail("there is nothing to read")
        var negative = false
        if (s[pos] == '-' || s[pos] == '+') { negative = s[pos] == '-'; pos++; skipSpaces() }

        val first = number()
        skipSpaces()
        val firstUnit = unit()
        skipSpaces()
        if (pos >= s.length) {
            val u = firstUnit ?: defaultUnit
                ?: fail("\"${first.toPlainString()}\" has no unit (write mm, cm, m, in, ft or yd after it)")
            return u.ofDecimal(if (negative) first.negate() else first)
        }

        // A second term: only feet followed by inches.
        if (firstUnit != LengthUnit.FT) {
            if (firstUnit == null) failHere("a unit")
            fail("only feet can be followed by inches, and \"${s.substring(pos)}\" follows ${firstUnit.symbol}")
        }
        if (s[pos] == '-') { pos++; skipSpaces() } // 4'-6"
        val second = number()
        skipSpaces()
        val secondUnit = unit()
        if (secondUnit != null && secondUnit != LengthUnit.IN) fail("feet can only be followed by inches, not ${secondUnit.symbol}")
        skipSpaces()
        if (pos < s.length) fail("unexpected \"${s.substring(pos)}\" after the inches")
        val inches = first.multiply(BigDecimal(12)).add(second)
        return LengthUnit.IN.ofDecimal(if (negative) inches.negate() else inches)
    }

    /** A decimal, a fraction `3/8`, or a whole number and a fraction, `6 1/2` or `6-1/2`. */
    private fun number(): BigDecimal {
        fraction()?.let { return it }
        val start = pos
        val text = StringBuilder()
        if (digitAt(pos)) {
            val end = digitsFrom(pos)
            text.append(s, pos, end)
            pos = end
            // Groups of exactly three digits after a space, comma or underscore: 1 250, 1,250, 1_250.
            // One followed by a `/` is the numerator of a mixed fraction (6 125/256), not a group.
            if (end - start <= 3) {
                while (pos < s.length && s[pos] in " ,_" &&
                    digitAt(pos + 1) && digitAt(pos + 2) && digitAt(pos + 3) &&
                    !digitAt(pos + 4) && (pos + 4 >= s.length || s[pos + 4] != '/')
                ) {
                    text.append(s, pos + 1, pos + 4)
                    pos += 4
                }
            }
        }
        var isWhole = true
        if (pos < s.length && s[pos] == '.' && digitAt(pos + 1)) {
            val end = digitsFrom(pos + 1)
            text.append(s, pos, end)
            pos = end
            isWhole = false
        }
        if (text.isEmpty()) failHere("a number")
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            var j = pos + 1
            if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
            if (digitAt(j)) {
                val end = digitsFrom(j)
                text.append(s, pos, end)
                pos = end
                isWhole = false
            }
        }
        val value = BigDecimal(text.toString())
        if (isWhole) {
            // A mixed number: the whole part, then a space or a hyphen, then a fraction.
            val save = pos
            skipSpaces()
            if (pos < s.length && s[pos] == '-') { pos++; skipSpaces() }
            val fraction = fraction()
            if (fraction != null) return value.add(fraction)
            pos = save
        }
        return value
    }

    /** `3/8` at the cursor, consumed; null (and nothing consumed) when there is none. */
    private fun fraction(): BigDecimal? {
        if (!digitAt(pos)) return null
        val numeratorEnd = digitsFrom(pos)
        if (numeratorEnd >= s.length || s[numeratorEnd] != '/' || !digitAt(numeratorEnd + 1)) return null
        val denominatorEnd = digitsFrom(numeratorEnd + 1)
        val numerator = BigDecimal(s.substring(pos, numeratorEnd))
        val denominator = BigDecimal(s.substring(numeratorEnd + 1, denominatorEnd))
        if (denominator.signum() == 0) fail("\"${s.substring(pos, denominatorEnd)}\" divides by zero")
        pos = denominatorEnd
        return numerator.divide(denominator, WIDE)
    }

    /** A unit at the cursor, consumed; null when there is none. A word unit must end the word. */
    private fun unit(): LengthUnit? {
        for ((word, unit) in UNIT_WORDS) {
            if (!s.regionMatches(pos, word, 0, word.length, ignoreCase = true)) continue
            val end = pos + word.length
            if (word[0].isLetter() && end < s.length && s[end].isLetter()) continue
            pos = end
            return unit
        }
        if (pos < s.length && s[pos].isLetter()) {
            val end = (pos until s.length).firstOrNull { !s[it].isLetter() } ?: s.length
            fail("\"${s.substring(pos, end)}\" is not a unit (use mm, cm, m, in, ft or yd)")
        }
        return null
    }
}

@file:Suppress("unused")

package afield.stdlib

import eu.sirotin.kotunil.core.*

/**
 * A numeric value with tracked physical units.
 *
 * Wraps a KotUniL [Expression] (which normalizes to SI internally) and remembers
 * the user's preferred display unit so that `3 | mm` displays as "3 mm" rather
 * than "0.003 m".
 *
 * Arithmetic follows standard dimensional-analysis rules:
 * - Addition / subtraction require compatible dimensions; the left operand's
 *   display unit is preserved.
 * - Multiplication / division produce compound dimensions; display falls back
 *   to SI symbols (e.g. "m2", "kg/m2").
 */
class Quantity(
    val expression: Expression,
    val displayUnit: Expression? = null
) : Comparable<Quantity> {

    /** The numeric value expressed in the display unit (or SI if none). */
    val displayValue: Double
        get() = if (displayUnit != null && displayUnit.value != 0.0)
            expression.value / displayUnit.value
        else
            expression.value

    /** The unit symbol string for display purposes. */
    val displaySymbol: String
        get() = (displayUnit ?: expression).unitSymbols().trim()

    /** The raw SI-normalized numeric value. */
    val value: Double get() = expression.value

    /** The SI unit symbol string. */
    val unitSymbols: String get() = expression.unitSymbols()

    // ── Arithmetic ──────────────────────────────────────────────────────

    operator fun plus(other: Quantity): Quantity =
        Quantity(expression + other.expression, displayUnit ?: other.displayUnit)

    operator fun minus(other: Quantity): Quantity =
        Quantity(expression - other.expression, displayUnit ?: other.displayUnit)

    operator fun times(other: Quantity): Quantity =
        Quantity(expression * other.expression)

    operator fun div(other: Quantity): Quantity =
        Quantity(expression / other.expression)

    operator fun rem(other: Quantity): Quantity =
        Quantity(expression % other.expression)

    operator fun plus(other: Number): Quantity =
        Quantity(expression + Expression(other.toDouble(), expression.dimensions), displayUnit)

    operator fun minus(other: Number): Quantity =
        Quantity(expression - Expression(other.toDouble(), expression.dimensions), displayUnit)

    operator fun times(other: Number): Quantity =
        Quantity(expression * other, displayUnit)

    operator fun div(other: Number): Quantity =
        Quantity(expression / other, displayUnit)

    operator fun rem(other: Number): Quantity =
        Quantity(expression % Expression(other.toDouble(), expression.dimensions))

    operator fun plus(other: Expression): Quantity =
        Quantity(expression + other, displayUnit)

    operator fun minus(other: Expression): Quantity =
        Quantity(expression - other, displayUnit)

    operator fun times(other: Expression): Quantity =
        Quantity(expression * other)

    operator fun div(other: Expression): Quantity =
        Quantity(expression / other)

    operator fun rem(other: Expression): Quantity =
        Quantity(expression % other)

    operator fun unaryMinus(): Quantity =
        Quantity(-expression, displayUnit)

    operator fun unaryPlus(): Quantity = this

    // ── Comparison ──────────────────────────────────────────────────────

    override fun compareTo(other: Quantity): Int =
        expression.compareTo(other.expression)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Quantity) return false
        return expression == other.expression
    }

    override fun hashCode(): Int = expression.hashCode()

    override fun toString(): String {
        val sym = displaySymbol
        return if (sym.isNotEmpty()) "$displayValue $sym" else "$displayValue"
    }

    /** Convert to a different (compatible) display unit. */
    fun `in`(unit: Expression): Quantity = Quantity(expression, unit)

    /** Convert to a different (compatible) display unit (alias). */
    fun to(unit: Expression): Quantity = Quantity(expression, unit)
}

// ── withUnit: powers the  `| unit`  spreadsheet syntax ──────────────────

fun Number.withUnit(unit: Expression): Quantity =
    Quantity(this.toDouble() * unit, displayUnit = unit)

fun Expression.withUnit(unit: Expression): Quantity =
    Quantity(this, displayUnit = unit)

fun Quantity.withUnit(unit: Expression): Quantity =
    Quantity(this.expression, displayUnit = unit)

fun Any?.withUnit(unit: Expression): Quantity {
    val numeric = when (val v = this) {
        null -> 0.0
        is Quantity -> return v.withUnit(unit)
        is Expression -> return v.withUnit(unit)
        is Number -> v.toDouble()
        else -> v.toString().toDoubleOrNull() ?: error("Cannot apply unit '${unit.unitSymbols()}' to ${v::class.simpleName}")
    }
    return numeric.withUnit(unit)
}

// ── Reverse operators: Number op Quantity ────────────────────────────────

operator fun Number.plus(q: Quantity): Quantity =
    Quantity(Expression(this.toDouble(), q.expression.dimensions) + q.expression, q.displayUnit)

operator fun Number.minus(q: Quantity): Quantity =
    Quantity(Expression(this.toDouble(), q.expression.dimensions) - q.expression, q.displayUnit)

operator fun Number.times(q: Quantity): Quantity =
    Quantity(this * q.expression, q.displayUnit)

operator fun Number.div(q: Quantity): Quantity =
    Quantity(this / q.expression)

// ── Reverse operators: Expression op Quantity ───────────────────────────

operator fun Expression.plus(q: Quantity): Quantity =
    Quantity(this + q.expression, q.displayUnit)

operator fun Expression.minus(q: Quantity): Quantity =
    Quantity(this - q.expression, q.displayUnit)

operator fun Expression.times(q: Quantity): Quantity =
    Quantity(this * q.expression)

operator fun Expression.div(q: Quantity): Quantity =
    Quantity(this / q.expression)

// ── Auto-scaling for human-readable display ─────────────────────────────

/**
 * SI prefix table: exponent → symbol.
 * Sorted by descending magnitude so we try the largest scale first.
 */
private val SI_PREFIXES = listOf(
    30 to "Q", 27 to "R", 24 to "Y", 21 to "Z",
    18 to "E", 15 to "P", 12 to "T", 9 to "G", 6 to "M", 3 to "k",
    2 to "h", 1 to "da",
    0 to "",
    -1 to "d", -2 to "c", -3 to "m",
    -6 to "μ", -9 to "n", -12 to "p", -15 to "f", -18 to "a",
    -21 to "z", -24 to "y", -27 to "r", -30 to "q"
)

/**
 * Returns a pair of (scaled value, unit string) that picks the SI prefix
 * making the number easiest to read.
 *
 * For single-factor dimensions (just "m", just "s", etc.) this finds the
 * prefix where the number lands between 1 and 9999.  For compound dimensions
 * (m/s, kg·m/s², etc.) it returns the value and symbols unchanged.
 */
fun autoScaleExpression(expr: Expression): Pair<Double, String> {
    val factors = expr.dimensions.factors
    if (factors.size != 1) return expr.value to expr.unitSymbols().trim()
    val factor = factors.first()
    val power = factor.powerValue
    if (power == 0.0) return expr.value to ""
    val baseSymbol = factor.specification.unitSymbol
    val absValue = kotlin.math.abs(expr.value)
    if (absValue == 0.0) return 0.0 to formatDimSymbol(baseSymbol, power)

    // kg is special: SI base is kg but prefix attaches to gram
    val isKg = baseSymbol == "kg"
    val (searchValue, searchSymbol) = if (isKg)
        (expr.value * Math.pow(1000.0, power)) to "g"
    else
        expr.value to baseSymbol

    var bestExp = 0
    var bestPrefix = ""
    var bestScaled = kotlin.math.abs(searchValue)
    for ((exp, prefix) in SI_PREFIXES) {
        val scale = Math.pow(10.0, -exp.toDouble() * power)
        val scaled = kotlin.math.abs(searchValue * scale)
        if (scaled >= 1.0 && scaled < bestScaled) {
            bestScaled = scaled
            bestExp = exp
            bestPrefix = prefix
        }
        // also accept if nothing better and we're in a reasonable range
        if (scaled in 0.001..9999.0 && bestScaled !in 1.0..9999.0) {
            bestScaled = scaled
            bestExp = exp
            bestPrefix = prefix
        }
    }

    val scaledValue = searchValue * Math.pow(10.0, -bestExp.toDouble() * power)
    val sym = formatDimSymbol("$bestPrefix$searchSymbol", power)
    return scaledValue to sym
}

private fun formatDimSymbol(symbol: String, power: Double): String {
    val intPow = power.toInt()
    return if (intPow == 1) symbol
    else if (kotlin.math.abs(power - intPow) < 1e-9) "$symbol$intPow"
    else "$symbol^$power"
}

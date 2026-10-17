package afield.ui.output

import java.util.Locale

private fun Number.fixedDecimal(places: Int): String =
    String.format(Locale.ROOT, "%.${places}f", toDouble())

/** Fixed-point text with zero fractional digits and a locale-independent decimal convention. */
val Number.f0: String get() = fixedDecimal(0)
/** Fixed-point text with one fractional digit. */
val Number.f1: String get() = fixedDecimal(1)
/** Fixed-point text with two fractional digits. */
val Number.f2: String get() = fixedDecimal(2)
/** Fixed-point text with three fractional digits, useful inside string templates. */
val Number.f3: String get() = fixedDecimal(3)
/** Fixed-point text with four fractional digits. */
val Number.f4: String get() = fixedDecimal(4)
/** Fixed-point text with five fractional digits. */
val Number.f5: String get() = fixedDecimal(5)
/** Fixed-point text with six fractional digits. */
val Number.f6: String get() = fixedDecimal(6)
/** Fixed-point text with seven fractional digits. */
val Number.f7: String get() = fixedDecimal(7)
/** Fixed-point text with eight fractional digits. */
val Number.f8: String get() = fixedDecimal(8)
/** Fixed-point text with nine fractional digits. */
val Number.f9: String get() = fixedDecimal(9)

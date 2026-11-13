package afield.math.units.length

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LengthTest {

    // ── Conversions ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `unit definitions are exact`() {
        assertEquals(25.4, 1.inch.mm)
        assertEquals(304.8, 1.ft.mm)
        assertEquals(914.4, 1.yd.mm)
        assertEquals(10.0, 1.cm.mm)
        assertEquals(1000.0, 1.m.mm)
        assertEquals(12.7, 0.5.inch.mm)
        assertEquals(1.0, 25.4.mm.inches)
        assertEquals(1.0, 304.8.mm.feet)
        assertEquals(1.0, 914.4.mm.yards)
    }

    @Test
    fun `units agree with each other exactly`() {
        assertEquals(1.ft, 12.inch)
        assertEquals(1.yd, 3.ft)
        assertEquals(1.yd, 36.inch)
        assertEquals(0.5.inch, 12.7.mm)
        assertEquals(1234.mm, 1.234.m)
        assertEquals(125.cm, 1.25.m)
        for (k in -600..600) {
            assertEquals(k.ft, (12 * k).inch, "$k ft")
            assertEquals(k.yd, (3 * k).ft, "$k yd")
        }
    }

    @Test
    fun `whole numbers and binary fractions of every unit round trip exactly`() {
        val values = (-2000..2000).map { it.toDouble() } + (-2000..2000).map { it / 16.0 } + (-500..500).map { it / 64.0 }
        for (x in values) {
            assertEquals(x, x.mm.mm, "$x mm")
            assertEquals(x, x.cm.cm, "$x cm")
            assertEquals(x, x.m.m, "$x m")
            assertEquals(x, x.inch.inches, "$x in")
            assertEquals(x, x.ft.feet, "$x ft")
            assertEquals(x, x.yd.yards, "$x yd")
        }
    }

    @Test
    fun `decimals read as written`() {
        // A plain x * 25.4 or x / 1000 misses these in the last bit; the conversion reads the decimal.
        for (k in -5000..5000) {
            val tenths = k / 10.0
            assertEquals(tenths, tenths.inch.inches, "$tenths in")
            assertEquals(tenths, tenths.m.m, "$tenths m")
            assertEquals(k / 10000.0, tenths.mm.m, "$tenths mm in m")
            val hundredths = k / 100.0
            assertEquals(hundredths, hundredths.mm.mm, "$hundredths mm")
            assertEquals(hundredths, hundredths.ft.feet, "$hundredths ft")
        }
        assertEquals(2.54, 0.1.inch.mm)
        assertEquals(0.0003, 0.3.mm.m)
    }

    @Test
    fun `the fast conversion agrees with BigDecimal`() {
        val random = java.util.Random(12)
        val exact = java.math.MathContext(40)
        fun reference(x: Double, numerator: Int, denominator: Int): Double =
            java.math.BigDecimal.valueOf(x).multiply(java.math.BigDecimal(numerator))
                .divide(java.math.BigDecimal(denominator), exact).toDouble()
        repeat(20000) {
            val x = when (it % 4) {
                0 -> random.nextInt(2000000) / 1000.0 - 1000.0 // three decimals
                1 -> random.nextGaussian() * 1000 // seventeen digits: BigDecimal's path
                2 -> random.nextInt(100000) / 7.0
                else -> random.nextLong() / 1e6
            }
            assertEquals(reference(x, 127, 5), x.inch.mm, "$x in")
            assertEquals(reference(x, 5, 1524), x.mm.feet, "$x mm in feet")
            assertEquals(reference(x, 1, 1000), x.mm.m, "$x mm in m")
        }
    }

    @Test
    fun `int double and other numbers all build lengths`() {
        assertEquals(3.0.inch, 3.inch)
        assertEquals(3.inch, 3.0f.inch)
        assertEquals(3.inch, 3L.inch)
        assertEquals(LengthUnit.IN.of(3.0), 3.inch)
        assertEquals(1.5, LengthUnit.FT.measure(18.inch))
        assertEquals(18.0, 18.inch.valueIn(LengthUnit.IN))
    }

    // ── Arithmetic ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `arithmetic`() {
        assertTrue((4.ft + 6.5.inch).near(54.5.inch))
        assertTrue((1.m - 250.mm).near(75.cm))
        assertEquals(54.inch, 18.inch * 3)
        assertEquals(54.inch, 3 * 18.inch)
        assertEquals(27.inch, 1.5 * 18.inch)
        assertEquals(27.inch, 18.inch * 1.5)
        assertEquals(6.inch, 18.inch / 3)
        assertEquals(6.inch, 18.inch / 3.0)
        assertEquals(4.0, 6.ft / 18.inch)
        assertEquals((-3).mm, -(3.mm))
        assertEquals(3.mm, +(3.mm))
        assertEquals(3.mm, abs((-3).mm))
        assertEquals(Length.ZERO, -Length.ZERO, "negating zero gives zero, not a negative zero")
        assertEquals(2.mm, min(2.mm, 3.mm))
        assertEquals(3.mm, max(2.mm, 1.cm / 10 * 3))
        assertTrue(listOf(1.ft, 6.inch, 3.inch).sum().near(21.inch))
        assertEquals(Length.ZERO, emptyList<Length>().sum())
    }

    @Test
    fun `comparison and sorting`() {
        assertTrue(1.inch > 25.mm)
        assertTrue(1.inch < 26.mm)
        assertTrue(1.ft >= 12.inch && 1.ft <= 12.inch)
        assertEquals(0, 0.0.mm.compareTo((-0.0).mm))
        assertEquals(listOf(1.mm, 1.cm, 1.inch, 1.ft, 1.m), listOf(1.m, 1.inch, 1.mm, 1.ft, 1.cm).sorted())
        assertTrue(25.4.mm in 1.mm..1.inch)
        assertEquals(1.inch, maxOf(1.cm, 1.inch))
    }

    @Test
    fun `near takes a tolerance`() {
        assertFalse(0.1.mm + 0.2.mm == 0.3.mm, "Double arithmetic, as the docs say")
        assertTrue((0.1.mm + 0.2.mm).near(0.3.mm))
        val carried = 11.9375.inch + 0.0625.inch
        assertTrue(carried.near(1.ft))
        assertTrue(10.mm.near(10.009.mm, 0.01.mm))
        assertFalse(10.mm.near(10.02.mm, 0.01.mm))
        assertFalse(10.mm.near(10.001.mm))
        assertTrue((-10).mm.near((-10.005).mm, 0.01.mm))
    }

    // ── Metric formatting ───────────────────────────────────────────────────────────────────────

    @Test
    fun `metric formatting`() {
        assertEquals("1 250 mm", 1250.mm.format())
        assertEquals("1 250 mm", 1250.mm.toString())
        assertEquals("1.25 m", 1250.mm.format(LengthUnit.M))
        assertEquals("125 cm", 1250.mm.format(LengthUnit.CM))
        assertEquals("12.7 mm", 0.5.inch.toString())
        assertEquals("76.2 mm", 3.inch.toString())
        assertEquals("1 234 567.891 mm", 1234567.891.mm.format())
        assertEquals("1234567.891 mm", 1234567.891.mm.format(groupSeparator = ""))
        assertEquals("1.3 m", 1250.mm.format(LengthUnit.M, decimals = 1))
        assertEquals("1.250 m", 1250.mm.format(LengthUnit.M, trimZeros = false))
        assertEquals("1 250.0 mm", 1250.mm.format(decimals = 1, trimZeros = false))
        assertEquals("3 mm", 2.5.mm.format(decimals = 0))
        assertEquals("-3 mm", (-2.5).mm.format(decimals = 0), "halves round away from zero")
        assertEquals("-1 250 mm", (-1250).mm.format())
        assertEquals("0 mm", (-0.0001).mm.format(), "no negative zero")
        assertEquals("0 mm", Length.ZERO.format())
        assertEquals("6 in", 6.inch.format(LengthUnit.IN))
        assertEquals("1.5 ft", 18.inch.format(LengthUnit.FT))
        assertEquals("1 yd", 36.inch.format(LengthUnit.YD))
        assertEquals("NaN mm", Double.NaN.mm.format())
        assertEquals("Infinity mm", Double.POSITIVE_INFINITY.mm.format())
        assertFailsWith<IllegalArgumentException> { 1.mm.format(decimals = -1) }
    }

    // ── Feet, inches and fractions ──────────────────────────────────────────────────────────────

    @Test
    fun `feet and inches`() {
        assertEquals("4' 6 1/2\"", 54.5.inch.formatFeetInches())
        assertEquals("4' 6 1/2\"", (4.ft + 6.5.inch).formatFeetInches())
        assertEquals("0' 3/16\"", 0.1875.inch.formatFeetInches())
        assertEquals("1' 0\"", 1.ft.formatFeetInches())
        assertEquals("0' 0\"", Length.ZERO.formatFeetInches())
        assertEquals("0' 6\"", 6.inch.formatFeetInches())
        assertEquals("4' 6 1/2\"", 54.5.inch.formatFeetInches(denominator = 2))
        assertEquals("4' 7\"", 54.5.inch.formatFeetInches(denominator = 1), "halves round up")
        assertEquals("0' 1/2\"", 0.5.inch.formatFeetInches(), "8/16 reduces")
        assertEquals("1' 5/32\"", 12.15625.inch.formatFeetInches(32))
    }

    @Test
    fun `rounding carries into inches and feet`() {
        assertEquals("1' 0\"", (11.9375.inch + 0.0625.inch).formatFeetInches())
        assertEquals("1' 0\"", 11.99.inch.formatFeetInches())
        assertEquals("1' 0\"", 11.97.inch.formatFeetInches())
        assertEquals("0' 11 15/16\"", 11.95.inch.formatFeetInches())
        assertEquals("0' 7\"", 6.99.inch.formatFeetInches())
        assertEquals("10' 0\"", (9.ft + 11.999.inch).formatFeetInches())
        assertEquals("1\"", 0.99.inch.formatInches())
        assertEquals("0' 0\"", 0.01.inch.formatFeetInches(), "rounds to nothing")
    }

    @Test
    fun `negative feet and inches`() {
        assertEquals("-4' 6 1/2\"", (-54.5).inch.formatFeetInches())
        assertEquals("-0' 3/16\"", (-0.1875).inch.formatFeetInches())
        assertEquals("-1' 0\"", (-11.99).inch.formatFeetInches())
        assertEquals("0' 0\"", (-0.01).inch.formatFeetInches(), "no negative zero")
        assertEquals("-3/8\"", (-0.375).inch.formatInches())
    }

    @Test
    fun `inches with fractions`() {
        assertEquals("3/8\"", 0.375.inch.formatInches())
        assertEquals("54 1/2\"", 54.5.inch.formatInches())
        assertEquals("6\"", 6.inch.formatInches())
        assertEquals("0\"", Length.ZERO.formatInches())
        assertEquals("1/3\"", (1.inch / 3).formatInches(denominator = 3))
        assertFailsWith<IllegalArgumentException> { 1.inch.formatInches(denominator = 0) }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `parses the forms people write`() {
        val cases = mapOf(
            "4' 6 1/2\"" to 54.5.inch,
            "4'-6 1/2\"" to 54.5.inch,
            "4'6-1/2\"" to 54.5.inch,
            "4' 6 1/2" to 54.5.inch,
            "4ft 6.5in" to 54.5.inch,
            "4 feet 6 1/2 inches" to 54.5.inch,
            "4′ 6 1/2″" to 54.5.inch,
            "4’ 6 1/2”" to 54.5.inch,
            "4'" to 4.ft,
            "4 ft" to 4.ft,
            "-1' 0\"" to (-1).ft,
            "1250 mm" to 1250.mm,
            "1 250 mm" to 1250.mm,
            "1 250 mm" to 1250.mm,
            "1,250mm" to 1250.mm,
            "1_250 mm" to 1250.mm,
            "1 234 567.5 mm" to 1234567.5.mm,
            "1.25m" to 1250.mm,
            "1.25 M" to 1250.mm,
            "2 metres" to 2.m,
            "125 cm" to 1250.mm,
            "-3.5 mm" to (-3.5).mm,
            "−3.5 mm" to (-3.5).mm,
            "+3.5 mm" to 3.5.mm,
            "6 in" to 6.inch,
            "6\"" to 6.inch,
            "6''" to 6.inch,
            "6 inches" to 6.inch,
            "3/8\"" to 0.375.inch,
            "6 1/2\"" to 6.5.inch,
            "6-1/2 in" to 6.5.inch,
            "6 125/256 in" to (6 + 125 / 256.0).inch,
            ".375 in" to 0.375.inch,
            "2 yd" to 2.yd,
            "1e3 mm" to 1.m,
            "  12.7 mm  " to 0.5.inch,
        )
        for ((text, expected) in cases) assertEquals(expected, Length.parse(text), text)
    }

    @Test
    fun `parsing reads the decimal exactly`() {
        assertEquals(1234.mm, Length.parse("1.234 m"))
        assertEquals(2.54.mm, Length.parse("0.1 in"))
        assertEquals(1.ft, Length.parse("12 in"))
    }

    @Test
    fun `a default unit reads bare numbers`() {
        assertEquals(12.mm, Length.parse("12", LengthUnit.MM))
        assertEquals(6.5.inch, Length.parse("6 1/2", LengthUnit.IN))
        assertEquals(12.inch, Length.parse("12 in", LengthUnit.MM), "a written unit wins")
    }

    @Test
    fun `metric formatting round trips over a grid`() {
        for (k in -20000..20000 step 7) {
            val length = (k / 10.0).mm
            assertEquals(length, Length.parse(length.format()), length.format())
            assertEquals(length, Length.parse(length.format(groupSeparator = "")))
            val whole = k.mm
            assertEquals(whole, Length.parse(whole.format(LengthUnit.M)), whole.format(LengthUnit.M))
            assertEquals(whole, Length.parse(whole.format(LengthUnit.CM)), whole.format(LengthUnit.CM))
        }
    }

    @Test
    fun `imperial formatting round trips over a grid`() {
        for (sixteenths in -3000..3000) {
            val length = (sixteenths / 16.0).inch
            val feetInches = length.formatFeetInches()
            assertEquals(length, Length.parse(feetInches), feetInches)
            val inches = length.formatInches()
            assertEquals(length, Length.parse(inches), inches)
            val decimal = length.format(LengthUnit.IN, decimals = 4)
            assertEquals(length, Length.parse(decimal), decimal)
        }
        for (k in -500..500) {
            val length = (k * 0.37).inch // not on the sixteenths grid: rounds, then reads back as shown
            val shown = length.formatFeetInches()
            assertEquals(shown, Length.parse(shown).formatFeetInches(), shown)
            assertTrue(Length.parse(shown).near(length, (1 / 32.0).inch), shown)
        }
    }

    @Test
    fun `parse errors name the bad text`() {
        fun message(text: String): String =
            assertFailsWith<IllegalArgumentException>(text) { Length.parse(text) }.message ?: ""
        val bad = mapOf(
            "" to "nothing to read",
            "   " to "nothing to read",
            "12" to "\"12\" has no unit",
            "12 furlongs" to "\"furlongs\" is not a unit",
            "4' 6 1/2x" to "\"x\" is not a unit",
            "abc" to "expected a number at \"abc\"",
            "1,5 mm" to "expected a unit at \",5 mm\"",
            "3/0\"" to "\"3/0\" divides by zero",
            "1 m 20 cm" to "only feet can be followed by inches",
            "4' 6 mm" to "feet can only be followed by inches, not mm",
            "4' 6\" 3" to "unexpected \"3\" after the inches",
            "-" to "it ends where a number should be",
            "4'-" to "it ends where a number should be",
            "12 13 mm" to "expected a unit at \"13 mm\"",
        )
        for ((text, fragment) in bad) {
            val m = message(text)
            assertTrue(m.startsWith("Length.parse: cannot read \"$text\""), "message for '$text' names it: $m")
            assertTrue(fragment in m, "message for '$text' says $fragment: $m")
        }
        assertNull(Length.parseOrNull("12 furlongs"))
        assertEquals(1.ft, Length.parseOrNull("1'"))
    }
}

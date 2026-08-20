package afield

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Table] is the one tabular renderer, so what matters here is not the exact markup but the four
 * things a caller depends on: columns keep the order they were declared in, cell text is escaped
 * unless a [Table.format] lambda opted out, a named column that does not exist fails loudly rather
 * than silently doing nothing, and a [Table.expect] claim shows up in all three places it promises
 * to — the colour, the footnote, and [Table.deviations].
 */
class TableTest {

    private data class Sample(val name: String, val calls: Int, val ratio: Double)

    @Test
    fun `columns keep their declared order and numeric columns right-align`() {
        val html = Table(listOf("builder", "calls"))
            .row("eachFrame { }", 7)
            .row("loop { }", 8)
            .toHTML()

        assertTrue(html.indexOf("builder") < html.indexOf("calls"), "declared column order")
        // The string column left-aligns, the numeric one right-aligns.
        assertTrue("text-align:left" in html, "expected a left-aligned label column")
        assertTrue("text-align:right" in html, "expected a right-aligned numeric column")
    }

    @Test
    fun `default cell text escapes, and a format lambda is trusted HTML`() {
        val hostile = Table(listOf("value")).row("<script>alert(1)</script>")
        assertTrue("&lt;script&gt;" in hostile.toHTML(), "a hostile cell must be escaped")
        assertFalse("<script>" in hostile.toHTML(), "no raw script tag may survive")

        val formatted = Table(listOf("value"))
            .row("x")
            .format("value") { "<b>bold</b>" }
        assertTrue("<b>bold</b>" in formatted.toHTML(), "a format lambda's HTML is inserted as-is")
    }

    @Test
    fun `whole-valued floats lose their decimal point and others keep four places`() {
        val cells = Table(listOf("v")).row(1.0).row(1.5).row(1.0 / 3.0).toText()
            .trim().lines().drop(1).map { it.trim() }
        assertEquals(listOf("1", "1.5", "0.3333"), cells, "unexpected default number formatting")
    }

    @Test
    fun `a magnitude four decimals would destroy goes to scientific notation, not to zero`() {
        val cells = Table(listOf("cost")).row(1e-9).row(0.0).row(-2.5e-7).toText()
            .trim().lines().drop(1).map { it.trim() }
        assertEquals(listOf("1.000e-09", "0", "-2.500e-07"), cells, "a converged residual must not print as 0")
    }

    @Test
    fun `an expect claim colours the cell, counts it in the footnote, and lists it in deviations`() {
        val t = Table(listOf("builder", "frameNumber"))
            .row("frames { }", 7)
            .row("broken { }", 3)
            .expect("frameNumber", because = "one frame per pump") { it == 7 }
        t.note = "cells are cumulative body calls"

        assertEquals(1, t.deviations.size, "exactly one row breaks the claim")
        assertEquals(Deviation(1, "frameNumber", 3, "one frame per pump"), t.deviations.single())

        val html = t.toHTML()
        assertTrue(CellRole.BAD.cssClass in html, "the failing cell should carry the bad role")
        assertTrue("one frame per pump" in html, "the reason should reach the tooltip")
        assertTrue(
            "1 cell outside expectation" in html,
            "the footnote should state the failure in words, so a static capture reads correctly:\n$html"
        )
    }

    @Test
    fun `a satisfied claim leaves the footnote alone and colours nothing`() {
        val t = Table(listOf("frameNumber")).row(7).row(7).expect("frameNumber") { it == 7 }
        t.note = "every builder advanced one frame per pump"
        val html = t.toHTML()
        assertTrue(t.deviations.isEmpty(), "no deviations expected")
        assertFalse("outside expectation" in html, "a satisfied claim adds nothing to the footnote")
        assertFalse(CellRole.BAD.cssClass in html, "nothing should be marked as a failure")
        assertTrue("every builder advanced one frame per pump" in html, "the note survives verbatim")
    }

    @Test
    fun `a role becomes a shared css class, not a hex literal, and expect outranks it`() {
        val t = Table(listOf("ran", "n"))
            .row(true, 1)
            .row(false, 2)
            .roleBy("ran") { if (it == true) CellRole.OK else CellRole.BAD }
            .role("n", CellRole.MUTED)
            .format("ran") { if (it == true) "yes" else "no" }
        val html = t.toHTML()
        assertTrue("field-md-ok" in html && "field-md-bad" in html, "both roles should render:\n$html")
        assertTrue("field-md-muted" in html, "a whole-column role should render")
        assertFalse("#" in html.substringAfter("<tbody>").substringBefore("</tbody>"), "no hex in the cells")

        // A failed expect outranks a roleBy that says OK.
        val claimed = Table(listOf("ran")).row(true)
            .roleBy("ran") { CellRole.OK }
            .expect("ran") { false }
        assertTrue(CellRole.BAD.cssClass in claimed.toHTML(), "expect must win over roleBy")
    }

    @Test
    fun `a bar is proportional, inherits currentColor, and leaves the text to format`() {
        val t = Table(listOf("label", "score"))
            .row("a", 1.0)
            .row("b", 0.5)
            .row("c", 0.0)
            .bar("score")
            .format("score") { "%.0f%%".format((it as Double) * 100) }
        val html = t.toHTML()

        // Proportional to the column max, so 1.0 -> 100% and 0.5 -> 50%.
        assertTrue("width:100.00%" in html, "the largest value should fill the bar:\n$html")
        assertTrue("width:50.00%" in html, "half the max should be half the bar")
        assertTrue("currentColor" in html, "the bar must inherit the cell colour, not carry its own")
        assertFalse("#" in html.substringAfter("<tbody>"), "no hex literal in a bar")
        // format still owns the text.
        assertTrue("100%" in html && "50%" in html, "the formatted text should survive alongside the bar")
    }

    @Test
    fun `an explicit bar max means of-the-whole rather than of-the-biggest`() {
        val ofBiggest = Table(listOf("p")).row(0.2).row(0.1).bar("p").toHTML()
        assertTrue("width:100.00%" in ofBiggest, "with no max, the biggest value fills the bar")

        val ofWhole = Table(listOf("p")).row(0.2).row(0.1).bar("p", max = 1.0).toHTML()
        assertTrue("width:20.00%" in ofWhole, "with max = 1.0, 0.2 is a fifth of the bar")
        assertFalse("width:100.00%" in ofWhole, "nothing should fill it")
    }

    @Test
    fun `a barred column left-aligns, and degenerate columns draw no bar`() {
        assertTrue("text-align:left" in Table(listOf("n")).row(1).row(2).bar("n").toHTML(),
            "bars must start together, so a barred column left-aligns")

        // All zero, and a non-numeric cell: no bar rather than a division by zero or a stray span.
        val zeros = Table(listOf("n")).row(0).row(0).bar("n").toHTML()
        assertFalse("currentColor" in zeros, "an all-zero column has nothing to draw")
        val text = Table(listOf("n")).row("x").row("y").bar("n").toHTML()
        assertFalse("currentColor" in text, "a non-numeric cell draws no bar")
    }

    @Test
    fun `facts is a headerless label-value listing with muted labels`() {
        val html = facts("triangles" to 1280, "watertight" to true).toHTML()
        assertFalse("<thead" in html, "a label-value summary has no header worth showing:\n$html")
        // Accent, matching `.field-md td:first-child` in field.css — which outranks a class on the
        // cell, so a muted label would disagree with itself inside a markdown card.
        assertTrue("field-md-accent" in html, "labels take the same accent a key column has everywhere")
        assertTrue("triangles" in html && "1280" in html && "true" in html, "the facts should be there")
    }

    @Test
    fun `facts drops null pairs so a conditional fact needs no sentinel`() {
        val t = facts("always" to 1, null, "also" to 2)
        assertEquals(2, t.rows.size, "the null pair should be dropped, not rendered blank")
        assertEquals(listOf<Any?>("also", 2), t.rows.last())
    }

    @Test
    fun `facts is still a Table, so the rest of the vocabulary applies`() {
        val html = facts("fast" to 12, "slow" to 40)
            .bar("value")
            .expect("value", because = "under budget") { (it as Int) < 30 }
            .toHTML()
        assertTrue("currentColor" in html, "bar() should work on a facts table")
        assertTrue(CellRole.BAD.cssClass in html, "expect() should work on a facts table")
        assertTrue("1 cell outside expectation" in html, "and its footnote")
    }

    @Test
    fun `facts escapes values by default but a format opts a column into markup`() {
        // The default: a value that looks like markup is shown, not interpreted.
        val escaped = facts("at" to "<span style='color:red'>1.0</span>").toHTML()
        assertTrue("&lt;span" in escaped, "an unformatted value must be escaped:\n$escaped")

        // How a toHTML() whose values are already markup opts in — this is what FourVisualizers does.
        val trusted = facts("at" to "<span style='color:red'>1.0</span>")
            .format("value") { it?.toString() ?: "" }
            .toHTML()
        assertTrue("<span style='color:red'>" in trusted, "a formatted value is inserted as markup")
        assertFalse("&lt;span" in trusted, "and is not double-escaped")
    }

    @Test
    fun `showHeader off drops only the header, keeping the footnote and rows`() {
        val t = Table(listOf("a", "b"), note = "a note").row(1, 2)
        t.showHeader = false
        val html = t.toHTML()
        assertFalse("<thead" in html, "no header")
        assertTrue("<tbody" in html && ">1<" in html, "rows survive")
        assertTrue("a note" in html, "the footnote survives")
    }

    @Test
    fun `a role in the first column keeps its own colour despite the card css`() {
        // field.css accents td:first-child, which outranks a class on the cell — so the role's colour
        // is emitted inline too, or a BAD marker in column one would render as an accent.
        val html = Table(listOf("change", "key")).row("-", "gone")
            .roleBy("change") { CellRole.BAD }
            .toHTML()
        assertTrue("color:var(--field-danger)" in html, "the role's token must be inline:\n$html")
        assertTrue(CellRole.BAD.cssClass in html, "and the class stays, for anything selecting on it")
    }

    @Test
    fun `naming a column that does not exist fails loudly rather than doing nothing`() {
        val t = Table(listOf("builder", "calls")).row("a", 1)
        for (op in listOf<(Table) -> Unit>(
            { it.align("clals", Align.RIGHT) },
            { it.color("clals", "#fff") },
            { it.format("clals") { "x" } },
            { it.expect("clals") { true } },
            { it.role("clals", CellRole.OK) },
            { it.roleBy("clals") { CellRole.OK } },
            { it.bar("clals") },
        )) {
            val e = assertFailsWith<IllegalArgumentException> { op(t) }
            assertTrue(
                "clals" in (e.message ?: "") && "builder" in (e.message ?: ""),
                "the message should name the typo and list the real columns, got: ${e.message}"
            )
        }
    }

    @Test
    fun `two columns sharing a name is an error, not a silently half-applied decorator`() {
        val e = assertFailsWith<IllegalArgumentException> { Table(listOf("v", "v")) }
        assertTrue("duplicate" in (e.message ?: ""), "the message should say what is wrong: ${e.message}")
    }

    @Test
    fun `a row wider than the header is an error, and a short row is padded`() {
        assertFailsWith<IllegalArgumentException> { Table(listOf("a")).row(1, 2) }
        val t = Table(listOf("a", "b", "c")).row(1)
        assertEquals(listOf<Any?>(1, null, null), t.rows.single())
    }

    @Test
    fun `rendering is bounded and says how much it elided`() {
        val t = Table(listOf("i"))
        repeat(100) { t.row(it) }
        t.maxRows = 10
        val html = t.toHTML()
        assertTrue("… 90 more rows" in html, "expected an elision line, got:\n$html")
        assertFalse(">99<" in html, "row 99 should not have been drawn")
    }

    @Test
    fun `the column-major factory transposes and pads ragged columns`() {
        val t = table("pump" to listOf(1, 2, 3), "calls" to listOf(10, 20))
        assertEquals(listOf("pump", "calls"), t.header)
        assertEquals(3, t.rows.size)
        assertEquals(listOf<Any?>(3, null), t.rows.last())
    }

    @Test
    fun `recordTable takes data classes in constructor order, not alphabetical order`() {
        val t = recordTable(listOf(Sample("a", 1, 0.5), Sample("b", 2, 0.25)))
        assertEquals(listOf("name", "calls", "ratio"), t?.header, "memberProperties order would be alphabetical")
        assertEquals(listOf<Any?>("b", 2, 0.25), t?.rows?.last())
    }

    @Test
    fun `recordTable takes uniform maps and equal-length lists, and declines everything else`() {
        val maps = recordTable(listOf(mapOf("x" to 1, "y" to 2), mapOf("x" to 3, "y" to 4)))
        assertEquals(listOf("x", "y"), maps?.header)

        val lists = recordTable(listOf(listOf(1, 2), listOf(3, 4)))
        assertEquals(listOf("0", "1"), lists?.header)

        assertNull(recordTable(listOf(listOf(1, 2), listOf(3))), "ragged rows are not a table")
        assertNull(recordTable(listOf(1, 2, 3)), "a list of scalars is a column, not a table of records")
        assertNull(recordTable(listOf(Sample("a", 1, 0.0))), "one record is not worth a table")
        assertNull(recordTable(listOf<Any?>(Sample("a", 1, 0.0), null)), "a null row is not a record")
    }

    @Test
    fun `a missing key in one map becomes an empty cell rather than a shifted row`() {
        val t = recordTable(listOf(mapOf("x" to 1, "y" to 2), mapOf("x" to 3)))
        assertEquals(listOf("x", "y"), t?.header)
        assertEquals(listOf<Any?>(3, null), t?.rows?.last())
    }

    @Test
    fun `a Table renders through value display, so display and h need no extra wiring`() {
        val html = Table(listOf("builder")).row("eachFrame { }").h
        assertTrue("value-viz" in html, "ValueVisualizer should have wrapped it")
        assertTrue("<table" in html, "ToHTML should have been dispatched ahead of reflection")
        assertTrue("eachFrame { }" in html, "the cell should be there")
    }

    // ── the value-display intercept: `rows.h` draws columns, but only where a table is an
    // improvement on what ValueVisualizer already does ──────────────────────────────────────────

    private data class Nested(val name: String, val values: List<Int>)

    private enum class Phase { WARM, COLD }

    @Test
    fun `a list of records renders as a table through plain value display`() {
        val html = listOf(Sample("a", 1, 0.5), Sample("b", 2, 0.25)).h
        assertTrue("<table" in html, "a uniform record list should table, got:\n$html")
        assertTrue(html.indexOf("name") < html.indexOf("calls"), "constructor column order")
        assertFalse("value-viz'>[" in html, "it should not also render as an inline list")
    }

    @Test
    fun `a list of uniform maps tables, and enums count as scalar cells`() {
        val html = listOf(
            mapOf("phase" to Phase.WARM, "ms" to 12),
            mapOf("phase" to Phase.COLD, "ms" to 900),
        ).h
        assertTrue("<table" in html, "uniform maps should table")
        assertTrue("WARM" in html && "900" in html, "cells should be there")
    }

    @Test
    fun `the intercept declines where the ordinary rendering is better`() {
        // A record holding a list has no useful single-cell text.
        assertFalse("<table" in listOf(Nested("a", listOf(1, 2)), Nested("b", listOf(3))).h, "nested values")
        // Pairs keep their denser inline form.
        assertFalse("<table" in listOf("a" to 1, "b" to 2).h, "kotlin.Pair")
        // A list of lists keeps its nesting, and with it a sparkline per row.
        assertFalse("<table" in listOf(listOf(1, 2, 3), listOf(4, 5, 6)).h, "list of lists")
        // Scalars are a column, not a table.
        assertFalse("<table" in listOf(1, 2, 3).h, "list of numbers")
        assertFalse("<table" in listOf("a", "b", "c").h, "list of strings")
    }

    @Test
    fun `a record list nested inside a struct keeps the struct tree`() {
        val wrapper = mapOf("runs" to listOf(Sample("a", 1, 0.5), Sample("b", 2, 0.25)))
        assertFalse("<table" in wrapper.h, "there is no width to spend on columns below the top level")
    }

    @Test
    fun `the intercept is bounded and says how much it elided`() {
        val many = (1..100).map { Sample("row$it", it, it / 100.0) }
        val html = many.h
        assertTrue("<table" in html, "a long record list still tables")
        assertTrue("more rows" in html, "expected an elision line, got the tail of:\n${html.takeLast(400)}")
        assertFalse("row99" in html, "row 99 is past the bound")
    }

    @Test
    fun `toText aligns columns for a terminal`() {
        val text = Table(listOf("builder", "calls"))
            .row("frames { }", 7)
            .row("eachFrame { }", 123)
            .toText()
        val lines = text.trim().lines()
        assertEquals(3, lines.size, "header plus two rows, got:\n$text")
        // Right-aligned numerics line up on their last character.
        assertTrue(lines[1].endsWith("  7"), "expected 7 right-aligned under a 5-wide column: '${lines[1]}'")
        assertTrue(lines[2].endsWith("123"), "expected 123 flush right: '${lines[2]}'")
    }
}

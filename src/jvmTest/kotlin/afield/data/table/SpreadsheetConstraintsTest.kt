package afield.data.table

import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Constraint-solving sheets against the real formula engine and the real Ceres wrapper: `=free(...)`
 * variable cells, formulas that trace back to them, `eq` / `le` / `ge` assertions, the weak stay that
 * makes an underdetermined sheet move as little as it can, the edit that holds, and the infeasible
 * assertion reported in its cell. Design: dev_docs/SPREADSHEET_CONSTRAINTS.md.
 */
class SpreadsheetConstraintsTest {
    private lateinit var session: SessionInformation
    private lateinit var sheet: Box

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<sheet-constraints-${System.nanoTime()}>", documentName = "sheetconstraints", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        documents[session.filename] = session
        sheet = Box("sheet").also {
            it.inside = session
            session.boxes[it.id] = it
            session.root.children.add(it)
        }
    }

    @AfterTest
    fun tearDown() {
        documents.remove(session.filename)
    }

    private fun value(ref: String): Double = sheet.getSpreadsheetCell(ref).d
    private fun snap() = sheet.getSpreadsheetSnapshot()
    private fun cell(ref: String) = assertNotNull(snap().cell(ref), "no cell $ref")
    private fun input(ref: String) = sheet.getSpreadsheetState().cell(ref)?.input
    private fun near(expected: Double, actual: Double, tol: Double = 1e-6, what: String = "") =
        assertTrue(abs(expected - actual) <= tol * (1 + abs(expected)), "$what expected $expected, was $actual")

    @Test
    fun anEqualityRunsBothWays() {
        sheet.editSpreadsheet(broadcast = false) {
            cell("A1", "Celsius"); formula("B1", "free(100)")
            cell("A2", "Fahrenheit"); formula("B2", "free(0)")
            formula("B3", "B2 eq B1 * 9.0 / 5.0 + 32.0")
        }
        // Nothing edited yet: both move, as little as they can, and the assertion holds.
        val c0 = value("B1"); val f0 = value("B2")
        near(f0, c0 * 1.8 + 32, what = "first solve")
        assertEquals("assertion", cell("B3").type)
        assertTrue(cell("B3").display.startsWith("✓"), cell("B3").display)
        assertEquals("variable", cell("B1").type)

        // Edit Celsius: it holds exactly and Fahrenheit follows.
        sheet.editSpreadsheet(broadcast = false) { formula("B1", "free(100)") }
        near(100.0, value("B1"), 1e-9, "edited C")
        near(212.0, value("B2"), 1e-7, "F from C")
        assertEquals("=free(212)", input("B2"))

        // Type a plain number over Fahrenheit: still a variable, and Celsius follows the other way.
        sheet.editSpreadsheet(broadcast = false) { cell("B2", 32) }
        assertEquals("=free(32)", input("B2"))
        near(0.0, value("B1"), 1e-7, "C from F")
        assertTrue(cell("B3").display.startsWith("✓"), cell("B3").display)
        assertNotNull(snap().solve, "header summary")
    }

    @Test
    fun anEditMovesTheOthersAsLittleAsItCan() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(3)")
            formula("A2", "free(7)")
            formula("A3", "free(42)")
            formula("B1", "A1 + A2 eq 10")
            formula("B2", "A1 * 2 + A3")
        }
        near(3.0, value("A1")); near(7.0, value("A2")); near(42.0, value("A3"))
        near(48.0, value("B2"), what = "formula over variables")

        sheet.editSpreadsheet(broadcast = false) { formula("A1", "free(6)") }
        near(6.0, value("A1"), 1e-9, "edited")
        near(4.0, value("A2"), 1e-7, "moved by the equality")
        near(42.0, value("A3"), 1e-9, "untouched by anything stays put")
        near(54.0, value("B2"), what = "downstream formula recomputed")
    }

    @Test
    fun underdeterminedMovesBothByTheLeastSquareChange() {
        // w * h = 40 from w = 4, h = 5: the stay is equal on both, so both stretch, by the same
        // relative amount to first order (the minimal-norm change); neither stays fixed.
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(4)")
            formula("A2", "free(5)")
            formula("A3", "A1 * A2")
            formula("A4", "A3 eq 40")
        }
        val w = value("A1"); val h = value("A2")
        near(40.0, w * h, 1e-7, "area")
        assertTrue(w > 4.0 && h > 5.0, "both moved: w=$w h=$h")
        // Lagrange: (w-4, h-5) parallel to the gradient (h, w).
        near((w - 4) / h, (h - 5) / w, 1e-4, "minimal change")
    }

    @Test
    fun theDocsRectangleStopsAnEditAtItsBound() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("B1", "free(4)"); formula("B2", "free(5)"); formula("B3", "B1 * B2")
            formula("B5", "B3 eq 40"); formula("B6", "B1 le 6")
        }
        assertEquals("5.9488", cell("B1").display); assertEquals("6.7241", cell("B2").display)
        sheet.editSpreadsheet(broadcast = false) { cell("B1", 8) }
        near(6.0, value("B1"), 1e-9, "edit stopped at the bound")
        assertEquals("6.6667", cell("B2").display)
    }

    @Test
    fun inequalitiesBecomeBoundsOrBarriers() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(5)")
            formula("A2", "free(5)")
            formula("B1", "A1 ge 7")          // a bound on one variable
            formula("B2", "A1 + A2 le 9")     // a barrier over two
            formula("B3", "0 le A2")          // number on the left
        }
        assertTrue(value("A1") >= 7.0 - 1e-7, "bound: ${value("A1")}")
        assertTrue(value("A1") + value("A2") <= 9.0 + 1e-6, "barrier: ${value("A1")} + ${value("A2")}")
        assertTrue(value("A2") >= -1e-7, "reversed bound: ${value("A2")}")
        listOf("B1", "B2", "B3").forEach { assertTrue(cell(it).display.startsWith("✓"), "$it ${cell(it).display}") }
    }

    @Test
    fun anEditIsRememberedInTheStoredSheet() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(1)"); formula("A2", "free(2)"); formula("B1", "A1 + A2 eq 3")
        }
        snap()
        assertEquals(mapOf("0:0" to "=free(1)", "1:0" to "=free(2)"), sheet.getSpreadsheetState().solved)
        // A second document (a reopened Field) sees the edit through the stored record alone.
        documents.remove(session.filename)
        val reopened = SessionInformation(filename = "<sheet-constraints-reopen-${System.nanoTime()}>", documentName = "reopen", root = session.root, ephemeral = true)
        reopened.loading = false
        val copy = Box("sheet-copy").also { it.inside = reopened; reopened.boxes[it.id] = it }
        copy.setSpreadsheetState(sheet.getSpreadsheetState().let { st ->
            st.copy(cells = st.cells.map { if (it.row == 0 && it.col == 0) it.copy(input = "=free(10)") else it })
        }, broadcast = false)
        documents[reopened.filename] = reopened
        near(10.0, copy.getSpreadsheetCell("A1").d, 1e-9, "edit held")
        near(-7.0, copy.getSpreadsheetCell("A2").d, 1e-7, "other moved")
        documents.remove(reopened.filename)
        documents[session.filename] = session
    }

    @Test
    fun anEditYieldsToARequiredAssertion() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(10)")
            formula("A2", "free(10)")
            formula("B1", "A1 le 50")           // a bound
            formula("B2", "A2 - A1 eq 5")        // an equality
            formula("B3", "A1 + A2 le 80")       // a barrier over two
        }
        // First solve, nothing edited: both meet the equality halfway, the minimal change.
        near(7.5, value("A1"), 1e-6); near(12.5, value("A2"), 1e-6)
        sheet.editSpreadsheet(broadcast = false) { formula("A1", "free(100)") }
        // The header describes the solve the edit triggered (a later recompute has nothing edited).
        val afterEdit = sheet.getSpreadsheetSnapshot(forceRecompute = false).solve
        assertTrue(afterEdit!!.contains("edit yielded"), afterEdit)
        // The bound stops A1 at 50, the equality takes A2 to 55, and then the barrier (105 > 80) pushes
        // the edited A1 back further, to the point where A1 + A1 + 5 = 80.
        near(37.5, value("A1"), 1e-6, "edit pushed back")
        near(42.5, value("A2"), 1e-6, "equality kept")
        listOf("B1", "B2", "B3").forEach { assertTrue(cell(it).display.startsWith("✓"), "$it ${cell(it).display}") }
    }

    @Test
    fun anInfeasibleAssertionIsReportedInItsCell() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(6)")
            formula("B1", "A1 ge 10")
            formula("B2", "A1 le 5")
            formula("B3", "3 eq 4")
        }
        val violated = listOf("B1", "B2", "B3").filter { cell(it).display.startsWith("✗") }
        assertTrue("B3" in violated, "a constant falsehood is violated: ${cell("B3").display}")
        assertTrue(violated.size >= 2, "at least one of the contradictory pair is violated: " +
            listOf("B1", "B2").map { cell(it).display })
        assertTrue(cell("B1").htmlDisplay!!.contains("data-assertion"), cell("B1").htmlDisplay)
        assertTrue(snap().solve!!.contains("violated"), snap().solve)
        assertTrue(cell("B1").display.contains("off by"), cell("B1").display)
    }

    @Test
    fun sumKeepsTheTraceAndOtherBoxesReadNumbers() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "free(30)")
            formula("A2", "free(30)")
            formula("A3", "free(30)")
            formula("A4", "sum(A1:A3) eq 120")
            formula("A5", "sqrt(A1 * A1) eq 40")
        }
        near(120.0, value("A1") + value("A2") + value("A3"), 1e-7, "budget")
        near(40.0, value("A1"), 1e-6, "through sqrt")
        assertTrue(sheet.getSpreadsheetCellValue("A1") is Double, "outside the sheet a variable is a number")
        // Existing numeric formulas still read as numbers.
        sheet.editSpreadsheet(broadcast = false) { formula("C1", "sum(2, 3).toInt()"); formula("C2", "sqrt(16.0) + 1") }
        assertEquals("5", cell("C1").display)
        assertEquals("5", cell("C2").display)
    }
}

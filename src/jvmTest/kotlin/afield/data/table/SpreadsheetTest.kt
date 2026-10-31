package afield.data.table

import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.math.linalg.Vec2
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The spreadsheet as a box sees it: cells built with [Box.editSpreadsheet], formulas compiled by the
 * real K2 formula engine, values read back with [Box.getSpreadsheetSnapshot]. The live sweep is driven
 * the way an open sheet drives it: a client opens the sheet over the websocket handler and the Runloop
 * hook polls once per frame ([pollSpreadsheetsOnce]).
 */
class SpreadsheetTest {
    private lateinit var session: SessionInformation
    private lateinit var sheet: Box
    private lateinit var source: Box

    @BeforeTest
    fun setUp() {
        val root = Box("root")
        session = SessionInformation(filename = "<sheet-test-${System.nanoTime()}>", documentName = "sheettest", root = root, ephemeral = true)
        root.inside = session
        session.loading = false
        thisSessionInfo.set(session)
        documents[session.filename] = session
        sheet = addBox("sheet")
        source = addBox("source")
        source.name = "source"
    }

    @AfterTest
    fun tearDown() {
        documents.remove(session.filename)
    }

    private fun addBox(id: String): Box = Box(id).also {
        it.inside = session
        session.boxes[it.id] = it
        session.root.children.add(it)
    }

    private fun display(ref: String): String? = sheet.getSpreadsheetSnapshot().cell(ref)?.display
    private fun error(ref: String): String? = sheet.getSpreadsheetSnapshot().cell(ref)?.errorDetail

    @Test
    fun formulasAreKotlinOverCellsAndRanges() {
        sheet.editSpreadsheet(broadcast = false) {
            cell("A1", 2); cell("A2", 3); cell("A3", 5)
            formula("B1", "A1 * 10")
            formula("B2", "sum(A1:A3)")
            formula("B3", "B1 + B2")
            formula("B4", "if (A3 > 4) \"big\" else \"small\"")
            formula("B5", "\$A\$1 + A2")
            formula("B6", "sqrt(16.0)")
            formula("B7", "\"A1 stays text\"")
            formula("B8", "\"total \${sum(A1:A3).toInt()}\"")
        }
        assertEquals("20", display("B1"))
        assertEquals("10", display("B2"))
        assertEquals("30", display("B3"))
        assertEquals("big", display("B4"))
        assertEquals("5", display("B5"))
        assertEquals("4", display("B6"))
        assertEquals("A1 stays text", display("B7"))
        assertEquals("total 10", display("B8"), error("B8"))
        assertEquals(30.0, (sheet.getSpreadsheetCell("B3")).d)
    }

    @Test
    fun mathFunctionsTakeCellsDirectly() {
        sheet.editSpreadsheet(broadcast = false) {
            cell("A1", 16); cell("A2", -2.5)
            formula("B1", "sqrt(A1)")
            formula("B2", "abs(A2)")
            formula("B3", "round(A2)")
            formula("B4", "pow(A1, 0.5)")
            formula("B5", "max(A1, A2)")
        }
        assertEquals("4", display("B1"), error("B1"))
        assertEquals("2.5", display("B2"), error("B2"))
        assertEquals("-2", display("B3"), error("B3"))
        assertEquals("4", display("B4"), error("B4"))
        assertEquals("16", display("B5"), error("B5"))
    }

    @Test
    fun aFilledDownFormulaIsOneTemplate() {
        val refs2 = mutableListOf<String>()
        val refs3 = mutableListOf<String>()
        val row2 = rewriteSpreadsheetFormula("B2 * C2 + sum(\$A\$1:A2) + cell(\"Z9\")", refs2)
        val row3 = rewriteSpreadsheetFormula("B3 * C3 + sum(\$A\$1:A3) + cell(\"Z9\")", refs3)
        assertEquals(row2, row3)
        assertEquals(listOf("B2", "C2", "A1:A2"), refs2)
        assertEquals(listOf("B3", "C3", "A1:A3"), refs3)
        assertEquals("3 .withUnit(m)", rewriteSpreadsheetFormula("3 | m"))
        val templated = mutableListOf<String>()
        assertEquals(
            "\"A1 is \${cell(__ref(0))}, \${max(range(__ref(1)))}\"",
            rewriteSpreadsheetFormula("\"A1 is \${A1}, \${max(A2:A3)}\"", templated)
        )
        assertEquals(listOf("A1", "A2:A3"), templated)
    }

    @Test
    fun anEmptyCellIsZeroInArithmetic() {
        sheet.editSpreadsheet(broadcast = false) {
            cell("A1", 4)
            formula("B1", "A1 * 10")
            formula("B2", "A2 * 10")
            formula("B3", "A2 + 1")
            formula("B4", "A2 < 1")
        }
        assertEquals("40", display("B1"))
        assertEquals("0", display("B2"), error("B2"))
        assertEquals("1", display("B3"), error("B3"))
        assertEquals("true", display("B4"), error("B4"))
    }

    @Test
    fun anErrorPropagatesToTheCellsThatReadIt() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "1 / 0 + error(\"boom\")")
            formula("B1", "A1 + 1")
            formula("C1", "sum(A1:B1)")
        }
        assertEquals("#ERR", display("A1"))
        assertEquals("#ERR", display("B1"))
        assertTrue(error("B1")!!.contains("A1"), error("B1"))
        assertEquals("#ERR", display("C1"))
    }

    @Test
    fun aCycleIsReportedOnEveryCellInIt() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "B1 + 1")
            formula("B1", "A1 + 1")
            formula("C1", "A1 * 2")
        }
        assertEquals("#CYCLE", display("A1"))
        assertEquals("#CYCLE", display("B1"))
        assertEquals("#ERR", display("C1"))
    }

    @Test
    fun valuesPromoteThroughUnitsVectorsAndLines() {
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "3 | m")
            formula("A2", "A1 * 2")
            formula("A3", "A1 / (2 | s)")
            formula("A6", "0.21 | m")
            formula("A4", "A6 * 3")
            formula("A5", "A6 / 3")
            formula("B1", "vec(1.0, 2.0)")
            formula("B2", "B1 * 3")
            formula("B3", "B1 + 1")
            formula("B4", "B1.x + B1.y")
            formula("C1", "FLine().moveTo(0.0, 0.0).lineTo(30.0, 40.0)")
            formula("C2", "C1.x + C1.width")
            formula("C4", "C1.height")
            formula("C3", "C1 + B1")
        }
        assertEquals("3 m", display("A1"))
        assertEquals("6 m", display("A2"))
        assertTrue(display("A3")!!.startsWith("1.5 m"), display("A3"))
        assertEquals("0.63 m", display("A4"))
        assertEquals("0.07 m", display("A5"))
        assertEquals("vec(1, 2)", display("B1"))
        assertEquals("vec(3, 6)", display("B2"))
        assertEquals("vec(2, 3)", display("B3"))
        assertEquals("3", display("B4"))
        assertEquals("FLine(0, 0, 30, 40)", display("C1"))
        assertEquals("30", display("C2"))
        assertEquals("40", display("C4"))
        assertEquals("FLine(1, 2, 30, 40)", display("C3"))
        assertTrue(sheet.getSpreadsheetCellValue("C3") is FLine)
        assertEquals(Vec2(3f, 6f), sheet.getSpreadsheetCell("B2").v2)
    }

    @Test
    fun aSheetFollowsAnotherBoxOnTheNextFrame() {
        source.setPropertyRaw("speed", 4.0, doResync = false)
        sheet.editSpreadsheet(broadcast = false) {
            formula("A1", "box(\"source\")[\"speed\"] * 2")
            formula("A2", "A1 + 1")
        }
        val pushes = mutableListOf<String>()
        val client = Any()
        val opened = handleSpreadsheetRequest(
            """{"channel":"__spreadsheet__","reqId":"r1","op":"open","uid":"sheet","property":"spreadsheet"}""",
            session, client
        ) { pushes += it }
        assertTrue(opened.contains("\"display\":\"8\""), opened)

        pollSpreadsheetsOnce()
        assertTrue(pushes.isEmpty(), "an unchanged dependency must not push: $pushes")

        source.setPropertyRaw("speed", 10.0, doResync = false)
        pollSpreadsheetsOnce()
        assertEquals(1, pushes.size)
        assertTrue(pushes.single().contains("\"display\":\"20\""), pushes.single())
        assertTrue(pushes.single().contains("\"display\":\"21\""), pushes.single())
        assertEquals("21", display("A2"))

        detachSpreadsheetClient(client)
    }

    @Test
    fun aOneFormulaSheetFollowsToo() {
        source.setPropertyRaw("speed", 4.0, doResync = false)
        sheet.editSpreadsheet(broadcast = false) { formula("A1", "box(\"source\")[\"speed\"] * 2") }
        assertEquals("8", display("A1"))
        source.setPropertyRaw("speed", 10.0, doResync = false)
        assertEquals("20", display("A1"))
    }

    @Test
    fun theBuilderSeedsOnceAndEditsInPlace() {
        val state = spreadsheetState {
            this["A1"] = "Price"
            this["B1"] = 12.5
            formula("C1", "=B1 * 2")
            columnWidth("A", 200)
        }
        assertEquals("=B1 * 2", state["C1"]?.input)
        assertEquals(200.0, state.colWidths[0])
        sheet.seedSpreadsheet(state = state, broadcast = false)
        sheet.seedSpreadsheet(broadcast = false) { this["A1"] = "ignored" }
        assertEquals("Price", sheet.getSpreadsheetState()["A1"]?.input)
        sheet.editSpreadsheet(broadcast = false) { this["B1"] = 20 }
        assertEquals("40", display("C1"))
        assertNotNull(sheet.getPropertyRaw("spreadsheet", null) as? String)
    }
}

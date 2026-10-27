package afield.ai.learning.pen

import afield.geometry.path.FLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PenVizTest {
    private val writer = PenLine(FLine().moveTo(0, 0).lineTo(20, 0), seed = 2, recordMemory = true).also { it.grow(200) }

    @Test
    fun ellipsesSitAroundTheirMeans() {
        val d = writer.distribution()!!
        val e = d.ellipses(minWeight = 0.0)
        assertEquals(d.size, e.size)
        e.forEachIndexed { i, l ->
            val c = l.nodes.map { it.to }
            val cx = c.dropLast(1).sumOf { it.x.toDouble() } / (c.size - 1)
            assertEquals(d.meanX[i], cx, 1e-3 + d.sigmaX[i] * 0.1)
        }
    }

    @Test
    fun colouringKeepsEveryMarkOnce() {
        // every mark is one lineTo; runs in a band share a moveTo
        val marks = writer.history.count { it.mark }
        fun lineTos(ls: List<FLine>) = ls.sumOf { l -> l.nodes.count { it is FLine.LineTo } }
        assertEquals(marks, lineTos(writer.coloredByCell(writer.liveliestCells(1)[0])))
        assertEquals(marks, lineTos(writer.widthByLift()))
    }

    @Test
    fun densityIsAPng() {
        val png = writer.futuresDensity(count = 20, steps = 10, width = 64, height = 32)
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
    }

    @Test
    fun sideBySideNeverOverlaps() {
        val groups = (0 until 4).map { listOf(PenDrawings.plant(it.toLong())) }
        val laid = sideBySide(groups, gap = 5.0)
        laid.zipWithNext().forEach { (a, b) ->
            assertTrue(a.fast2DBounds().let { it.x + it.w } <= b.fast2DBounds().x + 1e-3)
        }
    }
}

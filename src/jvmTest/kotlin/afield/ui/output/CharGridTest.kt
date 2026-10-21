package afield.ui.output

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The char grid's cell rules (the madp demo's SET/GET/PREV, cursor wrap and scroll), the diffs the
 * panel pulls, and the frame-mode sound grouping.
 */
class CharGridTest {
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.cells() = this["d"]!!.jsonArray.map { it.jsonPrimitive.int }.chunked(4)
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.int

    @Test
    fun setKeepsColourWhenOmittedAndClearsStyleWhenOmitted() {
        val g = CharGridBuffer(4, 3, "ab")
        assertTrue(g.set(1, 1, 5, "red", "transform:rotate(45deg)"))
        assertEquals(5, g.get(1, 1), "GET returns the raw index, not taken modulo the charset")
        g.set(1, 1, 2)
        assertEquals("red", g.colorAt(1, 1))
        assertEquals("", g.styleAt(1, 1))
        assertFalse(g.set(4, 0, 1), "outside the grid writes nothing")
        assertEquals(0, g.get(-1, 0))
    }

    @Test
    fun theCursorWrapsAndScrollsUpAtTheBottom() {
        val g = CharGridBuffer(2, 2, "abcdef")
        for (i in 1..4) g.setAtCursor(i)
        assertEquals(CharGridCursor(0, 1), CharGridCursor(g.cursorX, g.cursorY), "the fourth SET wrapped past the bottom and scrolled")
        assertEquals(listOf(3, 4), listOf(g.get(0, 0), g.get(1, 0)))
        assertFalse(g.isShown(0, 1), "the row that scrolled in is blank")
        g.setAtCursor(5)
        assertEquals(5, g.get(0, 1))
    }

    @Test
    fun prevHoldsTheLastSnapshotAndClearZeroesIt() {
        val g = CharGridBuffer(3, 3, " #")
        g.set(0, 0, 1, "#50fa7b")
        g.snapshot()
        g.set(0, 0, 0, "")
        assertEquals(1, g.prev(0, 0))
        assertEquals("#50fa7b", g.prevColor(0, 0))
        assertEquals(0, g.get(0, 0))
        g.clear()
        assertEquals(0, g.prev(0, 0))
        assertFalse(g.isShown(0, 0))
    }

    @Test
    fun thePanelGetsEverythingOnceThenOnlyWhatChanged() {
        val g = CharGridBuffer(40, 25, "░▒▓█")
        for (y in 0 until 25) for (x in 0 until 40) g.set(x, y, x + y, "hsl(${(x + y) % 3},60%,45%)")
        val first = parse(g.update(-1, -1, 0, 0))
        assertTrue(first["full"]!!.jsonPrimitive.boolean)
        assertEquals(1000, first.cells().size)
        assertEquals(listOf("░", "▒", "▓", "█"), first["glyphs"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(4, first["c"]!!.jsonArray.size, "\"\" and three hues")
        assertEquals((7 + 3) % 4, first.cells()[3 * 40 + 7][1], "the glyph travels modulo the charset")

        val idle = parse(g.update(first.int("e"), first.int("v"), 4, 1))
        assertEquals(0, idle.cells().size, "nothing changed, nothing sent")
        assertEquals(0, idle["c"]!!.jsonArray.size)

        g.set(3, 2, 99, "hsl(1,60%,45%)") // a new glyph, an already-known colour
        g.set(0, 0, 0, "hsl(0,60%,45%)") // what is already there: not a change
        g.set(5, 5, 1, "white")
        val next = parse(g.update(idle.int("e"), idle.int("v"), 4, 1))
        assertFalse(next["full"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(2 * 40 + 3, 5 * 40 + 5), next.cells().map { it[0] })
        assertEquals(4, next.int("c0"))
        assertEquals(listOf("white"), next["c"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(5 * 40 + 5, 1, 4, 0), next.cells()[1])
    }

    @Test
    fun blankCellsTravelAsMinusOneAndCssVariablesWhenTheyChange() {
        val g = CharGridBuffer(3, 1, "xy")
        g.set(1, 0, 1)
        g.cssVar("--spin", "30deg")
        val u = parse(g.update(-1, -1, 0, 0))
        assertEquals(listOf(-1, 1, -1), u.cells().map { it[1] })
        assertEquals("--spin:30deg;", u["css"]!!.jsonPrimitive.content)
        val quiet = parse(g.update(u.int("e"), u.int("v"), 1, 1))
        assertFalse(quiet.containsKey("css"), "unchanged variables are not resent")
    }

    @Test
    fun aFullRebuildOfTheStringTablesStartsANewEpoch() {
        val g = CharGridBuffer(2, 1, "ab")
        val before = parse(g.update(-1, -1, 0, 0))
        for (k in 0 until 50) g.set(0, 0, 1, "hsl($k,50%,50%)")
        g.set(1, 0, 0, "keep")
        assertTrue(g.compact(limit = 10))
        assertEquals("hsl(49,50%,50%)", g.colorAt(0, 0))
        assertEquals("keep", g.colorAt(1, 0))
        assertEquals(3 + 1, g.tableSize, "\"\" + two colours in use, and the empty style")
        val after = parse(g.update(before.int("e"), before.int("v"), 51, 1))
        assertTrue(after["full"]!!.jsonPrimitive.boolean, "a panel on the old epoch gets a full frame")
        assertEquals(2, after.cells().size)
    }

    @Test
    fun frameModeSoundPlaysEachDistinctPathOnceAtTheDemosGain() {
        val played = mutableListOf<String>()
        val s = CharGridSounds(listOf("", "/dough/play/note/57"), null, cells = 100) { path, _ -> played += path }
        repeat(16) { s.onSet(1, it, 0, 0, immediate = false) }
        s.onSet(0, 0, 0, 0, immediate = false)
        s.flush()
        // one enabled glyph, 100 cells: each voice 1 / (1 * 10); sixteen of them sum to sqrt(16) / 10.
        assertEquals(listOf("/dough/play/note/57/gain/0.4000"), played)
        s.flush()
        assertEquals(1, played.size, "a flush forgets the tick")
    }

    @Test
    fun soundLambdasSeeTheCellAndExtraVoicesAreCappedWithoutLosingLevel() {
        val played = mutableListOf<String>()
        val s = CharGridSounds(emptyList(), { "/dough/play/note/${55 + y}/pan/${x / 4.0}" }, cells = 400, maxVoices = 4) { path, _ -> played += path }
        for (y in 0 until 8) s.onSet(0, 2, y, 0, immediate = false)
        s.flush()
        assertEquals(4, played.size)
        // eight single voices at 1/20 each, four played: each raised by sqrt(8/4).
        assertTrue(played.all { it.endsWith("/gain/0.0707") }, played.toString())
        assertTrue(played.all { it.contains("/pan/0.5/") })

        played.clear()
        s.onSet(0, 1, 3, 9, immediate = true)
        assertEquals(listOf("/dough/play/note/58/pan/0.25"), played, "stream mode plays at once, as written")
    }
}

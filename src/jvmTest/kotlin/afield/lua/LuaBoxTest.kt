package afield.lua

import afield.Box
import afield.Rect
import afield.stdlib.LastRunNotes
import afield.stdlib.filled
import afield.stdlib.thickness
import org.junit.Assume
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a Lua box's code can reach — `print`, `display`, `box`, `field.named`, `lines` — run through the
 * same handlers [LuaExecution] gives a box, against a real LuaJIT state, on boxes made outside a
 * document. The live path (editor marks, launch, the frame loop) is exercised through the backdoor;
 * see `dev_docs/lua/LUA.md`.
 *
 * The tests that need LuaJIT are `Assume`s: without it they are skipped, not passed. [assignedNames]
 * needs no native library and always runs.
 */
class LuaBoxTest {

    private val opened = ArrayList<LuaState>()

    @AfterTest
    fun closeAll() = opened.forEach { it.close() }

    private fun needsLuaJIT() = Assume.assumeTrue("LuaJIT is not installed: ${LuaLibrary.describe()}", LuaLibrary.isAvailable)

    private class Captured(val printed: MutableList<String> = ArrayList(), val shown: MutableList<Pair<String, String>> = ArrayList())

    private fun boxWithLua(id: String): Triple<Box, LuaState, Captured> {
        needsLuaJIT()
        val box = Box(id)
        val captured = Captured()
        box.output.proxy = { m, _, _ -> captured.printed += m.toString() }
        box.output.displayProxy = { channel, m -> captured.shown += channel to m.toString() }
        val lua = LuaState(luaHandlersFor(box)).also { opened += it }
        return Triple(box, lua, captured)
    }

    /** `connect` needs a document session; outside one, the two sets are the whole of it. */
    private fun link(parent: Box, child: Box) {
        parent.children.add(child)
        child.parents.add(parent)
    }

    private fun ok(r: LuaState.Result): List<Any?> = assertIs<LuaState.Result.Ok>(r, "expected success, got $r").values

    @Test
    fun printJoinsWithTabsAndDisplayTakesAChannel() {
        val (_, lua, out) = boxWithLua("lua-print")
        ok(lua.run("print('a', 1, nil, true)\ndisplay('main', '<b>hi</b>')\ndisplay('<i>plain</i>')"))
        assertEquals(listOf("a\t1\tnil\ttrue"), out.printed)
        assertEquals(listOf("main" to "<b>hi</b>", "output" to "<i>plain</i>"), out.shown)
    }

    @Test
    fun boxPropertiesReadWriteAndInherit() {
        val (box, lua, _) = boxWithLua("lua-props")
        box.name = "me"
        val parent = Box("lua-props-parent").also { it.name = "parent" }
        link(parent, box)
        parent.setPropertyRaw("tempo", 120.0, false)
        box.setPropertyRaw("frame", Rect(10.0, 20.0, 300.0, 40.0), false)
        val values = ok(lua.run("box.speed = 2.5\nreturn box.name, box.speed, box.tempo, box.frame.w, box.parents[1].name, tostring(box)"))
        assertEquals(listOf("me", 2.5, 120.0, 300.0, "parent", "box 'me'"), values)
        assertEquals(2.5, box.getPropertyRaw("speed"), "a write from Lua is the box's property")
        ok(lua.run("box.frame = {x = 1, y = 2, w = 3, h = 4}"))
        assertEquals(Rect(1.0, 2.0, 3.0, 4.0), box.getPropertyRaw("frame"), "a frame table becomes a Rect")
        ok(lua.run("box.speed = nil"))
        assertEquals(null, box.getPropertyRaw("speed"), "nil removes the property")
    }

    @Test
    fun childrenAreBoxesAndNamedFindsOne() {
        val (box, lua, _) = boxWithLua("lua-kids")
        val a = Box("lua-kid-a").also { it.name = "a" }
        val b = Box("lua-kid-b").also { it.name = "b" }
        link(box, a); link(box, b)
        val names = ok(lua.run("local out = {}\nfor i, c in ipairs(box.children) do out[i] = c.name end\ntable.sort(out)\nreturn out"))[0]
        assertEquals(listOf("a", "b"), names)
        assertEquals(listOf("lua-kid-b", true), ok(lua.run("local b = field.named('b')\nreturn b.id, field.named('nobody') == nil")))
        // A proxy stored into a property crosses as the box itself.
        ok(lua.run("box.friend = field.named('a')"))
        assertEquals(a, box.getPropertyRaw("friend"))
    }

    @Test
    fun aReadOnlyPropertyIsALuaErrorOnTheLineThatWroteIt() {
        val (_, lua, _) = boxWithLua("lua-readonly")
        val err = assertIs<LuaState.Result.Err>(lua.run("local x = 1\nbox.children = {}\n"))
        assertEquals(2, err.line, "$err")
        assertTrue("can't be set from Lua" in err.message, err.message)
    }

    @Test
    fun linesDrawSvgIntoTheLinesPanel() {
        val (_, lua, out) = boxWithLua("lua-lines")
        val drawn = ok(lua.run("""
            local l = line():moveTo(0, 0):lineTo(100, 50):color(1, 0, 0):width(3)
            local c = line():circle(50, 50, 20):fill()
            local p = line{{0, 0}, {10, 10}, {20, 0}}
            return lines(l, c, p, {w = 200, h = 100})
        """.trimIndent()))
        assertEquals(listOf(3.0), drawn)
        val (channel, html) = out.shown.single()
        assertEquals("lines", channel)
        assertTrue("<svg" in html, html.take(300))
    }

    @Test
    fun luaLinesBecomeFLinesWithTheirStyle() {
        val f = luaLineToFLine(mapOf(
            "ops" to listOf(listOf("m", 0.0, 0.0), listOf("l", 10.0, 0.0), listOf("c", 10.0, 5.0, 5.0, 10.0, 0.0, 10.0), listOf("z")),
            "rgba" to listOf(0.0, 0.5, 1.0), "thickness" to 2.0, "filled" to true,
        ))
        assertEquals(4, f.nodes.size)
        assertEquals(2.0, f.thickness)
        assertTrue(f.filled)
    }

    @Test
    fun assignedNamesReadsPlainAssignmentsOnly() {
        val code = """
            local x = 1
            y, z = 2, 3
            t.field = 4
            function f(a) return a end
            local function g() end
            if x == 1 then end
            local a, b
        """.trimIndent()
        assertEquals(
            mapOf(1 to listOf("x"), 2 to listOf("y", "z"), 4 to listOf("f"), 5 to listOf("g"), 7 to listOf("a", "b")),
            assignedNames(code),
        )
    }

    @Test
    fun aRunsLineNotesSayWhatEachLineCameTo() {
        val (box, lua, _) = boxWithLua("lua-notes")
        val code = "local x = 6 * 7\nfunction twice(v) return v * 2 end\nfor i = 1, 3 do\n  x = twice(x)\nend\nprint(x)\nlocal missing = nil\nlocal boom = missing.field\nlocal after = 1"
        val outcome = lua.runGuarded(code, assignedNames(code))
        assertIs<LuaState.Result.Err>(outcome.result)
        recordNotes(box, code, 0, outcome)
        fun note(line: Int) = assertNotNull(LastRunNotes.note(box, "code", code, line), "no note on line $line").caption
        assertTrue("x = 42" in note(1), note(1))
        assertTrue("defined twice" in note(2), note(2))
        assertTrue("x = 336" in note(4) && "ran 3×" in note(4), note(4))
        assertTrue("stopped here" in note(8) && "attempt to index" in note(8), note(8))
        assertTrue("not reached" in note(9), note(9))
        LastRunNotes.forget(box)
    }

    @Test
    fun theBoxsStateClosesWhenTheBoxRetiresAndNotOnStop() {
        needsLuaJIT()
        val box = Box("lua-lifecycle")
        val execution = LuaExecution(box)
        box.ke = execution
        val before = LuaState.openStates.get()
        execution.luaState()
        assertEquals(before + 1, LuaState.openStates.get())
        execution.stop(box)
        assertTrue(execution.hasState, "stop leaves the state, so the box keeps its globals")
        box.runScopes.closeAll()
        assertEquals(before, LuaState.openStates.get(), "retiring the box closes its Lua")
        assertTrue(!execution.hasState)
    }
}

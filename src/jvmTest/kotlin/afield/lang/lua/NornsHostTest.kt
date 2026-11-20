package afield.lang.lua

import afield.document.graph.Box
import org.junit.Assume
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The norns host a Lua box gets from `require "norns"`: the 128×64 screen's levels, the script
 * lifecycle in virtual time, PolyPerc's sound, and the Lua 5.3 floor-division translation.
 *
 * [NornsScreen] needs no native library and always runs; the rest needs LuaJIT and is skipped without it.
 */
class NornsHostTest {

    private val opened = ArrayList<LuaState>()

    @AfterTest
    fun closeAll() = opened.forEach { it.close() }

    private fun lua(id: String): LuaState {
        Assume.assumeTrue("LuaJIT is not installed: ${LuaLibrary.describe()}", LuaLibrary.isAvailable)
        val box = Box(id)
        box.output.proxy = { _, _, _ -> }
        box.output.displayProxy = { _, _ -> }
        return LuaState(luaHandlersFor(box)).also { opened += it }
    }

    private fun ok(r: LuaState.Result): List<Any?> = assertIs<LuaState.Result.Ok>(r, "expected success, got $r").values

    @Test
    fun aFilledRectIsExactlyItsLevel() {
        val s = NornsScreen()
        s.apply(listOf(listOf("level", 9), listOf("rect", 2, 3, 4, 2), listOf("fill")))
        val lv = s.levels()
        for (y in 0 until NornsScreen.H) for (x in 0 until NornsScreen.W) {
            val inside = x in 2..5 && y in 3..4
            assertEquals(if (inside) 9 else 0, lv[y * NornsScreen.W + x], "pixel $x,$y")
        }
        s.apply(listOf(listOf("clear")))
        assertTrue(s.levels().all { it == 0 }, "clear leaves the screen black")
        assertTrue(s.apply(listOf(listOf("no_such_op"))).contains("no_such_op"), "an unknown op is reported")
    }

    @Test
    fun aScriptRendersOfflineWithItsClockAndKeys() {
        val l = lua("norns-render")
        val v = ok(l.run("""
            local norns = require("norns").reset()
            engine.name = "PolyPerc"
            local notes, keys = 0, {}
            function init()
              params:add_number("step", "step", 1, 8, 2)
              clock.run(function() while true do clock.sync(1 / 4); engine.hz(220); notes = notes + 1 end end)
            end
            function redraw() screen.clear(); screen.level(15); screen.rect(0, 0, 8, 8); screen.fill(); screen.update() end
            function key(n, z) keys[#keys + 1] = n .. ":" .. z end
            function enc(n, d) params:delta("step", d) end
            local heard = norns.render { seconds = 2, events = { { 0.5, "key", 2, 1 }, { 1, "enc", 2, 3 } } }
            local px = screen.peek(0, 0, 9, 1)
            return notes, table.concat(keys, ","), params:get("step"), heard.seconds, heard.peak, px:byte(1), px:byte(9)
        """.trimIndent()))
        // 120 bpm, a note each quarter beat: 16 in two seconds.
        assertEquals(16.0, v[0], "clock.sync(1/4) at 120 bpm")
        assertEquals("2:1", v[1])
        assertEquals(5.0, v[2], "params:delta through enc")
        assertEquals(2.0, v[3])
        assertTrue((v[4] as Double) > 0.05, "PolyPerc sounds: peak ${v[4]}")
        assertEquals(15.0, v[5], "screen.peek reads the level drawn")
        assertEquals(0.0, v[6])
    }

    @Test
    fun floorDivisionIsTranslatedForLuaJIT() {
        val l = lua("norns-idiv")
        val v = ok(l.run("""
            local norns = require "norns"
            local f = assert(loadstring(norns._lua53("return 7 // 2, -7 // 2, (1 + 2) * 9 // 4, '//' .. 9 // 2, #'abcde' // 2")))
            return f()
        """.trimIndent()))
        assertEquals(listOf<Any?>(3.0, -4.0, 6.0, "//4", 2.0), v)
    }
}

package afield.lua

import org.junit.Assume
import org.junit.Before
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LuaState] against a real LuaJIT `lua_State`. Every test here is an `Assume` on [LuaLibrary]: without
 * LuaJIT they are reported as *skipped*, not passed.
 */
class LuaStateTest {

    private val opened = ArrayList<LuaState>()

    @Before
    fun needsLuaJIT() {
        Assume.assumeTrue("LuaJIT is not installed: ${LuaLibrary.describe()}", LuaLibrary.isAvailable)
    }

    @AfterTest
    fun closeAll() = opened.forEach { it.close() }

    private fun state(raw: Map<String, LuaState.Handler> = emptyMap()) = LuaState(raw).also { opened += it }

    private fun ok(r: LuaState.Result): List<Any?> = assertIs<LuaState.Result.Ok>(r, "expected success, got $r").values

    @Test
    fun itIsLuaJIT21() {
        val lua = state()
        assertTrue(lua.version.startsWith("LuaJIT 2.1"), lua.version)
        assertEquals(0, lua.stackTop)
    }

    @Test
    fun valuesCrossBothWays() {
        val lua = state()
        assertEquals(listOf(3.0, "ab", true, null), ok(lua.run("return 1 + 2, 'a' .. 'b', 1 < 2, nil")))
        assertEquals(listOf(listOf(1.0, 2.0, 3.0)), ok(lua.run("return {1, 2, 3}")))
        val map = ok(lua.run("return {x = 1, y = {z = 'deep'}}"))[0] as Map<*, *>
        assertEquals(1.0, map["x"])
        assertEquals("deep", (map["y"] as Map<*, *>)["z"])
        lua.setGlobal("given", mapOf("xs" to listOf(1, 2, 3), "name" to "field", "ok" to true))
        assertEquals(listOf(6.0, "field", true), ok(lua.run("return given.xs[1] + given.xs[2] + given.xs[3], given.name, given.ok")))
        assertEquals(listOf("héllo ☃"), ok(lua.run("return 'héllo ☃'")), "strings are UTF-8 both ways")
        assertEquals(0, lua.stackTop, "nothing is left on the stack")
    }

    @Test
    fun aCycleDoesNotHang() {
        val lua = state()
        val t = ok(lua.run("local t = {a = 1}; t.self = t; return t"))[0] as Map<*, *>
        assertEquals("…", t["self"])
    }

    @Test
    fun kotlinFunctionsAreCallableAndTheirFailuresAreLuaErrorsOnTheCallersLine() {
        val lua = state()
        lua.setGlobal("twice", LuaState.Handler { _, args -> listOf((args[0] as Double) * 2) })
        lua.setGlobal("boom", LuaState.Handler { _, _ -> throw IllegalArgumentException("no thanks") })
        assertEquals(listOf(42.0), ok(lua.run("return twice(21)")))
        val err = assertIs<LuaState.Result.Err>(lua.run("local a = 1\nlocal b = 2\nboom()\n"))
        assertEquals(3, err.line, "the error is on the line that called the failing Kotlin function: $err")
        assertTrue("no thanks" in err.message, err.message)
        // And pcall inside Lua can catch it, like any Lua error.
        assertEquals(listOf(false), ok(lua.run("local ok = pcall(boom); return ok")))
        assertEquals(0, lua.stackTop)
    }

    @Test
    fun luaFunctionsCanBeCalledFromKotlin() {
        val lua = state()
        val f = ok(lua.run("return function(a, b) return a * b, 'done' end"))[0]
        assertIs<LuaState.LuaFunction>(f)
        assertEquals(listOf(12.0, "done"), ok(f(3, 4)))
    }

    @Test
    fun syntaxAndRuntimeErrorsCarryTheirLine() {
        val lua = state()
        val syntax = assertIs<LuaState.Result.Err>(lua.run("local x = 1\nlocal y = = 2\n"))
        assertTrue(syntax.syntax)
        assertEquals(2, syntax.line, "$syntax")
        val runtime = assertIs<LuaState.Result.Err>(lua.run("local t = nil\n\nreturn t.x\n"))
        assertEquals(3, runtime.line, "$runtime")
        assertTrue("attempt to index" in runtime.message, runtime.message)
        // An error object with no position still gets the line from the stack.
        val table = assertIs<LuaState.Result.Err>(lua.run("\nerror({code = 7})\n"))
        assertEquals(2, table.line, "$table")
    }

    @Test
    fun anInfiniteLoopIsStoppedByTheBudget() {
        val lua = state()
        ok(lua.run("field.budget = 0.3"))
        val t0 = System.nanoTime()
        val outcome = lua.runGuarded("local n = 0\nwhile true do n = n + 1 end\n", lineNames = null)
        val seconds = (System.nanoTime() - t0) / 1e9
        val err = assertIs<LuaState.Result.Err>(outcome.result)
        assertTrue("without finishing" in err.message, err.message)
        assertEquals(2, err.line, "$err")
        assertTrue(seconds < 3.0, "stopped after $seconds s")
        // And the state is still usable afterwards.
        assertEquals(listOf(2.0), ok(lua.run("return 1 + 1")))
    }

    @Test
    fun theJITStartsOffAndCanBeTurnedOn() {
        val lua = state()
        assertEquals(listOf(false), ok(lua.run("return (jit.status())")), "the guard needs the interpreter")
        assertEquals(listOf(true), ok(lua.run("jit.on(); return (jit.status())")))
    }

    /**
     * Why the JIT starts off: a count hook fires in interpreted code, and almost never in a loop LuaJIT
     * has compiled. Bounded here (ten million turns), because the unbounded version hangs the thread.
     */
    @Test
    fun aCompiledLoopEscapesTheCountHook() {
        val lua = state()
        val probe = """
            local fired = 0
            debug.sethook(function() fired = fired + 1 end, "", 1000)
            local n = 0
            for i = 1, 1e7 do n = n + i % 3 end
            debug.sethook()
            return fired
        """.trimIndent()
        val interpreted = ok(lua.run(probe))[0] as Double
        val compiled = ok(lua.run("jit.on()\n$probe"))[0] as Double
        println("count hook firings over 1e7 loop turns: interpreted $interpreted, compiled $compiled")
        assertTrue(interpreted >= 10000, "interpreted: the hook fires every 1000 instructions ($interpreted)")
        assertTrue(compiled < interpreted / 100, "compiled: the hook almost never fires ($compiled vs $interpreted)")
    }

    @Test
    fun theRecorderKeepsWhatEachLineAssigned() {
        val lua = state()
        val code = "local x = 6 * 7\ny = x + 1\nlocal s = 'hi'\nfor i = 1, 3 do\n  y = y + i\nend\n"
        val outcome = lua.runGuarded(code, mapOf(1 to listOf("x"), 2 to listOf("y"), 3 to listOf("s"), 5 to listOf("y")))
        ok(outcome.result)
        assertEquals(listOf("x" to "42"), outcome.lines[1]?.values)
        assertEquals(listOf("y" to "43"), outcome.lines[2]?.values)
        assertEquals(listOf("s" to "\"hi\""), outcome.lines[3]?.values)
        assertEquals(3, outcome.lines[5]?.count, "the loop body ran three times")
        assertEquals(listOf("y" to "49"), outcome.lines[5]?.values, "the last value it assigned")
    }

    @Test
    fun closingReleasesTheStateAndItsHandlers() {
        val before = LuaState.openStates.get()
        val handlersBefore = LuaState.liveHandlers
        val lua = LuaState(mapOf("print" to LuaState.Handler { _, _ -> emptyList() }))
        lua.setGlobal("f", LuaState.Handler { _, _ -> emptyList() })
        assertEquals(before + 1, LuaState.openStates.get())
        lua.close()
        lua.close()
        assertEquals(before, LuaState.openStates.get(), "close is idempotent and counted once")
        assertEquals(handlersBefore, LuaState.liveHandlers, "no handler outlives its state")
        assertTrue(lua.isClosed)
    }
}

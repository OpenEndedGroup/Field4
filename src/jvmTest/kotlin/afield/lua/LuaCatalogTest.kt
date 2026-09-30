package afield.lua

import afield.Box
import org.junit.Assume
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LuaCatalog] held to a live LuaJIT state with Field's prelude loaded, in both directions: every
 * function the state has in `_G`, the standard libraries, `field` and `field.Line` has a catalogue
 * line, and every line names a function the state has. Skipped (not passed) without LuaJIT.
 */
class LuaCatalogTest {

    @Before
    fun needsLuaJIT() {
        Assume.assumeTrue("LuaJIT is not installed: ${LuaLibrary.describe()}", LuaLibrary.isAvailable)
    }

    private val tables = listOf("string", "table", "math", "os", "io", "coroutine", "debug", "bit", "jit", "package", "field")

    @Test
    fun theCatalogueIsExactlyTheLiveStatesFunctions() {
        LuaState(luaHandlersFor(Box("lua-catalog"))).use { lua ->
            val listing = """
                local out = {}
                local function add(prefix, t)
                  for k, v in pairs(t) do
                    if type(k) == "string" and type(v) == "function" and not k:find("^__") then out[#out + 1] = prefix .. k end
                  end
                end
                add("", _G)
                for _, name in ipairs({${tables.joinToString(", ") { "\"$it\"" }}}) do add(name .. ".", _G[name]) end
                add("Line.", field.Line)
                return out
            """.trimIndent()
            @Suppress("UNCHECKED_CAST")
            val live = (assertIs<LuaState.Result.Ok>(lua.run(listing)).values[0] as List<String>).toSortedSet()
            val catalogued = (LuaCatalog.stdlib.keys + LuaCatalog.field.keys).toSortedSet()
            println("live functions ${live.size}; catalogued ${catalogued.size} (${LuaCatalog.stdlib.size} Lua/LuaJIT, ${LuaCatalog.field.size} Field)")
            assertEquals(emptySet(), live - catalogued, "functions with no catalogue line")
            assertEquals(emptySet(), catalogued - live, "catalogue lines naming a function the state does not have")
            assertTrue(LuaCatalog.stdlib.size >= 146 && LuaCatalog.field.size >= 34, "the catalogues shrank")
        }
    }

    @Test
    fun completionCarriesTheSignatureAndWhatItDoes() {
        val box = Box("lua-catalog-complete")
        val execution = LuaExecution(box)
        box.ke = execution
        try {
            execution.luaState()
            val format = execution.luaCompletions("string", "form").single()
            assertEquals("format", format.first)
            assertTrue(format.third.startsWith("string.format(fmt, ...) — "), format.third)
            val play = execution.luaCompletions("", "pla").single { it.first == "play" }
            assertTrue("pattern voice" in play.third, play.third)
            val moveTo = execution.luaCompletions("field.Line", "move").single()
            assertTrue(moveTo.third.startsWith("Line.moveTo(x, y)"), moveTo.third)
        } finally {
            execution.close()
        }
    }
}

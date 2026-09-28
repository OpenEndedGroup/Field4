package afield.python

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Python line-note rewriter ([PythonLineNotes.SOURCE]) run exactly as Field splices it into the
 * interpreter, under a plain `python3` — no JEP needed. Skipped when there is no `python3` on the path.
 *
 * Each case is one that the first version got wrong (2026-09-28): a docstring or `__future__` imports
 * at the top of a cell, a value whose `__repr__` must not run, a cell that assigns the recorder's name,
 * and the environment switch that turns it all off.
 */
class PythonLineNotesTest {

    /** Parse, rewrite, compile and run [cell] in a fresh namespace; what came back, as JSON. */
    private val harness = """
import ast, json, sys
def run(cell, key="<repl:test>"):
    mod = ast.parse(cell, mode="exec")
    before = ast.dump(mod)
    name = _field_line_notes.instrument(mod, key, "__cap__", cell)
    ast.fix_missing_locations(mod)
    scope = {}
    error = None
    try:
        code = compile(mod, key, "exec")
        with _field_line_notes.bind(scope, name):
            exec(code, scope, scope)
    except BaseException as e:
        error = type(e).__name__ + ": " + str(e)
    return {"name": name, "unchanged": ast.dump(mod) == before, "error": error,
            "nameInSource": bool(name) and name in cell, "recorderLeft": bool(name) and name in scope,
            "notes": json.loads(_field_line_notes.take(key)), "scope": {k: repr(v) for k, v in scope.items() if k in ("x", "y", "calls")}}
cells = json.loads(sys.stdin.read())
print(json.dumps([run(c) for c in cells]))
"""

    private fun python(): String? = listOf("python3", "/usr/bin/python3", "/opt/homebrew/bin/python3").firstOrNull { exe ->
        runCatching { ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun run(cells: List<String>, env: Map<String, String> = emptyMap()): List<JsonObject> {
        val exe = python()
        Assume.assumeTrue("no python3 on this machine", exe != null)
        val dir = Files.createTempDirectory("python-line-notes").toFile()
        try {
            val script = File(dir, "harness.py").apply { writeText(PythonLineNotes.SOURCE + "\n" + harness) }
            val process = ProcessBuilder(exe, script.path).also { it.environment().putAll(env) }.start()
            process.outputStream.use { it.write(JsonArray(cells.map { c -> JsonPrimitive(c) }).toString().toByteArray()) }
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "python3 failed: $err")
            return Json.parseToJsonElement(out).jsonArray.map { it.jsonObject }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun JsonObject.error() = this["error"].let { if (it is JsonNull) null else it!!.jsonPrimitive.content }
    private fun JsonObject.notes() = this["notes"]!!.jsonArray.map { it.jsonArray }

    @Test
    fun aDocstringAndFutureImportsStayFirstAndUnrecorded() {
        val (docstring, twoFutures) = run(listOf(
            "\"\"\"A cell with a docstring.\"\"\"\nfrom __future__ import annotations\nx = 1\n",
            "from __future__ import annotations\nfrom __future__ import division\nx = 3 / 2\n",
        ))
        assertNull(docstring.error(), "a docstring then a __future__ import still compiles")
        assertNull(twoFutures.error(), "two __future__ imports still compile")
        // Only the statement after the header is recorded.
        assertEquals(listOf(3), docstring.notes().map { it[0].jsonPrimitive.content.toInt() })
        assertEquals("1", docstring.notes().single()[4].jsonPrimitive.content)
        assertEquals("1.5", twoFutures.notes().single()[4].jsonPrimitive.content)
    }

    @Test
    fun aValueOfAUserTypeIsNamedButItsReprNeverRuns() {
        val (result) = run(listOf(
            "calls = []\nclass Loud:\n    def __repr__(self):\n        calls.append(1)\n        return 'loud'\n" +
                "x = Loud()\nLoud()\ny = [Loud()]\n[1, 2, 'three']\n'z' * 500\n",
        ))
        assertNull(result.error())
        assertEquals("[]", result["scope"]!!.jsonObject["calls"]!!.jsonPrimitive.content, "no __repr__ ran")
        val byLine = result.notes().associate { it[0].jsonPrimitive.content.toInt() to it }
        assertEquals("Loud", byLine[6]!![3].jsonPrimitive.content)
        assertTrue(byLine[6]!![4] is JsonNull, "x = Loud(): the type, not a repr")
        assertTrue(byLine[7]!![4] is JsonNull)
        assertTrue(byLine[8]!![4] is JsonNull, "a list of user objects is not printed either")
        assertEquals("[1, 2, 'three']", byLine[9]!![4].jsonPrimitive.content)
        assertTrue(byLine[10]!![4].jsonPrimitive.content.length <= 100, "a long string is cut")
    }

    @Test
    fun aCellCannotBreakTheRecorderByAssigningItsName() {
        val (result) = run(listOf("__field_line_note__ = None\n_field_line_notes = None\nx = 1\ny = x + 1\n"))
        assertNull(result.error())
        assertEquals(false, result["nameInSource"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, result["recorderLeft"]!!.jsonPrimitive.content.toBoolean(), "the recorder is unbound after the run")
        assertEquals("2", result.notes().last()[4].jsonPrimitive.content)
        assertEquals("2", result["scope"]!!.jsonObject["y"]!!.jsonPrimitive.content)
    }

    @Test
    fun theEnvironmentSwitchLeavesTheCellUntouched() {
        val (result) = run(listOf("x = 1\nx + 1\n"), env = mapOf("AFIELD_PYTHON_LINE_NOTES" to "0"))
        assertTrue(result["name"] is JsonNull)
        assertEquals(true, result["unchanged"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result.notes().isEmpty())
        assertNull(result.error())
    }
}

package afield.lang.python

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [PythonErrors.SOURCE] run under a plain `python3`, compiling cells under the same `<repl:...>`
 * filename a Python box uses. Before it, every runtime error in a Python box was marked on the
 * fragment's first line. Skipped when there is no `python3`.
 */
class PythonErrorsTest {

    private val harness = """
import json, sys
def run(cell, key="<repl:test>"):
    try:
        exec(compile(cell, key, "exec"), {}, None)
        return None
    except BaseException as e:
        return _field_error_location(e, key)
cells = json.loads(sys.stdin.read())
print(json.dumps([run(c) for c in cells]))
"""

    private fun python(): String? = listOf("python3", "/usr/bin/python3", "/opt/homebrew/bin/python3").firstOrNull { exe ->
        runCatching { ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun locate(vararg cells: String): List<List<String>> {
        val exe = python()
        Assume.assumeTrue("no python3 on this machine", exe != null)
        val dir = Files.createTempDirectory("python-errors").toFile()
        try {
            val script = File(dir, "harness.py").apply { writeText(PythonErrors.SOURCE + "\n" + harness) }
            val process = ProcessBuilder(exe, script.path).start()
            process.outputStream.use { it.write(JsonArray(cells.map { JsonPrimitive(it) }).toString().toByteArray()) }
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            return Json.parseToJsonElement(out).jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content } }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRuntimeErrorIsOnTheLineThatRaised() {
        val (row) = locate("a = 1\nb = 2\nc = a / 0\n")
        assertEquals(listOf("3", "ZeroDivisionError", "division by zero"), row)
    }

    @Test
    fun anErrorInADefinedFunctionIsOnTheLineInsideIt() {
        val (row) = locate("def f(x):\n    return x + undefined_name\n\nf(1)\n")
        assertEquals("2", row[0], "the innermost cell frame, not the call site")
        assertEquals("NameError", row[1])
    }

    @Test
    fun anErrorInsideALibraryIsOnTheCellLineThatCalledIt() {
        val (row) = locate("import json\nx = 1\njson.loads('{not json')\n")
        assertEquals("3", row[0])
        assertEquals("JSONDecodeError", row[1])
    }

    @Test
    fun aSyntaxErrorReportsItsOwnLine() {
        val (row) = locate("x = 1\ny = (2 +\nz = 3\n")
        assertEquals("SyntaxError", row[1])
        assertEquals(true, row[0].toInt() in 2..3, "the unclosed paren's line or the next, not line 1: $row")
    }

    @Test
    fun boxLinesFollowThePrintRule() {
        assertEquals(13, PythonErrors.boxLine(3, 10))
        assertEquals(14, PythonErrors.boxLine(3, 10, droppedLeadingBlank = 1))
        assertEquals(-1, PythonErrors.boxLine(-1, 10))
        assertEquals("NameError: name 'q' is not defined", PythonErrors.describe("NameError", "name 'q' is not defined"))
        assertEquals("KeyboardInterrupt", PythonErrors.describe("KeyboardInterrupt", ""))
    }
}

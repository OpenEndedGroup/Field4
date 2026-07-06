package afield.ml.tests

import afield.HasOutput
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLValue
import afield.ml.core.renderFieldMLValue
import afield.ml.renderInlineResult
import afield.stdlib.color
import afield.stdlib.thickness
import afield.graphics.FLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FieldMLInteropTest {
    @Test
    fun inlineResultIsPlainTextForMonacoDecorations() {
        assertEquals("42 : Int", renderInlineResult("42", "Int"))
        assertEquals("42", renderInlineResult("42", ""))
    }

    @Test
    fun outputBuiltinsArePipeFriendlyTaps() {
        val output = CapturingOutput()
        val value = evaluate(
            """
            42
              |> print
              |> println
              |> display "main"
            """,
            output,
        )

        assertEquals("42", renderFieldMLValue(value))
        assertEquals(listOf("42"), output.prints)
        assertEquals(listOf("42"), output.printlns)
        assertEquals(listOf<Pair<String, Any?>>("main" to "42"), output.displays)
    }

    @Test
    fun curatedVectorAndFLineInteropBuildsRealFieldObjects() {
        val result = compileOk(
            """
            fline
              |> moveTo 0 0
              |> lineTo 10 20
              |> thickness 2
              |> color (vec4 1 0 0 1)
            """,
        )

        assertEquals("FLine", afield.ml.type.TypePrinter().print(result.typed!!.type))

        val value = Evaluator(FieldMLRuntimePrelude.values()).evaluate(result.core!!)
        val line = assertIs<FieldMLValue.HostValue>(value).value
        val fline = assertIs<FLine>(line)
        assertEquals(2, fline.nodes.size)
        assertEquals(2.0, fline.thickness)
        assertEquals(1.0f, fline.color.x)
        assertEquals(0.0f, fline.color.y)
        assertEquals(0.0f, fline.color.z)
        assertEquals(1.0f, fline.color.w)
    }

    @Test
    fun genericHostBridgeCanCallMethodsAndReadMembers() {
        val lineValue = evaluate(
            """
            fline
              |> hostCall1 "moveTo" (vec2 1 2)
              |> hostCall1 "lineTo" (vec2 3 4)
            """,
        )

        val line = assertIs<FLine>(assertIs<FieldMLValue.HostValue>(lineValue).value)
        assertEquals(2, line.nodes.size)

        val xValue = evaluate("vec2 7 8 |> hostGet \"x\"")
        assertEquals("7.0", renderFieldMLValue(xValue))
    }

    private fun evaluate(source: String, output: HasOutput? = null): FieldMLValue {
        val result = compileOk(source)
        return Evaluator(FieldMLRuntimePrelude.values(output)).evaluate(result.core!!)
    }

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
        return result
    }

    private class CapturingOutput : HasOutput {
        val prints = mutableListOf<String>()
        val printlns = mutableListOf<String>()
        val displays = mutableListOf<Pair<String, Any?>>()
        val errors = mutableListOf<Any?>()

        override fun print(message: Any?) {
            prints += message.toString()
        }

        override fun println(message: Any?) {
            printlns += message.toString()
        }

        override fun display(message: Any?) {
            displays += "output" to message
        }

        override fun display(channel: String, message: Any?) {
            displays += channel to message
        }

        override fun error(message: Any?, property: String, line: Int) {
            errors += message
        }
    }
}

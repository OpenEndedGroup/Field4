package afield.lang.fieldml.tests

import afield.document.graph.Box
import afield.ui.output.HasOutput
import afield.lang.fieldml.CompilationResult
import afield.lang.fieldml.FieldMLCompiler
import afield.lang.fieldml.FieldMLRuntimePrelude
import afield.lang.fieldml.core.Evaluator
import afield.lang.fieldml.core.FieldMLValue
import afield.lang.fieldml.core.renderFieldMLValue
import afield.lang.fieldml.renderInlineResult
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.geometry.path.FLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FieldMLInteropTest {
    @Test
    fun hostTapExecutionRecoversFromAnIndexWithMovedSourceFiles() = withStaleFieldMLSourceIndex {
        hostTapBuildsJvmInteropWrappersFromIndexedSourceMetadata()
    }

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

        assertEquals("FLine", afield.lang.fieldml.type.TypePrinter().print(result.typed!!.type))

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

    @Test
    fun hostStaticCallCanReachKotlinBoxLinesExtension() {
        val box = Box("fieldml-host-static-test")
        val displays = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, message -> displays += channel to message }

        val value = evaluate(
            """
            let lines channel line =
              let _ = hostStaticCall "lines" (thisBox, [line], channel, 120.0, 80.0, false, false, false, false) (hostClass "afield.ui.visualization.SomeSkikoKt") in line

            fline
              |> moveTo 0.0 0.0
              |> lineTo 80.0 40.0
              |> lines "foo"
            """,
            box = box,
        )

        assertIs<FLine>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals("foo", displays.single().first)
        assertTrue(displays.single().second.toString().contains("data-afield-lines-output=\"true\""))
    }

    @Test
    fun tapCanSequenceHostInteropAndReturnInput() {
        val box = Box("fieldml-tap-host-test")
        val displays = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, message -> displays += channel to message }

        val value = evaluate(
            """
            let lines channel line =
              line |> tap (fun line ->
                hostStaticCall "lines" (thisBox, [line], channel, 120.0, 80.0, false, false, false, false) (hostClass "afield.ui.visualization.SomeSkikoKt")
              )

            fline
              |> moveTo 0.0 0.0
              |> lineTo 80.0 40.0
              |> lines "foo"
            """,
            box = box,
        )

        assertIs<FLine>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals("foo", displays.single().first)
        assertTrue(displays.single().second.toString().contains("data-afield-lines-output=\"true\""))
    }

    @Test
    fun hostStaticTapBuildsPipeFriendlyJvmInteropWrappers() {
        val box = Box("fieldml-host-static-tap-test")
        val displays = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, message -> displays += channel to message }

        val value = evaluate(
            """
            let lines channel =
              hostStaticTap "lines" (thisBox, [hostInput], channel, 120.0, 80.0, false, false, false, false) (hostClass "afield.ui.visualization.SomeSkikoKt")

            fline
              |> moveTo 0.0 0.0
              |> lineTo 80.0 40.0
              |> lines "foo"
            """,
            box = box,
        )

        assertIs<FLine>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals("foo", displays.single().first)
        assertTrue(displays.single().second.toString().contains("data-afield-lines-output=\"true\""))
    }

    @Test
    fun hostTapBuildsJvmInteropWrappersFromIndexedSourceMetadata() {
        val box = Box("fieldml-host-tap-test")
        val displays = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, message -> displays += channel to message }

        val value = evaluate(
            """
            let lines channel =
              hostTap "afield.ui.visualization.lines" { channel = channel, w = 120.0, h = 80.0 }

            fline
              |> moveTo 0.0 0.0
              |> lineTo 80.0 40.0
              |> lines "foo"
            """,
            box = box,
        )

        assertIs<FLine>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals("foo", displays.single().first)
        assertTrue(displays.single().second.toString().contains("data-afield-lines-output=\"true\""))
    }

    private fun evaluate(source: String, output: HasOutput? = null, box: Box? = null): FieldMLValue {
        val result = compileOk(source)
        return Evaluator(FieldMLRuntimePrelude.values(output, box)).evaluate(result.core!!)
    }

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            result.diagnostics.joinToString("\n") {
                val range = it.range?.start?.let { start -> "${start.line}:${start.column}" } ?: "?:?"
                "${it.severity}@$range: ${it.message}"
            },
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

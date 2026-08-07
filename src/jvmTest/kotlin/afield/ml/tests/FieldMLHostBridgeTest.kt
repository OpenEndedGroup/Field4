package afield.ml.tests

import afield.Box
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLValue
import afield.ml.core.renderFieldMLValue
import afield.ml.type.TypePrinter
import afield.graphics.FLine
import afield.stdlib.Img
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The JVM bridge, one capability per test: n-ary calls, constructors with arguments, SAM conversion,
 * overload resolution, arrays, varargs, null, static members — and the types the checker now infers
 * for all of them.
 *
 * Each of these is a call that the previous bridge refused outright, so a failure here is a
 * capability regression rather than a detail.
 */
class FieldMLHostBridgeTest {
    // ---- arity -------------------------------------------------------------------------------

    @Test
    fun naryConstructorTakesArgumentsAndAFieldMLListBecomesAJvmArray() {
        val result = compileOk(
            """
            let Img = hostClass "afield.stdlib.Img"
            let img = hostNew (2, 2, 1, [0.0, 0.25, 0.5, 1.0]) Img

            (img.width, img.height, img.pixelCount)
            """,
        )

        assertEquals("(Int, Int, Int)", TypePrinter().print(result.typed!!.type))
        assertEquals("(2, 2, 4)", renderFieldMLValue(evaluate(result)))
    }

    @Test
    fun naryInstanceCallReachesAThreeArgumentMethod() {
        // Img.index(x, y, c) — three arguments, which hostCall0/1/2 could not express at all.
        assertEquals(
            "5",
            renderFieldMLValue(
                run(
                    """
                    let Img = hostClass "afield.stdlib.Img"
                    let img = hostNew (2, 2, 3, [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]) Img

                    img |> hostCall "index" (1, 0, 2)
                    """,
                ),
            ),
        )
    }

    @Test
    fun theOldZeroOneAndTwoArgumentFormsStillWork() {
        val value = run(
            """
            fline
              |> hostCall1 "moveTo" (vec2 1 2)
              |> hostCall1 "lineTo" (vec2 3 4)
              |> hostCall2 "rot" 0.0 (vec2 0 0)
              |> hostCall0 "close"
            """,
        )
        val line = assertIs<FLine>(assertIs<FieldMLValue.HostValue>(value).value)
        assertEquals(3, line.nodes.size)
    }

    // ---- lambdas -----------------------------------------------------------------------------

    @Test
    fun aFieldMLFunctionDrivesAKotlinHigherOrderApiThroughSamConversion() {
        // Img.Companion.generate(width, height, (x, y) -> Float): three arguments *and* a Kotlin
        // lambda parameter, so it needs both halves of this work at once. `Float` is the generic
        // argument of Function2, which is only visible on the parameter's generic type.
        val result = compileOk(
            """
            let Img = hostClass "afield.stdlib.Img"
            let companion = Img |> hostGet "Companion"

            companion |> hostCall "generate" (4, 4, fun x y -> toFloat (x + y) / 6.0)
            """,
        )

        assertEquals("Img", TypePrinter().print(result.typed!!.type))
        val img = assertIs<Img>(assertIs<FieldMLValue.HostValue>(evaluate(result)).value)
        assertEquals(4, img.width)
        assertEquals(1, img.channels)
        assertEquals(0f, img.at(0, 0))
        assertEquals(1f, img.at(3, 3), 1e-6f)
        assertEquals(0.5f, img.at(3, 0), 1e-6f)
    }

    @Test
    fun aFieldMLFunctionSatisfiesAJavaFunctionalInterface() {
        // Collections.max(collection, comparator) — a java.util.Comparator whose single method
        // returns a primitive `int`, reversed here so the answer cannot come from the natural order.
        // Its result type is erased to Object, so it stays `Host` and needs an explicit assertion:
        // that is the honest shape of an erased generic, not a gap in the bridge.
        assertEquals(
            "1",
            renderFieldMLValue(
                run(
                    """
                    let Collections = hostClass "java.util.Collections"

                    hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) Collections
                      |> hostAsInt
                    """,
                ),
            ),
        )
    }

    @Test
    fun aFieldMLFunctionWarpsRealGeometryThroughFLineTransform() {
        // FLine.transform((Vector3f) -> Vector3f) is the shape most of Field's geometry API takes:
        // a lambda in, a host value out. The FieldML function receives a Vec3 and returns one.
        // The JVM hands the lambda a value the type checker knows nothing about, so `hostAs` is where
        // it gets a type — and from there `.x`/`.y`/`.z` are ordinary checked vector components.
        val result = compileOk(
            """
            fline
              |> moveTo 0.0 0.0
              |> lineTo 10.0 0.0
              |> hostCall1 "transform" (fun raw ->
                   let p = hostAs "Vec3" raw in
                   vec3 (p.x * 2.0) (p.y + 5.0) p.z
                 )
            """,
        )

        assertEquals("FLine", TypePrinter().print(result.typed!!.type))
        val line = assertIs<FLine>(assertIs<FieldMLValue.HostValue>(evaluate(result)).value)
        assertEquals(20.0f, line.nodes[1].to.x, 1e-5f)
        assertEquals(5.0f, line.nodes[1].to.y, 1e-5f)
    }

    @Test
    fun hostExtCallsAKotlinExtensionWithThePipedValueAsItsReceiver() {
        // Field's Kotlin surface is mostly extension functions, which are statics whose first
        // parameter is the receiver — so this is the form that actually reaches them from a pipe.
        val result = compileOk(
            """
            let Img = hostClass "afield.stdlib.Img"
            let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"

            hostNew (2, 1, 1, [0.0, 1.0]) Img
              |> hostExt Imaging "colormap" ("magma", hostNull, hostNull)
            """,
        )

        assertEquals("Img", TypePrinter().print(result.typed!!.type))
        assertEquals(3, assertIs<Img>(assertIs<FieldMLValue.HostValue>(evaluate(result)).value).channels)
    }

    // ---- overloads, widening, null, varargs --------------------------------------------------

    @Test
    fun overloadResolutionPicksTheCandidateThatMatchesTheArgumentWidths() {
        // Math.max is declared four times over. Picking the first name-and-arity match, as the
        // bridge used to, returns whichever the JVM happens to list first.
        val ints = compileOk("""hostStaticCall "max" (3, 7) (hostClass "java.lang.Math")""")
        assertEquals("Int", TypePrinter().print(ints.typed!!.type))
        assertEquals("7", renderFieldMLValue(evaluate(ints)))

        val floats = compileOk("""hostStaticCall "max" (3.5, 7.25) (hostClass "java.lang.Math")""")
        assertEquals("Float", TypePrinter().print(floats.typed!!.type))
        assertEquals("7.25", renderFieldMLValue(evaluate(floats)))
    }

    @Test
    fun aFieldMLFloatNarrowsToAKotlinFloatParameter() {
        // FieldML has one float type and it is a Double; Img.filled wants a `float`. Before scoring
        // and coercion this call simply found no candidate.
        val value = run(
            """
            let Img = hostClass "afield.stdlib.Img"
            let companion = Img |> hostGet "Companion"

            companion
              |> hostCall "filled" (2, 2, 1, 0.75)
              |> hostCall "at" (1, 1, 0)
            """,
        )
        assertEquals("0.75", renderFieldMLValue(value))
    }

    @Test
    fun hostNullFillsAnOptionalKotlinParameter() {
        // colormap(img, name, lo, hi) with nullable bounds: a reflective call cannot use Kotlin
        // default arguments, so the absent ones have to be spelled.
        val result = compileOk(
            """
            let Img = hostClass "afield.stdlib.Img"
            let Imaging = hostClass "afield.stdlib.Imaging_fieldKt"
            let gray = hostNew (2, 1, 1, [0.0, 1.0]) Img

            hostStaticCall "colormap" (gray, "viridis", hostNull, hostNull) Imaging
            """,
        )

        assertEquals("Img", TypePrinter().print(result.typed!!.type))
        val coloured = assertIs<Img>(assertIs<FieldMLValue.HostValue>(evaluate(result)).value)
        assertEquals(3, coloured.channels)
        assertTrue(coloured.at(0, 0) != coloured.at(1, 0), "a colormap should separate the two ends")
    }

    @Test
    fun varargsArePackedFromTheSpreadArguments() {
        assertEquals(
            "\"x=42 y=7\"",
            renderFieldMLValue(
                run("""hostStaticCall "format" ("x=%d y=%d", 42, 7) (hostClass "java.lang.String")"""),
            ),
        )
    }

    // ---- types -------------------------------------------------------------------------------

    @Test
    fun memberTypesAreInferredThroughTheBridgeRatherThanBeingOpaque() {
        assertEquals("String", typeOf("""thisBox |> hostGet "id""""))
        assertEquals("String", typeOf("thisBox.name"))
        assertEquals("Box", typeOf("thisBox.thisBox"))
        assertEquals("Int", typeOf("""fline |> hostGet "rev""""))
        assertEquals("[Node]", typeOf("""fline |> hostGet "nodes""""))
        assertEquals("Vec3", typeOf("""fline |> hostCall1 "endPointOf" 0"""))
        // A class handle is a type of its own, and reading a static member off it works.
        assertEquals("Class<Img>", typeOf("""hostClass "afield.stdlib.Img""""))
        assertEquals("Companion", typeOf("""hostClass "afield.stdlib.Img" |> hostGet "Companion""""))
    }

    @Test
    fun inferredMemberTypesFlowIntoOrdinaryFieldMLArithmetic() {
        // The point of inferring `Int` rather than `Host`: the rest of the language works downstream
        // with no casts at all.
        val result = compileOk(
            """
            let Img = hostClass "afield.stdlib.Img"
            let img = hostNew (8, 4, 1, tabulate (fun i -> toFloat i) 32) Img

            img.width * img.height + img.channels
            """,
        )
        assertEquals("Int", TypePrinter().print(result.typed!!.type))
        assertEquals("33", renderFieldMLValue(evaluate(result)))
    }

    @Test
    fun aMisspelledMemberIsATypeErrorRatherThanARuntimeCrash() {
        assertRefused("""thisBox |> hostGet "widht"""", "has no member 'widht'")
        assertRefused("thisBox.notAThing", "has no member 'notAThing'")
        assertRefused("""thisBox |> hostCall0 "notAMethod"""", "has no method 'notAMethod'")
        assertRefused("""hostClass "afield.stdlib.NotAClassAtAll"""", "Unknown JVM class")
    }

    @Test
    fun anImpossibleArgumentListIsATypeError() {
        assertRefused(
            """fline |> hostCall "moveTo" ("not", "numbers", "at", "all")""",
            "has no method 'moveTo'",
        )
    }

    @Test
    fun anOpaqueHostReceiverIsLeftDynamicRatherThanRefused() {
        // `hostInput` is a placeholder with no class, so nothing can be said about it — and nothing
        // should be claimed. This is the case that must keep working for programs written before the
        // bridge knew any types at all.
        assertEquals("Host", typeOf("""hostInput |> hostGet "anythingAtAll""""))
        assertEquals("Host", typeOf("""hostInput |> hostCall "whatever" (1, 2)"""))
    }

    @Test
    fun aNullableKotlinReturnStaysOpaqueInsteadOfClaimingItsClass() {
        // Box.lines returns EditableFLineViewState? and really does return null here, so naming the
        // class would be a type the very first run violates.
        assertEquals(
            "Host",
            typeOf(
                """
                hostStaticCall "lines" (thisBox, [fline], "main", 10.0, 10.0, false, false, false, false)
                  (hostClass "afield.graphics.SomeSkikoKt")
                """,
            ),
        )
    }

    // ---- harness -----------------------------------------------------------------------------

    private fun typeOf(source: String): String = TypePrinter().print(compileOk(source).typed!!.type)

    private fun assertRefused(source: String, messageContains: String) {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(!result.successful, "expected '$source' to be refused, it compiled")
        assertTrue(
            result.diagnostics.any { messageContains in it.message },
            "expected a diagnostic containing '$messageContains', got ${result.diagnostics.joinToString { it.message }}",
        )
    }

    private fun run(source: String): FieldMLValue = evaluate(compileOk(source))

    private fun evaluate(result: CompilationResult): FieldMLValue =
        Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!)

    private val box = Box("fieldml-host-bridge-test")

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
        return result
    }
}

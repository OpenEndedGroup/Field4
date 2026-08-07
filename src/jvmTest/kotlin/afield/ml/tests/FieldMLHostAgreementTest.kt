package afield.ml.tests

import afield.Box
import afield.graphics.FLine
import afield.ml.CompilationResult
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.fieldMLValueToHost
import afield.ml.type.TypePrinter
import afield.stdlib.Img
import afield.stdlib.colormap
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The calls whose *meaning* matters, checked against the same call written in Kotlin.
 *
 * `FieldMLHostSurfaceCoverageTest` sweeps 362 methods and compares every result, but the lambdas it
 * synthesises return a constant — so it proves the SAM *result* crosses correctly and says nothing
 * about the arguments travelling the other way. These cases fill that in: each one is a FieldML
 * program beside the Kotlin it is supposed to be equivalent to, with a body that depends on every
 * argument the JVM passes back in, and an exact comparison.
 *
 * Three deliberate details:
 *
 * *Divisors are powers of two.* FieldML arithmetic is double precision and narrows on the way into a
 * `float` parameter, while the Kotlin equivalent may compute in `Float` throughout. With `/ 64.0`
 * both are exact and a difference is a real difference; with `/ 40.0` the last bit would be a coin
 * toss and this test would be about IEEE 754 rather than about the bridge.
 *
 * *Comparisons are exact.* A tolerance here would hide precisely the bug worth catching — a value
 * that went through the wrong width on its way across.
 *
 * *The Kotlin side is written out, not derived.* Nothing in this file calls the bridge to work out
 * what the answer should be.
 */
class FieldMLHostAgreementTest {
    // ---- arguments travelling into a FieldML lambda ---------------------------------------------

    @Test
    fun aTwoArgumentKotlinLambdaSeesTheSamePixelCoordinates() {
        // Img.Companion.generate(w, h) { x, y -> Float }: the FieldML body depends on both arguments,
        // so a bridge that passed them in the wrong order, boxed the wrong width, or reused one value
        // for every pixel would produce a different raster.
        val throughFieldML = evaluateAs<Img>(
            """
            let Img = hostClass "afield.stdlib.Img"

            Img
              |> hostGet "Companion"
              |> hostCall "generate" (6, 5, fun x y -> toFloat (x * 5 + y) / 64.0)
            """,
        )
        val inKotlin = Img.generate(6, 5) { x, y -> ((x * 5 + y).toDouble() / 64.0).toFloat() }

        assertEquals(inKotlin.width, throughFieldML.width)
        assertEquals(inKotlin.height, throughFieldML.height)
        assertContentEquals(inKotlin.data, throughFieldML.data)
        // Not a constant raster — otherwise the argument check above would be vacuous.
        assertTrue(inKotlin.data.distinct().size == 30, "expected 30 distinct samples, got ${inKotlin.data.distinct().size}")
    }

    @Test
    fun aFourArgumentKotlinLambdaSeesEveryIndexAndTheSample() {
        // Img.mapIndexed { x, y, c, v -> Float } — four arguments, three Ints and a Float, mixed.
        val source = imgSource + """

            target |> hostCall1 "mapIndexed" (fun x y c v -> v + toFloat (x * 4 + y + c) / 64.0)
        """.trimIndent()
        val throughFieldML = evaluateAs<Img>(source)
        val inKotlin = imgKotlin().mapIndexed { x, y, c, v -> v + ((x * 4 + y + c).toDouble() / 64.0).toFloat() }

        assertContentEquals(inKotlin.data, throughFieldML.data)
    }

    @Test
    fun aSingleArgumentKotlinLambdaSeesTheSampleValue() {
        val throughFieldML = evaluateAs<Img>(imgSource + "\n\ntarget |> hostCall1 \"map\" (fun v -> v * 0.25 - 0.125)")
        val inKotlin = imgKotlin().map { v -> (v.toDouble() * 0.25 - 0.125).toFloat() }

        assertContentEquals(inKotlin.data, throughFieldML.data)
    }

    @Test
    fun aKotlinLambdaOverHostValuesAgreesPointForPoint() {
        // FLine.transform((Vector3f) -> Vector3f): a host value in, a host value out. `hostAs` is where
        // the value the JVM handed back gets a type.
        val throughFieldML = evaluateAs<FLine>(
            """
            fline
              |> moveTo 0.0 0.0
              |> lineTo 16.0 8.0
              |> lineTo 32.0 0.0
              |> hostCall1 "transform" (fun raw ->
                   let p = hostAs "Vec3" raw in
                   vec3 (p.x * 2.0) (p.y + 4.0) (p.z - 1.0)
                 )
            """,
        )
        val inKotlin = FLine().moveTo(0.0, 0.0).lineTo(16.0, 8.0).lineTo(32.0, 0.0)
            .transform { p -> Vector3f(p.x * 2.0f, p.y + 4.0f, p.z - 1.0f) }

        assertEquals(inKotlin.nodes.size, throughFieldML.nodes.size)
        inKotlin.nodes.indices.forEach { index ->
            val expected = inKotlin.nodes[index].toOrNull!!
            val actual = throughFieldML.nodes[index].toOrNull!!
            assertEquals(expected.x, actual.x, "node $index x")
            assertEquals(expected.y, actual.y, "node $index y")
            assertEquals(expected.z, actual.z, "node $index z")
        }
    }

    @Test
    fun aKotlinLambdaReturningBooleanAgrees() {
        // FLine.byFiltering((Vector3f, Node) -> Boolean): a primitive-boolean result through the erased
        // `Object` return of Function2.
        val throughFieldML = evaluateAs<FLine>(
            """
            fline
              |> moveTo 0.0 0.0
              |> lineTo 8.0 0.0
              |> lineTo 24.0 0.0
              |> lineTo 40.0 0.0
              |> hostCall1 "byFiltering" (fun raw node ->
                   // The first node has no previous point, so `raw` can be JVM null, which arrives as
                   // Unit. Matching on that is the FieldML spelling of a nullable host argument.
                   if raw == Unit then true
                   else (hostAs "Vec3" raw).x < 20.0
                 )
            """,
        )
        val inKotlin = FLine().moveTo(0.0, 0.0).lineTo(8.0, 0.0).lineTo(24.0, 0.0).lineTo(40.0, 0.0)
            // `Vec3?`: the first node of a line has no previous point, and the FieldML side has to
            // survive that too — which is what makes this a test of the whole round trip.
            .byFiltering { p, _ -> (p?.x ?: 0.0f) < 20.0f }

        // A filtered-out node in the middle of a subpath is *replaced* by a MoveTo rather than dropped,
        // so the node classes are the observable effect, not the count.
        assertEquals(
            inKotlin.nodes.map { it.javaClass.simpleName to it.toOrNull?.x },
            throughFieldML.nodes.map { it.javaClass.simpleName to it.toOrNull?.x },
        )
        assertTrue(
            inKotlin.nodes.map { it.javaClass.simpleName }.distinct().size > 1,
            "the predicate changed nothing, so this proves nothing: ${inKotlin.nodes.map { it.javaClass.simpleName }}",
        )
    }

    @Test
    fun aJavaFunctionalInterfaceWithAPrimitiveResultAgrees() {
        val throughFieldML = evaluate("""hostStaticCall "max" ([5, 1, 9, 3], fun a b -> b - a) (hostClass "java.util.Collections")""")
        val inKotlin = Collections.max(listOf(5L, 1L, 9L, 3L)) { a, b -> (b - a).toInt() }

        assertEquals(inKotlin, throughFieldML)
    }

    // ---- widths and overloads -------------------------------------------------------------------

    @Test
    fun aFieldMLFloatNarrowsToTheSameFloatKotlinWouldSee() {
        // 1/3 is the case where a wrong width shows: the bridge must narrow the double to a float, and
        // the sample must be bit-identical to what Kotlin's own narrowing produces.
        val throughFieldML = evaluateAs<Img>(
            """
            let Img = hostClass "afield.stdlib.Img"

            Img |> hostGet "Companion" |> hostCall "filled" (2, 2, 1, 1.0 / 3.0)
            """,
        )
        val expected = (1.0 / 3.0).toFloat()

        assertEquals(expected, throughFieldML.at(0, 0))
        assertEquals(expected, Img.filled(2, 2, 1, expected).at(0, 0))
        assertEquals(4, throughFieldML.data.size)
    }

    @Test
    fun anOverloadTieIsBrokenTowardsTheParameterOfTheSameKind() {
        // Vector2f has both `void set(int, float)` and `Vector2f set(float, float)`. Both accept a
        // FieldML `(3.0, 4.0)`. Before numeric affinity they scored equal and the winner was whatever
        // `Class.getMethods` listed first — which silently returned nothing and set the wrong component.
        val result = compileOk("""vec2 1.0 2.0 |> hostCall "set" (3.0, 4.0)""")
        assertEquals("Vec2", TypePrinter().print(result.typed!!.type))

        val throughFieldML = assertIs<Vector2f>(evaluateCompiled(result))
        val inKotlin = Vector2f(1.0f, 2.0f).set(3.0f, 4.0f)

        assertEquals(inKotlin.x, throughFieldML.x)
        assertEquals(inKotlin.y, throughFieldML.y)
    }

    @Test
    fun theTypeCheckerPredictsTheOverloadTheRuntimeInvokes() {
        // The two scorers share `numericAffinity` precisely so these cannot diverge: if the checker
        // says Int and the runtime reaches the double overload, the value below is a Float.
        listOf(
            """hostStaticCall "max" (3, 7) (hostClass "java.lang.Math")""" to "Int",
            """hostStaticCall "max" (3.5, 7.25) (hostClass "java.lang.Math")""" to "Float",
            """hostStaticCall "abs" (0 - 5) (hostClass "java.lang.Math")""" to "Int",
            """hostStaticCall "abs" (0.0 - 5.5) (hostClass "java.lang.Math")""" to "Float",
        ).forEach { (source, expectedType) ->
            val result = compileOk(source)
            assertEquals(expectedType, TypePrinter().print(result.typed!!.type), source)
            val value = evaluateCompiled(result)
            val runtimeType = when (value) {
                is Long -> "Int"
                is Double -> "Float"
                else -> value?.javaClass?.simpleName
            }
            assertEquals(expectedType, runtimeType, "$source: the runtime disagreed with the type checker")
        }
    }

    @Test
    fun varargsAndAnIntArgumentReachJavaFormatUnchanged() {
        val throughFieldML = evaluate("""hostStaticCall "format" ("%s/%d/%.3f", "a", 2, 1.5) (hostClass "java.lang.String")""")
        // A FieldML Int is a Long, which is what `%d` receives.
        val inKotlin = String.format("%s/%d/%.3f", "a", 2L, 1.5)

        assertEquals(inKotlin, throughFieldML)
    }

    @Test
    fun anOptionalKotlinParameterFilledWithHostNullMatchesTheDefault() {
        // colormap(img, name, lo = null, hi = null): a reflective call cannot use Kotlin defaults, so
        // `hostNull` has to reproduce them exactly.
        val throughFieldML = evaluateAs<Img>(
            imgSource + """

            hostStaticCall "colormap" (target, "viridis", hostNull, hostNull) (hostClass "afield.stdlib.Imaging_fieldKt")
            """.trimIndent(),
        )
        val inKotlin = imgKotlin().colormap("viridis")

        assertEquals(inKotlin.channels, throughFieldML.channels)
        assertContentEquals(inKotlin.data, throughFieldML.data)
    }

    // ---- harness ---------------------------------------------------------------------------------

    private val imgSamples = FloatArray(16) { it / 16.0f }

    private fun imgKotlin(): Img = Img(4, 4, 1, imgSamples.copyOf())

    private val imgSource: String
        get() = """
            let Img = hostClass "afield.stdlib.Img"
            let target = hostNew (4, 4, 1, [${imgSamples.joinToString(", ") { it.toString() }}]) Img
        """.trimIndent()

    private inline fun <reified T> evaluateAs(source: String): T = assertIs<T>(evaluate(source))

    private fun evaluate(source: String): Any? = evaluateCompiled(compileOk(source))

    private fun evaluateCompiled(result: CompilationResult): Any? =
        fieldMLValueToHost(Evaluator(FieldMLRuntimePrelude.values(box = box)).evaluate(result.core!!))

    private val box = Box("fieldml-host-agreement")

    private fun compileOk(source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(result.successful, result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" })
        return result
    }

    private fun assertContentEquals(expected: FloatArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size, "sample count")
        expected.indices.forEach { index -> assertEquals(expected[index], actual[index], "sample $index") }
    }
}

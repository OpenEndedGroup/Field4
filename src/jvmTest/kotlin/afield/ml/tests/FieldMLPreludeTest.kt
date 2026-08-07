package afield.ml.tests

import afield.Box
import afield.graphics.FLine
import afield.ml.FieldMLCompiler
import afield.ml.FieldMLRuntimePrelude
import afield.stdlib.thickness
import afield.ml.core.Evaluator
import afield.ml.core.FieldMLValue
import afield.ml.core.renderFieldMLValue
import afield.ml.type.TypePrinter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the parts of FieldML that make it usable for drawing work: iteration (recursion and list
 * combinators), numbers that can be converted and rounded, booleans, and vector arithmetic.
 */
class FieldMLPreludeTest {
    private data class Case(val name: String, val source: String, val value: String, val type: String)

    private fun evaluate(source: String): Pair<String, String> {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            "$source failed:\n${result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" }}",
        )
        val value = Evaluator(FieldMLRuntimePrelude.values()).evaluate(result.core!!)
        return renderFieldMLValue(value) to TypePrinter().print(result.typed!!.type)
    }

    private fun check(cases: List<Case>) {
        cases.forEach { case ->
            val (value, type) = evaluate(case.source)
            assertEquals(case.value, value, "${case.name}: value")
            assertEquals(case.type, type, "${case.name}: type")
        }
    }

    private fun assertFails(source: String, messageContains: String) {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(!result.successful, "expected '$source' to fail")
        assertTrue(
            result.diagnostics.any { messageContains in it.message },
            "expected a diagnostic containing '$messageContains', got ${result.diagnostics.joinToString { it.message }}",
        )
    }

    @Test
    fun functionsCanCallThemselves() {
        check(
            listOf(
                Case("factorial", "let fact n = if n < 1 then 1 else n * fact (n - 1)\nfact 5", "120", "Int"),
                Case(
                    "recursion over a list",
                    """
                    let total xs =
                      match xs with
                      | [] -> 0
                      | head :: rest -> head + total rest

                    total [1, 2, 3, 4]
                    """,
                    "10",
                    "Int",
                ),
                Case(
                    "recursive function stays polymorphic at its use site",
                    """
                    let count xs =
                      match xs with
                      | [] -> 0
                      | _ :: rest -> 1 + count rest

                    (count [1, 2], count ["a"])
                    """,
                    "(2, 1)",
                    "(Int, Int)",
                ),
            ),
        )
    }

    /** Recursion must not break the existing rule that a plain `let` shadows rather than self-references. */
    @Test
    fun plainLetBindingsStillShadow() {
        check(
            listOf(
                Case("value shadowing", "let x = 1\nlet x = x + 1\nx", "2", "Int"),
                Case("shadowing a function with a value", "let f x = x + 1\nlet f = f 1\nf", "2", "Int"),
            ),
        )
    }

    @Test
    fun prefixMinusNegatesNumbersAndVectors() {
        check(
            listOf(
                Case("negative float literal", "let x = -1.5\nx", "-1.5", "Float"),
                Case("negative int literal", "-3", "-3", "Int"),
                Case("negated expression", "let x = 3.0\n-(x * 2.0)", "-6.0", "Float"),
                Case("negation binds tighter than multiply", "-2.0 * 3.0", "-6.0", "Float"),
                Case("negated vector", "-(vec2 1.0 2.0)", "(-1.0, -2.0)", "Vec2"),
                Case("subtraction still parses", "10 - 3", "7", "Int"),
            ),
        )
    }

    @Test
    fun numbersHaveMathConversionsAndModulo() {
        check(
            listOf(
                Case("pi and sin", "sin (pi / 2.0)", "1.0", "Float"),
                Case("sqrt", "sqrt 9.0", "3.0", "Float"),
                Case("floor and ceil", "(floor 1.7, ceil 1.2)", "(1.0, 2.0)", "(Float, Float)"),
                Case("atan2", "atan2 0.0 1.0", "0.0", "Float"),
                Case("Int to Float", "toFloat 3 * 0.5", "1.5", "Float"),
                Case("Float to Int truncates", "toInt 3.7", "3", "Int"),
                Case("modulo reads well in a pipe", "7 |> mod 4", "3", "Int"),
                Case("integer division", "7 |> idiv 2", "3", "Int"),
                Case("float modulo", "7.5 |> fmod 2.0", "1.5", "Float"),
                Case("pow", "3.0 |> pow 2.0", "9.0", "Float"),
                Case("lerp", "0.5 |> lerp 0.0 10.0", "5.0", "Float"),
                Case("clamp", "5.0 |> clamp 0.0 1.0", "1.0", "Float"),
                Case("abs keeps Int as Int", "abs (-3)", "3", "Int"),
                Case("abs keeps Float as Float", "abs (-3.0)", "3.0", "Float"),
                Case("min and max", "max 2 (min 7 4)", "4", "Int"),
                Case("sign", "sign (-2.0)", "-1.0", "Float"),
            ),
        )
    }

    @Test
    fun booleansCombineAndShortCircuit() {
        check(
            listOf(
                Case("and, or, not", "true && not false || false", "true", "Bool"),
                Case("precedence below comparison", "1 < 2 && 3 > 2", "true", "Bool"),
                // `head []` throws, so this only evaluates if && fails to short circuit.
                Case("&& short circuits", "false && head [] == 1", "false", "Bool"),
                Case("|| short circuits", "true || head [] == 1", "true", "Bool"),
            ),
        )
    }

    @Test
    fun stringsConcatenate() {
        check(listOf(Case("concat", """ "a" ++ "b" ++ "c" """, "\"abc\"", "String")))
    }

    @Test
    fun listsIterateWithoutRecursion() {
        check(
            listOf(
                Case("range is end exclusive", "range 0 4", "[0, 1, 2, 3]", "[Int]"),
                Case("linspace includes both ends", "linspace 0.0 1.0 3", "[0.0, 0.5, 1.0]", "[Float]"),
                Case("linspace of one point", "linspace 2.0 4.0 1", "[2.0]", "[Float]"),
                Case("tabulate", "tabulate (fun i -> i * i) 4", "[0, 1, 4, 9]", "[Int]"),
                Case("map", "[1, 2, 3] |> map (fun x -> x * 2)", "[2, 4, 6]", "[Int]"),
                Case("mapIndexed", "[10, 20] |> mapIndexed (fun i x -> i + x)", "[10, 21]", "[Int]"),
                Case("filter", "range 0 6 |> filter (fun x -> mod 2 x == 0)", "[0, 2, 4]", "[Int]"),
                Case("fold", "range 1 5 |> fold (fun acc x -> acc + x) 0", "10", "Int"),
                Case("length and isEmpty", "(length [1, 2], isEmpty [])", "(2, true)", "(Int, Bool)"),
                Case("head and tail", "([1, 2, 3] |> head, [1, 2, 3] |> tail)", "(1, [2, 3])", "(Int, [Int])"),
                Case("nth", "[1, 2, 3] |> nth 1", "2", "Int"),
                Case("take, drop, reverse", "range 0 5 |> drop 1 |> take 2 |> reverse", "[2, 1]", "[Int]"),
                Case("append puts the piped list first", "[1, 2] |> append [3]", "[1, 2, 3]", "[Int]"),
                Case("concat", "concat [[1], [2, 3]]", "[1, 2, 3]", "[Int]"),
                Case("zip", """[1, 2] |> zip ["a", "b"]""", """[(1, "a"), (2, "b")]""", "[(Int, String)]"),
                Case("sortBy", "[3.0, 1.0, 2.0] |> sortBy (fun x -> x)", "[1.0, 2.0, 3.0]", "[Float]"),
                Case("sum", "linspace 0.0 1.0 3 |> sum", "1.5", "Float"),
            ),
        )
    }

    @Test
    fun consBuildsAndMatchesLists() {
        check(
            listOf(
                Case("cons is right associative", "1 :: 2 :: [3]", "[1, 2, 3]", "[Int]"),
                Case(
                    "cons pattern splits head from tail",
                    """
                    match [1, 2, 3] with
                    | head :: rest -> (head, length rest)
                    | [] -> (0, 0)
                    """,
                    "(1, 2)",
                    "(Int, Int)",
                ),
                Case(
                    "cons pattern does not match an empty list",
                    """
                    match [] with
                    | head :: _ -> head
                    | [] -> 0
                    """,
                    "0",
                    "Int",
                ),
                Case(
                    "nested cons patterns",
                    """
                    match [1, 2, 3] with
                    | a :: b :: _ -> a + b
                    | _ -> 0
                    """,
                    "3",
                    "Int",
                ),
            ),
        )
    }

    @Test
    fun vectorsSupportArithmeticAndComponents() {
        check(
            listOf(
                Case("add", "vec2 1.0 2.0 + vec2 3.0 4.0", "(4.0, 6.0)", "Vec2"),
                Case("subtract", "vec2 3.0 4.0 - vec2 1.0 1.0", "(2.0, 3.0)", "Vec2"),
                Case("scale on the right", "vec3 1.0 2.0 3.0 * 2.0", "(2.0, 4.0, 6.0)", "Vec3"),
                Case("scale on the left", "2.0 * vec2 1.0 1.0", "(2.0, 2.0)", "Vec2"),
                Case("component wise multiply", "vec2 2.0 3.0 * vec2 4.0 5.0", "(8.0, 15.0)", "Vec2"),
                Case("divide by a scalar", "vec2 4.0 2.0 / 2.0", "(2.0, 1.0)", "Vec2"),
                Case("components", "(vec3 1.0 2.0 3.0).z", "3.0", "Float"),
                Case("vec4 w", "(vec4 1.0 2.0 3.0 4.0).w", "4.0", "Float"),
                Case("dot", "dot (vec2 1.0 2.0) (vec2 3.0 4.0)", "11.0", "Float"),
                Case("vlength", "vlength (vec2 3.0 4.0)", "5.0", "Float"),
                Case("normalize", "vlength (normalize (vec3 0.0 5.0 0.0))", "1.0", "Float"),
                Case("distance", "distance (vec2 0.0 0.0) (vec2 3.0 4.0)", "5.0", "Float"),
                Case("mix", "mix (vec2 0.0 0.0) (vec2 10.0 10.0) 0.5", "(5.0, 5.0)", "Vec2"),
                Case("cross", "cross (vec3 1.0 0.0 0.0) (vec3 0.0 1.0 0.0)", "(0.0, 0.0, 1.0)", "Vec3"),
                Case("polar", "polar 0.0 2.0", "(2.0, 0.0)", "Vec2"),
                Case(
                    "vector helpers work at more than one width in the same program",
                    """
                    let mid a b = mix a b 0.5

                    (mid (vec2 0.0 0.0) (vec2 2.0 2.0), mid (vec3 0.0 0.0 0.0) (vec3 3.0 3.0 3.0))
                    """,
                    "((1.0, 1.0), (1.5, 1.5, 1.5))",
                    "(Vec2, Vec3)",
                ),
            ),
        )
    }

    /** Vector arithmetic must not silently produce nonsense when the widths or types disagree. */
    @Test
    fun vectorMisuseIsATypeError() {
        assertFails("vec2 1.0 1.0 + vec3 1.0 1.0 1.0", "Vec2 is not Vec3")
        assertFails("dot (vec2 1.0 1.0) (vec3 1.0 1.0 1.0)", "Vec2 is not Vec3")
        assertFails("normalize 3.0", "Expected Vec2, Vec3 or Vec4")
        assertFails("(vec2 1.0 2.0).z", "Vec2 has no field 'z'")
        assertFails("vec2 1.0 1.0 * vec2 1.0 1.0 * true", "Bool")
    }

    @Test
    fun mixedNumericTypesAreStillRejected() {
        assertFails("10 + 1.0", "Int is not Float")
        assertFails("true && 1", "Int is not Bool")
        assertFails(""" "a" ++ 1 """, "Int is not String")
    }

    /**
     * `animate` hands Field a Sequence, which the launch machinery steps once per frame. Stepping it
     * here is the same thing a running box does.
     */
    @Test
    fun animateStepsTheFunctionOncePerFrame() {
        val result = FieldMLCompiler.compile("animate (fun t -> t * 2.0)")
        assertTrue(result.successful, result.diagnostics.joinToString { it.message })
        val value = Evaluator(FieldMLRuntimePrelude.values()).evaluate(result.core!!)
        val host = (value as FieldMLValue.HostValue).value

        @Suppress("UNCHECKED_CAST")
        val frames = host as Sequence<Any>
        assertEquals(listOf(1L, 2L, 3L), frames.take(3).toList())
    }

    @Test
    fun animateForStopsAfterItsDuration() {
        val result = FieldMLCompiler.compile("animateFor 0.05 (fun t -> t)")
        assertTrue(result.successful, result.diagnostics.joinToString { it.message })
        val value = Evaluator(FieldMLRuntimePrelude.values()).evaluate(result.core!!)

        @Suppress("UNCHECKED_CAST")
        val frames = (value as FieldMLValue.HostValue).value as Sequence<Any>
        var steps = 0
        val deadline = System.nanoTime() + 5_000_000_000L
        for (unused in frames) {
            steps++
            if (System.nanoTime() > deadline) break
        }
        assertTrue(steps > 0, "expected at least one frame")
        assertTrue(System.nanoTime() < deadline, "animateFor did not stop on its own")
    }

    /** `draw` sends the line to a box output channel and hands the same line back for further piping. */
    @Test
    fun drawReturnsTheLineItDrew() {
        val result = FieldMLCompiler.compile(
            """
            fline
              |> moveTo 0.0 0.0
              |> lineTo 10.0 10.0
              |> draw "test"
              |> thickness 3.0
            """.trimIndent(),
        )
        assertTrue(result.successful, result.diagnostics.joinToString { it.message })
        val value = Evaluator(FieldMLRuntimePrelude.values(box = Box("fieldml-draw-test"))).evaluate(result.core!!)
        val line = (value as FieldMLValue.HostValue).value as FLine
        assertEquals(3.0, line.thickness)
        assertEquals(2, line.nodes.size)
    }

    /** A worked example of the shape this prelude exists for: geometry generated from a loop. */
    @Test
    fun geometryPipelineProducesAnFLine() {
        val (_, type) = evaluate(
            """
            let ring count radius =
              range 0 count
                |> map (fun i -> polar (tau * toFloat i / toFloat count) radius)
                |> fold (fun line point -> line |> lineToV point) fline
                |> close

            ring 12 60.0
            """,
        )
        assertEquals("FLine", type)
    }
}

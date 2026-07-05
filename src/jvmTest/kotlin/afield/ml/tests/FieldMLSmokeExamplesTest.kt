package afield.ml.tests

import afield.ml.FieldMLCompiler
import afield.ml.CompilationResult
import afield.ml.FieldMLRuntimePrelude
import afield.ml.core.Evaluator
import afield.ml.core.renderFieldMLValue
import afield.ml.type.TypePrinter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldMLSmokeExamplesTest {
    data class Example(
        val name: String,
        val source: String,
        val value: String,
        val type: String,
    )

    data class ErrorExample(
        val name: String,
        val source: String,
        val messageContains: String,
    )

    @Test
    fun fullLanguageExampleCatalogCompilesTypesAndEvaluates() {
        examples.forEach { example ->
            val result = compileOk(example.name, example.source)
            val actualValue = renderFieldMLValue(Evaluator(FieldMLRuntimePrelude.values()).evaluate(result.core!!))
            val actualType = TypePrinter().print(result.typed!!.type)

            assertEquals(example.value, actualValue, "${example.name}: value")
            assertEquals(example.type, actualType, "${example.name}: type")
            assertNotNull(result.tokens, "${example.name}: tokens")
            assertNotNull(result.surface, "${example.name}: surface AST")
            assertNotNull(result.typed, "${example.name}: typed AST")
            assertNotNull(result.core, "${example.name}: core")
            assertTrue(result.kotlin.orEmpty().contains("fun runFieldML(): Any?"), "${example.name}: Kotlin emission")
        }
    }

    @Test
    fun expectedFailuresProduceUsefulDiagnostics() {
        errorExamples.forEach { example ->
            val result = FieldMLCompiler.compile(example.source)

            assertTrue(!result.successful, "${example.name}: should fail")
            assertTrue(
                result.diagnostics.any { example.messageContains in it.message },
                "${example.name}: expected diagnostic containing '${example.messageContains}', got ${
                    result.diagnostics.joinToString { it.message }
                }",
            )
        }
    }

    private fun compileOk(name: String, source: String): CompilationResult {
        val result = FieldMLCompiler.compile(source.trimIndent())
        assertTrue(
            result.successful,
            "$name failed:\n${result.diagnostics.joinToString("\n") { "${it.severity}: ${it.message}" }}",
        )
        return result
    }

    companion object {
        val examples = listOf(
            Example(
                name = "integer arithmetic and precedence",
                source = "10 + 2 * 16",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "float arithmetic",
                source = "10.5 * 4.0",
                value = "42.0",
                type = "Float",
            ),
            Example(
                name = "boolean comparison and if",
                source = "if 20 > 10 then true else false",
                value = "true",
                type = "Bool",
            ),
            Example(
                name = "top-level immutable lets",
                source = """
                    let x = 10
                    let y = 32
                    x + y
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "local let expression",
                source = "let x = 6 in let y = 7 in x * y",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "curried function",
                source = """
                    let add a b = a + b
                    add 20 22
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "anonymous function",
                source = "(fun x -> x + 1) 41",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "polymorphic identity over record",
                source = """
                    let id x = x
                    {
                      a = id 10
                      b = id true
                      c = id "field"
                    }
                """,
                value = """{a = 10, b = true, c = "field"}""",
                type = "{a: Int, b: Bool, c: String}",
            ),
            Example(
                name = "record construction and field access",
                source = """
                    let p = {
                      x = 10.0
                      y = 20.0
                    }
                    {
                      x = p.x * 2.0
                      y = p.y * 2.0
                    }
                """,
                value = "{x = 20.0, y = 40.0}",
                type = "{x: Float, y: Float}",
            ),
            Example(
                name = "nested records",
                source = """
                    let p = {
                      position = { x = 10.0, y = 20.0 }
                      label = "seed"
                    }
                    (p.label, p.position.x + p.position.y)
                """,
                value = """("seed", 30.0)""",
                type = "(String, Float)",
            ),
            Example(
                name = "tuple literal",
                source = "(10, true, \"ok\")",
                value = """(10, true, "ok")""",
                type = "(Int, Bool, String)",
            ),
            Example(
                name = "homogeneous list literal",
                source = "[10, 20, 12]",
                value = "[10, 20, 12]",
                type = "[Int]",
            ),
            Example(
                name = "tuple pattern",
                source = "match (20, 22) with | (a, b) -> a + b",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "list pattern exact shape",
                source = "match [10, 32] with | [a, b] -> a + b | _ -> 0",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "literal string pattern",
                source = """match "field" with | "field" -> 42 | _ -> 0""",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "literal bool pattern",
                source = "match true with | true -> 42 | false -> 0",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "option adt none",
                source = """
                    type Option a = None | Some a
                    match None with
                    | Some x -> x
                    | None -> 42
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "option adt some",
                source = """
                    type Option a = None | Some a
                    match Some 42 with
                    | Some x -> x
                    | None -> 0
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "adt carries tuple and list",
                source = """
                    type Option a = None | Some a
                    match Some (1, [2, 3]) with
                    | Some pair -> pair
                    | None -> (0, [])
                """,
                value = "(1, [2, 3])",
                type = "(Int, [Int])",
            ),
            Example(
                name = "result adt with record payload",
                source = """
                    type Result a = Err String | Ok a
                    let payload = { x = 20, y = 22 }
                    match Ok payload with
                    | Ok p -> p.x + p.y
                    | Err message -> 0
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "nested constructor pattern",
                source = """
                    type Option a = None | Some a
                    match Some (Some 42) with
                    | Some (Some x) -> x
                    | Some None -> 0
                    | None -> 0
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "constructor pattern with tuple payload",
                source = """
                    type PairBox a = PairBox a
                    match PairBox (20, 22) with
                    | PairBox (a, b) -> a + b
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "adt polymorphic constructor reused at different types",
                source = """
                    type Option a = None | Some a
                    let id x = x
                    {
                      number = id (Some 42)
                      flag = id (Some true)
                    }
                """,
                value = "{number = Some 42, flag = Some true}",
                type = "{number: Option Int, flag: Option Bool}",
            ),
            Example(
                name = "comparison function",
                source = """
                    let max a b =
                      if a > b then a else b
                    max 10 42
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "pipe into anonymous function",
                source = "10 |> (fun x -> x + 32)",
                value = "42",
                type = "Int",
            ),
            Example(
                name = "pipe into curried function with existing argument",
                source = """
                    let add a b = a + b
                    10 |> add 32
                """,
                value = "42",
                type = "Int",
            ),
            Example(
                name = "stdlib output taps compose with pipe",
                source = """42 |> display "fieldml" |> show""",
                value = "\"42\"",
                type = "String",
            ),
            Example(
                name = "small expressive sketch payload",
                source = """
                    type Mark a = Mark a
                    let marks = [
                      Mark { x = 10.0, y = 20.0 },
                      Mark { x = 4.0, y = 5.0 }
                    ]
                    match marks with
                    | [Mark a, Mark b] -> (
                        { x = a.x * 2.0, y = a.y * 2.0 },
                        { x = b.x * 4.0, y = b.y * 4.0 }
                      )
                    | _ -> ({ x = 0.0, y = 0.0 }, { x = 0.0, y = 0.0 })
                """,
                value = "({x = 20.0, y = 40.0}, {x = 16.0, y = 20.0})",
                type = "({x: Float, y: Float}, {x: Float, y: Float})",
            ),
        )

        val errorExamples = listOf(
            ErrorExample(
                name = "mixed numeric operands",
                source = "10 + 1.0",
                messageContains = "Type mismatch",
            ),
            ErrorExample(
                name = "mixed list element types",
                source = "[1, true]",
                messageContains = "Type mismatch",
            ),
            ErrorExample(
                name = "missing record field",
                source = """
                    let p = { x = 10.0 }
                    p.y
                """,
                messageContains = "missing field 'y'",
            ),
            ErrorExample(
                name = "match branch mismatch",
                source = """
                    type Option a = None | Some a
                    match Some 10 with
                    | Some x -> x
                    | None -> false
                """,
                messageContains = "Type mismatch",
            ),
            ErrorExample(
                name = "constructor arity mismatch in pattern",
                source = """
                    type Option a = None | Some a
                    match Some 10 with
                    | Some -> 0
                """,
                messageContains = "expects 1 pattern argument",
            ),
            ErrorExample(
                name = "tuple arity mismatch",
                source = "match (1, 2) with | (a, b, c) -> a",
                messageContains = "Tuple arity mismatch",
            ),
        )
    }
}

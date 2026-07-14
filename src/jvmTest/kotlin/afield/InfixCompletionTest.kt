package afield

import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.SourceCodeCompletionVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the type-aware infix completion resolver ([InfixCompletion]). These exercise the
 * pure detection/resolution logic with a fake completion engine so no JVM scripting runtime is
 * required — the live end-to-end behaviour (`pulseBurst(10) through ` surfacing the FnTransform
 * builders) is driven by the same code path.
 */
class InfixCompletionTest {

    /** A completion engine that knows how to complete one infix operator with one or more overloads. */
    private fun operatorEngine(operatorName: String, vararg paramTypes: String): suspend (String, Int) -> CompletionResult =
        { code, cursor ->
            val bounds = getTokenBounds(code, cursor)
            val token = code.substring(bounds.start, bounds.end)
            val matches = if (operatorName.startsWith(token) && token.isNotEmpty()) {
                paramTypes.map { paramType ->
                    SourceCodeCompletionVariant(
                        text = "$operatorName(",
                        displayText = "$operatorName($paramType)",
                        tail = "Functions.Fn",
                        icon = "method"
                    )
                }
            } else emptyList()
            CompletionResult.Success(matches.map { it.text }, bounds, matches, code, cursor)
        }

    @Test
    fun resolvesInfixOperandTypeAfterOperator() = runBlocking {
        val code = "with(Functions) {\n    (pulseBurst(10) through ).plot(\"source\")\n}"
        val cursor = code.indexOf("through ") + "through ".length
        val expected = InfixCompletion.expectedOperandTypes(code, cursor, operatorEngine("through", "Functions.FnTransform"))
        assertEquals(setOf("FnTransform"), expected)
    }

    @Test
    fun resolvesWhilePartialOperandTyped() = runBlocking {
        val code = "with(Functions) {\n    (pulseBurst(10) through str).plot(\"source\")\n}"
        val cursor = code.indexOf("through str") + "through str".length
        val expected = InfixCompletion.expectedOperandTypes(code, cursor, operatorEngine("through", "Functions.FnTransform"))
        assertEquals(setOf("FnTransform"), expected)
    }

    @Test
    fun ignoresWhenCursorIsGluedToOperatorToken() = runBlocking {
        // Still typing the operator name itself — not yet an operand site.
        val code = "(pulseBurst(10) throu"
        val expected = InfixCompletion.expectedOperandTypes(code, code.length, operatorEngine("through", "Functions.FnTransform"))
        assertTrue(expected.isEmpty())
    }

    @Test
    fun ignoresKeywordsInReceiverPosition() = runBlocking {
        val code = "return foo"
        val expected = InfixCompletion.expectedOperandTypes(code, code.length, operatorEngine("foo", "Whatever"))
        assertTrue(expected.isEmpty())
    }

    @Test
    fun resolvesEveryOverloadOperandType() = runBlocking {
        // `over` is overloaded on operand type: `Fn.over(Domain)` and `Fn.over(ClosedRange<*>)`.
        val code = "with(Functions) {\n    (pulseBurst(10) over ).plot(\"source\")\n}"
        val cursor = code.indexOf("over ") + "over ".length
        val expected = InfixCompletion.expectedOperandTypes(
            code, cursor, operatorEngine("over", "Functions.Domain", "kotlin.ranges.ClosedRange<*>")
        )
        assertEquals(setOf("Domain", "ClosedRange<*>"), expected)
    }

    @Test
    fun matchesAnyOverloadReturnType() {
        // Both overload operand types should be boosted; an unrelated type should not.
        val expectedTypes = setOf("Domain", "ClosedRange<*>")
        val domain = SourceCodeCompletionVariant(
            text = "domain(", displayText = "domain(...)", tail = "Functions.Domain", icon = "method"
        )
        val range = SourceCodeCompletionVariant(
            text = "range(", displayText = "range(...)", tail = "kotlin.ranges.ClosedRange<*>", icon = "method"
        )
        val unrelated = SourceCodeCompletionVariant(
            text = "cos(", displayText = "cos(...)", tail = "Functions.Fn", icon = "method"
        )
        assertTrue(InfixCompletion.matchesOperandType(domain, expectedTypes))
        assertTrue(InfixCompletion.matchesOperandType(range, expectedTypes))
        assertFalse(InfixCompletion.matchesOperandType(unrelated, expectedTypes))
    }

    @Test
    fun parsesInfixParamTypeFromSourceDeclaration() {
        // The fast (source-index) hot path parses the operand type straight from the declaration line.
        assertEquals(
            "FnTransform",
            InfixCompletion.parseInfixParamType("    infix fun Fn.through(transform: FnTransform): Fn = transform(this)", "through")
        )
        assertEquals(
            "ClosedRange<*>",
            InfixCompletion.parseInfixParamType("    infix fun Fn.over(range: ClosedRange<*>): Fn =", "over")
        )
        assertEquals(
            "Generator<Out, Out2>",
            InfixCompletion.parseInfixParamType(
                "infix fun <In, Out, Out2> Generator<In, Out>.pipe(g: Generator<Out, Out2>) = generator2<In, Out2> {", "pipe"
            )
        )
    }

    @Test
    fun parseInfixParamTypeIgnoresNonInfixAndMismatchedOperators() {
        // A same-named non-infix function must be rejected (no `infix` modifier)...
        assertNull(InfixCompletion.parseInfixParamType("    private fun over(token: Token) {", "over"))
        // ...and the operator name must actually be the function being declared.
        assertNull(InfixCompletion.parseInfixParamType("    infix fun Fn.through(transform: FnTransform): Fn", "over"))
    }

    @Test
    fun matchesOperandTypeByReturnType() {
        val transform = SourceCodeCompletionVariant(
            text = "stretch(", displayText = "stretch(...)", tail = "Functions.FnTransform", icon = "method"
        )
        val fn = SourceCodeCompletionVariant(
            text = "cos(", displayText = "cos(...)", tail = "Functions.Fn", icon = "method"
        )
        assertTrue(InfixCompletion.matchesOperandType(transform, setOf("FnTransform")))
        assertFalse(InfixCompletion.matchesOperandType(fn, setOf("FnTransform")))
    }

    @Test
    fun skipsMultiParameterEntriesButKeepsGenericSingleParam() = runBlocking {
        // A non-infix multi-param overload (`f(A, B)`) must be dropped, while a single generic
        // param whose type arguments contain a comma (`Map<String, Int>`) must be kept.
        val code = "with(Functions) {\n    (pulseBurst(10) merge ).plot(\"source\")\n}"
        val cursor = code.indexOf("merge ") + "merge ".length
        val expected = InfixCompletion.expectedOperandTypes(
            code, cursor, operatorEngine("merge", "kotlin.collections.Map<String, Int>", "Functions.Domain, Int")
        )
        assertEquals(setOf("Map<String, Int>"), expected)
    }
}

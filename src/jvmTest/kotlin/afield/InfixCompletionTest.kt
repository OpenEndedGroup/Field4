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

    /** A completion engine that only knows how to complete the infix operator token. */
    private fun operatorEngine(operatorName: String, paramType: String): suspend (String, Int) -> CompletionResult =
        { code, cursor ->
            val bounds = getTokenBounds(code, cursor)
            val token = code.substring(bounds.start, bounds.end)
            val matches = if (operatorName.startsWith(token) && token.isNotEmpty()) {
                listOf(
                    SourceCodeCompletionVariant(
                        text = "$operatorName(",
                        displayText = "$operatorName($paramType)",
                        tail = "Functions.Fn",
                        icon = "method"
                    )
                )
            } else emptyList()
            CompletionResult.Success(matches.map { it.text }, bounds, matches, code, cursor)
        }

    @Test
    fun resolvesInfixOperandTypeAfterOperator() = runBlocking {
        val code = "with(Functions) {\n    (pulseBurst(10) through ).plot(\"source\")\n}"
        val cursor = code.indexOf("through ") + "through ".length
        val expected = InfixCompletion.expectedOperandType(code, cursor, operatorEngine("through", "Functions.FnTransform"))
        assertEquals("FnTransform", expected)
    }

    @Test
    fun resolvesWhilePartialOperandTyped() = runBlocking {
        val code = "with(Functions) {\n    (pulseBurst(10) through str).plot(\"source\")\n}"
        val cursor = code.indexOf("through str") + "through str".length
        val expected = InfixCompletion.expectedOperandType(code, cursor, operatorEngine("through", "Functions.FnTransform"))
        assertEquals("FnTransform", expected)
    }

    @Test
    fun ignoresWhenCursorIsGluedToOperatorToken() = runBlocking {
        // Still typing the operator name itself — not yet an operand site.
        val code = "(pulseBurst(10) throu"
        val expected = InfixCompletion.expectedOperandType(code, code.length, operatorEngine("through", "Functions.FnTransform"))
        assertNull(expected)
    }

    @Test
    fun ignoresKeywordsInReceiverPosition() = runBlocking {
        val code = "return foo"
        val expected = InfixCompletion.expectedOperandType(code, code.length, operatorEngine("foo", "Whatever"))
        assertNull(expected)
    }

    @Test
    fun matchesOperandTypeByReturnType() {
        val transform = SourceCodeCompletionVariant(
            text = "stretch(", displayText = "stretch(...)", tail = "Functions.FnTransform", icon = "method"
        )
        val fn = SourceCodeCompletionVariant(
            text = "cos(", displayText = "cos(...)", tail = "Functions.Fn", icon = "method"
        )
        assertTrue(InfixCompletion.matchesOperandType(transform, "FnTransform"))
        assertFalse(InfixCompletion.matchesOperandType(fn, "FnTransform"))
    }
}

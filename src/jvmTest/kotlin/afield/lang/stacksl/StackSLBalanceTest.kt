package afield.lang.stacksl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A called definition that is not net zero on the stack is refused, naming the definition and the fix. */
class StackSLBalanceTest {
    private fun compile(source: String) = StackSLCompiler.compile(source.trimIndent(), StackSLCompileOptions(prefix = "balance"))

    @Test
    fun aDefinitionThatLeavesACopyIsAnError() {
        val result = compile(
            """
            : half
              =v <v 0.5 *
            ;

            uv half
            """
        )
        assertFalse(result.ok, result.diagnostics.joinToString("\n"))
        val message = result.diagnostics.first { it.severity == StackSLSeverity.ERROR }.message
        assertTrue("half" in message && ">v" in message, message)
    }

    @Test
    fun aBalancedDefinitionCompiles() {
        val result = compile(
            """
            : half
              >v <v 0.5 *
            ;

            uv half
            """
        )
        assertTrue(result.ok, result.diagnostics.joinToString("\n"))
    }
}

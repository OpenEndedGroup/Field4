package afield

import afield.logger.lprintln

/*
FIELD_TEST
id: core.compiled-snippet.smoke
runner: jvm-main
entrypoint: afield.CompiledSnippetTest
tags: [smoke, core, kotlin, snippets]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "CompiledSnippet tests passed"
*/
/**
 * Test for CompiledSnippet functionality - compile once, evaluate many times
 * 
 * This follows the repo's lightweight "runnable object" style instead of JUnit.
 */
object CompiledSnippetTest {

    fun runTests(): Boolean {
        return try {
            println("Running CompiledSnippet tests...")
            testBasicCompileAndEval()
            testMultipleEvaluations()
            testWithVariables()
            testCompilationError()
            testEvaluationError()
            println("✅ CompiledSnippet tests passed")
            true
        } catch (t: Throwable) {
            println("❌ CompiledSnippet tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "CompiledSnippet tests failed" }
    }

    private fun testBasicCompileAndEval() {
        println("  Testing basic compile and eval...")
        val ki = KotlinInterface()
        val snippet = ki.compileSnippetAndWait("1 + 1")
        require(snippet != null) { "Failed to compile simple snippet" }
        
        val result = snippet.evalAndWait()
        require(result != null) { "Failed to evaluate simple snippet" }
        require(result.toString().contains("2")) { "Expected result to contain '2', got: $result" }
    }

    private fun testMultipleEvaluations() {
        println("  Testing multiple evaluations...")
        val ki = KotlinInterface()
        
        // Create a variable in the context
        ki.execAndWait("var x = 0")
        
        // Compile a snippet that increments x
        val snippet = ki.compileSnippetAndWait("++x")
        require(snippet != null) { "Failed to compile increment snippet" }
        
        // Evaluate it multiple times - each should increment
        val result1 = snippet.evalAndWait(updateContext = true)
        require(result1 != null) { "First evaluation failed" }
        
        val result2 = snippet.evalAndWait(updateContext = true)
        require(result2 != null) { "Second evaluation failed" }
        
        val result3 = snippet.evalAndWait(updateContext = true)
        require(result3 != null) { "Third evaluation failed" }
        
        // Verify the variable was incremented
        val checkResult = ki.execAndWait("x")
        require(checkResult.toString().contains("3")) { "Expected x to be 3 after three increments, got: $checkResult" }
    }

    private fun testWithVariables() {
        println("  Testing with variables in snippet...")
        val ki = KotlinInterface()
        
        // Set up a variable
        ki.execAndWait("var message = \"Hello\"")
        
        // Compile a snippet that uses the variable
        val snippet = ki.compileSnippetAndWait("message + \" World\"")
        require(snippet != null) { "Failed to compile snippet with variable" }
        
        // First evaluation
        val result1 = snippet.evalAndWait()
        require(result1 != null) { "First evaluation with variable failed" }
        require(result1.toString().contains("Hello World")) { "Expected 'Hello World', got: $result1" }
        
        // Change the variable
        ki.execAndWait("message = \"Goodbye\"")
        
        // Evaluate again - should use the new value
        val result2 = snippet.evalAndWait()
        require(result2 != null) { "Second evaluation with variable failed" }
        require(result2.toString().contains("Goodbye World")) { "Expected 'Goodbye World', got: $result2" }
    }

    private fun testCompilationError() {
        println("  Testing compilation error handling...")
        val ki = KotlinInterface()
        
        var errorHandlerCalled = false
        val snippet = ki.compileSnippetAndWait(
            "this is not valid kotlin code!!!",
            onCompileError = { 
                errorHandlerCalled = true
                lprintln("info,test", "Compilation error (expected): ${it.firstOrNull()?.message}")
            }
        )
        
        require(snippet == null) { "Should return null for invalid code" }
        require(errorHandlerCalled) { "Error handler should have been called" }
    }

    private fun testEvaluationError() {
        println("  Testing evaluation error handling...")
        val ki = KotlinInterface()
        
        // This compiles fine but throws at runtime
        val snippet = ki.compileSnippetAndWait("throw RuntimeException(\"Test error\")")
        require(snippet != null) { "Should compile code that throws at runtime" }
        
        var errorHandlerCalled = false
        val result = snippet.evalAndWait(
            onEvalException = { e ->
                errorHandlerCalled = true
                lprintln("info,test", "Evaluation exception (expected): ${e.message}")
            }
        )
        
        require(errorHandlerCalled) { "Exception handler should have been called" }
    }
}

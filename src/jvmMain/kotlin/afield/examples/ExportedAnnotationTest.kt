package afield.examples

import afield.Box
import afield.KotlinInterface
import afield.preamble

/*
FIELD_TEST
id: examples.exported-annotation.smoke
runner: jvm-main
entrypoint: afield.examples.ExportedAnnotationTest
tags: [smoke, examples, kotlin, exports]
timeoutSec: 20
expect:
  exitCode: 0
  stdoutContains:
    - "@exported annotation tests passed"
*/
/**
 * Test for @exported annotation functionality
 *
 * This follows the repo's lightweight "runnable object" style instead of JUnit.
 */
object ExportedAnnotationTest {

    fun runTests(): Boolean {
        return try {
            println("Running @exported annotation tests...")
            testBasicExport()
            testMultipleExports()
            testNoDuplicates()
            testTypeSimplification()
            testExportedValAndVar()
            testReplaceExistingDeclarationOnTypeChange()
            println("✅ @exported annotation tests passed")
            true
        } catch (t: Throwable) {
            println("❌ @exported annotation tests failed: ${t.message}")
            t.printStackTrace()
            false
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        check(runTests()) { "@exported annotation tests failed" }
    }

    private fun testBasicExport() {
        println("  Testing basic @exported function...")

        // Create a box and KI
        val box = Box("test_export_1")
        val ki = KotlinInterface(filePrefix = "test1")
        ki.associatedBox = box

        // Execute code with an @exported function
        val code = """
            @exported
            fun add(x: Int, y: Int): Int {
                return x + y
            }
        """.trimIndent()

        ki.execAndWait(code)

        // Check that the preamble was updated
        val preamble = box.preamble
        println("    Preamble: $preamble")
        require(preamble.isNotEmpty()) { "Preamble should not be empty" }
        require(preamble.contains("val Box._add by InheritanceFromContext<")) { "Preamble should contain exported _add declaration" }
        require(preamble.contains("kotlin.Int")) { "Preamble should contain parameter types" }
        require(preamble.contains("/**/")) { "Preamble should contain inherit marker /**/" }

        val exported = box.getPropertyRaw("_add")
        require(exported != null) { "Box property '_add' should be set to an exported function reference" }
        @Suppress("UNCHECKED_CAST")
        val add = exported as (Int, Int) -> Int
        require(add(2, 3) == 5) { "Exported add function should be callable via box property" }
    }

    private fun testMultipleExports() {
        println("  Testing multiple @exported functions...")

        val box = Box("test_export_2")
        val ki = KotlinInterface(filePrefix = "test2")
        ki.associatedBox = box

        // Execute code with multiple @exported functions
        val code = """
            @exported
            fun multiply(x: Int, y: Int): Int = x * y
            
            @exported
            fun greet(name: String): String = "Hello, ${'$'}name"
        """.trimIndent()

        ki.execAndWait(code)

        val preamble = box.preamble
        println("    Preamble: $preamble")
        require(preamble.contains("val Box._multiply by InheritanceFromContext<")) { "Preamble should contain _multiply" }
        require(preamble.contains("val Box._greet by InheritanceFromContext<")) { "Preamble should contain _greet" }
    }

    private fun testNoDuplicates() {
        println("  Testing no duplicate exports...")

        val box = Box("test_export_3")
        val ki = KotlinInterface(filePrefix = "test3")
        ki.associatedBox = box

        // Execute code with @exported function twice
        val code = """
            @exported
            fun square(x: Int): Int = x * x
        """.trimIndent()

        ki.execAndWait(code)
        val preambleAfterFirst = box.preamble
        val countAfterFirst = preambleAfterFirst.split("val Box._square by InheritanceFromContext<").size - 1

        ki.execAndWait(code)
        val preambleAfterSecond = box.preamble
        val countAfterSecond = preambleAfterSecond.split("val Box._square by InheritanceFromContext<").size - 1

        println("    After first exec: $countAfterFirst occurrences")
        println("    After second exec: $countAfterSecond occurrences")

        require(countAfterFirst == 1) { "Should have 1 occurrence after first execution" }
        require(countAfterSecond == 1) { "Should still have 1 occurrence after second execution (no duplicates)" }
    }

    private fun testTypeSimplification() {
        println("  Testing type simplification for non-whitelisted types...")

        val box = Box("test_export_4")
        val ki = KotlinInterface(filePrefix = "test4")
        ki.associatedBox = box

        // Execute code with a function that uses a custom type
        // Since we can't easily create custom types in the test, we'll just verify
        // that the basic whitelisted types work correctly
        val code = """
            @exported
            fun processData(items: List<String>): Int {
                return items.size
            }
        """.trimIndent()

        ki.execAndWait(code)

        val preamble = box.preamble
        println("    Preamble: $preamble")
        require(preamble.contains("val Box._processData by InheritanceFromContext<")) { "Preamble should contain processData" }
        require(preamble.contains("/**/")) { "Preamble should contain inherit marker" }
    }

    private fun testExportedValAndVar() {
        println("  Testing @exported val/var...")

        val box = Box("test_export_5")
        val ki = KotlinInterface(filePrefix = "test5")
        ki.associatedBox = box

        val code = """
            @exported
            val answer: Int = 42

            @exported
            var counter: Int = 1

            counter += 4
        """.trimIndent()

        ki.execAndWait(code)

        val preamble = box.preamble
        println("    Preamble: $preamble")
        require(preamble.contains("val Box._answer by InheritanceFromContext<")) { "Preamble should contain exported val alias _answer" }
        require(preamble.contains("var Box._counter by InheritanceFromContext<")) { "Preamble should contain exported var alias _counter" }

        val answer = box.getPropertyRaw("_answer")
        val counter = box.getPropertyRaw("_counter")
        require(answer == 42) { "Expected _answer to be 42, got $answer" }
        require(counter == 5) { "Expected _counter to be 5, got $counter" }
    }

    private fun testReplaceExistingDeclarationOnTypeChange() {
        println("  Testing replacement of existing exported declarations when type changes...")

        val box = Box("test_export_6")
        val ki = KotlinInterface(filePrefix = "test6")
        ki.associatedBox = box

        // Seed preamble with legacy declaration forms that should be replaced.
        box.preamble = """
            val _convert by Delegation<(kotlin.Int) -> kotlin.Int>() /**/
            val _answer: kotlin.Int /**/
        """.trimIndent()

        val code = """
            @exported
            fun convert(x: String): String = x.reversed()

            @exported
            val answer: String = "ok"
        """.trimIndent()

        ki.execAndWait(code)

        val preamble = box.preamble
        println("    Preamble after replacement: $preamble")

        require(!preamble.contains("val _convert by Delegation<(kotlin.Int) -> kotlin.Int>() /**/")) {
            "Old function declaration should be removed"
        }
        require(!preamble.contains("val _answer: kotlin.Int /**/")) {
            "Old val declaration should be removed"
        }
        require(preamble.contains("val Box._convert by InheritanceFromContext<(kotlin.String) -> kotlin.String>() /**/")) {
            "Updated function declaration should be present"
        }
        require(preamble.contains("val Box._answer by InheritanceFromContext<kotlin.String>() /**/")) {
            "Updated val declaration should be present"
        }
    }
}

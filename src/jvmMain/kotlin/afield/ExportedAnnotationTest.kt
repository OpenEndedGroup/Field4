package afield

import afield.logger.lprintln

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
        require(preamble.contains("val add:")) { "Preamble should contain 'val add:'" }
        require(preamble.contains("kotlin.Int")) { "Preamble should contain parameter types" }
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
        require(preamble.contains("val multiply:")) { "Preamble should contain multiply" }
        require(preamble.contains("val greet:")) { "Preamble should contain greet" }
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
        val countAfterFirst = preambleAfterFirst.split("val square:").size - 1
        
        ki.execAndWait(code)
        val preambleAfterSecond = box.preamble
        val countAfterSecond = preambleAfterSecond.split("val square:").size - 1
        
        println("    After first exec: $countAfterFirst occurrences")
        println("    After second exec: $countAfterSecond occurrences")
        
        require(countAfterFirst == 1) { "Should have 1 occurrence after first execution" }
        require(countAfterSecond == 1) { "Should still have 1 occurrence after second execution (no duplicates)" }
    }
}

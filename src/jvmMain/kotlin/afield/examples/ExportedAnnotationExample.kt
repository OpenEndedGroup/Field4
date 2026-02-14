package afield.examples

import afield.*

/**
 * Example demonstrating the @exported annotation feature.
 * 
 * This example shows how to use @exported to automatically
 * add function signatures to a box's preamble.
 */
object ExportedAnnotationExample {

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== @exported Annotation Example ===\n")
        
        // Create a box
        val box = Box("example_box")
        println("Created box: ${box.id}")
        println("Initial preamble: '${box.preamble}'\n")
        
        // Create a KotlinInterface for the box
        val ki = KotlinInterface(filePrefix = box.id)
        ki.associatedBox = box
        println("Created KotlinInterface\n")
        
        // Example 1: Simple exported function
        println("Example 1: Simple exported function")
        println("--------------------------------------")
        val code1 = """
            @exported
            fun add(x: Int, y: Int): Int {
                return x + y
            }
            
            // Test the function
            println("2 + 3 = " + add(2, 3))
        """.trimIndent()
        
        println("Executing code:\n$code1\n")
        ki.execAndWait(code1)
        println("\nPreamble after execution:")
        println(box.preamble)
        println()
        
        // Example 2: Multiple exported functions
        println("\nExample 2: Multiple exported functions")
        println("--------------------------------------")
        val code2 = """
            @exported
            fun multiply(x: Int, y: Int): Int = x * y
            
            @exported
            fun greet(name: String): String = "Hello, ${'$'}name!"
            
            @exported
            fun computeArea(width: Double, height: Double): Double {
                return width * height
            }
        """.trimIndent()
        
        println("Executing code:\n$code2\n")
        ki.execAndWait(code2)
        println("\nPreamble after execution:")
        println(box.preamble)
        println()
        
        // Example 3: Function with no parameters
        println("\nExample 3: Function with no parameters")
        println("--------------------------------------")
        val code3 = """
            @exported
            fun getCurrentTime(): Long = System.currentTimeMillis()
        """.trimIndent()
        
        println("Executing code:\n$code3\n")
        ki.execAndWait(code3)
        println("\nPreamble after execution:")
        println(box.preamble)
        println()
        
        // Show final preamble
        println("\n=== Final Preamble ===")
        println(box.preamble)
        println("\n=== End of Example ===")
    }
}

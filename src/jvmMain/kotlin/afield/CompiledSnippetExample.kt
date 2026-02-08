package afield

/**
 * Simple example demonstrating the CompiledSnippet usage pattern.
 * 
 * This is a minimal example showing how to:
 * 1. Compile a code snippet once
 * 2. Evaluate it multiple times with different context
 */
object CompiledSnippetExample {
    
    @JvmStatic
    fun main(args: Array<String>) {
        println("CompiledSnippet Example")
        println("=" * 50)
        
        // Create a KotlinInterface
        val ki = KotlinInterface()
        
        // Example 1: Simple expression
        println("\n1. Simple expression evaluated multiple times:")
        val snippet1 = ki.compileSnippetAndWait("1 + 1")
        if (snippet1 != null) {
            repeat(3) { i ->
                val result = snippet1.evalAndWait()
                println("   Evaluation $i: $result")
            }
        }
        
        // Example 2: Using context variables
        println("\n2. Expression with context variables:")
        ki.execAndWait("var counter = 0")
        
        val snippet2 = ki.compileSnippetAndWait("++counter")
        if (snippet2 != null) {
            repeat(5) { 
                val result = snippet2.evalAndWait(updateContext = true)
                println("   Counter: $result")
            }
        }
        
        // Example 3: Using changing context
        println("\n3. Expression using variables that change:")
        ki.execAndWait("var x = 10")
        ki.execAndWait("var y = 20")
        
        val snippet3 = ki.compileSnippetAndWait("x * y")
        if (snippet3 != null) {
            val result1 = snippet3.evalAndWait()
            println("   x=10, y=20: $result1")
            
            ki.execAndWait("x = 5")
            ki.execAndWait("y = 3")
            
            val result2 = snippet3.evalAndWait()
            println("   x=5, y=3: $result2")
        }
        
        // Example 4: Error handling
        println("\n4. Error handling:")
        val invalidSnippet = ki.compileSnippetAndWait(
            "this is not valid kotlin",
            onCompileError = { errors ->
                println("   Compilation error (expected): ${errors.firstOrNull()?.message?.take(50)}...")
            }
        )
        println("   Snippet is null: ${invalidSnippet == null}")
        
        println("\n" + "=" * 50)
        println("Example completed successfully!")
    }
}

private operator fun String.times(n: Int) = repeat(n)

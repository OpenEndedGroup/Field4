package afield.test

/**
 * This is a test class with KDoc
 * 
 * It has multiple lines of documentation
 * to test the KDoc extraction
 */
class TestClass {
    
    /**
     * A test function with KDoc
     * @param x the first parameter
     * @return the result value
     */
    fun testFunction(x: Int): Int {
        return x * 2
    }
    
    /**
     * A property with KDoc documentation
     */
    val testProperty: String = "test"
}

/**
 * A top-level function with KDoc
 */
fun topLevelFunction() {
    println("Hello")
}

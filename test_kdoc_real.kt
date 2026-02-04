package afield.test

/**
 * This is a simple test to verify KDoc extraction works
 */
class SimpleKDocTest {
    
    /**
     * Test function with a single line KDoc
     */
    fun simpleFunction(): String = "test"
    
    /**
     * Test function with multi-line KDoc
     * 
     * This has multiple lines
     * and more details
     */
    fun multiLineFunction(x: Int): Int {
        return x * 2
    }
}

/**
 * returns a list of `Vec3` (i.e. positions) by repeatedly moving along the line by 'distance' and recording those positions. Note that while the start of the line will be in this list the
 * end point will probably not (unless the length of the line just happens to be exactly divisible by `distance`)
 */
fun sampleByDistance(distance: Float): List<String> {
    return emptyList()
}

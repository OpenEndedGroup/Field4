package afield.test

import afield.*
import afield.stdlib.Marker

/**
 * Test file demonstrating the liveNumber functionality.
 * This can be used as a reference or executed in a Field4 box.
 * 
 * Note: These are demonstration examples, not automated unit tests.
 * They are meant to be run interactively in Field4 to see the live number behavior.
 * 
 * The liveNumber feature allows reading numeric values directly from source code
 * without recompilation. When you change the number in the editor, the value
 * changes immediately on the next evaluation.
 */

// Example 1: Simple live number
fun Box.testSimpleLiveNumber() {
    val m = __m("speed")
    
    // The value 10.0 can be changed in the editor without recompilation
    val speed = m.liveNumber(10.0)
    
    println("Current speed: $speed")
    // Try changing 10.0 to 20.0 in the editor and re-run!
}

// Example 2: Multiple live numbers for animation
fun Box.testAnimationParams() {
    val speedMarker = __m("animSpeed")
    val amplitudeMarker = __m("animAmplitude")
    val frequencyMarker = __m("animFrequency")
    
    // These values can all be changed in the editor without recompilation
    val speed = speedMarker.liveNumber(1.0)
    val amplitude = amplitudeMarker.liveNumber(10.0)
    val frequency = frequencyMarker.liveNumber(2.0)
    
    println("Animation parameters:")
    println("  Speed: $speed")
    println("  Amplitude: $amplitude")
    println("  Frequency: $frequency")
}

// Example 3: Live number with default value
fun Box.testLiveNumberWithDefault() {
    val m = __m("threshold")
    
    // If argument is empty or invalid, returns the default value
    val threshold = m.liveNumber(0.5)
    
    println("Threshold: $threshold")
}

// Example 4: Live numbers for position control
fun Box.testPositionControl() {
    val xMarker = __m("posX")
    val yMarker = __m("posY")
    val zMarker = __m("posZ")
    
    val x = xMarker.liveNumber(0.0)
    val y = yMarker.liveNumber(0.0)
    val z = zMarker.liveNumber(0.0)
    
    println("Position: ($x, $y, $z)")
    // Change the numbers in the editor to move the position!
}

// Example 5: Demonstrating live numbers in a loop
suspend fun Box.testLiveNumberInLoop() {
    val delayMarker = __m("loopDelay")
    
    repeat(10) { i ->
        // This value can be changed while the loop is running!
        val delayMs = delayMarker.liveNumber(100.0).toLong()
        
        println("Iteration $i (delay: ${delayMs}ms)")
        kotlinx.coroutines.delay(delayMs)
    }
}

// Example 6: Numeric formats
fun Box.testNumericFormats() {
    val intMarker = __m("testInt")
    val floatMarker = __m("testFloat")
    val scientificMarker = __m("testScientific")
    
    // Works with integers
    val intValue = intMarker.liveNumber(42.0)
    println("Integer: $intValue")
    
    // Works with floats
    val floatValue = floatMarker.liveNumber(3.14159)
    println("Float: $floatValue")
    
    // Works with scientific notation
    val scientificValue = scientificMarker.liveNumber(1.5e-3)
    println("Scientific: $scientificValue")
}

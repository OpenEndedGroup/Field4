package afield.test

import afield.*

/**
 * Test file demonstrating the slider functionality.
 * This can be used as a reference or executed in a Field4 box.
 */

// Example 1: Simple single slider
fun Box.testSimpleSlider() {
    var myValue = 0.5
    
    slider(
        label = "Test Parameter",
        min = 0.0,
        max = 1.0,
        step = 0.01,
        initialValue = myValue,
        channel = "test controls"
    ) { newValue ->
        myValue = newValue
        println("Slider value changed to: $myValue")
    }
}

// Example 2: Multiple sliders
fun Box.testMultipleSliders() {
    var x = 0.0
    var y = 0.0
    var z = 0.0
    
    sliders(
        channel = "coordinates",
        "x" to SliderConfig(
            label = "X Position",
            min = -100.0,
            max = 100.0,
            step = 1.0,
            initialValue = x,
            onUpdate = { x = it }
        ),
        "y" to SliderConfig(
            label = "Y Position",
            min = -100.0,
            max = 100.0,
            step = 1.0,
            initialValue = y,
            onUpdate = { y = it }
        ),
        "z" to SliderConfig(
            label = "Z Position",
            min = -100.0,
            max = 100.0,
            step = 1.0,
            initialValue = z,
            onUpdate = { z = it }
        )
    )
    
    // Print values every second to show they're updating
    repeat(10) {
        Thread.sleep(1000)
        println("Position: ($x, $y, $z)")
    }
}

// Example 3: Color mixer with sliders
fun Box.testColorMixer() {
    var red = 128.0
    var green = 128.0
    var blue = 128.0
    
    sliders(
        channel = "color mixer",
        "r" to SliderConfig(
            label = "Red",
            min = 0.0,
            max = 255.0,
            step = 1.0,
            initialValue = red,
            onUpdate = { 
                red = it
                updateColor(red, green, blue)
            }
        ),
        "g" to SliderConfig(
            label = "Green",
            min = 0.0,
            max = 255.0,
            step = 1.0,
            initialValue = green,
            onUpdate = { 
                green = it
                updateColor(red, green, blue)
            }
        ),
        "b" to SliderConfig(
            label = "Blue",
            min = 0.0,
            max = 255.0,
            step = 1.0,
            initialValue = blue,
            onUpdate = { 
                blue = it
                updateColor(red, green, blue)
            }
        )
    )
}

private fun Box.updateColor(r: Double, g: Double, b: Double) {
    val hex = String.format("#%02X%02X%02X", r.toInt(), g.toInt(), b.toInt())
    println("Color: $hex")
    // Display the color
    display("color preview", """
        <div style="width: 100px; height: 100px; background-color: $hex; border: 2px solid white;"></div>
    """)
}

// Example 4: Animation parameters
fun Box.testAnimationParams() {
    var speed = 1.0
    var amplitude = 10.0
    var frequency = 2.0
    
    sliders(
        channel = "animation",
        "speed" to SliderConfig(
            label = "Speed",
            min = 0.1,
            max = 5.0,
            step = 0.1,
            initialValue = speed,
            onUpdate = { speed = it }
        ),
        "amplitude" to SliderConfig(
            label = "Amplitude",
            min = 1.0,
            max = 50.0,
            step = 1.0,
            initialValue = amplitude,
            onUpdate = { amplitude = it }
        ),
        "frequency" to SliderConfig(
            label = "Frequency",
            min = 0.1,
            max = 10.0,
            step = 0.1,
            initialValue = frequency,
            onUpdate = { frequency = it }
        )
    )
    
    // Simulate animation loop (simplified)
    var time = 0.0
    repeat(100) {
        time += 0.1
        val y = amplitude * kotlin.math.sin(frequency * time * speed)
        println("y = $y")
        Thread.sleep(50)
    }
}

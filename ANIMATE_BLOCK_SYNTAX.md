# Animate Block Syntax

The `animate { }` block provides a formal way to distinguish between setup/preamble code and animation loop code.

## Syntax

```kotlin
// Setup code runs once
foo()
bar(10.0)

// Animation code runs every frame
animate {
    gux()
}
```

## How It Works

The `animate` function:
- Takes a lambda that will be executed every frame
- Returns a `Frames` object
- When code containing `animate` is launched (Shift+Cmd+Enter), Field4 executes:
  1. All code **before** the `animate` block once (preamble/setup)
  2. The code **inside** the `animate` block every frame

## Examples

### Example 1: Processing Animation

```kotlin
// Setup - runs once
p.stroke(255f)
p.strokeWeight(2f)
val speed = 5f

// Animation - runs every frame
animate {
    p.background(0f)
    p.fill(255f, 100f, 100f)
    p.ellipse(p.mouseX.toFloat(), p.mouseY.toFloat(), 50f, 50f)
}
```

### Example 2: With State

```kotlin
// Initialize variables - runs once
var x = 100f
var y = 100f
var vx = 2f
var vy = 3f

// Animation loop - runs every frame
animate {
    p.background(0f)
    
    // Update position
    x += vx
    y += vy
    
    // Bounce off edges
    if (x < 0 || x > p.width) vx *= -1
    if (y < 0 || y > p.height) vy *= -1
    
    // Draw
    p.fill(255f, 200f, 100f)
    p.ellipse(x, y, 40f, 40f)
}
```

### Example 3: Multiple Setup Steps

```kotlin
// All of this runs once
val colors = listOf(
    Triple(255f, 0f, 0f),
    Triple(0f, 255f, 0f),
    Triple(0f, 0f, 255f)
)

p.strokeWeight(3f)
println("Animation starting...")

// This runs every frame
animate {
    p.background(0f)
    
    colors.forEachIndexed { i, (r, g, b) ->
        p.fill(r, g, b)
        val x = (i + 1) * p.width / (colors.size + 1).toFloat()
        val y = p.height / 2f + 50 * kotlin.math.sin(p.frameCount * 0.05f + i)
        p.ellipse(x, y, 60f, 60f)
    }
}
```

### Example 4: Without animate (old style)

You can still use the old style if you want everything to run every frame:

```kotlin
// This all runs every frame
p.background(0f)
p.fill(255f, 100f, 100f)
p.ellipse(p.mouseX.toFloat(), p.mouseY.toFloat(), 50f, 50f)
```

## Advanced Usage

### Multiple animate Blocks

You can have multiple `animate` blocks - they will all run:

```kotlin
val size1 = 50f
val size2 = 30f

animate {
    p.fill(255f, 0f, 0f)
    p.ellipse(100f, 100f, size1, size1)
}

animate {
    p.fill(0f, 255f, 0f)
    p.ellipse(200f, 200f, size2, size2)
}
```

### Using Frames API

The `animate` block gives you access to the `Frames` API:

```kotlin
var counter = 0

animate {
    p.background(0f)
    p.text("Frame: ${frameNumber}", 10f, 20f)
    
    counter++
    if (counter > 100) {
        end() // Stop the animation
    }
}
```

### Combining with Other Frames Functions

```kotlin
// Setup
var phase = 0f

// Use animate for the main loop
animate {
    p.background(0f)
    
    phase += 0.05f
    val x = p.width / 2f + 100 * kotlin.math.cos(phase)
    val y = p.height / 2f + 100 * kotlin.math.sin(phase)
    
    p.ellipse(x.toFloat(), y.toFloat(), 50f, 50f)
    
    // Wait for a specific condition
    if (kotlin.math.abs(x) < 10) {
        delay(1000) // Pause for 1 second
    }
}
```

## Implementation Details

The `animate` function is implemented as:

```kotlin
fun animate(block: suspend Frames.() -> Unit): Frames {
    return eachFrame(block)
}
```

When you launch code that returns a `Frames` object, Field4's execution system:
1. Executes all code in the box once
2. Detects the `Frames` return value
3. Starts running the frame loop in the main coroutine dispatcher
4. Calls the lambda inside `animate` every frame

## Comparison with begin/end

The old `begin()` / `end()` style:

```kotlin
// Old style - manually specified
val code = """
    p.background(0f)
    p.ellipse(p.mouseX.toFloat(), p.mouseY.toFloat(), 50f, 50f)
"""
begin()
```

The new `animate` style:

```kotlin
// New style - formal syntax
animate {
    p.background(0f)
    p.ellipse(p.mouseX.toFloat(), p.mouseY.toFloat(), 50f, 50f)
}
```

## Stopping Animation

To stop an animation started with `animate`, use the box's `end()` method or the stop command:

```kotlin
// In another box or the same box after some condition:
this.end()
```

Or use the stop command (Shift+Cmd+Period or equivalent).

## Related Functions

- `frames { }` - Lower-level frame control with manual frame() calls
- `loop { }` - Similar to animate but with automatic frame advancement
- `eachFrame { }` - Direct alias for what animate uses
- `start { }` - Start a frame-based animation

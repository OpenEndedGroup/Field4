# Processing Integration Quick Start

This guide will help you get started with Processing in Field4.

## What is Processing?

Processing is a flexible software sketchbook and a language for learning how to code within the context of the visual arts. Field4's Processing integration allows you to use Processing's graphics API within Field4's live coding environment.

## Getting Started

### 1. Create a Processing Root Box

First, create a parent box that will be the root for your Processing workspace:

1. Create a new box in Field4
2. Set its `isProcessingRoot` property to `true` (you can do this in the box properties)
3. All child boxes of this root will now execute in the Processing context

### 2. Create Processing Code Boxes

Create child boxes under the Processing root. The code in these boxes will have access to the Processing API.

### 3. Write Processing Code

In child boxes, you can use all Processing functions. The Processing applet is accessible via the `p` variable:

```kotlin
// Draw a red circle
p.fill(255f, 0f, 0f)
p.ellipse(400f, 300f, 100f, 100f)
```

Or with Kotlin's `with` scope, you can omit the `p.` prefix:

```kotlin
with(p) {
    fill(255f, 0f, 0f)
    ellipse(400f, 300f, 100f, 100f)
}
```

## Execution Modes

### Run Once (Execute Fragment)

Use Shift+Enter or the "Execute Fragment" command to run code once:

```kotlin
// Set stroke properties (one time)
stroke(255f)
strokeWeight(2f)
```

### Run Every Frame (Launch) - New Syntax!

Use the **`animate { }`** block to clearly separate setup from animation:

```kotlin
// Setup - runs once
val speed = 5f
stroke(255f)

// Animation - runs every frame
animate {
    background(0f)
    fill(255f, map(mouseX.toFloat(), 0f, width.toFloat(), 0f, 255f), 0f)
    ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)
}
```

**Why use `animate`?**
- Clearer separation between setup and animation code
- More formal and readable syntax
- The code outside `animate` runs once, inside runs every frame
- See [`ANIMATE_BLOCK_SYNTAX.md`](../animation/ANIMATE_BLOCK_SYNTAX.md) for more details

### Run Every Frame (Launch) - Old Style

You can still use the launch command without `animate` - all code runs every frame:

```kotlin
// All of this runs every frame
background(0f)
fill(255f, map(mouseX.toFloat(), 0f, width.toFloat(), 0f, 255f), 0f)
ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)
```

### Stop Running Code

Use the "Stop" command to remove all launched code from a box.

## Examples

### Example 1: Simple Animation with animate

```kotlin
// Setup (runs once)
val speed = 5f

// Animation (runs every frame)
animate {
    // Clear the screen
    background(0f)
    
    // Draw a circle that follows the mouse
    fill(255f, 100f, 100f)
    ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)
}
```

### Example 2: Rotating Shapes

```kotlin
// Setup variables
val size = 100f

// Animate
animate {
    background(0f)
    translate(width / 2f, height / 2f)
    rotate(frameCount * 0.01f)
    fill(100f, 200f, 255f)
    rect(-size/2, -size/2, size, size)
}
```

### Example 3: Interactive Drawing

```kotlin
// Setup
strokeWeight(2f)
stroke(255f)

// Animate
animate {
    if (mousePressed) {
        line(pmouseX.toFloat(), pmouseY.toFloat(), 
             mouseX.toFloat(), mouseY.toFloat())
    }
}
```

### Example 4: Multiple Elements

You can have multiple boxes running simultaneously:

**Box 1 - Background:**
```kotlin
background(0f)
```

**Box 2 - Grid:**
```kotlin
stroke(50f)
for (i in 0 until width step 50) {
    line(i.toFloat(), 0f, i.toFloat(), height.toFloat())
}
for (i in 0 until height step 50) {
    line(0f, i.toFloat(), width.toFloat(), i.toFloat())
}
```

**Box 3 - Moving Circle:**
```kotlin
fill(255f, 200f, 0f)
ellipse((frameCount % width).toFloat(), height / 2f, 40f, 40f)
```

## Tips and Tricks

### 1. Use Kotlin Features

You can use Kotlin's features along with Processing:

```kotlin
// Use Kotlin's range and map
(0 until 10).map { i ->
    val x = i * width / 10f
    fill(i * 25f, 255f - i * 25f, 200f)
    ellipse(x, height / 2f, 30f, 30f)
}
```

### 2. Store State in Variables

Since launched code runs every frame, you can use variables to maintain state:

```kotlin
// In one box, initialize
var circleX = width / 2f
var circleY = height / 2f
var velocityX = 2f
var velocityY = 2f

// In the launched code
background(0f)
circleX += velocityX
circleY += velocityY

// Bounce off edges
if (circleX < 0 || circleX > width) velocityX *= -1
if (circleY < 0 || circleY > height) velocityY *= -1

fill(255f, 100f, 100f)
ellipse(circleX, circleY, 50f, 50f)
```

### 3. Combine with Field4's Features

You can use Field4's features like outputs, properties, and box communication:

```kotlin
// Output values for debugging
println("FPS: ${frameRate}")
println("Mouse: ($mouseX, $mouseY)")

// Use box properties
val myColor = this.properties["color"] as? Int ?: 255
fill(myColor.toFloat(), 100f, 100f)
```

## Common Processing Methods

### Drawing
- `background(gray)` - Clear screen
- `fill(r, g, b)` - Set fill color
- `stroke(r, g, b)` - Set stroke color
- `noFill()` / `noStroke()` - Disable fill/stroke
- `strokeWeight(weight)` - Set line width

### Shapes
- `point(x, y)` - Draw a point
- `line(x1, y1, x2, y2)` - Draw a line
- `ellipse(x, y, w, h)` - Draw an ellipse
- `rect(x, y, w, h)` - Draw a rectangle
- `triangle(x1, y1, x2, y2, x3, y3)` - Draw a triangle

### Transform
- `translate(x, y)` - Move origin
- `rotate(angle)` - Rotate (angle in radians)
- `scale(s)` - Scale
- `pushMatrix()` / `popMatrix()` - Save/restore transform

### Input
- `mouseX`, `mouseY` - Current mouse position
- `pmouseX`, `pmouseY` - Previous mouse position
- `mousePressed` - Boolean, is mouse button pressed
- `key` - Last key pressed
- `keyPressed` - Boolean, is any key pressed

### Math
- `map(value, start1, stop1, start2, stop2)` - Remap value
- `lerp(start, stop, amt)` - Linear interpolation
- `constrain(value, min, max)` - Constrain value
- `random(high)` or `random(low, high)` - Random number

### System
- `width`, `height` - Applet dimensions
- `frameCount` - Number of frames since start
- `frameRate` - Current frame rate
- `frameRate(fps)` - Set target frame rate

## Troubleshooting

### Processing Window Not Appearing

The Processing window is created lazily on first execution. If it doesn't appear:
1. Check the console for errors
2. Try executing simple code first: `println("Hello Processing")`
3. Make sure you're on a system that supports Swing/AWT windows

### Code Not Running

Make sure:
1. The parent box has `isProcessingRoot` set to `true`
2. You're executing code in a child box, not the root
3. For animations, use "Launch" not "Execute Fragment"

### Performance Issues

If animations are slow:
1. Minimize work in the draw loop
2. Use `noStroke()` or `noFill()` when not needed
3. Check frameRate: `println(frameRate)`
4. Consider using simpler shapes or fewer elements

## Next Steps

- Check out the example template in `templates/processing-example.edn`
- Read the full documentation in [`PROCESSING_SUPPORT.md`](./PROCESSING_SUPPORT.md)
- Explore the Processing reference: https://processing.org/reference/
- Experiment with combining Processing graphics with Field4's other features

## Feedback

This is a new feature! Please report any issues or suggestions for improvements.

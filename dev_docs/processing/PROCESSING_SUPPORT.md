# Processing Support for Field4

This document describes the Processing integration in Field4, which allows you to write Processing-style graphics code within the Field4 environment.

## Overview

The Processing support provides:
- A wrapper around Processing's PApplet that runs in its own window
- The ability to execute Kotlin code in the Processing context
- Support for inserting/removing code from the render loop (draw method)
- Integration with Field4's Box system for organizing code

## How It Works

### Architecture

1. **Field4ProcessingApplet**: A PApplet subclass that manages the Processing lifecycle (setup/draw)
2. **ProcessingHost**: Manages the applet instance and provides methods to execute code
3. **ProcessingExecution**: Implements BoxExecution interface to integrate with Field4's execution system
4. **Strands**: Code blocks that run every frame in the draw loop

### Creating a Processing Box

To use Processing in Field4:

1. Create a parent box and set its `isProcessingRoot` property to `true`
2. Create child boxes under the Processing root
3. These child boxes will automatically use the Processing execution environment

### Execution Modes

#### executeFragment
Executes code once in the Processing context. The code has access to the Processing applet via the `p` variable.

```kotlin
// Example: Draw a circle once
p.fill(255f, 0f, 0f)
p.ellipse(400f, 300f, 100f, 100f)
```

#### launchAll
Inserts code into the render loop. The code runs every frame in the draw method.

```kotlin
// Example: Animated circle
p.fill(255f, 0f, 0f)
p.ellipse(p.mouseX.toFloat(), p.mouseY.toFloat(), 100f, 100f)
```

#### stop
Removes all code from the render loop for a box.

### Code Examples

#### Basic Setup

```kotlin
// In the child box of a Processing root:

// One-time setup (executeFragment)
p.stroke(255f)
p.strokeWeight(2f)

// Launch animated code (launchAll)
// This will run every frame
p.background(0f)
p.fill(255f, map(p.mouseX.toFloat(), 0f, p.width.toFloat(), 0f, 255f), 0f)
p.ellipse(p.mouseX.toFloat(), p.mouseY.toFloat(), 50f, 50f)
```

#### Multiple Strands

You can have multiple code blocks running simultaneously:

```kotlin
// Strand "background" - clear each frame
p.background(0f)

// Strand "circle" - draw a circle
p.fill(255f, 0f, 0f)
p.ellipse(400f, 300f, 100f, 100f)

// Strand "line" - draw a line to mouse
p.stroke(0f, 255f, 0f)
p.line(400f, 300f, p.mouseX.toFloat(), p.mouseY.toFloat())
```

### API Reference

All Processing methods are available through the `p` variable, which references the PApplet instance. Common methods include:

- **Drawing**: `background()`, `fill()`, `stroke()`, `line()`, `ellipse()`, `rect()`, etc.
- **Input**: `mouseX`, `mouseY`, `mousePressed`, `keyPressed`, etc.
- **Math**: `map()`, `lerp()`, `constrain()`, `random()`, etc.
- **Transform**: `translate()`, `rotate()`, `scale()`, `pushMatrix()`, `popMatrix()`

For the complete API, see the [Processing Reference](https://processing.org/reference/).

### Implementation Details

#### Wrapped Code Execution

When you execute code in a Processing box, it's automatically wrapped to provide access to the applet:

```kotlin
// Your code:
fill(255f, 0f, 0f)
ellipse(400f, 300f, 100f, 100f)

// Wrapped code:
import processing.core.*
import afield.processing.*
val p = getSharedHost().getApplet()
with(p) {
    fill(255f, 0f, 0f)
    ellipse(400f, 300f, 100f, 100f)
}
```

For launched code (running every frame):

```kotlin
// Your code:
background(0f)
ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)

// Wrapped code:
import processing.core.*
import afield.processing.*
val p = getSharedHost().getApplet()
val lambda: () -> Unit = {
    with(p) {
        background(0f)
        ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)
    }
}
lambda // Returns the lambda which gets called every frame
```

#### Thread Safety

The Processing applet runs on the EDT (Event Dispatch Thread). Code execution is coordinated with Field4's main thread using the `inMain` coroutine scope.

#### Applet Lifecycle

- **Boot**: The applet is created lazily on first use
- **settings()**: Called automatically, sets size to 800x600 with P2D renderer
- **setup()**: Called automatically, sets frameRate to 60fps
- **draw()**: Executes all registered strand lambdas each frame

### Limitations and Future Work

Current limitations:
- Single shared applet instance (not per-box isolation)
- Fixed window size (could be made configurable)
- No Processing-specific code completion (delegates to Kotlin completion)
- No support for Processing's `createGraphics()` or PGraphics objects yet

Future enhancements:
- Per-box isolated applets
- Configurable window size and renderer
- Processing-specific autocomplete
- Support for loadImage(), loadFont(), etc.
- Integration with Field4's graphics system

### Related Files

- `src/jvmMain/kotlin/afield/processing/ProcessingInterface.kt` - Main implementation
- `build.gradle.kts` - Processing dependency (org.processing:core:3.3.7)

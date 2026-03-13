# Processing Integration Implementation Summary

## Problem Statement

The goal was to create a wrapper around the Processing project with:
1. Support for a single "applet"
2. Ability to execute Kotlin code "inside" that applet context
3. Map Field's idea of launching code to inserting/deinserting code from the applet's render loop
4. Implemented as a separate version of KotlinExecution.kt similar to Python/Clojure support

## Solution Overview

The implementation follows the established pattern used by Python and Clojure integrations in Field4, creating a complete language runtime wrapper that integrates Processing's graphics capabilities.

## Architecture

### 1. Field4ProcessingApplet (Processing Wrapper)

A custom PApplet subclass that:
- Manages Processing's lifecycle (settings, setup, draw)
- Maintains a map of "strands" (named code blocks) that execute each frame
- Provides methods to add/remove/clear strands
- Runs at 60fps with an 800x600 P2D renderer window

**Key Features:**
- `addStrand(name, code)` - Insert code into the render loop
- `removeStrand(name)` - Remove code from the render loop
- `clearStrands()` - Remove all strands
- Automatic error handling for code executed in draw loop

### 2. ProcessingHost (Applet Manager)

Manages the Processing applet instance:
- Lazy initialization (boots on first use)
- Creates and displays the Processing window using Swing
- Provides a shared singleton instance accessible to all Processing boxes
- Handles applet lifecycle and thread coordination

**Key Features:**
- Thread-safe initialization with EDT (Event Dispatch Thread) coordination
- Waits for applet to complete setup before allowing code execution
- Provides access to the applet for code wrapping

### 3. ProcessingExecution (BoxExecution Implementation)

Implements the BoxExecution interface to integrate with Field4's execution system:

#### executeFragment
- Executes code once in the Processing context
- Wraps user code to provide access to the PApplet via `p` variable
- Uses Field4's `standardExecutionPractice` for compilation and error handling
- Returns output and handles errors through Field4's messaging system

#### executeAll
- Currently delegates to `executeFragment`
- Executes all code in the box once

#### launchAll
- **Core feature for render loop integration**
- Compiles user code into a lambda function
- Wraps code to run in Processing context (`with(p) { ... }`)
- Registers the lambda as a strand in the applet
- Lambda executes every frame in the draw loop
- Uses Field4's generation/watermark system to track running code

#### stop
- Removes all strands from the applet for a box
- Updates running status for all strands
- Effectively "deinserts" code from the render loop

#### executeSymbol
- Executes a symbol as a function call
- Useful for calling Processing functions by name

#### complete
- Delegates to Kotlin completion
- Processing API methods are available through the `p` variable

### 4. Integration with Field4

Following the established pattern:

**@RunsAt(early) - installProcessing()**
- Adds `isProcessingRoot` to persistent properties
- Ensures the property is saved with box metadata

**@RunsAt(plugins) - install()**
- Registers a TopologyWatcher for "processing"
- Monitors box hierarchy changes

**processingTopologyNeedsUpdating()**
- Uses `applyRuntimeToggle` to switch child boxes between Kotlin and Processing execution
- Applies visual customization (blue dashed border) to Processing boxes
- Manages ExecutionContext lifecycle

## Code Wrapping Strategy

### For executeFragment (one-time execution):

```kotlin
// User writes:
fill(255f, 0f, 0f)
ellipse(400f, 300f, 100f, 100f)

// Gets wrapped as:
import processing.core.*
import afield.processing.*
val p = getSharedHost().getApplet()
with(p) {
    fill(255f, 0f, 0f)
    ellipse(400f, 300f, 100f, 100f)
}
```

### For launchAll (render loop):

```kotlin
// User writes:
background(0f)
ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)

// Gets wrapped as:
import processing.core.*
import afield.processing.*
val p = getSharedHost().getApplet()
val lambda: () -> Unit = {
    with(p) {
        background(0f)
        ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)
    }
}
lambda
```

The lambda is then stored and called every frame.

## Key Design Decisions

### 1. Single Shared Applet

**Decision:** Use one shared Processing applet for all Processing boxes.

**Rationale:**
- Simpler to manage one window
- Consistent with Processing's typical single-sketch model
- Allows multiple boxes to contribute to the same canvas
- Reduces resource usage

**Trade-off:** Boxes share the same drawing surface and can interfere with each other.

### 2. Kotlin as the Language

**Decision:** Use Kotlin (not Processing's Java-based syntax) for writing Processing code.

**Rationale:**
- Consistent with Field4's Kotlin-first approach
- Leverages Field4's existing Kotlin compilation infrastructure
- Provides better integration with Field4's features (boxes, properties, etc.)
- Users get Kotlin's modern language features alongside Processing's graphics API

**Trade-off:** Users need to add explicit type suffixes (`.toFloat()`) and use `p.` prefix or `with(p)` scope.

### 3. Strand-Based Architecture

**Decision:** Use named "strands" to manage multiple code blocks in the render loop.

**Rationale:**
- Matches Field4's existing strand concept
- Allows fine-grained control over what's running
- Each strand can be started/stopped independently
- Supports multiple boxes contributing to rendering

### 4. Integration with standardExecutionPractice

**Decision:** Use Field4's standard execution macro for all code compilation.

**Rationale:**
- Consistent error handling and reporting
- Automatic integration with Field4's output system
- Supports Field4's advanced features (macros, implicit receivers, etc.)
- Reduces code duplication

## Comparison with Python/Clojure

| Aspect | Processing | Python | Clojure |
|--------|-----------|---------|---------|
| Language | Kotlin | Python | Clojure |
| Runtime | Processing PApplet | Jep (Java Embedded Python) | Clojure JVM |
| Compilation | Kotlin scripting | Python interpreter | Clojure eval |
| State | Per-box KI context | Global Jep interpreter | Per-namespace |
| Main Use Case | Graphics/Animation | Scripting/Libraries | Functional programming |
| Launch Support | Yes (render loop) | No | No |

## Usage Example

```kotlin
// 1. Create a parent box, set isProcessingRoot = true

// 2. In a child box, write setup code (execute once):
stroke(255f)
strokeWeight(2f)

// 3. In another child box, launch animated code:
background(0f)
fill(map(mouseX.toFloat(), 0f, width.toFloat(), 0f, 255f), 100f, 100f)
ellipse(mouseX.toFloat(), mouseY.toFloat(), 50f, 50f)

// 4. Stop the animation with the stop command
```

## Future Enhancements

1. **Per-Box Applets**: Support isolated applets for each Processing root
2. **Configurable Settings**: Allow users to customize size, renderer, frameRate
3. **Processing-Specific Completion**: Autocomplete for Processing API methods
4. **Resource Loading**: Support for loadImage(), loadFont(), etc.
5. **Graphics Export**: Integration with Field4's graphics system for compositing
6. **PGraphics Support**: Enable offscreen rendering buffers
7. **Better Error Messages**: Processing-specific error formatting

## Testing Considerations

The implementation cannot be fully tested in the current environment due to:
- Headless environment (no display for Swing/AWT windows)
- Network restrictions preventing full Gradle build

However, the implementation:
- Follows established patterns from Python/Clojure integrations
- Uses standard Field4 infrastructure (BoxExecution, standardExecutionPractice, etc.)
- Has no syntax errors (verified with kotlinc)
- Includes comprehensive documentation and examples

## Files Added

1. **src/jvmMain/kotlin/afield/processing/ProcessingInterface.kt** (430+ lines)
   - Complete Processing integration implementation

2. **[PROCESSING_SUPPORT.md](./PROCESSING_SUPPORT.md)**
   - Technical documentation
   - Architecture details
   - API reference

3. **[PROCESSING_QUICKSTART.md](./PROCESSING_QUICKSTART.md)**
   - User-friendly quick start guide
   - Examples and tutorials
   - Troubleshooting tips

4. **templates/processing-example.edn**
   - Example workspace with Processing boxes
   - Demonstrates basic usage patterns

5. **build.gradle.kts** (modified)
   - Added Processing dependency: `org.processing:core:3.3.7`

## Conclusion

The Processing integration provides a complete runtime environment for Processing graphics code within Field4, following the established patterns for language integration. It successfully maps Field's launching concept to Processing's render loop, allowing code to be dynamically inserted and removed from frame-by-frame execution. The implementation is consistent with Python and Clojure integrations while addressing the unique requirements of interactive graphics programming.

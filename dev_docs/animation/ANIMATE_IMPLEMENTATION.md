# Animate Block Implementation Summary

## Problem Statement

The user requested a more formal way to distinguish between preamble/setup code and animation loop code when launching code in Field4. The requirement was:

```kotlin
foo()
bar(10.0)

animate {
   gux() 
}
```

Where `foo()` and `bar(10.0)` run once, and `gux()` runs every frame.

## Solution

Implemented an `animate` function that returns a `Frames` object, leveraging Field4's existing frame execution system.

### Implementation

**File: `src/jvmMain/kotlin/afield/stdlib/stdlib.kt`**

```kotlin
/**
 * Creates an animation loop from a lambda.
 * The lambda will be executed every frame when this function is launched.
 * 
 * Usage:
 * ```
 * foo()
 * bar(10.0)
 * 
 * animate {
 *     gux()
 * }
 * ```
 * This calls foo() and bar() once, then calls gux() every frame.
 */
fun animate(block: suspend Frames.() -> Unit): Frames {
    return eachFrame(block)
}
```

## How It Works

### Execution Flow

1. **User writes code with animate block:**
   ```kotlin
   // Setup code
   val speed = 5f
   println("Starting animation...")
   
   // Animation code
   animate {
       println("Frame: $frameNumber")
   }
   ```

2. **When launched (Shift+Cmd+Enter):**
   - Field4 executes all code in the box once
   - Setup code (`val speed = 5f`, `println(...)`) runs
   - `animate { }` function is called, returns a `Frames` object
   - The return value is detected by `launchReturnValue()` in `execution.kt`

3. **Frame execution:**
   - `launchReturnValue()` recognizes the `Frames` return type
   - Starts running the animation in Field4's main coroutine dispatcher
   - The lambda inside `animate` is called every frame
   - Continues until stopped or the animation ends

### Integration with Existing System

The implementation leverages Field4's existing infrastructure:

- **`Frames` class** (`Phased.kt`): Existing frame execution system
- **`eachFrame` function** (`Phased.kt`): Wraps a lambda to run every frame
- **`launchReturnValue` function** (`execution.kt`): Detects `Frames` return type and executes it

No changes to the core execution system were needed - just a new high-level function.

## Examples

### Basic Animation

```kotlin
// Setup
var x = 100f
val speed = 2f

// Animate
animate {
    println("x = $x")
    x += speed
}
```

### Animation with State

```kotlin
// Setup - runs once
var x = 0.0
val speed = 0.1

// Animation - runs every frame
animate {
    x += speed
    println("x = $x")
}
```

### Multiple Setup Steps

```kotlin
// All of this runs once
val offsets = listOf(0.0, 0.25, 0.5)
println("Animation initialized")

// This runs every frame
animate {
    offsets.forEachIndexed { i, offset ->
        val sample = kotlin.math.sin(frameNumber * 0.05 + offset)
        println("track $i -> $sample")
    }
}
```

## Benefits

1. **Clear Intent**: Explicitly shows what runs once vs. every frame
2. **Readable**: Familiar block syntax makes code easier to understand
3. **Formal**: Standard pattern across all animation code
4. **Backwards Compatible**: Old style (all code in launch) still works
5. **Minimal Implementation**: Just one function, leverages existing system
6. **Type Safe**: Returns `Frames` type, caught at compile time

## Comparison with Alternatives

### Before (Old Style)

```kotlin
// Everything runs every frame
println(frameNumber)
```

Problem: No clear separation between setup and animation

### After (New Style)

```kotlin
// Setup
val label = "tick"

// Animation
animate {
    println("$label $frameNumber")
}
```

Benefit: Clear, formal distinction

### Alternative: Manual Frames

```kotlin
frames {
    while (frame()) {
        gux()
    }
}
```

Problem: More verbose, requires manual frame() calls

### Alternative: begin/end

```kotlin
val code = """
    gux()
"""
begin()
```

Problem: Code as string, no syntax checking

## Documentation

Created comprehensive documentation:

1. **[ANIMATE_BLOCK_SYNTAX.md](./ANIMATE_BLOCK_SYNTAX.md)** - Complete guide with examples
2. **templates/animate-examples.edn** - Example workspace
3. **src/jvmTest/kotlin/afield/test/AnimateBlockTest.kt** - Unit tests

## Testing

Unit tests verify:
- `animate` returns a `Frames` object
- Syntax matches problem statement
- Setup code runs once (conceptually)
- Animation code is wrapped for frame execution

Full integration testing requires running Field4 itself.

## Files Changed

```
Modified:
  src/jvmMain/kotlin/afield/stdlib/stdlib.kt (+19 lines)

Added:
  dev_docs/animation/ANIMATE_BLOCK_SYNTAX.md (156 lines)
  templates/animate-examples.edn (89 lines)
  src/jvmTest/kotlin/afield/test/AnimateBlockTest.kt (94 lines)
```

## Future Enhancements

Potential improvements:

1. **Multiple animate blocks**: Support multiple animation loops in one box
2. **animate.once()**: Special syntax for one-time setup within animate context
3. **animate.every(n)**: Run code every N frames
4. **IDE support**: Syntax highlighting for animate blocks
5. **Debugging**: Special handling in debugger for animate blocks

## Conclusion

The `animate { }` block provides a clean, formal syntax for distinguishing setup from animation code. It leverages Field4's existing `Frames` system, requires minimal code changes, and provides a clear, intentional way to structure animation code that matches the problem statement perfectly.

The implementation is:
- **Simple**: One function, 3 lines of code
- **Powerful**: Leverages entire Frames API
- **Clear**: Obvious intent and usage
- **Compatible**: Works with existing system
- **Documented**: Comprehensive examples and guides

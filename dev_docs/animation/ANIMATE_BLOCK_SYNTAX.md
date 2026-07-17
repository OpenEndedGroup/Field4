# Animate Block Syntax

The `animate { }` block provides a formal way to distinguish between setup/preamble
code and animation-loop code.

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

`animate` takes a lambda, returns a `Frames` object, and (via `eachFrame`) arranges
for that lambda to run once per frame. When you launch a box whose code returns a
`Frames` value, Field4:

1. Runs all code **before** the `animate` block once (the preamble/setup).
2. Runs the code **inside** the `animate` block every frame.

Launch a box with the editor's **"Launch Box"** action (Alt+UpArrow). Only the
last expression's return value is used, so `animate { ... }` should be the final
statement in the box.

The implementation contract lives with the code: see the KDoc on `animate` in
`src/jvmMain/kotlin/afield/stdlib/stdlib.kt` and on `Frames`/`eachFrame`/`loop`/`frames`
in `src/jvmMain/kotlin/afield/Phased.kt`.

## Examples

### Example 1: Simple Frame Loop

```kotlin
// Setup - runs once
var x = 0.0
val speed = 0.05

// Animation - runs every frame
animate {
    x += speed
    println("x = $x")
}
```

### Example 2: With State

```kotlin
// Initialize variables - runs once
var position = 0.0
var velocity = 0.12

// Animation loop - runs every frame
animate {
    position += velocity
    if (position > 1.0 || position < 0.0) velocity *= -1.0
    println("position = $position, velocity = $velocity")
}
```

### Example 3: Multiple Setup Steps

```kotlin
// All of this runs once
val offsets = listOf(0.0, 0.25, 0.5)
println("Animation starting...")

// This runs every frame
animate {
    offsets.forEachIndexed { i, offset ->
        val sample = kotlin.math.sin(frameNumber * 0.05 + offset)
        println("track $i -> $sample")
    }
}
```

## Frame Semantics

Inside the `animate` block you are running as the receiver of a `Frames` object,
so its members are in scope:

- `frameNumber` — the current frame count, incremented each frame.
- `end()` — request that the animation stop. The loop stops the next time it
  reaches a frame boundary.
- `delay(ms)` — suspend for `ms` milliseconds and then advance a frame.

```kotlin
var counter = 0

animate {
    println("Frame: $frameNumber")
    counter++
    if (counter > 100) {
        end() // Stop the animation
    }
}
```

## Stopping Animation

To stop an animation started with `animate`, call `end()` from inside the block
(as above), call the box's `end()` method, or use the editor's **"Stop Box"**
action (Alt+DownArrow, or Alt+Shift+DownArrow).

```kotlin
// In another box, or the same box after some condition:
this.end()
```

## Related Functions

All of these build on the `Frames` machinery in `Phased.kt`:

- `frames { }` — lower-level frame control with manual `frame()` calls.
- `loop { }` — like `animate` but auto-advances a frame each pass even if the
  body never calls `frame()`.
- `eachFrame { }` — the function `animate` delegates to; runs the body once per
  frame while `frame()` returns true.
- `start { }` — start a frame-based animation (a thin wrapper around `frames`).

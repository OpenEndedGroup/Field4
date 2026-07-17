# PreparedSnippet - Compile Once, Evaluate Many Times

## Overview

`compileSnippet` lets you compile a piece of Kotlin code once within the context of a `KotlinScriptEngine` (`box.ki`) and then evaluate it multiple times. This is more efficient than using `exec()` repeatedly for the same code, as compilation happens only once.

`compileSnippet(...)` returns the `PreparedSnippet` interface (`KotlinScriptEngine.kt`). Concrete instances are either `KotlinInterface.CompiledSnippet` (the legacy K1 engine) or `KotlinInterface2.K2PreparedSnippet` (the K2 engine, which is the default backend — `afield.kotlin.engine` defaults to `"k2"`). Both implement the same `PreparedSnippet` interface, so the examples below are identical across backends.

For a complete runnable example, see [CompiledSnippetExample.kt](../src/jvmMain/kotlin/afield/examples/CompiledSnippetExample.kt).

## Use Cases

1. **Performance-critical loops**: When you need to execute the same code snippet in a tight loop or animation frame
2. **Reactive programming**: Compile a formula once and re-evaluate it when input data changes
3. **User-defined expressions**: Allow users to input expressions that are evaluated repeatedly
4. **Cached computations**: Compile computation logic once and reuse across multiple data sets

## API

### Compilation

```kotlin
// Async version (preferred in coroutine contexts)
suspend fun compileSnippet(
    s: String,
    implicits: List<Any> = emptyList(),
    onCompileError: (List<ScriptDiagnostic>) -> Unit = {}
): PreparedSnippet?

// Blocking version (convenient for simple cases)
fun compileSnippetAndWait(
    s: String,
    implicits: List<Any> = emptyList(),
    onCompileError: (List<ScriptDiagnostic>) -> Unit = {}
): PreparedSnippet?
```

### Evaluation

```kotlin
// Async version — note onEvalException rethrows by default
suspend fun PreparedSnippet.eval(
    updateContext: Boolean = false,
    implicits: List<Any> = emptyList(),
    onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
    onEvalException: (Throwable) -> Unit = { throw it }
): ResultValue?

// Blocking version
fun PreparedSnippet.evalAndWait(
    updateContext: Boolean = false,
    implicits: List<Any> = emptyList(),
    onEvalError: (List<ScriptDiagnostic>) -> Unit = {},
    onEvalException: (Throwable) -> Unit = {}
): ResultValue?
```

Passing an empty `implicits` list (the default) falls back to the implicits captured at compile time; supply a non-empty list to override them for that evaluation.

## Examples

### Basic Example

```kotlin
val ki = box.ki

// Compile once
val snippet = ki.compileSnippet("2 + 2")
if (snippet != null) {
    // Evaluate multiple times
    val result1 = snippet.eval()  // Returns 4
    val result2 = snippet.eval()  // Returns 4
    val result3 = snippet.eval()  // Returns 4
}
```

### Using Context Variables

```kotlin
val ki = box.ki

// Set up variables in the context
ki.exec("var x = 10")
ki.exec("var y = 20")

// Compile a snippet that uses context variables
val snippet = ki.compileSnippet("x + y")
if (snippet != null) {
    val result1 = snippet.eval()  // Returns 30
    
    // Change the variables
    ki.exec("x = 5")
    ki.exec("y = 15")
    
    // Evaluate again with new values
    val result2 = snippet.eval()  // Returns 20
}
```

### Animation Loop Example

```kotlin
val ki = box.ki

// Set up frame counter
ki.exec("var frame = 0")

// Compile animation logic once
val animationSnippet = ki.compileSnippet("""
    frame++
    val angle = frame * 0.1
    val x = Math.cos(angle) * 100
    val y = Math.sin(angle) * 100
    println("Position: (${'$'}x, ${'$'}y)")
    frame
""")

// In your animation loop (called every frame)
if (animationSnippet != null) {
    repeat(60) { // 60 frames
        animationSnippet.eval(updateContext = true)
        // No compilation overhead per frame!
    }
}
```

### User Expression Evaluation

```kotlin
val ki = box.ki

// User defines an expression
val userExpression = "speed * time"

// Set up input variables
ki.exec("var speed = 0.0")
ki.exec("var time = 0.0")

// Compile the user's expression
val snippet = ki.compileSnippet(userExpression)

if (snippet != null) {
    // Evaluate with different inputs
    ki.exec("speed = 10.0; time = 2.0")
    val distance1 = snippet.eval()  // Returns 20.0
    
    ki.exec("speed = 15.0; time = 3.0")
    val distance2 = snippet.eval()  // Returns 45.0
    
    ki.exec("speed = 5.0; time = 10.0")
    val distance3 = snippet.eval()  // Returns 50.0
}
```

### With Box Implicit Receiver

```kotlin
// Compile with Box as implicit receiver
val snippet = box.ki.compileSnippet(
    "name + \" at frame \" + frame",
    implicits = listOf(box)
)

if (snippet != null) {
    // Can access box properties directly in the snippet
    val result = snippet.eval()
}
```

### Error Handling

```kotlin
val ki = box.ki

// Handle compilation errors
val snippet = ki.compileSnippet(
    "invalid kotlin code!!!",
    onCompileError = { errors ->
        errors.forEach { error ->
            println("Compile error: ${error.message}")
        }
    }
)
// snippet will be null if compilation fails

// Handle evaluation errors
val validSnippet = ki.compileSnippet("throw RuntimeException(\"error\")")
if (validSnippet != null) {
    validSnippet.eval(
        onEvalException = { exception ->
            println("Evaluation failed: ${exception.message}")
        }
    )
}
```

### Updating Context

```kotlin
val ki = box.ki

// Compile a snippet that defines new variables/functions
val snippet = ki.compileSnippet("val myNewVar = 42")

if (snippet != null) {
    // Evaluate and update the context so myNewVar is available later
    snippet.eval(updateContext = true)
    
    // Now myNewVar is available in subsequent exec calls
    ki.exec("println(myNewVar)")  // Prints 42
}
```

## Performance Considerations

### When to Use CompiledSnippet

- ✅ Code that executes repeatedly (loops, animations, event handlers)
- ✅ User-defined expressions evaluated with different data
- ✅ Hot paths where compilation overhead matters

### When to Use Regular exec()

- ✅ One-time code execution
- ✅ Code that changes frequently
- ✅ Interactive development/REPL usage

### Performance Comparison

```kotlin
// Using exec() - recompiles every time
repeat(1000) {
    ki.exec("println(\"Hello \$it\")")  // ~1000 compilations
}

// Using CompiledSnippet - compiles once
val snippet = ki.compileSnippet("println(\"Hello \$it\")")
if (snippet != null) {
    repeat(1000) {
        snippet.eval()  // Only 1 compilation!
    }
}
```

## Implementation Notes

- The returned `PreparedSnippet` is a concrete inner class of the active engine: `KotlinInterface.CompiledSnippet` (K1) or `KotlinInterface2.K2PreparedSnippet` (K2, default). Either way it holds a reference to its parent engine.
- Compiled snippets capture the compilation configuration at compile time
- Implicit receivers and properties are resolved during evaluation, so they can change between evaluations
- The `updateContext` parameter controls whether evaluation results are added to the context for future use

## Thread Safety

Like the rest of the Kotlin script engine, a `PreparedSnippet` is not thread-safe. Use appropriate synchronization if accessing from multiple threads.

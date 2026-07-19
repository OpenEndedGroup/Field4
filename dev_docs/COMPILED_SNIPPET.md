# Reusing a compiled Kotlin snippet

`KotlinScriptEngine.compileSnippet(...)` compiles code against the engine's current
REPL state without evaluating it. It returns a `PreparedSnippet`, which can evaluate
that compiled code repeatedly in the same engine.

The authoritative contract—including context updates, implicit receivers, and the
different default exception behavior of suspending and blocking evaluation—is KDoc on
[`KotlinScriptEngine.compileSnippet` and `PreparedSnippet`](../src/jvmMain/kotlin/afield/KotlinScriptEngine.kt).
The active engine is selected by `KotlinEngineConfig`; K2 is the default and K1 remains
available through the `afield.kotlin.engine` system property.

## Minimal pattern

```kotlin
val ki = box.ki
ki.exec("var counter = 0")

val increment = ki.compileSnippetAndWait("++counter")
requireNotNull(increment)

repeat(3) {
    increment.evalAndWait(updateContext = true)
}

ki.exec("println(counter)") // 3
```

`updateContext = true` matters here because the snippet intentionally changes REPL
state. Leave it false for a calculation that should not publish its declarations or
assignments into later evaluations.

Defaults passed as `implicits` to `compileSnippet(...)` are captured by the prepared
snippet. A non-empty `implicits` list passed to `eval(...)` replaces those defaults for
that call; an empty list uses the captured values.

## Error callbacks

Compilation returns `null` after calling `onCompileError`. Runtime failures are reported
to `onEvalException`. The suspending `eval(...)` rethrows by default, while the blocking
`evalAndWait(...)` has a no-op default exception callback; callers that need to observe a
blocking failure must supply the callback.

Executable coverage is
[`CompiledSnippetTest`](../src/jvmMain/kotlin/afield/CompiledSnippetTest.kt). A longer
console example is
[`CompiledSnippetExample`](../src/jvmMain/kotlin/afield/examples/CompiledSnippetExample.kt).
Both currently instantiate the K1 implementation directly, so they prove the shared K1
contract rather than K2 parity. Code using `box.ki` receives the configured backend.

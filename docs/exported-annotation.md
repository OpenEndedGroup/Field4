# @exported Annotation Feature

## Overview

The `@exported` annotation allows you to automatically export function signatures to a box's preamble. When a function is annotated with `@exported`, its fully-typed signature will be added to the box's preamble after execution, making it available as a declared property with the inherit marker (`/**/`).

## Usage

### Basic Example

```kotlin
// In a Field4 box, define a function with @exported annotation
@exported
fun calculateDistance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    return kotlin.math.sqrt(dx * dx + dy * dy)
}
```

After executing this code, the box's preamble will automatically contain:

```kotlin
val calculateDistance: (kotlin.Double, kotlin.Double, kotlin.Double, kotlin.Double) -> kotlin.Double /**/
```

The `/**/` marker indicates this declaration should be inherited by child boxes.

### Multiple Functions

You can export multiple functions in the same code fragment:

```kotlin
@exported
fun add(a: Int, b: Int): Int = a + b

@exported
fun multiply(a: Int, b: Int): Int = a * b

@exported
fun greet(name: String): String = "Hello, $name!"
```

The preamble will contain:

```kotlin
val add: (kotlin.Int, kotlin.Int) -> kotlin.Int /**/
val multiply: (kotlin.Int, kotlin.Int) -> kotlin.Int /**/
val greet: (kotlin.String) -> kotlin.String /**/
```

### Type Whitelisting

The system automatically simplifies types to ensure they work across boxes:

**Whitelisted types** (preserved as-is):
- Kotlin standard types: `kotlin.*` (Int, String, List, etc.)
- Field4 types: `afield.*` (Box, Vec2, Vec3, Vec4, Quat, etc.)
- JOML types: `org.joml.*` (Vector3f, Matrix4f, etc.)
- Built-in types: Int, Long, Float, Double, Boolean, String, etc.

**Non-whitelisted types** are simplified to generic supertypes:
- `java.util.ArrayList<String>` → `List<*>`
- `com.custom.MyClass` → `Any?`
- Custom collections → `Collection<*>`, `Iterable<*>`, etc.

This ensures exported functions use types that are available in all boxes.

### Function Types

The annotation works with any function signature:

```kotlin
// No parameters
@exported
fun getCurrentTime(): Long = System.currentTimeMillis()

// Complex types
@exported
fun processData(items: List<String>, transform: (String) -> Int): List<Int> {
    return items.map(transform)
}

// Nullable types
@exported
fun findFirst(items: List<Int>, predicate: (Int) -> Boolean): Int? {
    return items.firstOrNull(predicate)
}
```

## How It Works

1. **Annotation Detection**: When Kotlin code is executed, the `ContextUpdater` scans all methods in the evaluated script
2. **Signature Generation**: For functions marked with `@exported`, a fully-typed property declaration is generated using Kotlin reflection
3. **Type Simplification**: Types are checked against a whitelist; non-whitelisted types are simplified to generic supertypes
4. **Preamble Update**: The generated declaration with inherit marker (`/**/`) is added to the box's `preamble` property
5. **Duplicate Avoidance**: The system tracks exported functions to prevent duplicate entries

## Implementation Details

- **Annotation**: Defined in `KotlinInterface.kt` with `@Target(AnnotationTarget.FUNCTION)` and `@Retention(AnnotationRetention.RUNTIME)`
- **Detection**: Occurs in `ContextUpdater.refreshMethods()` after script evaluation
- **Signature Format**: Generated as `val functionName: (ParamType1, ParamType2) -> ReturnType /**/`
- **Type Whitelisting**: Types from `kotlin.*`, `afield.*`, `org.joml.*` and built-in types are preserved; others are simplified
- **Inherit Marker**: The `/**/` suffix ensures declarations are inherited by child boxes
- **Storage**: Added to `Box.preamble` using the `HasProperties` interface

## Benefits

1. **Type Safety**: Generated declarations include full type information
2. **Reusability**: Functions can be easily referenced from other boxes
3. **Inheritance**: The `/**/` marker enables parent box function inheritance
4. **Type Compatibility**: Automatic type simplification ensures cross-box compatibility
5. **Documentation**: Preamble serves as a clear interface definition
6. **No Manual Updates**: Signatures are automatically maintained when functions change

## Limitations

- Only functions can be exported (not properties or classes)
- Duplicate function names will only appear once in the preamble (last definition wins)
- The exported signature is a type declaration, not the implementation
- Function must be evaluated at least once for the export to occur

## Example Workflow

1. Create a utilities box with exported functions:
```kotlin
@exported
fun clamp(value: Double, min: Double, max: Double): Double {
    return when {
        value < min -> min
        value > max -> max
        else -> value
    }
}

@exported
fun lerp(a: Double, b: Double, t: Double): Double {
    return a + (b - a) * t
}
```

2. After execution, the box's preamble contains the function signatures
3. Other boxes can now reference these functions with full type information
4. AI code completion will understand the function signatures from the preamble

## Testing

See `ExportedAnnotationTest.kt` for comprehensive tests of this feature.

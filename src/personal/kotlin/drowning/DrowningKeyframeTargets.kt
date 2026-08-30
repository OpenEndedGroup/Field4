@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package drowning

import afield.stdlib.KeyframeScope
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc

/** Compatibility shims for dust documents saved before typed targets moved into Field proper. */
@Deprecated("Typed targets are now KeyframeScope members; remove this import")
fun KeyframeScope.floatTarget(name: String, get: () -> Float, set: (Float) -> Unit) =
    this.floatTarget(name, get, set)

@Deprecated("Typed targets are now KeyframeScope members; remove this import")
fun KeyframeScope.booleanTarget(name: String, get: () -> Boolean, set: (Boolean) -> Unit) =
    this.booleanTarget(name, get, set)

@Deprecated("Typed targets are now KeyframeScope members; remove this import")
fun KeyframeScope.intTarget(
    name: String,
    get: () -> Int,
    range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
    set: (Int) -> Unit
) = this.intTarget(name, get, range, set)

@Deprecated("Typed targets are now KeyframeScope members; remove this import")
fun KeyframeScope.vector3Target(name: String, get: () -> Vector3fc, set: (Vector3f) -> Unit) =
    this.vector3Target(name, get, set)

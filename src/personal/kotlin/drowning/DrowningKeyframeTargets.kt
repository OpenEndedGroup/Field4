package drowning

import afield.stdlib.KeyframeScope
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import kotlin.math.roundToInt

/**
 * Small typed bridges from live-code locals to the ordinary Field keyframer.
 *
 * Shader structures can be registered directly with [KeyframeScope.target], but many useful
 * controls deliberately live one level above a shader: an opacity may also suppress a draw, and an
 * instance count or trail stride changes CPU-authored geometry. Registering the local keeps that
 * single authoritative value behind both paths.
 *
 * Boolean and integer controls are represented as floats. That is intentional: float lanes
 * interpolate, whereas a persisted Boolean is a step and interpolating an Int through the generic
 * numeric path produces a Double. The adapters threshold or round only when applying the live value.
 */
fun KeyframeScope.floatTarget(name: String, get: () -> Float, set: (Float) -> Unit) {
    target(name, get = get, set = { value -> set(value.keyframeFloat(name)) })
}

fun KeyframeScope.booleanTarget(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
    target(name,
        get = { if (get()) 1f else 0f },
        set = { value -> set(value.keyframeFloat(name) >= 0.5f) })
}

fun KeyframeScope.intTarget(
    name: String,
    get: () -> Int,
    range: IntRange = Int.MIN_VALUE..Int.MAX_VALUE,
    set: (Int) -> Unit
) {
    target(name,
        get = { get().toFloat() },
        set = { value -> set(value.keyframeFloat(name).roundToInt().coerceIn(range)) })
}

fun KeyframeScope.vector3Target(name: String, get: () -> Vector3fc, set: (Vector3f) -> Unit) {
    target(name,
        get = { Vector3f(get()) },
        set = { value ->
            val vector = value as? Vector3fc
                ?: error("keyframe '$name' expected Vector3f, got ${value?.let { it::class.simpleName } ?: "null"}")
            set(Vector3f(vector))
        })
}

private fun Any?.keyframeFloat(name: String): Float =
    (this as? Number)?.toFloat()
        ?: error("keyframe '$name' expected a number, got ${this?.let { it::class.simpleName } ?: "null"}")

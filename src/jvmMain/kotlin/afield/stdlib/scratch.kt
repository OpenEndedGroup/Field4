@file:afield.CompletionCategory("Internal")

package afield.stdlib

import afield.Box
import afield.HasProperties
import afield.RunScope
import afield.stdlib.Delegation
import kotlin.reflect.KProperty

val Box.once
    get() = Once(this)

/** Property-delegate namespace bound to one [Box]; normally reached as `box.once`. */
class Once(val inside : Box) {
}

/**
 * Returns a lazy initializer for the generated property named by [property].
 *
 * The box property is read first. When it is `null`, the supplied computation runs and any
 * non-null result is stored back on the box. `null` is therefore the empty sentinel, not a cached
 * value: a computation returning `null` can run again on the next call.
 *
 * The computation runs with the box's parent [afield.RunScope] current
 * ([afield.BoxRunScopes.parent]), not the run's, so what it registers — a slider callback, a render
 * module, an audio patch — belongs to the box rather than to the run that happened to compute it,
 * and the next run, which finds the cached value and does not compute again, does not release it.
 * Stop still releases what is running (an audio patch, a feeder, a process), and retiring the box
 * releases everything; either way, once something the computation registered is released, the
 * cached value is cleared, so the next run computes a fresh one rather than returning a dead one. A
 * value whose computation registered nothing — a shader string, most `prop` values — is never
 * cleared this way. Only registrations made on the calling thread are seen.
 */
@Suppress("UNCHECKED_CAST")
class Onceing<T>() {
    operator fun getValue(f: Once, property: KProperty<*>): ( () -> T) -> T {
        return { compute ->
            val box = f.inside
            var a = box.getPropertyRaw(property.name) as T?
            if (a==null) {
                val (value, registered) = RunScope.withCurrent(box.runScopes.parent) {
                    RunScope.collectingOwned { compute() }
                }
                a = value
                if (a!=null) {
                    box.setPropertyRaw(property.name, a, true)
                    if (registered.isNotEmpty()) {
                        val cached: Any = a
                        val forget = {
                            if (box.getPropertyRaw(property.name) === cached) box.removePropertyRaw(property.name, true)
                        }
                        registered.forEach { it.onRelease(forget) }
                    }
                }
            }
            a as T
        }
    }
}

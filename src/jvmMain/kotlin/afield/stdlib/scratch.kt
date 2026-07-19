@file:afield.CompletionCategory("Internal")

package afield.stdlib

import afield.Box
import afield.HasProperties
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
 */
@Suppress("UNCHECKED_CAST")
class Onceing<T>() {
    operator fun getValue(f: Once, property: KProperty<*>): ( () -> T) -> T {
        return { compute ->
            var a = f.inside.getPropertyRaw(property.name) as T?
            if (a==null) {
                a = compute()
                if (a!=null)
                    f.inside.setPropertyRaw(property.name, a, true)
            }
            a as T
        }
    }
}

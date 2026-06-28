@file:afield.CompletionCategory("Internal")

package afield.stdlib

import afield.Box
import afield.HasProperties
import afield.stdlib.Delegation
import kotlin.reflect.KProperty

val Box.once
    get() = Once(this)

class Once(val inside : Box) {
}

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

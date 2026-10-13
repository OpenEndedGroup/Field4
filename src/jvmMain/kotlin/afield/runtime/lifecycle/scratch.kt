@file:afield.runtime.completion.CompletionCategory("Internal")

package afield.runtime.lifecycle

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.properties.prop
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.lang.stacksl.evolution.render
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.audio.synth.*
import afield.graphics.shader.compute
import afield.math.linalg.f
import afield.document.graph.Box
import afield.document.graph.HasProperties
import afield.runtime.lifecycle.RunScope
import afield.document.properties.Delegation
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
 * The computation runs with the box's parent [afield.runtime.lifecycle.RunScope] current
 * ([afield.runtime.lifecycle.BoxRunScopes.parent]), not the run's, so what it registers — a slider callback, a render
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

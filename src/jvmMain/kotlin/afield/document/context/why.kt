@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.document.context

import afield.animation.keyframes.h
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.Inheritance
import afield.document.properties.compareTo
import afield.document.properties.prop
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.lang.stacksl.h
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
import afield.runtime.lifecycle.once
import afield.ui.output.h

import afield.audio.synth.*
import afield.math.linalg.f
import afield.document.graph.Box
import afield.document.context.ContextResolution
import afield.document.context.WhyAnswer
import kotlin.reflect.KProperty

/**
 * `why.speed`: where this box's `speed` comes from, as [ContextResolution.explain] answers it.
 *
 * `why` is a namespace bound to the box, the way [once] is. `prop<Double>(speed)` writes
 * `val Why.speed by Whying()` into the preamble beside `var Box.speed by Inheritance<Double>()`, so
 * every `prop`-declared name is a member here, completes, and is inherited by children with the
 * accessor itself. Anything else (a parent's plain `val`, a property set from outside, a value found
 * by type) is asked directly: `ContextResolution.explain(thisBox, "speed").h`, or
 * `ContextResolution.explain(thisBox, type = "Renderer").h`.
 */
val Box.why: Why
    get() = Why(this)

/** The namespace behind [Box.why]; its members are the `val Why.<name> by Whying()` lines `prop` writes. */
class Why(val inside: Box)

/** The delegate `prop` generates for [Why]: the property's own name is the question. */
class Whying {
    operator fun getValue(f: Why, property: KProperty<*>): WhyAnswer = ContextResolution.explain(f.inside, name = property.name)
}

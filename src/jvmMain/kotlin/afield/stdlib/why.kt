@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.ContextResolution
import afield.WhyAnswer
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

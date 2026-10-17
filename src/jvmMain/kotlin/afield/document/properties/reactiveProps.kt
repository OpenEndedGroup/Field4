@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.document.properties

import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.context.Why
import afield.document.context.Whying
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.workspace.file
import afield.geometry.mesh.expanded
import afield.graphics.render.rangeTo
import afield.graphics.shader.callable
import afield.graphics.shader.miss
import afield.graphics.vulkan.buffers.add
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
import afield.runtime.lifecycle.Once
import afield.runtime.lifecycle.Onceing
import afield.runtime.lifecycle.once

import afield.audio.synth.*
import afield.data.collections.get
import afield.math.linalg.f
import afield.document.graph.Box
import afield.document.context.ContextResolution
import afield.document.graph.HasHierarchy
import afield.document.properties.PropertyDependencies
import afield.runtime.lifecycle.ReactiveRuns
import afield.runtime.macros.Insert
import afield.runtime.macros.MacroRegistry
import kotlin.reflect.KProperty

/**
 * `propUp`, `propDown` and `propUpDown`: [prop] with re-execution attached.
 *
 * All three declare the same typed, inherited slot `prop` does (a `var Box.<name>` in the preamble,
 * copied down into children), and read and write it the same way. What they add:
 *
 * - **Up** — a read that finds no value anywhere up the graph runs the box that supplies it, then
 *   reads again. The supplier is the box that declared the property (or supplied it before), nearest
 *   first, and the run happens *inside* the read, so a chain of them unwinds in order.
 * - **Down** — a write that changes the value reruns, after the writing box's run has finished,
 *   every box that read the old value, in dependency order.
 *
 * Declare it where the value is written (Down is decided by the writer's declaration, Up by the
 * reader's), and the declaration reaches the children as `prop`'s does. The scheduling is
 * `afield.runtime.lifecycle.ReactiveRuns`; what it did is in `ReactiveRuns.recentEvents()`.
 */

/** Which re-executions a [ReactiveInheritance] property triggers. */
enum class Reactive(val up: Boolean, val down: Boolean) {
    /** A missing value runs its supplier. */
    UP(true, false),

    /** A changed value reruns its readers. */
    DOWN(false, true),

    /** Both. */
    UP_DOWN(true, true),
}

/**
 * The delegate behind `propUp` / `propDown` / `propUpDown`: [Inheritance] (the same walk, the same
 * local write), plus a pull on a missing read and a change notice on a changing write, per [mode].
 */
class ReactiveInheritance<T>(val mode: Reactive, val defaultValue: T? = null) {

    /**
     * The value from this box or the nearest ancestor. Under [Reactive.up], a miss first runs the
     * supplier and reads again.
     *
     * @throws MissingInheritedPropertyException when there is still no value, no [defaultValue] and
     * the type is not nullable; the message says which suppliers ran and why that did not help.
     */
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val name = property.name
        val (found, num) = inheritedPropertyWalk(f, name)
        if (found != null) return found as T
        var detail = ""
        if (mode.up) {
            val reader = Box.context.get() ?: f
            val outcome = ReactiveRuns.pull(reader, f, name) {
                ContextResolution.peek(
                    f,
                    ContextResolution.Query.propertyNamed(name),
                    ContextResolution.Policy.INHERITANCE,
                    ContextResolution.Mechanism.INHERITANCE,
                ) != null
            }
            if (outcome.satisfied) {
                val (again, _) = inheritedPropertyWalk(f, name)
                if (again != null) return again as T
            }
            detail = outcome.describe(name)
        }
        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(missingInheritedPropertyMessage(f, name, num) + detail)
        }
        return null as T
    }

    /**
     * Writes the value **locally**, as [Inheritance] does. Under [Reactive.down], a value that differs
     * from the one there (by `equals`) reruns its readers once the writing box's run has finished.
     * Assigning null erases the property.
     */
    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) {
        val name = property.name
        val old = f.getPropertyRaw(name)
        if (v == null) f.removePropertyRaw(name) else f.setPropertyRaw(name, v as Any)
        PropertyDependencies.recordWrite(f, name, ContextResolution.Mechanism.INHERITANCE)
        // Box.setPropertyRaw already noted a write to a name the box's code declares Down.
        if (mode.down && old != v && f is Box && (v == null || !ReactiveRuns.isDeclaredDown(f, name))) ReactiveRuns.noteChange(f, name)
    }
}

/**
 * [prop], and a read that finds nothing runs the box that supplies the value first:
 * `propUp<Double>(speed)`.
 *
 * Put it in the box that sets `speed`; a child reading `speed` before that box has run (on opening
 * the document, say) runs it, waits, and carries on. A macro placeholder, like [prop].
 */
fun <T> Box.propUp(name: String) {
    throw MacroRegistry.MacroNotExpandedException("propUp should not be callable directly, but should be expanded as a macro")
}

/**
 * [prop], and changing the value reruns the boxes that read it: `propDown<Double>(speed)`.
 *
 * Put it in the box that sets `speed`. When a run of this box (or a slider callback) changes it, every
 * box that read the old value reruns, in order, once this box's run has finished. A macro placeholder.
 */
fun <T> Box.propDown(name: String) {
    throw MacroRegistry.MacroNotExpandedException("propDown should not be callable directly, but should be expanded as a macro")
}

/** [propUp] and [propDown] together: `propUpDown<Double>(speed)`. A macro placeholder. */
fun <T> Box.propUpDown(name: String) {
    throw MacroRegistry.MacroNotExpandedException("propUpDown should not be callable directly, but should be expanded as a macro")
}

/** Macro expansion for `propUp` / `propDown` / `propUpDown`: [macro_prop]'s declarations, with [ReactiveInheritance]. */
fun Box.macro_reactive_prop(contents: List<String>, mode: Reactive, macroName: String): Insert? {
    if (contents.size != 2)
        throw MacroRegistry.MacroExpansionProblem("$macroName should be called like $macroName<Int>(foo)")

    val (name, default) = propNameAndDefault(contents[1])
    if (mode.down) ReactiveRuns.declaredDown(this, name)
    return Insert(
        here = "$name", standaloneHere = "Unit",
        preamble = propTypeImports(contents[0]), preambleAfterImports = """
        var Box.${name} by ReactiveInheritance${contents[0]}(Reactive.${mode.name}${default?.let { ", $it" } ?: ""}) /**/
        val Once.${name} by Onceing${contents[0]}() /**/
        val Why.${name} by Whying() /**/
    """.trimIndent()
    )
}

/** [rootProp] with [propUp]'s pull: `rootPropUp<Double>(speed)`, read and written as `root.speed`. A macro placeholder. */
fun <T> Box.rootPropUp(name: String) {
    throw MacroRegistry.MacroNotExpandedException("rootPropUp should not be callable directly, but should be expanded as a macro")
}

/** [rootProp] with [propDown]'s reruns: changing `root.speed` reruns the boxes that read it. A macro placeholder. */
fun <T> Box.rootPropDown(name: String) {
    throw MacroRegistry.MacroNotExpandedException("rootPropDown should not be callable directly, but should be expanded as a macro")
}

/** [rootPropUp] and [rootPropDown] together. A macro placeholder. */
fun <T> Box.rootPropUpDown(name: String) {
    throw MacroRegistry.MacroNotExpandedException("rootPropUpDown should not be callable directly, but should be expanded as a macro")
}

/**
 * Macro expansion for `rootPropUp` / `rootPropDown` / `rootPropUpDown`: [macro_reactive_prop]'s
 * declarations on the document root, as [macro_root_prop] does for `rootProp`, with the call site
 * becoming `root.<name>`. The declaring box is recorded, so the root's slot reruns its readers (Down)
 * and a missing value runs the declaring box (Up) while that box's code still says so.
 */
fun Box.macro_reactive_root_prop(contents: List<String>, mode: Reactive, macroName: String): Insert? {
    if (contents.size != 2)
        throw MacroRegistry.MacroExpansionProblem("$macroName should be called like $macroName<Int>(foo)")

    val (name, default) = propNameAndDefault(contents[1])
    if (mode.down) ReactiveRuns.declaredDownOnRoot(this, this.root, name)
    return Insert(
        here = "root.$name", standaloneHere = "Unit",
        preamble = propTypeImports(contents[0]), preambleAfterImports = """
        var Box.${name} by ReactiveInheritance${contents[0]}(Reactive.${mode.name}${default?.let { ", $it" } ?: ""}) /**/
        val Once.${name} by Onceing${contents[0]}() /**/
        val Why.${name} by Whying() /**/
    """.trimIndent(), target = this.root
    )
}

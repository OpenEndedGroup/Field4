@file:afield.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.stdlib

import afield.Box
import afield.ContextResolution
import afield.HasHierarchy
import afield.PropertyDependencies
import afield.ReactiveRuns
import afield.macros.Insert
import afield.macros.MacroRegistry
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
 * `afield.ReactiveRuns`; what it did is in `ReactiveRuns.recentEvents()`.
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
        if (mode.down && old != v && f is Box) ReactiveRuns.noteChange(f, name)
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

    val name = contents[1].trim().removeSurrounding("(", ")")
    if (mode.down) ReactiveRuns.declaredDown(this, name)
    return Insert(
        here = "$name", standaloneHere = "Unit",
        preamble = propTypeImports(contents[0]), preambleAfterImports = """
        var Box.${name} by ReactiveInheritance${contents[0]}(Reactive.${mode.name}) /**/
        val Once.${name} by Onceing${contents[0]}() /**/
        val Why.${name} by Whying() /**/
    """.trimIndent()
    )
}

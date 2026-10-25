@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.document.properties

import afield.data.collections.alphas
import afield.data.collections.get
import afield.data.collections.m
import afield.data.collections.r
import afield.data.collections.s
import afield.data.collections.times
import afield.geometry.mesh.topology
import afield.graphics.render.rangeTo
import afield.graphics.vulkan.buffers.add
import afield.lang.stacksl.h
import afield.math.autodiff.clamp
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.channel
import afield.media.image.open

import afield.data.collections.invoke
import afield.math.linalg.f
import afield.math.linalg.invoke
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f
import afield.math.linalg.Matrix4f
import afield.math.linalg.Matrix3f
import afield.math.linalg.Quaternionf
import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.server.Server.Companion.stop
import afield.graphics.vulkan.util.FramePresentationLog
import afield.graphics.vulkan.util.Options
import afield.graphics.vulkan.util.floatToHalfBits
import kotlinx.coroutines.DelicateCoroutinesApi
import afield.math.linalg.*
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.reflect.KProperty

/**
 * The general-purpose helpers that every Field box already has in scope.
 *
 * Box code is compiled with the standard imports in `intro` in its preamble and with `Box` as an
 * implicit receiver, so everything here — including the `Box.` extensions — can be called
 * unqualified from inside a box.
 *
 * The substantial part is the property-delegate machinery that `prop` and `@exported` generate
 * against: [Delegation] (stores on the receiver's own property bag), [Inheritance] (walks up
 * `parents` until some ancestor has the property, otherwise throws
 * [MissingInheritedPropertyException] with a message naming the property and the box), and
 * [InheritanceFromContext] (same walk, but prefers each box's live Kotlin variables over raw
 * properties).
 *
 * The rest is small conveniences: graph access (`named`, `+=` / `-=` for
 * connect/disconnect, `__m` markers); execution control (`begin`, `end`, [start], [animate],
 * `aside`, `cell`); frame-presentation logging ([frameLogStart], [frameLogStop],
 * [frameLogMark], [frameLogLine]); matrix and buffer building ([mat4], [mat3], `FloatBuffer.add`,
 * `IntBuffer.add`, `ByteBuffer.putf16`); and terse numeric/collection/string shorthands
 * (`Number.f` / `.i` / `.l` / `.d`, [fa], [ia], `Int.alphas`, `String.times`, `List.m` / `.s`,
 * `String.r`, and [highlightSyntax] behind `String.h` / `String.ss`).
 */

/**
 * The property delegate behind `prop` and `@exported`: reads and writes go to the receiver's own
 * property bag under the property's name, not to a Kotlin field.
 *
 * That is what makes such a property *persistent and visible* — it survives the box being re-executed,
 * shows up in the property inspector, and can be read by name from elsewhere. `var x by Delegation()`
 * on a `HasProperties` is the whole usage.
 *
 * The three optional hooks are what the generated code uses to add behaviour: [defaultValue] supplies
 * (and, if non-null, **stores**) a value on first read of an unset property, [onGet] observes each read,
 * and [hook] gets to inspect and rewrite each incoming value before it lands — receiving the current
 * value as well as the new one, so it can clamp, coerce or reject.
 *
 * Unchecked casts throughout: this trusts that the bag holds what the property's type says it does, so
 * a name collision between two differently-typed properties surfaces as a `ClassCastException` at the
 * read, not at the write.
 */
class Delegation<T>(
    var onGet: ((HasProperties, currentValue: T) -> Unit)? = null,
    var hook: ((inside: HasProperties, currentValue: T, incomingValue: T) -> T)? = null,
    val defaultValue: (() -> T)? = null,
) {
    /**
     * Reads the property from [f]'s bag, filling in [defaultValue] on the first read if it is unset.
     *
     * A non-null default is *written back* as a side effect, so the value the box sees is the value
     * anything else reading that property by name will see too. A default that itself returns null is
     * handed straight back without being stored.
     *
     * That guarantee is why the default is **re-read from the bag** after being written rather than
     * handed back directly: [HasProperties.setPropertyRaw] is allowed to normalise what it stores, and
     * for the topology sets (`children` / `parents` / `nested`) it does — `Boxes.asTopologySet` wraps a
     * plain `Set` in a `CopyOnWriteArraySet`, so the stored object is *not* the one passed in. Returning
     * `def` handed the caller a throwaway collection whose mutations went nowhere: `nestInto`'s
     * `parent.nested.add(this)` on a never-yet-read `nested` was silently lost, leaving `nestedIn` set
     * and the parent's `nested` empty. Re-reading costs one map lookup and makes the paragraph above
     * true for normalising properties as well as plain ones.
     */
    operator fun getValue(f: HasProperties, property: KProperty<*>): T {

        var a = f.getPropertyRaw(property.name)
        if (a == null && defaultValue != null) {
            val def = defaultValue()
            if (def != null) {
                f.setPropertyRaw(property.name, def as Any)
                a = f.getPropertyRaw(property.name) ?: def
            } else {
                return def as T
            }
        }

        val v = (a as T)
        onGet?.invoke(f, v)
        return v
    }

    /**
     * Writes the property into [f]'s bag, after letting [hook] rewrite the incoming value.
     *
     * The hook sees the current value (or the default, when there is none yet) alongside the new one and
     * returns what actually gets stored, so it is the place clamping and coercion live.
     *
     * Assigning null to a `Delegation<T?>` **erases** the property rather than storing a null, because
     * [HasProperties.setPropertyRaw] only takes a non-null value. Erased and never-set therefore read
     * back identically — which is what the nullable delegates in this tree already assume, none of them
     * having a non-null [defaultValue] that an erase could resurrect. The hook still runs first, so a
     * null write reaches reverse-link bookkeeping like `nestedIn`'s.
     */
    operator fun setValue(f: HasProperties, property: KProperty<*>, v: T) {
        var _v = v
        hook?.let {
            var a = f.getPropertyRaw(property.name) as T?
            if (a == null) a = defaultValue?.invoke() as T
            _v = it(f, a as T, _v)
        }
        val value = _v
        if (value == null) f.removePropertyRaw(property.name)
        else f.setPropertyRaw(property.name, value)
    }
}

/**
 * Thrown when an inherited (`prop`-style) property has no value anywhere up the box graph and no default.
 *
 * Almost always a *timing* problem rather than a missing property: the parent box that exports the value
 * has not been executed yet, or threw while executing. The message names the property and the box, and
 * says how many parents were searched, so it can be read on its own.
 */
class MissingInheritedPropertyException(message: String) : IllegalStateException(message)

private fun Box.isMetarootBox(): Boolean = id == METAROOT_ID

internal fun missingInheritedPropertyMessage(box: Box, propertyName: String, parentCount: Int): String {
    val boxName = box.name.ifBlank { box.id }
    if (box.isMetarootBox()) {
        return "Can't find a value for inherited property '$propertyName' in metaroot. " +
            "Metaroot is shared by open tabs/documents and has no parent boxes; this usually means the box in another open tab/document that exports '$propertyName' hasn't executed yet, or failed while executing."
    }
    val where = when (parentCount) {
        0 -> "in '$boxName', which has no boxes above it but the document root"
        1 -> "in '$boxName' or the 1 box above it"
        else -> "in '$boxName' or any of the $parentCount boxes above it"
    }
    return "Can't find a value for inherited property '$propertyName' $where (the document root was searched too). " +
        "Are you sure the parent boxes that define '$propertyName' have been executed?"
}

private fun missingContextualInheritedPropertyMessage(box: Box, propertyName: String, candidates: List<String>): String {
    val boxName = box.name.ifBlank { box.id }
    val candidateList = candidates.joinToString("', '", prefix = "'", postfix = "'")
    if (box.isMetarootBox()) {
        return "Can't find a value for contextual inherited property '$propertyName' in metaroot. " +
            "Looked for $candidateList in metaroot properties and live Kotlin variables. " +
            "Metaroot is shared by open tabs/documents and has no parent boxes; this usually means the box in another open tab/document that exports '$propertyName' hasn't executed yet, or failed while executing."
    }
    return "Can't find a value for contextual inherited property '$propertyName' in '$boxName' or any of its parent boxes. " +
        "Looked for $candidateList in box properties and live Kotlin variables. " +
        "Are you sure the parent boxes that define '$propertyName' have been executed?"
}

/**
 * The lookup [Inheritance] performs, without a `KProperty` to perform it through: this box's own raw
 * property, then breadth-first up `parents` until some ancestor has one. `null` means nothing up the
 * graph has it.
 *
 * Extracted so a *second* reader of the same slot cannot drift from the first. FieldML is that second
 * reader — an interpreter has no generated `var Box.<name> by Inheritance<T>()` to read through, so
 * `prop` in a FieldML box calls this, and the two agree by construction rather than by comment. Any
 * third reader (a UI inspector, an agent tool) should do the same — or call `afield.document.context.ContextResolution`
 * directly, which is the walk this is, and which can also say *where* the value came from.
 *
 * Note this has **no** `defaultValue` and throws nothing: the delegate's failure
 * behaviour depends on the declared property's nullability, which only the delegate can see.
 */
fun inheritedPropertyRaw(from: Box, name: String): Any? = inheritedPropertyWalk(from, name).first

/**
 * [inheritedPropertyRaw] plus the number of *ancestor boxes examined* (not counting the document
 * root and metaroot, which are above every box), which is the only other thing
 * [Inheritance] needs — [missingInheritedPropertyMessage] reports it so a missing property reads as
 * the timing problem it usually is.
 */
internal fun inheritedPropertyWalk(from: Box, name: String): Pair<Any?, Int> {
    val search = ContextResolution.search(
        from,
        ContextResolution.Query.propertyNamed(name),
        ContextResolution.Policy.INHERITANCE,
        ContextResolution.Mechanism.INHERITANCE,
    )
    return search.resolution?.value to search.boxAncestorsExamined
}

/**
 * The property delegate that makes a value *inherited*: reading it looks in this box, then breadth-first
 * up through `parents` until some ancestor has it.
 *
 * This is how a child box picks up a parent's settings without being told about them — the pattern behind
 * `prop`. Writing always writes **locally**, shadowing the ancestor's value for this box and everything
 * below it rather than changing theirs.
 *
 * Breadth-first, so with two ancestors defining the same name the nearer one wins; equally distant ones
 * resolve in `parents` order (edge insertion order), and a tie between two different values is reported
 * once on the `warning,context` log channel. When nothing has it: [defaultValue] if you gave one, else null for a nullable
 * property, else [MissingInheritedPropertyException].
 *
 * The walk itself is [inheritedPropertyWalk], shared with FieldML's `prop`.
 */
class Inheritance<T>(val defaultValue: T? = null) {
    /**
     * The value from this box or the nearest ancestor that has it.
     *
     * @throws MissingInheritedPropertyException when nothing up the graph has the property, there is no
     * [defaultValue], and the property's type is not nullable — usually meaning the exporting box has not
     * run yet.
     */
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val (found, num) = inheritedPropertyWalk(f, property.name)
        if (found != null) return found as T
        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(missingInheritedPropertyMessage(f, property.name, num))
        }
        return null as T
    }

    /**
     * Writes the value **locally**, shadowing any ancestor's value rather than modifying it. A write
     * into a box other than the executing one (`root.speed = 3.0` under `rootProp`) is recorded in
     * [PropertyDependencies], which is how a later reader's producer is known.
     */
    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) {
        f.setPropertyRaw(property.name, v as Any)
        PropertyDependencies.recordWrite(f, property.name, ContextResolution.Mechanism.INHERITANCE)
    }
}

/**
 * [Inheritance], but it can also see the *live Kotlin variables* of the boxes it walks — so a plain
 * `val speed = 3.0` in a parent box is inheritable, with no property or annotation needed.
 *
 * The lookup order per box is: live Kotlin variables first, then raw properties. A leading underscore is
 * optional, so `_speed` also matches a variable called `speed`, which is how the generated names stay out
 * of the way of the names a person types.
 *
 * The reading box's **own** variables count only if they are `@exported` values (its own export, or
 * one exported into it by `@rootExported`). Since 2026-09-29 an ordinary local cannot answer: before,
 * `val speed = _speed` resolved from the parent once and then found its own leftover `speed` on every
 * later run — the self-pinning `fromContext` was cured of in 2026-08. Its own properties still count,
 * so `_speed = 4.0` (which writes one) still shadows locally. Ties between equidistant ancestors are
 * as [Inheritance]'s. The walk is `afield.document.context.ContextResolution`.
 *
 * Reading the parent's live variable means you get whatever it holds *now*, including the value from a
 * half-finished edit-and-rerun. Failure behaviour is as [Inheritance], and the exception message lists
 * every name that was tried.
 */
class InheritanceFromContext<T>(val defaultValue: T? = null) {
    /**
     * The value from this box or the nearest ancestor, preferring live Kotlin variables over properties.
     *
     * @throws MissingInheritedPropertyException when neither a variable nor a property of any candidate
     * name exists up the graph, there is no [defaultValue], and the type is not nullable.
     */
    operator fun getValue(f: Box, property: KProperty<*>): T {
        val query = ContextResolution.Query.inheritedFromContext(property.name)
        val candidateNames = query.names.orEmpty()
        val found = ContextResolution.walk(
            f,
            query,
            ContextResolution.Policy.INHERITANCE_FROM_CONTEXT,
            ContextResolution.Mechanism.INHERITANCE_FROM_CONTEXT,
        )
        if (found != null) return found.value as T

        if (defaultValue != null) return defaultValue as T
        if (!property.returnType.isMarkedNullable) {
            throw MissingInheritedPropertyException(
                missingContextualInheritedPropertyMessage(f, property.name, candidateNames)
            )
        }
        return null as T
    }

    /**
     * Writes the value **locally** as a raw property, shadowing whatever an ancestor had; a write into
     * another box is recorded as [Inheritance.setValue]'s is.
     */
    operator fun setValue(f: HasHierarchy, property: KProperty<*>, v: T) {
        f.setPropertyRaw(property.name, v as Any)
        PropertyDependencies.recordWrite(f, property.name, ContextResolution.Mechanism.INHERITANCE_FROM_CONTEXT)
    }
}


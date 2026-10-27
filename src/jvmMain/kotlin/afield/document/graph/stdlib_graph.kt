@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.document.graph

import afield.data.collections.alphas
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.r
import afield.data.collections.times
import afield.graphics.render.rangeTo
import afield.graphics.vulkan.buffers.add
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times

import afield.data.collections.isTrue
import afield.data.collections.m
import afield.data.collections.s
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
 * Finding boxes by name, connecting them, and hanging scratch state off one.
 *
 * `named` is how one box gets hold of another, searching the whole document from the root and
 * skipping the tombstoned boxes a delete leaves behind. `parent += child` and `parent -= child`
 * connect and disconnect, and connection is the edge property inheritance walks. [Marker] is a
 * named property bag attached to a box, obtained with `__m` and created on first use, for state
 * that belongs to part of a box rather than to the box itself; it lives in memory only and is not
 * saved with the document.
 *
 * One of the `stdlib` files, which the `intro` preamble star-imports into every box, so everything
 * here can be called unqualified, with `Box` as the implicit receiver.
 */

/**
 * The first box in this set called [s]. **Throws** `NoSuchElementException` when there is none.
 *
 * Deleted boxes are skipped by default — a deleted box lingers in the graph carrying a `_tombstone`
 * property rather than vanishing, and without this filter you can find one and wonder why your edits go
 * nowhere. Pass `ignoreDeleted = false` to include them.
 */
fun Set<Box>.named(s: String, ignoreDeleted: Boolean = true) =
    this.first { it.name == s && (!ignoreDeleted || !it.getPropertyRaw("_tombstone").isTrue()) }

/**
 * The box called [s] in this document — the usual way for one box to get hold of another.
 *
 * Searches `root.children`, so it finds any box in the same document regardless of where the call is
 * made from, and it is the document, not this box's own children. Names are not unique, so with two
 * matches you get an arbitrary one; **throws** `NoSuchElementException` with none. Deleted
 * (`_tombstone`) boxes are skipped unless you ask for them.
 */
fun Box.named(s: String, ignoreDeleted: Boolean = true) =
    this.root.children.first { it.name == s && (!ignoreDeleted || !it.getPropertyRaw("_tombstone").isTrue()) }

/**
 * `parent += child` — connects [child] to this box, making it a child in the box graph.
 *
 * Connection is what property inheritance walks, so this is how a box comes to see its parent's exported
 * values. The graph is a DAG, not a tree: a box can be connected to several parents, and `+=` adds rather
 * than moves.
 */
operator fun Box.plusAssign(child: Box) {
    this.connect(child)
}

/** `parent -= child` — breaks the connection to [child], without deleting either box. */
operator fun Box.minusAssign(child: Box) {
    this.disconnect(child)
}

/**
 * A named property bag attached to a box — somewhere to hang state that belongs to *part* of a box rather than to
 * the box itself.
 *
 * Obtained by name with [Box.__m], which creates it on first use and returns the same instance thereafter, so it
 * survives across frames while the box is running. Its properties live in a plain in-memory map: they are **not**
 * saved with the document and are not visible to the property inspector, unlike a real box property.
 *
 * It presents itself as a leaf of the box graph — no children, and its single parent is the owning box — so
 * inheritance-shaped code can walk into it without special-casing.
 */
data class Marker(val name: String, val box: Box) : HasProperties, HasHierarchy {
    /** This marker's state, as a plain map. In memory only; nothing here is persisted with the document. */
    val properties = mutableMapOf<String, Any>()

    /** Stores a value under [name]. The `doResync` flag is ignored — a marker has no client to notify. */
    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        properties[name] = value
    }

    /** The value stored under [name], or [default] if this marker has never been given one. */
    override fun getPropertyRaw(name: String, default: Any?): Any? {
        return properties[name] ?: default
    }

    /** Erases [name]. The `doResync` flag is ignored — a marker has no client to notify. */
    override fun removePropertyRaw(name: String, doResync: Boolean) {
        properties.remove(name)
    }

    /** Always empty: a marker is a leaf. Assigning to it does nothing. */
    override var children: MutableSet<Box>
        get() = mutableSetOf<Box>()
        set(value) {}

    /** Just the owning box, so a parent walk reaches it. Assigning to it does nothing. */
    override var parents: MutableSet<Box>
        get() = mutableSetOf(box)
        set(value) {}
}

/**
 * The [Marker] called [s] on this box, created on first use — the get-or-create for per-box scratch state.
 *
 * `__m("trail") { properties["points"] = mutableListOf<Vec3>() }`: [onCreation] runs **only** the first time,
 * which is what makes this safe to call every frame from inside an animation loop. The marker and its contents
 * outlive an individual frame but not the process, and are not saved with the document.
 */
fun Box.__m(s: String, onCreation: Marker.() -> Unit = {}) = this.markers.computeIfAbsent(s) {
    val m = Marker(s, box = this)
    m.onCreation()
    m
}


@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.runtime.execution

import afield.data.collections.alphas
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.r
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.fragment
import afield.graphics.render.label
import afield.graphics.render.rangeTo
import afield.graphics.shader.callable
import afield.graphics.shader.fragment
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

import afield.document.properties.Delegation
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
 * Starting and stopping box code from inside box code, and the two block markers the editor reads.
 *
 * `begin` runs another box's whole `code` property as if its run key had been pressed, on a named
 * strand, so a second launch on the same strand replaces the first; `end` stops every strand that
 * box is running and disposes any Field 4 renderer it owns. [start] and [animate] open a [Frames]
 * scope, the second re-entering its body every frame. `aside` holds a block an ordinary run skips
 * and option-return executes, which is where one-off experiments and debug dumps can live instead
 * of in a comment; `cell` marks a block as one notebook-style cell and at runtime only calls it.
 *
 * One of the `stdlib` files, which the `intro` preamble star-imports into every box, so everything
 * here can be called unqualified, with `Box` as the implicit receiver.
 */

/**
 * Runs this box's whole `code` property, as if someone had pressed the run key on it.
 *
 * The programmatic run: one box can start another, which is how a "conductor" box drives a set of others.
 * It launches and returns immediately rather than waiting, and the run's output goes to *that* box's output
 * area, not the caller's.
 *
 * [strand] names the execution lane the code goes into. Two launches on the same strand replace each other,
 * which is exactly how re-running a box supersedes its previous animation; distinct strand names let a box
 * run several things at once. [end] stops whatever is running.
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.begin(strand: String = "default") {
    val box = this
    val source = box.getPropertyRaw("code") as? String ?: ""
    val execute = Execute(box.id, "launch", 1, source, false, strand = strand)

    FlightRecorder.recordLaunchEntrypoint("stdlib.begin", box, execute, strand = strand)
    box.ke.launchAll(box, execute, source, strand = strand, {
//        send(it)
    })
}

/**
 * Stops everything this box is running — every strand, not just the default one — and disposes any
 * Field 4 renderer it owns, closing that window.
 *
 * The stop key, callable from code. Cancellation is not graceful: running coroutines are cancelled where they
 * stand, so anything that must be cleaned up belongs in a `finally` inside the box, not after the call to
 * this.
 */
fun Box.end() {
    stop(this, entrypoint = "stdlib.end")
}

/**
 * Starts a new [Frames] scope and runs [a] inside it.
 *
 * This is a small convenience wrapper around `frames { ... }` that returns the
 * created [Frames] instance.
 */
fun start(a: suspend Frames.() -> Unit): Frames {
    return frames {
        a()
    }
}

/**
 * Thin wrapper around [frames] that starts a frame-based animation from a plain
 * suspend lambda (no [Frames] receiver). The body is responsible for advancing
 * frames if it needs multi-frame behavior.
 */
fun start(a: suspend () -> Unit): Frames {
    return frames {
        a()
    }
}

/**
 * Creates an animation loop from a lambda.
 * The lambda will be executed every frame when this function is launched.
 *
 * Usage:
 * ```
 * foo()
 * bar(10.0)
 *
 * animate {
 *     gux()
 * }
 * ```
 * This calls foo() and bar() once, then calls gux() every frame.
 */
fun animate(block: suspend Frames.() -> Unit): Frames {
    return eachFrame(block)
}

// matrix help

/**
 * True while this box is running a *fragment* — a selected region or an `alt-return` block — rather than its
 * whole body.
 *
 * Set by the execution machinery around the run, and the flag [aside] tests. Read it if you want code that
 * behaves differently when someone is poking at part of the box; false during a normal full run.
 */
var Box._currentlyExecutingFragment by Delegation<Boolean>(defaultValue = { false })

/**
 * A block that runs **only** when you explicitly ask for it — put the cursor inside and press
 * option/alt-return — and is skipped by an ordinary run of the box.
 *
 * The place to keep the one-off experiments, the expensive setup and the debug dumps that would otherwise have
 * to live commented out. [name] is a label for the editor's benefit and does not affect execution.
 */
@Doc("used to mark a particular block in the editor that only executes when the cursor is inside the block and you press option/alt-return")
inline fun Box.aside(name: String = "", block: () -> Unit) {
    if (this._currentlyExecutingFragment) block()
}

/**
 * Marks a block as one notebook-style cell, so the editor can treat it as a unit for auto-execution and
 * re-running.
 *
 * At runtime it is nothing at all — it calls the block immediately and returns its value, so wrapping code in
 * `cell { }` changes only what the editor does with it, never what it computes.
 */
@Doc("used to mark a particular block in the editor, this helps with grouping auto-execution")
inline fun <T> Box.cell(block: () -> T) = block()


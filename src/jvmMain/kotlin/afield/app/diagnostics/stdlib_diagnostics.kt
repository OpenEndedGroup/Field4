@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.app.diagnostics

import afield.data.collections.alphas
import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.r
import afield.data.collections.s
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
import afield.study.ui.numbers

import afield.document.workspace.path
import afield.runtime.execution.start
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
 * The frame-presentation log: per-frame graphics timing, written to a file while it runs.
 *
 * [frameLogStart] begins recording and returns the path, choosing one if you do not give it one,
 * and the log keeps growing until [frameLogStop] closes it. [frameLogMark] drops a named marker
 * tied to a frame boundary, so an event can be lined up against the frame that was slow, and
 * [frameLogLine] appends free text with no frame attached. The `waitAfterPresent` argument changes
 * what is measured rather than how it is reported: each frame waits for the present to complete,
 * which gives true per-frame numbers at some cost to throughput.
 *
 * One of the `stdlib` files, which the `intro` preamble star-imports into every box, so everything
 * here can be called unqualified, with `Box` as the implicit receiver.
 */

/**
 * Starts recording the frame-presentation log — per-frame timing for the graphics pipeline — and returns the
 * path it is being written to.
 *
 * The first thing to reach for when frames are janky and you want to know where the time went. With no
 * [path] a location is chosen for you, and [append] decides whether an existing log is added to or replaced.
 *
 * [waitAfterPresent] changes what is measured, not just how it is reported: it makes each frame wait for the
 * present to complete, which gives true per-frame numbers at some cost to throughput. It defaults to the
 * process-wide option, so leave it alone unless you are chasing a presentation stall specifically.
 *
 * Call [frameLogStop] when done; the log keeps growing until you do.
 */
fun frameLogStart(
    path: String? = null,
    waitAfterPresent: Boolean = Options.framePresentationLogWaitAfterPresent,
    append: Boolean = false
): String = FramePresentationLog.start(path, waitAfterPresent, append)

/** Stops frame-presentation logging and closes the file. Harmless if nothing was being logged. */
fun frameLogStop() {
    FramePresentationLog.stop()
}

/**
 * Drops a named marker into the frame log at the current frame — "this is where I clicked the thing".
 *
 * The point of a marker over a plain line is that it is tied to a frame boundary, so you can line an event up
 * against the frame that was slow.
 */
fun frameLogMark(message: String) {
    FramePresentationLog.mark(message)
}

/** Appends a free-text line to the frame log, with no frame association. See [frameLogMark] for events. */
fun frameLogLine(message: String) {
    FramePresentationLog.addLine(message)
}



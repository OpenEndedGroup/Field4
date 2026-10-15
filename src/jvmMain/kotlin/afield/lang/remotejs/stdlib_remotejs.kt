@file:afield.runtime.completion.CompletionCategory("Workspace")
@file:Suppress("UNCHECKED_CAST")

package afield.lang.remotejs

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
import afield.media.image.channel

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
 * Evaluates [code] as JavaScript in every browser session attached to this workspace, in the context of
 * this box.
 *
 * Fire-and-forget across all connected clients — nothing comes back, and there is no error channel, so a
 * mistake in [code] fails silently in the browser console. Use it to poke at the box's own DOM or to
 * drive something on the page that Kotlin has no handle on.
 */
fun Box.inBrowser(code: String) {
    inside.sessions.forEach {
        _send(it, Message("local-eval").payload(LocalEval(uid = id, prompt = code, kind = "eval")))
    }
}


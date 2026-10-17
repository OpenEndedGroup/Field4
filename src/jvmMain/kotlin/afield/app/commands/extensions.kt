@file:afield.runtime.completion.CompletionCategory("Internal")

package afield.app.commands

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.graphics.render.rangeTo
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
import afield.document.properties.Delegation
import afield.graphics.vulkan.buffers.add
import afield.document.graph.Box
import afield.document.graph.needsResync

internal var _action = 0

data class Action(
    val desc: String,
    val detail: String,
    val docHtml: String = "",
    val docJs: String = "",
    val target: (List<Box>) -> Unit = {}
)

fun action(
    desc : String = "",
    detail: String,
    docHtml: String = "",
    docJs: String = "",
    payload : (List<Box>) -> Unit
)  = Action(desc, detail, docHtml, docJs, payload)

val Box.actions by Delegation<MutableMap<String, Action>>(
    // ConcurrentHashMap: `gather("actions")` walks every ancestor's map (`together.putAll(it)`) from
    // doResync on the runloop, while box code registers actions from execution threads. Already a
    // HashMap, so nothing depended on its order.
    defaultValue = { java.util.concurrent.ConcurrentHashMap<String, Action>() },
    onGet = { inside, v ->
        needsResync.add((inside as Box) to "actions")
    }
)

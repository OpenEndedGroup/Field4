@file:afield.CompletionCategory("Internal")

package afield.stdlib

import afield.Box
import afield.needsResync

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

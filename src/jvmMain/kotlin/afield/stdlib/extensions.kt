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
    defaultValue = {HashMap<String, Action>()},
    onGet = { inside, v ->
        needsResync.add((inside as Box) to "actions")
    }
)

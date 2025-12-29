package afield.stdlib

import afield.Box
import afield.needsResync

internal var _action = 0

data class Action(
    val desc: String,
    val target: (List<Box>) -> Unit = {}
)

fun action(desc : String = "", payload : (List<Box>) -> Unit)  = Action(desc, payload)

val Box.actions by Delegation<MutableMap<String, Action>>(
    defaultValue = {HashMap<String, Action>()},
    onGet = { inside, v ->
        needsResync.add((inside as Box) to "actions")
    }
)

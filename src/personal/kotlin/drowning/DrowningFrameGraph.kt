package drowning

import afield.four.Target

/** Insert or replace a target immediately before [before], preserving every unrelated entry. */
fun MutableMap<String, Target>.putBefore(name: String, target: Target, before: String) {
    val old = entries.toList()
    clear()
    var inserted = false
    old.forEach { (key, value) ->
        if (key == name) return@forEach
        if (!inserted && key == before) {
            this[name] = target
            inserted = true
        }
        this[key] = value
    }
    if (!inserted) this[name] = target
}

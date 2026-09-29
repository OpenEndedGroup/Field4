package afield.four.targets

import afield.LedgerSlot
import afield.LifecycleKinds
import afield.LifecycleLedger
import afield.Runloop
import afield.inMain

/**
 * Put [value] into this render-target map under [name] — what `renderer += "name" to module` does —
 * and record it in the lifecycle ledger against the box that is executing.
 *
 * The map belongs to the renderer, not to the box, so nothing used to take a box's module back out:
 * stopping or deleting the box left it drawing, and pinned the snippet classloader that built it.
 * The ledger entry's release removes the entry only if it still holds [value] — a later run, or
 * another box, may have put something else under the same name — and does so on the main runloop,
 * which is where the render loop walks these maps.
 */
internal fun <V : Any> MutableMap<String, V>.putOwned(name: String, value: V) {
    this[name] = value
    val map = this
    LifecycleLedger.claim(
        LifecycleKinds.RENDER_MODULE,
        name,
        slot = LedgerSlot(map, name),
        live = { map[name] === value },
        release = {
            val remove = { if (map[name] === value) map.remove(name) }
            if (Runloop.isMainThread) remove() else inMain { remove() }
        },
    )
}

package afield.four.targets

import afield.LedgerSlot
import afield.LifecycleKinds
import afield.LifecycleLedger
import afield.Ownership
import afield.Runloop
import afield.inMain
import java.util.IdentityHashMap

/**
 * Put [value] into this render-target map under [name] — what `renderer += "name" to module` does —
 * and record it in the lifecycle ledger against the box that is executing.
 *
 * The map belongs to the renderer, not to the box, so nothing used to take a box's module back out:
 * deleting the box left it drawing, and pinned the snippet classloader that built it. The claim is
 * [afield.ReleasePolicy.KEYED]: stopping the box leaves the module drawing, a re-run that puts
 * something under the same name replaces it, and retiring the box (delete, document close) removes it.
 * The ledger entry's release removes the entry only if it still holds [value] — a later run, or
 * another box, may have put something else under the same name — and does so on the main runloop,
 * which is where the render loop walks these maps.
 *
 * [renderer] is the renderer whose target the map belongs to (any identity token will do). The
 * claim is also filed under it, so that [releaseOwnedModulesOf] can end it when that renderer is
 * disposed: the module dies with the device, and the ownership would otherwise stay live, keeping
 * the module and its snippet classloader, until the box that put it there retired.
 */
internal fun <V : Any> MutableMap<String, V>.putOwned(name: String, value: V, renderer: Any? = null) {
    this[name] = value
    val map = this
    val ownership = LifecycleLedger.claim(
        LifecycleKinds.RENDER_MODULE,
        name,
        slot = LedgerSlot(map, name),
        live = { map[name] === value },
        release = {
            val remove = { if (map[name] === value) map.remove(name) }
            if (Runloop.isMainThread) remove() else inMain { remove() }
        },
    )
    if (renderer != null) OwnedModulesByRenderer.file(renderer, ownership)
}

/**
 * Close every render-module ownership [putOwned] filed under [renderer], which takes each module out
 * of its target map and out of the owning box's run scope. Called when the renderer is disposed.
 * Returns how many were still live.
 */
internal fun releaseOwnedModulesOf(renderer: Any): Int {
    val owned = OwnedModulesByRenderer.take(renderer)
    var live = 0
    owned.forEach { if (it.isLive) live++; it.close() }
    return live
}

/** How many live render-module ownerships are filed under [renderer]. */
internal fun ownedModuleCount(renderer: Any): Int = OwnedModulesByRenderer.live(renderer)

private object OwnedModulesByRenderer {
    private val byRenderer = IdentityHashMap<Any, MutableList<Ownership>>()

    fun file(renderer: Any, ownership: Ownership) = synchronized(this) {
        val list = byRenderer.getOrPut(renderer) { ArrayList() }
        // Pruned as it grows, so a renderer that lives through many re-runs does not collect the
        // ownerships its re-runs superseded.
        list.removeAll { !it.isLive }
        list.add(ownership)
    }

    fun take(renderer: Any): List<Ownership> = synchronized(this) { byRenderer.remove(renderer).orEmpty() }

    fun live(renderer: Any): Int = synchronized(this) { byRenderer[renderer]?.count { it.isLive } ?: 0 }
}

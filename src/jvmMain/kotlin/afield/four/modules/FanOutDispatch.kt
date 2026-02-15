package afield.four.modules

import afield.four.Renderer
import afield.four.buffers.ParallelGeometryPool
import afield.four.buffers.ParallelGeometryRef
import afield.four.buffers.Structure

private fun findParallelGeometryRefs(push: Structure): List<ParallelGeometryRef> {
    val refs = LinkedHashSet<ParallelGeometryRef>()
    push.offsets.keys.forEach { name ->
        val resolved = try {
            val live = push.bindings[name]
            live?.invoke() ?: push.values[name]
        } catch (_: Throwable) {
            push.values[name]
        }
        if (resolved is ParallelGeometryRef) {
            refs += resolved
        }
    }
    return refs.toList()
}

internal fun forEachFanOutDispatch(
    renderer: Renderer,
    push: Structure,
    onNoFanOut: () -> Unit,
    onWriter: (ParallelGeometryPool.ActiveWriter) -> Unit
) {
    val refs = findParallelGeometryRefs(push)
    if (refs.isEmpty()) {
        onNoFanOut()
        return
    }

    val pools = refs.map { it.pool }.distinct()
    require(pools.size == 1) {
        "Fan-out push constants must reference exactly one ParallelGeometryPool, found ${pools.size}"
    }

    val pool = pools.first()
    val snapshot = pool.snapshotForFrame(
        frameId = renderer.frameCounter,
        imageIndex = renderer.currentImage
    ) ?: return

    if (snapshot.writers.isEmpty()) return

    snapshot.writers.forEach { writer ->
        pool.withDispatchWriter(snapshot.token, writer) {
            onWriter(writer)
        }
    }
}

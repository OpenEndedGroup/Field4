package afield.graphics.render

import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.ParallelGeometryPool
import afield.graphics.vulkan.buffers.ParallelGeometryRef
import afield.graphics.vulkan.buffers.Structure

/**
 * Evaluates every live push binding once, returning the results (for [Structure.dispatchSnapshot])
 * and any [ParallelGeometryRef]s among them. A binding that throws is left out of the snapshot, so the
 * write invokes it again and reports the error where it always has.
 */
private fun evaluateBindings(push: Structure): Pair<Map<String, Any?>, List<ParallelGeometryRef>> {
    val refs = LinkedHashSet<ParallelGeometryRef>()
    val evaluated = HashMap<String, Any?>()
    push.offsets.keys.forEach { name ->
        val live = push.bindings[name]
        val resolved = if (live == null) push.values[name] else try {
            live.invoke().also { evaluated[name] = it } ?: push.values[name]
        } catch (_: Throwable) {
            push.values[name]
        }
        if (resolved is ParallelGeometryRef) {
            refs += resolved
        }
    }
    return evaluated to refs.toList()
}

internal fun forEachFanOutDispatch(
    renderer: Renderer,
    push: Structure,
    onNoFanOut: () -> Unit,
    onWriter: (ParallelGeometryPool.ActiveWriter) -> Unit
) {
    withEvaluatedPush(push) { refs -> dispatch(renderer, refs, onNoFanOut, onWriter) }
}

/**
 * Evaluates [push]'s bindings exactly once and runs [block] with the fan-out references found among
 * them; writes of [push] inside [block] reuse those results. Evaluating them a second time for the
 * write made a side-effecting binding such as `parity = { step++ % 2 }` advance twice per dispatch, so
 * the GPU only ever saw every other value (see dev_docs/GROWING_NCA.md).
 */
internal fun <T> withEvaluatedPush(push: Structure, block: (List<ParallelGeometryRef>) -> T): T {
    val (evaluated, refs) = evaluateBindings(push)
    push.dispatchSnapshot = evaluated
    try {
        return block(refs)
    } finally {
        push.dispatchSnapshot = null
    }
}

private fun dispatch(
    renderer: Renderer,
    refs: List<ParallelGeometryRef>,
    onNoFanOut: () -> Unit,
    onWriter: (ParallelGeometryPool.ActiveWriter) -> Unit
) {
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

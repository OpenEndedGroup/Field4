package afield

import afield.stdlib.Delegation

private val nestingMutationDepth = ThreadLocal.withInitial { 0 }

private inline fun <T> withNestingGuard(body: () -> T): T {
    nestingMutationDepth.set(nestingMutationDepth.get() + 1)
    return try {
        body()
    } finally {
        nestingMutationDepth.set(nestingMutationDepth.get() - 1)
    }
}

private fun inNestingGuard(): Boolean = nestingMutationDepth.get() > 0

var Box.nested by Delegation<MutableSet<Box>>(defaultValue = { mutableSetOf() }, hook = ::onNestedChange)
var Box.nestedIn by Delegation<Box?>(defaultValue = { null }, hook = ::onNestedInChange)

private fun onNestedChange(owner: HasProperties, currentValue: MutableSet<Box>, incomingValue: MutableSet<Box>): MutableSet<Box> {
    val parent = owner as Box
    if (inNestingGuard()) return incomingValue.toMutableSet()

    return withNestingGuard {
        val target = incomingValue.toMutableSet()
        val removed = currentValue - target
        val added = target - currentValue

        removed.forEach { child ->
            if (child.nestedIn == parent) {
                child.nestedIn = null
            }
            // Disconnect parent-child relationship when removing from nested
            parent.disconnect(child)
        }
        added.forEach { child ->
            if (child.nestedIn != parent) {
                child.nestedIn = parent
            }
            // Connect parent-child relationship when adding to nested
            parent.connect(child)
        }
        target
    }
}

private fun onNestedInChange(owner: HasProperties, currentValue: Box?, incomingValue: Box?): Box? {
    val child = owner as Box
    if (inNestingGuard()) return incomingValue

    return withNestingGuard {
        val oldParent = currentValue
        val newParent = incomingValue

        if (oldParent != null && oldParent != newParent) {
            val updated = oldParent.nested.toMutableSet()
            if (updated.remove(child)) {
                oldParent.nested = updated
            }
            // Disconnect parent-child relationship from old parent
            oldParent.disconnect(child)
        }

        if (newParent != null && !newParent.nested.contains(child)) {
            val updated = newParent.nested.toMutableSet()
            updated.add(child)
            newParent.nested = updated
            // Connect parent-child relationship to new parent
            newParent.connect(child)
        }

        newParent
    }
}

/**
 * Re-derive the container edges that nesting implies, over [boxes], and return how many edges were
 * added.
 *
 * **The invariant.** A nested box is connected to its container: `nested`/`nestedIn` and a
 * `children`/`parents` edge are two halves of one relation, because `gatherPreambleUpwards`
 * (`macros/macros.kt`) walks `parents`, and the edge is what carries a container's imports and
 * marked lines into what is drawn inside it. Two routes maintain that going forward —
 * [onNestedChange] / [onNestedInChange] since `cbeded171`, and [nestInto] since `398cb0ca9`. This is
 * the third enforcement point, and it is the one for state that arrives already broken.
 *
 * A document written before `398cb0ca9` has `nestedIn` set on the child and nothing on either
 * `parents` set, because `nestInto` was the only route a person could reach from the UI and it did
 * not connect. Measured over one real workspace — 401 documents holding a `metadata.edn`, 4479
 * boxes: of the 33 live boxes with `nestedIn` set, **31 have no persisted edge**, and the two that do
 * were made by the copy/duplicate route, which assigns `nestedIn` and so fired the hook. So for
 * nesting this is not a corner case, it is the whole installed base.
 *
 * This has run at EDN load since `03ad66360` (2026-04-30), inline and unnamed inside
 * `SavingContext.reconcileLoadedTopology` — which is why `398cb0ca9`, written four months later,
 * describes the missing edge as a live condition when it is a file-only one. Lifted here under a name
 * so the other paths that materialise boxes from persisted state share the one implementation rather
 * than each growing their own or (as they did) going without.
 *
 * **Deliberately additive, and why that is sound rather than a heuristic.** A missing edge is
 * *indistinguishable* from a deliberately removed one: the metadata says `"parents" #{}` beside
 * `"nestedIn" #afield/box "…"` and records no reason, no provenance, no tombstone for the edge, so a
 * `-disconnect` and a never-connected box are byte-identical. Re-deriving therefore cannot be a
 * repair of intent — only of a *derived value*, which is exactly what the edge now is. Since nesting
 * always connects and un-nesting always disconnects, on every route, "nested but not connected" is
 * no longer a state any gesture can produce and keep; a person who wants no edge wants no nesting.
 * There is no intent here to overrule.
 *
 * Tombstoned boxes are skipped in both directions, and only the nest edge is ever added — a box's
 * other edges, its document-root edge included, are untouched.
 */
fun reconcileNestingEdges(boxes: Collection<Box>): Int {
    var added = 0

    fun ensureConnection(parent: Box, child: Box) {
        var changed = false
        if (!parent.children.contains(child)) {
            parent.setPropertyRaw("children", parent.children.toMutableSet().apply { add(child) })
            changed = true
        }
        if (!child.parents.contains(parent)) {
            child.setPropertyRaw("parents", child.parents.toMutableSet().apply { add(parent) })
            changed = true
        }
        if (changed) added++
    }

    boxes.forEach { box ->
        // A deleted box gets no edges re-derived either way round. The version of this that lived in
        // `SavingContext` filtered tombstoned *children* and a tombstoned *container* but never asked
        // whether `box` itself was deleted, so a tombstone that still carried `nestedIn` — 11 of them
        // in the surveyed workspace — re-added a `children` edge on its live container, and a live
        // box under a deleted container gained a `parents` edge that would have fed the deleted box's
        // preamble back into it. On the document-open path that was invisible, because
        // `agent_backdoor.kt` prunes tombstoned parents and children immediately afterwards. The
        // other callers of this function have no such prune.
        if (box.getPropertyRaw("_tombstone").isTrue()) return@forEach

        box.nested
            .filter { !it.getPropertyRaw("_tombstone").isTrue() }
            .forEach { child ->
                if (child.nestedIn != box) {
                    child.setPropertyRaw("nestedIn", box)
                }
                ensureConnection(box, child)
            }

        val nestedParent = box.nestedIn
        if (nestedParent != null && !nestedParent.getPropertyRaw("_tombstone").isTrue()) {
            if (!nestedParent.nested.contains(box)) {
                nestedParent.setPropertyRaw("nested", nestedParent.nested.toMutableSet().apply { add(box) })
            }
            ensureConnection(nestedParent, box)
        }
    }

    return added
}

fun Box.addNested(child: Box) {
    nested = nested.toMutableSet().apply { add(child) }
}

fun Box.removeNested(child: Box) {
    if (nested.contains(child)) {
        nested = nested.toMutableSet().apply { remove(child) }
    }
}

fun Box.clearNested() {
    if (nested.isNotEmpty()) {
        nested = mutableSetOf()
    }
}

/**
 * Nest this box inside [parent] — or, with `null`, un-nest it — converting the frame to and from
 * parent-relative coordinates, and moving the connection edge along with the containment.
 *
 * **Nesting connects.** That is what [onNestedChange] and [onNestedInChange] have done since
 * `cbeded171` ("Auto-parent nested boxes to nest container"), but this function did not, for two
 * compounding reasons: it runs inside [withNestingGuard], which those hooks bail out of, and it
 * mutates `nested` in place, so a `Delegation` set-hook would never have fired for it anyway. Since
 * `nestInto` is the path the `-nest-into` / `-un-nest` actions and subtree import all take, the effect
 * was that almost every nested box in a document had no edge, while a box nested by assigning
 * `nestedIn` — `workspace/copy.kt`, which duplicates a nested box — had one. Duplicating a nested box
 * gave the copy a parent its original never had, and with it the container's inherited preamble.
 *
 * So the two are the same operation now, and a box nested into a graphics box does inherit its
 * imports and marked preamble lines (`macros/macros.kt#fun gatherPreambleUpwards`).
 *
 * One correction to the paragraph above, found while migrating the installed base: "had no edge" was
 * true of the *file*, not of the running document. [reconcileNestingEdges] — which lived inline in
 * `SavingContext.reconcileLoadedTopology` from `03ad66360`, four months before this function was
 * fixed, and which nothing named or tested — re-derives the edge from `nested`/`nestedIn` on every
 * EDN load. So a document saved before this fix opens *with* its nest edges and inherits correctly;
 * what it lacks is only the persisted copy, until the next save. The measured survey is on
 * [reconcileNestingEdges].
 *
 * **Un-nesting disconnects, and that is deliberate.** The residual asymmetry this function was
 * shipped with — "a box that held an edge to its nest parent for some *other* reason loses it" — is
 * settled by the same argument that licenses re-deriving at load: an edge that exists for some other
 * reason is *indistinguishable* from the derived one. Nothing in `parents` records why an edge is
 * there. So un-nest has to pick, and removing is the choice consistent with re-deriving: the edge
 * lives and dies with the nesting, and a person who wants it back after un-nesting can reconnect,
 * where nothing will now re-derive it away. Keeping it would instead leave a phantom edge with no
 * gesture that explains it. `Box.disconnect` removes only this one edge, so a box's document-root
 * edge is untouched either way.
 */
fun Box.nestInto(parent: Box?) {
    withNestingGuard {
        val oldParent = nestedIn
        if (oldParent != null) {
            val oldOffsetX = oldParent.frame.x
            val oldOffsetY = oldParent.frame.y + oldParent.frame.h
            frame = frame.copy(x = frame.x + oldOffsetX, y = frame.y + oldOffsetY)
            oldParent.nested.remove(this)
            needsResync.add(oldParent to "nested")
            if (oldParent != parent) oldParent.disconnect(this)
        }

        if (parent != null) {
            val newOffsetX = parent.frame.x
            val newOffsetY = parent.frame.y + parent.frame.h
            frame = frame.copy(x = frame.x - newOffsetX, y = frame.y - newOffsetY)
            parent.nested.add(this)
            needsResync.add(parent to "nested")
            parent.connect(this)
        }

        nestedIn = parent
        needsResync.add(this to "nestedIn")
    }
}

/**
 * Recursively collects all nested boxes from the given set of boxes.
 * This ensures that when saving templates or copying boxes, all nested children are included.
 *
 * @param boxes The initial set of boxes
 * @return A new set containing the original boxes plus all their recursively nested children
 */
fun collectAllNestedBoxes(boxes: Set<Box>): Set<Box> {
    val result = mutableSetOf<Box>()
    val queue = ArrayDeque(boxes)

    while (queue.isNotEmpty()) {
        val box = queue.removeFirst()
        if (result.add(box)) {
            // result.add() returns true only if the box wasn't already in the set,
            // which prevents infinite loops in circular references and duplicate processing
            queue.addAll(box.nested)
        }
    }

    return result
}

/**
 * Creates a notification message describing the number of boxes and nested boxes.
 *
 * @param selectedCount The number of originally selected boxes
 * @param totalCount The total number including nested boxes
 * @param actionVerb The action being performed (e.g., "Copied", "Saved"). Pass empty string for no verb.
 * @return A formatted message string
 */
fun formatBoxCountMessage(selectedCount: Int, totalCount: Int, actionVerb: String = ""): String {
    val nestedCount = totalCount - selectedCount
    val boxWord = if (selectedCount == 1) "box" else "boxes"
    val prefix = if (actionVerb.isNotEmpty()) "$actionVerb " else ""

    return if (nestedCount > 0) {
        "$prefix$selectedCount $boxWord + $nestedCount nested".trim()
    } else {
        "$prefix$selectedCount $boxWord".trim()
    }
}

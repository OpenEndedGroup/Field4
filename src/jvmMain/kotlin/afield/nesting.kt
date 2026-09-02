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
 * The asymmetry that remains is the one the hooks already had: un-nesting disconnects, so if the box
 * held a connection to its nest parent for some *other* reason, un-nesting takes that away too.
 * `Box.disconnect` removes only this one edge, so a box's document-root edge is untouched either way.
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

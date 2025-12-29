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

fun Box.nestInto(parent: Box?) {
    withNestingGuard {
        val oldParent = nestedIn
        if (oldParent != null) {
            val oldOffsetX = oldParent.frame.x
            val oldOffsetY = oldParent.frame.y + oldParent.frame.h
            frame = frame.copy(x = frame.x + oldOffsetX, y = frame.y + oldOffsetY)
        }

        if (parent != null) {
            val newOffsetX = parent.frame.x
            val newOffsetY = parent.frame.y + parent.frame.h
            frame = frame.copy(x = frame.x - newOffsetX, y = frame.y - newOffsetY)
        }

        nestedIn = parent
    }
}

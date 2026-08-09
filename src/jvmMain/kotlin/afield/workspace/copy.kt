package afield

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val copyRegex = Regex("""^(.*) \(copy(?: (\d+))?\)$""")

/**
 * Returns the next copy-style name for [name].
 *
 * Existing suffixes like `" (copy)"` or `" (copy 3)"` are advanced; otherwise
 * `" (copy)"` is appended.
 */
fun nextCopyName(name: String): String {
    val match = copyRegex.matchEntire(name)

    return if (match != null) {
        val base = match.groupValues[1]
        val numberPart = match.groupValues[2]

        val nextNumber = if (numberPart.isEmpty()) {
            2
        } else {
            numberPart.toInt() + 1
        }

        "$base (copy $nextNumber)"
    } else {
        "$name (copy)"
    }
}


fun copyBoxes(boxes: List<Box>): List<Box> {
    val sourceBoxes = collectAllNestedBoxes(
        boxes.filter { !it.getPropertyRaw("_tombstone").isTrue() }.toSet()
    ).filter { !it.getPropertyRaw("_tombstone").isTrue() }

    if (sourceBoxes.isEmpty()) return emptyList()

    val sourceSet = sourceBoxes.toSet()
    val topLevelSources = sourceSet.filter { it.nestedIn !in sourceSet }.toSet()

    val oldToNew = sourceBoxes.associateWith { source ->
        copyBox(source, offsetFrame = source in topLevelSources)
    }

    oldToNew.entries.forEach { (older, newer) ->
        older.parents.forEach { parent ->
            if (parent.getPropertyRaw("_tombstone").isTrue()) return@forEach
            when (parent) {
                newer.root -> {}
                in oldToNew -> {
                    oldToNew[parent]!!.connect(newer)
                }

                else -> {
                    parent.connect(newer)
                }
            }
        }
        older.children.forEach { child ->
            if (child.getPropertyRaw("_tombstone").isTrue()) return@forEach
            when (child) {
                in oldToNew -> {
                    newer.connect(oldToNew[child]!!)
                }

                else -> {
                    newer.connect(child)
                }
            }
        }
    }

    oldToNew.entries.forEach { (older, newer) ->
        val oldNestedParent = older.nestedIn ?: return@forEach
        if (oldNestedParent.getPropertyRaw("_tombstone").isTrue()) return@forEach
        newer.nestedIn = oldToNew[oldNestedParent] ?: oldNestedParent
    }

    return oldToNew.values.toList()
}

@OptIn(ExperimentalUuidApi::class)
fun copyBox(b: Box, offsetFrame: Boolean = true): Box {
    val newBox = Box(id = Uuid.random().toString())

    val exclude = setOf("parents", "children", "nested", "nestedIn")
    Box.ioPersistentProperties.forEach { name ->
        if (name in b.properties && name !in exclude) {
            newBox.properties[name] = b.properties[name]
            if (Box.uiResyncProperties.any { it.matches(name) }) {
                needsResync.add(newBox to name)
            }
        }
    }

    val newName = nextCopyName(b.name)
    newBox.properties["name"] = newName
    newBox.name = newName
    newBox.inside = b.inside
    newBox.frame = if (offsetFrame) {
        b.frame.copy(x = b.frame.x + 80, y = b.frame.y + 80)
    } else {
        b.frame.copy()
    }

    b.root.connect(newBox)
    b.inside.boxes[newBox.id] = newBox
    newBox.ki = makeKI(newBox, reason = "copyBox")
    newBox.name = newName


    return newBox
}

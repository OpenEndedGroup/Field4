package afield

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val copyRegex = Regex("""^(.*) \(copy(?: (\d+))?\)$""")

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
    val oldToNew = boxes.filter {
        !it.getPropertyRaw("_tombstone").isTrue()
    }.associateWith { copyBox(it) }
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
    return oldToNew.values.toList()
}

@OptIn(ExperimentalUuidApi::class)
fun copyBox(b: Box): Box {
    val newBox = Box(id = Uuid.random().toString())

    val exclude = setOf("parents", "children")
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
    newBox.frame = b.frame.copy(x = b.frame.x + 80, y = b.frame.y + 80)

    b.root.connect(newBox)
    b.inside.boxes[newBox.id] = newBox
    newBox.ki = makeKI(newBox)
    newBox.name = newName


    return newBox
}

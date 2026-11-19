package afield.document.graph

import afield.app.notifications.notify
import afield.app.server.thisSessionInfo
import java.util.ArrayDeque

internal enum class GraphRelation { CHILDREN, PARENTS }

/** The anchor is excluded even when a cycle reaches it again. Root and deleted nodes are barriers. */
internal fun graphVisibilityTargets(anchor: Box, relation: GraphRelation, includeSelf: Boolean): Set<Box> {
    fun eligible(box: Box) = box.id != "root" && box.getPropertyRaw("_tombstone") != true
    if (!eligible(anchor)) return emptySet()
    fun adjacent(box: Box) = if (relation == GraphRelation.CHILDREN) box.children else box.parents
    val visited = hashSetOf(anchor)
    val result = linkedSetOf<Box>()
    if (includeSelf) result.add(anchor)
    val pending = ArrayDeque<Box>()
    pending.addAll(adjacent(anchor))
    while (pending.isNotEmpty()) {
        val box = pending.removeFirst()
        if (!visited.add(box) || !eligible(box)) continue
        result.add(box)
        pending.addAll(adjacent(box))
    }
    return result
}

/** Use Field’s normal session visibility setter and client synchronization; leave topology intact. */
internal fun applyGraphVisibility(targets: Collection<Box>, visible: Boolean): Int {
    var changed = 0
    for (box in targets.distinct()) {
        if (box.id == "root" || box.getPropertyRaw("_tombstone") == true) continue
        if (box.getPropertyRaw("visible") == visible) continue
        box.visible = visible
        changed++
    }
    return changed
}

internal fun installGraphVisibilityActions() {
    data class Command(val id: String, val relation: GraphRelation, val includeSelf: Boolean, val visible: Boolean)
    val commands = listOf(
        Command("-hide-children", GraphRelation.CHILDREN, false, false),
        Command("-hide-with-children", GraphRelation.CHILDREN, true, false),
        Command("-reveal-children", GraphRelation.CHILDREN, false, true),
        Command("-hide-parents", GraphRelation.PARENTS, false, false),
        Command("-hide-with-parents", GraphRelation.PARENTS, true, false),
        Command("-reveal-parents", GraphRelation.PARENTS, false, true)
    )
    fun report(count: Int, visible: Boolean) {
        notify("${if (visible) "Revealed" else "Hid"} $count box${if (count == 1) "" else "es"}")
    }
    for (command in commands) actionTable[command.id] = { anchors ->
        val targets = anchors.flatMap { graphVisibilityTargets(it, command.relation, command.includeSelf) }
        report(applyGraphVisibility(targets, command.visible), command.visible)
    }
    actionTable["-reveal-all-boxes"] = {
        report(applyGraphVisibility(thisSessionInfo.get().boxes.values, true), true)
    }
}

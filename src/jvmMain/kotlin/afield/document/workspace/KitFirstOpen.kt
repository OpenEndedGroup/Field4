package afield.document.workspace

import afield.document.graph.Box
import java.io.File

/**
 * A document copied from a kit's starting point runs its roles, in the kit's launch order, the first time
 * it opens, so it does something without `field_kit.py activate`.
 *
 * [openStartingPointIntoWorkspace] leaves [MARKER] in a copy that holds a kit record; the server's
 * document-ready hook ([afield.app.server.installKitFirstOpen]) calls [rolesToLaunch] and launches what it
 * returns, once: the marker is consumed whether or not a launch succeeds, so a role that fails is not
 * relaunched on every open. Later opens run only what `autorun` asks for, as for any document.
 */

/** Finds the roles a freshly copied kit document launches on its first open. */
object KitFirstOpen {
    /** The one-time marker, in the document folder beside `metadata.edn`. */
    const val MARKER = ".kit-launch-on-open"

    /**
     * The role boxes of the document in [documentDir], in launch order, when it carries [MARKER]; the marker
     * is deleted. Empty for any other document, and for roles no box plays.
     */
    fun rolesToLaunch(documentDir: File, boxes: Collection<Box>): List<Box> {
        val marker = File(documentDir, MARKER)
        if (!marker.isFile) return emptyList()
        marker.delete()
        val record = runCatching { Kits.record(documentDir) }.getOrNull() ?: return emptyList()
        val live = boxes.filter { it.id != "root" && it.getPropertyRaw("_tombstone") != true }
        return record.launchOrder.mapNotNull { role -> live.singleOrNull { it.getPropertyRaw(record.roleProperty) == role } }
    }
}

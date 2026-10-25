package afield.ai.conversation

import afield.ai.tools.AiStateBlobs
import afield.document.graph.Box
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Housekeeping for the box states AI4 conversations save under `<document>/ai4_blobs/state/`.
 *
 * [AiStatePruning.sweepOrphans] removes states no session tree refers to, and [Box.pruneAiStates]
 * trims old states along each branch on request, reporting by default rather than deleting.
 */

/**
 * Keeping `<document>/ai4_blobs/state/` (the box states AI4 conversations save after each request that
 * changes them) from growing without bound, without deleting a state a turn can still be restored to.
 *
 * Two levels. [sweepOrphans] deletes only states that no session tree in the document refers to (left by
 * a conversation that was cleared or a box that was deleted); [AiStateBlobs.put] runs it once per
 * document per session. [Box.pruneAiStates] is the one that gives history up, and only when asked: it keeps
 * the latest few states along every branch and the state at every fork, and by default only reports.
 */
object AiStatePruning {
    /** What a sweep or prune found: the files it removed (or would remove), and the bytes they hold. */
    data class Report(val kept: Int, val removed: List<File>, val dryRun: Boolean) {
        val bytes: Long get() = removed.sumOf { it.length() }
        override fun toString() =
            "${if (dryRun) "would remove" else "removed"} ${removed.size} saved state(s), " +
                "${"%.1f".format(bytes / 1e6)} MB; kept $kept"
    }

    /** A state file younger than this may belong to a request still writing its turn, so it is never swept. */
    const val GRACE_MILLIS = 10 * 60 * 1000L

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** The state hashes a node records, before and after its request, for every extension key. */
    private fun statesOf(node: JsonObject): List<String> {
        val branch = node["branch"] as? JsonObject ?: return emptyList()
        return listOf("before", "after").flatMap { which ->
            (branch[which] as? JsonObject)?.values?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }
    }

    /** Every state hash any of [sessions] refers to. */
    fun referenced(sessions: List<AiSessionTree.Session>): Set<String> =
        sessions.flatMap { s -> s.nodes().values.flatMap(::statesOf) }.toSet()

    /**
     * The hashes [Box.pruneAiStates] keeps: along the path to every tip, the last [keepPerBranch] distinct
     * `after` states of each extension key, and every state recorded at a node with more than one child.
     */
    fun retained(sessions: List<AiSessionTree.Session>, keepPerBranch: Int): Set<String> {
        require(keepPerBranch >= 1) { "keepPerBranch must be at least 1" }
        val out = HashSet<String>()
        for (s in sessions) {
            val nodes = s.nodes()
            val children = nodes.values.groupingBy { it.str("parent") }.eachCount()
            nodes.values.filter { (children[it.str("id")] ?: 0) > 1 }.forEach { out += statesOf(it) }
            val tips = nodes.keys.filter { (children[it] ?: 0) == 0 }
            for (tip in tips) {
                val perKey = LinkedHashMap<String, MutableList<String>>()
                s.path(tip).forEach { node ->
                    ((node["branch"] as? JsonObject)?.get("after") as? JsonObject)?.forEach { (key, value) ->
                        val sha = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val seq = perKey.getOrPut(key) { mutableListOf() }
                        seq.remove(sha); seq += sha
                    }
                }
                perKey.values.forEach { out += it.takeLast(keepPerBranch) }
            }
        }
        return out
    }

    /** The state files in [folder] whose hash is not in [keep], older than [graceMillis]; deleted unless [dryRun]. */
    fun sweep(folder: File, keep: Set<String>, dryRun: Boolean, graceMillis: Long = GRACE_MILLIS, now: Long = System.currentTimeMillis()): Report {
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(".state.gz") }.orEmpty()
        val (held, loose) = files.partition { it.name.removeSuffix(".state.gz") in keep || now - it.lastModified() < graceMillis }
        if (!dryRun) loose.forEach { it.delete() }
        return Report(held.size, loose, dryRun)
    }

    /** Every box's session tree in [box]'s document. */
    internal fun sessionsOf(box: Box): List<AiSessionTree.Session> =
        if (!box.isInsideInitialized()) listOf(AiSessionTree.load(box))
        else box.inside.boxes.values.filter { (it.getPropertyRaw(AiSessionTree.PROPERTY) as? String)?.isNotBlank() == true }.map { AiSessionTree.load(it) }

    /** Delete the states in [box]'s document that no session tree refers to. */
    fun sweepOrphans(box: Box, dryRun: Boolean = false): Report? {
        val folder = AiStateBlobs.folderFor(box) ?: return null
        return sweep(folder, referenced(sessionsOf(box)), dryRun)
    }
}

/**
 * Free disk taken by this document's saved AI4 box states, keeping the latest [keepPerBranch] along every
 * branch of every conversation and the state at every fork. A turn whose state is removed can still be
 * rewound to, but its box is not restored. Reports what it would remove unless [dryRun] is false.
 */
fun Box.pruneAiStates(keepPerBranch: Int = 3, dryRun: Boolean = true): AiStatePruning.Report? {
    val folder = AiStateBlobs.folderFor(this) ?: return null
    return AiStatePruning.sweep(folder, AiStatePruning.retained(AiStatePruning.sessionsOf(this), keepPerBranch), dryRun)
}

package afield.tools.automation

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

/**
 * Mount an operation transcript into a document as a namespaced subgraph, and take it down again.
 *
 * [mount] takes the transcript as text, gives every box it creates the uid `prefix + localId`, connects
 * the boxes it creates that have no parent inside it beneath [mount]'s `parent`, and runs it. It
 * returns the map from the transcript's own box names to the uids they were given. [unmount] stops
 * every live box whose uid starts with the prefix, most recently created first, and deletes them.
 *
 * This is how a gallery, a slideshow or a scene runner swaps whole scenes in and out of one live
 * world: each scene is an ordinary transcript, mounted under the box that owns the renderer.
 *
 * ```kotlin
 * val scene = TranscriptMount.mount(documentPath, File("scene.yaml").readText(), prefix = "scene.a.", parent = "runner")
 * println(scene.uid("scene_drawing"))   // "scene.a.scene_drawing"
 * // … later
 * TranscriptMount.unmount(documentPath, "scene.a.")
 * ```
 *
 * Rules a mountable transcript keeps:
 * - Its code refers to other boxes through properties and `fromContext`, never by uid: the uids it
 *   was written with are not the ones it runs with.
 * - `save` operations are dropped: a mounted scene does not write the document somewhere else.
 * - A transcript that fails part-way keeps the boxes it made; [unmount] with the same prefix removes them.
 *
 * Both calls run the transcript's operations and wait for its launches, so call them from a
 * background thread or a box's `start { }`, not from code running on the main loop.
 */
object TranscriptMount {
    /** What [mount] made: the uid of each box the transcript created, by the name the transcript gave it. */
    class Mounted(
        val prefix: String,
        val documentPath: String,
        val uids: Map<String, String>,
        /** Created boxes with no parent inside the transcript, which were connected beneath the parent. */
        val roots: List<String>,
        val run: OperationTranscript.MacroRunResult,
    ) {
        val success get() = run.success

        fun uid(localId: String): String =
            uids[localId] ?: throw IllegalArgumentException("The mounted transcript created no box '$localId'; it created ${uids.keys}")

        override fun toString() = "Mounted($prefix: ${uids.size} boxes, success=$success)"
    }

    private val prefixPattern = Regex("[A-Za-z0-9_.-]+")
    private val runVerbs = setOf("launch", "launch-all", "launch-fragment", "execute", "execute-fragment", "wait", "await", "sync", "settle", "wait-pending")
    private val saveVerbs = setOf("save", "save-document", "save-as")

    private fun verb(op: Map<String, Any?>) = op.keys.firstOrNull()?.lowercase().orEmpty()

    private fun requirePrefix(prefix: String) =
        require(prefix.matches(prefixPattern)) { "A mount prefix uses letters, digits and _ . - only (it becomes part of box uids and sidecar file names), got '$prefix'" }

    /** Parse a transcript's text: a list of operations, or a map with `operations` (or `ops`). */
    internal fun operations(transcript: String): List<Map<String, Any?>> {
        val loaded = Yaml().load<Any?>(transcript)
        val raw = when (loaded) {
            is List<*> -> loaded
            is Map<*, *> -> (loaded["operations"] as? List<*>) ?: (loaded["ops"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList<Any?>()
        }
        return raw.mapNotNull { op ->
            @Suppress("UNCHECKED_CAST")
            (op as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } as Map<String, Any?>?
        }
    }

    /** The operations [mount] runs, and the uid map, without touching any document. */
    internal fun plan(
        operations: List<Map<String, Any?>>,
        prefix: String,
        parent: String?,
        dx: Double = 0.0,
        dy: Double = 0.0,
    ): Triple<List<Map<String, Any?>>, Map<String, String>, List<String>> {
        requirePrefix(prefix)
        val kept = operations.filter { verb(it) !in saveVerbs }
        val created = OperationTranscript.createdBoxes(kept).toList()
        require(created.isNotEmpty()) { "The transcript creates no boxes, so there is nothing to mount" }
        val uids = created.associateWith { prefix + it }
        val relocated = OperationTranscript.relocateOperations(kept, fresh = true, dx = dx, dy = dy, rename = { prefix + it })
        val children = kept.filter { verb(it) == "connect" }.mapNotNull { op ->
            val p = op.values.firstOrNull() as? Map<*, *>
            (p?.get("child") ?: p?.get("to") ?: p?.get("target"))?.toString()
        }.toSet()
        val roots = created.filter { it !in children }
        if (parent == null) return Triple(relocated, uids, roots.map { uids.getValue(it) })
        require(parent !in uids.values) { "The parent '$parent' is one of the boxes being mounted" }
        // Connect before anything runs, so the roots see the parent's context (a renderer, say) at launch.
        val firstRun = relocated.indexOfFirst { verb(it) in runVerbs }.let { if (it < 0) relocated.size else it }
        val connects = roots.map { linkedMapOf<String, Any?>("connect" to linkedMapOf("parent" to parent, "child" to uids.getValue(it))) }
        val ops = relocated.subList(0, firstRun) + connects + relocated.subList(firstRun, relocated.size)
        return Triple(ops, uids, roots.map { uids.getValue(it) })
    }

    /** Stop the boxes most recently created first, give their loops a few frames to end, then delete them. */
    internal fun teardownPlan(ids: List<String>): List<Map<String, Any?>> {
        val reversed = ids.asReversed()
        return reversed.map { linkedMapOf<String, Any?>("stop" to linkedMapOf("box" to it)) } +
            listOf(linkedMapOf<String, Any?>("wait" to linkedMapOf("frames" to 3))) +
            reversed.map { linkedMapOf<String, Any?>("delete" to linkedMapOf("box" to it)) }
    }

    private fun dump(ops: List<Map<String, Any?>>, name: String): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
            width = 1000
        }
        return Yaml(options).dump(linkedMapOf("name" to name, "operations" to ops))
    }

    private fun macroName(kind: String, prefix: String) = kind + "_" + prefix.trimEnd('.', '-', '_')

    /**
     * Mount [transcript] into the open document at [documentPath] under [prefix], beneath [parent].
     * Refuses a prefix that already has live boxes; [unmount] it first.
     */
    fun mount(
        documentPath: String,
        transcript: String,
        prefix: String,
        parent: String? = null,
        dx: Double = 0.0,
        dy: Double = 0.0,
    ): Mounted {
        requirePrefix(prefix)
        val live = OperationTranscript.liveBoxIds(documentPath)
            ?: throw IllegalStateException("No open document at '$documentPath'; open it before mounting into it")
        val clash = live.filter { it.startsWith(prefix) }
        check(clash.isEmpty()) { "'$prefix' is already mounted (${clash.size} live boxes, e.g. ${clash.first()}); unmount it first" }
        if (parent != null) require(parent in live || parent == "root") { "The parent '$parent' is not a live box of this document" }
        val (ops, uids, roots) = plan(operations(transcript), prefix, parent, dx, dy)
        val name = macroName("mount", prefix)
        val wrote = OperationTranscript.writeMacro(documentPath, name, dump(ops, name))
        check(wrote.success) { "Could not write the mount macro: ${wrote.message}" }
        val run = OperationTranscript.runMacro(documentPath, name)
        return Mounted(prefix, documentPath, uids, roots, run)
    }

    /** The document [box] belongs to, for the overloads that take a box. */
    private fun documentPathOf(box: afield.document.graph.Box): String =
        afield.app.server.documents.values.firstOrNull { it.boxes[box.id] === box }?.filename
            ?: throw IllegalStateException("Box '${box.id}' belongs to no open document")

    /** [mount] beneath [parent], in [parent]'s own document: the form box code uses. */
    fun mount(parent: afield.document.graph.Box, transcript: String, prefix: String, dx: Double = 0.0, dy: Double = 0.0): Mounted =
        mount(documentPathOf(parent), transcript, prefix, parent.id, dx, dy)

    /** [unmount] in [anyBox]'s document. */
    fun unmount(anyBox: afield.document.graph.Box, prefix: String, order: Mounted? = null): OperationTranscript.MacroRunResult =
        unmount(documentPathOf(anyBox), prefix, order)

    /** The live boxes mounted under [prefix], in the order they were created by this JVM's session. */
    fun mounted(documentPath: String, prefix: String): List<String> {
        requirePrefix(prefix)
        return OperationTranscript.liveBoxIds(documentPath).orEmpty().filter { it.startsWith(prefix) }
    }

    /**
     * Stop and delete every live box under [prefix], including boxes a failed or partial mount left
     * behind. Order follows [Mounted.uids] when given, else the session's own order.
     */
    fun unmount(documentPath: String, prefix: String, order: Mounted? = null): OperationTranscript.MacroRunResult {
        val live = mounted(documentPath, prefix)
        // Creation order: the Mounted we were given, else the mount macro this document still holds.
        val recorded = order?.uids?.values?.toList() ?: OperationTranscript.readMacro(documentPath, macroName("mount", prefix))
            .takeIf { it.success }?.content?.let { OperationTranscript.createdBoxes(operations(it)).toList() }.orEmpty()
        val ids = recorded.filter { it in live }.let { known -> known + (live - known.toSet()).sorted() }
        val name = macroName("unmount", prefix)
        if (ids.isEmpty()) return OperationTranscript.MacroRunResult(true, "Nothing is mounted under '$prefix'.", name, "do", emptyList(), 0, 0)
        val wrote = OperationTranscript.writeMacro(documentPath, name, dump(teardownPlan(ids), name))
        check(wrote.success) { "Could not write the unmount macro: ${wrote.message}" }
        return OperationTranscript.runMacro(documentPath, name)
    }
}

package afield.document.workspace

import afield.app.protocol.handlers
import afield.app.protocol.send
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.ui.notation.with

import afield.app.protocol.Message
import afield.app.protocol.ProvisionalEntry
import afield.app.protocol.ProvisionalSet
import afield.app.protocol._send
import afield.app.protocol.computeIfAbsent
import afield.app.protocol.payload
import afield.app.server.thisSessionInfo
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.box
import afield.document.graph.doResync
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.map
import afield.tools.automation.FlightRecorder
import afield.ui.output.markdown
import afield.ui.output.templates.filter
import afield.app.diagnostics.logging.lprintln
import afield.ai.learning.*
import afield.ai.retrieval.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.animation.motion.*
import afield.app.commands.*
import afield.app.diagnostics.*
import afield.audio.analysis.*
import afield.audio.synth.*
import afield.data.collections.*
import afield.data.formats.*
import afield.data.graph.*
import afield.data.sorting.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.geometry.mesh.*
import afield.geometry.path.*
import afield.geometry.spatial.*
import afield.graphics.color.*
import afield.graphics.interaction.*
import afield.graphics.render.*
import afield.graphics.shader.*
import afield.graphics.text.*
import afield.graphics.vulkan.buffers.*
import afield.io.osc.*
import afield.io.process.*
import afield.lang.lambda.*
import afield.lang.python.*
import afield.lang.remotejs.*
import afield.lang.stacksl.*
import afield.math.autodiff.*
import afield.math.control.*
import afield.math.estimation.*
import afield.math.functions.*
import afield.math.linalg.*
import afield.math.optimization.*
import afield.math.statistics.*
import afield.math.units.*
import afield.media.image.*
import afield.media.video.*
import afield.runtime.execution.*
import afield.runtime.languages.*
import afield.runtime.lifecycle.*
import afield.simulation.agents.*
import afield.simulation.automata.*
import afield.simulation.particles.*
import afield.study.build.*
import afield.study.evidence.*
import afield.study.search.*
import afield.study.source.*
import afield.study.ui.*
import afield.ui.controls.*
import afield.ui.output.*
import afield.ui.visualization.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Provisional edits: proposed text for a box property, typically from AI4, held per document until someone
 * accepts or rejects it. Entries are kept in memory, keyed by document, box uid and property, and every
 * change to the entries sends the document's whole [ProvisionalSet] to its browsers as `set-provisional`.
 *
 * [acceptProvisional] writes the proposed text into the box, records the acceptance (with any
 * [AiProvisionalArchive] markdown attached by [setProvisionalArchive]) in the flight recorder, and drops the
 * entry; [rejectProvisional] only drops it. [acceptAllProvisional] and [rejectAllProvisional] do the same for
 * a whole document. When the decided property is a shader source (GLSL, Slang or `stackSL`) and the box already has the
 * matching live shader module, that module is rebuilt afterwards, once per box and shader kind, on the
 * runloop; rejecting reloads it too.
 */

private val provisionalByDocument =
    ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, String>>>()

private val provisionalArchiveByDocument =
    ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, AiProvisionalArchive>>>()

/** Markdown kept beside a provisional entry and recorded with it when the entry is accepted. */
data class AiProvisionalArchive(
    /**
     * The reasoning to keep with the acceptance, usually the conversation that produced the edit. Blank
     * markdown is not stored at all, so an archive has to carry something to survive.
     */
    val markdown: String,
    /**
     * Who proposed the edit. Both archives built in the tree leave it at `AI4`, and an acceptance is
     * recorded from [markdown] alone, so nothing reads this yet.
     */
    val source: String = "AI4"
)

private val shaderProvisionalProperties = setOf(
    "vertex",
    "fragment",
    "stackSL",
    "vertex_fragment",
    "mesh",
    "task",
    "compute",
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "vertex_s",
    "fragment_s",
    "mesh_s",
    "task_s",
    "compute_s",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s"
)

private val graphicsShaderProperties = setOf("vertex", "fragment", "vertex_fragment", "vertex_s", "fragment_s")
private val computeShaderProperties = setOf("compute", "compute_s")
private val meshShaderProperties = setOf("task", "mesh", "fragment", "task_s", "mesh_s", "fragment_s")
private val rayShaderProperties = setOf(
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s"
)

private fun currentDocument(): String? =
    try {
        thisSessionInfo.get()?.filename
    } catch (_: Exception) {
        null
    }

private fun documentKeyFor(box: Box): String? =
    if (box.isInsideInitialized()) box.inside.filename else currentDocument()

/**
 * Rebuilds the live shader modules whose source a provisional decision changed, one reload per box and
 * shader kind however many of its properties were decided (accept-all on a box with both `vertex` and
 * `fragment` pending compiles it once, against both new stages).
 *
 * Launched on the runloop and not awaited. The callers are the `provisional-accept` / `-reject` /
 * `-accept-all` / `-reject-all` handlers, which run on the Netty event loop serving that browser's /ws
 * socket; waiting there for the runloop to come free and then for a GLSL/Slang compile plus pipeline
 * rebuild stalled every message on the socket, keepalive pongs included, until clients dropped it
 * ("1011 keepalive ping timeout"). Nothing after the call needs the reload: the handlers send no reply,
 * the property is written and the provisional set sent before this runs, and a reload reports its own
 * errors onto the shader property. The runloop runs launches in order, so a later decision or `alt-r`
 * on the same box still follows this one.
 */
@OptIn(DelicateCoroutinesApi::class)
private fun reloadShaderRuntimeForProvisionalProperties(decided: List<Pair<Box, String>>) {
    val propertiesByBox = decided
        .filter { (_, property) -> property in shaderProvisionalProperties }
        .groupBy({ it.first }, { it.second })
    if (propertiesByBox.isEmpty()) return
    GlobalScope.launch(Runloop.runLoop.main) {
        propertiesByBox.forEach { (target, properties) -> reloadShaderRuntime(target, properties.toSet()) }
    }
}

private fun reloadShaderRuntime(target: Box, properties: Set<String>) {
    fun changed(kind: Set<String>) = properties.any { it in kind }
    runCatching {
        if (changed(graphicsShaderProperties) && runCatching { target._shader != null }.getOrDefault(false)) {
            reloadShader(target)
        }
        if (changed(computeShaderProperties) && runCatching { target._computeShader != null }.getOrDefault(false)) {
            reloadComputeShader(target)
        }
        if (changed(meshShaderProperties) && runCatching { target._meshShader != null }.getOrDefault(false)) {
            reloadMeshShader(target)
        }
        if (changed(rayShaderProperties) && runCatching { target._rayTracingShader != null }.getOrDefault(false)) {
            reloadRayTracingShader(target)
        }
    }.onFailure { throwable ->
        lprintln(
            "warning,ai4",
            "Unable to refresh shader runtime after provisional decision for ${target.id}/${properties.joinToString("+")}: ${
                throwable.message ?: throwable::class.java.name
            }"
        )
    }
}

private fun documentMap(document: String): ConcurrentHashMap<String, ConcurrentHashMap<String, String>> =
    provisionalByDocument.computeIfAbsent(document) { ConcurrentHashMap() }

private fun propertyMap(document: String, uid: String): ConcurrentHashMap<String, String> =
    documentMap(document).computeIfAbsent(uid) { ConcurrentHashMap() }

/**
 * Proposes [contents] for one of a box's properties, against the document the box belongs to. With no
 * contents it proposes the property's current text, which makes accepting a no-op on the box and leaves the
 * proposal as a thing to approve rather than a change to apply. Does nothing when the box belongs to no
 * document, and when the property is absent or does not hold text.
 */
fun markProvisional(box: Box, property: String, contents: String? = null) {
    val document = documentKeyFor(box) ?: return
    val value = contents ?: (box.getPropertyRaw(property) as? String) ?: return
    setProvisional(document, box.id, property, value)
}

/**
 * Proposes [contents] for a box property by document path and box uid, replacing any proposal already held
 * for that property, and sends the document's whole set to its browsers. Neither the box nor the property
 * has to exist yet; a proposal for a box the session does not have is dropped when it is decided.
 */
fun setProvisional(document: String, uid: String, property: String, contents: String) {
    val props = propertyMap(document, uid)
    props[property] = contents
    sendProvisionalSet(document)
}

/**
 * Attaches the reasoning behind a proposal to one box property, to be recorded if that property is accepted.
 * An archive with blank markdown is ignored. Archives are held apart from the proposals, and one is dropped
 * when its own proposal is decided, so an archive attached to a property with no proposal stays until the
 * document's proposals are cleared as a whole.
 */
fun setProvisionalArchive(document: String, uid: String, property: String, archive: AiProvisionalArchive) {
    if (archive.markdown.isBlank()) return
    val props = provisionalArchiveByDocument
        .computeIfAbsent(document) { ConcurrentHashMap() }
        .computeIfAbsent(uid) { ConcurrentHashMap() }
    props[property] = archive
}

/**
 * Attaches one archive to every property in [entries], which is how a single conversation covers the several
 * boxes it edited. Each entry gets the same markdown, so accepting any one of them records the whole of it.
 */
fun setProvisionalArchive(document: String, entries: List<ProvisionalEntry>, archive: AiProvisionalArchive) {
    entries.forEach { entry ->
        setProvisionalArchive(document, entry.uid, entry.property, archive)
    }
}

private fun provisionalArchive(document: String, uid: String, property: String): AiProvisionalArchive? =
    provisionalArchiveByDocument[document]?.get(uid)?.get(property)

private fun removeProvisionalArchive(document: String, uid: String, property: String) {
    val docMap = provisionalArchiveByDocument[document] ?: return
    val props = docMap[uid] ?: return
    props.remove(property)
    if (props.isEmpty()) docMap.remove(uid)
    if (docMap.isEmpty()) provisionalArchiveByDocument.remove(document)
}

/**
 * Drops the proposal for one of a box's properties without applying it, and reports whether there was one.
 * False for a box that belongs to no document. This is [removeProvisional] addressed by box; note that the
 * other function of this name takes a document path and drops every proposal in it.
 */
fun clearProvisional(box: Box, property: String): Boolean {
    val document = documentKeyFor(box) ?: return false
    return removeProvisional(document, box.id, property)
}

/**
 * Drops one proposal and its archive without applying it, reporting whether there was one to drop. The
 * document's set is sent to its browsers on every path through this, including the ones that found nothing,
 * so a browser showing a stale proposal is corrected either way.
 */
fun removeProvisional(document: String, uid: String, property: String): Boolean {
    val docMap = provisionalByDocument[document] ?: run {
        sendProvisionalSet(document)
        return false
    }
    val props = docMap[uid] ?: run {
        sendProvisionalSet(document)
        return false
    }
    val removed = props.remove(property) != null
    if (props.isEmpty()) docMap.remove(uid)
    if (docMap.isEmpty()) provisionalByDocument.remove(document)
    removeProvisionalArchive(document, uid, property)
    sendProvisionalSet(document)
    return removed
}

/**
 * Drops every proposal and archive held for a document, applying none of them, and tells its browsers. Takes
 * a document path, unlike the overload taking a box and a property, which drops exactly one.
 */
fun clearProvisional(document: String) {
    provisionalByDocument.remove(document)
    provisionalArchiveByDocument.remove(document)
    sendProvisionalSet(document)
}

/** A document's proposals as the wire type the frontend receives, which is [provisionalEntries] in an envelope. */
fun provisionalSet(document: String): ProvisionalSet =
    ProvisionalSet(provisionalEntries(document))

/**
 * Every proposal held for a document, each naming a box uid, a property and the proposed text. Empty for a
 * document with none. The order follows the concurrent maps these are held in, so treat it as arbitrary.
 */
fun provisionalEntries(document: String): List<ProvisionalEntry> {
    val docMap = provisionalByDocument[document] ?: return emptyList()
    return docMap.flatMap { (uid, props) ->
        props.map { (property, contents) ->
            ProvisionalEntry(uid = uid, property = property, contents = contents)
        }
    }
}

/**
 * Writes one proposal into its box, records the acceptance with any archive attached to it, and drops the
 * proposal. Reports false and changes nothing when there is no such proposal; when the box is not in the
 * current session it also drops the proposal, since nothing could accept it later. A decided shader property
 * has its live module rebuilt afterwards.
 */
fun acceptProvisional(document: String, uid: String, property: String): Boolean {
    val docMap = provisionalByDocument[document] ?: return false
    val props = docMap[uid] ?: return false
    val contents = props[property] ?: return false
    val target = thisSessionInfo.get().boxes[uid] ?: run {
        removeProvisional(document, uid, property)
        return false
    }
    target.setPropertyRaw(property, contents, doResync = true)
    FlightRecorder.recordAiProvisionalAccepted(
        box = target,
        property = property,
        acceptedValue = contents,
        archiveMarkdown = provisionalArchive(document, uid, property)?.markdown
    )
    removeProvisional(document, uid, property)
    reloadShaderRuntimeForProvisionalProperties(listOf(target to property))
    return true
}

/**
 * Records an acceptance of [acceptedValue], with any archive attached to the property, and does nothing
 * else: the proposal stays and the box is not written. The editor sends this when someone takes part of a
 * proposal hunk by hunk, so what was accepted is the editor's text rather than the proposal verbatim.
 * Reports false when the proposal or the box is gone.
 */
fun recordAcceptedProvisionalValue(document: String, uid: String, property: String, acceptedValue: String): Boolean {
    val docMap = provisionalByDocument[document] ?: return false
    val props = docMap[uid] ?: return false
    if (!props.containsKey(property)) return false
    val target = thisSessionInfo.get().boxes[uid] ?: return false
    FlightRecorder.recordAiProvisionalAccepted(
        box = target,
        property = property,
        acceptedValue = acceptedValue,
        archiveMarkdown = provisionalArchive(document, uid, property)?.markdown
    )
    return true
}

/**
 * Drops one proposal without writing it, and reports whether there was one. A rejected shader property has
 * its live module rebuilt too, so the box goes back to running the source it had before the proposal.
 */
fun rejectProvisional(document: String, uid: String, property: String): Boolean {
    val target = thisSessionInfo.get().boxes[uid]
    val removed = removeProvisional(document, uid, property)
    if (removed && target != null) {
        reloadShaderRuntimeForProvisionalProperties(listOf(target to property))
    }
    return removed
}

/**
 * Writes every proposal a document holds into its box and records each acceptance, then clears the document.
 * A proposal whose box is not in the session is cleared along with the rest without being applied. Each box
 * and shader kind is rebuilt once, however many of its properties were accepted.
 */
fun acceptAllProvisional(document: String) {
    val entries = provisionalEntries(document)
    if (entries.isEmpty()) {
        sendProvisionalSet(document)
        return
    }
    val accepted = entries.mapNotNull { entry ->
        val target = thisSessionInfo.get().boxes[entry.uid] ?: return@mapNotNull null
        target.setPropertyRaw(entry.property, entry.contents, doResync = true)
        FlightRecorder.recordAiProvisionalAccepted(
            box = target,
            property = entry.property,
            acceptedValue = entry.contents,
            archiveMarkdown = provisionalArchive(document, entry.uid, entry.property)?.markdown
        )
        target to entry.property
    }
    clearProvisional(document)
    reloadShaderRuntimeForProvisionalProperties(accepted)
}

/**
 * Drops every proposal a document holds, writing none of them, and rebuilds the live shader modules of the
 * boxes whose shader sources were among them.
 */
fun rejectAllProvisional(document: String) {
    val entries = provisionalEntries(document)
    clearProvisional(document)
    val sessionBoxes = thisSessionInfo.get().boxes
    reloadShaderRuntimeForProvisionalProperties(
        entries.mapNotNull { entry -> sessionBoxes[entry.uid]?.let { it to entry.property } }
    )
}

/**
 * Sends a document's whole proposal set to every browser open on it, as `set-provisional`. The set is sent
 * whole rather than as a delta, so this is also how a browser is corrected after any change.
 */
fun sendProvisionalSet(document: String) {
    val message = Message("set-provisional").payload(provisionalSet(document))
    _send(message, document = Regex.escape(document))
}

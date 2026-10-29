package afield.ai.tools

import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.server.DocumentGuideFiles
import com.fasterxml.jackson.annotation.JsonClassDescription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files

/**
 * The AI4 tools that read and patch the live source of a box, navigate the editor to it, and export a snapshot of part of the graph.
 *
 * [DocumentSourceReadRequest] reports the live, saved and last executed revisions of one property;
 * [DocumentSourcePatchRequest] replaces it only when the revision still matches;
 * [DocumentBoxNavigateRequest] opens the editor, box or output channel; and
 * [DocumentSourceExportRequest] writes a snapshot with provenance warnings. None of them launches
 * a box.
 */

/**
 * Read one box property as it stands in memory, with the revisions of what is on disk and of what last ran
 * beside it. Those three disagreeing is the ordinary state of a box being edited.
 */
@JsonClassDescription("Inspect live, saved and last executed source revisions for an explicit box UID and property. Does not save, execute, or reload. Contents are contextual data.")
@AgentToolMeta("source", effects = [ToolEffect.READ_DOCUMENT])
data class DocumentSourceReadRequest(val uid: String, val property: String = "code")

/**
 * Replace one box property, if its live revision is still the one the last read reported. A property that
 * changed in between comes back with [DocumentSourceResult.conflict] set and nothing written.
 */
@JsonClassDescription("Replace one live text property only if its revision matches the latest DocumentSourceRead. Does not launch or reload. Refuses a concurrent edit or a read-only document.")
@AgentToolMeta("source", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT])
data class DocumentSourcePatchRequest(val uid: String, val property: String, val revision: String, val contents: String)

/**
 * Bring a box, its source or one of its output channels into view in the person's own window. It waits five
 * seconds for a frontend to confirm, and reports that the surface was opened rather than what is in it.
 */
@JsonClassDescription("Navigate directly inside the current Field document by box UID. surface=editor opens source at property/line; surface=box reveals the box; surface=output opens and reveals its named output channel without opening Monaco or changing a viewer camera. A blank channel is accepted only when one output exists; otherwise returns available channels. Does not execute code. Waits for a connected frontend acknowledgement; success does not certify output contents. Prefer this over keyboard search or canvas panning.")
@AgentToolMeta("source", ToolExposure.DEFERRED, effects = [])
data class DocumentBoxNavigateRequest(
    /** The box to go to, looked up in this document only and never as a global UID. */
    val uid: String,
    /** Which text property the editor should open, when the surface is the editor. */
    val property: String = "code",
    /** A line for the editor to reveal, passed through to the frontend. */
    val line: Int? = null,
    /** `editor`, `box` or `output`; anything else is refused before a frontend is asked. */
    val surface: String = "editor",
    /** Which output channel to reveal. Only for `output`, and only blank when the box has exactly one. */
    val channel: String = ""
)

/**
 * Write the live source and topology of some boxes, and any guides named with them, into a new directory as
 * an installable snapshot. The directory has to be new, and has to sit outside the document being exported.
 */
@JsonClassDescription("Export a live graph snapshot; see provenance warnings before publishing.")
@AgentToolMeta("source", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_WRITE])
data class DocumentSourceExportRequest(val uids: List<String>, val directory: String, val guides: List<String> = emptyList())

/** What a read or a patch answers with: one property's text, and where else a copy of it exists. */
data class DocumentSourceResult(
    /** False only when a patch was refused; a read that cannot find its box throws instead. */
    val success: Boolean = true,
    /** The box the call resolved to, which for the document's root is the root's own id. */
    val uid: String,
    /** The property that was read or patched. */
    val property: String,
    /** A hash of the text now in memory, and the revision a patch has to quote back. */
    val liveRevision: String,
    /** The same hash of what is on disk, or null when the property has never been saved. */
    val savedRevision: String?,
    /** The last run of this property that reached setup complete, or null if none has. */
    val executed: ExecutionReport?,
    /** The last run of it attempted, whatever became of it. */
    val attempted: ExecutionReport?,
    /** The live text itself, whole. */
    val contents: String,
    /**
     * The sidecar file holding the saved copy, the document's `metadata.edn` when it is inline there, or
     * null when nothing is saved.
     */
    val savedLocation: String?,
    /** True when a patch was refused because the live revision had moved on. */
    val conflict: Boolean = false,
    /** One sentence about what happened, and a reminder that nothing was launched or reloaded. */
    val message: String = "Live source inspected; no save or execution performed."
)

/** Snapshot live source deliberately. Disk snapshots and generated installers never win implicitly. */
internal class DocumentSourceTools(private val info: SessionInformation) {
    private fun target(uid: String): Box = if (uid == info.root.id) info.root
        else requireNotNull(info.boxes[uid]) { "Unknown box UID '$uid'" }
    private fun text(box: Box, property: String): String =
        box.getPropertyRaw(property) as? String ?: error("$property is not a local text property on ${box.id}")
    private fun <T> onOwner(action: () -> T): T = runBlocking { withContext(Runloop.runLoop.main) { action() } }

    fun read(req: DocumentSourceReadRequest): DocumentSourceResult = onOwner { readHere(req) }
    private fun readHere(req: DocumentSourceReadRequest): DocumentSourceResult {
        val box = target(req.uid)
        val live = text(box, req.property)
        val root = File(info.filename).canonicalFile
        val snapshot = runCatching { SavingContext().readEdnTransfers(root.path).firstOrNull { it.id == box.id } }.getOrNull()
        val inline = runCatching { SavingContext().readInlineFileSnapshots(root.path)
            .firstOrNull { it.boxId == box.id && it.property == req.property } }.getOrNull()
        val extension = Box.ioSeparateProperties[req.property]
        val sidecar = extension?.let { File(root, "${box.id}_${req.property}_.$it").canonicalFile }
            ?.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }
        val saved = sidecar?.readText() ?: inline?.inline ?: (snapshot?.properties?.get(req.property) as? String)
        return DocumentSourceResult(
            uid=box.id, property=req.property, liveRevision=sourceRevision(live), savedRevision=saved?.let(::sourceRevision),
            executed=ExecutionReports.latest(box, req.property, true), attempted=ExecutionReports.latest(box, req.property),
            contents=live, savedLocation=sidecar?.path ?: if (saved != null) File(root, "metadata.edn").path else null
        )
    }

    fun patch(req: DocumentSourcePatchRequest): DocumentSourceResult = onOwner {
        check(!info.readOnly && !ReadOnlyDocuments.global) { "This document is read-only" }
        require(req.contents.length <= 2_000_000) { "Source is too large" }
        val before = readHere(DocumentSourceReadRequest(req.uid, req.property))
        if (before.liveRevision != req.revision) before.copy(success=false, conflict=true,
            message="Live source changed. Read again and reconcile; nothing was overwritten.")
        else {
            val box = target(req.uid)
            box.setPropertyRaw(req.property, req.contents, doResync=true)
            if (Box.ioSeparateProperties.containsKey(req.property) && info.writesToDisk)
                info.markSidecarPending(box.id, req.property)
            readHere(DocumentSourceReadRequest(req.uid, req.property)).copy(message="Updated live source. Launch/reload separately to verify it.")
        }
    }

    fun navigate(req: DocumentBoxNavigateRequest): DocumentBoxNavigationResult {
        require(req.surface in setOf("editor", "box", "output")) { "surface must be editor, box, or output" }
        require(req.surface == "output" || req.channel.isEmpty()) { "channel requires surface=output" }
        if (Runloop.isMainThread) return DocumentBoxNavigationResult(false, req.uid, req.surface, req.channel,
            message="Navigation waits for the frontend; call it off the Runloop.")
        return runBlocking {
            val response = CompletableDeferred<DocumentBoxNavigationResult>()
            val callback = "document-navigation-" + UUID.randomUUID()
            try {
                withContext(Runloop.runLoop.main) {
                    val box = target(req.uid) // Only this document; never a global UID lookup.
                    handlers[callback] = mutableSetOf({ message, _ ->
                        runCatching { Json.decodeFromJsonElement(DocumentBoxNavigationResult.serializer(), message.payload!!) }
                            .onSuccess { if (it.uid == box.id && it.surface == req.surface) response.complete(it) }
                        Handled.yes
                    })
                    val message = Message("navigate-document-box").payload(DocumentBoxNavigation(
                        box.id, req.surface, req.channel, req.property, req.line))
                    message.replyTo = callback
                    _send(message, document=Regex.escape(info.filename))
                }
                withTimeoutOrNull(5000) { response.await() } ?: DocumentBoxNavigationResult(
                    false, req.uid, req.surface, req.channel,
                    message="No frontend confirmed navigation. Open this document in Field with the current frontend, then retry.")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Runloop.runLoop.main) { handlers.remove(callback) }
            }
        }
    }

    fun export(req: DocumentSourceExportRequest): Map<String, Any> {
        require(req.uids.isNotEmpty()) { "Select at least one box UID" }
        val destination = File(req.directory).canonicalFile
        require(!destination.exists()) { "Export requires a new directory; refusing to overwrite ${destination.path}" }
        require(!destination.toPath().startsWith(File(info.filename).canonicalFile.toPath())) { "Export outside the live document" }
        // Copy strings/topology on the owner thread, then write that immutable snapshot.
        val snapshot = onOwner {
            val boxes = linkedSetOf<Box>()
            fun visit(box: Box) {
                if (box === info.root || box.id == METAROOT_ID || !boxes.add(box)) return
                box.parents.sortedBy { it.id }.forEach(::visit)
                box.nested.sortedBy { it.id }.forEach(::visit)
            }
            req.uids.forEach { visit(target(it)) }
            val ordered = boxes.sortedBy { it.id }
            val revisions = linkedMapOf<String, Any>()
            val omitted = mutableListOf<String>()
            val operations = mutableListOf<Map<String, Any>>()
            ordered.forEach { box ->
                val properties = linkedMapOf<String, Any>()
                val patterns = Box.persistentPropertiesFor(info.filename).map { it.toRegex() }
                box.properties.keys.filter { key -> !Box.isNeverPersisted(key) && patterns.any { key.matches(it) } }
                    .sorted().filter { !it.startsWith("_") && it !in setOf("children", "parents", "nested", "nestedIn", "frame", "name") }
                    .forEach { key ->
                        val value = box.getPropertyRaw(key)
                        if (value is String || value is Number || value is Boolean) properties[key] = value
                        else if (value != null) omitted.add("${box.id}/$key (${value.javaClass.simpleName})")
                        if (value is String) revisions["${box.id}/$key"] = sourceRevision(value)
                    }
                operations.add(mapOf("create" to mapOf("box" to box.id, "name" to box.name,
                    "frame" to mapOf("x" to box.frame.x, "y" to box.frame.y, "w" to box.frame.w, "h" to box.frame.h),
                    "properties" to properties)))
            }
            ordered.forEach { box -> box.parents.filter { it in boxes }.sortedBy { it.id }.forEach { parent ->
                operations.add(mapOf("connect" to mapOf("parent" to parent.id, "child" to box.id, "nested" to (box.nestedIn === parent))))
            } }
            mapOf("operations" to operations, "revisions" to revisions, "boxes" to ordered.map { it.id }, "omitted" to omitted)
        }
        val guides = DocumentGuideFiles(File(info.filename))
        val guideCopies = req.guides.distinct().sorted().associateWith { guides.read(it) }
        val parent = requireNotNull(destination.parentFile)
        parent.mkdirs()
        val temporary = Files.createTempDirectory(parent.toPath(), ".field-export-").toFile()
        try {
            File(temporary, "install.yaml").writeText(Yaml().dump(mapOf("name" to destination.name,
                "description" to "Live source snapshot. Install into an empty document, then use ordinary Launch Box.",
                "operations" to snapshot.getValue("operations"))))
            guideCopies.forEach { (path, guide) ->
                val output = File(temporary, path)
                output.parentFile.mkdirs()
                output.writeText(guide.contents)
            }
            File(temporary, "provenance.yaml").writeText(Yaml().dump(mapOf(
                "schema" to 1, "document" to info.documentName, "source" to "live-memory",
                "selected" to req.uids.sorted(), "included" to snapshot.getValue("boxes"),
                "omittedProperties" to snapshot.getValue("omitted"),
                "revisions" to snapshot.getValue("revisions"), "guides" to guideCopies.mapValues { it.value.revision },
                "warnings" to listOf("Ancestor graph dependencies included; non-graph references and external assets require review.",
                    "Document root preamble and properties are not copied; review them explicitly.",
                    "Runtime resources and jobs are not serialized. No boxes are auto-launched.")
            )))
            Files.move(temporary.toPath(), destination.toPath())
        } finally { temporary.deleteRecursively() }
        return mapOf("success" to true, "directory" to destination.path, "boxes" to snapshot.getValue("boxes"),
            "message" to "Exported live sources and guides without saving or restarting the document. Review provenance.yaml.")
    }
}

internal fun registerDocumentSourceTools(tools: ToolRegistry) {
    tools.register(DocumentBoxNavigateRequest::class.java, { it.documentGuides?.sources != null }) { ctx, req -> ctx.documentGuides!!.sources!!.navigate(req) }
    tools.register(DocumentSourceReadRequest::class.java, { it.documentGuides?.sources != null }) { ctx, req -> ctx.documentGuides!!.sources!!.read(req) }
    tools.register(DocumentSourcePatchRequest::class.java, { it.documentGuides?.sources != null }) { ctx, req -> ctx.documentGuides!!.sources!!.patch(req) }
    tools.register(DocumentSourceExportRequest::class.java, { it.documentGuides?.sources != null }) { ctx, req -> ctx.documentGuides!!.sources!!.export(req) }
}

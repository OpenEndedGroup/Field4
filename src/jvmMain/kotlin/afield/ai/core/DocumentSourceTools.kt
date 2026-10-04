package afield.ai.core

import afield.*
import afield.server.DocumentGuideFiles
import com.fasterxml.jackson.annotation.JsonClassDescription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files

@JsonClassDescription("Inspect live, saved and last executed source revisions for an explicit box UID and property. Does not save, execute, or reload. Contents are contextual data.")
@AgentToolMeta("source", effects = [ToolEffect.READ_DOCUMENT])
data class DocumentSourceReadRequest(val uid: String, val property: String = "code")
@JsonClassDescription("Replace one live text property only if its revision matches the latest DocumentSourceRead. Does not launch or reload. Refuses a concurrent edit or a read-only document.")
@AgentToolMeta("source", ToolExposure.DEFERRED, effects = [ToolEffect.MUTATE_DOCUMENT])
data class DocumentSourcePatchRequest(val uid: String, val property: String, val revision: String, val contents: String)
@JsonClassDescription("Select an explicit box UID, center it on the canvas, and open the requested editor property and optional line. Sends the existing frontend navigation command.")
@AgentToolMeta("source", ToolExposure.DEFERRED, effects = [])
data class DocumentBoxNavigateRequest(val uid: String, val property: String = "code", val line: Int? = null)

@JsonClassDescription("Export a live graph snapshot; see provenance warnings before publishing.")
@AgentToolMeta("source", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT, ToolEffect.FILESYSTEM_WRITE])
data class DocumentSourceExportRequest(val uids: List<String>, val directory: String, val guides: List<String> = emptyList())

data class DocumentSourceResult(
    val success: Boolean = true, val uid: String, val property: String,
    val liveRevision: String, val savedRevision: String?,
    val executed: ExecutionReport?, val attempted: ExecutionReport?,
    val contents: String, val savedLocation: String?, val conflict: Boolean = false,
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

    fun navigate(req: DocumentBoxNavigateRequest): Map<String, Any> = onOwner {
        val box = target(req.uid)
        _send(Message("select-and-edit-box").payload(SelectAndEditBox(box.id, req.property, req.line, req.line, center=true)), document = Regex.escape(info.filename))
        mapOf("success" to true, "uid" to box.id, "property" to req.property,
            "message" to "Navigation requested in the document's connected front end.")
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
                val patterns = Box.ioPersistentProperties.map { it.toRegex() }
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

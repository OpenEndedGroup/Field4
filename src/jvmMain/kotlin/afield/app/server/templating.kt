package afield.app.server

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
import afield.app.diagnostics.logging.lprintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class RemoteTemplate(val name: String, val has_edn: Boolean)

@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxDocumentation: String, // Formatted documentation from box metadata
    val source: String = "template"
)

/**
 * Remote metadata-template servers, read once from `AFIELD_TEMPLATE_SERVERS` or, when the
 * environment variable is absent, `afield.templateServers`.
 *
 * [templating] starts one background fetch of each `/list` endpoint. The cache is not refreshed
 * afterwards, and failed or empty responses do not create cache entries. Template insertion uses the local
 * templates first, in [templateRoots] order, then cached remote servers.
 */
val templateServers: List<String> by lazy {
    val serversEnv = System.getenv("AFIELD_TEMPLATE_SERVERS") ?: System.getProperty("afield.templateServers") ?: ""
    if (serversEnv.isBlank()) emptyList()
    else serversEnv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

// Cache for remote templates - populated on startup
private val remoteTemplatesCache = ConcurrentHashMap<String, List<String>>()

// HTTP client for fetching templates. Both timeouts bound how long a template server that is down or
// stalled can hold a fetch; a download is never made on a websocket event loop (see insert-template).
private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
private val templateRequestTimeout = Duration.ofSeconds(10)

// Template listing and remote downloads run here, off the Netty event loop serving the page's websocket.
private val templateIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Fetch templates from a remote server's /list endpoint
 */
private fun fetchTemplatesFromServer(serverUrl: String): List<String> {
    try {
        val listUrl = if (serverUrl.endsWith("/")) "${serverUrl}list" else "$serverUrl/list"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(listUrl))
            .timeout(templateRequestTimeout)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            val templates = Json.decodeFromString<List<RemoteTemplate>>(response.body())
            return templates.filter { it.has_edn }.map { it.name }
        } else {
            lprintln("warning,template", "Failed to fetch templates from $serverUrl - Status: ${response.statusCode()}")
        }
    } catch (e: Exception) {
        lprintln("warning,template", "Error fetching templates from $serverUrl: ${e.message}")
    }
    return emptyList()
}

/**
 * Download a template's metadata.edn from a remote server
 */
private fun downloadTemplateFromServer(serverUrl: String, templateName: String): String? {
    try {
        val downloadUrl = if (serverUrl.endsWith("/")) "${serverUrl}download/$templateName" else "$serverUrl/download/$templateName"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(downloadUrl))
            .timeout(templateRequestTimeout)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() == 200) {
            return response.body()
        } else {
            lprintln("warning,template", "Failed to download template $templateName from $serverUrl - Status: ${response.statusCode()}")
        }
    } catch (e: Exception) {
        lprintln("warning,template", "Error downloading template $templateName from $serverUrl: ${e.message}")
    }
    return null
}

/**
 * Extract documentation from template boxes.
 * Accumulates metadata from all boxes, orders by increasing frame.y,
 * and creates a formatted documentation string used by the "insert from
 * workspace" QuickPick/palette detail panel (see insertFromWorkspace in
 * src/jsMain/kotlin/afield/app/client/commands.kt).
 *
 * Template-authoring guide
 * ------------------------
 * Template documentation lives in the `metadata` property of a template's
 * boxes, written as YAML. Three fields are read and accumulated:
 *
 * ```yaml
 * doc: |
 *   Main documentation for this box. Can span multiple lines.
 * docPreamble: "Introduction text for this section"
 * boxPreamble: "Specific preamble for this box"
 * ```
 *
 * - Boxes are ordered by increasing `frame.y` (top-to-bottom visual order),
 *   so the accumulated documentation reads in the same order the boxes appear.
 * - After the prose, a compressed box list is appended, e.g.
 *   `Boxes: ShaderBox/{vertex,fragment}, ControlBox, DataBox/{compute}`.
 *   The `{...}` suffix lists which sidecar-able properties each box carries.
 * - The exact set of sidecar properties reported is the `sidecarProperties`
 *   list below — treat that list as the source of truth, not any external doc,
 *   since it changes as new shader stages are added. The `code` property is
 *   deliberately excluded from this display.
 * - Repeated box names are compressed (e.g. `Untitled × 5`).
 *
 * @param source label for where the template came from (workspace / bundled /
 *   remote / etc.), surfaced in the palette description.
 */
private fun extractTemplateMetadata(templatePath: String, templateName: String, source: String): TemplateMetadata? {
    try {
        val file = File(templatePath)
        if (!file.exists()) return null

        val lastModified = file.lastModified()
        val ednContent = file.readText()

        val directory = file.parentFile?.absolutePath ?: File(".").absolutePath
        val savingContext = SavingContext()
        val transfers = savingContext.readEdnTransfersFromString(ednContent)

        // Sort boxes by frame.y (increasing) without instantiating boxes
        val sortedTransfers = transfers.sortedBy { transfer ->
            val frame = savingContext.resolveStoredValue(transfer.properties["frame"], directory) as? Rect
            frame?.y ?: 0.0
        }

        // Sidecar-able properties (excluding "code")
        val sidecarProperties = listOf(
            "vertex",
            "fragment",
            "vertex_fragment",
            "compute",
            "task",
            "mesh",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "vertex_s",
            "fragment_s",
            "compute_s",
            "task_s",
            "mesh_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s"
        )

        // Build documentation from metadata properties
        val docBuilder = StringBuilder()

        sortedTransfers.forEach { transfer ->
            val metadata = savingContext.resolveStoredValue(transfer.properties["metadata"], directory) as? String
            if (!metadata.isNullOrEmpty()) {
                try {
                    val parsed = parseMetadata(metadata)

                    parsed["doc"]?.let { doc ->
                        if (doc.toString().isNotEmpty()) {
                            docBuilder.append(doc.toString().trim()).append("\n\n")
                        }
                    }

                    parsed["docPreamble"]?.let { preamble ->
                        if (preamble.toString().isNotEmpty()) {
                            docBuilder.append(preamble.toString().trim()).append("\n\n")
                        }
                    }

                    parsed["boxPreamble"]?.let { boxPreamble ->
                        if (boxPreamble.toString().isNotEmpty()) {
                            docBuilder.append(boxPreamble.toString().trim()).append("\n\n")
                        }
                    }
                } catch (e: Exception) {
                    // Ignore metadata parsing errors
                }
            }
        }

        // Add compressed box description
        val rawDescriptions = sortedTransfers.mapNotNull { transfer ->
            val boxName = savingContext.resolveStoredValue(transfer.properties["name"], directory)
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            // Find which sidecar properties this box has
            val properties = sidecarProperties.filter { prop ->
                val value = savingContext.resolveStoredValue(transfer.properties[prop], directory)
                value is String && value.trim().isNotEmpty()
            }

            if (properties.isEmpty()) {
                boxName
            } else {
                "$boxName/{${properties.joinToString(",")}}"
            }
        }

        // Compress repeated names to reduce noise (e.g., "Untitled x5")
        val boxDescriptions = LinkedHashMap<String, Int>().apply {
            rawDescriptions.forEach { desc ->
                this[desc] = (this[desc] ?: 0) + 1
            }
        }.map { (desc, count) ->
            if (count > 1) "$desc × $count" else desc
        }

        if (boxDescriptions.isNotEmpty()) {
            if (docBuilder.isNotEmpty()) {
                docBuilder.appendLine()
            }
            docBuilder.append("Boxes: ${boxDescriptions.joinToString(", ")}")
        }

        return TemplateMetadata(
            name = templateName,
            lastModified = lastModified,
            boxDocumentation = docBuilder.toString().trim(),
            source = source
        )
    } catch (e: Exception) {
        lprintln("warning,template", "Failed to extract metadata for $templateName: ${e.message}")
        return null
    }
}

/** Populate the process-local remote-template cache once, without delaying server startup. */
private fun initializeTemplateServers() {
    if (templateServers.isEmpty()) {
        lprintln("warning,template", "No template servers configured (use AFIELD_TEMPLATE_SERVERS environment variable)")
        return
    }

    lprintln("info,template", "Initializing template servers in background: ${templateServers.joinToString(", ")}")

    // Launch background coroutine to fetch templates from all servers
    CoroutineScope(Dispatchers.IO).launch {
        templateServers.forEach { serverUrl ->
            try {
                val templates = fetchTemplatesFromServer(serverUrl)
                if (templates.isNotEmpty()) {
                    remoteTemplatesCache[serverUrl] = templates
                    lprintln("info,template", "Fetched ${templates.size} templates from $serverUrl")
                }
            } catch (e: Exception) {
                lprintln("error,template", "Failed to initialize template server $serverUrl: ${e.message}")
            }
        }
    }
}

/** One folder the `cmd+t` palette reads templates from; [suffix] renames a template a higher folder shadows. */
internal data class TemplateRoot(val dir: String, val source: String, val suffix: String)

/** A local template the palette offers under [name], read from [path] (an `.edn` file or a document folder). */
internal data class TemplateEntry(val name: String, val path: String, val source: String)

/**
 * The folders `cmd+t` reads, highest precedence first:
 *
 * 1. `$home/templates` — saved with **Save as template** in this workspace ("Workspace template");
 * 2. the bundled `$app/templates` ("Bundled template");
 * 3. `src/personal/templates` in Field's working directory ("Personal template");
 * 4. [afield.document.workspace.personalTemplatesDir], normally `~/.afield/templates` ("Personal template");
 * 5. the documents directly in the workspace [home] ("Workspace file").
 *
 * Remote servers come after all of these. Bundled outranks personal for the same reason as on the
 * Welcome screen ([afield.document.workspace.documentTemplateRoots]): a name means the same thing in
 * every checkout. Labels, documentation and the inserted copy all come from the same entry.
 */
internal fun templateRoots(
    home: String,
    app: String,
    checkoutPersonal: String = File(System.getProperty("user.dir"), "src/personal/templates").path,
    personal: String = afield.document.workspace.personalTemplatesDir().path,
): List<TemplateRoot> = listOf(
    TemplateRoot("$home/templates", "workspace-template", "workspace-template"),
    TemplateRoot("$app/templates", "bundled-template", "bundled"),
    TemplateRoot(checkoutPersonal, "personal-template", "checkout"),
    TemplateRoot(personal, "personal-template", "personal"),
    TemplateRoot(home, "workspace", "workspace"),
)

/**
 * Every local template in [roots], one entry per name. The first root holding a name owns it; the
 * same name in a later root is offered as `<name>-<suffix>` (`greeter-personal`) unless that is taken
 * too, so nothing is listed twice and nothing personal silently vanishes. A root reached twice (the
 * personal folder pointed at the workspace's `templates/`, say) is read once. [currentDocument], a
 * document folder, is left out: inserting it would copy the document into itself.
 */
internal fun localTemplateEntries(roots: List<TemplateRoot>, currentDocument: String? = null): List<TemplateEntry> {
    val current = currentDocument?.let { File(it).absoluteFile.normalize() }
    val seenRoots = HashSet<String>()
    val byName = LinkedHashMap<String, TemplateEntry>()
    for (root in roots) {
        val dir = File(root.dir)
        if (!dir.isDirectory || !seenRoots.add(dir.canonicalPath)) continue
        for (found in SavingContext().listTemplates(dir.path)) {
            val path = resolveTemplatePath(dir.path, found)
            if (current != null && File(path).absoluteFile.normalize() == current) continue
            val name = if (found !in byName) found else "$found-${root.suffix}"
            if (name !in byName) byName[name] = TemplateEntry(name, path, root.source)
        }
    }
    return byName.values.toList()
}

/**
 * Every template the palette offers, with its documentation: [localTemplateEntries] over
 * [templateRoots], then cached remote names no local template already claims. Reads every
 * document's `metadata.edn` and sidecars, so in a real workspace this is hundreds of files.
 */
internal fun listTemplatesWithMetadata(
    currentDocument: String? = null,
    roots: List<TemplateRoot> = templateRoots(home, app),
    remote: List<String> = remoteTemplatesCache.values.flatten(),
): Directory {
    val local = localTemplateEntries(roots, currentDocument)
    val taken = local.mapTo(HashSet()) { it.name }
    val remoteNames = remote.filter { taken.add(it) }

    val templateMetadataList = local.map { entry ->
        val metadataFile = File(entry.path).let { if (it.isFile) it else File(it, SavingContext.METADATA_EDN) }
        extractTemplateMetadata(metadataFile.path, entry.name, entry.source)
            ?: TemplateMetadata(name = entry.name, lastModified = 0, boxDocumentation = "", source = entry.source)
    } + remoteNames.map {
        // For remote templates, we don't have metadata yet
        TemplateMetadata(name = it, lastModified = 0, boxDocumentation = "", source = "remote-template")
    }

    return Directory(
        command = "list",
        value = local.map { it.name } + remoteNames,
        metadata = Json.encodeToJsonElement(templateMetadataList)
    )
}

/** The palette's reply to `request-templates`: [list]'s result, or an empty list when it throws. */
internal fun templateListingOrEmpty(list: () -> Directory = ::listTemplatesWithMetadata): Directory =
    runCatching(list)
        .onFailure { lprintln("warning,template", "Failed to list templates: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrElse { Directory(command = "list") }

@OptIn(ExperimentalUuidApi::class)
@RunsAt(installHandlers)
fun templating() {
    // Initialize remote template servers in the background
    initializeTemplateServers()
    handlers["request-templates"] = mutableSetOf({ message, reply ->
        // This runs on the Netty event loop serving the page's websocket, and the listing reads every
        // document in the workspace. It touches no session state, so it runs off the loop and replies
        // from there; a throw still replies, with an empty list, so the palette is never left waiting.
        val currentDocument = thisSessionInfo.get()?.filename
        templateIoScope.launch {
            reply(Message("list").payload(templateListingOrEmpty { listTemplatesWithMetadata(currentDocument) }))
        }
        Handled.yes
    })

    handlers["insert-template"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val localTemplates = localTemplateEntries(templateRoots(home, app)).associateBy { it.name }

        val session = thisSession.get()
        val si = thisSessionInfo.get()

        // Inserts the whole request as one unit (the mouse translation is over all of its boxes).
        // `downloaded` holds each remote template's (server, metadata.edn), or null when no server served it.
        fun insert(downloaded: Map<String, Pair<String, String>?>) {
            val loadedBoxes = exec.value.flatMap { templateName ->
                val local = localTemplates[templateName]
                val boxes = if (local != null) {
                    insertTemplateInto(si, templateName, local.path)
                } else {
                    val remote = downloaded[templateName]
                    if (remote == null) {
                        if (templateName in downloaded) notify("Could not download template '$templateName'", "error")
                        else notify("No template named '$templateName'", "error")
                        emptySet()
                    } else {
                        val (serverUrl, ednContent) = remote
                        // Use same ID transformation as cloneInto to generate new UUIDs
                        val seen = mutableMapOf<String, String>("root" to "root")
                        SavingContext().loadFromEdnText(
                            ednContent,
                            si.filename,
                            filterId = { id ->
                                seen.computeIfAbsent(id) { Uuid.random().toString() }
                            }
                        ).also { lprintln("info,template", "Imported template '$templateName' from remote server $serverUrl") }
                    }
                }

                // Set templateId on all loaded boxes
                boxes.forEach { box ->
                    box.templateId = templateName
                }

                boxes
            }

            // Calculate center of mass and translate boxes to mouse position if provided
            if (loadedBoxes.isNotEmpty() && exec.mouseX != null && exec.mouseY != null) {
                // Only translate top-level boxes, not nested children. Nested boxes have frames that are
                // relative to their parent's position (offset by -parent.x, -(parent.y + parent.h)).
                // Translating them would incorrectly add the offset twice since they move with their parent.
                val nestedBoxes = loadedBoxes.flatMap { it.nested }.toSet()
                val topLevelBoxes = loadedBoxes.filter { it !in nestedBoxes }

                if (topLevelBoxes.isNotEmpty()) {
                    // Calculate center of mass (average of all top-level box centers)
                    val centers = topLevelBoxes.map { box ->
                        val frame = box.frame
                        Pair(frame.cx, frame.cy)
                    }

                    val centerX = centers.map { it.first }.average()
                    val centerY = centers.map { it.second }.average()

                    // Calculate translation needed to move center of mass to mouse position
                    val deltaX = exec.mouseX - centerX
                    val deltaY = exec.mouseY - centerY

                    // Apply translation only to top-level boxes
                    topLevelBoxes.forEach { box ->
                        val frame = box.frame
                        box.frame = frame.copy(x = frame.x + deltaX, y = frame.y + deltaY)
                    }

                    lprintln("info,templating", "Translated ${topLevelBoxes.size} boxes by ($deltaX, $deltaY) to center at mouse position (${exec.mouseX}, ${exec.mouseY})")
                }
            }

            loadedBoxes.forEach { box ->
                box.resyncAll()
            }
            si.topologyGeneration++
            si.autorunBoxes(loadedBoxes)
        }

        val remoteNames = exec.value.filter { name ->
            name !in localTemplates && remoteTemplatesCache.values.any { name in it }
        }
        if (remoteNames.isEmpty()) {
            insert(emptyMap())
        } else {
            // The download leaves this socket's event loop; the insertion comes back to the session, so it
            // stays serialised with the session's other handlers. Messages that arrive during the download
            // are handled first, which is the point: the socket keeps answering, keepalive pongs included.
            templateIoScope.launch {
                val downloaded = remoteNames.associateWith { name ->
                    remoteTemplatesCache.entries.filter { name in it.value }.firstNotNullOfOrNull { (serverUrl, _) ->
                        downloadTemplateFromServer(serverUrl, name)?.let { serverUrl to it }
                    }
                }
                session.launch {
                    thisSession.set(session)
                    thisSessionInfo.set(si)
                    try {
                        insert(downloaded)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                    }
                }
            }
        }

        Handled.yes
    })

    handlers["save-as-template"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<SaveAsTemplate>(message.payload!!)

        val selectedBoxes = request.uids.mapNotNull { uid -> box(uid) }.toSet()
        if (selectedBoxes.isEmpty()) {
            notify("No valid boxes found for template", "error")
        } else {
            try {
                // Recursively collect all nested boxes
                val boxesWithNested = collectAllNestedBoxes(selectedBoxes)
                val templatesDir = "$home/templates"
                File(templatesDir).mkdirs()
                SavingContext().saveAsTemplate(request.templateName, boxesWithNested, templatesDir)
                val countMessage = formatBoxCountMessage(selectedBoxes.size, boxesWithNested.size)
                notify("Template saved: ${request.templateName} ($countMessage)", "success")
            } catch (e: Exception) {
                notify("Failed to save template: ${e.message}", "error")
            }
        }
        Handled.yes
    })
}

/**
 * Clones the local template at [path] into [info] (the session's current document) under fresh ids and
 * tags each box with `templateId = name`. A template's header names become the receiving document's,
 * so its boxes keep saving them. Leaves resync, placement and autorun to the caller.
 */
internal fun insertTemplateInto(info: SessionInformation, name: String, path: String): Set<afield.document.graph.Box> =
    SavingContext().cloneTemplateInto(path).also { boxes ->
        afield.document.graph.Box.recordDocumentPersistentProperties(
            info.filename,
            // `load` records a standalone `.edn` template's header against its folder
            afield.document.graph.Box.persistentPropertiesFor(
                File(path).let { if (it.isFile) it.absoluteFile.parent else it.path }))
        boxes.forEach { it.templateId = name }
    }

/** Where `insert-template` reads the local template [name] from, or null (which leaves the remote servers). */
internal fun localTemplatePath(name: String, roots: List<TemplateRoot>): String? =
    localTemplateEntries(roots).firstOrNull { it.name == name }?.path

private fun resolveTemplatePath(baseDir: String, name: String): String {
    val base = baseDir.trimEnd('/')
    val withExt = "$base/$name.edn"
    return if (File(withExt).isFile) withExt else "$base/$name"
}

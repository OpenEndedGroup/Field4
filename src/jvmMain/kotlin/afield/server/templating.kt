package afield.server

import afield.*
import afield.logger.lprintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class RemoteTemplate(val name: String, val has_edn: Boolean)

@Serializable
data class TemplateMetadata(
    val name: String,
    val lastModified: Long,
    val boxDocumentation: String // Formatted documentation from box metadata
)

// Configuration for template servers - comma-separated list of URLs
val templateServers: List<String> by lazy {
    val serversEnv = System.getenv("AFIELD_TEMPLATE_SERVERS") ?: System.getProperty("afield.templateServers") ?: ""
    if (serversEnv.isBlank()) emptyList()
    else serversEnv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

// Cache for remote templates - populated on startup
private val remoteTemplatesCache = ConcurrentHashMap<String, List<String>>()

// HTTP client for fetching templates
private val httpClient = HttpClient.newBuilder().build()

/**
 * Fetch templates from a remote server's /list endpoint
 */
private fun fetchTemplatesFromServer(serverUrl: String): List<String> {
    try {
        val listUrl = if (serverUrl.endsWith("/")) "${serverUrl}list" else "$serverUrl/list"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(listUrl))
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
 * and creates a formatted documentation string.
 */
private fun extractTemplateMetadata(templatePath: String, templateName: String): TemplateMetadata? {
    try {
        val file = File(templatePath)
        if (!file.exists()) return null
        
        val lastModified = file.lastModified()
        val ednContent = file.readText()
        
        // Load boxes to extract metadata
        val directory = file.parentFile?.absolutePath ?: File(".").absolutePath
        val boxes = SavingContext().loadFromEdnText(ednContent, directory) { it }
        
        // Sort boxes by frame.y (increasing)
        val sortedBoxes = boxes.sortedBy { it.frame.y }
        
        // Sidecar-able properties (excluding "code")
        val sidecarProperties = listOf("vertex", "fragment", "compute", "task", "mesh")
        
        // Build documentation from metadata properties
        val docBuilder = StringBuilder()
        
        sortedBoxes.forEach { box ->
            // Get metadata from the box
            val metadata = box.metadata
            if (metadata.isNotEmpty()) {
                // Parse YAML metadata
                try {
                    val parsed = parseMetadata(metadata)
                    
                    // Extract documentation fields if they exist
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
        val boxDescriptions = sortedBoxes.mapNotNull { box ->
            val boxName = box.name?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            
            // Find which sidecar properties this box has
            val properties = sidecarProperties.filter { prop ->
                val value = box.getPropertyRaw(prop)
                value is String && value.trim().isNotEmpty()
            }
            
            if (properties.isEmpty()) {
                boxName
            } else {
                "$boxName/{${properties.joinToString(",")}}"
            }
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
            boxDocumentation = docBuilder.toString().trim()
        )
    } catch (e: Exception) {
        lprintln("warning,template", "Failed to extract metadata for $templateName: ${e.message}")
        return null
    }
}

/**
 * Initialize template server querying in the background
 */
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

@OptIn(ExperimentalUuidApi::class)
@RunsAt(installHandlers)
fun templating() {
    // Initialize remote template servers in the background
    initializeTemplateServers()
    handlers["request-templates"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templates2 = SavingContext().listTemplates(app + "/templates/")
        val templates = SavingContext().listTemplates(home)
        val templatesHome = SavingContext().listTemplates("$home/templates")

        // Include remote templates from all configured servers
        val remoteTemplates = remoteTemplatesCache.values.flatten()
        
        val allTemplateNames = templates + templatesHome + templates2 + remoteTemplates

        // Extract metadata for each template
        val templateMetadataList = allTemplateNames.mapNotNull { templateName ->
            // Try to find the template file in order of preference
            val paths = listOf(
                "$home/$templateName.edn" to "$home",
                "$home/$templateName/metadata.edn" to "$home/$templateName",
                "$home/templates/$templateName.edn" to "$home/templates",
                "$home/templates/$templateName/metadata.edn" to "$home/templates/$templateName",
                "$app/templates/$templateName.edn" to "$app/templates",
                "$app/templates/$templateName/metadata.edn" to "$app/templates/$templateName"
            )
            
            for ((templateFile, directory) in paths) {
                val file = File(templateFile)
                if (file.exists()) {
                    return@mapNotNull extractTemplateMetadata(templateFile, templateName)
                }
            }
            
            // For remote templates, we don't have metadata yet
            TemplateMetadata(
                name = templateName,
                lastModified = 0,
                boxDocumentation = ""
            )
        }

        reply(Message("list").payload(
            Directory(
                command = "list", 
                value = allTemplateNames,
                metadata = Json.encodeToJsonElement(templateMetadataList)
            )
        ))

        Handled.yes
    })

    handlers["insert-template"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templatesHome = SavingContext().listTemplates("$home/templates")
        val templatesApp = SavingContext().listTemplates("$app/templates")

        val si = thisSessionInfo.get()

        val loadedBoxes = exec.value.flatMap { templateName ->
            // Try local templates first
            if (templatesHome.contains(templateName)) {
                SavingContext().cloneInto("$home/$templateName")
            } else if (templatesApp.contains(templateName)) {
                SavingContext().cloneInto("$app/templates/$templateName")
            } else {
                // Try remote templates
                var boxes = emptySet<afield.Box>()
                for ((serverUrl, serverTemplates) in remoteTemplatesCache) {
                    if (serverTemplates.contains(templateName)) {
                        val ednContent = downloadTemplateFromServer(serverUrl, templateName)
                        if (ednContent != null) {
                            // Use same ID transformation as cloneInto to generate new UUIDs
                            @OptIn(ExperimentalUuidApi::class)
                            val seen = mutableMapOf<String, String>("root" to "root")
                            boxes = SavingContext().loadFromEdnText(ednContent, si.filename) { id ->
                                seen.computeIfAbsent(id) { Uuid.random().toString() }
                            }
                            lprintln("info,template", "Imported template '$templateName' from remote server $serverUrl")
                            break
                        }
                    }
                }
                boxes
            }
        }
        
        // Calculate center of mass and translate boxes to mouse position if provided
        if (loadedBoxes.isNotEmpty() && exec.mouseX != null && exec.mouseY != null) {
            // Calculate center of mass (average of all box centers)
            val centers = loadedBoxes.map { box ->
                val frame = box.frame
                Pair(frame.cx, frame.cy)
            }
            
            val centerX = centers.map { it.first }.average()
            val centerY = centers.map { it.second }.average()
            
            // Calculate translation needed to move center of mass to mouse position
            val deltaX = exec.mouseX - centerX
            val deltaY = exec.mouseY - centerY
            
            // Apply translation to all boxes
            loadedBoxes.forEach { box ->
                val frame = box.frame
                box.frame = frame.copy(x = frame.x + deltaX, y = frame.y + deltaY)
            }
            
            lprintln("Translated ${loadedBoxes.size} boxes by ($deltaX, $deltaY) to center at mouse position (${exec.mouseX}, ${exec.mouseY})")
        }
        
        loadedBoxes.forEach {
            it.resyncAll()
        }
        si.topologyGeneration++

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

private fun resolveTemplatePath(baseDir: String, name: String): String {
    val base = baseDir.trimEnd('/')
    val withExt = "$base/$name.edn"
    return if (File(withExt).isFile) withExt else "$base/$name"
}

package afield.server

import afield.Directory
import afield.Handled
import afield.Message
import afield.RunsAt
import afield.SaveAsTemplate
import afield.SavingContext
import afield.app
import afield.box
import afield.handlers
import afield.home
import afield.installHandlers
import afield.notify
import afield.payload
import afield.thisSessionInfo
import afield.logger.lprintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromString
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
            lprintln("WARNING: Failed to fetch templates from $serverUrl - Status: ${response.statusCode()}")
        }
    } catch (e: Exception) {
        lprintln("WARNING: Error fetching templates from $serverUrl: ${e.message}")
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
            lprintln("WARNING: Failed to download template $templateName from $serverUrl - Status: ${response.statusCode()}")
        }
    } catch (e: Exception) {
        lprintln("WARNING: Error downloading template $templateName from $serverUrl: ${e.message}")
    }
    return null
}

/**
 * Initialize template server querying in the background
 */
private fun initializeTemplateServers() {
    if (templateServers.isEmpty()) {
        lprintln("No template servers configured (use AFIELD_TEMPLATE_SERVERS environment variable)")
        return
    }
    
    lprintln("Initializing template servers in background: ${templateServers.joinToString(", ")}")
    
    // Launch background coroutine to fetch templates from all servers
    CoroutineScope(Dispatchers.IO).launch {
        templateServers.forEach { serverUrl ->
            try {
                val templates = fetchTemplatesFromServer(serverUrl)
                if (templates.isNotEmpty()) {
                    remoteTemplatesCache[serverUrl] = templates
                    lprintln("Fetched ${templates.size} templates from $serverUrl")
                }
            } catch (e: Exception) {
                lprintln("ERROR: Failed to initialize template server $serverUrl: ${e.message}")
            }
        }
    }
}

@RunsAt(installHandlers)
fun templating() {
    // Initialize remote template servers in the background
    initializeTemplateServers()
    handlers["request-templates"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templates2 = SavingContext().listTemplates(app + "/templates/")
        val templates = SavingContext().listTemplates(home)
        
        // Include remote templates from all configured servers
        val remoteTemplates = remoteTemplatesCache.values.flatten()
        
        reply(Message("list").payload(Directory(command = "list", value = templates + templates2 + remoteTemplates)))

        Handled.yes
    })

    handlers["insert-template"] = mutableSetOf({ message, reply ->

        val exec = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val templatesHome = SavingContext().listTemplates("$home/templates")
        val templatesApp = SavingContext().listTemplates("$app/templates")

        val si = thisSessionInfo.get()

        exec.value.flatMap { templateName ->
            // Try local templates first
            if (templates.contains(templateName)) {
                SavingContext().cloneInto("$home/$templateName")
            } else if (templates2.contains(templateName)) {
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
                            lprintln("Imported template '$templateName' from remote server $serverUrl")
                            break
                        }
                    }
                }
                boxes
            }
        }.forEach {
            it.resyncAll()
        }
        si.topologyGeneration++

        Handled.yes
    })

    handlers["save-as-template"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<SaveAsTemplate>(message.payload!!)
        
        val boxes = request.uids.mapNotNull { uid -> box(uid) }.toSet()
        if (boxes.isEmpty()) {
            notify("No valid boxes found for template", "error")
        } else {
            try {
                val templatesDir = "$home/templates"
                File(templatesDir).mkdirs()
                SavingContext().saveAsTemplate(request.templateName, boxes, templatesDir)
                notify("Template saved: ${request.templateName}", "success")
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


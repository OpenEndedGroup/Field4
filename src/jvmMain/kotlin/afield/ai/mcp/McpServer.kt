@file:Suppress("unused")

package afield.ai.mcp

import afield.ai.core.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

/* -------------------------------------------------------------------------- */
/*                               JSON-RPC types                               */
/* -------------------------------------------------------------------------- */

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(val code: Int, val message: String, val data: JsonElement? = null)

/* -------------------------------------------------------------------------- */
/*                                MCP payloads                                */
/* -------------------------------------------------------------------------- */

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val serverInfo: Implementation,
    val capabilities: JsonObject,
    val instructions: String? = null
)

@Serializable
data class Implementation(val name: String, val version: String)

@Serializable
data class ToolsListResult(
    val tools: List<ToolDef>,
    val nextCursor: String? = null
)

@Serializable
data class ToolDef(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null,
    val annotations: JsonObject? = null
)

/* -------------------------------------------------------------------------- */
/*                          Tool content                                       */
/* -------------------------------------------------------------------------- */

@Serializable
sealed interface ToolContent

@Serializable
@SerialName("text")
data class ToolText(
    val type: String = "text",
    val text: String
) : ToolContent

@Serializable
@SerialName("image")
data class ToolImage(
    val type: String = "image",
    val mimeType: String,
    val data: String? = null,  // base64 payload
    val uri: String? = null,   // http(s) or data: URI
    val alt: String? = null
) : ToolContent

@Serializable
data class ToolCallResult(
    val content: List<ToolContent>,
    val isError: Boolean = false
)

/* -------------------------------------------------------------------------- */
/*                                  SSE utils                                 */
/* -------------------------------------------------------------------------- */

private fun String.sseEvent(id: String? = null): String {
    val dataLines = this.lines().joinToString("\n") { "data: $it" }
    val idLine = id?.let { "id: $it\n" } ?: ""
    return idLine + dataLines + "\n\n"
}

// Use different discriminator so "type" remains the MCP content type.
private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "kind"
}

/* -------------------------------------------------------------------------- */
/*                               Origin checking                               */
/* -------------------------------------------------------------------------- */

private fun isAllowedOrigin(origin: String?): Boolean {
    if (origin == null) return true
    val o = origin.lowercase()
    return o.startsWith("http://localhost") || o.startsWith("http://127.0.0.1")
}

/* -------------------------------------------------------------------------- */
/*                          AI4 Tool Integration                              */
/* -------------------------------------------------------------------------- */

/**
 * MCP Server that exposes AI4 tools via Model Context Protocol.
 * 
 * This server enables external AI tools like Claude and Codex to access
 * Field4's rich set of AI assistant tools through a standardized protocol.
 */
class McpServer(
    private val host: String = "127.0.0.1",
    private val port: Int = 3000,
    private val toolContext: ToolExecutionContext = ToolExecutionContext()
) {
    private val baseUrl = "http://$host:$port"

    /**
     * Converts AI4 tools to MCP tool definitions
     */
    private fun buildToolDefinitions(): List<ToolDef> {
        return listOf(
            // Commentary tool
            ToolDef(
                name = "commentary",
                description = "Assistant commentary or design notes about the code.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Commentary text"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("text")) }
                }
            ),
            
            // List attachments
            ToolDef(
                name = "list_attachments",
                description = "List all attached context files available for this request.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {}
                }
            ),
            
            // Get attachment
            ToolDef(
                name = "get_attachment",
                description = "Retrieve the code content of an attached file by its name.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("file") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("File name to retrieve"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("file")) }
                }
            ),
            
            // List definitions (Kotlin-only)
            ToolDef(
                name = "list_definitions",
                description = "List all definitions visible from the current Kotlin file, including associated shaders.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {}
                }
            ),
            
            // List file contents
            ToolDef(
                name = "list_contents",
                description = "Return full contents for available text properties (code and shaders) with file refs and languages.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {}
                }
            ),
            
            // Edit file
            ToolDef(
                name = "edit_file",
                description = "Edit another file by specifying its name and new content.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("file") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("File name to edit"))
                        }
                        putJsonObject("newContent") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("New content for the file"))
                        }
                    }
                    putJsonArray("required") { 
                        add(JsonPrimitive("file"))
                        add(JsonPrimitive("newContent"))
                    }
                }
            ),
            
            // Workspace symbol search
            ToolDef(
                name = "workspace_symbol_search",
                description = "Looks up symbols in the IDE workspace index.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Search query"))
                        }
                        putJsonObject("limit") {
                            put("type", JsonPrimitive("integer"))
                            put("description", JsonPrimitive("Maximum number of results (default: 25)"))
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("query")) }
                }
            ),
            
            // Check code
            ToolDef(
                name = "check_code",
                description = "Check code for compilation/syntax errors without executing it. Supports Kotlin, Python, Clojure, and GLSL.",
                inputSchema = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    putJsonObject("properties") {
                        putJsonObject("code") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Source code to check"))
                        }
                        putJsonObject("language") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Programming language (Kotlin, Python, Clojure, GLSL)"))
                        }
                        putJsonObject("property") {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Optional property name (for GLSL: vertex, fragment, compute, mesh, task)"))
                        }
                    }
                    putJsonArray("required") { 
                        add(JsonPrimitive("code"))
                        add(JsonPrimitive("language"))
                    }
                }
            )
        )
    }

    /**
     * Execute tool calls using the AI4 tool execution context
     */
    private suspend fun executeTool(name: String, args: JsonObject): ToolCallResult {
        return try {
            when (name) {
                "commentary" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    ToolCallResult(content = listOf(ToolText(text = "Commentary noted: $text")))
                }
                
                "list_attachments" -> {
                    val attachments = toolContext.attachments.map { att ->
                        "${att.fileName} (${att.language})"
                    }.joinToString("\n")
                    ToolCallResult(content = listOf(ToolText(text = "Available attachments:\n$attachments")))
                }
                
                "get_attachment" -> {
                    val file = args["file"]?.jsonPrimitive?.contentOrNull ?: ""
                    val response = toolContext.resolveAttachment?.invoke(file)
                    if (response != null) {
                        val text = """
                            File: ${response.file}
                            Language: ${response.language}
                            Content:
                            ${response.content}
                        """.trimIndent()
                        ToolCallResult(content = listOf(ToolText(text = text)))
                    } else {
                        ToolCallResult(
                            content = listOf(ToolText(text = "File not found: $file")),
                            isError = true
                        )
                    }
                }
                
                "list_definitions" -> {
                    val result = toolContext.listDefinitions?.invoke()
                    if (result != null) {
                        val text = result.definitions.joinToString("\n") { def ->
                            "${def.name}: ${def.type} (${def.fileRef}, ${def.language})"
                        }
                        ToolCallResult(content = listOf(ToolText(text = "Definitions:\n$text")))
                    } else {
                        ToolCallResult(
                            content = listOf(ToolText(text = "No definitions provider available")),
                            isError = true
                        )
                    }
                }
                
                "list_contents" -> {
                    val result = toolContext.listFileContents?.invoke()
                    if (result != null) {
                        val text = result.contents.joinToString("\n\n") { entry ->
                            """
                            File: ${entry.fileRef}
                            Property: ${entry.property}
                            Language: ${entry.language}
                            Content:
                            ${entry.content}
                            """.trimIndent()
                        }
                        ToolCallResult(content = listOf(ToolText(text = text)))
                    } else {
                        ToolCallResult(
                            content = listOf(ToolText(text = "No file contents provider available")),
                            isError = true
                        )
                    }
                }
                
                "edit_file" -> {
                    val file = args["file"]?.jsonPrimitive?.contentOrNull ?: ""
                    val newContent = args["newContent"]?.jsonPrimitive?.contentOrNull ?: ""
                    val result = toolContext.editOtherFile?.invoke(
                        EditFileRequest(file, newContent)
                    )
                    if (result != null && result.success) {
                        ToolCallResult(content = listOf(ToolText(text = "File edited successfully: $file")))
                    } else {
                        ToolCallResult(
                            content = listOf(ToolText(text = "Failed to edit file: ${result?.message ?: "Unknown error"}")),
                            isError = true
                        )
                    }
                }
                
                "workspace_symbol_search" -> {
                    val query = args["query"]?.jsonPrimitive?.contentOrNull ?: ""
                    val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 25
                    val result = toolContext.workspaceSymbolSearch?.invoke(
                        WorkspaceSymbolSearch(query, limit)
                    )
                    if (result != null) {
                        val text = result.matches.joinToString("\n") { match ->
                            "${match.symbol} (${match.kind}) - ${match.locationHint}"
                        }
                        ToolCallResult(content = listOf(ToolText(text = "Search results:\n$text")))
                    } else {
                        ToolCallResult(
                            content = listOf(ToolText(text = "No workspace search provider available")),
                            isError = true
                        )
                    }
                }
                
                "check_code" -> {
                    val code = args["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    val language = args["language"]?.jsonPrimitive?.contentOrNull ?: ""
                    val property = args["property"]?.jsonPrimitive?.contentOrNull
                    val result = toolContext.checkCode?.invoke(
                        CheckCodeRequest(code, language, property)
                    )
                    if (result != null) {
                        if (result.success) {
                            ToolCallResult(content = listOf(ToolText(text = "✓ Code compiles successfully (no errors)")))
                        } else {
                            val errorText = result.errors.joinToString("\n") { error ->
                                val location = if (error.line != null) {
                                    "Line ${error.line}${if (error.column != null) ", Column ${error.column}" else ""}"
                                } else {
                                    "Location unknown"
                                }
                                "[$location] ${error.severity.uppercase()}: ${error.message}"
                            }
                            ToolCallResult(
                                content = listOf(ToolText(text = "✗ Compilation errors:\n$errorText")),
                                isError = false // Not a tool error, just compilation errors
                            )
                        }
                    } else {
                        ToolCallResult(
                            content = listOf(ToolText(text = "No code checker provider available")),
                            isError = true
                        )
                    }
                }
                
                else -> {
                    ToolCallResult(
                        content = listOf(ToolText(text = "Unknown tool: $name")),
                        isError = true
                    )
                }
            }
        } catch (e: Exception) {
            ToolCallResult(
                content = listOf(ToolText(text = "Tool execution error: ${e.message}")),
                isError = true
            )
        }
    }

    /**
     * Start the MCP server
     */
    fun start(wait: Boolean = true) {
        embeddedServer(Netty, host = host, port = port) {
            install(ContentNegotiation) { json(json) }
            install(CORS) {
                allowHeaders { true }
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                anyHost()
            }

            routing {
                route("/mcp") {
                    // GET: background SSE (heartbeats)
                    get {
                        val origin = call.request.headers[HttpHeaders.Origin]
                        if (!isAllowedOrigin(origin)) {
                            call.respond(HttpStatusCode.Forbidden, "Forbidden origin")
                            return@get
                        }
                        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            var counter = 0L
                            while (coroutineContext.isActive) {
                                val evt = JsonObject(
                                    mapOf(
                                        "jsonrpc" to JsonPrimitive("2.0"),
                                        "method" to JsonPrimitive("notifications/heartbeat"),
                                        "params" to JsonObject(mapOf("seq" to JsonPrimitive(counter++)))
                                    )
                                )
                                write(json.encodeToString(JsonObject.serializer(), evt).sseEvent(id = "hb-$counter"))
                                flush()
                                delay(15_000)
                            }
                        }
                    }

                    // POST: JSON-RPC → stream responses as SSE
                    post {
                        val origin = call.request.headers[HttpHeaders.Origin]
                        if (!isAllowedOrigin(origin)) {
                            call.respond(HttpStatusCode.Forbidden, "Forbidden origin")
                            return@post
                        }

                        val body = call.receiveText().trim()
                        if (body.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, "Empty body")
                            return@post
                        }

                        val el = runCatching { json.parseToJsonElement(body) }.getOrElse {
                            call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                            return@post
                        }
                        
                        val messages: List<JsonObject> = when (el) {
                            is JsonObject -> listOf(el)
                            is JsonArray -> el.map { it.jsonObject }
                            else -> {
                                call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC envelope")
                                return@post
                            }
                        }
                        
                        val hasRequest = messages.any { it["method"] != null && it["id"] != null }
                        if (!hasRequest) {
                            call.respond(HttpStatusCode.Accepted)
                            return@post
                        }

                        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                        call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                            for (msg in messages) {
                                val req = runCatching { json.decodeFromJsonElement<JsonRpcRequest>(msg) }.getOrNull()
                                if (req == null || req.method.isBlank() || req.id == null) {
                                    val err = JsonRpcResponse(
                                        id = msg["id"],
                                        error = JsonRpcError(-32600, "Invalid Request")
                                    )
                                    write(json.encodeToString(err).sseEvent())
                                    flush()
                                    continue
                                }

                                val progressStart = JsonObject(
                                    mapOf(
                                        "jsonrpc" to JsonPrimitive("2.0"),
                                        "method" to JsonPrimitive("notifications/progress"),
                                        "params" to JsonObject(
                                            mapOf(
                                                "forRequestId" to req.id!!,
                                                "stage" to JsonPrimitive("started")
                                            )
                                        )
                                    )
                                )
                                write(json.encodeToString(progressStart).sseEvent())
                                flush()

                                val response: JsonRpcResponse = when (req.method) {
                                    "initialize" -> {
                                        val init = InitializeResult(
                                            protocolVersion = "2025-03-26",
                                            serverInfo = Implementation("field4-mcp", "1.0.0"),
                                            capabilities = buildJsonObject {
                                                putJsonObject("tools") { 
                                                    put("listChanged", JsonPrimitive(false)) 
                                                }
                                            },
                                            instructions = "Field4 AI tools: access to attachments, definitions, file contents, and workspace search."
                                        )
                                        JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(init))
                                    }

                                    "tools/list" -> {
                                        val tools = buildToolDefinitions()
                                        JsonRpcResponse(
                                            id = req.id,
                                            result = json.encodeToJsonElement(ToolsListResult(tools))
                                        )
                                    }

                                    "tools/call" -> {
                                        val p = req.params?.jsonObject ?: buildJsonObject { }
                                        val name = p["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                        val args = p["arguments"]?.jsonObject ?: buildJsonObject { }

                                        val result = executeTool(name, args)
                                        JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(result))
                                    }

                                    else -> JsonRpcResponse(
                                        id = req.id,
                                        error = JsonRpcError(-32601, "Method not found: ${req.method}")
                                    )
                                }

                                write(json.encodeToString(response).sseEvent())
                                flush()

                                val done = JsonObject(
                                    mapOf(
                                        "jsonrpc" to JsonPrimitive("2.0"),
                                        "method" to JsonPrimitive("notifications/progress"),
                                        "params" to JsonObject(
                                            mapOf(
                                                "forRequestId" to req.id,
                                                "stage" to JsonPrimitive("finished")
                                            )
                                        )
                                    )
                                )
                                write(json.encodeToString(done).sseEvent())
                                flush()
                            }
                        }
                    }
                }
            }
        }.start(wait = wait)
    }
}

/**
 * Standalone main function to run MCP server
 */
fun main() {
    println("Starting Field4 MCP Server on http://127.0.0.1:3000/mcp")
    println("This server exposes Field4's AI tools via Model Context Protocol")
    println("Press Ctrl+C to stop")
    
    McpServer(
        host = "127.0.0.1",
        port = 3000
    ).start(wait = true)
}

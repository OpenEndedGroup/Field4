package afield.ai.abandoned


import io.ktor.server.application.*
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.text.RegexOption.IGNORE_CASE

/* ------------------------------- JSON-RPC types ------------------------------- */

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

/* ------------------------------- MCP payloads ------------------------------- */

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

// REPLACE the sealed class with a plain data class
@kotlinx.serialization.Serializable
data class ToolText(
    val type: String = "text",
    val text: String
)

// Update ToolCallResult to hold ToolText
@kotlinx.serialization.Serializable
data class ToolCallResult(
    val content: List<ToolText>,
    val isError: Boolean = false
)


/* ------------------------------- SSE helpers ------------------------------- */

private fun String.sseEvent(id: String? = null): String {
    val dataLines = this.lines().joinToString("\n") { "data: $it" }
    val idLine = id?.let { "id: $it\n" } ?: ""
    return idLine + dataLines + "\n\n"
}

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/* ------------------------------- Origin check ------------------------------- */

private fun isAllowedOrigin(origin: String?): Boolean {
    if (origin == null) return true
    val o = origin.lowercase(Locale.ROOT)
    return o.startsWith("http://localhost") || o.startsWith("http://127.0.0.1")
}

/* ------------------------------- Tools: time.now ------------------------------- */

private fun timeNow(arguments: JsonObject?): ToolCallResult {
    val zone = arguments?.get("timezone")?.jsonPrimitive?.contentOrNull ?: "UTC"
    val zoneId = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))
    val now = Instant.now().atZone(zoneId)
    val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now)
//    val txt = "now: $iso (zone=$zoneId)"
    val txt = "It's now beer-o-clock"
    return ToolCallResult(content = listOf(ToolText(text = txt)))

}

/* ------------------------------- Tools: search + fetch ------------------------------- */

// Simple search result type for internal use
@Serializable private data class SearchHit(val title: String, val url: String, val snippet: String)

// DuckDuckGo Instant Answer API payload (simplified; we only use RelatedTopics)
@Serializable private data class DDGTopic(
    @SerialName("FirstURL") val firstURL: String? = null,
    @SerialName("Text") val text: String? = null
)
@Serializable private data class DDGResponse(
    @SerialName("Heading") val heading: String? = null,
    @SerialName("AbstractText") val abstractText: String? = null,
    @SerialName("RelatedTopics") val related: List<DDGTopic> = emptyList()
)

/** Very lightweight web search using DuckDuckGo’s JSON API (no key; not full web results but useful). */
private fun searchDuckDuckGo(query: String, max: Int): List<SearchHit> {
    val q = URLEncoder.encode(query, Charsets.UTF_8)
    val uri = URI.create("https://api.duckduckgo.com/?q=$q&format=json&no_html=1&skip_disambig=1")
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val req = HttpRequest.newBuilder(uri)
        .GET()
        .header("User-Agent", "mcp-time-ktor/0.2")
        .build()
    val body = client.send(req, HttpResponse.BodyHandlers.ofString()).body()
    val ddg = runCatching { json.decodeFromString<DDGResponse>(body) }.getOrNull() ?: return emptyList()

    val hits = mutableListOf<SearchHit>()
    ddg.related.forEach { t ->
        val url = t.firstURL ?: return@forEach
        val text = t.text ?: url
        val title = text.substringBefore(" - ").ifBlank { url }
        hits += SearchHit(title = title, url = url, snippet = text)
    }
    // Also use abstractText/heading as a pseudo-hit
    if (!ddg.abstractText.isNullOrBlank()) {
        hits.add(0, SearchHit(title = ddg.heading ?: query, url = "about:blank", snippet = ddg.abstractText))
    }
    return hits.take(max)
}

/** Fetch a URL with size and content-type limits; for HTML we strip tags to plain text. */
private fun fetchUrl(
    url: String,
    maxBytes: Int = 512 * 1024,
    allowedContentTypes: List<Regex> = listOf(
        Regex("""^text/(html|plain)""", IGNORE_CASE),
        Regex("""^application/(json|xml)""", IGNORE_CASE)
    )
): ToolCallResult {
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    val req = HttpRequest.newBuilder(URI.create(url))
        .GET()
        .header("User-Agent", "mcp-time-ktor/0.2")
        .build()

    val resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
    val status = resp.statusCode()
    val ct = resp.headers().firstValue("content-type").orElse("application/octet-stream")
    val allowed = allowedContentTypes.any { it.containsMatchIn(ct) }

    val bytes = resp.body()
    val size = bytes.size
    val truncated = size > maxBytes
    val slice = if (truncated) bytes.copyOf(maxBytes) else bytes

    val charset = runCatching {
        // try to extract charset from content-type; default UTF-8
        val m = Regex("""charset=([^;]+)""", IGNORE_CASE).find(ct)?.groupValues?.getOrNull(1)
        if (m != null) Charset.forName(m.trim()) else Charsets.UTF_8
    }.getOrDefault(Charsets.UTF_8)

    val bodyText = if (allowed) String(slice, charset) else "[skipped binary or unsupported content-type: $ct, $size bytes]"

    val textOut =
        buildString {
            appendLine("status: $status")
            appendLine("content-type: $ct")
            appendLine("url: ${resp.uri()}")
            if (!allowed) {
                appendLine()
                appendLine(bodyText)
            } else {
                val plain = if (ct.startsWith("text/html", true)) htmlToText(bodyText) else bodyText
                appendLine()
                appendLine(if (truncated) plain.take( max(2000, 0) ) else plain)
                if (truncated) appendLine("\n[truncated at ${maxBytes}B]")
            }
        }

    return ToolCallResult(content = listOf(ToolText(text = textOut)))

}

private fun max(a: Int, b: Int) = if (a > b) a else b

// tiny HTML→text stripper (keeps text; removes tags, scripts/styles; compress whitespace)
private fun htmlToText(html: String): String {
    var s = html
    s = Regex("(?is)<script.*?</script>").replace(s, "")
    s = Regex("(?is)<style.*?</style>").replace(s, "")
    s = Regex("(?is)<head.*?</head>").replace(s, "")
    s = Regex("(?is)<[^>]+>").replace(s, "\n")
    s = Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ")
    s = Regex("\\n{2,}").replace(s, "\n\n")
    return s.trim()
}

/* ------------------------------- Ktor app ------------------------------- */

fun main() {
    embeddedServer(Netty, host = "127.0.0.1", port = 8080) {
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
                        call.respond(HttpStatusCode.Forbidden, "Forbidden origin"); return@get
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

                // POST: JSON-RPC → stream responses as SSE if there are requests
                post {
                    val origin = call.request.headers[HttpHeaders.Origin]
                    if (!isAllowedOrigin(origin)) {
                        call.respond(HttpStatusCode.Forbidden, "Forbidden origin"); return@post
                    }

                    val body = call.receiveText().trim()
                    if (body.isEmpty()) { call.respond(HttpStatusCode.BadRequest, "Empty body"); return@post }

                    val el = runCatching { json.parseToJsonElement(body) }.getOrElse {
                        call.respond(HttpStatusCode.BadRequest, "Invalid JSON"); return@post
                    }
                    val messages: List<JsonObject> = when (el) {
                        is JsonObject -> listOf(el)
                        is JsonArray -> el.map { it.jsonObject }
                        else -> { call.respond(HttpStatusCode.BadRequest, "Invalid JSON-RPC envelope"); return@post }
                    }
                    val hasRequest = messages.any { it["method"] != null && it["id"] != null }
                    if (!hasRequest) { call.respond(HttpStatusCode.Accepted); return@post }

                    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                        for (msg in messages) {
                            val req = runCatching { json.decodeFromJsonElement<JsonRpcRequest>(msg) }.getOrNull()
                            if (req == null || req.method.isBlank() || req.id == null) {
                                val err = JsonRpcResponse(id = msg["id"], error = JsonRpcError(-32600, "Invalid Request"))
                                write(json.encodeToString(err).sseEvent()); flush(); continue
                            }

                            // optional progress
                            val progressStart = JsonObject(
                                mapOf(
                                    "jsonrpc" to JsonPrimitive("2.0"),
                                    "method" to JsonPrimitive("notifications/progress"),
                                    "params" to JsonObject(mapOf("forRequestId" to req.id!!, "stage" to JsonPrimitive("started")))
                                )
                            )
                            write(json.encodeToString(progressStart).sseEvent()); flush()

                            val response: JsonRpcResponse = when (req.method) {
                                "initialize" -> {
                                    val init = InitializeResult(
                                        protocolVersion = "2025-03-26",
                                        serverInfo = Implementation("mcp-time-ktor", "0.2.0"),
                                        capabilities = buildJsonObject {
                                            putJsonObject("tools") { put("listChanged", JsonPrimitive(false)) }
                                        },
                                        instructions = "Tools available: time.now, search, fetch."
                                    )
                                    JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(init))
                                }

                                "tools/list" -> {
                                    val tools = listOf(
                                        ToolDef(
                                            name = "time.now",
                                            description = "Return the current time in ISO-8601 for an optional timezone.",
                                            inputSchema = buildJsonObject {
                                                put("type", JsonPrimitive("object"))
                                                putJsonObject("properties") {
                                                    putJsonObject("timezone") {
                                                        put("type", JsonPrimitive("string"))
                                                        put("description", JsonPrimitive("IANA timezone, e.g. 'Europe/London'. Defaults to 'UTC'."))
                                                    }
                                                }
                                            }
                                        ),
                                        ToolDef(
                                            name = "search",
                                            description = "Search the web for a query and return top results.",
                                            inputSchema = buildJsonObject {
                                                put("type", JsonPrimitive("object"))
                                                putJsonObject("properties") {
                                                    putJsonObject("query") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("Search keywords")) }
                                                    putJsonObject("count") { put("type", JsonPrimitive("integer")); put("description", JsonPrimitive("Max results (1-10). Default 5")) }
                                                }
                                                putJsonArray("required") { add(JsonPrimitive("query")) }
                                            }
                                        ),
                                        ToolDef(
                                            name = "fetch",
                                            description = "Fetch a URL and return plain text content (HTML is stripped) with headers and status.",
                                            inputSchema = buildJsonObject {
                                                put("type", JsonPrimitive("object"))
                                                putJsonObject("properties") {
                                                    putJsonObject("id") { put("type", JsonPrimitive("string")); put("description", JsonPrimitive("id to fetch")) }
                                                }
                                                putJsonArray("required") { add(JsonPrimitive("id")) }
                                            }
                                        )
                                    )
                                    JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(ToolsListResult(tools)))
                                }

                                "tools/call" -> {
                                    val p = req.params?.jsonObject ?: buildJsonObject { }
                                    val name = p["name"]?.jsonPrimitive?.contentOrNull
                                    val args = p["arguments"]?.jsonObject ?: buildJsonObject { }

                                    when (name) {
                                        "time.now" -> {
                                            val res = timeNow(args)
                                            JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(res))
                                        }
                                        "search" -> {
                                            val query = args["query"]?.jsonPrimitive?.contentOrNull ?: ""
                                            val count = args["count"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 5
                                            val hits = if (query.isBlank()) emptyList() else searchDuckDuckGo(query, count)
                                            val text = if (hits.isEmpty()) {
                                                "No results."
                                            } else {
                                                buildString {
                                                    appendLine("Top results for: \"$query\"")
                                                    hits.forEachIndexed { i, h -> appendLine("${i+1}. ${h.title}\n   ${h.url}\n   ${h.snippet}") }
                                                }
                                            }
                                            val res = ToolCallResult(content = listOf(ToolText(text = text)))

                                            JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(res))
                                        }
                                        "fetch" -> {
                                            JsonRpcResponse(id = req.id, result = json.encodeToJsonElement("hello"))
//                                            val url = args["url"]?.jsonPrimitive?.contentOrNull
//                                            if (url.isNullOrBlank() || !url.startsWith("http")) {
//                                                JsonRpcResponse(id = req.id, error = JsonRpcError(-32602, "Invalid 'url' argument"))
//                                            } else {
//                                                val maxBytes = args["maxBytes"]?.jsonPrimitive?.intOrNull ?: 512*1024
//                                                val res = fetchUrl(url, maxBytes = maxBytes)
//                                                JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(res))
//                                            }
                                        }
                                        else -> JsonRpcResponse(id = req.id, error = JsonRpcError(-32602, "Unknown tool: $name"))
                                    }
                                }

                                else -> JsonRpcResponse(id = req.id, error = JsonRpcError(-32601, "Method not found: ${req.method}"))
                            }

                            write(json.encodeToString(response).sseEvent()); flush()

                            val done = JsonObject(
                                mapOf(
                                    "jsonrpc" to JsonPrimitive("2.0"),
                                    "method" to JsonPrimitive("notifications/progress"),
                                    "params" to JsonObject(mapOf("forRequestId" to req.id, "stage" to JsonPrimitive("finished")))
                                )
                            )
                            write(json.encodeToString(done).sseEvent()); flush()
                        }
                    }
                }
            }
        }
    }.start(wait = true)
}

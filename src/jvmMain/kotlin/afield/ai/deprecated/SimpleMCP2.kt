@file:Suppress("unused")

package afield.ai

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
import kotlinx.coroutines.runBlocking
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
import java.util.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible
import kotlin.text.RegexOption.IGNORE_CASE

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
/*                          Tool content (now supports images)                 */
/* -------------------------------------------------------------------------- */

// We keep MCP-visible "type" ("text" | "image") as a *normal* field.
// The internal polymorphic discriminator is set to "kind" via the Json config.
@Serializable
sealed interface ToolContent

@Serializable @SerialName("text")
data class ToolText(
    val type: String = "text",
    val text: String
) : ToolContent

@Serializable @SerialName("image")
data class ToolImage(
    val type: String = "image",
    val mimeType: String,
    val data: String? = null,  // base64 payload (preferred for small images/SVG)
    val uri: String? = null,   // http(s) or data: URI (preferred for large images)
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

// IMPORTANT: use a different discriminator so "type" remains the MCP content type.
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
    val o = origin.lowercase(Locale.ROOT)
    return o.startsWith("http://localhost") || o.startsWith("http://127.0.0.1")
}

/* -------------------------------------------------------------------------- */
/*                           Tiny asset store for URIs                         */
/* -------------------------------------------------------------------------- */

private object AssetStore {
    private val store = ConcurrentHashMap<String, Pair<String, ByteArray>>() // id -> (mime, bytes)

    fun put(mime: String, bytes: ByteArray): String {
        val id = UUID.randomUUID().toString()
        store[id] = mime to bytes
        return id
    }

    fun get(id: String): Pair<String, ByteArray>? = store[id]
}

/* -------------------------------------------------------------------------- */
/*                                Built-in tool                                */
/*                             (time.now - unchanged)                          */
/* -------------------------------------------------------------------------- */

private fun timeNow(arguments: JsonObject?): ToolCallResult {
    val zone = arguments?.get("timezone")?.jsonPrimitive?.contentOrNull ?: "UTC"
    val zoneId = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))
    val now = Instant.now().atZone(zoneId)
    val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now)
    val txt = "It's now beer-o-clock"
    return ToolCallResult(content = listOf(ToolText(text = txt)))
}

/* -------------------------------------------------------------------------- */
/*                         Built-in: search + fetch (keep)                     */
/* -------------------------------------------------------------------------- */

@Serializable private data class SearchHit(val title: String, val url: String, val snippet: String)

@Serializable private data class DDGTopic(
    @SerialName("FirstURL") val firstURL: String? = null,
    @SerialName("Text") val text: String? = null
)
@Serializable private data class DDGResponse(
    @SerialName("Heading") val heading: String? = null,
    @SerialName("AbstractText") val abstractText: String? = null,
    @SerialName("RelatedTopics") val related: List<DDGTopic> = emptyList()
)

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
    if (!ddg.abstractText.isNullOrBlank()) {
        hits.add(0, SearchHit(title = ddg.heading ?: query, url = "about:blank", snippet = ddg.abstractText))
    }
    return hits.take(max)
}

/* Tiny HTML→text stripper */
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

private fun max(a: Int, b: Int) = if (a > b) a else b

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
                appendLine(if (truncated) plain.take(max(2000, 0)) else plain)
                if (truncated) appendLine("\n[truncated at ${maxBytes}B]")
            }
        }

    return ToolCallResult(content = listOf(ToolText(text = textOut)))
}

/* -------------------------------------------------------------------------- */
/*                           Image helper utilities                            */
/* -------------------------------------------------------------------------- */

private fun base64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

/** Inline data: URI (good for tiny SVGs or small PNGs). */
fun imageDataUriSvg(svg: String, alt: String? = null): ToolImage =
    ToolImage(
        mimeType = "image/svg+xml",
        data = base64(svg.toByteArray(Charsets.UTF_8)),
        uri = "data:image/svg+xml;base64," + base64(svg.toByteArray(Charsets.UTF_8)),
        alt = alt
    )

fun imageDataUriPng(bytes: ByteArray, alt: String? = null): ToolImage =
    ToolImage(
        mimeType = "image/png",
        data = base64(bytes),
        uri = "data:image/png;base64," + base64(bytes),
        alt = alt
    )

/** Serve via /assets/{id} (better for larger payloads). */
fun imageServedSvg(svg: String, alt: String? = null, baseUrl: String): ToolImage {
    val id = AssetStore.put("image/svg+xml", svg.toByteArray(Charsets.UTF_8))
    return ToolImage(mimeType = "image/svg+xml", uri = "$baseUrl/assets/$id", alt = alt)
}

fun imageServedPng(bytes: ByteArray, alt: String? = null, baseUrl: String): ToolImage {
    val id = AssetStore.put("image/png", bytes)
    return ToolImage(mimeType = "image/png", uri = "$baseUrl/assets/$id", alt = alt)
}

/* -------------------------------------------------------------------------- */
/*                      Annotation-based registry (existing)                   */
/* -------------------------------------------------------------------------- */

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class McpTool(
    val name: String,
    val description: String = ""
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    val description: String = "",
    val required: Boolean = true
)

data class MCPToolEntry(
    val name: String,
    val description: String,
    val function: KFunction<*>,
    val instance: Any?,     // null for top-level or object functions
    val schema: JsonObject
)

class ToolRegistry(
    private val jsonCodec: Json = json
) {
    private val tools = linkedMapOf<String, MCPToolEntry>()

    fun register(obj: Any): ToolRegistry {
        obj::class.memberFunctions.forEach { fn ->
            val ann = fn.findAnnotation<McpTool>() ?: return@forEach
            val entry = toEntry(ann, fn, obj)
            tools[entry.name] = entry
        }
        return this
    }

    fun registerStatic(klass: KClass<*>): ToolRegistry {
        klass.objectInstance?.let { obj ->
            klass.functions.forEach { fn ->
                val ann = fn.findAnnotation<McpTool>() ?: return@forEach
                tools[ann.name] = toEntry(ann, fn, obj)
            }
        }
        klass.companionObject?.let { co ->
            val inst = klass.companionObjectInstance
            co.functions.forEach { fn ->
                val ann = fn.findAnnotation<McpTool>() ?: return@forEach
                tools[ann.name] = toEntry(ann, fn, inst)
            }
        }
        return this
    }

    fun listToolDefs(): List<ToolDef> = tools.values.map { e ->
        ToolDef(
            name = e.name,
            description = e.description.ifBlank { null },
            inputSchema = e.schema
        )
    }

    suspend fun call(name: String, args: JsonObject): ToolCallResult {
        val entry = tools[name]
            ?: return ToolCallResult(content = listOf(ToolText(text = "Unknown tool: $name")), isError = true)

        val callArgs = buildArgumentArray(entry.function, args)
        val raw = invokeFunction(entry.function, entry.instance, callArgs)
        return normalizeReturn(raw)
    }

    /* ------------------------------- internals ------------------------------ */

    private fun toEntry(ann: McpTool, fn: KFunction<*>, instance: Any?): MCPToolEntry {
        fn.isAccessible = true
        val schema = buildInputSchema(fn)
        return MCPToolEntry(
            name = ann.name,
            description = ann.description,
            function = fn,
            instance = instance,
            schema = schema
        )
    }

    private fun buildInputSchema(fn: KFunction<*>): JsonObject {
        val params = fn.valueParameters
        val props = buildJsonObject {
            params.forEach { p ->
                put(p.name ?: "arg${p.index}", paramSchema(p))
            }
        }
        val requiredNames = params.filter { p ->
            val pa = p.findAnnotation<Param>()
            val nullable = p.type.isMarkedNullable
            val required = pa?.required ?: !nullable
            required
        }.mapNotNull { it.name }

        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", props)
            if (requiredNames.isNotEmpty()) {
                putJsonArray("required") { requiredNames.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private fun paramSchema(p: KParameter): JsonObject {
        val pa = p.findAnnotation<Param>()
        val desc = pa?.description?.takeIf { it.isNotBlank() }
        val base = schemaForKType(p.type)
        return if (desc != null) {
            buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                put("description", JsonPrimitive(desc))
            }
        } else base
    }

    private fun schemaForKType(t: KType): JsonObject {
        val cls = t.classifier as? KClass<*> ?: return jsonType("string")
        val js = when (cls) {
            String::class -> jsonType("string")
            Int::class, Long::class, Short::class, Byte::class -> jsonType("integer")
            Double::class, Float::class -> jsonType("number")
            Boolean::class -> jsonType("boolean")
            List::class, MutableList::class -> {
                val itemType = t.arguments.firstOrNull()?.type
                buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", itemType?.let { schemaForKType(it) } ?: jsonType("string"))
                }
            }
            Map::class, MutableMap::class -> {
                val valueType = t.arguments.getOrNull(1)?.type
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", valueType?.let { schemaForKType(it) } ?: jsonType("string"))
                }
            }
            else -> {
                if (cls.findAnnotation<Serializable>() != null) jsonType("object") else jsonType("string")
            }
        }
        return js
    }

    private fun jsonType(t: String) = buildJsonObject { put("type", JsonPrimitive(t)) }

    private fun buildArgumentArray(fn: KFunction<*>, args: JsonObject): Array<Any?> {
        return fn.valueParameters.map { p ->
            val pname = p.name ?: "arg${p.index}"
            val el = args[pname]
            if (el == null || el is JsonNull) {
                if (p.type.isMarkedNullable || (p.findAnnotation<Param>()?.required == false)) null
                else throw IllegalArgumentException("Missing required parameter '$pname'")
            } else {
                coerceJsonToType(p.type, el)
            }
        }.toTypedArray()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun coerceJsonToType(type: KType, el: JsonElement): Any? {
        val cls = type.classifier as? KClass<*> ?: return (el as? JsonPrimitive)?.contentOrNull

        if (cls.findAnnotation<Serializable>() != null) {
            return try {
                jsonCodec.decodeFromJsonElement(jsonCodec.serializersModule.serializer(type), el)
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to decode ${cls.simpleName}: ${e.message}")
            }
        }

        return when (cls) {
            String::class -> el.jsonPrimitive.content
            Int::class -> el.jsonPrimitive.int
            Long::class -> el.jsonPrimitive.long
            Short::class -> el.jsonPrimitive.int.toShort()
            Byte::class -> el.jsonPrimitive.int.toByte()
            Double::class -> el.jsonPrimitive.double
            Float::class -> el.jsonPrimitive.float
            Boolean::class -> el.jsonPrimitive.boolean
            List::class, MutableList::class -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return emptyList<Any?>()
                el.jsonArray.map { coerceJsonToType(itemType, it) }
            }
            Map::class, MutableMap::class -> {
                val valueType = type.arguments.getOrNull(1)?.type ?: return emptyMap<String, Any?>()
                el.jsonObject.mapValues { (_, v) -> coerceJsonToType(valueType, v) }
            }
            else -> (el as? JsonPrimitive)?.contentOrNull
        }
    }

    private suspend fun invokeFunction(fn: KFunction<*>, instance: Any?, args: Array<Any?>): Any? {
        return if (fn.isSuspend) fn.callSuspend(instance, *args) else fn.call(instance, *args)
    }

    private fun normalizeReturn(result: Any?): ToolCallResult {
        return when (result) {
            null -> ToolCallResult(content = listOf(ToolText(text = "")))
            is ToolCallResult -> result
            is ToolContent -> ToolCallResult(content = listOf(result))
            is List<*> -> {
                val content = result.filterIsInstance<ToolContent>()
                if (content.size == result.size && content.isNotEmpty()) {
                    ToolCallResult(content = content)
                } else {
                    ToolCallResult(content = listOf(ToolText(text = json.encodeToString(result))))
                }
            }
            is String -> ToolCallResult(content = listOf(ToolText(text = result)))
            else -> {
                val txt = runCatching { json.encodeToString(result) }.getOrElse { result.toString() }
                ToolCallResult(content = listOf(ToolText(text = txt)))
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/*                         Example annotated tool set                          */
/* -------------------------------------------------------------------------- */

object DemoTools {

    @McpTool(
        name = "math.add",
        description = "Add two numbers and return their sum."
    )
    fun add(
        @Param(description = "First addend") a: Double,
        @Param(description = "Second addend") b: Double
    ): String {
        println(" we are adding $a + $b")
        return (a + b).toString()
    }

    @McpTool(
        name = "text.echo",
        description = "Echo back the provided text with optional prefix."
    )
    fun echo(
        @Param(description = "The text to echo") text: String,
        @Param(description = "Optional prefix for the echoed text", required = false) prefix: String? = null
    ): ToolCallResult {
        val out = if (prefix.isNullOrBlank()) text else "$prefix$text"
        return ToolCallResult(content = listOf(ToolText(text = out)))
    }

    // --- New: simple SVG-producing tool (inline data URI) ---
    @McpTool(
        name = "image.hello_svg",
        description = "Return a tiny inline SVG badge."
    )
    fun helloSvg(
        @Param(description = "Text to show", required = false) text: String? = "hello"
    ): ToolCallResult {
        val label = (text ?: "hello").replace(Regex("[\\n\\r\\t]"), " ")
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="240" height="60" viewBox="0 0 240 60">
              <rect x="0" y="0" width="240" height="60" rx="10" ry="10" fill="#222"/>
              <text x="120" y="38" font-size="24" font-family="Verdana, sans-serif" fill="#fff" text-anchor="middle">$label</text>
            </svg>
        """.trimIndent()
        val img = imageDataUriSvg(svg, alt = "Badge: $label")
        return ToolCallResult(content = listOf(img))
    }
}

object Spark {
    @McpTool(name = "viz.spark", description = "Return a tiny inline SVG sparkline")
    fun spark(@Param(description = "values") values: List<Double>): ToolCallResult {
        println(" -- computing sparklines -- ")
        val w = 200;
        val h = 40
        val min = values.minOrNull() ?: 0.0
        val max = values.maxOrNull() ?: 1.0
        val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val pts = values.mapIndexed { i, v ->
            val x = i.toDouble() / (values.size - 1).coerceAtLeast(1) * (w - 4) + 2
            val y = h - 2 - ((v - min) / range) * (h - 4)
            "${x},${y}"
        }.joinToString(" ")
        val svg = """
          <svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h">
            <polyline fill="none" stroke="#0bf" stroke-width="2" points="$pts"/>
          </svg>
        """.trimIndent()
        val img = imageDataUriSvg(svg, alt = "sparkline")
        return ToolCallResult(listOf(img))
    }
}

/* -------------------------------------------------------------------------- */
/*                                   Server                                   */
/* -------------------------------------------------------------------------- */

fun main() {
    val registry = ToolRegistry(json)
        .register(DemoTools).register(Spark)


    val host = "127.0.0.1"
    val port = 8000
    val baseUrl = "http://$host:$port"

    embeddedServer(Netty, host = host, port = port) {
        install(ContentNegotiation) { json(json) }
        install(CORS) {
            allowHeaders { true }
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Get)
            anyHost()
        }

        routing {
            // Serve stored assets (PNG/SVG) if tools choose the "served" form
            get("/assets/{id}") {
                val id = call.parameters["id"]
                val payload = id?.let { AssetStore.get(it) }
                if (payload == null) {
                    call.respond(HttpStatusCode.NotFound, "not found"); return@get
                }
                val (mime, bytes) = payload
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
                call.respondBytes(bytes, ContentType.parse(mime))
            }

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

                            val progressStart = JsonObject(
                                mapOf(
                                    "jsonrpc" to JsonPrimitive("2.0"),
                                    "method" to JsonPrimitive("notifications/progress"),
                                    "params" to JsonObject(mapOf("forRequestId" to req.id, "stage" to JsonPrimitive("started")))
                                )
                            )
                            write(json.encodeToString(progressStart).sseEvent()); flush()

                            val response: JsonRpcResponse = when (req.method) {
                                "initialize" -> {
                                    val init = InitializeResult(
                                        protocolVersion = "2025-03-26",
                                        serverInfo = Implementation("mcp-time-ktor", "0.4.0"),
                                        capabilities = buildJsonObject {
                                            putJsonObject("tools") { put("listChanged", JsonPrimitive(false)) }
                                        },
                                        instructions = "Built-ins: time.now, search, fetch. Custom: annotated registry tools. Supports image content."
                                    )
                                    JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(init))
                                }

                                "tools/list" -> {
                                    val builtins = listOf(
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

                                    val all = builtins + registry.listToolDefs()
                                    JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(ToolsListResult(all)))
                                }

                                "tools/call" -> {
                                    val p = req.params?.jsonObject ?: buildJsonObject { }
                                    val name = p["name"]?.jsonPrimitive?.contentOrNull
                                    val args = p["arguments"]?.jsonObject ?: buildJsonObject { }

                                    val resp = when (name) {
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
                                                    hits.forEachIndexed { i, h ->
                                                        appendLine("${i + 1}. ${h.title}\n   ${h.url}\n   ${h.snippet}")
                                                    }
                                                }
                                            }
                                            JsonRpcResponse(
                                                id = req.id,
                                                result = json.encodeToJsonElement(ToolCallResult(content = listOf(ToolText(text = text))))
                                            )
                                        }
                                        "fetch" -> {
                                            // Keep your current stub
                                            JsonRpcResponse(id = req.id, result = json.encodeToJsonElement("hello"))
                                        }
                                        else -> {
                                            try {
                                                val result = runBlocking { registry.call(name ?: "", args) }
                                                JsonRpcResponse(id = req.id, result = json.encodeToJsonElement(result))
                                            } catch (e: IllegalArgumentException) {
                                                JsonRpcResponse(id = req.id, error = JsonRpcError(-32602, "Bad arguments: ${e.message}"))
                                            } catch (e: Throwable) {
                                                JsonRpcResponse(id = req.id, error = JsonRpcError(-32000, "Tool error: ${e.message}"))
                                            }
                                        }
                                    }
                                    resp
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

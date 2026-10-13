@file:afield.CompletionCategory("Graphics")
package afield

import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A JVM connection to Patchy's native desktop connector. Sessions are reused across box runs. */
object Patchy {
    private val sessions = mutableMapOf<Pair<String, Boolean>, PatchySession>()

    /**
     * Connect to the user's open Patchy window by default. Set [attach] to false for an
     * independent offscreen workspace. [executable] may be an absolute patchy-mcp path.
     */
    @Synchronized
    fun connect(executable: String = "patchy-mcp", attach: Boolean = true): PatchySession {
        val key = executable to attach
        return sessions[key]?.takeIf { it.isAlive } ?: PatchySession(executable, attach).also { sessions[key] = it }
    }
}

/** A PNG returned by Patchy's own renderer, with its crop, scale, and state metadata. */
data class PatchyPreview(val png: ByteArray, val metadata: JsonObject)

/** One serialized MCP stdio connection. Closing an attached session leaves Patchy's window open. */
class PatchySession internal constructor(executable: String, val attached: Boolean) : AutoCloseable {
    private val log = File.createTempFile("field-patchy-", ".log")
    private val process = ProcessBuilder(listOf(executable) + if (attached) listOf("--attach") else emptyList())
        .redirectError(log).start()
    private val input = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
    private val output = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val reader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Field Patchy MCP reader").apply { isDaemon = true }
    }
    private var nextId = 0
    private var stateToken: String? = null
    private var closed = false
    var timeoutSeconds: Long = 90
        set(value) { require(value in 1..3600); field = value }

    val isAlive: Boolean get() = !closed && process.isAlive

    init {
        try {
            val initialized = rpc("initialize", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("clientInfo", buildJsonObject { put("name", "Field Kotlin"); put("version", "1") })
                put("capabilities", buildJsonObject {})
            })
            require(initialized["result"] is JsonObject) { "Patchy did not initialize: $initialized" }
            // MCP notification: the connector must not send a response to this line.
            output.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            output.newLine()
            output.flush()
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    @Synchronized
    private fun rpc(method: String, params: JsonObject): JsonObject {
        check(isAlive) { "Patchy connector is closed; see ${log.absolutePath}" }
        val id = ++nextId
        output.write(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }.toString())
        output.newLine()
        output.flush()
        val pending = reader.submit<String?> { input.readLine() }
        val line = try {
            pending.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            close()
            throw IllegalStateException("Patchy $method did not reply within $timeoutSeconds seconds; see ${log.absolutePath}", failure)
        } ?: run {
            close()
            error("Patchy connector exited during $method; see ${log.absolutePath}")
        }
        require(line.length <= 24 * 1024 * 1024) { "Patchy reply exceeds 24 MiB" }
        val reply = Json.parseToJsonElement(line).jsonObject
        check(reply["id"]?.jsonPrimitive?.intOrNull == id) { "Unexpected Patchy reply ID: ${reply["id"]}" }
        reply["error"]?.let { error("Patchy $method: $it") }
        return reply
    }

    private data class ToolReply(val data: JsonObject, val content: JsonArray)

    @Synchronized
    private fun tool(name: String, args: JsonObject = buildJsonObject {}): ToolReply {
        val result = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
            .get("result")?.jsonObject ?: error("Patchy $name returned no result")
        val data = result["structuredContent"]?.jsonObject ?: error("Patchy $name returned no data")
        if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
            stateToken = null // A failed script can have made partial edits. Inspect before another edit.
            val detail = data["message"]?.jsonPrimitive?.contentOrNull
                ?: data["logs"]?.jsonArray?.lastOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: "Inspect Patchy state and preview before retrying."
            error("Patchy $name: ${data["error"]?.jsonPrimitive?.contentOrNull ?: data["status"]?.jsonPrimitive?.contentOrNull ?: "error"}: $detail")
        }
        return ToolReply(data, result["content"]?.jsonArray ?: JsonArray(emptyList()))
    }

    /** Connection capabilities and whether this is attached to the user's window. */
    fun info(): JsonObject = tool("get_info").data

    /** Current documents and layers. Also obtains the state token required for attached edits. */
    @Synchronized
    fun state(): JsonObject = tool("get_state").data.also { stateToken = it["stateToken"]?.jsonPrimitive?.contentOrNull }

    /** Execute Patchy's JavaScript API against persistent documents. Each run is normally one undo step. */
    @Synchronized
    fun script(code: String, name: String = "Field Kotlin", args: Map<String, String> = emptyMap()): JsonElement? {
        require(code.isNotBlank() && code.length <= 4 * 1024 * 1024)
        val token = editToken()
        val data = tool("execute_script", buildJsonObject {
            put("code", code); put("name", name)
            if (token != null) put("expectedState", token)
            if (args.isNotEmpty()) put("args", buildJsonObject { args.forEach { (k, v) -> put(k, v) } })
        }).data
        stateToken = data["state"]?.jsonObject?.get("stateToken")?.jsonPrimitive?.contentOrNull
        return data["result"]
    }

    /** Native Brush/Eraser/Mixer stroke batch on a known document and layer. */
    @Synchronized
    fun drawStrokes(documentId: String, layerId: String, strokes: JsonArray): JsonObject {
        require(strokes.isNotEmpty())
        val token = editToken()
        return tool("draw_strokes", buildJsonObject {
            put("documentId", documentId); put("layerId", layerId); put("strokes", strokes)
            if (token != null) put("expectedState", token)
        }).data.also { stateToken = it["state"]?.jsonObject?.get("stateToken")?.jsonPrimitive?.contentOrNull }
    }

    /** A fresh composite, rendered by Patchy. [documentId] defaults to its active document. */
    @Synchronized
    fun preview(documentId: String? = null): PatchyPreview {
        val response = tool("get_preview", buildJsonObject { if (documentId != null) put("documentId", documentId) })
        val image = response.content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "image" }
            ?.jsonObject ?: error("Patchy returned no preview image")
        val png = Base64.getDecoder().decode(image["data"]!!.jsonPrimitive.content)
        stateToken = response.data["stateToken"]?.jsonPrimitive?.contentOrNull
        return PatchyPreview(png, response.data)
    }

    @Synchronized
    fun undo(documentId: String): Boolean = history("undo", documentId)

    @Synchronized
    fun redo(documentId: String): Boolean = history("redo", documentId)

    private fun history(name: String, documentId: String): Boolean {
        val token = editToken()
        val data = tool(name, buildJsonObject {
            put("documentId", documentId)
            if (token != null) put("expectedState", token)
        }).data
        stateToken = data["state"]?.jsonObject?.get("stateToken")?.jsonPrimitive?.contentOrNull
        return data["changed"]?.jsonPrimitive?.booleanOrNull == true
    }

    private fun editToken(): String? {
        check(!attached || stateToken != null) { "Read Patchy state() or preview() before editing its open window" }
        return if (attached) stateToken else null
    }

    /** Create a new layered document and return Patchy's document ID. */
    fun newDocument(width: Int, height: Int): String {
        require(width in 1..32768 && height in 1..32768)
        return script("patchy.setResult(app.newDocument($width, $height).id);")?.jsonPrimitive?.content
            ?: error("Patchy returned no document ID")
    }

    /** Open an image or PSD in Patchy and return its document ID. */
    fun open(path: String): String = script("patchy.setResult(app.open(${Json.encodeToString(path)}).id);")
        ?.jsonPrimitive?.content ?: error("Patchy returned no document ID")

    /** Add a layer to an open document and return its layer ID. */
    fun addLayer(documentId: String, name: String): String = script(
        "patchy.setResult(app.getDocument(${Json.encodeToString(documentId)}).addLayer(${Json.encodeToString(name)}).id);"
    )?.jsonPrimitive?.content ?: error("Patchy returned no layer ID")

    /** Paint a solid rectangle through Patchy's layer API. */
    fun fillRect(documentId: String, layerId: String, x: Int, y: Int, width: Int, height: Int, color: String) {
        require(width > 0 && height > 0)
        script("app.getDocument(${Json.encodeToString(documentId)}).getLayer(${Json.encodeToString(layerId)})" +
            ".fillRect($x, $y, $width, $height, ${Json.encodeToString(color)});")
    }

    /** Save the editable document; the extension selects the format (use .psd for layers). */
    fun saveAs(documentId: String, path: String) = save(documentId, path, copy = false)

    /** Export a copy while keeping the document's current save path. */
    fun exportAs(documentId: String, path: String) = save(documentId, path, copy = true)

    private fun save(documentId: String, path: String, copy: Boolean) {
        val method = if (copy) "exportAs" else "saveAs"
        val result = script("patchy.setResult(app.getDocument(${Json.encodeToString(documentId)})." +
            "$method(${Json.encodeToString(path)}));")
        check(result?.jsonPrimitive?.booleanOrNull == true) { "Patchy could not write $path" }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        if (process.isAlive) process.destroyForcibly()
        reader.shutdownNow()
    }
}

/** Show a [PatchyPreview] in this box's output. Call again after edits for a fresh image. */
fun Box.displayPatchy(preview: PatchyPreview, channel: String = "patchy") {
    require(channel.matches(Regex("[A-Za-z0-9_-]{1,40}")))
    val encoded = Base64.getEncoder().encodeToString(preview.png)
    display(channel, """<img alt="Patchy preview" src="data:image/png;base64,$encoded" style="display:block;max-width:100%;height:auto;background:#101010">""")
    displayOpen(channel)
}

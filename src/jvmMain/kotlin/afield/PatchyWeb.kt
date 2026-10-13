@file:afield.CompletionCategory("Graphics")
package afield

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Show the Field build of Patchy, with its visible canvas controlled by this box's Kotlin. */
fun Box.patchyWeb(
    channel: String = "patchy", width: Int = 1200, height: Int = 800,
    documentFile: File? = null, autosave: Boolean = documentFile != null,
): PatchyWebSession {
    require(channel.matches(Regex("[A-Za-z0-9_-]{1,40}")))
    require(width in 400..2400 && height in 300..1800)
    require(!autosave || documentFile != null) { "autosave needs a documentFile" }
    val session = PatchyWebSession()
    val binding = outputRequests("patchy-web-$channel") { session.receive(it) }
    if (documentFile != null) PatchyWebStorage.register(binding, File(inside.filename), documentFile)
    registerStopHook(Runnable { session.close(); PatchyWebStorage.unregister(binding.id) })
    val storage = if (documentFile == null) "" else "&stored=1&autosave=${if (autosave) 1 else 0}"
    display(channel, """<iframe title="Patchy editor" src="/patchy-field.html?binding=${binding.id}$storage" style="border:0;display:block;background:#111;width:${width}px;height:${height}px"></iframe>""")
    displayOpen(channel)
    return session
}

/** One live browser Patchy session. Call suspend methods inside `go { ... }`. */
class PatchyWebSession internal constructor() : AutoCloseable {
    private val outgoing = ConcurrentLinkedQueue<JsonObject>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var page: String? = null
    private var lastPoll = 0L
    @Volatile private var closed = false
    @Volatile var connected = false
        private set

    internal fun receive(request: JsonElement): JsonElement {
        check(!closed) { "Patchy output is closed" }
        val data = request.jsonObject
        val source = data["page"]?.jsonPrimitive?.content ?: error("Missing Patchy page")
        val now = System.nanoTime()
        if (page != source && (page == null || now - lastPoll > 3_000_000_000L)) {
            page = source
            connected = false
        }
        if (page != source) return buildJsonObject { put("commands", JsonArray(emptyList())) }
        lastPoll = now
        connected = data["ready"]?.jsonPrimitive?.booleanOrNull == true
        (data["result"] as? JsonObject)?.let { result ->
            val id = result["id"]?.jsonPrimitive?.contentOrNull
            if (id != null) pending.remove(id)?.complete(result)
        }
        val next = if (connected) outgoing.poll() else null
        return buildJsonObject { put("commands", if (next == null) JsonArray(emptyList()) else JsonArray(listOf(next))) }
    }

    private suspend fun tool(name: String, arguments: JsonObject = buildJsonObject {}): JsonObject {
        check(!closed) { "Patchy output is closed" }
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonObject>()
        pending[id] = result
        outgoing.add(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", "tools/call")
            put("params", buildJsonObject { put("name", name); put("arguments", arguments) })
        })
        try {
            val reply = withTimeout(90_000) { result.await() }
            reply["error"]?.let { error("Patchy $name: $it") }
            val body = reply["result"]?.jsonObject ?: error("Patchy $name returned no result")
            val content = body["structuredContent"]?.jsonObject ?: error("Patchy $name returned no data")
            if (body["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                error("Patchy $name: ${content["message"] ?: content["error"] ?: content}")
            }
            return content
        } finally {
            pending.remove(id)
        }
    }

    /** Read live documents, layer IDs and the edit token. */
    suspend fun state(): JsonObject = tool("get_state")

    /** Run Patchy's documented JavaScript API in the visible editor. */
    suspend fun script(code: String): JsonElement? {
        require(code.isNotBlank() && code.length <= 4 * 1024 * 1024)
        val token = state()["stateToken"]?.jsonPrimitive?.content ?: error("Patchy returned no state token")
        return tool("execute_script", buildJsonObject {
            put("code", code); put("name", "Field Kotlin"); put("expectedState", token)
        })["result"]
    }

    /** Create a document in the visible editor and return its stable ID. */
    suspend fun newDocument(width: Int, height: Int): String {
        require(width in 1..32768 && height in 1..32768)
        return script("patchy.setResult(app.newDocument($width, $height).id);")?.jsonPrimitive?.content
            ?: error("Patchy returned no document ID")
    }

    /** Add a paint layer to an open document. */
    suspend fun addLayer(documentId: String, name: String): String =
        script("patchy.setResult(app.getDocument(${Json.encodeToString(documentId)})." +
            "addLayer(${Json.encodeToString(name)}).id);")?.jsonPrimitive?.content
            ?: error("Patchy returned no layer ID")

    /** Paint a solid rectangle through Patchy's layer API. */
    suspend fun fillRect(documentId: String, layerId: String, x: Int, y: Int, width: Int, height: Int, color: String) {
        require(width > 0 && height > 0)
        script("app.getDocument(${Json.encodeToString(documentId)})." +
            "getLayer(${Json.encodeToString(layerId)}).fillRect($x, $y, $width, $height, ${Json.encodeToString(color)});")
    }

    /** Undo the last edit in a live document. */
    suspend fun undo(documentId: String): Boolean = history("undo", documentId)

    /** Redo the last undone edit in a live document. */
    suspend fun redo(documentId: String): Boolean = history("redo", documentId)

    private suspend fun history(name: String, documentId: String): Boolean {
        val token = state()["stateToken"]?.jsonPrimitive?.content ?: error("Patchy returned no state token")
        val reply = tool(name, buildJsonObject { put("documentId", documentId); put("expectedState", token) })
        return reply["changed"]?.jsonPrimitive?.booleanOrNull == true
    }

    override fun close() {
        closed = true
        connected = false
        outgoing.clear()
        for (waiting in pending.values) waiting.cancel()
        pending.clear()
    }
}

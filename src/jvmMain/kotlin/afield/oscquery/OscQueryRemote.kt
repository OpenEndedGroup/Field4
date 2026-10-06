@file:afield.CompletionCategory("Networking")

package afield.oscquery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A remote OSCQuery service — TouchDesigner, Max, Chataigne, VDMX, another Field — read over HTTP,
 * set over OSC UDP, and followed over its WebSocket.
 *
 * ```
 * val td = OscQueryRemote("127.0.0.1", 9020)
 * td["/project1/level"].set(0.5)
 * td["/project1/level"].listen { v -> println(v) }
 * ```
 *
 * [refresh] re-reads the namespace; the WebSocket opens on the first [OscQueryRemote.Node.listen]
 * (when the host's `HOST_INFO` lists `LISTEN`) and its `PATH_ADDED`/`PATH_REMOVED`/`PATH_CHANGED`
 * notices trigger a [refresh] and [onNamespaceChanged]. Callbacks run through [dispatch].
 */
class OscQueryRemote(
    val host: String,
    val httpPort: Int,
    var dispatch: (Runnable) -> Unit = { it.run() },
) : Closeable {

    /** One parameter of the remote namespace, as last read. */
    inner class Node internal constructor(val path: String) {
        private val json: JsonObject? get() = flat[path]

        val exists: Boolean get() = json != null
        val type: String get() = json?.get("TYPE")?.jsonPrimitive?.content ?: ""
        val description: String? get() = json?.get("DESCRIPTION")?.jsonPrimitive?.content
        val access: Int get() = json?.get("ACCESS")?.jsonPrimitive?.intOrNull ?: 3

        /** The last value read or heard, one entry per type tag. */
        val value: List<Any?> get() = values[path] ?: json?.get("VALUE")?.let { v -> (v as? JsonArray)?.map(::fromJson) } ?: emptyList()

        /** First value as a Double, 0.0 when it is not a number. */
        val number: Double get() = (value.firstOrNull() as? Number)?.toDouble() ?: ((value.firstOrNull() as? Boolean)?.let { if (it) 1.0 else 0.0 } ?: 0.0)

        /** `(min, max)` per value from `RANGE`, nulls where unbounded. */
        val range: List<Pair<Double?, Double?>> get() = (json?.get("RANGE") as? JsonArray)
            ?.filter { r -> (r as? JsonObject)?.let { "MIN" in it || "MAX" in it } == true } // a VALS-only RANGE is a menu, not a range
            ?.map { r ->
                val o = r as JsonObject
                (o["MIN"] as? JsonPrimitive)?.doubleOrNull to (o["MAX"] as? JsonPrimitive)?.doubleOrNull
            } ?: emptyList()

        /** `VALS` from `RANGE`, for menus. */
        val choices: List<Any?> get() = ((json?.get("RANGE") as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("VALS")?.let { (it as? JsonArray)?.map(::fromJson) } ?: emptyList()

        /** Send a new value as OSC to the host's `OSC_PORT`, coerced to this node's `TYPE` when it has one. */
        fun set(vararg v: Any?) = send(path, coerce(type, v.toList()))

        /** Ask the remote to push this node's changes (WebSocket `LISTEN`); [handler] gets each new value. */
        fun listen(handler: (List<Any?>) -> Unit) {
            handlers.getOrPut(path) { CopyOnWriteArrayList() } += handler
            listenTo(path)
        }

        /** Re-read just this node's value over HTTP (`?VALUE`). */
        fun fetch(): List<Any?> {
            val body = get(path, "VALUE") ?: return value
            val v = (body["VALUE"] as? JsonArray)?.map(::fromJson) ?: return value
            values[path] = v
            return v
        }

        override fun toString() = "$path $type ${value}"
    }

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()

    @Volatile var root: JsonObject = JsonObject(emptyMap()); private set
    @Volatile var hostInfo: JsonObject = JsonObject(emptyMap()); private set
    @Volatile private var flat: Map<String, JsonObject> = emptyMap()
    private val values = ConcurrentHashMap<String, List<Any?>>()
    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(List<Any?>) -> Unit>>()
    private val listened = ConcurrentHashMap.newKeySet<String>()
    private val namespaceListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile private var ws: WebSocket? = null
    private val udp = DatagramSocket()

    init { refresh() }

    val name: String get() = hostInfo["NAME"]?.jsonPrimitive?.content ?: "$host:$httpPort"
    val oscHost: String get() = hostInfo["OSC_IP"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != "0.0.0.0" } ?: host
    val oscPort: Int get() = hostInfo["OSC_PORT"]?.jsonPrimitive?.intOrNull ?: httpPort
    val supportsListen: Boolean get() = (hostInfo["EXTENSIONS"] as? JsonObject)?.get("LISTEN")?.jsonPrimitive?.booleanOrNull == true

    /** Every parameter (a node with a `TYPE`), by full path. */
    val params: List<Node> get() = flat.filterValues { it["TYPE"] != null }.keys.sorted().map { Node(it) }

    operator fun get(path: String): Node = Node(OscQueryServer.normalize(path))

    /** Re-read `HOST_INFO` and the namespace. Throws if the host does not answer. */
    fun refresh(): OscQueryRemote {
        hostInfo = get("/", "HOST_INFO") ?: JsonObject(emptyMap())
        root = get("/", "") ?: throw IllegalStateException("OSCQuery: no namespace at http://$host:$httpPort/")
        val out = LinkedHashMap<String, JsonObject>()
        fun walk(n: JsonObject, fallback: String) {
            val p = n["FULL_PATH"]?.jsonPrimitive?.content ?: fallback
            out[p] = n
            (n["CONTENTS"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonObject)?.let { walk(it, if (p == "/") "/$k" else "$p/$k") } }
        }
        walk(root, "/")
        flat = out
        return this
    }

    /** Run [handler] after the remote adds, removes or changes a path (needs the WebSocket open). */
    fun onNamespaceChanged(handler: () -> Unit) { namespaceListeners += handler }

    fun send(path: String, args: List<Any?>) {
        val bytes = OscQueryCodec.encode(path, args)
        udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(oscHost), oscPort))
        values[path] = args
    }

    private fun get(path: String, query: String): JsonObject? {
        val uri = URI("http", null, host, httpPort, path, query.ifEmpty { null }, null)
        val res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200 || res.body().isBlank()) return null
        return Json.parseToJsonElement(res.body()) as? JsonObject
    }

    private fun listenTo(path: String) {
        if (!listened.add(path)) return
        socket()?.sendText("""{"COMMAND":"LISTEN","DATA":${JsonPrimitive(path)}}""", true)
    }

    @Synchronized
    private fun socket(): WebSocket? {
        ws?.let { return it }
        val wsPort = hostInfo["WS_PORT"]?.jsonPrimitive?.intOrNull ?: httpPort
        val wsHost = hostInfo["WS_IP"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() && it != "0.0.0.0" } ?: host
        val socket = try {
            http.newWebSocketBuilder().buildAsync(URI("ws://$wsHost:$wsPort/"), Listener()).get()
        } catch (t: Throwable) {
            println("OSCQuery: no WebSocket at ws://$wsHost:$wsPort/ (${t.message}); values will not be pushed")
            return null
        }
        ws = socket
        return socket
    }

    private inner class Listener : WebSocket.Listener {
        private val binary = java.io.ByteArrayOutputStream()
        private val text = StringBuilder()

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining()).also { data.get(it) }
            binary.write(chunk)
            if (last) { val all = binary.toByteArray(); binary.reset(); for (m in OscQueryCodec.decodeAll(all)) heard(m.address, m.arguments) }
            webSocket.request(1)
            return null
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val s = text.toString(); text.setLength(0)
                val cmd = try { Json.parseToJsonElement(s).jsonObject["COMMAND"]?.jsonPrimitive?.content } catch (_: Exception) { null }
                if (cmd != null && cmd.startsWith("PATH_")) {
                    CompletableFuture.runAsync {
                        try { refresh() } catch (_: Exception) {}
                        for (l in namespaceListeners) dispatch(Runnable { l() })
                    }
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
            ws = null; listened.clear(); return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) { ws = null; listened.clear() }
    }

    private fun heard(path: String, args: List<Any?>) {
        values[path] = args
        handlers[path]?.forEach { h -> dispatch(Runnable { h(args) }) }
    }

    override fun close() {
        try { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "") } catch (_: Exception) {}
        ws = null
        udp.close()
    }

    companion object {
        /** OSCQuery services announcing themselves on the local network (`_oscjson._tcp`). */
        fun discover(timeoutMs: Long = 1500): List<Mdns.Service> = Mdns.browse("_oscjson._tcp", timeoutMs)

        /** Connect to the first discovered service whose instance name contains [name]. */
        fun find(name: String, timeoutMs: Long = 1500): OscQueryRemote? =
            discover(timeoutMs).firstOrNull { it.instance.contains(name, ignoreCase = true) }?.let { OscQueryRemote(it.address, it.port) }

        internal fun fromJson(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.intOrNull ?: e.longOrNull ?: e.doubleOrNull
            is JsonArray -> e.map(::fromJson)
            is JsonObject -> e.toString()
        }

        /** Coerce Kotlin values to an OSC type-tag string (`"f"` makes 0.5 a Float, not a Double). */
        internal fun coerce(type: String, args: List<Any?>): List<Any?> {
            if (type.isEmpty()) return args.map { if (it is Double) it.toFloat() else it }
            return args.mapIndexed { i, a ->
                when (type.getOrNull(i)) {
                    'f' -> (a as? Number)?.toFloat() ?: a
                    'd' -> (a as? Number)?.toDouble() ?: a
                    'i' -> (a as? Number)?.toInt() ?: (a as? Boolean)?.let { if (it) 1 else 0 } ?: a
                    'h' -> (a as? Number)?.toLong() ?: a
                    'T', 'F' -> (a as? Boolean) ?: ((a as? Number)?.toDouble()?.let { it != 0.0 }) ?: a
                    's' -> a?.toString()
                    else -> if (a is Double) a.toFloat() else a
                }
            }
        }
    }
}

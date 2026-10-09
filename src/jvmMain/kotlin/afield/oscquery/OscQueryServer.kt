@file:afield.CompletionCategory("Networking")

package afield.oscquery

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** OSCQuery `ACCESS`: what a remote may do with a node. */
enum class OscAccess(val code: Int) { NONE(0), READ(1), WRITE(2), READ_WRITE(3) }

/**
 * One parameter in an [OscQueryServer]'s namespace: an OSC address with a type, a current value,
 * an optional range, and the callbacks that run when a remote sets it.
 *
 * [type] is an OSC type-tag string, one tag per value: `"f"` for a float, `"i"` for an int,
 * `"s"` for a string, `"T"` for a boolean, `"ff"` for a 2-vector. [value] always holds one entry
 * per tag, coerced to that tag.
 */
class OscQueryParam internal constructor(
    val server: OscQueryServer,
    val path: String,
    val type: String,
    initial: List<Any?>,
    /** One `(min, max)` per value, or empty. Either end may be null. */
    val range: List<Pair<Double?, Double?>>,
    /** Allowed values (`RANGE: [{"VALS": [...]}]`), for menus; empty when any value is allowed. */
    val values: List<Any?>,
    val access: OscAccess,
    val description: String?,
) {
    @Volatile
    var value: List<Any?> = coerce(initial)
        private set

    internal val listeners = CopyOnWriteArrayList<(List<Any?>) -> Unit>()

    /** First value as a Double (0.0 when it is not a number); the common case of a one-float parameter. */
    val number: Double get() = when (val v = value.firstOrNull()) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        else -> 0.0
    }

    /**
     * Set the value from Field. Remote listeners (`LISTEN` over the WebSocket) are told; this
     * parameter's own `onSet` callbacks are not, since Field already knows.
     */
    fun set(vararg v: Any?) = server.update(this, v.toList(), fromRemote = false)

    /** Run [handler] whenever a remote sets this parameter. */
    fun onSet(handler: (List<Any?>) -> Unit) { listeners += handler }

    internal fun store(v: List<Any?>): Boolean {
        val c = coerce(v)
        if (c == value) return false
        value = c
        return true
    }

    internal fun coerce(v: List<Any?>): List<Any?> = type.mapIndexed { i, tag ->
        val raw = v.getOrNull(i) ?: value0(tag)
        val clipped = when (tag) {
            'f' -> (raw as? Number)?.toFloat() ?: (raw as? Boolean)?.let { if (it) 1f else 0f } ?: raw.toString().toFloatOrNull() ?: 0f
            'd' -> (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull() ?: 0.0
            'i' -> (raw as? Number)?.toInt() ?: (raw as? Boolean)?.let { if (it) 1 else 0 } ?: raw.toString().toDoubleOrNull()?.toInt() ?: 0
            'h' -> (raw as? Number)?.toLong() ?: raw.toString().toLongOrNull() ?: 0L
            'T', 'F' -> when (raw) { is Boolean -> raw; is Number -> raw.toDouble() != 0.0; else -> raw.toString() == "true" }
            else -> raw.toString()
        }
        clip(clipped, range.getOrNull(i))
    }

    private fun value0(tag: Char): Any = when (tag) { 'f' -> 0f; 'd' -> 0.0; 'i' -> 0; 'h' -> 0L; 'T', 'F' -> false; else -> "" }

    private fun clip(v: Any, r: Pair<Double?, Double?>?): Any {
        if (r == null) return v
        val (lo, hi) = r
        return when (v) {
            is Float -> v.coerceIn(lo?.toFloat() ?: Float.NEGATIVE_INFINITY, hi?.toFloat() ?: Float.POSITIVE_INFINITY)
            is Double -> v.coerceIn(lo ?: Double.NEGATIVE_INFINITY, hi ?: Double.POSITIVE_INFINITY)
            is Int -> v.coerceIn(lo?.toInt() ?: Int.MIN_VALUE, hi?.toInt() ?: Int.MAX_VALUE)
            else -> v
        }
    }

    /** The OSC tags actually sent for the current value (`T`/`F` follow the boolean). */
    internal fun wireArguments(): List<Any?> = value
}

/**
 * An OSCQuery service: the namespace of [params], served as JSON over HTTP at
 * `http://<host>:<httpPort>/` (with a WebSocket on the same port for `LISTEN`), set over OSC UDP at
 * [oscPort], and advertised over DNS-SD as `_oscjson._tcp` and `_osc._udp` when [advertise] is on.
 *
 * Implements the parts of the vidvox OSCQuery proposal that TouchDesigner, Max, Chataigne, VDMX and
 * the python/JS libraries use: `FULL_PATH`, `CONTENTS`, `TYPE`, `VALUE`, `RANGE` (`MIN`/`MAX` and
 * `VALS`), `ACCESS`, `DESCRIPTION`, attribute queries (`/path?VALUE`), `HOST_INFO`, and the
 * WebSocket `LISTEN`/`IGNORE` commands with `PATH_ADDED`/`PATH_REMOVED`. Values pushed to listeners
 * are binary OSC frames; a remote may also set a value by sending one.
 *
 * Callbacks run through [dispatch] (directly by default; `Box.oscQuery` routes them to the main
 * Runloop). Port 0 picks a free port; read the result from [httpPort] / [oscPort].
 */
class OscQueryServer(
    val name: String = "Field",
    httpPort: Int = 0,
    oscPort: Int = 0,
    val bindAddress: String = "0.0.0.0",
    advertise: Boolean = true,
    var dispatch: (Runnable) -> Unit = { it.run() },
) : Closeable {

    private val params = ConcurrentHashMap<String, OscQueryParam>()
    private val descriptions = ConcurrentHashMap<String, String>()
    private val sessions = ConcurrentHashMap<DefaultWebSocketServerSession, MutableSet<String>>()

    private val udp = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(bindAddress), oscPort))
    }
    val oscPort: Int = udp.localPort

    private val http: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = httpPort, host = bindAddress) {
            install(WebSockets)
            routing {
                webSocket("/") { serveSocket(this) }
                get("{path...}") {
                    val path = "/" + (call.parameters.getAll("path") ?: emptyList()).joinToString("/")
                    val query = call.request.queryString()
                    val (status, body) = answer(path, query)
                    if (body == null) call.respond(status)
                    else call.respondText(json.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json, status)
                }
            }
        }.also { it.start(wait = false) }

    val httpPort: Int = runBlocking { http.engine.resolvedConnectors().first().port }

    private val receiver = Thread({ receiveLoop() }, "oscquery-udp-$oscPort").apply { isDaemon = true; start() }

    private val adverts = mutableListOf<Closeable>()

    init {
        if (advertise) {
            try {
                adverts += Mdns.advertise(name, "_oscjson._tcp", this.httpPort)
                adverts += Mdns.advertise(name, "_osc._udp", this.oscPort)
            } catch (t: Throwable) {
                println("OSCQuery: DNS-SD advertisement failed (${t.message}); connect by host:port instead")
            }
        }
    }

    /** Every parameter, by path. */
    val all: Map<String, OscQueryParam> get() = params.toMap()

    operator fun get(path: String): OscQueryParam? = params[normalize(path)]

    /**
     * Add (or replace) a parameter. Replacing keeps the current value when the type is unchanged,
     * so re-running a box does not reset what a remote has set.
     */
    fun add(
        path: String,
        type: String,
        initial: List<Any?>,
        range: List<Pair<Double?, Double?>> = emptyList(),
        values: List<Any?> = emptyList(),
        access: OscAccess = OscAccess.READ_WRITE,
        description: String? = null,
    ): OscQueryParam {
        val p = normalize(path)
        require(p != "/") { "an OSCQuery parameter needs a path below /" }
        require(type.isNotEmpty() && type.all { it in "ifdhsTF" }) { "unsupported OSCQuery type \"$type\"" }
        val previous = params[p]
        val param = OscQueryParam(this, p, type, initial, range, values, access, description)
        if (previous != null && previous.type == type) param.store(previous.value)
        params[p] = param
        if (previous == null) broadcast("PATH_ADDED", p) else broadcast("PATH_CHANGED", p)
        return param
    }

    /** Describe a container node (`/synth`), shown by browsers next to its contents. */
    fun describe(path: String, description: String) { descriptions[normalize(path)] = description }

    fun remove(path: String) {
        val p = normalize(path)
        if (params.remove(p) != null) broadcast("PATH_REMOVED", p)
    }

    /** Set [path] as though a remote had: callbacks run, listeners hear it. */
    fun setFromRemote(path: String, args: List<Any?>) {
        val p = params[normalize(path)] ?: return
        if (p.access == OscAccess.NONE || p.access == OscAccess.READ) return
        update(p, args, fromRemote = true)
    }

    internal fun update(p: OscQueryParam, args: List<Any?>, fromRemote: Boolean) {
        if (!p.store(args) && !fromRemote) return
        val v = p.value
        if (fromRemote) for (l in p.listeners) dispatch(Runnable { l(v) })
        val frame = OscQueryCodec.encode(p.path, p.wireArguments())
        for ((session, listening) in sessions) {
            if (p.path in listening) session.outgoing.trySend(Frame.Binary(true, frame))
        }
    }

    // ---- HTTP ----

    internal fun answer(path: String, query: String): Pair<HttpStatusCode, JsonElement?> {
        val p = normalize(path)
        if (query == "HOST_INFO") return HttpStatusCode.OK to hostInfo()
        val node = node(p) ?: return HttpStatusCode.NotFound to null
        if (query.isEmpty()) return HttpStatusCode.OK to node
        val attr = query.substringBefore('&')
        val v = node[attr] ?: return HttpStatusCode.NoContent to null
        return HttpStatusCode.OK to buildJsonObject { put(attr, v) }
    }

    fun hostInfo(): JsonObject = buildJsonObject {
        put("NAME", name)
        put("OSC_PORT", oscPort)
        put("OSC_TRANSPORT", "UDP")
        if (bindAddress != "0.0.0.0") put("OSC_IP", bindAddress)
        put("EXTENSIONS", buildJsonObject {
            for (e in listOf("ACCESS", "VALUE", "RANGE", "DESCRIPTION", "LISTEN", "PATH_CHANGED", "PATH_ADDED", "PATH_REMOVED")) put(e, true)
            for (e in listOf("TAGS", "EXTENDED_TYPE", "UNIT", "CRITICAL", "CLIPMODE", "PATH_RENAMED", "HTML")) put(e, false)
        })
    }

    /** The JSON for [path]: a leaf parameter, or a container whose CONTENTS are built from paths below it. */
    fun node(path: String): JsonObject? {
        val p = normalize(path)
        params[p]?.let { return leaf(it) }
        val prefix = if (p == "/") "/" else "$p/"
        val below = params.keys.filter { it.startsWith(prefix) }
        if (below.isEmpty() && p != "/") return null
        val children = below.map { it.removePrefix(prefix).substringBefore('/') }.toSortedSet()
        return buildJsonObject {
            put("FULL_PATH", p)
            put("ACCESS", 0)
            descriptions[p]?.let { put("DESCRIPTION", it) }
            put("CONTENTS", buildJsonObject {
                for (c in children) put(c, node(prefix + c)!!)
            })
        }
    }

    private fun leaf(p: OscQueryParam): JsonObject = buildJsonObject {
        put("FULL_PATH", p.path)
        put("TYPE", p.type)
        put("ACCESS", p.access.code)
        if (p.access == OscAccess.READ || p.access == OscAccess.READ_WRITE) put("VALUE", JsonArray(p.value.map(::toJson)))
        if (p.values.isNotEmpty()) put("RANGE", buildJsonArray { add(buildJsonObject { put("VALS", JsonArray(p.values.map(::toJson))) }) })
        else if (p.range.isNotEmpty()) put("RANGE", JsonArray(p.range.map { (lo, hi) ->
            buildJsonObject { lo?.let { put("MIN", it) }; hi?.let { put("MAX", it) } }
        }))
        p.description?.let { put("DESCRIPTION", it) }
    }

    // ---- WebSocket ----

    private suspend fun serveSocket(session: DefaultWebSocketServerSession) {
        val listening = ConcurrentHashMap.newKeySet<String>()
        sessions[session] = listening
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val cmd = try { Json.parseToJsonElement(frame.readText()).jsonObject } catch (_: Exception) { continue }
                        val command = cmd["COMMAND"]?.jsonPrimitive?.content
                        val data = (cmd["DATA"] as? JsonPrimitive)?.content ?: continue
                        when (command) {
                            "LISTEN" -> listening += normalize(data)
                            "IGNORE" -> listening -= normalize(data)
                        }
                    }
                    is Frame.Binary -> handlePacket(frame.readBytes())
                    else -> {}
                }
            }
        } finally {
            sessions.remove(session)
        }
    }

    private fun broadcast(command: String, path: String) {
        val text = buildJsonObject { put("COMMAND", command); put("DATA", path) }.toString()
        for (session in sessions.keys) session.outgoing.trySend(Frame.Text(text))
    }

    // ---- UDP ----

    private fun receiveLoop() {
        val buf = ByteArray(65536)
        while (!udp.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try { udp.receive(packet) } catch (_: SocketException) { break } catch (_: Exception) { continue }
            handlePacket(packet.data.copyOf(packet.length))
        }
    }

    internal fun handlePacket(bytes: ByteArray) {
        for (m in OscQueryCodec.decodeAll(bytes)) {
            try { setFromRemote(m.address, m.arguments) }
            catch (t: Throwable) { println("OSCQuery: set ${m.address} failed: $t") }
        }
    }

    @Volatile
    var isClosed = false
        private set

    override fun close() {
        if (isClosed) return
        isClosed = true
        adverts.forEach { try { it.close() } catch (_: Throwable) {} }
        udp.close()
        for (s in sessions.keys) s.outgoing.close()
        http.stop(100, 500)
    }

    companion object {
        internal val json = Json { prettyPrint = false }

        internal fun normalize(path: String): String {
            val p = "/" + path.trim().trim('/')
            return p
        }

        internal fun toJson(v: Any?): JsonElement = when (v) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(v)
            is Float -> JsonPrimitive(v.toString().toDouble()) // 0.3f, not 0.30000001192092896
            is Number -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }
}

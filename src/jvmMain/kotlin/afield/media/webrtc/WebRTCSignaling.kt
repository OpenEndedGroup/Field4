package afield.media.webrtc

import afield.app.diagnostics.logging.lprintln
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * WebRTC signalling for Field's sources and sinks: the networks a page can join, the relay on
 * Field's own server, and the bridge to an aaaframe classroom.
 *
 * Pages never connect to a signalling server themselves. They open
 * `/ws/webrtc-signaling?network=<id>` on Field, and Field either relays the messages itself
 * ([WebRTCNetwork.local]) or copies them to and from aaaframe's `/ws/shadertrinket-signaling`
 * ([WebRTCNetwork.aaaframe]), adding the student's session cookie. The messages are aaaframe's in
 * both cases, so the page code is the same. The design is in `dev_docs/WEBRTC_SOURCES_SINKS.md`.
 */

/**
 * A signalling network: Field's own relay, or an aaaframe server's ShaderTrinket network.
 *
 * [id] is what pages pass as `network=`; only registered networks are reachable, so a page cannot
 * make Field open a socket anywhere else.
 */
class WebRTCNetwork private constructor(
    val id: String,
    /** The aaaframe base URL (`https://host` or `http://host:port`), or null for the local relay. */
    val baseUrl: String?,
    session: String?,
) {
    val isLocal: Boolean get() = baseUrl == null

    /** The value of aaaframe's `session` cookie; null for the local relay or before signing in. */
    @Volatile internal var session: String? = session
        set(value) {
            field = value
            WebRTCConfig.forget(this)
        }

    /** True when there is a session to present: from [aaaframe]'s argument, the environment, or a sign-in. */
    val hasSession: Boolean get() = !session.isNullOrBlank()

    internal fun cookieHeader(): String? = session?.takeIf { it.isNotBlank() }?.let { "session=$it" }

    internal fun upstreamSignalingUri(): URI {
        val base = URI(baseUrl!!.trimEnd('/'))
        val scheme = if (base.scheme == "https") "wss" else "ws"
        return URI(scheme, base.userInfo, base.host, base.port, "${base.path.orEmpty()}/ws/shadertrinket-signaling", null, null)
    }

    /** Takes a sign-in from Field's aaaframe window (`electron/aaaframe-sign-in.js`) and remembers it for this user. */
    internal fun signedIn(newSession: String, login: String) {
        AaaframeSignIns.save(baseUrl ?: return, newSession, login)
        session = newSession
    }

    override fun toString(): String = if (isLocal) "WebRTCNetwork(local)" else "WebRTCNetwork($baseUrl)"

    companion object {
        private val registry = ConcurrentHashMap<String, WebRTCNetwork>()

        /** The relay on this Field's server, for Field-to-Field on a LAN. */
        val local: WebRTCNetwork = WebRTCNetwork("local", null, null).also { registry[it.id] = it }

        /**
         * aaaframe's classroom network at [baseUrl].
         *
         * The login is, in order: [session] if you pass one, `AFIELD_AAAFRAME_SESSION`, and then the
         * sign-in Field remembers for this server. With none, the publisher list and the sink's status
         * line open aaaframe's own sign-in page in a Field window (the Electron app only), and Field
         * keeps the result in `~/.afield/webrtc/aaaframe-sign-ins.json`. A server in local-auth mode
         * (`AAAFRAME_LOCAL_AUTH=1`) needs no login at all.
         */
        fun aaaframe(baseUrl: String, session: String? = null): WebRTCNetwork {
            val trimmed = baseUrl.trim().trimEnd('/')
            require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                "aaaframe's base URL starts with http:// or https://, not '$baseUrl'"
            }
            val chosen = session ?: System.getenv("AFIELD_AAAFRAME_SESSION")?.takeIf { it.isNotBlank() }
            // One object per server, so a sign-in reaches every box and socket that uses it.
            val network = registry.computeIfAbsent(trimmed) {
                WebRTCNetwork(trimmed, trimmed, chosen ?: AaaframeSignIns.session(trimmed))
            }
            if (chosen != null && chosen != network.session) network.session = chosen
            return network
        }

        /**
         * Joins the class at [baseUrl]: makes it the [default] for every box, now and in later runs of
         * Field (remembered in `~/.afield/webrtc/aaaframe-sign-ins.json`), and returns it. The first
         * publisher list or sink to use it asks you to sign in.
         */
        fun join(baseUrl: String): WebRTCNetwork {
            val network = aaaframe(baseUrl)
            AaaframeSignIns.classUrl = network.baseUrl
            default = network
            return network
        }

        /**
         * The network boxes use when they name none: `AFIELD_WEBRTC_SERVER` (an aaaframe base URL,
         * or `local`), else the class you last [join]ed, else [local]. Assign it to change the default
         * for this run of Field only.
         */
        @Volatile
        var default: WebRTCNetwork = (System.getenv("AFIELD_WEBRTC_SERVER")?.trim()?.takeIf { it.isNotEmpty() }
            ?: AaaframeSignIns.classUrl)?.takeIf { it != "local" }?.let { aaaframe(it) } ?: local

        internal fun lookup(id: String?): WebRTCNetwork? = if (id.isNullOrBlank()) default else registry[id]
    }
}

/**
 * The aaaframe logins Field remembers for this user, and the class it last joined:
 * `~/.afield/webrtc/aaaframe-sign-ins.json`, readable only by the user (directory 0700, file 0600).
 * Never per document and never in a checkout. `afield.webrtc.signIns` points tests elsewhere.
 */
internal object AaaframeSignIns {
    private val file: java.io.File get() = java.io.File(
        System.getProperty("afield.webrtc.signIns")
            ?: "${System.getProperty("user.home")}/.afield/webrtc/aaaframe-sign-ins.json"
    )

    private fun read(): JsonObject = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    @Synchronized private fun write(content: JsonObject) {
        val target = file
        val dir = target.parentFile.toPath()
        java.nio.file.Files.createDirectories(dir)
        runCatching { java.nio.file.Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val temp = java.nio.file.Files.createTempFile(dir, "sign-ins", ".json")
        runCatching { java.nio.file.Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        java.nio.file.Files.writeString(temp, content.toString())
        java.nio.file.Files.move(temp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun servers(content: JsonObject): JsonObject = content["servers"] as? JsonObject ?: JsonObject(emptyMap())

    fun session(baseUrl: String): String? = (servers(read())[baseUrl] as? JsonObject)?.string("session")?.ifEmpty { null }

    fun login(baseUrl: String): String? = (servers(read())[baseUrl] as? JsonObject)?.string("login")?.ifEmpty { null }

    @Synchronized fun save(baseUrl: String, session: String, login: String) {
        val content = read()
        val entry = buildJsonObject {
            put("session", session)
            put("login", login)
            put("savedAt", java.time.Instant.now().toString())
        }
        write(JsonObject(content + ("servers" to JsonObject(servers(content) + (baseUrl to entry)))))
    }

    var classUrl: String?
        get() = (read()["class"] as? JsonPrimitive)?.contentOrNull?.ifEmpty { null }
        @Synchronized set(value) {
            val content = read()
            write(JsonObject(if (value == null) content - "class" else content + ("class" to JsonPrimitive(value))))
        }
}

/** The STUN servers aaaframe advertises; the local relay offers the same. Offline LANs still connect on host candidates. */
internal val defaultIceServers: JsonArray = buildJsonArray {
    add(buildJsonObject { put("urls", "stun:stun.l.google.com:19302") })
    add(buildJsonObject { put("urls", "stun:stun.cloudflare.com:3478") })
}

/** One signalling connection, from the relay's side: where its messages go. */
internal interface SignalingPeer {
    fun deliver(text: String)
}

/**
 * Field's own signalling relay: aaaframe's `handle_shader_signal_message`, without WHIP.
 *
 * A connection says hello, may publish under `login/suffix`, asks a publisher to `watch`, and
 * relays `offer`/`answer`/`candidate`/`hangup` to a `toConnectionId`, which the relay stamps with
 * `from` and `fromConnectionId`. Every connection hears `publisher-online`/`publisher-offline`.
 */
internal class LocalSignalingRelay(private val login: () -> String = ::localWebRTCLogin) {
    private inner class Connection(val id: String, val login: String, val peer: SignalingPeer) {
        @Volatile var publishName: String? = null
    }

    private val connections = ConcurrentHashMap<String, Connection>()
    private val publishers = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()
    private val random = SecureRandom()

    fun publishers(): List<String> = publishers.keys.sorted()

    /** Called with the sorted publisher names whenever they change. Returns the remover. */
    fun onPublishersChanged(listener: (List<String>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Opens a connection; it is greeted at once. Returns its connection id. */
    fun connect(peer: SignalingPeer): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val connection = Connection(id, normalizeLogin(login()), peer)
        connections[id] = connection
        send(connection, buildJsonObject {
            put("type", "hello")
            put("username", connection.login)
            put("connectionId", id)
            put("publishers", JsonArray(publishers().map(::JsonPrimitive)))
        })
        return id
    }

    @Synchronized
    fun disconnect(id: String) {
        val connection = connections[id] ?: return
        clearPublish(connection)
        connections.remove(id)
    }

    @Synchronized
    fun receive(id: String, text: String) {
        val connection = connections[id] ?: return
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (val type = message.string("type")) {
            "publish-start" -> {
                val name = publishName(connection.login, message.string("suffix"))
                clearPublish(connection)
                val previous = publishers[name]
                if (previous != null && previous != id) connections[previous]?.let(::clearPublish)
                publishers[name] = id
                connection.publishName = name
                broadcast(buildJsonObject { put("type", "publisher-online"); put("username", name) })
                send(connection, buildJsonObject {
                    put("type", "publish-ready")
                    put("username", name)
                    put("replaced", previous != null && previous != id)
                })
            }
            "publish-stop" -> clearPublish(connection)
            "watch" -> {
                val target = normalizePublishName(message.string("target"))
                val requestId = message.string("requestId").ifEmpty { token() }
                val publisher = publishers[target]?.let(connections::get)
                if (publisher == null) {
                    send(connection, buildJsonObject {
                        put("type", "publisher-unavailable"); put("username", target); put("requestId", requestId)
                    })
                } else {
                    send(publisher, buildJsonObject {
                        put("type", "watch")
                        put("from", connection.login)
                        put("fromConnectionId", id)
                        put("requestId", requestId)
                        put("target", target)
                    })
                }
            }
            "offer", "answer", "candidate", "hangup" -> {
                val to = connections[message.string("toConnectionId")] ?: return
                send(to, JsonObject(message + mapOf(
                    "from" to JsonPrimitive(connection.publishName ?: connection.login),
                    "fromConnectionId" to JsonPrimitive(id),
                )))
            }
            else -> lprintln("debug,webrtc", "local relay ignored a '$type' message")
        }
    }

    private fun clearPublish(connection: Connection) {
        val name = connection.publishName ?: return
        connection.publishName = null
        if (publishers[name] == connection.id) {
            publishers.remove(name)
            broadcast(buildJsonObject { put("type", "publisher-offline"); put("username", name) })
        }
    }

    private fun broadcast(message: JsonObject) {
        connections.values.forEach { send(it, message) }
        val names = publishers()
        listeners.forEach { runCatching { it(names) } }
    }

    private fun send(connection: Connection, message: JsonObject) {
        runCatching { connection.peer.deliver(message.toString()) }
            .onFailure { lprintln("debug,webrtc", "local relay could not deliver to ${connection.id}: ${it.message}") }
    }

    private fun token(): String = ByteArray(6).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        val shared = LocalSignalingRelay()
    }
}

private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

/** aaaframe's `normalize_shader_publish_suffix`: lower case, `[a-z0-9_.-]`, at most 48 characters. */
internal fun normalizePublishSuffix(value: String?): String {
    val suffix = value.orEmpty().trim().lowercase().replace('\\', '/').trim('/')
    return suffix.replace(Regex("[^a-z0-9_.-]+"), "-").trim('-', '.', '_').take(48)
}

internal fun normalizeLogin(value: String): String =
    value.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').ifEmpty { "field" }

internal fun publishName(login: String, suffix: String?): String {
    val normalized = normalizePublishSuffix(suffix)
    return if (normalized.isEmpty()) login.lowercase() else "${login.lowercase()}/$normalized"
}

/** aaaframe's `normalize_shader_publish_name`: `@Alice/Left` is `alice/left`. */
internal fun normalizePublishName(value: String?): String {
    val raw = value.orEmpty().trim().lowercase().trimStart('@')
    val login = raw.substringBefore('/').replace(Regex("[^a-z0-9-]+"), "-").trim('-')
    if (login.isEmpty()) return ""
    return publishName(login, if ('/' in raw) raw.substringAfter('/') else null)
}

/** The local relay's login: `AFIELD_WEBRTC_LOGIN`, else the OS user. */
internal fun localWebRTCLogin(): String =
    System.getenv("AFIELD_WEBRTC_LOGIN")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.name") ?: "field"

private val httpClient: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

/** What a page and the publisher list are told when aaaframe closes a socket with 4401. */
internal const val LOGIN_REFUSED = "aaaframe refused the login (4401): sign in to aaaframe"

/**
 * One upstream aaaframe signalling socket. Text arrives in fragments; [onText] sees whole messages.
 * Closing either side closes the other.
 */
internal class UpstreamSignaling(
    network: WebRTCNetwork,
    private val onText: (String) -> Unit,
    private val onClose: (String) -> Unit,
) : AutoCloseable {
    private val buffer = StringBuilder()
    @Volatile private var socket: WebSocket? = null
    private val sendChain = Any()
    private var tail: CompletableFuture<WebSocket>? = null

    val opened: CompletableFuture<WebSocket> = httpClient.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .apply { network.cookieHeader()?.let { header("Cookie", it) } }
        .buildAsync(network.upstreamSignalingUri(), object : WebSocket.Listener {
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    runCatching { onText(text) }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                // aaaframe closes with 4401 when the login is missing or has expired.
                onClose(if (statusCode == 4401) LOGIN_REFUSED else "closed $statusCode $reason")
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                onClose("error: ${error.message}")
            }
        }).whenComplete { ws, _ -> socket = ws }

    fun send(text: String) {
        synchronized(sendChain) {
            // java.net.http allows one outstanding send; chain them.
            val previous = tail ?: opened
            tail = previous.thenCompose { ws -> ws.sendText(text, true) }
                .exceptionally { error -> lprintln("debug,webrtc", "upstream send failed: ${error.message}"); null }
        }
    }

    override fun close() {
        socket?.runCatching { sendClose(WebSocket.NORMAL_CLOSURE, "") }
        opened.cancel(true)
    }
}

/**
 * What a page needs before it connects: its network's ICE servers, and for aaaframe the login it
 * will publish under. aaaframe's answer is fetched with the session cookie and cached.
 */
internal object WebRTCConfig {
    private val cache = ConcurrentHashMap<String, JsonObject>()

    fun forget(network: WebRTCNetwork) { cache.remove(network.id) }

    fun forNetwork(network: WebRTCNetwork): JsonObject {
        if (network.isLocal) return buildJsonObject {
            put("network", network.id)
            put("kind", "local")
            put("login", normalizeLogin(localWebRTCLogin()))
            put("iceServers", defaultIceServers)
        }
        cache[network.id]?.let { return it }
        val request = HttpRequest.newBuilder(URI("${network.baseUrl}/api/shadertrinket-config"))
            .timeout(Duration.ofSeconds(10))
            .apply { network.cookieHeader()?.let { header("Cookie", it) } }
            .GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            return buildJsonObject {
                put("network", network.id)
                put("kind", "aaaframe")
                put("error", "aaaframe ${network.baseUrl} answered ${response.statusCode()} to /api/shadertrinket-config" +
                    if (response.statusCode() == 401) "; it needs the student's session cookie (WebRTCNetwork.aaaframe(url, session = ...))" else "")
                put("iceServers", defaultIceServers)
            }
        }
        val upstream = Json.parseToJsonElement(response.body()).jsonObject
        val webrtc = upstream["webrtc"]?.jsonObject
        val config = buildJsonObject {
            put("network", network.id)
            put("kind", "aaaframe")
            put("login", upstream["user"]?.jsonObject?.get("login")?.jsonPrimitive?.contentOrNull.orEmpty())
            put("iceServers", webrtc?.get("iceServers")?.jsonArray ?: defaultIceServers)
        }
        cache[network.id] = config
        return config
    }
}

/**
 * The publisher names on [network], kept current from the signalling broadcasts. For aaaframe this
 * holds one listen-only upstream socket; for the local relay it reads the relay directly.
 */
internal class PublisherLobby private constructor(val network: WebRTCNetwork) {
    @Volatile var names: List<String> = emptyList(); private set
    @Volatile var error: String? = null; private set
    private val listeners = CopyOnWriteArrayList<(List<String>) -> Unit>()
    @Volatile private var upstream: UpstreamSignaling? = null

    fun onChange(listener: (List<String>) -> Unit): () -> Unit {
        listeners += listener
        listener(names)
        return { listeners -= listener }
    }

    private fun update(next: List<String>) {
        names = next
        listeners.forEach { runCatching { it(next) } }
    }

    private fun start() {
        if (network.isLocal) {
            update(LocalSignalingRelay.shared.publishers())
            LocalSignalingRelay.shared.onPublishersChanged(::update)
            return
        }
        connectUpstream()
    }

    private var generation = 0

    /** Opens a fresh upstream socket, unless one newer than [after] already replaced the old one. */
    @Synchronized private fun connectUpstream(after: Int = generation) {
        if (after != generation) return
        val mine = ++generation
        upstream?.close()
        val current = sortedSetOf<String>()
        upstream = UpstreamSignaling(network, onText = { text ->
            val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@UpstreamSignaling
            synchronized(current) {
                when (message.string("type")) {
                    "hello" -> { current.clear(); message["publishers"]?.jsonArray?.forEach { current += it.jsonPrimitive.content } }
                    "publisher-online" -> current += message.string("username")
                    "publisher-offline" -> current -= message.string("username")
                    else -> return@UpstreamSignaling
                }
                error = null
                update(current.toList())
            }
        }, onClose = { reason ->
            if (mine == generation) {
                error = reason
                update(emptyList())
                // Reconnect while anyone is listening; aaaframe restarts in class happen. A refused
                // login waits longer, and a sign-in reconnects at once (signedIn).
                val delay = if (reason == LOGIN_REFUSED) 15_000L else 3_000L
                Thread({ Thread.sleep(delay); if (listeners.isNotEmpty()) connectUpstream(after = mine) }, "webrtc-lobby-retry")
                    .apply { isDaemon = true }.start()
            }
        })
    }

    /** A sign-in arrived: replace the refused socket with one carrying the new cookie, now. */
    fun signedIn() {
        if (!network.isLocal) synchronized(this) { connectUpstream(after = generation) }
    }

    companion object {
        private val lobbies = ConcurrentHashMap<String, PublisherLobby>()
        fun of(network: WebRTCNetwork): PublisherLobby =
            lobbies.computeIfAbsent(network.id) { PublisherLobby(network).also(PublisherLobby::start) }

        fun existing(network: WebRTCNetwork): PublisherLobby? = lobbies[network.id]
    }
}

/**
 * `/ws/webrtc-signaling?network=<id>` and `/api/webrtc-config?network=<id>`: what the sink and
 * source pages talk to. Installed beside Field's other WebSockets.
 */
fun Route.installWebRTCSignalingRoutes() {
    get("/api/webrtc-config") {
        val network = WebRTCNetwork.lookup(call.request.queryParameters["network"])
            ?: return@get call.respond(HttpStatusCode.NotFound, "unknown WebRTC network; register it with WebRTCNetwork.aaaframe(...)")
        val config = runCatching { withContext(Dispatchers.IO) { WebRTCConfig.forNetwork(network) } }.getOrElse { error ->
            buildJsonObject {
                put("network", network.id)
                put("error", "could not reach ${network.baseUrl}: ${error.message}")
                put("iceServers", defaultIceServers)
            }
        }
        call.respondText(config.toString(), ContentType.Application.Json)
    }

    webSocket("/ws/webrtc-signaling") {
        val network = WebRTCNetwork.lookup(call.request.queryParameters["network"])
        if (network == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown WebRTC network"))
            return@webSocket
        }
        // Outgoing frames go through one channel so neither the relay nor the upstream listener
        // ever suspends or blocks on this socket.
        val outgoing = Channel<String>(Channel.UNLIMITED)
        val writer = launch { for (text in outgoing) send(Frame.Text(text)) }
        val peer = object : SignalingPeer { override fun deliver(text: String) { outgoing.trySend(text) } }
        if (network.isLocal) {
            val relay = LocalSignalingRelay.shared
            val id = relay.connect(peer)
            try {
                for (frame in incoming) if (frame is Frame.Text) relay.receive(id, frame.readText())
            } finally {
                relay.disconnect(id)
                outgoing.close()
                writer.cancel()
            }
        } else {
            val closed = kotlinx.coroutines.CompletableDeferred<String>()
            val upstream = UpstreamSignaling(network, onText = peer::deliver, onClose = { closed.complete(it) })
            val reader = launch {
                for (frame in incoming) if (frame is Frame.Text) upstream.send(frame.readText())
                closed.complete("page closed")
            }
            val reason = closed.await()
            if (reason != "page closed") {
                lprintln("info,webrtc", "aaaframe signalling for ${network.baseUrl} ended: $reason")
                outgoing.trySend(buildJsonObject { put("type", "field-error"); put("error", reason) }.toString())
            }
            upstream.close()
            outgoing.close()
            writer.join()
            reader.cancel()
            close(CloseReason(CloseReason.Codes.NORMAL, reason.take(100)))
        }
    }
}

@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.mqtt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One received message. [text], [number] and [json] read the payload. */
class MqttMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: QoS = QoS.AT_MOST_ONCE,
    /** True when this is the broker replaying a retained message to a new subscription. */
    val retained: Boolean = false,
    /** `System.nanoTime()` when it arrived. */
    val receivedNanos: Long = System.nanoTime(),
) {
    /** The payload as UTF-8. */
    val text: String get() = payload.decodeToString()

    /**
     * The payload as a number: plain text (`"21.5"`), or a JSON number. Null when it is neither —
     * for a JSON object, use [json] or [number] with a key.
     */
    val number: Double? get() = text.trim().toDoubleOrNull() ?: (jsonOrNull as? JsonPrimitive)?.doubleOrNull

    /** The payload parsed as JSON; throws on text that is not JSON. */
    val json: JsonElement get() = Json.parseToJsonElement(text)

    /** [json], or null when the payload is not JSON. */
    val jsonOrNull: JsonElement? get() = runCatching { json }.getOrNull()

    /**
     * A number inside a JSON payload, by a dotted path: `number("temp")` for `{"temp": 21.5}`,
     * `number("env.temp")`, `number("values.2")` for an array index.
     */
    fun number(path: String): Double? = (at(path) as? JsonPrimitive)?.doubleOrNull

    /** A string inside a JSON payload, by dotted path; see [number]. */
    fun string(path: String): String? = (at(path) as? JsonPrimitive)?.content

    /** The JSON element at a dotted [path], or null. */
    fun at(path: String): JsonElement? {
        var e: JsonElement = jsonOrNull ?: return null
        for (key in path.split('.')) {
            e = when (e) {
                is JsonObject -> e[key] ?: return null
                is JsonArray -> e.getOrNull(key.toIntOrNull() ?: return null) ?: return null
                else -> return null
            }
        }
        return e
    }

    override fun toString() = "MqttMessage($topic = ${if (payload.size <= 64) text else "${payload.size} bytes"})"
}

/** A subscription made by [MqttClient.subscribe]; [close] it to stop the callback. */
class MqttSubscription internal constructor(
    val filter: String,
    val qos: QoS,
    internal val handler: (MqttMessage) -> Unit,
    private val owner: MqttClient,
) : AutoCloseable {
    @Volatile
    var isClosed = false
        internal set

    /** Messages delivered to this subscription so far. */
    val count = AtomicLong()

    override fun close() = owner.unsubscribe(this)
}

/** A connection change, passed to [MqttClient.onConnection]. */
data class MqttConnectionEvent(val connected: Boolean, val reason: String)

/**
 * An MQTT 3.1.1 client over plain TCP.
 *
 * - **QoS 0 and 1.** A QoS 1 publish is held until its PUBACK arrives and re-sent (with DUP) after a
 *   reconnect; the returned future completes on PUBACK. A QoS 2 publish is sent and completed with
 *   PUBREL/PUBCOMP, but resend of an interrupted QoS 2 flow is not implemented.
 * - **Reconnect.** With [autoReconnect], a dropped connection is retried with backoff (0.25 s
 *   doubling to 8 s), and every live subscription is made again on the new connection.
 * - **Keepalive.** A PINGREQ goes out when nothing has been sent for [keepAliveSeconds]; no
 *   PINGRESP within the same interval drops the connection (and reconnects).
 * - **Last will** ([will]) is published by the broker if this client vanishes without [close].
 *
 * Callbacks run through [dispatch]. In Field, [afield.io.mqtt.mqtt] passes one that queues onto the
 * main Runloop, so a callback may touch boxes, sliders and graphics like any other box code; the
 * default here calls on the client's reader thread, which is what tests and `jvm-main` want.
 *
 * [url] is `tcp://host[:port]` (also `mqtt://`); the port defaults to 1883.
 */
class MqttClient(
    val url: String,
    val clientId: String = "field4-" + UUID.randomUUID().toString().take(8),
    val keepAliveSeconds: Int = 30,
    val cleanSession: Boolean = true,
    val will: MqttPacket.Will? = null,
    val username: String? = null,
    val password: String? = null,
    val autoReconnect: Boolean = true,
    val connectTimeoutMs: Int = 3000,
    private val dispatch: (Runnable) -> Unit = { it.run() },
) : AutoCloseable {

    val host: String
    val port: Int

    init {
        val u = URI(if ("://" in url) url else "tcp://$url")
        require(u.scheme in setOf("tcp", "mqtt")) { "only tcp:// / mqtt:// urls are supported, not '$url'" }
        host = u.host ?: throw IllegalArgumentException("no host in '$url'")
        port = if (u.port > 0) u.port else Mqtt.PORT
    }

    @Volatile
    var isClosed = false
        private set

    @Volatile
    var isConnected = false
        private set

    /** Why the last connection attempt failed or dropped, for a status line. */
    @Volatile
    var lastError: String? = null
        private set

    /** Successful connections so far (1 + reconnects). */
    val connections = AtomicInteger()

    /** Messages received, publishes sent. */
    val received = AtomicLong()
    val sent = AtomicLong()

    /** The most recent message on every topic received, by topic. */
    val latest = ConcurrentHashMap<String, MqttMessage>()

    /** Called (through [dispatch]) when the connection comes up or goes down. */
    val onConnection = CopyOnWriteArrayList<(MqttConnectionEvent) -> Unit>()

    /** Called on the reader thread for every message, before any subscription callback. Keep it short. */
    val onReceive = CopyOnWriteArrayList<(MqttMessage) -> Unit>()

    /** Local handlers. Separate from [brokerSubscriptions], the cover of their filters. */
    private val subscriptions = CopyOnWriteArrayList<MqttSubscription>()
    /** Filters subscribed at the broker on this connection, with their QoS. Guarded by [coverLock]. */
    private val brokerSubs = LinkedHashMap<String, QoS>()
    private val coverLock = Any()
    /** Retained messages the client has seen, by topic, replayed to handlers the cover hides from the broker. */
    private val retainedCache = ConcurrentHashMap<String, MqttMessage>()
    private val ids = AtomicInteger(0)
    private val pendingAcks = ConcurrentHashMap<Int, CompletableFuture<Unit>>()
    /** Unacknowledged QoS 1 publishes, re-sent with DUP after a reconnect. */
    private val unacked = ConcurrentHashMap<Int, MqttPacket.Publish>()
    private val lock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var lastSendNanos = 0L
    @Volatile private var pingSentNanos = 0L
    private var connectedFuture = CompletableFuture<Unit>()

    private val thread = Thread(::run, "mqtt-client-$clientId").apply { isDaemon = true; start() }

    private fun nextId(): Int {
        while (true) {
            val id = ids.updateAndGet { it % 65535 + 1 }
            if (!pendingAcks.containsKey(id)) return id
        }
    }

    /** Blocks until connected; false after [timeoutMs]. */
    fun awaitConnected(timeoutMs: Long = 5000): Boolean = try {
        synchronized(lock) { connectedFuture }.get(timeoutMs, TimeUnit.MILLISECONDS); true
    } catch (_: Exception) { isConnected }

    private fun send(p: MqttPacket): Boolean {
        val o = out ?: return false
        return try {
            synchronized(o) { o.write(Mqtt.encode(p)); o.flush() }
            lastSendNanos = System.nanoTime()
            true
        } catch (e: IOException) {
            drop("send failed: ${e.message}"); false
        }
    }

    /**
     * Publish [payload] to [topic]. With [retain], the broker keeps it and hands it to every later
     * subscriber; an empty retained payload clears the topic. The future completes when the
     * message has left (QoS 0) or been acknowledged (QoS 1/2). A QoS 0 message published while
     * disconnected is dropped and its future fails; QoS 1 is queued and sent on reconnect.
     */
    fun publish(topic: String, payload: ByteArray, qos: QoS = QoS.AT_MOST_ONCE, retain: Boolean = false): CompletableFuture<Unit> {
        require(MqttTopic.isValidName(topic)) { "'$topic' is not a topic you can publish to (no + or #, not empty)" }
        check(!isClosed) { "client closed" }
        val f = CompletableFuture<Unit>()
        if (retain) {
            if (payload.isEmpty()) retainedCache.remove(topic) else retainedCache[topic] = MqttMessage(topic, payload, qos, retained = true)
        }
        if (qos == QoS.AT_MOST_ONCE) {
            if (send(MqttPacket.Publish(topic, payload, qos, retain))) { sent.incrementAndGet(); f.complete(Unit) }
            else f.completeExceptionally(IOException("not connected to $url"))
            return f
        }
        val id = nextId()
        val p = MqttPacket.Publish(topic, payload, qos, retain, packetId = id)
        pendingAcks[id] = f
        if (qos == QoS.AT_LEAST_ONCE) unacked[id] = p
        if (send(p)) sent.incrementAndGet()
        return f
    }

    fun publish(topic: String, payload: String, qos: QoS = QoS.AT_MOST_ONCE, retain: Boolean = false) =
        publish(topic, payload.encodeToByteArray(), qos, retain)

    /**
     * Subscribe to [filter] (`+` one level, `#` the rest). Retained matches arrive at once.
     *
     * The broker sees only a *cover* of the local filters ([MqttTopic.cover]): a filter another one
     * already subsumes (`a/+` under `a/#`) sends no SUBSCRIBE, so the broker sends each message
     * once and each matching handler gets it once. Such a filter's retained replay comes from the
     * client's cache of retained messages seen, which knows only what the cover has delivered.
     */
    fun subscribe(filter: String, qos: QoS = QoS.AT_MOST_ONCE, handler: (MqttMessage) -> Unit): MqttSubscription {
        require(MqttTopic.isValidFilter(filter)) { "'$filter' is not a valid topic filter" }
        val s = MqttSubscription(filter, qos, handler, this)
        synchronized(coverLock) {
            subscriptions.add(s)
            val sent = updateCover()
            // No SUBSCRIBE covering it went out, so the broker will not replay retained messages for it.
            // (Disconnected: the SUBSCRIBE on connect replays them.)
            val hidden = sent != null && sent.keys.none { MqttTopic.subsumes(it, filter) }
            if (hidden) retainedCache.values.filter { MqttTopic.matches(filter, it.topic) }.forEach { m ->
                dispatch(Runnable { if (!s.isClosed) { s.count.incrementAndGet(); s.handler(m) } })
            }
        }
        return s
    }

    internal fun unsubscribe(s: MqttSubscription) {
        if (s.isClosed) return
        s.isClosed = true
        synchronized(coverLock) {
            subscriptions.remove(s)
            updateCover()
        }
    }

    /**
     * Bring the broker's subscriptions to the cover of the local filters. New or upgraded filters
     * are subscribed before superseded ones are unsubscribed, so no matching message falls in a gap
     * (one may arrive twice during the swap). Not connected: nothing is sent; connecting resends all.
     */
    private fun updateCover(): Map<String, QoS>? = synchronized(coverLock) {
        if (out == null) return@synchronized null
        val wanted = MqttTopic.cover(subscriptions.groupBy { it.filter }.mapValues { (_, l) -> QoS.of(l.maxOf { it.qos.value }) })
        val add = wanted.filter { (f, q) -> brokerSubs[f] != q }
        val remove = brokerSubs.keys.filter { it !in wanted }
        if (add.isNotEmpty()) {
            if (!send(MqttPacket.Subscribe(nextId(), add.toList()))) return@synchronized null
            brokerSubs.putAll(add)
        }
        if (remove.isNotEmpty()) {
            if (!send(MqttPacket.Unsubscribe(nextId(), remove))) return@synchronized null
            remove.forEach { brokerSubs.remove(it) }
        }
        add
    }

    /** The live subscriptions' filters. */
    val filters: List<String> get() = subscriptions.map { it.filter }.distinct()

    /** The filters subscribed at the broker: the cover of [filters]. */
    val brokerSubscriptions: Map<String, QoS> get() = synchronized(coverLock) { brokerSubs.toMap() }

    private fun run() {
        var backoff = 250L
        while (!isClosed) {
            try {
                connectOnce()
                backoff = 250L
                readLoop()
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
            }
            drop(lastError ?: "connection closed")
            if (!autoReconnect || isClosed) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(8000)
        }
    }

    private fun connectOnce() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = s
        if (isClosed) { s.close(); return }
        val o = s.getOutputStream()
        val input = BufferedInputStream(s.getInputStream())
        s.soTimeout = connectTimeoutMs
        // [out] stays null until CONNACK: a publish or subscribe made meanwhile must not reach the
        // socket ahead of CONNECT (mosquitto drops such a client); it is queued and sent below.
        o.write(Mqtt.encode(MqttPacket.Connect(clientId, cleanSession, keepAliveSeconds, will, username, password?.encodeToByteArray())))
        o.flush()
        lastSendNanos = System.nanoTime()
        val ack = Mqtt.read(input) as? MqttPacket.ConnAck ?: throw MqttProtocolException("expected CONNACK")
        if (ack.returnCode != 0) {
            throw MqttProtocolException("broker refused: ${Mqtt.connackReason(ack.returnCode)}")
        }
        synchronized(coverLock) {
            brokerSubs.clear(); out = o
            // Queued publishes first, under the lock so no SUBSCRIBE overtakes them: a retained message
            // published before a subscribe call, while connecting, must reach the broker first, or
            // the subscription gets it live, not retained.
            unacked.values.sortedBy { it.packetId }.forEach { send(it.copy(dup = true)) }
        }
        s.soTimeout = 250 // so the reader can check the keepalive clock
        pingSentNanos = 0L
        isConnected = true
        lastError = null
        connections.incrementAndGet()
        // Subscriptions are per connection with a clean session: make the cover again.
        updateCover()
        synchronized(lock) { connectedFuture.complete(Unit) }
        val ev = MqttConnectionEvent(true, "connected to $url")
        onConnection.forEach { cb -> dispatch(Runnable { cb(ev) }) }
        this.input = input
    }

    @Volatile private var input: BufferedInputStream? = null

    private fun readLoop() {
        val input = this.input ?: return
        val keepNanos = keepAliveSeconds * 1_000_000_000L
        while (!isClosed) {
            val p = try {
                Mqtt.read(input) ?: return
            } catch (_: java.net.SocketTimeoutException) {
                null
            }
            if (keepNanos > 0) {
                val now = System.nanoTime()
                if (pingSentNanos != 0L && now - pingSentNanos > keepNanos) throw IOException("no PINGRESP within ${keepAliveSeconds}s")
                if (pingSentNanos == 0L && now - lastSendNanos > keepNanos * 3 / 4) {
                    pingSentNanos = now; send(MqttPacket.PingReq)
                }
            }
            if (p != null) handle(p)
        }
    }

    private fun handle(p: MqttPacket) {
        when (p) {
            is MqttPacket.Publish -> {
                when (p.qos) {
                    QoS.AT_MOST_ONCE -> {}
                    QoS.AT_LEAST_ONCE -> send(MqttPacket.PubAck(p.packetId))
                    QoS.EXACTLY_ONCE -> send(MqttPacket.PubRec(p.packetId))
                }
                val m = MqttMessage(p.topic, p.payload, p.qos, p.retain)
                received.incrementAndGet()
                latest[p.topic] = m
                onReceive.forEach { runCatching { it(m) } }
                if (p.retain) {
                    if (p.payload.isEmpty()) retainedCache.remove(p.topic) else retainedCache[p.topic] = m
                } else retainedCache[p.topic]?.let { old ->
                    // A live message is not marked retained even when it was published retained, so a
                    // different live payload may have replaced the retained one: forget rather than replay stale.
                    if (!old.payload.contentEquals(p.payload)) retainedCache.remove(p.topic)
                }
                subscriptions.filter { MqttTopic.matches(it.filter, p.topic) }.forEach { s ->
                    dispatch(Runnable {
                        if (!s.isClosed) { s.count.incrementAndGet(); s.handler(m) }
                    })
                }
            }
            is MqttPacket.PubAck -> { unacked.remove(p.packetId); pendingAcks.remove(p.packetId)?.complete(Unit) }
            is MqttPacket.PubRec -> send(MqttPacket.PubRel(p.packetId))
            is MqttPacket.PubComp -> pendingAcks.remove(p.packetId)?.complete(Unit)
            is MqttPacket.PubRel -> send(MqttPacket.PubComp(p.packetId))
            MqttPacket.PingResp -> pingSentNanos = 0L
            is MqttPacket.SubAck -> if (p.returnCodes.any { it == 0x80 }) lastError = "broker refused a subscription"
            else -> {}
        }
    }

    private fun drop(reason: String) {
        val s = socket ?: return
        socket = null; out = null; input = null
        try { s.close() } catch (_: IOException) {}
        synchronized(coverLock) { brokerSubs.clear() } // after close, so a writer blocked under the lock is released
        if (isConnected) {
            isConnected = false
            synchronized(lock) { connectedFuture = CompletableFuture() }
            val ev = MqttConnectionEvent(false, reason)
            onConnection.forEach { cb -> dispatch(Runnable { cb(ev) }) }
        }
        // QoS 0 waiters and QoS 2 flows cannot survive a reconnect.
        pendingAcks.keys.filter { !unacked.containsKey(it) }.forEach { pendingAcks.remove(it)?.completeExceptionally(IOException(reason)) }
    }

    /** Disconnect cleanly (the broker does not publish the will) and stop reconnecting. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        send(MqttPacket.Disconnect)
        drop("closed")
        pendingAcks.values.forEach { it.completeExceptionally(IOException("client closed")) }
        pendingAcks.clear()
        thread.interrupt()
    }

    override fun toString() = "MqttClient($url, ${if (isConnected) "connected" else "disconnected"}, ${subscriptions.size} subscriptions)"
}

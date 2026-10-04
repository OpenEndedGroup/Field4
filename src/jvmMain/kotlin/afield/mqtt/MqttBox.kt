@file:afield.CompletionCategory("Networking")

package afield.mqtt

import afield.Box
import afield.Runloop
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Process-wide MQTT state behind `Box.mqtt`: one client per broker url, the embedded brokers, and
 * the queue that brings callbacks onto the main Runloop.
 *
 * Lifecycle follows the house rule for launched things: clients and brokers are keyed (by url and
 * by port) and **survive a box being stopped** — a sensor feed should not drop because you stopped
 * the box that plots it. Re-running a box reuses them. Subscriptions are keyed by box and key, so a
 * re-run *replaces* its callback instead of adding a second one. Stopping is explicit:
 * [BoxMqtt.unsubscribe], [BoxMqtt.close], or [closeAll] from a "panic" box.
 */
object MqttConnections {
    private val clients = ConcurrentHashMap<String, MqttClient>()
    private val brokers = ConcurrentHashMap<Int, MqttBroker>()
    private val slots = ConcurrentHashMap<String, Slot>()
    private val histories = ConcurrentHashMap<String, ConcurrentHashMap<String, History>>()

    /** How many numeric values per topic [BoxMqtt.series] keeps. */
    @Volatile
    var historyLength = 512

    private val queue = ConcurrentLinkedQueue<Runnable>()
    @Volatile private var drainInstalled = false

    /**
     * Runs [r] on the main Runloop when there is one (inside Field), directly otherwise (tests,
     * `jvm-main`, where no Runloop turns and a queued callback would never run).
     */
    fun dispatch(r: Runnable) {
        if (Runloop.mainThread == null) return r.run()
        queue.add(r)
        if (!drainInstalled) synchronized(this) {
            if (!drainInstalled) {
                drainInstalled = true
                Runloop.runLoop.preRun.setUnowned("afield.mqtt.dispatch", sequence {
                    while (true) {
                        // Bounded per frame, so a flood of messages cannot stall the Runloop.
                        var n = 0
                        while (n++ < 2000) {
                            val next = queue.poll() ?: break
                            try { next.run() } catch (t: Throwable) { afield.logger.lprintln("error,mqtt", "callback threw: $t") }
                        }
                        yield(null)
                    }
                })
            }
        }
    }

    internal class Slot(val filter: String, val client: MqttClient, @Volatile var handler: (String, MqttMessage) -> Unit) {
        lateinit var subscription: MqttSubscription
    }

    internal class History(capacity: Int) {
        private val values = DoubleArray(capacity)
        private var start = 0
        private var size = 0
        @Synchronized fun add(v: Double) {
            if (size < values.size) values[(start + size++) % values.size] = v
            else { values[start] = v; start = (start + 1) % values.size }
        }
        @Synchronized fun toList(): List<Double> = List(size) { values[(start + it) % values.size] }
    }

    /** The embedded broker on [port], started on first use and kept until [closeAll]. */
    fun broker(port: Int = Mqtt.PORT, bindAddress: String = "127.0.0.1"): MqttBroker =
        brokers.compute(port) { _, old -> if (old != null && !old.isClosed) old else MqttBroker(port, bindAddress) }!!

    /**
     * The url `Box.mqtt` connects to: `AFIELD_MQTT_URL` if set, else an embedded broker on 1883, or
     * on a free port if something else already has 1883.
     */
    val defaultUrl: String by lazy {
        System.getenv("AFIELD_MQTT_URL")?.takeIf { it.isNotBlank() } ?: try {
            broker(Mqtt.PORT).url
        } catch (_: BindException) {
            broker(0).also { brokers[it.port] = it }.url
        }
    }

    /** The client for [url], made (and connected in the background) on first use. */
    fun client(url: String, clientId: String? = null, will: MqttPacket.Will? = null, username: String? = null, password: String? = null): MqttClient =
        clients.compute(url) { _, old ->
            if (old != null && !old.isClosed) old
            else MqttClient(
                url, clientId = clientId ?: ("field4-" + java.util.UUID.randomUUID().toString().take(8)),
                will = will, username = username, password = password, dispatch = ::dispatch,
            ).also { c -> watch(url, c) }
        }!!

    private fun watch(url: String, c: MqttClient) {
        val h = histories.computeIfAbsent(url) { ConcurrentHashMap() }
        // Recorded on the reader thread from whatever the client's subscriptions bring in.
        c.onReceive.add { m -> m.number?.let { v -> h.computeIfAbsent(m.topic) { History(historyLength) }.add(v) } }
    }

    internal fun history(url: String, topic: String): List<Double> = histories[url]?.get(topic)?.toList() ?: emptyList()

    internal fun subscribe(box: Box, client: MqttClient, key: String, filter: String, qos: QoS, handler: (String, MqttMessage) -> Unit) {
        val k = "${box.id}|${client.url}|$key"
        slots.compute(k) { _, old ->
            if (old != null && old.filter == filter && old.client === client && !old.subscription.isClosed) {
                old.handler = handler; old
            } else {
                old?.subscription?.close()
                Slot(filter, client, handler).also { slot ->
                    slot.subscription = client.subscribe(filter, qos) { m ->
                        Box.withBoxContextNow(box) {
                            try { slot.handler(m.topic, m) } catch (t: Throwable) { box.println("mqtt '$filter': $t") }
                        }
                    }
                }
            }
        }
    }

    internal fun unsubscribe(box: Box, url: String?, key: String?) {
        slots.entries.removeIf { (k, slot) ->
            val parts = k.split('|', limit = 3)
            val hit = parts[0] == box.id && (url == null || parts[1] == url) && (key == null || parts[2] == key)
            if (hit) slot.subscription.close()
            hit
        }
    }

    internal fun subscriptionKeys(box: Box): List<String> = slots.keys.filter { it.startsWith("${box.id}|") }.map { it.split('|', limit = 3)[2] }

    fun closeClient(url: String) {
        clients.remove(url)?.close()
        slots.entries.removeIf { it.value.client.url == url }
    }

    /** Close every client and embedded broker. */
    fun closeAll() {
        slots.clear()
        clients.values.forEach { it.close() }; clients.clear()
        brokers.values.forEach { it.close() }; brokers.clear()
    }
}

/**
 * MQTT from a box, against one broker. Get one with `mqtt` (the default broker) or
 * `mqtt("tcp://192.168.1.10:1883")`.
 */
class BoxMqtt internal constructor(private val box: Box, val client: MqttClient) {

    val url: String get() = client.url
    val connected: Boolean get() = client.isConnected

    /**
     * Call [handler] with `(topic, message)` for every message matching [filter]. `+` matches one
     * level (`sensors/+/temp`), `#` everything below (`sensors/#`). Runs on the main Runloop.
     *
     * Re-running the box replaces the handler for the same [key] (the filter by default), so a box
     * can be edited and re-run without callbacks piling up. Stopping the box leaves it subscribed;
     * [unsubscribe] ends it.
     */
    fun subscribe(filter: String, qos: Int = 0, key: String = filter, handler: (topic: String, message: MqttMessage) -> Unit) {
        MqttConnections.subscribe(box, client, key, filter, QoS.of(qos), handler)
    }

    /** End this box's subscription under [key], or all of them on this broker. */
    fun unsubscribe(key: String? = null) = MqttConnections.unsubscribe(box, client.url, key)

    /** This box's subscription keys on this broker. */
    val subscriptions: List<String> get() = MqttConnections.subscriptionKeys(box)

    /**
     * Publish [value] to [topic]. A `String` or number goes as text, a `ByteArray` as is, and a `Map`,
     * `List` or `JsonElement` as JSON. [retain] keeps it on the broker for later subscribers.
     */
    fun publish(topic: String, value: Any?, qos: Int = 0, retain: Boolean = false) {
        client.publish(topic, encode(value), QoS.of(qos), retain)
    }

    /** Clear a retained message. */
    fun clearRetained(topic: String) {
        client.publish(topic, ByteArray(0), QoS.AT_MOST_ONCE, retain = true)
    }

    /** The latest message on every topic this connection has received. */
    val latest: Map<String, MqttMessage> get() = client.latest

    /** The latest message on [topic], or null. */
    operator fun get(topic: String): MqttMessage? = client.latest[topic]

    /** The latest numeric value on [topic] (or [default]); with [path], a number inside JSON. */
    fun number(topic: String, path: String? = null, default: Double = 0.0): Double {
        val m = client.latest[topic] ?: return default
        return (if (path == null) m.number else m.number(path)) ?: default
    }

    /** The latest text on [topic], or [default]. */
    fun text(topic: String, default: String = ""): String = client.latest[topic]?.text ?: default

    /**
     * The recent numeric values on [topic], oldest first (up to `MqttConnections.historyLength`), for
     * `showSignals("temp" to mqtt.series("sensors/a/temp"))`.
     */
    fun series(topic: String): List<Double> = MqttConnections.history(client.url, topic)

    /** Disconnect from this broker and forget its subscriptions (every box's). */
    fun close() = MqttConnections.closeClient(client.url)

    override fun toString() = "mqtt ${client.url}: ${if (connected) "connected" else "connecting (${client.lastError ?: "…"})"}, " +
        "${client.received.get()} received, ${client.sent.get()} sent, ${client.latest.size} topics"

    companion object {
        /** How [publish] turns a value into bytes. */
        fun encode(value: Any?): ByteArray = when (value) {
            null -> ByteArray(0)
            is ByteArray -> value
            is String -> value.encodeToByteArray()
            is Number, is Boolean -> value.toString().encodeToByteArray()
            else -> toJson(value).toString().encodeToByteArray()
        }

        /** Maps, lists, numbers, strings and booleans to JSON. */
        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
            is Iterable<*> -> buildJsonArray { value.forEach { add(toJson(it)) } }
            is Array<*> -> toJson(value.toList())
            is DoubleArray -> toJson(value.toList())
            is FloatArray -> toJson(value.toList())
            is IntArray -> toJson(value.toList())
            else -> JsonPrimitive(value.toString())
        }
    }
}

/** MQTT against the default broker (an embedded one unless `AFIELD_MQTT_URL` is set). */
val Box.mqtt: BoxMqtt get() = BoxMqtt(this, MqttConnections.client(MqttConnections.defaultUrl))

/**
 * MQTT against the broker at [url] (`tcp://host:1883`). The first call for a url makes the
 * connection, with [clientId], [will] (`topic to payload`, published retained by the broker if Field
 * vanishes) and credentials; later calls reuse it.
 */
fun Box.mqtt(
    url: String,
    clientId: String? = null,
    will: Pair<String, String>? = null,
    username: String? = null,
    password: String? = null,
): BoxMqtt = BoxMqtt(this, MqttConnections.client(
    url, clientId, will?.let { MqttPacket.Will(it.first, it.second.encodeToByteArray(), QoS.AT_LEAST_ONCE, retain = true) }, username, password,
))

/**
 * Start (or reuse) an embedded broker on [port]. Bind to `"0.0.0.0"` so devices on the network can
 * connect to this machine. It keeps running when the box stops.
 */
fun Box.mqttBroker(port: Int = Mqtt.PORT, bindAddress: String = "127.0.0.1"): MqttBroker = MqttConnections.broker(port, bindAddress)

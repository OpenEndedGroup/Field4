@file:afield.runtime.completion.CompletionCategory("Networking")

package afield.io.mqtt

import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A small MQTT 3.1.1 broker that runs inside Field, so boxes, tests and demos can talk MQTT with
 * nothing installed and no network.
 *
 * What it does: QoS 0 and 1 (a QoS 2 subscription is granted as 1, a QoS 2 publish is completed with
 * PUBREC/PUBCOMP and forwarded as 1), retained messages (an empty retained payload clears the
 * topic), last will on an unclean disconnect or a missed keepalive (1.5 × the client's interval),
 * client-id takeover (a second CONNECT with the same id closes the first), `$`-topics hidden from
 * leading wildcards. What it does not: persistent sessions (`cleanSession = false` is accepted but
 * the session is not kept across connections), authentication, TLS.
 *
 * [port] 0 picks a free port; read the chosen one from [port] after construction. Bound to
 * [bindAddress], loopback by default — pass `"0.0.0.0"` to let ESP32s on the LAN connect.
 */
class MqttBroker(
    port: Int = 0,
    bindAddress: String = "127.0.0.1",
    /** Largest packet accepted from a client. */
    val maxPacketSize: Int = 1 shl 20,
) : AutoCloseable {

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(bindAddress), port))
    }

    /** The bound port. */
    val port: Int = server.localPort

    /** `tcp://127.0.0.1:<port>`, the url a [MqttClient] connects to. */
    val url: String get() = "tcp://127.0.0.1:$port"

    @Volatile
    var isClosed = false
        private set

    private val sessions = ConcurrentHashMap<String, Session>()
    private val retained = ConcurrentHashMap<String, MqttPacket.Publish>()
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "mqtt-broker-$port").apply { isDaemon = true } }

    /** Called (on a broker thread) for every message the broker routes; for logging and tests. */
    val onMessage = CopyOnWriteArrayList<(clientId: String, MqttPacket.Publish) -> Unit>()

    /** Ids of the connected clients. */
    val clients: Set<String> get() = sessions.keys.toSet()

    /** Topics holding a retained message. */
    val retainedTopics: Set<String> get() = retained.keys.toSet()

    init {
        workers.execute {
            while (!isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                workers.execute { serve(s) }
            }
        }
    }

    /** Publish from inside the process, as if a client had sent it. */
    fun publish(topic: String, payload: ByteArray, qos: QoS = QoS.AT_MOST_ONCE, retain: Boolean = false) {
        require(MqttTopic.isValidName(topic)) { "bad topic '$topic'" }
        route("\$broker", MqttPacket.Publish(topic, payload, qos, retain))
    }

    fun publish(topic: String, payload: String, qos: QoS = QoS.AT_MOST_ONCE, retain: Boolean = false) =
        publish(topic, payload.encodeToByteArray(), qos, retain)

    private inner class Session(val socket: Socket, val connect: MqttPacket.Connect) {
        val out: OutputStream = socket.getOutputStream()
        val subscriptions = ConcurrentHashMap<String, QoS>()
        private var nextId = 1
        /** QoS 1 messages sent to this client and not yet acknowledged, by packet id. */
        val inflight = ConcurrentHashMap<Int, MqttPacket.Publish>()
        @Volatile var will: MqttPacket.Will? = connect.will

        @Synchronized
        fun send(p: MqttPacket) {
            try { out.write(Mqtt.encode(p)); out.flush() } catch (_: IOException) { close() }
        }

        @Synchronized
        fun deliver(p: MqttPacket.Publish, granted: QoS, retainFlag: Boolean) {
            val qos = if (p.qos.value < granted.value) p.qos else granted
            val q = if (qos == QoS.EXACTLY_ONCE) QoS.AT_LEAST_ONCE else qos
            if (q == QoS.AT_MOST_ONCE) {
                send(MqttPacket.Publish(p.topic, p.payload, q, retainFlag))
            } else {
                var id = nextId
                while (inflight.containsKey(id)) id = id % 65535 + 1
                nextId = id % 65535 + 1
                val out = MqttPacket.Publish(p.topic, p.payload, q, retainFlag, packetId = id)
                if (inflight.size < 65535) inflight[id] = out
                send(out)
            }
        }

        fun close() { try { socket.close() } catch (_: IOException) {} }
    }

    private fun serve(socket: Socket) {
        var session: Session? = null
        var clean = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000 // a client has 10 s to send CONNECT
            val input = BufferedInputStream(socket.getInputStream())
            val first = Mqtt.read(input, maxPacketSize) as? MqttPacket.Connect ?: return socket.close()
            if (first.protocolLevel != 4 && first.protocolLevel != 3) {
                // An MQTT 5 client parses CONNACK as v5 (with a properties length), so it gets the v5
                // form with 0x84 "unsupported protocol version" (MQTT 5 §3.2.2.2); older ones get 3.1.1's 0x01.
                val refusal = if (first.protocolLevel >= 5) byteArrayOf(0x20, 3, 0, 0x84.toByte(), 0) else Mqtt.encode(MqttPacket.ConnAck(false, 1))
                socket.getOutputStream().write(refusal); return socket.close()
            }
            val id = first.clientId.ifEmpty {
                if (!first.cleanSession) {
                    socket.getOutputStream().write(Mqtt.encode(MqttPacket.ConnAck(false, 2))); return socket.close()
                }
                "auto-" + java.util.UUID.randomUUID()
            }
            val s = Session(socket, first.copy(clientId = id))
            session = s
            sessions.put(id, s)?.let { old -> old.will = null; old.close() }
            s.send(MqttPacket.ConnAck(false, 0))
            // §3.1.2.10: the server disconnects after 1.5 × keepalive of silence.
            socket.soTimeout = if (first.keepAliveSeconds > 0) first.keepAliveSeconds * 1500 else 0
            while (!isClosed) {
                val p = Mqtt.read(input, maxPacketSize) ?: break
                when (p) {
                    is MqttPacket.Publish -> {
                        when (p.qos) {
                            QoS.AT_MOST_ONCE -> {}
                            QoS.AT_LEAST_ONCE -> s.send(MqttPacket.PubAck(p.packetId))
                            QoS.EXACTLY_ONCE -> s.send(MqttPacket.PubRec(p.packetId))
                        }
                        route(id, p)
                    }
                    is MqttPacket.PubRel -> s.send(MqttPacket.PubComp(p.packetId))
                    is MqttPacket.PubAck -> s.inflight.remove(p.packetId)
                    is MqttPacket.PubRec -> s.send(MqttPacket.PubRel(p.packetId))
                    is MqttPacket.PubComp -> s.inflight.remove(p.packetId)
                    is MqttPacket.Subscribe -> {
                        val codes = p.filters.map { (f, q) ->
                            if (!MqttTopic.isValidFilter(f)) 0x80
                            else {
                                val granted = if (q == QoS.EXACTLY_ONCE) QoS.AT_LEAST_ONCE else q
                                s.subscriptions[f] = granted
                                granted.value
                            }
                        }
                        s.send(MqttPacket.SubAck(p.packetId, codes))
                        // §3.3.1.3: matching retained messages go out after the SUBACK, retain flag set.
                        p.filters.forEach { (f, _) ->
                            val granted = s.subscriptions[f] ?: return@forEach
                            retained.values.filter { MqttTopic.matches(f, it.topic) }.forEach { s.deliver(it, granted, true) }
                        }
                    }
                    is MqttPacket.Unsubscribe -> {
                        p.filters.forEach { s.subscriptions.remove(it) }
                        s.send(MqttPacket.UnsubAck(p.packetId))
                    }
                    MqttPacket.PingReq -> s.send(MqttPacket.PingResp)
                    MqttPacket.Disconnect -> { clean = true; break }
                    is MqttPacket.Connect -> throw MqttProtocolException("second CONNECT") // §3.1.0-2
                    else -> {}
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } finally {
            val s = session
            if (s != null && sessions.remove(s.connect.clientId, s)) {
                val will = s.will
                if (!clean && will != null && !isClosed) route(s.connect.clientId, MqttPacket.Publish(will.topic, will.payload, will.qos, will.retain))
            }
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private fun route(from: String, p: MqttPacket.Publish) {
        if (p.retain) {
            if (p.payload.isEmpty()) retained.remove(p.topic) else retained[p.topic] = p.copy(dup = false)
        }
        onMessage.forEach { runCatching { it(from, p) } }
        sessions.values.forEach { s ->
            // A client with overlapping filters gets one copy at the highest granted QoS (§3.3.5).
            var best: QoS? = null
            s.subscriptions.forEach { (f, q) -> if (MqttTopic.matches(f, p.topic) && (best == null || q.value > best.value)) best = q }
            best?.let { s.deliver(p, it, false) }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { server.close() } catch (_: IOException) {}
        sessions.values.forEach { it.close() }
        sessions.clear()
        workers.shutdownNow()
        workers.awaitTermination(1, TimeUnit.SECONDS)
    }

    override fun toString() = "MqttBroker($url, ${sessions.size} clients, ${retained.size} retained)"
}

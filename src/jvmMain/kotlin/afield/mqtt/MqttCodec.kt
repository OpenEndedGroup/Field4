@file:afield.CompletionCategory("Networking")

package afield.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** A malformed MQTT packet, or one this implementation refuses. */
class MqttProtocolException(message: String) : IOException(message)

/** Delivery guarantee of a publish or a subscription. QoS 2 is not implemented; see [Mqtt]. */
enum class QoS(val value: Int) {
    /** Fire and forget. */
    AT_MOST_ONCE(0),

    /** Acknowledged with PUBACK, re-sent until it is; may arrive twice. */
    AT_LEAST_ONCE(1),

    /** Four-step handshake. Accepted on the wire, granted as [AT_LEAST_ONCE]. */
    EXACTLY_ONCE(2);

    companion object {
        fun of(v: Int): QoS = entries.firstOrNull { it.value == v } ?: throw MqttProtocolException("bad QoS $v")
    }
}

/** An MQTT 3.1.1 control packet. [Mqtt.encode] and [Mqtt.read] convert to and from bytes. */
sealed class MqttPacket {
    data class Will(val topic: String, val payload: ByteArray, val qos: QoS = QoS.AT_MOST_ONCE, val retain: Boolean = false) {
        override fun equals(other: Any?) = other is Will && topic == other.topic && payload.contentEquals(other.payload) && qos == other.qos && retain == other.retain
        override fun hashCode() = topic.hashCode() * 31 + payload.contentHashCode()
    }

    data class Connect(
        val clientId: String,
        val cleanSession: Boolean = true,
        val keepAliveSeconds: Int = 60,
        val will: Will? = null,
        val username: String? = null,
        val password: ByteArray? = null,
        val protocolLevel: Int = 4,
    ) : MqttPacket() {
        override fun equals(other: Any?) = other is Connect && clientId == other.clientId && cleanSession == other.cleanSession &&
            keepAliveSeconds == other.keepAliveSeconds && will == other.will && username == other.username &&
            (password?.toList() == other.password?.toList()) && protocolLevel == other.protocolLevel
        override fun hashCode() = clientId.hashCode()
    }

    /** [returnCode] 0 is accepted; 1–5 are the 3.1.1 refusals (see [Mqtt.connackReason]). */
    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPacket()

    class Publish(
        val topic: String,
        val payload: ByteArray,
        val qos: QoS = QoS.AT_MOST_ONCE,
        val retain: Boolean = false,
        val dup: Boolean = false,
        /** Present only for QoS > 0. */
        val packetId: Int = 0,
    ) : MqttPacket() {
        val text: String get() = payload.decodeToString()
        fun copy(qos: QoS = this.qos, retain: Boolean = this.retain, dup: Boolean = this.dup, packetId: Int = this.packetId) =
            Publish(topic, payload, qos, retain, dup, packetId)
        override fun equals(other: Any?) = other is Publish && topic == other.topic && payload.contentEquals(other.payload) &&
            qos == other.qos && retain == other.retain && dup == other.dup && packetId == other.packetId
        override fun hashCode() = topic.hashCode() * 31 + packetId
        override fun toString() = "Publish($topic, ${payload.size} bytes, $qos, retain=$retain, dup=$dup, id=$packetId)"
    }

    data class PubAck(val packetId: Int) : MqttPacket()
    data class PubRec(val packetId: Int) : MqttPacket()
    data class PubRel(val packetId: Int) : MqttPacket()
    data class PubComp(val packetId: Int) : MqttPacket()
    data class Subscribe(val packetId: Int, val filters: List<Pair<String, QoS>>) : MqttPacket()

    /** One return code per filter: the granted QoS, or 0x80 for failure. */
    data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPacket()
    data class Unsubscribe(val packetId: Int, val filters: List<String>) : MqttPacket()
    data class UnsubAck(val packetId: Int) : MqttPacket()
    data object PingReq : MqttPacket()
    data object PingResp : MqttPacket()
    data object Disconnect : MqttPacket()
}

/**
 * The MQTT 3.1.1 wire format (OASIS standard, 2014): fixed header, remaining-length varint,
 * length-prefixed UTF-8 strings, and every control packet.
 */
object Mqtt {
    const val PORT = 1883

    /** Largest remaining length the 4-byte varint can express (§2.2.3). */
    const val MAX_REMAINING_LENGTH = 268_435_455

    fun connackReason(code: Int): String = when (code) {
        0 -> "accepted"
        1 -> "unacceptable protocol version"
        2 -> "identifier rejected"
        3 -> "server unavailable"
        4 -> "bad user name or password"
        5 -> "not authorized"
        else -> "unknown return code $code"
    }

    /** §2.2.3: seven bits per byte, least significant group first, top bit = more follows. */
    fun encodeRemainingLength(length: Int, out: ByteArrayOutputStream) {
        require(length in 0..MAX_REMAINING_LENGTH) { "remaining length $length out of range" }
        var x = length
        do {
            var b = x % 128
            x /= 128
            if (x > 0) b = b or 0x80
            out.write(b)
        } while (x > 0)
    }

    fun remainingLengthBytes(length: Int): ByteArray = ByteArrayOutputStream().also { encodeRemainingLength(length, it) }.toByteArray()

    /** Reads a remaining-length varint; refuses a fifth byte, as §2.2.3 requires. */
    fun readRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        for (i in 0 until 4) {
            val b = input.read()
            if (b < 0) throw EOFException("stream ended inside remaining length")
            value += (b and 0x7f) * multiplier
            if (b and 0x80 == 0) return value
            multiplier *= 128
        }
        throw MqttProtocolException("malformed remaining length (more than 4 bytes)")
    }

    private fun ByteArrayOutputStream.u16(v: Int) { write((v shr 8) and 0xff); write(v and 0xff) }
    private fun ByteArrayOutputStream.bin(b: ByteArray) {
        require(b.size <= 0xffff) { "field longer than 65535 bytes" }
        u16(b.size); write(b)
    }
    private fun ByteArrayOutputStream.str(s: String) = bin(s.encodeToByteArray())

    private fun frame(header: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(header)
        encodeRemainingLength(body.size, out)
        out.write(body)
        return out.toByteArray()
    }

    private fun body(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()

    fun encode(p: MqttPacket): ByteArray = when (p) {
        is MqttPacket.Connect -> frame(0x10, body {
            str(if (p.protocolLevel == 3) "MQIsdp" else "MQTT")
            write(p.protocolLevel)
            var flags = 0
            if (p.cleanSession) flags = flags or 0x02
            p.will?.let { flags = flags or 0x04 or (it.qos.value shl 3) or (if (it.retain) 0x20 else 0) }
            if (p.password != null) flags = flags or 0x40
            if (p.username != null) flags = flags or 0x80
            write(flags)
            u16(p.keepAliveSeconds)
            str(p.clientId)
            p.will?.let { str(it.topic); bin(it.payload) }
            p.username?.let { str(it) }
            p.password?.let { bin(it) }
        })
        is MqttPacket.ConnAck -> frame(0x20, byteArrayOf(if (p.sessionPresent) 1 else 0, p.returnCode.toByte()))
        is MqttPacket.Publish -> {
            val header = 0x30 or (if (p.dup) 0x08 else 0) or (p.qos.value shl 1) or (if (p.retain) 1 else 0)
            frame(header, body {
                str(p.topic)
                if (p.qos != QoS.AT_MOST_ONCE) u16(p.packetId)
                write(p.payload)
            })
        }
        is MqttPacket.PubAck -> frame(0x40, body { u16(p.packetId) })
        is MqttPacket.PubRec -> frame(0x50, body { u16(p.packetId) })
        is MqttPacket.PubRel -> frame(0x62, body { u16(p.packetId) })
        is MqttPacket.PubComp -> frame(0x70, body { u16(p.packetId) })
        is MqttPacket.Subscribe -> frame(0x82, body {
            u16(p.packetId)
            p.filters.forEach { (f, q) -> str(f); write(q.value) }
        })
        is MqttPacket.SubAck -> frame(0x90, body { u16(p.packetId); p.returnCodes.forEach { write(it) } })
        is MqttPacket.Unsubscribe -> frame(0xA2, body { u16(p.packetId); p.filters.forEach { str(it) } })
        is MqttPacket.UnsubAck -> frame(0xB0, body { u16(p.packetId) })
        MqttPacket.PingReq -> byteArrayOf(0xC0.toByte(), 0)
        MqttPacket.PingResp -> byteArrayOf(0xD0.toByte(), 0)
        MqttPacket.Disconnect -> byteArrayOf(0xE0.toByte(), 0)
    }

    /** Reads one packet; null on a clean end of stream between packets. */
    fun read(input: InputStream, maxPacketSize: Int = MAX_REMAINING_LENGTH): MqttPacket? {
        val header = input.read()
        if (header < 0) return null
        val length = readRemainingLength(input)
        if (length > maxPacketSize) throw MqttProtocolException("packet of $length bytes exceeds limit $maxPacketSize")
        val body = input.readNBytes(length)
        if (body.size < length) throw EOFException("stream ended inside packet")
        return decode(header, body)
    }

    /** Decodes a whole packet held in [bytes] (header, length and body). */
    fun decode(bytes: ByteArray): MqttPacket = read(bytes.inputStream()) ?: throw EOFException("empty")

    private class Reader(val b: ByteArray) {
        var i = 0
        val left get() = b.size - i
        fun u8(): Int { if (i >= b.size) throw MqttProtocolException("packet too short"); return b[i++].toInt() and 0xff }
        fun u16(): Int = (u8() shl 8) or u8()
        fun bin(): ByteArray {
            val n = u16()
            if (n > left) throw MqttProtocolException("string runs past the packet")
            return b.copyOfRange(i, i + n).also { i += n }
        }
        fun str(): String {
            val bytes = bin()
            val s = bytes.decodeToString()
            // §1.5.3: no U+0000, and the bytes must be well-formed UTF-8 (decodeToString replaces bad input).
            if ('\u0000' in s || !s.encodeToByteArray().contentEquals(bytes)) throw MqttProtocolException("malformed UTF-8 string")
            return s
        }
        fun rest(): ByteArray = b.copyOfRange(i, b.size).also { i = b.size }
    }

    private fun expectFlags(header: Int, flags: Int, name: String) {
        if (header and 0x0f != flags) throw MqttProtocolException("$name with reserved flags ${header and 0x0f}")
    }

    fun decode(header: Int, body: ByteArray): MqttPacket {
        val r = Reader(body)
        return when (header shr 4) {
            1 -> {
                expectFlags(header, 0, "CONNECT")
                val name = r.str()
                val level = r.u8()
                if (!((name == "MQTT" && level == 4) || (name == "MQIsdp" && level == 3))) {
                    // Still decoded so a broker can answer CONNACK 1 rather than drop the socket.
                    return MqttPacket.Connect(clientId = "", protocolLevel = level)
                }
                val flags = r.u8()
                if (flags and 0x01 != 0) throw MqttProtocolException("CONNECT reserved flag set")
                val keepAlive = r.u16()
                val clientId = r.str()
                val will = if (flags and 0x04 != 0) {
                    val t = r.str(); val payload = r.bin()
                    MqttPacket.Will(t, payload, QoS.of((flags shr 3) and 3), flags and 0x20 != 0)
                } else null
                val user = if (flags and 0x80 != 0) r.str() else null
                val pass = if (flags and 0x40 != 0) r.bin() else null
                MqttPacket.Connect(clientId, flags and 0x02 != 0, keepAlive, will, user, pass, level)
            }
            2 -> MqttPacket.ConnAck(r.u8() and 1 == 1, r.u8())
            3 -> {
                val qos = QoS.of((header shr 1) and 3)
                val topic = r.str()
                if (!MqttTopic.isValidName(topic)) throw MqttProtocolException("bad topic name '$topic'")
                val id = if (qos != QoS.AT_MOST_ONCE) r.u16() else 0
                MqttPacket.Publish(topic, r.rest(), qos, header and 1 == 1, header and 0x08 != 0, id)
            }
            4 -> MqttPacket.PubAck(r.u16())
            5 -> MqttPacket.PubRec(r.u16())
            6 -> MqttPacket.PubRel(r.u16())
            7 -> MqttPacket.PubComp(r.u16())
            8 -> {
                expectFlags(header, 2, "SUBSCRIBE")
                val id = r.u16()
                val filters = buildList { while (r.left > 0) add(r.str() to QoS.of(r.u8() and 3)) }
                if (filters.isEmpty()) throw MqttProtocolException("SUBSCRIBE with no filters")
                MqttPacket.Subscribe(id, filters)
            }
            9 -> { val id = r.u16(); MqttPacket.SubAck(id, buildList { while (r.left > 0) add(r.u8()) }) }
            10 -> {
                expectFlags(header, 2, "UNSUBSCRIBE")
                val id = r.u16()
                MqttPacket.Unsubscribe(id, buildList { while (r.left > 0) add(r.str()) })
            }
            11 -> MqttPacket.UnsubAck(r.u16())
            12 -> MqttPacket.PingReq
            13 -> MqttPacket.PingResp
            14 -> MqttPacket.Disconnect
            else -> throw MqttProtocolException("unknown packet type ${header shr 4}")
        }
    }
}

/** Topic names, topic filters and the `+` / `#` wildcards (MQTT 3.1.1 §4.7). */
object MqttTopic {
    /** A name you can publish to: non-empty, no wildcards. */
    fun isValidName(topic: String): Boolean = topic.isNotEmpty() && '+' !in topic && '#' !in topic

    /** A filter you can subscribe to: `+` fills a whole level, `#` only as the whole last level. */
    fun isValidFilter(filter: String): Boolean {
        if (filter.isEmpty()) return false
        val levels = filter.split('/')
        levels.forEachIndexed { i, level ->
            if ('#' in level && (level != "#" || i != levels.lastIndex)) return false
            if ('+' in level && level != "+") return false
        }
        return true
    }

    /**
     * Whether every topic [g] matches is also matched by [f] (both valid filters). `a/#` subsumes
     * `a`, `a/+` and `a/b/c`; `a/+` subsumes `a/b`; `#` and `+/#` subsume each other; every filter
     * subsumes itself. A filter whose first level is a wildcard never subsumes one whose first level
     * is a literal `$`-level, since leading wildcards do not match `$` topics (§4.7.2).
     */
    fun subsumes(f: String, g: String): Boolean {
        val a = f.split('/')
        val b = g.split('/')
        if ((a[0] == "+" || a[0] == "#") && b[0].startsWith("$")) return false
        var i = 0
        while (true) {
            // g ends here, so it matches topics of exactly this depth; f must match them too: f ends
            // here as well, or its only remaining level is `#`, which also matches the parent.
            if (i == b.size) return a.size == b.size || (a.size == b.size + 1 && a[i] == "#")
            if (i == a.size) return false
            val x = a[i]
            val y = b[i]
            when {
                x == "#" -> return true
                // g's `#` matches any depth from here, including the parent level (depth i). Only
                // `+/#` at the first level covers that, because a topic of depth 0 does not exist.
                y == "#" -> return i == 0 && x == "+" && a.size == 2 && a[1] == "#"
                x == "+" -> {}
                y == "+" -> return false
                x != y -> return false
            }
            i++
        }
    }

    /**
     * The smallest set of filters that matches every topic [filters] match: each filter subsumed by
     * another is dropped (of two equivalent ones, the lexicographically smaller is kept), and each
     * kept filter takes the highest QoS among the filters it covers.
     */
    fun cover(filters: Map<String, QoS>): Map<String, QoS> {
        val keys = filters.keys
        val kept = keys.filter { f -> keys.none { g -> g != f && subsumes(g, f) && (!subsumes(f, g) || g < f) } }
        return kept.associateWith { k -> QoS.of(filters.filterKeys { subsumes(k, it) }.values.maxOf { it.value }) }
    }

    /**
     * Whether [topic] matches [filter]. `sport/#` matches `sport` itself; `+` matches an empty level;
     * a filter starting with a wildcard does not match a topic starting with `$` (§4.7.2).
     */
    fun matches(filter: String, topic: String): Boolean {
        if (topic.startsWith("$") && (filter.startsWith("+") || filter.startsWith("#"))) return false
        val f = filter.split('/')
        val t = topic.split('/')
        var i = 0
        while (i < f.size) {
            val level = f[i]
            if (level == "#") return true
            if (i >= t.size) return false
            if (level != "+" && level != t[i]) return false
            i++
        }
        return i == t.size
    }
}

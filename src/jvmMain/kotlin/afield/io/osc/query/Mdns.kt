package afield.io.osc.query

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A dependency-free multicast DNS and DNS-SD implementation, sized to advertise and find OSCQuery
 * services on the local network.
 *
 * [Mdns] answers queries for the services an [OscQueryServer] registers and runs the one-shot
 * browse behind `Box.oscQueryDiscover`.
 */

/**
 * Just enough multicast DNS (RFC 6762) and DNS-SD (RFC 6763) to advertise and find OSCQuery
 * services, with no dependency: a responder that answers PTR/SRV/TXT/A questions for the services
 * registered with [advertise], and a one-shot [browse].
 *
 * The responder shares UDP 5353 with the system's own (mDNSResponder on macOS, Avahi on Linux) via
 * `SO_REUSEADDR`/`SO_REUSEPORT`. It does not probe for name conflicts: each process advertises under
 * its own host name, `field-<pid>.local`, and an instance name the caller chose. [browse] sends a
 * "legacy unicast" query from an ephemeral port, which responders answer directly to that port, so
 * browsing needs no group membership.
 */
object Mdns {
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353
    private const val TTL = 120

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33
    const val TYPE_ANY = 255

    /** A service found by [browse]. */
    data class Service(val instance: String, val type: String, val host: String, val port: Int, val addresses: List<String>) {
        /** The address to connect to: the first IPv4 one, or the host name. */
        val address: String get() = addresses.firstOrNull() ?: host
    }

    private class Registration(val instance: String, val type: String, val port: Int) {
        val serviceName get() = "$type.local"
        val instanceName get() = "$instance.$type.local"
    }

    private val registrations = CopyOnWriteArrayList<Registration>()
    private val hostName = "field-${ProcessHandle.current().pid()}.local"
    @Volatile private var socket: MulticastSocket? = null

    /**
     * Advertise [instance] as a [type] service (`_oscjson._tcp`, `_osc._udp`) on [port] until the
     * returned handle is closed, which sends a goodbye.
     */
    fun advertise(instance: String, type: String, port: Int): Closeable {
        val reg = Registration(instance, type, port)
        registrations += reg
        val s = ensureResponder()
        announce(s, reg, TTL)
        Thread({ try { Thread.sleep(1000); if (reg in registrations) announce(s, reg, TTL) } catch (_: Exception) {} }, "mdns-reannounce").apply { isDaemon = true; start() }
        return Closeable {
            if (registrations.remove(reg)) try { announce(s, reg, 0) } catch (_: Exception) {}
        }
    }

    /** Services of [type] currently answering, collected for [timeoutMs]. */
    fun browse(type: String = "_oscjson._tcp", timeoutMs: Long = 1500): List<Service> {
        val service = "$type.local"
        val found = LinkedHashMap<String, Service>()
        DatagramSocket(0).use { s ->
            val query = Message(id = 0x4f51, flags = 0).apply { questions += Question(service, TYPE_PTR) }.encode()
            for (i in 0 until 2) s.send(DatagramPacket(query, query.size, GROUP, PORT))
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(9000)
            val ptrs = HashSet<String>()
            val srv = HashMap<String, Pair<String, Int>>()
            val addrs = HashMap<String, MutableList<String>>()
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                s.soTimeout = left.toInt().coerceAtLeast(1)
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { break }
                val m = try { Message.decode(p.data.copyOf(p.length)) } catch (_: Exception) { continue }
                for (r in m.records) when (r.type) {
                    TYPE_PTR -> if (r.name.equals(service, true) && r.ttl > 0) ptrs += r.target!!
                    TYPE_SRV -> srv[r.name.lowercase()] = r.target!! to r.port
                    TYPE_A -> addrs.getOrPut(r.name.lowercase()) { mutableListOf() }.let { l -> r.address?.let { if (it !in l) l += it } }
                }
                for (ptr in ptrs) {
                    val (host, port) = srv[ptr.lowercase()] ?: continue
                    val instance = ptr.removeSuffix(".$service")
                    found[ptr.lowercase()] = Service(instance, type, host, port, addrs[host.lowercase()].orEmpty())
                }
            }
        }
        return found.values.toList()
    }

    // ---- responder ----

    @Synchronized
    private fun ensureResponder(): MulticastSocket {
        socket?.let { return it }
        val s = MulticastSocket(null)
        s.reuseAddress = true
        try { s.setOption(StandardSocketOptions.SO_REUSEPORT, true) } catch (_: Exception) {}
        s.bind(InetSocketAddress(PORT))
        var joined = 0
        for (nif in multicastInterfaces()) {
            try { s.joinGroup(InetSocketAddress(GROUP, PORT), nif); joined++ } catch (_: Exception) {}
        }
        if (joined == 0) s.joinGroup(InetSocketAddress(GROUP, PORT), null)
        socket = s
        Thread({ serve(s) }, "mdns-responder").apply { isDaemon = true; start() }
        return s
    }

    private fun multicastInterfaces(): List<NetworkInterface> =
        NetworkInterface.networkInterfaces().filter {
            try { it.isUp && it.supportsMulticast() && it.inetAddresses().anyMatch { a -> a is Inet4Address } } catch (_: Exception) { false }
        }.toList()

    private fun localAddresses(): List<String> {
        val all = multicastInterfaces().flatMap { nif -> nif.inetAddresses().toList().filterIsInstance<Inet4Address>() }
        val real = all.filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map { it.hostAddress }
        return real.ifEmpty { listOf("127.0.0.1") }
    }

    private fun serve(s: MulticastSocket) {
        val buf = ByteArray(9000)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (_: Exception) { if (s.isClosed) break else continue }
            val m = try { Message.decode(p.data.copyOf(p.length)) } catch (_: Exception) { continue }
            if (m.flags and 0x8000 != 0) continue // a response, not a query
            val answer = Message(id = if (p.port != PORT) m.id else 0, flags = 0x8400)
            for (q in m.questions) {
                for (reg in registrations) records(reg, q, TTL, answer)
            }
            if (answer.records.isEmpty()) continue
            if (p.port != PORT) answer.questions += m.questions // legacy unicast echoes the question
            val bytes = answer.encode()
            try {
                if (p.port != PORT) s.send(DatagramPacket(bytes, bytes.size, p.address, p.port))
                else s.send(DatagramPacket(bytes, bytes.size, GROUP, PORT))
            } catch (_: Exception) {}
        }
    }

    private fun records(reg: Registration, q: Question, ttl: Int, into: Message) {
        val any = q.type == TYPE_ANY
        when {
            q.name.equals("_services._dns-sd._udp.local", true) && (q.type == TYPE_PTR || any) ->
                into.records += Record.ptr("_services._dns-sd._udp.local", reg.serviceName, ttl)
            q.name.equals(reg.serviceName, true) && (q.type == TYPE_PTR || any) -> {
                into.records += Record.ptr(reg.serviceName, reg.instanceName, ttl)
                instanceRecords(reg, ttl, into)
            }
            q.name.equals(reg.instanceName, true) -> instanceRecords(reg, ttl, into)
            q.name.equals(hostName, true) && (q.type == TYPE_A || any) ->
                for (a in localAddresses()) into.records += Record.a(hostName, a, ttl)
        }
    }

    private fun instanceRecords(reg: Registration, ttl: Int, into: Message) {
        into.records += Record.srv(reg.instanceName, hostName, reg.port, ttl)
        into.records += Record.txt(reg.instanceName, listOf("txtvers=1"), ttl)
        for (a in localAddresses()) into.records += Record.a(hostName, a, ttl)
    }

    private fun announce(s: MulticastSocket, reg: Registration, ttl: Int) {
        val m = Message(id = 0, flags = 0x8400)
        m.records += Record.ptr(reg.serviceName, reg.instanceName, ttl)
        instanceRecords(reg, ttl, m)
        val bytes = m.encode()
        s.send(DatagramPacket(bytes, bytes.size, GROUP, PORT))
    }

    // ---- wire format ----

    internal data class Question(val name: String, val type: Int)

    internal class Record(val name: String, val type: Int, val ttl: Int, val data: ByteArray) {
        /** Decoded PTR/SRV target, SRV port and A address. */
        var target: String? = null
        var port: Int = 0
        var address: String? = null

        companion object {
            fun ptr(name: String, target: String, ttl: Int) = Record(name, TYPE_PTR, ttl, encodeName(target)).also { it.target = target }
            fun srv(name: String, target: String, port: Int, ttl: Int) = Record(name, TYPE_SRV, ttl,
                ByteBuffer.allocate(6).putShort(0).putShort(0).putShort(port.toShort()).array() + encodeName(target)).also { it.target = target; it.port = port }
            fun txt(name: String, entries: List<String>, ttl: Int) = Record(name, TYPE_TXT, ttl,
                entries.flatMap { e -> e.toByteArray().let { listOf(it.size.toByte()) + it.toList() } }.toByteArray())
            fun a(name: String, ip: String, ttl: Int) = Record(name, TYPE_A, ttl, InetAddress.getByName(ip).address).also { it.address = ip }
        }
    }

    internal class Message(val id: Int, val flags: Int) {
        val questions = mutableListOf<Question>()
        val records = mutableListOf<Record>()

        fun encode(): ByteArray {
            val out = ByteArrayOutputStream()
            fun u16(v: Int) { out.write(v shr 8 and 0xff); out.write(v and 0xff) }
            u16(id); u16(flags); u16(questions.size); u16(records.size); u16(0); u16(0)
            for (q in questions) { out.write(encodeName(q.name)); u16(q.type); u16(1) }
            for (r in records) {
                out.write(encodeName(r.name)); u16(r.type)
                // cache-flush bit on unique records (all but PTR), class IN
                u16(if (r.type == TYPE_PTR) 1 else 0x8001)
                u16(r.ttl ushr 16); u16(r.ttl and 0xffff)
                u16(r.data.size); out.write(r.data)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): Message {
                val b = ByteBuffer.wrap(bytes)
                fun u16() = b.short.toInt() and 0xffff
                val m = Message(u16(), u16())
                val qd = u16(); val an = u16(); val ns = u16(); val ar = u16()
                repeat(qd) { val n = readName(bytes, b); val t = u16(); u16(); m.questions += Question(n, t) }
                repeat(an + ns + ar) {
                    val n = readName(bytes, b); val t = u16(); u16()
                    val ttl = b.int; val len = u16()
                    val start = b.position()
                    val data = bytes.copyOfRange(start, start + len)
                    val r = Record(n, t, ttl, data)
                    when (t) {
                        TYPE_PTR -> r.target = readName(bytes, ByteBuffer.wrap(bytes).position(start) as ByteBuffer)
                        TYPE_SRV -> { r.port = ((data[4].toInt() and 0xff) shl 8) or (data[5].toInt() and 0xff)
                            r.target = readName(bytes, ByteBuffer.wrap(bytes).position(start + 6) as ByteBuffer) }
                        TYPE_A -> if (len == 4) r.address = InetAddress.getByAddress(data).hostAddress
                    }
                    m.records += r
                    b.position(start + len)
                }
                return m
            }
        }
    }

    internal fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        // The instance label may itself contain dots ("Field 1.2"); split service labels off the end.
        val labels = splitName(name)
        for (l in labels) { val bytes = l.toByteArray(); out.write(bytes.size.coerceAtMost(63)); out.write(bytes, 0, bytes.size.coerceAtMost(63)) }
        out.write(0)
        return out.toByteArray()
    }

    private fun splitName(name: String): List<String> {
        val parts = name.split('.')
        val idx = parts.indexOfFirst { it.startsWith("_") }
        // "<instance>._type._proto.local": everything before the first "_" label is one instance label
        return if (idx > 1) listOf(parts.subList(0, idx).joinToString(".")) + parts.subList(idx, parts.size) else parts
    }

    internal fun readName(bytes: ByteArray, b: ByteBuffer): String {
        val labels = mutableListOf<String>()
        var pos = b.position()
        var jumped = false
        var guard = 0
        while (guard++ < 128) {
            val len = bytes[pos].toInt() and 0xff
            if (len == 0) { pos++; break }
            if (len and 0xc0 == 0xc0) {
                val ptr = ((len and 0x3f) shl 8) or (bytes[pos + 1].toInt() and 0xff)
                if (!jumped) b.position(pos + 2)
                jumped = true
                pos = ptr
                continue
            }
            labels += String(bytes, pos + 1, len, Charsets.UTF_8)
            pos += 1 + len
        }
        if (!jumped) b.position(pos)
        return labels.joinToString(".")
    }
}

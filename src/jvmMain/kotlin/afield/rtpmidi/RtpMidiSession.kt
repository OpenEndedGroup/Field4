@file:afield.CompletionCategory("Networking")

package afield.rtpmidi

import afield.rtpmidi.RtpMidiCodec.Command
import afield.rtpmidi.RtpMidiCodec.Session
import java.io.Closeable
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.sound.midi.MidiMessage
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage
import kotlin.random.Random

/**
 * An AppleMIDI network session: the same thing as a session in macOS's *Audio MIDI Setup → MIDI
 * Network Setup*, rtpMIDI on Windows, or an iPad app's "network MIDI".
 *
 * It listens on a control port and the data port above it ([port], [port] + 1). It answers
 * invitations from other peers ([acceptInvitations]) and can invite them itself ([invite]).
 * Once a peer is connected, MIDI sent with [send] goes to every connected peer, and MIDI they send
 * arrives at [onMessage] listeners on the session's receive thread.
 *
 * The initiator of each connection runs the CK clock exchange on connecting and every
 * [syncIntervalMs] after; both ends keep the result as [Peer.offset] and [Peer.roundTrip].
 * Outgoing packets carry no recovery journal, which RFC 6295 allows; incoming journals are skipped.
 */
class RtpMidiSession(
    val name: String = "Field",
    port: Int = DEFAULT_PORT,
    bindAddress: String? = null,
    @Volatile var acceptInvitations: Boolean = true,
    val syncIntervalMs: Long = 10_000,
) : Closeable {
    companion object {
        const val DEFAULT_PORT = 5004
        private val origin = System.nanoTime()

        private fun bindPair(port: Int, bindAddress: String?): Pair<DatagramSocket, DatagramSocket> {
            val addr = bindAddress?.let { InetAddress.getByName(it) }
            fun open(p: Int) = DatagramSocket(null).apply { reuseAddress = false; bind(InetSocketAddress(addr, p)) }
            if (port != 0) {
                val c = open(port)
                val d = try { open(port + 1) } catch (e: BindException) { c.close(); throw BindException("RTP-MIDI data port ${port + 1} is in use") }
                return c to d
            }
            repeat(50) {
                val c = open(0)
                if (c.localPort < 65535) try { return c to open(c.localPort + 1) } catch (_: SocketException) {}
                c.close()
            }
            throw BindException("no free pair of consecutive UDP ports")
        }
    }

    enum class State { INVITING_CONTROL, INVITING_DATA, CONNECTED }

    /** A remote participant. Times are in 100 µs units, the AppleMIDI clock. */
    class Peer internal constructor(val address: InetAddress, val controlPort: Int, val initiator: Boolean, val token: Int) {
        @Volatile
        var name: String = ""; internal set
        @Volatile
        var ssrc: Int = 0; internal set
        @Volatile
        var state: State = State.INVITING_CONTROL; internal set
        val dataPort get() = controlPort + 1
        /** The peer's clock minus ours, from the last CK exchange; null until one has run. */
        @Volatile
        var offset: Long? = null; internal set
        /** Round-trip time of the last CK exchange. */
        @Volatile
        var roundTrip: Long? = null; internal set
        internal val rtts = ArrayDeque<Long>()
        @Volatile
        var received = 0L; internal set
        @Volatile
        var sent = 0L; internal set
        @Volatile
        var lastSequence: Int = -1; internal set
        @Volatile internal var feedbackDue = false
        @Volatile
        var acknowledged: Int = -1; internal set
        @Volatile
        var syncs = 0; internal set
        internal var attempts = 0
        internal var sysexPending: ByteArray? = null

        /** Round trip in milliseconds, and its min and mean over the last 32 exchanges. */
        val roundTripMs: Double? get() = roundTrip?.let { it / 10.0 }
        val roundTripStatsMs: Triple<Double, Double, Double>? get() = synchronized(rtts) {
            if (rtts.isEmpty()) null else Triple(rtts.min() / 10.0, rtts.average() / 10.0, rtts.max() / 10.0)
        }
        val connected get() = state == State.CONNECTED
        override fun toString() = "$name @ ${address.hostAddress}:$controlPort ${state.name.lowercase()}" +
            (roundTripMs?.let { " rtt %.1f ms".format(it) } ?: "")
    }

    private val sockets = bindPair(port, bindAddress)
    private val control get() = sockets.first
    private val data get() = sockets.second
    val port: Int get() = control.localPort
    val ssrc: Int = Random.nextInt()
    private val peersBySsrc = ConcurrentHashMap<Int, Peer>()
    private val pending = CopyOnWriteArrayList<Peer>()
    private val listeners = CopyOnWriteArrayList<(MidiMessage, Long, Peer) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(Peer) -> Unit>()
    private var sequence = Random.nextInt(0, 0xFFFF)
    @Volatile
    var closed = false; private set
    @Volatile
    var lastError: String? = null; private set
    /** Packets that did not parse; they are dropped and counted. */
    @Volatile
    var malformed = 0L; private set

    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rtpmidi-timer-$port").apply { isDaemon = true }
    }

    init {
        for (s in listOf(control, data)) Thread({ receiveLoop(s) }, "rtpmidi-${if (s === control) "control" else "data"}-$port").apply { isDaemon = true; start() }
        timer.scheduleAtFixedRate({ tick() }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** Our clock, in 100 µs units. */
    fun now(): Long = (System.nanoTime() - origin) / 100_000

    val peers: List<Peer> get() = peersBySsrc.values.toList() + pending
    val connectedPeers: List<Peer> get() = peersBySsrc.values.filter { it.connected }

    /** Called with each MIDI message received, its sender timestamp in microseconds, and the peer, on the receive thread. */
    fun onMessage(block: (MidiMessage, Long, Peer) -> Unit): Closeable { listeners += block; return Closeable { listeners -= block } }

    /** Called when a peer connects, finishes a clock sync, or leaves. */
    fun onPeerChange(block: (Peer) -> Unit): Closeable { stateListeners += block; return Closeable { stateListeners -= block } }

    /**
     * Invites the session at [host]:[port] (its control port). The invitation is repeated every
     * second, up to 12 times, until it is accepted or refused.
     */
    fun invite(host: String, port: Int = DEFAULT_PORT): Peer {
        val addr = InetAddress.getByName(host)
        (peers.firstOrNull { it.address == addr && it.controlPort == port })?.let { return it }
        val p = Peer(addr, port, initiator = true, token = Random.nextInt())
        pending += p
        sendInvitation(p)
        return p
    }

    private fun sendInvitation(p: Peer) {
        p.attempts++
        val pkt = RtpMidiCodec.encode(Session.Invitation("IN", p.token, ssrc, name))
        if (p.state == State.INVITING_CONTROL) sendTo(control, pkt, p.address, p.controlPort)
        else sendTo(data, pkt, p.address, p.dataPort)
    }

    /** Ends the connection to [peer] with BY. */
    fun disconnect(peer: Peer) {
        val bye = RtpMidiCodec.encode(Session.Bye(peer.token, ssrc))
        sendTo(control, bye, peer.address, peer.controlPort)
        drop(peer)
    }

    // ---- sending MIDI ----

    /** Sends raw MIDI [messages], each status-first, in one packet to every connected peer. */
    fun sendBytes(vararg messages: ByteArray) {
        if (messages.isEmpty()) return
        val targets = connectedPeers
        if (targets.isEmpty()) return
        val pkt = synchronized(this) {
            sequence = (sequence + 1) and 0xFFFF
            RtpMidiCodec.encodeRtp(sequence, now(), ssrc, messages.map { Command(0, it) })
        }
        for (p in targets) { sendTo(data, pkt, p.address, p.dataPort); p.sent += messages.size }
    }

    fun send(message: MidiMessage) = sendBytes(message.message.copyOf(message.length))

    fun noteOn(pitch: Int, velocity: Int = 100, channel: Int = 0) =
        sendBytes(byteArrayOf((0x90 or (channel and 15)).toByte(), (pitch and 127).toByte(), (velocity and 127).toByte()))

    fun noteOff(pitch: Int, velocity: Int = 0, channel: Int = 0) =
        sendBytes(byteArrayOf((0x80 or (channel and 15)).toByte(), (pitch and 127).toByte(), (velocity and 127).toByte()))

    fun controlChange(controller: Int, value: Int, channel: Int = 0) =
        sendBytes(byteArrayOf((0xB0 or (channel and 15)).toByte(), (controller and 127).toByte(), (value and 127).toByte()))

    fun programChange(program: Int, channel: Int = 0) =
        sendBytes(byteArrayOf((0xC0 or (channel and 15)).toByte(), (program and 127).toByte()))

    /** Pitch bend, -8192..8191 with 0 as centre. */
    fun pitchBend(value: Int, channel: Int = 0) {
        val v = (value + 8192).coerceIn(0, 16383)
        sendBytes(byteArrayOf((0xE0 or (channel and 15)).toByte(), (v and 127).toByte(), (v shr 7).toByte()))
    }

    /** A SysEx message; [bytes] may include the F0/F7 framing or not. */
    fun sysex(bytes: ByteArray) {
        val framed = if (bytes.isNotEmpty() && bytes[0] == 0xF0.toByte()) bytes else byteArrayOf(0xF0.toByte()) + bytes + byteArrayOf(0xF7.toByte())
        sendBytes(framed)
    }

    /** Note off on every note of every channel (CC 123), for a stuck note. */
    fun allNotesOff() = sendBytes(*Array(16) { byteArrayOf((0xB0 or it).toByte(), 123, 0) })

    // ---- receiving ----

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(65536)
        while (!closed) {
            val pkt = DatagramPacket(buf, buf.size)
            try { s.receive(pkt) } catch (e: Exception) { if (closed) return; lastError = e.toString(); continue }
            try { handle(s === data, buf, pkt.length, pkt.address, pkt.port) } catch (e: Exception) { malformed++; lastError = "bad packet from ${pkt.address.hostAddress}: $e" }
        }
    }

    private fun handle(isData: Boolean, buf: ByteArray, len: Int, from: InetAddress, fromPort: Int) {
        if (RtpMidiCodec.isSession(buf, len)) {
            val s = RtpMidiCodec.decodeSession(buf, len) ?: return
            handleSession(isData, s, from, fromPort)
        } else if (isData && RtpMidiCodec.isRtpMidi(buf, len)) {
            val ssrc = java.nio.ByteBuffer.wrap(buf, 8, 4).int
            val peer = peersBySsrc[ssrc] ?: return
            if (!peer.connected) return
            val rtp = RtpMidiCodec.decodeRtp(buf, len, peer.sysexPending)
            peer.sysexPending = rtp.sysexPending
            peer.lastSequence = rtp.sequence
            peer.feedbackDue = true
            var t = rtp.timestamp
            for (c in rtp.commands) {
                t += c.delta
                val m = toMidiMessage(c.bytes) ?: continue
                peer.received++
                val micros = t * 100
                listeners.forEach { runCatching { it(m, micros, peer) } }
            }
        }
    }

    private fun handleSession(isData: Boolean, s: Session, from: InetAddress, fromPort: Int) {
        when (s) {
            is Session.Invitation -> when (s.command) {
                "IN" -> {
                    if (!acceptInvitations) {
                        sendTo(if (isData) data else control, RtpMidiCodec.encode(Session.Invitation("NO", s.token, ssrc, name)), from, fromPort)
                        return
                    }
                    val existing = peersBySsrc[s.ssrc]
                    val controlPort = if (isData) fromPort - 1 else fromPort
                    val p = existing ?: Peer(from, controlPort, initiator = false, token = s.token).also { peersBySsrc[s.ssrc] = it }
                    p.ssrc = s.ssrc; p.name = s.name ?: p.name
                    sendTo(if (isData) data else control, RtpMidiCodec.encode(Session.Invitation("OK", s.token, ssrc, name)), from, fromPort)
                    if (isData && !p.connected) { p.state = State.CONNECTED; changed(p) }
                    else if (!isData) p.state = State.INVITING_DATA
                }
                "OK" -> {
                    val p = pending.firstOrNull { it.token == s.token } ?: peersBySsrc.values.firstOrNull { it.token == s.token } ?: return
                    p.ssrc = s.ssrc; p.name = s.name ?: p.name
                    if (!isData && p.state == State.INVITING_CONTROL) {
                        p.state = State.INVITING_DATA; p.attempts = 0
                        sendInvitation(p)
                    } else if (isData && p.state == State.INVITING_DATA) {
                        p.state = State.CONNECTED
                        pending -= p
                        peersBySsrc[s.ssrc] = p
                        changed(p)
                        startSync(p)
                    }
                }
                "NO" -> pending.firstOrNull { it.token == s.token }?.let { lastError = "${it.address.hostAddress}:${it.controlPort} refused the invitation"; pending -= it; changed(it) }
            }
            is Session.Bye -> peersBySsrc[s.ssrc]?.let { drop(it) }
            is Session.Sync -> {
                val p = peersBySsrc[s.ssrc] ?: return
                when (s.count) {
                    0 -> sendTo(data, RtpMidiCodec.encode(Session.Sync(ssrc, 1, s.ts1, now(), 0)), p.address, p.dataPort)
                    1 -> {
                        val ts3 = now()
                        sendTo(data, RtpMidiCodec.encode(Session.Sync(ssrc, 2, s.ts1, s.ts2, ts3)), p.address, p.dataPort)
                        synced(p, offset = s.ts2 - (s.ts1 + ts3) / 2, rtt = ts3 - s.ts1)
                    }
                    2 -> synced(p, offset = (s.ts1 + s.ts3) / 2 - s.ts2, rtt = s.ts3 - s.ts1)
                }
            }
            is Session.Feedback -> peersBySsrc[s.ssrc]?.acknowledged = s.sequence
        }
    }

    private fun synced(p: Peer, offset: Long, rtt: Long) {
        p.offset = offset; p.roundTrip = rtt; p.syncs++
        synchronized(p.rtts) { p.rtts.addLast(rtt); while (p.rtts.size > 32) p.rtts.removeFirst() }
        changed(p)
    }

    private fun startSync(p: Peer) {
        if (p.connected) sendTo(data, RtpMidiCodec.encode(Session.Sync(ssrc, 0, now(), 0, 0)), p.address, p.dataPort)
    }

    private var ticks = 0L
    private fun tick() {
        if (closed) return
        ticks++
        try {
            for (p in pending) {
                if (p.attempts >= 12) { lastError = "no answer from ${p.address.hostAddress}:${p.controlPort}"; pending -= p; changed(p) }
                else if (ticks % 2 == 0L) sendInvitation(p)
            }
            for (p in peersBySsrc.values) {
                if (p.feedbackDue && p.lastSequence >= 0) {
                    p.feedbackDue = false
                    sendTo(control, RtpMidiCodec.encode(Session.Feedback(ssrc, p.lastSequence)), p.address, p.controlPort)
                }
                // The initiator syncs three times in the first few seconds, then every syncIntervalMs.
                if (p.initiator && p.connected && (p.syncs < 3 && ticks % 3 == 0L || ticks % (syncIntervalMs / 500).coerceAtLeast(1) == 0L)) startSync(p)
            }
        } catch (e: Exception) { lastError = e.toString() }
    }

    private fun drop(p: Peer) {
        pending -= p
        peersBySsrc.entries.removeIf { it.value === p }
        p.state = State.INVITING_CONTROL
        changed(p)
    }

    private fun changed(p: Peer) = stateListeners.forEach { runCatching { it(p) } }

    private fun sendTo(s: DatagramSocket, bytes: ByteArray, addr: InetAddress, port: Int) {
        try { s.send(DatagramPacket(bytes, bytes.size, addr, port)) } catch (e: Exception) { lastError = e.toString() }
    }

    override fun close() {
        if (closed) return
        for (p in peersBySsrc.values) runCatching { sendTo(control, RtpMidiCodec.encode(Session.Bye(p.token, ssrc)), p.address, p.controlPort) }
        closed = true
        timer.shutdownNow()
        control.close(); data.close()
        peersBySsrc.clear(); pending.clear()
    }

    override fun toString() = "RTP-MIDI session '$name' on $port, ${connectedPeers.size} connected" +
        connectedPeers.joinToString("") { "\n  $it" }
}

/** Raw status-first bytes as a `javax.sound.midi` message, or null for bytes that are not one. */
fun toMidiMessage(bytes: ByteArray): MidiMessage? = try {
    val status = bytes[0].toInt() and 0xFF
    when {
        status == 0xF0 -> SysexMessage(bytes, bytes.size)
        bytes.size == 1 -> ShortMessage(status)
        bytes.size == 2 -> ShortMessage(status, bytes[1].toInt() and 0x7F, 0)
        else -> ShortMessage(status, bytes[1].toInt() and 0x7F, bytes[2].toInt() and 0x7F)
    }
} catch (_: Exception) { null }

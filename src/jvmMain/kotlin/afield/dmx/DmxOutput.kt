@file:afield.CompletionCategory("Lighting")

package afield.dmx

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Which wire protocol a [DmxOutput] speaks or a [DmxInput] listens for. */
enum class DmxProtocol { ARTNET, SACN }

/**
 * One universe of DMX data: 512 slots, written by Field code, read by a [DmxOutput]'s sender thread.
 *
 * Channels are **1-based** at this API, as on every console and fixture manual (`u[1]` is the first
 * slot); [data] is the raw 0-based array for bulk copies. Values are clamped into 0..255.
 */
class DmxUniverse(val number: Int) {
    /** The raw slots, 0-based. Writing here directly is fine; the sender copies it each tick. */
    val data = ByteArray(512)

    /** How many slots are transmitted; 512 unless a rig wants shorter packets. */
    var length = 512
        set(value) { require(value in 1..512); field = value }

    /** Value of 1-based [channel], 0..255. */
    operator fun get(channel: Int): Int = data[channel - 1].toInt() and 0xff

    /** Set 1-based [channel] to [value], clamped to 0..255. */
    operator fun set(channel: Int, value: Int) {
        data[channel - 1] = value.coerceIn(0, 255).toByte()
    }

    /** Set 1-based [channel] from a 0..1 level. */
    fun setLevel(channel: Int, level: Double) = set(channel, Math.round(level.coerceIn(0.0, 1.0) * 255).toInt())

    /** Set a 16-bit value (0..65535) into [channel] (coarse) and `channel + 1` (fine). */
    fun set16(channel: Int, value: Int) {
        val v = value.coerceIn(0, 65535)
        data[channel - 1] = (v shr 8).toByte()
        data[channel] = v.toByte()
    }

    /** Zero every slot. */
    fun clear() = java.util.Arrays.fill(data, 0)

    override fun toString() = "DmxUniverse($number, first slots ${(1..minOf(12, length)).map { get(it) }})"
}

/**
 * A DMX transmitter: a set of [DmxUniverse] frame buffers and a thread that sends them as Art-Net or
 * sACN.
 *
 * You write into universes whenever you like — `out.universe(1)[1] = 255`, or through a [Patch] —
 * and the sender thread, ticking at [maxRateHz], decides what goes on the wire:
 *
 * - **Send on change.** A universe whose bytes differ from what was last sent is sent on the next
 *   tick. Ticks are at most 44 Hz by default, the DMX512 full-frame rate that both protocols treat
 *   as the ceiling for a universe.
 * - **Keep-alive.** An unchanged universe is re-sent every [keepAliveMs] (default 1000 ms). E1.31-2016
 *   §6.6.2 asks a source that suppresses unchanged data to send three packets of the unchanged
 *   values and then one keep-alive every 800–1000 ms, and receivers drop a source after
 *   E131_NETWORK_DATA_LOSS_TIMEOUT (2.5 s); Art-Net 4 likewise expects an unchanged universe to be
 *   refreshed at least every 4 s. One second satisfies both, and this class sends the three
 *   post-change repeats for both protocols because Art-Net nodes do not mind them.
 * - **Sync (optional).** With [sync] on, every tick that sent anything ends with one ArtSync, or one
 *   sACN synchronization packet on [syncUniverse] (data packets then carry that sync address), so a
 *   multi-universe strip updates in one step instead of tearing across universes.
 * - **Stream terminated.** Closing an sACN output sends three packets per universe with the
 *   Stream_Terminated option set (E1.31 §6.2.6), so receivers release the universe immediately
 *   instead of holding the last look for 2.5 s.
 *
 * Destinations: Art-Net goes to [host] (a node's IP, or a broadcast address). sACN goes to [host] if
 * one is given (unicast), otherwise to each universe's multicast group `239.255.hi.lo`.
 *
 * In a box, use [artnetOut] / [sacnOut] rather than the constructor: they reuse the output across
 * re-runs and close it when the box is stopped.
 */
class DmxOutput(
    val protocol: DmxProtocol,
    val host: String? = null,
    val port: Int = if (protocol == DmxProtocol.ARTNET) ArtNet.PORT else Sacn.PORT,
    val sourceName: String = "Field4",
    val priority: Int = Sacn.DEFAULT_PRIORITY,
    val cid: UUID = UUID.randomUUID(),
    val maxRateHz: Double = 44.0,
    val keepAliveMs: Long = 1000,
    val sync: Boolean = false,
    val syncUniverse: Int = 7962,
    val discovery: Boolean = protocol == DmxProtocol.SACN,
    autoStart: Boolean = true,
) : AutoCloseable {

    private class Stream(val universe: DmxUniverse) {
        val snapshot = ByteArray(512)
        val lastSent = ByteArray(512)
        val packet = ByteArray(maxOf(ArtNet.DMX_MAX_PACKET, Sacn.DATA_MAX_PACKET))
        var sequence = 0
        var lastSendNanos = 0L
        var repeatsLeft = 0
        var everSent = false
    }

    private val streams = ConcurrentSkipListMap<Int, Stream>()
    private val socket = DatagramSocket().apply { broadcast = true }
    private val cidBytes = Sacn.cidBytes(cid)
    private val syncPacket = ByteArray(Sacn.SYNC_LENGTH.coerceAtLeast(ArtNet.SYNC_LENGTH))
    private val discoveryPacket = ByteArray(120 + 2 * 512)
    private var syncSequence = 0
    private var lastDiscoveryNanos = 0L
    private val lock = Any()
    private var executor: ScheduledExecutorService? = null

    @Volatile
    var isClosed = false
        private set

    private val sent = AtomicLong()

    /** Data packets sent so far (sync and discovery packets not counted). */
    val packetsSent: Long get() = sent.get()

    /** The last send failure, or null. Sends never throw into Field code; look here instead. */
    @Volatile
    var lastError: Throwable? = null
        private set

    private val hostAddress: InetAddress? = host?.let { InetAddress.getByName(it) }

    init {
        require(maxRateHz > 0 && maxRateHz <= 1000) { "maxRateHz must be in (0, 1000], got $maxRateHz" }
        if (protocol == DmxProtocol.ARTNET) require(hostAddress != null) { "Art-Net needs a destination host (a node IP or broadcast address)" }
        if (autoStart) start()
    }

    /** The frame buffer for [number], created (all zeros) on first use. */
    fun universe(number: Int): DmxUniverse {
        if (protocol == DmxProtocol.SACN) require(number in 1..63999) { "sACN universes are 1..63999, got $number" }
        else PortAddress(number) // validates 0..32767
        return streams.computeIfAbsent(number) { Stream(DmxUniverse(it)) }.universe
    }

    /** The universes this output has been asked for, ascending. */
    val universes: List<Int> get() = streams.keys.toList()

    /**
     * Run [block] holding the frame lock, so the sender never transmits a half-written frame. Every
     * [Patch.render] into an output goes through here.
     */
    fun <T> frame(block: DmxOutput.() -> T): T = synchronized(lock) { block() }

    /** Start the sender thread (the constructor does this unless `autoStart = false`). */
    fun start() {
        if (executor != null || isClosed) return
        val ex = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "afield-dmx-${protocol.name.lowercase()}-out").apply { isDaemon = true }
        }
        val periodMicros = (1_000_000.0 / maxRateHz).toLong()
        ex.scheduleAtFixedRate({ runCatching { tick(System.nanoTime(), force = false) }.onFailure { lastError = it } },
            0, periodMicros, TimeUnit.MICROSECONDS)
        executor = ex
    }

    /** Send every universe now, regardless of change or rate; for tests and one-shot scripts. */
    fun flush() = tick(System.nanoTime(), force = true)

    /**
     * One sender pass at time [nowNanos]; returns how many data packets it sent. Public so tests can
     * drive the send-on-change / keep-alive policy with a synthetic clock and no thread.
     */
    @Synchronized
    fun tick(nowNanos: Long, force: Boolean = false): Int {
        if (isClosed) return 0
        var count = 0
        val keepAliveNanos = keepAliveMs * 1_000_000
        synchronized(lock) {
            for (s in streams.values) {
                System.arraycopy(s.universe.data, 0, s.snapshot, 0, 512)
            }
        }
        for ((number, s) in streams) {
            val changed = !s.everSent || !java.util.Arrays.equals(s.snapshot, s.lastSent)
            val due = when {
                force || changed -> { s.repeatsLeft = 2; true }
                s.repeatsLeft > 0 -> { s.repeatsLeft--; true }
                else -> nowNanos - s.lastSendNanos >= keepAliveNanos
            }
            if (!due) continue
            if (send(number, s, options = 0)) {
                System.arraycopy(s.snapshot, 0, s.lastSent, 0, 512)
                s.lastSendNanos = nowNanos
                s.everSent = true
                count++
            }
        }
        if (count > 0 && sync) sendSync()
        if (discovery && protocol == DmxProtocol.SACN && nowNanos - lastDiscoveryNanos >= Sacn.DISCOVERY_INTERVAL_MS * 1_000_000) {
            lastDiscoveryNanos = nowNanos
            sendDiscovery()
        }
        return count
    }

    private fun send(number: Int, s: Stream, options: Int): Boolean {
        val len = s.universe.length
        s.sequence = if (protocol == DmxProtocol.ARTNET) (s.sequence % 255) + 1 else (s.sequence + 1) and 0xff
        val (n, dest) = when (protocol) {
            DmxProtocol.ARTNET ->
                ArtNet.encodeDmx(s.packet, PortAddress(number), s.snapshot, len, sequence = s.sequence) to hostAddress!!
            DmxProtocol.SACN ->
                Sacn.encodeData(
                    s.packet, cidBytes, sourceName, number, s.snapshot, len,
                    sequence = s.sequence, priority = priority, options = options,
                    syncAddress = if (sync) syncUniverse else 0,
                ) to (hostAddress ?: Sacn.multicastAddress(number))
        }
        return transmit(s.packet, n, dest).also { if (it) sent.incrementAndGet() }
    }

    private fun sendSync() {
        when (protocol) {
            DmxProtocol.ARTNET -> transmit(syncPacket, ArtNet.encodeSync(syncPacket), hostAddress!!)
            DmxProtocol.SACN -> {
                syncSequence = (syncSequence + 1) and 0xff
                val n = Sacn.encodeSync(syncPacket, cidBytes, syncUniverse, syncSequence)
                transmit(syncPacket, n, hostAddress ?: Sacn.multicastAddress(syncUniverse))
            }
        }
    }

    private fun sendDiscovery() {
        val list = streams.keys.toIntArray().take(512).toIntArray()
        val n = Sacn.encodeDiscovery(discoveryPacket, cidBytes, sourceName, list)
        transmit(discoveryPacket, n, hostAddress ?: Sacn.multicastAddress(Sacn.DISCOVERY_UNIVERSE))
    }

    private fun transmit(buf: ByteArray, n: Int, dest: InetAddress): Boolean = try {
        socket.send(DatagramPacket(buf, n, InetSocketAddress(dest, port)))
        true
    } catch (t: Throwable) {
        lastError = t
        false
    }

    /**
     * Stop the sender and release the socket. An sACN output first sends three stream-terminated
     * packets per universe. Idempotent.
     */
    override fun close() {
        if (isClosed) return
        executor?.shutdownNow()
        runCatching { executor?.awaitTermination(250, TimeUnit.MILLISECONDS) }
        executor = null
        if (protocol == DmxProtocol.SACN) synchronized(this) {
            synchronized(lock) { for (s in streams.values) System.arraycopy(s.universe.data, 0, s.snapshot, 0, 512) }
            repeat(3) { for ((number, s) in streams) send(number, s, Sacn.OPTION_STREAM_TERMINATED) }
        }
        isClosed = true
        runCatching { socket.close() }
    }

    override fun toString() =
        "DmxOutput(${protocol.name}, -> ${host ?: "multicast"}:$port, universes=${universes}, sent=$packetsSent${if (isClosed) ", closed" else ""})"
}

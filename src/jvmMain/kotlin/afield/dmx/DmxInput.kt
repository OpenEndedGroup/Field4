@file:afield.CompletionCategory("Lighting")

package afield.dmx

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** How a [DmxInput] combines several sources sending the same universe. */
enum class MergeMode {
    /** Highest takes precedence: each slot is the maximum over live sources. */
    HTP,

    /** Latest takes precedence: the universe is whatever the most recent packet said. */
    LTP,
}

/**
 * A DMX receiver: Field as an Art-Net or sACN **node**.
 *
 * A daemon thread receives packets on [port], keeps the latest frame per source per universe, and
 * [universe] returns the merged result. Merging follows the protocols:
 *
 * - **sACN** — only sources at the highest live priority count (E1.31 §6.2.3); among those, [merge]
 *   decides. A packet failing the §6.7.2 sequence rule is discarded (see [SacnSequence]). A source is
 *   dropped when it sends Stream_Terminated, or after E131_NETWORK_DATA_LOSS_TIMEOUT (2.5 s) of
 *   silence. Data carrying a synchronization address is held until the matching sync packet arrives
 *   (E1.31 §6.3.3.1). Preview data is ignored unless [acceptPreview].
 * - **Art-Net** — sources are told apart by IP and port; [merge] decides; a source silent for 10 s
 *   is dropped, which is the Art-Net 4 merge timeout. Once an ArtSync has arrived, ArtDmx frames are
 *   held and applied together on the next ArtSync, until 4 s pass without one (Art-Net 4's
 *   synchronous mode). An ArtPoll is answered with an ArtPollReply describing [node], so consoles
 *   and discovery tools list Field as a node.
 *
 * Multicast sACN needs the socket to join each universe's group: pass the universes to watch in
 * [multicastUniverses]. Unicast sACN (a sender pointed at this machine's IP or 127.0.0.1) needs
 * nothing.
 *
 * In a box, use [artnetIn] / [sacnIn]: they close the socket when the box is stopped.
 */
class DmxInput(
    val protocol: DmxProtocol,
    val port: Int = if (protocol == DmxProtocol.ARTNET) ArtNet.PORT else Sacn.PORT,
    val merge: MergeMode = MergeMode.HTP,
    val multicastUniverses: List<Int> = emptyList(),
    val node: ArtNet.NodeInfo = ArtNet.NodeInfo(),
    val answerPolls: Boolean = true,
    val acceptPreview: Boolean = false,
    bindAddress: String = "0.0.0.0",
) : AutoCloseable {

    /** What is known about one source on one universe. */
    class Source(val key: String, val name: String, val universe: Int) {
        internal val data = ByteArray(512)
        internal val pending = ByteArray(512)
        internal var pendingSync = 0
        internal var pendingSinceNanos = 0L
        internal val sequence = SacnSequence()

        /** Slots in the most recent packet. */
        @Volatile
        var length = 0
            internal set

        /** sACN priority (Art-Net sources report 100). */
        @Volatile
        var priority = Sacn.DEFAULT_PRIORITY
            internal set

        /** `System.nanoTime()` of the last accepted packet. */
        @Volatile
        var lastSeenNanos = 0L
            internal set

        /** Packets accepted from this source. */
        @Volatile
        var packets = 0L
            internal set

        override fun toString() = "Source($name @ $key, universe $universe, prio $priority, $packets packets)"
    }

    private val sources = ConcurrentHashMap<Int, ConcurrentHashMap<String, Source>>()

    @Volatile
    private var lastArtSyncNanos = Long.MIN_VALUE / 2
    private val versions = ConcurrentHashMap<Int, AtomicLong>()
    private val listeners = CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()
    private val received = AtomicLong()
    private val discarded = AtomicLong()

    /** ArtPollReplies seen by this input (nodes that answered someone's ArtPoll), keyed by IP. */
    val pollReplies = ConcurrentHashMap<String, ArtNet.PollReply>()

    /** sACN universe-discovery pages seen, keyed by source CID. */
    val discoveredSources = ConcurrentHashMap<UUID, Sacn.Discovery>()

    private val socket: DatagramSocket
    private val thread: Thread

    @Volatile
    var isClosed = false
        private set

    /** The last receive failure, or null. */
    @Volatile
    var lastError: Throwable? = null
        private set

    /** Packets received (any kind), and packets discarded (sequence rule, malformed, preview). */
    val packetsReceived: Long get() = received.get()
    val packetsDiscarded: Long get() = discarded.get()

    /** ArtPollReplies this input has sent in answer to ArtPolls. */
    @Volatile
    var pollsAnswered = 0
        private set

    init {
        val addr = InetSocketAddress(InetAddress.getByName(bindAddress), port)
        socket = if (protocol == DmxProtocol.SACN && multicastUniverses.isNotEmpty()) {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(addr)
                for (u in multicastUniverses) {
                    runCatching { joinGroup(InetSocketAddress(Sacn.multicastAddress(u), 0), null) }
                        .onFailure { lastError = it }
                }
            }
        } else {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(addr)
            }
        }
        socket.soTimeout = 250
        thread = Thread({ receiveLoop() }, "afield-dmx-${protocol.name.lowercase()}-in:$port").apply {
            isDaemon = true
            start()
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(1500)
        val packet = DatagramPacket(buf, buf.size)
        while (!isClosed) {
            try {
                packet.setData(buf, 0, buf.size)
                socket.receive(packet)
                received.incrementAndGet()
                handle(buf, packet.length, packet.address, packet.port)
            } catch (_: SocketTimeoutException) {
            } catch (t: Throwable) {
                if (!isClosed) lastError = t
            }
        }
    }

    /**
     * Process one datagram as if it had arrived from [from]:[fromPort]. The receive thread calls this;
     * it is public so tests can feed packets without a socket.
     */
    fun handle(buf: ByteArray, length: Int, from: InetAddress, fromPort: Int) {
        when (protocol) {
            DmxProtocol.ARTNET -> handleArtNet(buf, length, from, fromPort)
            DmxProtocol.SACN -> handleSacn(buf, length)
        }
    }

    private fun handleArtNet(buf: ByteArray, length: Int, from: InetAddress, fromPort: Int) {
        when (ArtNet.opCode(buf, length)) {
            ArtNet.OP_DMX -> {
                val n = ((buf[16].toInt() and 0xff) shl 8) or (buf[17].toInt() and 0xff)
                if (n > 512 || length < ArtNet.DMX_HEADER + n) { discarded.incrementAndGet(); return }
                val key = "${from.hostAddress}:$fromPort"
                val universe = ((buf[15].toInt() and 0x7f) shl 8) or (buf[14].toInt() and 0xff)
                val src = sourceFor(universe, key, key)
                val now = System.nanoTime()
                val synchronous = now - lastArtSyncNanos < ART_SYNC_TIMEOUT_NANOS
                val target = if (synchronous) src.pending else src.data
                ArtNet.decodeDmxInto(buf, length, target)
                if (n < 512) java.util.Arrays.fill(target, n, 512, 0)
                if (synchronous) {
                    // Synchronous mode: hold the frame until the next ArtSync (see OP_SYNC below).
                    src.pendingSync = ART_SYNC_PENDING
                    src.pendingSinceNanos = now
                    src.length = n
                    src.lastSeenNanos = now
                    src.packets++
                } else {
                    src.pendingSync = 0 // a frame held from synchronous mode is older than this one
                    accepted(universe, src, n, Sacn.DEFAULT_PRIORITY)
                }
            }
            ArtNet.OP_POLL -> if (answerPolls) {
                val reply = ByteArray(ArtNet.POLL_REPLY_LENGTH)
                // Advertise what is actually arriving (up to the four ports one reply can carry); an
                // idle node advertises its configured ports. Net and SubNet come from the first one.
                val known = sources.keys.sorted().ifEmpty { node.ports.map { it.universe } }.take(4)
                val first = PortAddress(known.firstOrNull()?.coerceIn(0, 0x7fff) ?: 0)
                val info = node.copy(net = first.net, subNet = first.subNet, ports = known.map { ArtNet.NodePort(it and 0x0f) })
                val n = ArtNet.encodePollReply(reply, info)
                runCatching { socket.send(DatagramPacket(reply, n, InetSocketAddress(from, fromPort))) }
                    .onSuccess { pollsAnswered++ }
                    .onFailure { lastError = it }
            }
            ArtNet.OP_POLL_REPLY -> ArtNet.decodePollReply(buf, length)?.let { pollReplies[it.address] = it }
            ArtNet.OP_SYNC -> {
                // Art-Net 4: a node that has seen ArtSync buffers ArtDmx and outputs it on the next
                // ArtSync, and drops back to immediate output after 4 s without one.
                lastArtSyncNanos = System.nanoTime()
                for ((u, table) in sources) for (src in table.values) {
                    if (src.pendingSync == ART_SYNC_PENDING) {
                        System.arraycopy(src.pending, 0, src.data, 0, 512)
                        src.pendingSync = 0
                        bump(u)
                    }
                }
            }
            else -> discarded.incrementAndGet()
        }
    }

    private fun handleSacn(buf: ByteArray, length: Int) {
        when (Sacn.kind(buf, length)) {
            Sacn.Kind.DATA -> {
                val d = Sacn.decodeData(buf, length)
                if (d == null || d.startCode != 0 || (d.preview && !acceptPreview)) { discarded.incrementAndGet(); return }
                val key = d.cid.toString()
                val table = sources.computeIfAbsent(d.universe) { ConcurrentHashMap() }
                if (d.streamTerminated) {
                    if (table.remove(key) != null) bump(d.universe)
                    return
                }
                val src = table.computeIfAbsent(key) { Source(key, d.sourceName, d.universe) }
                if (!src.sequence.accept(d.sequence)) { discarded.incrementAndGet(); return }
                if (d.syncAddress != 0) {
                    System.arraycopy(d.data, 0, src.pending, 0, d.data.size)
                    if (d.data.size < 512) java.util.Arrays.fill(src.pending, d.data.size, 512, 0)
                    src.pendingSync = d.syncAddress
                    src.pendingSinceNanos = System.nanoTime()
                    src.length = d.data.size
                    src.priority = d.priority
                    src.lastSeenNanos = System.nanoTime()
                    src.packets++
                    return
                }
                System.arraycopy(d.data, 0, src.data, 0, d.data.size)
                if (d.data.size < 512) java.util.Arrays.fill(src.data, d.data.size, 512, 0)
                src.pendingSync = 0 // anything still held for a sync is older than this
                accepted(d.universe, src, d.data.size, d.priority)
            }
            Sacn.Kind.SYNC -> {
                val s = Sacn.decodeSync(buf, length) ?: return
                for ((u, table) in sources) for (src in table.values) {
                    if (src.pendingSync == s.syncAddress) {
                        System.arraycopy(src.pending, 0, src.data, 0, 512)
                        src.pendingSync = 0
                        bump(u)
                    }
                }
            }
            Sacn.Kind.DISCOVERY -> Sacn.decodeDiscovery(buf, length)?.let { discoveredSources[it.cid] = it }
            null -> discarded.incrementAndGet()
        }
    }

    private fun sourceFor(universe: Int, key: String, name: String): Source =
        sources.computeIfAbsent(universe) { ConcurrentHashMap() }.computeIfAbsent(key) { Source(key, name, universe) }

    private fun accepted(universe: Int, src: Source, length: Int, priority: Int) {
        src.length = length
        src.priority = priority
        src.lastSeenNanos = System.nanoTime()
        src.packets++
        bump(universe)
        if (listeners.isNotEmpty()) {
            val merged = universe(universe)
            listeners.forEach { l -> runCatching { l(universe, merged) }.onFailure { lastError = it } }
        }
    }

    private fun bump(universe: Int) {
        versions.computeIfAbsent(universe) { AtomicLong() }.incrementAndGet()
    }

    private val timeoutNanos: Long
        get() = (if (protocol == DmxProtocol.SACN) Sacn.NETWORK_DATA_LOSS_TIMEOUT_MS else 10_000L) * 1_000_000

    /** Sources currently live on [universe] (timed-out ones are pruned here). */
    fun sources(universe: Int, nowNanos: Long = System.nanoTime()): List<Source> {
        val table = sources[universe] ?: return emptyList()
        table.values.removeIf { nowNanos - it.lastSeenNanos > timeoutNanos }
        // A sync address nobody ever syncs must not freeze the universe forever: after the data-loss
        // timeout's worth of waiting, commit what is pending.
        for (s in table.values) if (s.pendingSync != 0 && nowNanos - s.pendingSinceNanos > timeoutNanos / 2) {
            System.arraycopy(s.pending, 0, s.data, 0, 512)
            s.pendingSync = 0
        }
        return table.values.toList()
    }

    /** Every universe any live source has sent, ascending. */
    val universes: List<Int> get() = sources.keys.filter { sources(it).isNotEmpty() }.sorted()

    /**
     * The merged 512 slots of [universe] (a fresh array; all zeros if nothing is live). Use [into] to
     * reuse a buffer on a per-frame path.
     */
    fun universe(universe: Int, into: ByteArray = ByteArray(512)): ByteArray {
        java.util.Arrays.fill(into, 0)
        val live = sources(universe)
        if (live.isEmpty()) return into
        val top = if (protocol == DmxProtocol.SACN) live.maxOf { it.priority } else Int.MIN_VALUE
        val eligible = if (protocol == DmxProtocol.SACN) live.filter { it.priority == top } else live
        when (merge) {
            MergeMode.LTP -> System.arraycopy(eligible.maxBy { it.lastSeenNanos }.data, 0, into, 0, 512)
            MergeMode.HTP -> for (s in eligible) for (i in 0 until 512) {
                val v = s.data[i].toInt() and 0xff
                if (v > (into[i].toInt() and 0xff)) into[i] = v.toByte()
            }
        }
        return into
    }

    /** Value of 1-based [channel] of the merged [universe], 0..255. */
    fun channel(universe: Int, channel: Int): Int = universe(universe)[channel - 1].toInt() and 0xff

    /** Increments whenever [universe]'s merged data may have changed; cheap to poll per frame. */
    fun version(universe: Int): Long = versions[universe]?.get() ?: 0L

    /**
     * Call [listener] with `(universe, merged slots)` on the receive thread after every accepted data
     * packet. Keep it short; hand work to the main loop if it touches Field state.
     */
    fun onFrame(listener: (universe: Int, data: ByteArray) -> Unit) {
        listeners += listener
    }

    /** Stop receiving and release the socket. Idempotent. */
    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { socket.close() }
        runCatching { thread.join(500) }
    }

    private companion object {
        /** `Source.pendingSync` value meaning "held for the next ArtSync" (sACN uses the sync universe). */
        const val ART_SYNC_PENDING = -1
        const val ART_SYNC_TIMEOUT_NANOS = 4_000_000_000L
    }

    override fun toString() =
        "DmxInput(${protocol.name} :$port, ${merge.name}, universes=$universes, received=$packetsReceived${if (isClosed) ", closed" else ""})"
}

@file:afield.CompletionCategory("Lighting")

package afield.dmx

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Streaming ACN (ANSI E1.31-2016, "sACN") packet encoding and decoding — the wire format only.
 *
 * sACN carries DMX512 universes over UDP port 5568 in three nested ACN PDUs — root layer, framing
 * layer, DMP layer — each opening with a 16-bit "flags and length" word whose top nibble is `0x7`
 * and whose low 12 bits count the bytes *from that word to the end of the packet*. All multi-byte
 * fields are big-endian. This file implements:
 *
 * - **Data packets** (root vector `0x04`, framing vector `0x02`): 126-byte header plus a START code
 *   and up to 512 slots — 638 bytes at most.
 * - **Synchronization packets** (root `0x08`, framing `0x01`, 49 bytes): E1.31 §6.3 universe sync.
 *   A data packet whose `Synchronization Address` is non-zero is held by receivers until a sync
 *   packet for that address arrives.
 * - **Universe discovery packets** (root `0x08`, framing `0x02`, §8): the list of universes a source
 *   is transmitting, sent every 10 s ([DISCOVERY_INTERVAL_MS]) to universe 64214.
 *
 * Universes are 1..63999; multicast traffic for universe `u` goes to `239.255.(u >> 8).(u & 0xff)`
 * ([multicastAddress]). Every source identifies itself by a 16-byte CID (a UUID), which is also how
 * a receiver tells two sources on the same universe apart for priority and merging.
 */
object Sacn {
    /** UDP port for all E1.31 traffic: 5568 (ACN_SDT_MULTICAST_PORT). */
    const val PORT = 5568

    const val VECTOR_ROOT_E131_DATA = 0x00000004
    const val VECTOR_ROOT_E131_EXTENDED = 0x00000008
    const val VECTOR_E131_DATA_PACKET = 0x00000002
    const val VECTOR_E131_EXTENDED_SYNCHRONIZATION = 0x00000001
    const val VECTOR_E131_EXTENDED_DISCOVERY = 0x00000002
    const val VECTOR_DMP_SET_PROPERTY = 0x02
    const val VECTOR_UNIVERSE_DISCOVERY_UNIVERSE_LIST = 0x00000001

    /** Options bit 7: preview data — for visualisers, not for driving lights. */
    const val OPTION_PREVIEW = 0x80

    /** Options bit 6: stream terminated — the source is going away; drop it now. */
    const val OPTION_STREAM_TERMINATED = 0x40

    /** Options bit 5: force synchronization. */
    const val OPTION_FORCE_SYNC = 0x20

    /** Bytes before the first slot (the START code is byte 125). */
    const val DATA_HEADER = 126

    /** Largest data packet: 126 header bytes + 512 slots. */
    const val DATA_MAX_PACKET = DATA_HEADER + 512

    /** Synchronization packets are fixed length. */
    const val SYNC_LENGTH = 49

    /** Universe used for discovery packets (E1.31 §8, E131_DISCOVERY_UNIVERSE). */
    const val DISCOVERY_UNIVERSE = 64214

    /** E131_UNIVERSE_DISCOVERY_INTERVAL (E1.31 Appendix A): 10 s. */
    const val DISCOVERY_INTERVAL_MS = 10_000L

    /** E131_NETWORK_DATA_LOSS_TIMEOUT (E1.31 Appendix A): a source silent this long is gone. */
    const val NETWORK_DATA_LOSS_TIMEOUT_MS = 2_500L

    /** Default priority for a source that does not say otherwise (E1.31 §6.2.3: 0..200, default 100). */
    const val DEFAULT_PRIORITY = 100

    private val ACN_ID = byteArrayOf(0x41, 0x53, 0x43, 0x2d, 0x45, 0x31, 0x2e, 0x31, 0x37, 0x00, 0x00, 0x00)

    /** The multicast group for [universe]: `239.255.hi.lo`. */
    fun multicastAddress(universe: Int): InetAddress {
        require(universe in 1..63999 || universe == DISCOVERY_UNIVERSE) { "sACN universe must be 1..63999, got $universe" }
        return InetAddress.getByAddress(byteArrayOf(239.toByte(), 255.toByte(), (universe shr 8).toByte(), universe.toByte()))
    }

    /** A CID as the 16 bytes E1.31 puts on the wire (UUID, most significant byte first). */
    fun cidBytes(cid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(cid.mostSignificantBits).putLong(cid.leastSignificantBits).array()

    private fun cidOf(buf: ByteArray, at: Int): UUID {
        val bb = ByteBuffer.wrap(buf, at, 16)
        return UUID(bb.long, bb.long)
    }

    private fun put16(buf: ByteArray, at: Int, v: Int) {
        buf[at] = (v shr 8).toByte(); buf[at + 1] = v.toByte()
    }

    private fun put32(buf: ByteArray, at: Int, v: Int) {
        buf[at] = (v shr 24).toByte(); buf[at + 1] = (v shr 16).toByte()
        buf[at + 2] = (v shr 8).toByte(); buf[at + 3] = v.toByte()
    }

    private fun get16(buf: ByteArray, at: Int) = ((buf[at].toInt() and 0xff) shl 8) or (buf[at + 1].toInt() and 0xff)

    private fun get32(buf: ByteArray, at: Int) =
        ((buf[at].toInt() and 0xff) shl 24) or ((buf[at + 1].toInt() and 0xff) shl 16) or
            ((buf[at + 2].toInt() and 0xff) shl 8) or (buf[at + 3].toInt() and 0xff)

    /** Flags-and-length word: `0x7` in the top nibble, 12-bit length below. */
    private fun flagsLength(length: Int) = 0x7000 or (length and 0x0fff)

    private fun writeRoot(buf: ByteArray, total: Int, vector: Int, cid: ByteArray) {
        put16(buf, 0, 0x0010) // preamble size
        put16(buf, 2, 0x0000) // post-amble size
        System.arraycopy(ACN_ID, 0, buf, 4, 12)
        put16(buf, 16, flagsLength(total - 16))
        put32(buf, 18, vector)
        System.arraycopy(cid, 0, buf, 22, 16)
    }

    private fun writeSourceName(buf: ByteArray, at: Int, name: String) {
        java.util.Arrays.fill(buf, at, at + 64, 0)
        val bytes = name.toByteArray(Charsets.UTF_8)
        System.arraycopy(bytes, 0, buf, at, minOf(bytes.size, 63)) // null-terminated, 64 bytes
    }

    /**
     * Encode an E1.31 data packet into [buf] and return its length (`126 + length`).
     *
     * @param cid the 16-byte source CID, see [cidBytes]; encode it once and keep it.
     * @param sequence 0..255, incremented by the sender per universe per packet.
     * @param options bit flags: [OPTION_PREVIEW], [OPTION_STREAM_TERMINATED], [OPTION_FORCE_SYNC].
     * @param syncAddress universe of the sync packets this data waits for; 0 = unsynchronised.
     * @param startCode 0x00 for dimmer data; 0xDD is the widely used per-channel-priority extension.
     */
    fun encodeData(
        buf: ByteArray,
        cid: ByteArray,
        sourceName: String,
        universe: Int,
        data: ByteArray,
        length: Int = 512,
        sequence: Int = 0,
        priority: Int = DEFAULT_PRIORITY,
        options: Int = 0,
        syncAddress: Int = 0,
        startCode: Int = 0,
        dataOffset: Int = 0,
    ): Int {
        require(length in 0..512) { "an E1.31 data packet carries 0..512 slots, got $length" }
        require(universe in 1..63999) { "sACN universe must be 1..63999, got $universe" }
        require(priority in 0..200) { "sACN priority is 0..200, got $priority" }
        val total = DATA_HEADER + length
        require(buf.size >= total)
        writeRoot(buf, total, VECTOR_ROOT_E131_DATA, cid)
        // Framing layer
        put16(buf, 38, flagsLength(total - 38))
        put32(buf, 40, VECTOR_E131_DATA_PACKET)
        writeSourceName(buf, 44, sourceName)
        buf[108] = priority.toByte()
        put16(buf, 109, syncAddress)
        buf[111] = sequence.toByte()
        buf[112] = options.toByte()
        put16(buf, 113, universe)
        // DMP layer
        put16(buf, 115, flagsLength(total - 115))
        buf[117] = VECTOR_DMP_SET_PROPERTY.toByte()
        buf[118] = 0xa1.toByte()   // address type & data type
        put16(buf, 119, 0x0000)    // first property address
        put16(buf, 121, 0x0001)    // address increment
        put16(buf, 123, length + 1) // property value count: START code + slots
        buf[125] = startCode.toByte()
        System.arraycopy(data, dataOffset, buf, DATA_HEADER, length)
        return total
    }

    /** Encode an E1.31 synchronization packet (49 bytes) for [syncAddress] and return its length. */
    fun encodeSync(buf: ByteArray, cid: ByteArray, syncAddress: Int, sequence: Int): Int {
        require(syncAddress in 1..63999)
        require(buf.size >= SYNC_LENGTH)
        writeRoot(buf, SYNC_LENGTH, VECTOR_ROOT_E131_EXTENDED, cid)
        put16(buf, 38, flagsLength(SYNC_LENGTH - 38))
        put32(buf, 40, VECTOR_E131_EXTENDED_SYNCHRONIZATION)
        buf[44] = sequence.toByte()
        put16(buf, 45, syncAddress)
        buf[47] = 0; buf[48] = 0 // reserved
        return SYNC_LENGTH
    }

    /**
     * Encode one page of a universe discovery packet and return its length (`120 + 2n`).
     * [universes] must be sorted ascending and at most 512 long (one page).
     */
    fun encodeDiscovery(
        buf: ByteArray,
        cid: ByteArray,
        sourceName: String,
        universes: IntArray,
        page: Int = 0,
        lastPage: Int = 0,
    ): Int {
        require(universes.size <= 512) { "one discovery page lists at most 512 universes" }
        val total = 120 + 2 * universes.size
        require(buf.size >= total)
        writeRoot(buf, total, VECTOR_ROOT_E131_EXTENDED, cid)
        put16(buf, 38, flagsLength(total - 38))
        put32(buf, 40, VECTOR_E131_EXTENDED_DISCOVERY)
        writeSourceName(buf, 44, sourceName)
        put32(buf, 108, 0) // reserved
        put16(buf, 112, flagsLength(total - 112))
        put32(buf, 114, VECTOR_UNIVERSE_DISCOVERY_UNIVERSE_LIST)
        buf[118] = page.toByte()
        buf[119] = lastPage.toByte()
        universes.forEachIndexed { i, u -> put16(buf, 120 + 2 * i, u) }
        return total
    }

    private fun isAcn(buf: ByteArray, length: Int): Boolean {
        if (length < 38) return false
        if (get16(buf, 0) != 0x0010) return false
        for (i in 0 until 12) if (buf[4 + i] != ACN_ID[i]) return false
        return true
    }

    /** What kind of E1.31 packet [buf] holds, or null if it is not one we understand. */
    fun kind(buf: ByteArray, length: Int = buf.size): Kind? {
        if (!isAcn(buf, length) || length < 44) return null
        val root = get32(buf, 18)
        val framing = get32(buf, 40)
        return when {
            root == VECTOR_ROOT_E131_DATA && framing == VECTOR_E131_DATA_PACKET -> Kind.DATA
            root == VECTOR_ROOT_E131_EXTENDED && framing == VECTOR_E131_EXTENDED_SYNCHRONIZATION -> Kind.SYNC
            root == VECTOR_ROOT_E131_EXTENDED && framing == VECTOR_E131_EXTENDED_DISCOVERY -> Kind.DISCOVERY
            else -> null
        }
    }

    /** Packet kinds this decoder recognises. */
    enum class Kind { DATA, SYNC, DISCOVERY }

    /** Decode a data packet; [Data.data] holds exactly the slots after the START code. */
    fun decodeData(buf: ByteArray, length: Int = buf.size): Data? {
        if (kind(buf, length) != Kind.DATA || length < DATA_HEADER) return null
        if (buf[117].toInt() != VECTOR_DMP_SET_PROPERTY || (buf[118].toInt() and 0xff) != 0xa1) return null
        val count = get16(buf, 123) - 1
        if (count < 0 || count > 512 || DATA_HEADER + count > length) return null
        val nameEnd = (44 until 108).firstOrNull { buf[it] == 0.toByte() } ?: 108
        return Data(
            cid = cidOf(buf, 22),
            sourceName = String(buf, 44, nameEnd - 44, Charsets.UTF_8),
            priority = buf[108].toInt() and 0xff,
            syncAddress = get16(buf, 109),
            sequence = buf[111].toInt() and 0xff,
            options = buf[112].toInt() and 0xff,
            universe = get16(buf, 113),
            startCode = buf[125].toInt() and 0xff,
            data = buf.copyOfRange(DATA_HEADER, DATA_HEADER + count),
        )
    }

    /** Decode a synchronization packet. */
    fun decodeSync(buf: ByteArray, length: Int = buf.size): Sync? {
        if (kind(buf, length) != Kind.SYNC || length < SYNC_LENGTH) return null
        return Sync(cidOf(buf, 22), buf[44].toInt() and 0xff, get16(buf, 45))
    }

    /** Decode one page of a universe discovery packet. */
    fun decodeDiscovery(buf: ByteArray, length: Int = buf.size): Discovery? {
        if (kind(buf, length) != Kind.DISCOVERY || length < 120) return null
        if (get32(buf, 114) != VECTOR_UNIVERSE_DISCOVERY_UNIVERSE_LIST) return null
        val n = (length - 120) / 2
        val nameEnd = (44 until 108).firstOrNull { buf[it] == 0.toByte() } ?: 108
        return Discovery(
            cid = cidOf(buf, 22),
            sourceName = String(buf, 44, nameEnd - 44, Charsets.UTF_8),
            page = buf[118].toInt() and 0xff,
            lastPage = buf[119].toInt() and 0xff,
            universes = IntArray(n) { get16(buf, 120 + 2 * it) },
        )
    }

    /** A decoded data packet. */
    class Data(
        val cid: UUID,
        val sourceName: String,
        val priority: Int,
        val syncAddress: Int,
        val sequence: Int,
        val options: Int,
        val universe: Int,
        val startCode: Int,
        val data: ByteArray,
    ) {
        val preview: Boolean get() = options and OPTION_PREVIEW != 0
        val streamTerminated: Boolean get() = options and OPTION_STREAM_TERMINATED != 0
        override fun toString() = "sACN(universe=$universe, \"$sourceName\", prio=$priority, seq=$sequence, ${data.size} slots)"
    }

    /** A decoded synchronization packet. */
    data class Sync(val cid: UUID, val sequence: Int, val syncAddress: Int)

    /** A decoded discovery page. */
    class Discovery(val cid: UUID, val sourceName: String, val page: Int, val lastPage: Int, val universes: IntArray) {
        override fun toString() = "sACN discovery(\"$sourceName\", page $page/$lastPage, ${universes.toList()})"
    }
}

/**
 * The E1.31 §6.7.2 sequence-number rule, per source per universe.
 *
 * Sequence numbers are 8-bit and wrap, so "older" is decided on the signed 8-bit difference
 * `new - last`: a packet is **discarded** when that difference is in `-19..0` — a duplicate, or one
 * that arrived out of order within a 20-packet window. Anything else is accepted, which is what lets
 * a source that restarted with a random sequence number be heard again after at most one packet.
 */
class SacnSequence {
    private var last = -1

    /** Returns true to accept [sequence] (and remember it), false to discard the packet. */
    fun accept(sequence: Int): Boolean {
        val s = sequence and 0xff
        if (last < 0) { last = s; return true }
        val diff = (s - last).toByte().toInt() // signed 8-bit difference
        if (diff <= 0 && diff > -20) return false
        last = s
        return true
    }

    /** Forget the last sequence number, e.g. after the source timed out. */
    fun reset() { last = -1 }

    companion object {
        /** The stateless form of the rule: would [next] be accepted after [last]? */
        fun isNewer(last: Int, next: Int): Boolean {
            val diff = ((next and 0xff) - (last and 0xff)).toByte().toInt()
            return !(diff <= 0 && diff > -20)
        }
    }
}

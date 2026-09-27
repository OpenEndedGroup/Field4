@file:afield.CompletionCategory("Lighting")

package afield.dmx

/**
 * Art-Net 4 packet encoding and decoding — the wire format only, no sockets.
 *
 * Art-Net (Artistic Licence, "Art-Net 4 Specification", protocol version 14) carries DMX512 over
 * UDP port 6454 (`0x1936`). Every packet starts with the 8-byte id `"Art-Net\0"` followed by a
 * 16-bit OpCode sent **low byte first**; most other multi-byte fields are sent high byte first,
 * which is the single most common way hand-rolled Art-Net goes wrong. This file implements the
 * four packets a lighting bridge needs:
 *
 * - **ArtDmx** (`0x5000`) — one universe of slot data, 2..512 bytes, even length.
 * - **ArtSync** (`0x5200`) — "latch the frames you have buffered now", for tear-free multi-universe
 *   output.
 * - **ArtPoll** (`0x2000`) / **ArtPollReply** (`0x2100`) — discovery. Field answers ArtPoll as a node
 *   when it is receiving (see [DmxInput]).
 *
 * Encoders write into a caller-owned `ByteArray` and return the number of bytes written, so a
 * sender can reuse one buffer per universe for its whole life; nothing on the send path allocates.
 * Decoders return small immutable values, or `null` for a packet that is not Art-Net or not one of
 * these OpCodes.
 */
object ArtNet {
    /** UDP port every Art-Net packet is sent to and received on: 6454 (`0x1936`). */
    const val PORT = 6454

    /** Protocol revision this implementation writes into `ProtVerHi/Lo`: 14. */
    const val PROTOCOL_VERSION = 14

    const val OP_POLL = 0x2000
    const val OP_POLL_REPLY = 0x2100
    const val OP_DMX = 0x5000
    const val OP_SYNC = 0x5200

    /** Size of the ArtDmx header before the slot data. */
    const val DMX_HEADER = 18

    /** Largest ArtDmx packet: header plus 512 slots. */
    const val DMX_MAX_PACKET = DMX_HEADER + 512

    /** ArtSync is fixed length. */
    const val SYNC_LENGTH = 14

    /** Art-Net 4 ArtPoll, including the targeted-mode and ESTA/OEM fields. */
    const val POLL_LENGTH = 22

    /** Art-Net 4 ArtPollReply, including the trailing filler. */
    const val POLL_REPLY_LENGTH = 239

    private val ID = byteArrayOf(
        'A'.code.toByte(), 'r'.code.toByte(), 't'.code.toByte(), '-'.code.toByte(),
        'N'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte(), 0
    )

    private fun writeHeader(buf: ByteArray, opCode: Int) {
        System.arraycopy(ID, 0, buf, 0, 8)
        buf[8] = (opCode and 0xff).toByte()          // OpCode low byte first
        buf[9] = ((opCode shr 8) and 0xff).toByte()
    }

    private fun writeProtVer(buf: ByteArray, at: Int) {
        buf[at] = 0
        buf[at + 1] = PROTOCOL_VERSION.toByte()
    }

    /**
     * Encode an ArtDmx packet into [buf] and return its length.
     *
     * [length] slots are copied from [data] starting at [dataOffset]. Art-Net requires an even
     * length in 2..512, so an odd [length] is padded with one zero slot and a length below 2 is
     * padded to 2 — the receiver sees the extra zero, which is what the spec intends.
     *
     * @param sequence 1..255 to let receivers reorder, 0 to disable sequencing.
     * @param physical informational: the physical input port the data came from.
     */
    fun encodeDmx(
        buf: ByteArray,
        portAddress: PortAddress,
        data: ByteArray,
        length: Int = 512,
        sequence: Int = 0,
        physical: Int = 0,
        dataOffset: Int = 0,
    ): Int {
        require(length in 0..512) { "ArtDmx carries 0..512 slots, got $length" }
        var wire = if (length % 2 == 1) length + 1 else length
        if (wire < 2) wire = 2
        require(buf.size >= DMX_HEADER + wire) { "buffer holds ${buf.size} bytes, ArtDmx needs ${DMX_HEADER + wire}" }
        writeHeader(buf, OP_DMX)
        writeProtVer(buf, 10)
        buf[12] = sequence.toByte()
        buf[13] = physical.toByte()
        buf[14] = portAddress.subUni.toByte()      // SubUni: SubNet << 4 | Universe
        buf[15] = portAddress.net.toByte()         // Net: bits 14..8
        buf[16] = ((wire shr 8) and 0xff).toByte() // LengthHi — big-endian, unlike OpCode
        buf[17] = (wire and 0xff).toByte()
        System.arraycopy(data, dataOffset, buf, DMX_HEADER, length)
        for (i in length until wire) buf[DMX_HEADER + i] = 0
        return DMX_HEADER + wire
    }

    /** Encode an ArtSync packet into [buf] (14 bytes) and return its length. */
    fun encodeSync(buf: ByteArray): Int {
        require(buf.size >= SYNC_LENGTH)
        writeHeader(buf, OP_SYNC)
        writeProtVer(buf, 10)
        buf[12] = 0 // Aux1
        buf[13] = 0 // Aux2
        return SYNC_LENGTH
    }

    /**
     * Encode an Art-Net 4 ArtPoll into [buf] (22 bytes) and return its length.
     *
     * @param flags `TalkToMe`: bit 1 asks nodes to send ArtPollReply whenever their state changes.
     */
    fun encodePoll(buf: ByteArray, flags: Int = 0x02, diagPriority: Int = 0x10): Int {
        require(buf.size >= POLL_LENGTH)
        writeHeader(buf, OP_POLL)
        writeProtVer(buf, 10)
        buf[12] = flags.toByte()
        buf[13] = diagPriority.toByte()
        // Target port-address range (only honoured when flags bit 5 is set): all universes.
        buf[14] = 0x7f; buf[15] = 0xff.toByte()
        buf[16] = 0; buf[17] = 0
        buf[18] = 0; buf[19] = 0 // EstaMan
        buf[20] = 0; buf[21] = 0 // Oem
        return POLL_LENGTH
    }

    /**
     * Encode an ArtPollReply (239 bytes) describing a node and return its length.
     *
     * A node advertises up to four ports that share one Net and SubNet (the `NetSwitch` /
     * `SubSwitch` fields); a node with more universes sends one reply per page, distinguished by
     * `BindIndex`. That is what [NodeInfo.bindIndex] is for.
     */
    fun encodePollReply(buf: ByteArray, node: NodeInfo): Int {
        require(buf.size >= POLL_REPLY_LENGTH)
        java.util.Arrays.fill(buf, 0, POLL_REPLY_LENGTH, 0)
        writeHeader(buf, OP_POLL_REPLY)
        for (i in 0 until 4) buf[10 + i] = node.ip.getOrElse(i) { 0 }
        buf[14] = (PORT and 0xff).toByte()          // Port, low byte first, always 0x1936
        buf[15] = ((PORT shr 8) and 0xff).toByte()
        buf[16] = 0; buf[17] = 1                    // VersInfo: firmware 1
        buf[18] = node.net.toByte()                 // NetSwitch
        buf[19] = node.subNet.toByte()              // SubSwitch
        buf[20] = 0x00; buf[21] = 0xff.toByte()     // Oem: 0x00ff, "unknown OEM"
        buf[22] = 0                                 // Ubea
        buf[23] = 0xd0.toByte()                     // Status1: indicators normal, front-panel port-address
        buf[24] = 0; buf[25] = 0                    // EstaMan (Lo, Hi): 0 = prototype
        writeAscii(buf, 26, 18, node.shortName)
        writeAscii(buf, 44, 64, node.longName)
        writeAscii(buf, 108, 64, node.report)
        val ports = node.ports.take(4)
        buf[172] = 0; buf[173] = ports.size.toByte() // NumPorts
        ports.forEachIndexed { i, p ->
            // PortTypes: bit 7 output (Art-Net -> DMX), bit 6 input (DMX -> Art-Net); low bits 0 = DMX512.
            buf[174 + i] = ((if (p.output) 0x80 else 0) or (if (p.input) 0x40 else 0)).toByte()
            buf[178 + i] = if (p.input) 0x80.toByte() else 0                    // GoodInput: data received
            buf[182 + i] = if (p.output) 0x80.toByte() else 0                   // GoodOutputA: data being output
            buf[186 + i] = (p.universe and 0x0f).toByte()                       // SwIn
            buf[190 + i] = (p.universe and 0x0f).toByte()                       // SwOut
        }
        buf[194] = 100                              // AcnPriority
        buf[200] = node.style.toByte()              // Style: 0x00 StNode, 0x01 StController
        for (i in 0 until 6) buf[201 + i] = node.mac.getOrElse(i) { 0 }
        for (i in 0 until 4) buf[207 + i] = node.ip.getOrElse(i) { 0 } // BindIp
        buf[211] = node.bindIndex.toByte()
        buf[212] = 0x08                             // Status2: supports 15-bit port-address
        return POLL_REPLY_LENGTH
    }

    private fun writeAscii(buf: ByteArray, at: Int, max: Int, s: String) {
        val bytes = s.toByteArray(Charsets.US_ASCII)
        val n = minOf(bytes.size, max - 1) // always leave the terminating null
        System.arraycopy(bytes, 0, buf, at, n)
    }

    private fun readAscii(buf: ByteArray, at: Int, max: Int, limit: Int): String {
        var end = at
        val stop = minOf(at + max, limit)
        while (end < stop && buf[end] != 0.toByte()) end++
        return String(buf, at, end - at, Charsets.US_ASCII)
    }

    /** True if [buf] starts with the Art-Net id; says nothing about the OpCode. */
    fun isArtNet(buf: ByteArray, length: Int = buf.size): Boolean {
        if (length < 10) return false
        for (i in 0 until 8) if (buf[i] != ID[i]) return false
        return true
    }

    /** The OpCode of an Art-Net packet, or -1 if [buf] is not one. */
    fun opCode(buf: ByteArray, length: Int = buf.size): Int =
        if (!isArtNet(buf, length)) -1 else (buf[8].toInt() and 0xff) or ((buf[9].toInt() and 0xff) shl 8)

    /**
     * Decode an ArtDmx packet. The returned [Dmx.data] is a fresh copy of exactly `length` slots;
     * use [decodeDmxInto] on a hot receive path to avoid the copy.
     */
    fun decodeDmx(buf: ByteArray, length: Int = buf.size): Dmx? {
        if (opCode(buf, length) != OP_DMX || length < DMX_HEADER) return null
        val n = ((buf[16].toInt() and 0xff) shl 8) or (buf[17].toInt() and 0xff)
        if (n > 512 || DMX_HEADER + n > length) return null
        return Dmx(
            sequence = buf[12].toInt() and 0xff,
            physical = buf[13].toInt() and 0xff,
            portAddress = PortAddress.of(buf[15].toInt() and 0x7f, buf[14].toInt() and 0xff),
            data = buf.copyOfRange(DMX_HEADER, DMX_HEADER + n),
        )
    }

    /**
     * Decode just the header of an ArtDmx packet and copy its slots into [into]; returns the port
     * address as its raw 15-bit int, or -1 if this is not a well-formed ArtDmx. The slot count is
     * `(buf[16] shl 8) or buf[17]`.
     */
    fun decodeDmxInto(buf: ByteArray, length: Int, into: ByteArray): Int {
        if (opCode(buf, length) != OP_DMX || length < DMX_HEADER) return -1
        val n = ((buf[16].toInt() and 0xff) shl 8) or (buf[17].toInt() and 0xff)
        if (n > 512 || DMX_HEADER + n > length) return -1
        System.arraycopy(buf, DMX_HEADER, into, 0, minOf(n, into.size))
        return ((buf[15].toInt() and 0x7f) shl 8) or (buf[14].toInt() and 0xff)
    }

    /** Decode an ArtPoll (accepts the 14-byte pre-Art-Net-4 form as well as the 22-byte one). */
    fun decodePoll(buf: ByteArray, length: Int = buf.size): Poll? {
        if (opCode(buf, length) != OP_POLL || length < 14) return null
        return Poll(
            protocolVersion = ((buf[10].toInt() and 0xff) shl 8) or (buf[11].toInt() and 0xff),
            flags = buf[12].toInt() and 0xff,
            diagPriority = buf[13].toInt() and 0xff,
        )
    }

    /** Decode an ArtPollReply. Accepts the shorter replies older nodes send (>= 207 bytes). */
    fun decodePollReply(buf: ByteArray, length: Int = buf.size): PollReply? {
        if (opCode(buf, length) != OP_POLL_REPLY || length < 207) return null
        val numPorts = minOf(4, buf[173].toInt() and 0xff)
        val net = buf[18].toInt() and 0x7f
        val sub = buf[19].toInt() and 0x0f
        val universes = (0 until numPorts).map { i ->
            PortAddress(net, sub, buf[190 + i].toInt() and 0x0f)
        }
        return PollReply(
            ip = buf.copyOfRange(10, 14),
            port = (buf[14].toInt() and 0xff) or ((buf[15].toInt() and 0xff) shl 8),
            shortName = readAscii(buf, 26, 18, length),
            longName = readAscii(buf, 44, 64, length),
            report = readAscii(buf, 108, 64, length),
            portTypes = (0 until numPorts).map { buf[174 + it].toInt() and 0xff },
            outputs = universes,
            style = buf[200].toInt() and 0xff,
            bindIndex = if (length > 211) buf[211].toInt() and 0xff else 0,
        )
    }

    /** A decoded ArtDmx. */
    class Dmx(val sequence: Int, val physical: Int, val portAddress: PortAddress, val data: ByteArray) {
        override fun toString() = "ArtDmx($portAddress, seq=$sequence, ${data.size} slots)"
    }

    /** A decoded ArtPoll. */
    data class Poll(val protocolVersion: Int, val flags: Int, val diagPriority: Int)

    /** A decoded ArtPollReply: who a node is and which universes it outputs. */
    class PollReply(
        val ip: ByteArray,
        val port: Int,
        val shortName: String,
        val longName: String,
        val report: String,
        val portTypes: List<Int>,
        val outputs: List<PortAddress>,
        val style: Int,
        val bindIndex: Int,
    ) {
        /** Dotted-quad form of [ip]. */
        val address: String get() = ip.joinToString(".") { (it.toInt() and 0xff).toString() }
        override fun toString() = "ArtPollReply($address \"$shortName\" / \"$longName\", outputs=$outputs)"
    }

    /** One advertised port of a node. [universe] is the low 4 bits of the port-address. */
    data class NodePort(val universe: Int, val output: Boolean = true, val input: Boolean = false)

    /** What Field says about itself in an ArtPollReply. */
    data class NodeInfo(
        val shortName: String = "Field4",
        val longName: String = "Field4 live-coding environment (afield.dmx)",
        val report: String = "#0001 [0000] Field4 ok",
        val ip: ByteArray = byteArrayOf(127, 0, 0, 1),
        val mac: ByteArray = ByteArray(6),
        val net: Int = 0,
        val subNet: Int = 0,
        val ports: List<NodePort> = listOf(NodePort(0)),
        val style: Int = 0,
        val bindIndex: Int = 1,
    )
}

/**
 * An Art-Net 4 port-address: the 15-bit universe number, split Net (7 bits) : SubNet (4) :
 * Universe (4).
 *
 * Art-Net numbers universes from **0**; sACN numbers them from **1**. [DmxOutput] uses one integer
 * universe number for both protocols and passes it through unchanged — universe 1 is port-address
 * `0:0:1` in Art-Net and sACN universe 1 — which is how most consoles present a mixed rig, and means
 * Art-Net universe 0 is reachable but sACN universe 0 is not (it is reserved by E1.31).
 */
@JvmInline
value class PortAddress(val value: Int) {
    init {
        require(value in 0..0x7fff) { "Art-Net port-address is 15 bits (0..32767), got $value" }
    }

    /** Build from the three parts. */
    constructor(net: Int, subNet: Int, universe: Int) : this(
        run {
            require(net in 0..127) { "Net is 7 bits, got $net" }
            require(subNet in 0..15) { "SubNet is 4 bits, got $subNet" }
            require(universe in 0..15) { "Universe is 4 bits, got $universe" }
            (net shl 8) or (subNet shl 4) or universe
        }
    )

    /** Bits 14..8. */
    val net: Int get() = (value shr 8) and 0x7f

    /** Bits 7..4. */
    val subNet: Int get() = (value shr 4) and 0x0f

    /** Bits 3..0. */
    val universe: Int get() = value and 0x0f

    /** The `SubUni` byte of ArtDmx: SubNet in the high nibble, Universe in the low. */
    val subUni: Int get() = value and 0xff

    override fun toString() = "$net:$subNet:$universe"

    companion object {
        /** From the two bytes ArtDmx carries: `Net` and `SubUni`. */
        fun of(net: Int, subUni: Int) = PortAddress(((net and 0x7f) shl 8) or (subUni and 0xff))
    }
}

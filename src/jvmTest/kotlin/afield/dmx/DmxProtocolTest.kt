package afield.dmx

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Byte-exact layouts for the Art-Net 4 and E1.31 packets `afield.dmx` writes.
 *
 * The expected bytes are written out from the spec tables (Art-Net 4 "ArtDmx" / "ArtPollReply"
 * packet definitions, E1.31-2016 Table 4-1 / 4-2 / 4-3), not derived from the encoder, so a
 * field in the wrong place or the wrong endianness fails here. The independent check — a Python
 * decoder reading these packets off a socket — is recorded in `dev_docs/dmx_lighting.md`.
 */
class DmxProtocolTest {

    private fun u(b: Byte) = b.toInt() and 0xff

    @Test
    fun portAddressSplitsFifteenBitsIntoNetSubNetUniverse() {
        val pa = PortAddress(net = 0x12, subNet = 0x3, universe = 0x4)
        assertEquals(0x1234, pa.value)
        assertEquals(0x12, pa.net)
        assertEquals(0x3, pa.subNet)
        assertEquals(0x4, pa.universe)
        assertEquals(0x34, pa.subUni)
        assertEquals("18:3:4", pa.toString())
        assertEquals(pa, PortAddress.of(0x12, 0x34))
        assertEquals(32767, PortAddress(127, 15, 15).value)
        assertFailsWith<IllegalArgumentException> { PortAddress(0x8000) }
        assertFailsWith<IllegalArgumentException> { PortAddress(0, 16, 0) }
    }

    @Test
    fun artDmxHeaderIsByteExact() {
        val data = ByteArray(512) { (it * 7).toByte() }
        val buf = ByteArray(ArtNet.DMX_MAX_PACKET)
        val n = ArtNet.encodeDmx(buf, PortAddress(0x1234), data, 512, sequence = 0x2a, physical = 3)
        assertEquals(530, n)
        val expected = byteArrayOf(
            0x41, 0x72, 0x74, 0x2d, 0x4e, 0x65, 0x74, 0x00, // "Art-Net\0"
            0x00, 0x50,                                     // OpCode 0x5000, low byte first
            0x00, 0x0e,                                     // ProtVer 14, high byte first
            0x2a,                                           // Sequence
            0x03,                                           // Physical
            0x34,                                           // SubUni
            0x12,                                           // Net
            0x02, 0x00,                                     // Length 512, high byte first
        )
        assertContentEquals(expected, buf.copyOfRange(0, 18))
        assertContentEquals(data, buf.copyOfRange(18, 530))
    }

    @Test
    fun artDmxPadsOddAndTinyLengthsToAnEvenCountOfAtLeastTwo() {
        val buf = ByteArray(ArtNet.DMX_MAX_PACKET) { 0x55 }
        val n = ArtNet.encodeDmx(buf, PortAddress(1), byteArrayOf(9, 8, 7), 3)
        assertEquals(18 + 4, n)
        assertEquals(0, u(buf[16])); assertEquals(4, u(buf[17]))
        assertContentEquals(byteArrayOf(9, 8, 7, 0), buf.copyOfRange(18, 22))
        assertEquals(18 + 2, ArtNet.encodeDmx(buf, PortAddress(1), ByteArray(0), 0))
    }

    @Test
    fun artDmxRoundTrips() {
        val data = ByteArray(100) { it.toByte() }
        val buf = ByteArray(ArtNet.DMX_MAX_PACKET)
        val n = ArtNet.encodeDmx(buf, PortAddress(5, 1, 9), data, 100, sequence = 200)
        val d = assertNotNull(ArtNet.decodeDmx(buf, n))
        assertEquals(PortAddress(5, 1, 9), d.portAddress)
        assertEquals(200, d.sequence)
        assertContentEquals(data, d.data)
        assertNull(ArtNet.decodeDmx(buf, 17), "a truncated header is not an ArtDmx")
        assertNull(ArtNet.decodeDmx(buf, 18 + 50), "a length field longer than the datagram is rejected")
    }

    @Test
    fun artSyncAndArtPollLayouts() {
        val buf = ByteArray(64)
        assertEquals(14, ArtNet.encodeSync(buf))
        assertContentEquals(
            byteArrayOf(0x41, 0x72, 0x74, 0x2d, 0x4e, 0x65, 0x74, 0x00, 0x00, 0x52, 0x00, 0x0e, 0x00, 0x00),
            buf.copyOfRange(0, 14)
        )
        assertEquals(22, ArtNet.encodePoll(buf, flags = 0x02))
        assertEquals(0x00, u(buf[8])); assertEquals(0x20, u(buf[9]))
        assertEquals(0x02, u(buf[12]))
        assertEquals(ArtNet.Poll(14, 0x02, 0x10), ArtNet.decodePoll(buf, 22))
        assertEquals(ArtNet.OP_POLL, ArtNet.opCode(buf, 22))
    }

    @Test
    fun artPollReplyIs239BytesWithFieldsWhereTheSpecPutsThem() {
        val buf = ByteArray(ArtNet.POLL_REPLY_LENGTH)
        val node = ArtNet.NodeInfo(
            shortName = "Field4", longName = "Field4 long", ip = byteArrayOf(10, 0, 0, 7),
            net = 3, subNet = 2, ports = listOf(ArtNet.NodePort(1), ArtNet.NodePort(2, output = false, input = true)),
        )
        assertEquals(239, ArtNet.encodePollReply(buf, node))
        assertEquals(0x00, u(buf[8])); assertEquals(0x21, u(buf[9]))        // OpCode 0x2100
        assertContentEquals(byteArrayOf(10, 0, 0, 7), buf.copyOfRange(10, 14))
        assertEquals(0x36, u(buf[14])); assertEquals(0x19, u(buf[15]))      // Port 0x1936, low byte first
        assertEquals(3, u(buf[18])); assertEquals(2, u(buf[19]))            // NetSwitch, SubSwitch
        assertEquals('F'.code, u(buf[26])); assertEquals(0, u(buf[32]))     // ShortName, null-terminated
        assertEquals(2, u(buf[173]))                                        // NumPortsLo
        assertEquals(0x80, u(buf[174])); assertEquals(0x40, u(buf[175]))    // PortTypes: output, input
        assertEquals(1, u(buf[190])); assertEquals(2, u(buf[191]))          // SwOut
        val r = assertNotNull(ArtNet.decodePollReply(buf))
        assertEquals("10.0.0.7", r.address)
        assertEquals(6454, r.port)
        assertEquals("Field4", r.shortName)
        assertEquals("Field4 long", r.longName)
        assertEquals(listOf(PortAddress(3, 2, 1), PortAddress(3, 2, 2)), r.outputs)
    }

    private val cid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")

    @Test
    fun sacnFullDataPacketIsByteExact() {
        val buf = ByteArray(Sacn.DATA_MAX_PACKET)
        val data = ByteArray(512) { (255 - it).toByte() }
        val n = Sacn.encodeData(buf, Sacn.cidBytes(cid), "Field4", universe = 0x0102, data = data,
            sequence = 9, priority = 150, options = Sacn.OPTION_PREVIEW, syncAddress = 7)
        assertEquals(638, n)
        // Root layer
        assertContentEquals(byteArrayOf(0x00, 0x10, 0x00, 0x00), buf.copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x41, 0x53, 0x43, 0x2d, 0x45, 0x31, 0x2e, 0x31, 0x37, 0x00, 0x00, 0x00), buf.copyOfRange(4, 16))
        assertEquals(0x72, u(buf[16])); assertEquals(0x6e, u(buf[17]))      // 0x7000 | 622
        assertContentEquals(byteArrayOf(0, 0, 0, 4), buf.copyOfRange(18, 22))
        assertContentEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(),
                0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
            buf.copyOfRange(22, 38)
        )
        // Framing layer
        assertEquals(0x72, u(buf[38])); assertEquals(0x58, u(buf[39]))      // 0x7000 | 600
        assertContentEquals(byteArrayOf(0, 0, 0, 2), buf.copyOfRange(40, 44))
        assertEquals("Field4", String(buf, 44, 6)); assertEquals(0, u(buf[50])); assertEquals(0, u(buf[107]))
        assertEquals(150, u(buf[108]))
        assertEquals(0, u(buf[109])); assertEquals(7, u(buf[110]))          // sync address
        assertEquals(9, u(buf[111]))
        assertEquals(0x80, u(buf[112]))
        assertEquals(0x01, u(buf[113])); assertEquals(0x02, u(buf[114]))    // universe 258
        // DMP layer
        assertEquals(0x72, u(buf[115])); assertEquals(0x0b, u(buf[116]))    // 0x7000 | 523
        assertEquals(0x02, u(buf[117])); assertEquals(0xa1, u(buf[118]))
        assertContentEquals(byteArrayOf(0, 0, 0, 1), buf.copyOfRange(119, 123))
        assertEquals(0x02, u(buf[123])); assertEquals(0x01, u(buf[124]))    // 513 values
        assertEquals(0, u(buf[125]))                                        // START code
        assertContentEquals(data, buf.copyOfRange(126, 638))

        val d = assertNotNull(Sacn.decodeData(buf, n))
        assertEquals(cid, d.cid); assertEquals("Field4", d.sourceName); assertEquals(258, d.universe)
        assertEquals(150, d.priority); assertEquals(7, d.syncAddress); assertEquals(9, d.sequence)
        assertTrue(d.preview); assertFalse(d.streamTerminated)
        assertContentEquals(data, d.data)
    }

    @Test
    fun sacnShortPacketLengthsFollowTheSlotCount() {
        val buf = ByteArray(Sacn.DATA_MAX_PACKET)
        val n = Sacn.encodeData(buf, Sacn.cidBytes(cid), "x", 1, byteArrayOf(1, 2, 3), 3)
        assertEquals(129, n)
        assertEquals(0x7000 or (129 - 16), (u(buf[16]) shl 8) or u(buf[17]))
        assertEquals(0x7000 or (129 - 38), (u(buf[38]) shl 8) or u(buf[39]))
        assertEquals(0x7000 or (129 - 115), (u(buf[115]) shl 8) or u(buf[116]))
        assertEquals(4, (u(buf[123]) shl 8) or u(buf[124]))
    }

    @Test
    fun sacnSyncAndDiscoveryLayouts() {
        val buf = ByteArray(2000)
        assertEquals(49, Sacn.encodeSync(buf, Sacn.cidBytes(cid), syncAddress = 7962, sequence = 3))
        assertEquals(0x70, u(buf[16])); assertEquals(33, u(buf[17]))
        assertContentEquals(byteArrayOf(0, 0, 0, 8), buf.copyOfRange(18, 22))
        assertEquals(0x70, u(buf[38])); assertEquals(11, u(buf[39]))
        assertContentEquals(byteArrayOf(0, 0, 0, 1), buf.copyOfRange(40, 44))
        assertEquals(Sacn.Sync(cid, 3, 7962), Sacn.decodeSync(buf, 49))
        assertEquals(Sacn.Kind.SYNC, Sacn.kind(buf, 49))

        val n = Sacn.encodeDiscovery(buf, Sacn.cidBytes(cid), "Field4", intArrayOf(1, 2, 300))
        assertEquals(126, n)
        assertContentEquals(byteArrayOf(0, 0, 0, 2), buf.copyOfRange(40, 44))
        assertEquals(0x70, u(buf[112])); assertEquals(14, u(buf[113]))
        assertContentEquals(byteArrayOf(0, 0, 0, 1), buf.copyOfRange(114, 118))
        assertEquals(0x01, u(buf[124])); assertEquals(0x2c, u(buf[125]))
        val disc = assertNotNull(Sacn.decodeDiscovery(buf, n))
        assertContentEquals(intArrayOf(1, 2, 300), disc.universes)
        assertEquals("Field4", disc.sourceName)
    }

    @Test
    fun sacnMulticastAddressIs239_255_hi_lo() {
        assertEquals("239.255.1.2", Sacn.multicastAddress(258).hostAddress)
        assertEquals("239.255.0.1", Sacn.multicastAddress(1).hostAddress)
        assertEquals("239.255.250.214", Sacn.multicastAddress(Sacn.DISCOVERY_UNIVERSE).hostAddress)
        assertFailsWith<IllegalArgumentException> { Sacn.multicastAddress(0) }
    }

    @Test
    fun sequenceRuleDiscardsDuplicatesAndTheTwentyPacketBackWindow() {
        val s = SacnSequence()
        assertTrue(s.accept(10))
        assertFalse(s.accept(10), "duplicate: diff 0")
        assertFalse(s.accept(5), "late by 5: diff -5")
        assertTrue(s.accept(11))
        assertTrue(s.accept(200), "a jump forward is accepted")
        assertFalse(s.accept(181), "diff -19 is inside the window")
        assertTrue(s.accept(180), "diff -20 is outside it (a restarted source)")
        val w = SacnSequence()
        assertTrue(w.accept(250))
        assertTrue(w.accept(3), "wrap-around: 3 - 250 is +9 in signed 8-bit")
        assertFalse(w.accept(252), "252 after 3 is -7 in signed 8-bit")
        assertTrue(SacnSequence.isNewer(255, 0))
        assertFalse(SacnSequence.isNewer(0, 255))
    }
}

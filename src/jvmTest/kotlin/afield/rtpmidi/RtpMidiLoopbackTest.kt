package afield.rtpmidi

import afield.rtpmidi.RtpMidiCodec.Command
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.midi.MidiMessage
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** RFC 6295 command-section encoding and the AppleMIDI session packets, byte for byte. */
class RtpMidiCodecTest {
    @Test
    fun `running status is used on encode and restored on decode`() {
        val cmds = listOf(Command(0, b(0x90, 60, 100)), Command(0, b(0x90, 64, 90)), Command(0, b(0xB0, 1, 7)))
        val enc = RtpMidiCodec.encodeCommands(cmds)
        // short header, no Z: length 3 + (1 delta + 2 running) + (1 delta + 3) = 10
        assertContentEquals(b(10, 0x90, 60, 100, 0, 64, 90, 0, 0xB0, 1, 7), enc)
        assertEquals(cmds, RtpMidiCodec.decodeCommands(enc, 0).commands)
    }

    @Test
    fun `long header, Z flag and multi-byte deltas`() {
        val cmds = listOf(Command(300, b(0x80, 60, 0))) + List(8) { Command(it * 1000, b(0xE1, it, 64)) }
        val enc = RtpMidiCodec.encodeCommands(cmds, runningStatus = false)
        assertTrue(enc[0].toInt() and 0x80 != 0, "long header")
        assertTrue(enc[0].toInt() and 0x20 != 0, "Z flag")
        assertEquals(cmds, RtpMidiCodec.decodeCommands(enc, 0).commands)
    }

    @Test
    fun `real-time inside running status, SysEx cancels it`() {
        // 90 3C 64 | F8 | (running) 3E 50 | F0 01 02 F7 | 90 40 10
        val list = b(0x90, 0x3C, 0x64, 0, 0xF8, 0, 0x3E, 0x50, 0, 0xF0, 1, 2, 0xF7, 0, 0x90, 0x40, 0x10)
        val d = RtpMidiCodec.decodeCommands(b(0x80, list.size, *list.map { it.toInt() and 0xFF }.toIntArray()), 0).commands
        assertEquals(5, d.size)
        assertContentEquals(b(0xF8), d[1].bytes)
        assertContentEquals(b(0x90, 0x3E, 0x50), d[2].bytes)
        assertContentEquals(b(0xF0, 1, 2, 0xF7), d[3].bytes)
    }

    @Test
    fun `a journal after the command list is skipped`() {
        val section = RtpMidiCodec.encodeCommands(listOf(Command(0, b(0x90, 60, 1))))
        section[0] = (section[0].toInt() or 0x40).toByte()
        val withJournal = section + b(0x80, 0x12, 0x34, 0xDE, 0xAD, 0xBE, 0xEF)
        val d = RtpMidiCodec.decodeCommands(withJournal, 0)
        assertTrue(d.journalPresent)
        assertEquals(listOf(Command(0, b(0x90, 60, 1))), d.commands)
    }

    @Test
    fun `segmented SysEx is reassembled across packets`() {
        val first = RtpMidiCodec.decodeCommands(b(4, 0xF0, 1, 2, 0xF0), 0)
        assertTrue(first.commands.isEmpty())
        val mid = RtpMidiCodec.decodeCommands(b(3, 0xF7, 3, 0xF0), 0, sysexPending = first.sysexPending)
        val end = RtpMidiCodec.decodeCommands(b(3, 0xF7, 4, 0xF7), 0, sysexPending = mid.sysexPending)
        assertContentEquals(b(0xF0, 1, 2, 3, 4, 0xF7), end.commands.single().bytes)
    }

    @Test
    fun `session packets round trip`() {
        val all = listOf(
            RtpMidiCodec.Session.Invitation("IN", 0x11223344, 0x55667788, "Field"),
            RtpMidiCodec.Session.Invitation("NO", 1, 2, null),
            RtpMidiCodec.Session.Bye(3, 4),
            RtpMidiCodec.Session.Sync(5, 1, 100, 200, 0),
            RtpMidiCodec.Session.Feedback(6, 0xBEEF),
        )
        for (s in all) { val e = RtpMidiCodec.encode(s); assertEquals(s, RtpMidiCodec.decodeSession(e, e.size)) }
        val inv = RtpMidiCodec.encode(all[0])
        assertContentEquals(b(0xFF, 0xFF, 'I'.code, 'N'.code, 0, 0, 0, 2), inv.copyOf(8))
        assertEquals(0, inv.last().toInt())
    }
}

/** Two sessions in one JVM, over real loopback UDP. */
class RtpMidiLoopbackTest {
    private val open = CopyOnWriteArrayList<RtpMidiSession>()
    private fun session(name: String, accept: Boolean = true) = RtpMidiSession(name, port = 0, bindAddress = "127.0.0.1", acceptInvitations = accept).also { open += it }

    @AfterTest fun tearDown() { open.forEach { it.close() } }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private fun connected(): Triple<RtpMidiSession, RtpMidiSession, RtpMidiSession.Peer> {
        val a = session("A"); val bb = session("B")
        val peer = a.invite("127.0.0.1", bb.port)
        assertTrue(waitFor { peer.connected && bb.connectedPeers.isNotEmpty() }, "invitation completed: $peer / $bb")
        return Triple(a, bb, peer)
    }

    @Test
    fun `invitation connects both ends, with names`() {
        val (a, bb, peer) = connected()
        assertEquals("B", peer.name)
        assertEquals("A", bb.connectedPeers.single().name)
        assertEquals(a.ssrc, bb.connectedPeers.single().ssrc)
    }

    @Test
    fun `clock sync measures offset and round trip on both ends`() {
        val (a, bb, peer) = connected()
        assertTrue(waitFor { peer.offset != null && bb.connectedPeers.single().offset != null }, "CK completed")
        // Both sessions share a clock origin in this JVM, so the true offset is 0.
        assertTrue(abs(peer.offset!!) <= 20, "offset ${peer.offset} within 2 ms")
        assertTrue(abs(bb.connectedPeers.single().offset!!) <= 20)
        assertTrue(peer.roundTrip!! in 0..200, "rtt ${peer.roundTrip}")
        assertNotNull(peer.roundTripStatsMs)
        assertTrue(abs(a.now() - bb.now()) <= 1)
    }

    @Test
    fun `note, CC, running status and SysEx arrive in order`() {
        val (a, bb, _) = connected()
        val got = CopyOnWriteArrayList<MidiMessage>()
        bb.onMessage { m, _, _ -> got += m }
        a.noteOn(60, 100, 2)
        a.controlChange(7, 99, 2)
        a.sendBytes(b(0x91, 62, 80), b(0x91, 64, 81), b(0x91, 62, 0))  // one packet, running status on the wire
        a.sysex(b(0x7D, 1, 2, 3))
        a.noteOff(60, channel = 2)
        assertTrue(waitFor { got.size == 7 }, "received ${got.size}")
        val s = got.take(5).map { it as ShortMessage }
        assertEquals(listOf(0x92 to 60, 0xB2 to 7, 0x91 to 62, 0x91 to 64, 0x91 to 62), s.map { it.status to it.data1 })
        assertEquals(listOf(100, 99, 80, 81, 0), s.map { it.data2 })
        assertContentEquals(b(0xF0, 0x7D, 1, 2, 3, 0xF7), (got[5] as SysexMessage).message)
        assertEquals(0x82, (got[6] as ShortMessage).status)
        // and back the other way
        val back = CopyOnWriteArrayList<MidiMessage>()
        a.onMessage { m, _, _ -> back += m }
        bb.programChange(5)
        assertTrue(waitFor { back.size == 1 })
        assertEquals(5, (back[0] as ShortMessage).data1)
    }

    @Test
    fun `receiver feedback acknowledges the sender's sequence`() {
        val (a, bb, peer) = connected()
        bb.onMessage { _, _, _ -> }
        a.noteOn(1)
        assertTrue(waitFor(3000) { peer.acknowledged >= 0 }, "RS arrived")
        assertEquals(bb.connectedPeers.single().lastSequence, peer.acknowledged)
    }

    @Test
    fun `a refusing session answers NO, and BY disconnects`() {
        val a = session("A"); val no = session("No", accept = false)
        val p = a.invite("127.0.0.1", no.port)
        assertTrue(waitFor { a.peers.isEmpty() }, "refused")
        assertTrue(a.lastError!!.contains("refused"))
        assertTrue(!p.connected)

        val (c, d, _) = connected()
        c.close()
        assertTrue(waitFor { d.connectedPeers.isEmpty() }, "BY received")
    }

    @Test
    fun `a packet with a journal from a raw peer is decoded`() {
        val (_, bb, _) = connected()
        val got = CopyOnWriteArrayList<MidiMessage>()
        val stamps = CopyOnWriteArrayList<Long>()
        bb.onMessage { m, ts, _ -> got += m; stamps += ts }
        // Hand-built RTP from the connected peer's SSRC, J flag set and a journal tail.
        val ssrc = bb.connectedPeers.single().ssrc
        val pkt = RtpMidiCodec.encodeRtp(7, 1234, ssrc, listOf(Command(0, b(0x95, 70, 33))))
        pkt[12] = (pkt[12].toInt() or 0x40).toByte()
        val withJournal = pkt + b(0x00, 0x07, 0x08, 0x81, 0x11, 0x22)
        DatagramSocket().use { it.send(DatagramPacket(withJournal, withJournal.size, InetAddress.getLoopbackAddress(), bb.port + 1)) }
        assertTrue(waitFor { got.size == 1 })
        assertEquals(70, (got[0] as ShortMessage).data1)
        assertEquals(123400L, stamps[0], "RTP timestamp 1234 x 100 us")
    }
}

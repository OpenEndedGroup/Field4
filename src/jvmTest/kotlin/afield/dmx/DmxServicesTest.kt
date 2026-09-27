package afield.dmx

import afield.Box
import afield.graphics.FLine
import afield.stdlib.Img
import afield.stdlib.color
import afield.stdlib.vec
import afield.stopTrackedJobs
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The patch model, the send policy, and loopback round trips through real UDP sockets on
 * 127.0.0.1 (ephemeral ports, so parallel runs and a running Field on 6454/5568 do not collide).
 */
class DmxServicesTest {

    private fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }

    // ---- patch ------------------------------------------------------------------------------

    @Test
    fun rgbStripRollsToTheNextUniverseAfter170Pixels() {
        val patch = Patch(startUniverse = 1)
        val px = patch.strip(200, vec(0.0, 0.0), vec(199.0, 0.0))
        assertEquals(170, px.count { it.universe == 1 })
        assertEquals(30, px.count { it.universe == 2 })
        assertEquals(508, px[169].address)
        assertEquals(510, px[169].lastAddress)
        assertEquals(2, px[170].universe); assertEquals(1, px[170].address)
        assertEquals(listOf(1, 2), patch.universes)
        assertEquals(2 to 91, patch.cursor)
    }

    @Test
    fun rgbwStripHolds128PerUniverseAndDimmersPackTight() {
        val patch = Patch(startUniverse = 3)
        val px = patch.strip(130, vec(0.0, 0.0), vec(1.0, 0.0), FixtureProfile.RGBW)
        assertEquals(128, px.count { it.universe == 3 })
        assertEquals(4, px[128].universe)
        assertEquals(1, px[128].address)
        val dims = Patch().strip(513, vec(0.0, 0.0), vec(1.0, 0.0), FixtureProfile.DIMMER)
        assertEquals(512, dims.count { it.universe == 1 })
        assertEquals(2, dims[512].universe)
    }

    @Test
    fun fixturesWriteTheirSlots() {
        val data = ByteArray(512)
        val patch = Patch()
        val rgb = patch.add(FixtureProfile.RGB, 1, 1, vec(0.0, 0.0)).apply { rgb(1.0, 0.5, 0.0) }
        val rgb16 = patch.add(FixtureProfile.RGB16, 1, 10, vec(0.0, 0.0)).apply { rgb(0.5, 1.0, 0.0) }
        val rgbw = patch.add(FixtureProfile.RGBW, 1, 20, vec(0.0, 0.0)).apply { rgb(1.0, 0.8, 0.6) }
        val dim = patch.add(FixtureProfile.DIMMER, 1, 30, vec(0.0, 0.0)).apply { rgb(1.0, 1.0, 1.0); dimmer = 0.5 }
        val dim16 = patch.add(FixtureProfile.DIMMER16, 1, 40, vec(0.0, 0.0)).apply { rgb(1.0, 1.0, 1.0) }
        val drgb = patch.add(FixtureProfile.DRGB, 1, 50, vec(0.0, 0.0)).apply { rgb(0.0, 0.0, 1.0); dimmer = 0.2 }
        patch.renderInto { data }
        fun at(ch: Int) = data[ch - 1].toInt() and 0xff
        assertEquals(listOf(255, 128, 0), listOf(at(1), at(2), at(3)))
        // 0.5 * 65535 = 32767.5 -> 32768 = 0x8000
        assertEquals(listOf(0x80, 0x00, 0xff, 0xff, 0, 0), (10..15).map { at(it) })
        // white takes min(r,g,b) = 0.6
        assertEquals(listOf(102, 51, 0, 153), (20..23).map { at(it) })
        // Rec.709 luminance of white is 1 up to double rounding, so allow the last step either way.
        assertTrue(at(30) in 127..128, "dimmer 0.5 x luminance 1, got ${at(30)}")
        assertTrue(((at(40) shl 8) or at(41)) >= 0xfffe, "16-bit dimmer at full")
        assertEquals(listOf(51, 0, 0, 255), (50..53).map { at(it) }, "DRGB: dimmer is the master, colour unscaled")
        listOf(rgb, rgb16, rgbw, dim, dim16, drgb).forEach { assertTrue(it.lastAddress <= 512) }
    }

    @Test
    fun gammaIsAppliedBeforeQuantisation() {
        val data = ByteArray(512)
        val p = Patch()
        p.add(FixtureProfile.RGB.withGamma(2.0), 1, 1, vec(0.0, 0.0)).rgb(0.5, 1.0, 0.0)
        p.renderInto { data }
        assertEquals(64, data[0].toInt() and 0xff) // 0.25 * 255 = 63.75
    }

    @Test
    fun mappersColourByPositionAlongALineAndFromAnImage() {
        val p = Patch()
        val line = FLine().moveTo(0.0, 0.0).lineTo(100.0, 0.0).lineTo(100.0, 100.0)
        val px = p.stripAlong(line, 5)
        assertEquals(5, px.size)
        assertEquals(100.0, px[2].position.x.toDouble(), 1e-3)
        assertEquals(0.0, px[2].position.y.toDouble(), 1e-3)
        assertEquals(100.0, px[4].position.y.toDouble(), 1e-3)

        p.map(t = 0.0) { x, y, _ -> vec(x / 100.0, y / 100.0, 0.0) }
        assertEquals(1.0, px[4].color.y.toDouble(), 1e-6)

        p.mapAlong(t = 0.0) { u, _ -> vec(u, 0.0, 0.0) }
        assertEquals(0.5, px[2].color.x.toDouble(), 1e-6)

        // 2x1 image: left pixel red, right pixel blue
        val img = Img(2, 1, 3, floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f))
        p.mapImage(img)
        assertEquals(1.0, px[0].color.x.toDouble(), 1e-6)   // x = 0   -> left
        assertEquals(1.0, px[4].color.z.toDouble(), 1e-6)   // x = 100 -> right
    }

    @Test
    fun visualiserDrawsOneDiscAndRingPerFixtureAndRasterises() {
        val p = Patch()
        p.strip(10, vec(0.0, 0.0), vec(90.0, 0.0))
        p.mapAlong(t = 0.0) { u, _ -> vec(u, 1 - u, 0.5) }
        val lines = p.toFLines()
        assertEquals(1 + 2 * 10, lines.size)
        val out = File.createTempFile("dmx-patch", ".png")
        try {
            renderLinesPng(lines, out, 320, 80)
            assertTrue(out.length() > 100)
        } finally {
            out.delete()
        }
        assertEquals(1 + 170, universeToFLines(ByteArray(512), rgb = true).size)
        assertEquals(1 + 512, universeToFLines(ByteArray(512)).size)
        // An RGBW universe read back as fixtures: white adds to every primary.
        val rgbw = ByteArray(512).also { it[4] = 255.toByte(); it[7] = 128.toByte() } // fixture 2: R = 1, W = 0.5
        val cells = universeToFLines(rgbw, profile = FixtureProfile.RGBW)
        assertEquals(1 + 128, cells.size)
        val c = cells[2].color
        assertEquals(1.0, c.x.toDouble(), 1e-6)
        assertEquals(128 / 255.0, c.y.toDouble(), 1e-6)
        assertEquals(128 / 255.0, c.z.toDouble(), 1e-6)
    }

    // ---- send policy ------------------------------------------------------------------------

    @Test
    fun sendsOnChangeThenThreeRepeatsThenKeepAliveOnly() {
        val out = DmxOutput(DmxProtocol.ARTNET, host = "127.0.0.1", port = freePort(), autoStart = false, keepAliveMs = 1000)
        try {
            out.universe(1)[1] = 10
            val ms = 1_000_000L
            assertEquals(1, out.tick(0))            // changed
            assertEquals(1, out.tick(23 * ms))      // repeat 1
            assertEquals(1, out.tick(46 * ms))      // repeat 2
            assertEquals(0, out.tick(69 * ms))      // suppressed
            assertEquals(0, out.tick(900 * ms))
            assertEquals(1, out.tick(1047 * ms))    // keep-alive, 1 s after the last send
            out.universe(1)[1] = 11
            assertEquals(1, out.tick(1070 * ms))    // changed again
            assertEquals(5, out.packetsSent)
        } finally {
            out.close()
        }
    }

    @Test
    fun animateRunsOnTheSenderTickAndAReRunReplacesIt() {
        val out = DmxOutput(DmxProtocol.ARTNET, host = "127.0.0.1", port = freePort(), autoStart = false)
        try {
            val p = Patch()
            p.strip(2, vec(0.0, 0.0), vec(1.0, 0.0))
            var first = 0
            p.animate(out) { t ->
                first++
                assertTrue(t >= 0.0)
                mapAlong(t) { u, _ -> vec(u, 0.0, 0.0) }
            }
            out.tick(System.nanoTime())
            assertEquals(1, first)
            assertEquals(0, out.universe(1)[1], "pixel 1 red, u = 0")
            assertEquals(255, out.universe(1)[4], "pixel 2 red, u = 1")
            var second = 0
            p.animate(out) { second++ }
            out.tick(System.nanoTime())
            assertEquals(1, first, "the replaced animation no longer runs")
            assertEquals(1, second)
        } finally {
            out.close()
        }
    }

    @Test
    fun showPatchAndShowUniverseDisplayIntoAnOpenChannel() {
        val box = Box("dmx-show")
        val p = Patch()
        p.strip(4, vec(0.0, 0.0), vec(30.0, 0.0))
        box.showPatch(p)
        val input = DmxInput(DmxProtocol.ARTNET, port = freePort(), bindAddress = "127.0.0.1")
        try {
            box.showUniverse(input, 1, profile = null)
        } finally {
            input.close()
        }
    }

    // ---- loopback ---------------------------------------------------------------------------

    @Test
    fun artNetLoopbackDeliversAUniverseAndAnswersArtPoll() {
        val port = freePort()
        val input = DmxInput(DmxProtocol.ARTNET, port = port, bindAddress = "127.0.0.1")
        val out = DmxOutput(DmxProtocol.ARTNET, host = "127.0.0.1", port = port, autoStart = false)
        try {
            val patch = Patch(startUniverse = 1)
            patch.strip(200, vec(0.0, 0.0), vec(1.0, 0.0))
            patch.mapAlong(t = 0.0) { u, _ -> vec(1.0, u, 0.0) }
            patch.render(out)
            out.flush()
            assertTrue(waitFor { input.version(1) > 0 && input.version(2) > 0 }, "both universes should arrive")
            val u1 = input.universe(1)
            assertEquals(255, u1[0].toInt() and 0xff)
            assertEquals(0, u1[1].toInt() and 0xff)
            val u2 = input.universe(2)
            assertEquals(255, u2[3 * 29 + 1].toInt() and 0xff, "last pixel of the strip, green = u = 1")

            val replies = artPoll("127.0.0.1", port, timeoutMs = 500)
            assertEquals(1, replies.size, "the DmxInput should answer as a node")
            assertEquals("Field4", replies[0].shortName)
            assertTrue(input.pollsAnswered >= 1)
        } finally {
            out.close(); input.close()
        }
    }

    @Test
    fun sacnLoopbackMergesByPriorityThenHtpAndDropsTerminatedStreams() {
        val port = freePort()
        val input = DmxInput(DmxProtocol.SACN, port = port, bindAddress = "127.0.0.1")
        val a = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = port, sourceName = "A", cid = UUID.randomUUID(), autoStart = false)
        val b = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = port, sourceName = "B", cid = UUID.randomUUID(), autoStart = false)
        val hi = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = port, sourceName = "HI", priority = 150, cid = UUID.randomUUID(), autoStart = false)
        try {
            a.universe(7)[1] = 200; a.universe(7)[2] = 10
            b.universe(7)[1] = 50; b.universe(7)[2] = 90
            a.flush(); b.flush()
            assertTrue(waitFor { input.sources(7).size == 2 })
            assertEquals(listOf(200, 90), listOf(input.channel(7, 1), input.channel(7, 2)), "HTP between equal priorities")

            hi.universe(7)[1] = 1
            hi.flush()
            assertTrue(waitFor { input.sources(7).size == 3 && input.channel(7, 1) == 1 }, "priority 150 wins outright")
            assertEquals(0, input.channel(7, 2))

            hi.close() // three Stream_Terminated packets
            assertTrue(waitFor { input.sources(7).size == 2 }, "a terminated stream is dropped at once, not after 2.5 s")
            assertEquals(200, input.channel(7, 1))
            assertEquals(listOf("A", "B"), input.sources(7).map { it.name }.sorted())
            assertNotNull(input.discoveredSources.values.firstOrNull { it.sourceName == "A" }, "flush sends a discovery page")
        } finally {
            a.close(); b.close(); hi.close(); input.close()
        }
    }

    @Test
    fun sacnSyncHoldsDataUntilTheSyncPacket() {
        val port = freePort()
        val input = DmxInput(DmxProtocol.SACN, port = port, bindAddress = "127.0.0.1")
        val cid = Sacn.cidBytes(UUID.randomUUID())
        val buf = ByteArray(Sacn.DATA_MAX_PACKET)
        try {
            val lo = InetAddress.getLoopbackAddress()
            var n = Sacn.encodeData(buf, cid, "s", 3, byteArrayOf(42), 1, sequence = 1, syncAddress = 99)
            input.handle(buf, n, lo, 1)
            assertEquals(0, input.channel(3, 1), "held until sync")
            n = Sacn.encodeSync(buf, cid, 99, 1)
            input.handle(buf, n, lo, 1)
            assertEquals(42, input.channel(3, 1))
            n = Sacn.encodeData(buf, cid, "s", 3, byteArrayOf(7), 1, sequence = 1)
            input.handle(buf, n, lo, 1)
            assertEquals(42, input.channel(3, 1), "a duplicate sequence number is discarded")
            assertEquals(1, input.packetsDiscarded)
        } finally {
            input.close()
        }
    }

    @Test
    fun boxOwnedOutputSurvivesReRunAndClosesOnStop() {
        val box = Box("dmx-lifecycle")
        val port = freePort()
        val first = box.artnetOut(port = port)
        val again = box.artnetOut(port = port)
        assertSame(first, again, "a re-run with the same arguments keeps the socket")
        val input = box.artnetIn(port = freePort())
        assertFalse(first.isClosed)
        box.stopTrackedJobs()
        assertTrue(first.isClosed, "stopping the box releases the output")
        assertTrue(input.isClosed, "and the input")
        val reopened = box.artnetOut(port = port)
        assertFalse(reopened.isClosed)
        box.stopTrackedJobs()
        assertTrue(reopened.isClosed)
    }
}

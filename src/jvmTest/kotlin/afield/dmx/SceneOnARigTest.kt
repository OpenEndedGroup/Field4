package afield.dmx

import afield.rectsl.RectSLImager
import afield.rectsl.rectSLImage
import afield.stdlib.vec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chain `public/docs/chain_scene_to_light.md` publishes — a RectSL scene drawn on the CPU,
 * mapped onto a 16 × 8 RGB grid and four RGBWA washes, sent as sACN over loopback and received
 * back — with its measurements pinned. Ports are loopback and away from sACN's 5568, so a lighting
 * program on the same machine is neither disturbed nor heard.
 */
class SceneOnARigTest {

    private fun near(expected: Double, actual: Double, tol: Double, what: String) =
        assertTrue(abs(expected - actual) <= tol, "$what: expected $expected ± $tol, got $actual")

    @Test
    fun `a RectSL frame is the scene's colours as typed, or as light`() {
        val scene = RectSLImager(SceneOnARig.SCENE)
        assertEquals(4.0, scene.duration)
        assertEquals(128, scene.heightFor(256))
        val typed = scene.frame(0.0, 256, 128)
        assertEquals(3, typed.channels)
        // The top-right corner is background, 0x0c1024 as typed (the line starts at the left edge).
        near(0x0c / 255.0, typed.at(255, 0, 0).toDouble(), 1e-5, "background red")
        near(0x24 / 255.0, typed.at(255, 0, 2).toDouble(), 1e-5, "background blue")
        // The sun starts at x = -5 (wall units, +y up): column (−5 + 8) × 16 = 48, row 64.
        near(1.0, typed.at(48, 60, 0).toDouble(), 1e-5, "sun red at t = 0")
        near(0x7a / 255.0, typed.at(48, 60, 1).toDouble(), 1e-5, "sun green at t = 0")
        // ... and is at x = +5 at t = 2.
        near(1.0, scene.frame(2.0, 256, 128).at(208, 60, 0).toDouble(), 1e-5, "sun red at t = 2")
        // Linear is the same pixels through the sRGB decode.
        val light = rectSLImage(SceneOnARig.SCENE, 0.0, 256, 128, linear = true)
        near(Math.pow((0x7a / 255.0 + 0.055) / 1.055, 2.4), light.at(48, 60, 1).toDouble(), 1e-5, "sun green, linear")
    }

    @Test
    fun `decode reads back what writeInto wrote, to within what the wire carries`() {
        val bounds = mapOf(
            FixtureProfile.RGB to 0.5, FixtureProfile.GRB to 0.5, FixtureProfile.DRGB to 1.0,
            FixtureProfile.RGBW to 1.0, FixtureProfile.RGBWA to 1.5, FixtureProfile.RGB16 to 0.5 / 257,
        )
        val worst = SceneOnARig.decodeErrors()
        assertEquals(bounds.keys, worst.keys)
        for ((profile, bound) in bounds) {
            val w = worst.getValue(profile)
            assertTrue(w <= bound + 1e-6, "${profile.name}: worst $w levels, bound $bound")
        }
        // Measured 2026-09-28: every colour slot rounded once is within half a level, and white and
        // amber, added back into red, stack their roundings.
        assertTrue(worst.getValue(FixtureProfile.RGBWA) > 1.0, "RGBWA worst ${worst.getValue(FixtureProfile.RGBWA)}")
        assertTrue(worst.getValue(FixtureProfile.RGB) > 0.49, "RGB worst ${worst.getValue(FixtureProfile.RGB)}")
        // A lone dimmer is a grey of its level.
        val d = Fixture("d", FixtureProfile.DIMMER, 1, 1, vec(0.0, 0.0)).apply { rgb(1, 1, 1); dimmer = 0.5 }
        val data = ByteArray(512).also { d.writeInto(it) }
        near(128 / 255.0, d.decode(data).x.toDouble(), 1e-6, "dimmer")
    }

    @Test
    fun `the scene survives sACN over loopback byte for byte`() {
        val trip = SceneOnARig.roundTrip(port = 35681)
        assertEquals(9, trip.frames)
        assertEquals(132, trip.fixtures)
        assertEquals(0, trip.levelErrors, "$trip")
        assertTrue(trip.worstGridLevels <= 0.5 + 1e-6, "$trip")
        // RGBWA: red is red + white + amber, three roundings.
        assertTrue(trip.worstWashLevels <= 1.5 + 1e-6, "$trip")
        assertTrue(trip.packetsReceived >= 2L * trip.frames, "$trip received ${trip.packetsReceived}")
    }

    @Test
    fun `an independent decode of the datagrams agrees with the rig`() {
        // The packets taken off a bare socket and read at the offsets E1.31-2016 Table 4-1 gives,
        // not through Sacn.decodeData.
        val port = 35682
        val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)); soTimeout = 2000 }
        val rig = SceneOnARig.Rig()
        rig.show(RectSLImager(SceneOnARig.SCENE).frame(1.0, SceneOnARig.FRAME_W, SceneOnARig.FRAME_H))
        val out = DmxOutput(DmxProtocol.SACN, host = "127.0.0.1", port = port, autoStart = false, discovery = false)
        val byUniverse = HashMap<Int, ByteArray>()
        try {
            rig.patch.render(out)
            out.flush()
            val buf = ByteArray(1500)
            while (byUniverse.size < 2) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                fun u8(i: Int) = buf[i].toInt() and 0xff
                fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
                fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
                assertEquals("ASC-E1.17", String(buf, 4, 9, Charsets.US_ASCII))
                assertEquals(4, u32(18), "root vector VECTOR_ROOT_E131_DATA")
                assertEquals(2, u32(40), "framing vector VECTOR_E131_DATA_PACKET")
                assertEquals(2, u8(117), "DMP vector VECTOR_DMP_SET_PROPERTY")
                assertEquals(0, u8(125), "start code")
                val count = u16(123)
                assertEquals(513, count, "property value count")
                byUniverse[u16(113)] = buf.copyOfRange(126, 126 + count - 1)
            }
        } finally {
            out.close(); socket.close()
        }
        assertEquals(setOf(1, 2), byUniverse.keys)
        var worst = 0.0
        for (f in rig.patch.fixtures) {
            val back = f.decode(byUniverse.getValue(f.universe))
            worst = maxOf(worst, abs(back.x - f.color.x).toDouble(), abs(back.y - f.color.y).toDouble(), abs(back.z - f.color.z).toDouble())
        }
        assertTrue(worst * 255 <= 1.5 + 1e-6, "worst ${worst * 255} levels")
        // And drawn in the rig's layout from those bytes: a ground, then one disc per fixture
        // (no rings over 64 fixtures).
        assertEquals(1 + rig.patch.fixtures.size, rig.patch.receivedToFLines({ byUniverse.getValue(it) }).size)
    }

    @Test
    fun `point sampling a thin line flickers and area averaging does not`() {
        val (point, area) = SceneOnARig.aliasing()
        // Area: the line's light lands on the rig in the right amount, whichever column it is in.
        near(SceneOnARig.LINE_TRUTH, area.mean, 0.02, "area mean")
        assertTrue(area.flicker < 1e-3, "area flicker ${area.flicker}")
        assertEquals(0, area.darkFrames)
        // Point: most frames the line falls between the sample columns and the rig goes dark.
        assertTrue(point.darkFrames > point.total.size / 2, "point dark frames ${point.darkFrames} of ${point.total.size}")
        assertTrue(point.max > 3 * SceneOnARig.LINE_TRUTH, "point peak total ${point.max}")
        assertTrue(point.flicker > 3, "point flicker ${point.flicker}")
        // Averaged over the sweep, the strobe carries about the right light; it is when that is wrong.
        near(SceneOnARig.LINE_TRUTH, point.mean, 0.25, "point mean")
    }

    @Test
    fun `the scene keeps up with the sender clock`() {
        val timing = SceneOnARig.timing(port = 35683, seconds = 2.0)
        assertEquals(null, timing.lastError)
        assertTrue(timing.ticks > 40, "ticks ${timing.ticks}")
        assertTrue(timing.arrivalMillis.size > 40, "arrivals ${timing.arrivalMillis.size}")
        // 44 Hz is 22.7 ms; a loaded CI machine gets slack, not a pass for a stalled sender.
        assertTrue(timing.arrivalMedian in 15.0..40.0, "median gap ${timing.arrivalMedian} ms")
    }
}

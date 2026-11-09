package afield.io.resolve

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Resolve] end to end through a real `python -m fieldresolve` worker, with `FIELDRESOLVE_FAKE=1`
 * standing the fixture in `python/fieldresolve/_fake.py` in for a running Resolve. The expected
 * values come from that fixture's docstring. Timecode arithmetic and the Python side alone are
 * covered by `tools/test_fieldresolve.py`.
 */
class ResolveTest {
    @BeforeTest
    fun fake() { Resolve.close(); Resolve.workerEnvironment = mapOf("FIELDRESOLVE_FAKE" to "1") }

    @AfterTest
    fun real() { Resolve.close(); Resolve.workerEnvironment = emptyMap() }

    @Test
    fun `status names the project and the current timeline`() {
        val s = Resolve.status()
        assertEquals("Fake Project", s.project)
        assertEquals("Edit 1", s.timeline)
        assertEquals(2, s.timelineCount)
        assertTrue(Resolve.available)
        assertEquals("field-resolve-worker.log", Resolve.logFile().name)
    }

    @Test
    fun `markers of the current timeline carry frame, timecode, color and note`() {
        val ms = Resolve.markers()
        assertEquals(listOf("Marker 1", "Marker 2"), ms.map { it.name })
        val red = ms[1]
        assertEquals(48, red.frame)
        assertEquals(86448, red.recordFrame)
        assertEquals("01:00:02:00", red.timecode)
        assertEquals(2.0, red.seconds, 1e-9)
        assertEquals("Red", red.color)
        assertEquals("cut here", red.note)
        assertEquals("x", ms[0].customData)
    }

    @Test
    fun `a timeline by name uses its own rate`() {
        val df = Resolve.timeline("Edit DF")
        assertEquals(false, df.current)
        assertEquals(true, df.dropFrame)
        assertEquals(29.97, df.fps, 1e-9)
        assertEquals(3600, df.length)
        assertEquals("01:01:00;02", Resolve.markers("Edit DF").single().timecode)
        assertEquals(listOf("Edit 1", "Edit DF"), Resolve.timelines().map { it.name })
    }

    @Test
    fun `markersAt follows duration and markersColored ignores case`() {
        val t = Resolve.timeline()
        assertEquals(listOf("Marker 1"), t.markersAt(23).map { it.name })
        assertEquals(emptyList(), t.markersAt(24).map { it.name })
        assertEquals(listOf("Marker 2"), t.markersAt(48).map { it.name })
        assertEquals(listOf("Marker 2"), t.markersColored("red").map { it.name })
    }

    @Test
    fun `clipMarkers places a clip's marker on its timeline and drops trimmed ones`() {
        val m = Resolve.clipMarkers().single()
        assertEquals("A001.mov", m.clip)
        assertEquals(110, m.frame)
        assertEquals(1010, m.clipFrame)
        assertEquals("video", m.trackType)
        assertEquals(1, m.track)
    }

    @Test
    fun `playhead is counted from the timeline start`() {
        val p = Resolve.playhead()
        assertEquals("Edit 1", p.timeline)
        assertEquals(24, p.frame)
        assertEquals(86424, p.recordFrame)
    }

    @Test
    fun `on(host) asks that machine's Resolve through the same worker`() {
        val other = Resolve.on("macbook-pro-2")
        assertEquals("Fake Project on macbook-pro-2", other.status().project)
        assertEquals("macbook-pro-2", other.status().host)
        assertEquals(listOf("Marker 1", "Marker 2"), other.markers().map { it.name })
        assertEquals("Fake Project", Resolve.status().project)
        assertEquals(null, Resolve.host)
    }

    @Test
    fun `a missing timeline lists the ones there, and the worker keeps going`() {
        val e = assertFailsWith<NoSuchElementException> { Resolve.markers("Nope") }
        assertTrue("Edit DF" in e.message.orEmpty(), e.message)
        assertEquals(2, Resolve.markers().size)
    }
}

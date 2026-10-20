package afield.app.canvas

import afield.tools.docs.canvasFlightArrivalFrame
import afield.tools.docs.canvasFlightFrameCount
import afield.tools.docs.canvasFlightFrameTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The canvas flight's arithmetic ([CanvasFlightTimeline], [flightView]) and the recorder's frame
 * clock. The flight in a browser, and the film, are the `canvas-flight` UI regression.
 */
class CanvasFlightTimelineTest {
    private fun spec(vararg stops: CanvasFlightStop, leg: Double = 6.0, hold: Double = 2.0) =
        CanvasFlightSpec(stops.toList(), legSeconds = leg, holdSeconds = hold)

    private val views = listOf(
        CanvasFlightView(0.0, 0.0, 0.25),
        CanvasFlightView(1000.0, 400.0, 1.0),
        CanvasFlightView(1200.0, 900.0, 2.0)
    )

    @Test
    fun opensWithAHoldThenFliesALegAndHoldsAtEachStop() {
        val t = CanvasFlightTimeline(spec(CanvasFlightStop("a"), CanvasFlightStop("b"), CanvasFlightStop("c")))
        // On the first stop at once, held 2 s; then 6 s to each next stop and 2 s there.
        assertEquals(listOf(0.0, 8.0, 16.0), t.arrivals)
        assertEquals(18.0, t.duration, 0.0)
    }

    @Test
    fun aStopCanSetItsOwnLegAndHold() {
        val t = CanvasFlightTimeline(spec(
            CanvasFlightStop("a", legSeconds = 99.0, holdSeconds = 1.0),
            CanvasFlightStop("b", legSeconds = 3.0),
            CanvasFlightStop("c", holdSeconds = 0.0)
        ))
        // The first stop's leg is ignored: the flight starts there.
        assertEquals(listOf(0.0, 4.0, 12.0), t.arrivals)
        assertEquals(12.0, t.duration, 0.0)
    }

    @Test
    fun theCameraStandsExactlyOnEachStopFromItsArrivalUntilItsHoldEnds() {
        val t = CanvasFlightTimeline(spec(CanvasFlightStop("a"), CanvasFlightStop("b"), CanvasFlightStop("c")))
        for ((i, arrival) in t.arrivals.withIndex()) {
            for (dt in listOf(0.0, 0.5, 1.99)) assertEquals(views[i], t.at(arrival + dt, views))
        }
        assertEquals(views[0], t.at(-5.0, views))
        assertEquals(views[2], t.at(t.duration + 5.0, views))
    }

    @Test
    fun zoomMovesInLogSpaceAndTheCentreInAStraightWorldLine() {
        val t = CanvasFlightTimeline(spec(CanvasFlightStop("a"), CanvasFlightStop("b"), CanvasFlightStop("c")))
        // Halfway through the first leg the ease is at one half.
        val mid = t.at(2.0 + 3.0, views)
        assertEquals(sqrt(0.25 * 1.0), mid.zoom, 1e-12)
        assertEquals(500.0, mid.x, 1e-9)
        assertEquals(200.0, mid.y, 1e-9)
        // Every point of the leg is on the segment between the stops' centres, in order.
        var last = -1.0
        for (k in 0..120) {
            val v = t.at(2.0 + 6.0 * k / 120.0, views)
            assertEquals("on the line at $k", 0.4 * v.x, v.y, 1e-9)
            assertTrue("forward at $k", v.x >= last)
            last = v.x
            // log zoom is the same fraction of the way as the centre
            val f = v.x / 1000.0
            assertEquals(kotlin.math.ln(0.25) + f * (kotlin.math.ln(1.0) - kotlin.math.ln(0.25)), kotlin.math.ln(v.zoom), 1e-9)
        }
    }

    @Test
    fun aLegBetweenFarApartStopsPullsBackSoBothStayInSight() {
        val t = CanvasFlightTimeline(spec(CanvasFlightStop("a"), CanvasFlightStop("b"), CanvasFlightStop("c")))
        // Two close-ups 1200 world px apart in a 1280x720 window: flown straight at log-space zoom the
        // middle of the leg shows 640x360 world px of empty canvas between them.
        val far = listOf(CanvasFlightView(0.0, 0.0, 2.0), CanvasFlightView(1200.0, 0.0, 2.0), CanvasFlightView(1200.0, 0.0, 2.0))
        val mid = t.at(5.0, far, 1280.0, 720.0)
        assertEquals(600.0, mid.x, 1e-9)
        // Halfway the middle half of each stop's view is in the window: 600 + 1280/8 world px each side.
        assertEquals(1280.0 / 2.0 / (600.0 + 1280.0 / 8.0), mid.zoom, 1e-9)
        // The stops themselves are untouched, and the leg is smooth and never zooms out past its middle.
        assertEquals(far[0], t.at(2.0, far, 1280.0, 720.0))
        assertEquals(far[1], t.at(8.0, far, 1280.0, 720.0))
        val zooms = (0..120).map { t.at(2.0 + 6.0 * it / 120.0, far, 1280.0, 720.0).zoom }
        assertTrue(zooms.all { it >= mid.zoom - 1e-12 })
        // Without the pane's size, or with pullBack off, the leg is the plain log-space zoom.
        assertEquals(2.0, t.at(5.0, far).zoom, 1e-12)
        val straight = CanvasFlightTimeline(spec(CanvasFlightStop("a"), CanvasFlightStop("b"), CanvasFlightStop("c")).copy(pullBack = false))
        assertEquals(2.0, straight.at(5.0, far, 1280.0, 720.0).zoom, 1e-12)
        // Stops that already share the window do not pull back.
        assertEquals(0.0, canvasFlightPullBack(views[0], views[1], 1280.0, 720.0), 0.0)
    }

    @Test
    fun theEaseIsStillAtBothEndsAndSymmetric() {
        assertEquals(0.0, canvasFlightEase(0.0), 0.0)
        assertEquals(1.0, canvasFlightEase(1.0), 1e-15)
        assertEquals(0.5, canvasFlightEase(0.5), 1e-15)
        val h = 1e-6
        assertTrue(canvasFlightEase(h) / h < 1e-5)
        assertTrue((1.0 - canvasFlightEase(1.0 - h)) / h < 1e-5)
        for (k in 0..100) {
            val u = k / 100.0
            assertEquals(1.0, canvasFlightEase(u) + canvasFlightEase(1.0 - u), 1e-12)
        }
    }

    @Test
    fun aBookmarkInTheWindowItWasSavedInRestoresItsOwnTranslation() {
        val saved = ScrollPosition(x = -340.0, y = 125.0, zoom = 1.6, viewX = 0.0, viewY = 0.0, viewW = 1280.0, viewH = 720.0)
        val v = saved.flightView(0.0, 0.0, 1280.0, 720.0, currentZoom = 1.0)
        val (tx, ty) = v.translation(0.0, 0.0, 1280.0, 720.0)
        assertEquals(-340.0, tx, 1e-9)
        assertEquals(125.0, ty, 1e-9)
        assertEquals(1.6, v.zoom, 0.0)
        // A bookmark from before saved sizes is taken as saved in this window.
        val old = ScrollPosition(x = -340.0, y = 125.0, zoom = 1.6)
        val (ox, oy) = old.flightView(0.0, 0.0, 900.0, 500.0, currentZoom = 1.0).translation(0.0, 0.0, 900.0, 500.0)
        assertEquals(-340.0, ox, 1e-9)
        assertEquals(125.0, oy, 1e-9)
        // And one from before zoom was saved keeps the current zoom.
        assertEquals(0.7, ScrollPosition(x = 0.0, y = 0.0).flightView(0.0, 0.0, 100.0, 100.0, 0.7).zoom, 0.0)
    }

    @Test
    fun aBookmarkFromAnotherWindowShowsTheSamePartOfTheCanvas() {
        val saved = ScrollPosition(x = 100.0, y = 50.0, zoom = 1.0, viewX = 0.0, viewY = 0.0, viewW = 1600.0, viewH = 1000.0)
        val here = saved.flightView(0.0, 0.0, 1280.0, 720.0, currentZoom = 1.0)
        val there = saved.flightView(0.0, 0.0, 1600.0, 1000.0, currentZoom = 1.0)
        // The same world point at the centre, zoomed so the saved 1600x1000 fits in 1280x720.
        assertEquals(there.x, here.x, 1e-9)
        assertEquals(there.y, here.y, 1e-9)
        assertEquals(0.72, here.zoom, 1e-12)
        // The saved window's world rectangle is inside the one shown here.
        val shownW = 1280.0 / here.zoom
        val shownH = 720.0 / here.zoom
        assertTrue(shownW >= 1600.0 - 1e-9 && shownH >= 1000.0 - 1e-9)
    }

    @Test
    fun stopsNameBookmarksByNameSlotOrId() {
        fun b(slot: Int, name: String) = Bookmark("bookmark_$slot", name, 0.0, ScrollPosition(0.0, 0.0))
        val data = BookmarkData(bookmarks = listOf(b(3, "Plan"), b(1, "overview"), b(7, "Bookmark 7")))
        assertEquals("bookmark_1", data.find(" Overview ")?.id)
        assertEquals("bookmark_3", data.find("3")?.id)
        assertEquals("bookmark_3", data.find("F3")?.id)
        assertEquals("bookmark_7", data.find("bookmark_7")?.id)
        assertNull(data.find("close-ups"))
        assertEquals(listOf(1, 3, 7), data.inSlotOrder().map { it.slot })
        assertTrue(data.describeMissing("close-ups").contains("\"overview\" (F1)"))
        assertTrue(BookmarkData(bookmarks = emptyList()).describeMissing("x").contains("no bookmarks"))
    }

    @Test
    fun specsOutsideTheirRangesSayWhy() {
        assertNull(spec(CanvasFlightStop("a")).problem())
        assertNotNull(CanvasFlightSpec(emptyList()).problem())
        assertNotNull(spec(CanvasFlightStop("a"), leg = 0.0).problem())
        assertNotNull(spec(CanvasFlightStop("a"), hold = -1.0).problem())
        assertNotNull(spec(CanvasFlightStop("a"), CanvasFlightStop("b", legSeconds = Double.NaN)).problem())
        assertNotNull(CanvasFlightSpec(listOf(CanvasFlightStop("a")), orbitDegreesPerSecond = 400.0).problem())
        assertNotNull(spec(*Array(20) { CanvasFlightStop("s$it") }, leg = 300.0).problem())
    }

    @Test
    fun aFilmHasAFramePerTickWithTheLastOnTheEnd() {
        assertEquals(600, canvasFlightFrameCount(20.0, 30))
        assertEquals(45, canvasFlightFrameCount(4.5, 10))
        assertEquals(1, canvasFlightFrameCount(0.0, 30))
        assertEquals(0.0, canvasFlightFrameTime(0, 600, 30, 20.0), 0.0)
        assertEquals(1.0, canvasFlightFrameTime(30, 600, 30, 20.0), 1e-12)
        assertEquals(20.0, canvasFlightFrameTime(599, 600, 30, 20.0), 0.0)
        // Every step but the last is exactly 1/fps.
        val steps = (1 until 600).map { canvasFlightFrameTime(it, 600, 30, 20.0) - canvasFlightFrameTime(it - 1, 600, 30, 20.0) }
        assertTrue(steps.dropLast(1).all { abs(it - 1.0 / 30) < 1e-9 })
        // The frame that first shows a stop is the first at or after its arrival.
        assertEquals(60, canvasFlightArrivalFrame(2.0, 600, 30))
        assertEquals(61, canvasFlightArrivalFrame(2.01, 600, 30))
        assertEquals(599, canvasFlightArrivalFrame(20.0, 600, 30))
        assertEquals(0, canvasFlightArrivalFrame(0.0, 600, 30))
    }
}

package afield.stdlib

import afield.Box
import java.util.concurrent.CancellationException
import kotlin.math.sin
import kotlin.test.*

class SignalViewerTest {
    @Test fun overviewPreservesSampledExtremaAndZoomEvaluatesNewDetail() {
        SignalViewer(listOf(SignalChannel("x", { t -> .5 + .5 * sin(t * 2 * Math.PI * .2) },
            comparison = { .25 })), 0.0, 3600.0).use { viewer ->
            val wide = viewer.window(0.0, 3600.0, 1000)
            assertEquals("envelope", wide.mode)
            assertTrue(wide.lanes.single().current.low.min() < .0001)
            assertTrue(wide.lanes.single().current.high.max() > .9999)
            assertTrue(wide.lanes.single().comparison!!.low.all { it == .25 })
            val zoom = viewer.window(1.2, 1.3, 1000)
            assertEquals("waveform", zoom.mode)
            assertTrue(zoom.sampleSpacing < .0001)
            assertEquals(2001, zoom.lanes.single().current.low.size)
            assertEquals(.5 + .5 * sin(1.2 * 2 * Math.PI * .2), zoom.lanes.single().current.low.first())
            assertEquals(.5 + .5 * sin(1.3 * 2 * Math.PI * .2), zoom.lanes.single().current.low.last())
        }
    }

    @Test fun queryBudgetCancellationAndInvalidValues() {
        var calls = 0
        SignalViewer(listOf(SignalChannel("x", { calls++; .5 })), -100.0, 1e8, 1e-8).use { viewer ->
            val window = viewer.window(-100.0, 1e8, 16)
            assertEquals(500001, calls)
            assertEquals(16, window.lanes.single().current.low.size)
            assertTrue(window.sampleSpacing > 100)
            assertFailsWith<IllegalArgumentException> { viewer.window(Double.NaN, 1.0, 16) }
            assertFailsWith<IllegalArgumentException> { viewer.window(2.0, 1.0, 16) }
            assertFailsWith<IllegalArgumentException> { viewer.window(-101.0, 1.0, 16) }
            assertFailsWith<IllegalArgumentException> { viewer.window(0.0, 1.0, 5000) }
            assertFailsWith<CancellationException> { viewer.window(0.0, 1.0, 16) { throw CancellationException() } }
        }
        SignalViewer(listOf(SignalChannel("bad", { Double.NaN })), 0.0, 1.0).use { viewer ->
            assertFailsWith<IllegalArgumentException> { viewer.window(0.0, 1.0, 16) }
        }
    }

    @Test fun replacingOutputReleasesSourceAndEscapesMarkup() {
        val box = Box("signal-viewer-test")
        box.output.displayProxy = { _, _ -> }
        val first = SignalViewer(listOf(SignalChannel("x", { .5 })), 0.0, 1.0)
        val second = SignalViewer(listOf(SignalChannel("</script><img>", { .5 })), 0.0, 1.0)
        box.displaySignal(first)
        assertSame(first, SignalViewer.find(first.id))
        val html = box.displaySignal(second, options = SignalViewerOptions(label = "</script><img>"))
        assertNull(SignalViewer.find(first.id))
        assertSame(second, SignalViewer.find(second.id))
        assertFalse(html.contains("</script><img>"))
        second.close()
        assertNull(SignalViewer.find(second.id))
    }
}

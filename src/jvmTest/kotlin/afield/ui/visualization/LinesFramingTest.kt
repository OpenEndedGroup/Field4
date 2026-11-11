package afield.ui.visualization

import afield.document.graph.Box
import afield.document.graph.stopTrackedJobs
import afield.geometry.path.FLine
import afield.graphics.render.fline
import kotlin.test.*

class LinesFramingTest {
    private val shown = mutableListOf<String>()
    private fun box() = Box("framing-${System.nanoTime()}").also { it.output.displayProxy = { _, html -> shown += html.toString() } }

    /** A horizontal stroke from 0 to [right], at height 50: the moving edge of an animation. */
    private fun stroke(right: Double): FLine = fline { moveTo(0.0, 50.0); lineTo(right, 50.0); lineTo(right, 60.0) }

    /** The width the viewer is told to fit, from the wrapper's `data-afield-bounds-w`. */
    private fun fittedWidth(): Double =
        Regex("data-afield-bounds-w=\"([0-9.eE+-]+)\"").find(shown.last())!!.groupValues[1].toDouble()

    private fun <T> animating(value: Boolean, block: () -> T): T {
        val previous = linesDisplayIsAnimation
        linesDisplayIsAnimation = { value }
        try { return block() } finally { linesDisplayIsAnimation = previous }
    }

    @Test fun anAnimationKeepsItsFirstFramingWhileAStaticDisplayFitsItself() {
        val b = box()
        animating(true) {
            b.lines(stroke(100.0), channel = "wave", w = 640.0, h = 300.0)
            assertEquals(100.0, fittedWidth())
            b.lines(stroke(180.0), channel = "wave", w = 640.0, h = 300.0)
            assertEquals(100.0, fittedWidth(), "a frame of the animation keeps the first framing")
        }
        animating(false) {
            b.lines(stroke(180.0), channel = "wave", w = 640.0, h = 300.0)
            assertEquals(180.0, fittedWidth(), "a slider redraw (off the Runloop) fits itself, as before")
        }
        animating(true) {
            b.lines(stroke(40.0), channel = "wave", w = 640.0, h = 300.0)
            assertEquals(180.0, fittedWidth(), "the animation holds the most recent fitted framing")
            b.lines(stroke(40.0), channel = "other", w = 640.0, h = 300.0)
            assertEquals(40.0, fittedWidth(), "each channel has its own framing")
        }
    }

    @Test fun stoppingTheBoxReleasesAHeldFraming() {
        val b = box()
        animating(true) {
            b.lines(stroke(100.0), channel = "wave", w = 640.0, h = 300.0)
            b.stopTrackedJobs()
            b.lines(stroke(150.0), channel = "wave", w = 640.0, h = 300.0)
            assertEquals(150.0, fittedWidth(), "a relaunch (or Reset: figures) frames afresh")
        }
    }

    @Test fun fitHoldAndFixedAreExplicit() {
        val b = box()
        animating(true) {
            b.lines(stroke(100.0), channel = "a", w = 640.0, h = 300.0, framing = LinesFraming.Fit)
            b.lines(stroke(170.0), channel = "a", w = 640.0, h = 300.0, framing = LinesFraming.Fit)
            assertEquals(170.0, fittedWidth())
            b.lines(stroke(10.0), channel = "f", w = 640.0, h = 300.0, framing = LinesFraming.fixed(0.0, 0.0, 640.0, 300.0))
            assertEquals(640.0, fittedWidth())
        }
        animating(false) {
            b.lines(stroke(100.0), channel = "h", w = 640.0, h = 300.0, framing = LinesFraming.Hold)
            b.lines(stroke(300.0), channel = "h", w = 640.0, h = 300.0, framing = LinesFraming.Hold)
            assertEquals(100.0, fittedWidth(), "Hold keeps the first framing even for a slider redraw")
        }
    }

    @Test fun aHeldFramingAlsoHoldsAFitToSizeScale() {
        val b = box()
        animating(true) {
            b.lines(stroke(100.0), channel = "s", w = 640.0, h = 300.0, fitToSize = true)
            val first = fittedWidth()
            b.lines(stroke(200.0), channel = "s", w = 640.0, h = 300.0, fitToSize = true)
            assertEquals(first, fittedWidth(), 1e-6)
        }
    }
}

package afield.ui.output

import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.slider
import afield.document.graph.Box
import afield.ui.visualization.menu
import afield.ui.visualization.multiSlider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `tooltip` every control takes: on the markup as `data-tooltip`, and on the control record. */
class ControlTooltipTest {

    private class Shown(val box: Box) {
        val html = mutableListOf<String>()
    }

    private fun newBox(): Shown {
        val box = Box("tooltips-${System.nanoTime()}")
        val shown = Shown(box)
        box.output.displayProxy = { _, message -> shown.html += message.toString() }
        return shown
    }

    private fun live(box: Box) = controllerControlsForBox(box.id).associateBy { it.label }

    @Test
    fun markdownHelpIsEscapedOnTheLabel() {
        val html = withControlHover("""<div class="field-control"><span class="field-label">a</span></div>""", "**Gain** & <trim>")
        assertTrue(html.contains("data-hover-text=\"**Gain** &amp; &lt;trim&gt;\""), html)
        assertTrue(html.contains("&lt;strong&gt;Gain&lt;/strong&gt;"), html)
        assertEquals(1, Regex("data-hover-text=").findAll(html).count())
    }

    @Test
    fun noHelpLeavesTheMarkupUnchanged() {
        val original = """<div class="field-control"><span class="field-label">a</span></div>"""
        assertEquals(original, withControlHover(original, null))
        assertEquals(original, withControlHover(original, "   "))
    }

    @Test
    fun everyGroupControlCarriesItsTooltipToMarkupAndRecord() {
        val shown = newBox()
        shown.box.controlGroup("look") {
            slider("gain", hoverText = "Output level before the limiter") { }
            checkbox("mute", hoverText = "Silence without stopping") { }
            text("title", hoverText = "Shown in the window bar") { }
            colorPicker("tint", hoverText = "Multiplies the base colour") { }
            vec2Control("offset", hoverText = "Where the centre sits") { }
            button("gate", hoverText = "Held: the voice sounds") { }
            meter("level", hoverText = "RMS of the last block")
            multiSlider("steps", count = 4, hoverText = "Per-step velocity") { }
            menu("wave", listOf("sine", "saw"), hoverText = "Oscillator shape") { _, _ -> }
            slider("plain") { }
        }
        val controls = live(shown.box)
        assertEquals("Output level before the limiter", controls.getValue("gain").hoverText)
        assertEquals("Silence without stopping", controls.getValue("mute").hoverText)
        assertEquals("Shown in the window bar", controls.getValue("title").hoverText)
        assertEquals("Multiplies the base colour", controls.getValue("tint").hoverText)
        assertEquals("Where the centre sits", controls.getValue("offset").hoverText)
        assertEquals("Held: the voice sounds", controls.getValue("gate").hoverText)
        assertEquals("RMS of the last block", controls.getValue("level").hoverText)
        assertEquals("Per-step velocity", controls.getValue("steps").hoverText)
        assertEquals("Oscillator shape", controls.getValue("wave").hoverText)
        assertNull(controls.getValue("plain").hoverText)

        val html = shown.html.single()
        for (text in controls.values.mapNotNull { it.hoverText }) {
            assertTrue(html.contains("data-hover-text=\"$text\""), "markup carries '$text'")
        }
        assertEquals(9, Regex("data-hover-text=").findAll(html).count(), "one per control that has one, none for 'plain'")
    }

    @Test
    fun aTooltipBlockCoversItsControlsAndAnOwnTooltipWins() {
        val shown = newBox()
        shown.box.controlGroup("flock") {
            tooltip("Neighbourhood radius") {
                slider("align") { }
                slider("cohere", hoverText = "Pull towards the centre") { }
            }
            slider("speed") { }
        }
        val controls = live(shown.box)
        assertEquals("Neighbourhood radius", controls.getValue("align").hoverText)
        assertEquals("Pull towards the centre", controls.getValue("cohere").hoverText)
        assertNull(controls.getValue("speed").hoverText, "the block ends where it ends")
    }

    @Test
    fun aRerunDoesNotKeepAnOldTooltip() {
        val shown = newBox()
        shown.box.controlGroup("c") { slider("gain", hoverText = "first") { } }
        assertEquals("first", live(shown.box).getValue("gain").hoverText)
        shown.box.controlGroup("c") { slider("gain") { } }
        assertNull(live(shown.box).getValue("gain").hoverText, "the tooltip comes from the code, not the value cache")
    }

    @Test
    fun standaloneControlsTakeOneToo() {
        val shown = newBox()
        shown.box.slider("gain", hoverText = "alone") { }
        shown.box.checkbox("on", hoverText = "also alone") { }
        shown.box.syncToProperty("speed", 0.5, 0.0, 2.0, hoverText = "persisted")
        assertTrue(shown.html[0].contains("data-hover-text=\"alone\""))
        assertTrue(shown.html[1].contains("data-hover-text=\"also alone\""))
        assertTrue(shown.html[2].contains("data-hover-text=\"persisted\""))
    }

    @Test
    fun aKeyframedControlCarriesItsTooltip() {
        val shown = newBox()
        Box.context.set(shown.box)
        try {
            val keys = KeyframeScope()
            @Suppress("UNUSED_VARIABLE")
            val size by keys.slider(0.4, 0.1..1.0, hoverText = "Particle radius")
            shown.box.controlGroup("look") { keys.controls() }
        } finally {
            Box.context.remove()
        }
        assertEquals("Particle radius", live(shown.box).getValue("size").hoverText)
        assertFalse(shown.html.single().contains("data-hover-text=\"\""))
    }
}

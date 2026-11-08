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
    fun theAttributeGoesOnTheOutermostControlAndIsEscaped() {
        val html = withControlTooltip("""<div class="field-control field-slider" data-x="1"><span class="field-label">a</span></div>""",
            "Gain <dB> & \"trim\"\nsecond line")
        assertTrue(html.startsWith("""<div data-tooltip="Gain &lt;dB&gt; &amp; &quot;trim&quot;&#10;second line" """), html)
        assertTrue(html.contains("""class="field-control field-slider" data-x="1">"""), html)
        assertEquals(1, Regex("data-tooltip=").findAll(html).count())
    }

    @Test
    fun noTooltipLeavesTheMarkupByteForByte() {
        val original = """<div class="field-control field-checkbox">x</div>"""
        assertEquals(original, withControlTooltip(original, null))
        assertEquals(original, withControlTooltip(original, "   "))
    }

    @Test
    fun markupWithoutAControlRootIsWrapped() {
        val html = withControlTooltip("<span>odd</span>", "why")
        assertEquals("""<div class="field-tooltip-host" data-tooltip="why" aria-description="why"><span>odd</span></div>""", html)
    }

    @Test
    fun everyGroupControlCarriesItsTooltipToMarkupAndRecord() {
        val shown = newBox()
        shown.box.controlGroup("look") {
            slider("gain", tooltip = "Output level before the limiter") { }
            checkbox("mute", tooltip = "Silence without stopping") { }
            text("title", tooltip = "Shown in the window bar") { }
            colorPicker("tint", tooltip = "Multiplies the base colour") { }
            vec2Control("offset", tooltip = "Where the centre sits") { }
            button("gate", tooltip = "Held: the voice sounds") { }
            meter("level", tooltip = "RMS of the last block")
            multiSlider("steps", count = 4, tooltip = "Per-step velocity") { }
            menu("wave", listOf("sine", "saw"), tooltip = "Oscillator shape") { _, _ -> }
            slider("plain") { }
        }
        val controls = live(shown.box)
        assertEquals("Output level before the limiter", controls.getValue("gain").tooltip)
        assertEquals("Silence without stopping", controls.getValue("mute").tooltip)
        assertEquals("Shown in the window bar", controls.getValue("title").tooltip)
        assertEquals("Multiplies the base colour", controls.getValue("tint").tooltip)
        assertEquals("Where the centre sits", controls.getValue("offset").tooltip)
        assertEquals("Held: the voice sounds", controls.getValue("gate").tooltip)
        assertEquals("RMS of the last block", controls.getValue("level").tooltip)
        assertEquals("Per-step velocity", controls.getValue("steps").tooltip)
        assertEquals("Oscillator shape", controls.getValue("wave").tooltip)
        assertNull(controls.getValue("plain").tooltip)

        val html = shown.html.single()
        for (text in controls.values.mapNotNull { it.tooltip }) {
            assertTrue(html.contains("data-tooltip=\"$text\""), "markup carries '$text'")
        }
        assertEquals(9, Regex("data-tooltip=").findAll(html).count(), "one per control that has one, none for 'plain'")
    }

    @Test
    fun aTooltipBlockCoversItsControlsAndAnOwnTooltipWins() {
        val shown = newBox()
        shown.box.controlGroup("flock") {
            tooltip("Neighbourhood radius") {
                slider("align") { }
                slider("cohere", tooltip = "Pull towards the centre") { }
            }
            slider("speed") { }
        }
        val controls = live(shown.box)
        assertEquals("Neighbourhood radius", controls.getValue("align").tooltip)
        assertEquals("Pull towards the centre", controls.getValue("cohere").tooltip)
        assertNull(controls.getValue("speed").tooltip, "the block ends where it ends")
    }

    @Test
    fun aRerunDoesNotKeepAnOldTooltip() {
        val shown = newBox()
        shown.box.controlGroup("c") { slider("gain", tooltip = "first") { } }
        assertEquals("first", live(shown.box).getValue("gain").tooltip)
        shown.box.controlGroup("c") { slider("gain") { } }
        assertNull(live(shown.box).getValue("gain").tooltip, "the tooltip comes from the code, not the value cache")
    }

    @Test
    fun standaloneControlsTakeOneToo() {
        val shown = newBox()
        shown.box.slider("gain", tooltip = "alone") { }
        shown.box.checkbox("on", tooltip = "also alone") { }
        shown.box.syncToProperty("speed", 0.5, 0.0, 2.0, tooltip = "persisted")
        assertTrue(shown.html[0].contains("data-tooltip=\"alone\""))
        assertTrue(shown.html[1].contains("data-tooltip=\"also alone\""))
        assertTrue(shown.html[2].contains("data-tooltip=\"persisted\""))
    }

    @Test
    fun aKeyframedControlCarriesItsTooltip() {
        val shown = newBox()
        Box.context.set(shown.box)
        try {
            val keys = KeyframeScope()
            @Suppress("UNUSED_VARIABLE")
            val size by keys.slider(0.4, 0.1..1.0, tooltip = "Particle radius")
            shown.box.controlGroup("look") { keys.controls() }
        } finally {
            Box.context.remove()
        }
        assertEquals("Particle radius", live(shown.box).getValue("size").tooltip)
        assertFalse(shown.html.single().contains("data-tooltip=\"\""))
    }
}

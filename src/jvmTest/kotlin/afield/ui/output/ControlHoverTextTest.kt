package afield.ui.output

import afield.document.graph.Box
import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.keyframeCheckbox
import afield.ui.controls.ControlLaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlHoverTextTest {
    @Test fun `hover markdown travels with controls and follows reruns`() {
        val box = Box("hover-${System.nanoTime()}")
        val html = mutableListOf<String>()
        box.output.displayProxy = { _, content -> html += content.toString() }
        val callback: (Boolean) -> Unit = { }
        val numericCallback: (Double) -> Unit = { }
        val keys = KeyframeScope()
        fun draw(help: String) = box.controlGroup("controls") {
            slider("gain", hoverText = help) { }
            checkbox("enabled", false, callback) // The original positional callback form.
            button("reset", hoverText = "**Reset**") { }
            slider("legacy slider", 0.0, 1.0, 0.01, 0.5, ControlLaw.LINEAR, 2.0, 0.5, 0, numericCallback)
            button("legacy button", callback)
            keyframeCheckbox(keys, "flag", "keyed checkbox", { false }, callback)
        }
        draw("**Strong** & `code`")
        val controls = controllerControlsForBox(box.id).associateBy { it.label }
        assertEquals("**Strong** & `code`", controls.getValue("gain").hoverText)
        assertTrue(controls.getValue("gain").hoverHtml!!.contains("<strong>Strong</strong>"))
        assertTrue(html.last().contains("data-hover-html="))
        assertTrue(html.last().contains("&lt;strong&gt;Strong&lt;/strong&gt;"))
        assertEquals(null, controls.getValue("enabled").hoverText)
        draw("Updated **help**")
        val updated = controllerControlsForBox(box.id).first { it.label == "gain" }
        assertEquals("Updated **help**", updated.hoverText)
        assertTrue(updated.hoverHtml!!.contains("<strong>help</strong>"))
    }
}

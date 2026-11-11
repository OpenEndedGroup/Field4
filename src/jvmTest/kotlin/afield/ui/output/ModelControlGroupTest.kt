package afield.ui.output

import afield.animation.keyframes.OrderedKeyframeGroup
import afield.document.graph.Box
import afield.study.source.SourceBackedFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ModelControlGroupTest {
    private fun box() = Box("model-controls-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }

    @Test fun rebuildingAParameterPaneDoesNotFlattenItsFunctionToACachedConstant() {
        val box = box()
        box.controlGroup("look") { slider("cooling", initialValue = .8) {} }
        val group = OrderedKeyframeGroup({ 3 }) {}.lane("cooling", .1)
        val rule = SourceBackedFunction<Int, Double>(box.id, "{ i: Int -> .1 + .04 * i }") { .1 + .04 * it }
        group["cooling"] = rule
        var callbacks = 0
        box.controlGroup("look", restoreValues = false) {
            slider("cooling", initialValue = group.resolve()["cooling", 0]) { callbacks++; group["cooling"] = it }
        }
        assertEquals(0, callbacks)
        assertSame(rule, group.capture()["cooling"])
        val control = controllerControlsForBox(box.id).single { it.label == "cooling" }
        assertEquals(.1, control.value)
        getSliderCallback(control.id)!!(.4)
        assertEquals(1, callbacks)
        assertEquals(listOf(.4, .4, .4), group.resolve().values("cooling"))
    }

    @Test fun modelPolicyIgnoresStoredTextAndChecksWhileTheDefaultStillRestores() {
        val box = box()
        box.controlGroup("look") {
            checkbox("enabled", initialValue = true) {}
            stringInput("rule", initialValue = "old source") {}
        }
        var callbacks = 0
        box.controlGroup("look", columns = 2, restoreValues = false) {
            checkbox("enabled", initialValue = false) { callbacks++ }
            stringInput("rule", initialValue = "current source") { callbacks++ }
        }
        assertEquals(0, callbacks)
        val records = controllerControlsForBox(box.id).associateBy { it.label }
        assertEquals(false, records.getValue("enabled").checked)
        assertEquals("current source", records.getValue("rule").text)
        var restored = ""
        box.controlGroup("look") { stringInput("rule", initialValue = "ignored initializer") { restored = it } }
        assertEquals("current source", restored)
    }
    @Test fun multilineSourceRetainsLineBreaksAndEscapesHtmlEntities() {
        val box = box()
        var html = ""
        box.output.displayProxy = { _, content -> html = content.toString() }
        val source = "{ i: Int ->\n    val label = \"&lt;wave>\"\n    i * .04\n}"
        box.controlGroup("rules", restoreValues = false) {
            multilineText("source", initialValue = source, rows = 5) {}
        }
        assertEquals(source, controllerControlsForBox(box.id).single().text)
        kotlin.test.assertTrue(html.contains("<textarea"))
        kotlin.test.assertTrue(html.contains("rows=\"5\""))
        kotlin.test.assertTrue(html.contains("&amp;lt;wave&gt;"))
    }

}

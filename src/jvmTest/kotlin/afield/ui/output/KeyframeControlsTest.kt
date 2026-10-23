package afield.ui.output

import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.checkbox
import afield.animation.keyframes.color
import afield.animation.keyframes.slider
import afield.animation.keyframes.text
import afield.animation.keyframes.vec2
import afield.app.protocol.ControllerControl
import afield.document.graph.Box
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector4f
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `var size by keys.slider(...)` and its siblings: the delegate form of the `keyframe*` helpers. */
class KeyframeControlsTest {

    private fun newBox(): Box = Box("kfdelegates-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }

    private fun live(box: Box) = controllerControlsForBox(box.id).associateBy { it.label }

    private fun Box.gesture(label: String, change: (ControllerControl) -> ControllerControl) {
        val (_, control) = controlGroupControlsWithKeys(this, "look").first { it.second.label == label }
        val next = updateControllerControlValue(control.id, change).single()
        when (next.type) {
            "slider" -> getSliderCallback(next.id)!!.invoke(next.value!!)
            "checkbox" -> getCheckboxCallback(next.id)!!.invoke(next.checked!!)
        }
    }

    /** One run of a box body: a fresh scope and fresh delegates, as a rerun makes. */
    private class Run(box: Box, val changes: MutableList<String> = mutableListOf()) {
        val keys = KeyframeScope()
        var size by keys.slider(0.4, 0.1..1.0)
        var gain by keys.slider(0.35f, 0.0..4.0, label = "light gain")
        var offset by keys.vec2(Vector2f(0f, 0f))
        var tint by keys.color(Vector4f(0.2f, 0.6f, 1f, 1f))
        var ring by keys.checkbox(false)
        var title by keys.text("calm")
        val atDeclaration = size

        init {
            keys.onChange { changes += it }
            box.controlGroup("look") { keys.controls() }
        }
    }

    @Test
    fun propertyNameIsKeyAndLabelUnlessOverridden() {
        val box = newBox()
        val run = Run(box)
        assertEquals(setOf("size", "gain", "offset", "tint", "ring", "title"), run.keys.capture().values.keys)
        assertEquals(listOf("size", "light gain", "offset", "tint", "ring", "title"),
            controlGroupControlsWithKeys(box, "look").map { it.second.label })
        assertEquals(0.4, live(box).getValue("size").value)
    }

    @Test
    fun aRerunKeepsWhatThePanelLastSet() {
        val box = newBox()
        val first = Run(box)
        box.gesture("size") { it.copy(value = 0.8) }
        assertEquals(0.8, first.size)
        assertEquals(listOf("size"), first.changes)

        val second = Run(box)
        assertEquals(0.4, second.atDeclaration, "before controls() the initializer is all there is")
        assertEquals(0.8, second.size, "controls() restores the panel's value")
        assertTrue("size" in second.changes, "and tells onChange, so the picture follows")
    }

    @Test
    fun recallAndMorphWriteTheLocalTheControlAndSurviveARerun() {
        val box = newBox()
        val run = Run(box)
        val a = run.keys.capture()
        run.size = 1.0
        run.ring = true
        run.offset = Vector2f(1f, -1f)
        assertEquals(1.0, live(box).getValue("size").value, "assigning in code moves the control")
        assertEquals(emptyList(), run.changes, "but calls no listener")
        val b = run.keys.capture()

        run.keys.interpolate(a, b, 0.5)()
        assertEquals(0.7, run.size, 1e-9)
        assertEquals(Vector2f(0.5f, -0.5f), run.offset)
        assertEquals(true, run.ring)
        assertEquals(0.7, live(box).getValue("size").value!!, 1e-9)
        assertTrue(run.changes.containsAll(listOf("size", "offset", "ring")))

        run.keys.interpolateAlong(listOf(a, b), 0.0)()
        assertEquals(0.4, run.size, 1e-9)
        assertEquals(false, run.ring)

        b()
        val again = Run(box)
        assertEquals(1.0, again.size, "a recalled key is what the next run starts from")
        assertEquals(true, again.ring)
        assertEquals(Vector2f(1f, -1f), again.offset)
    }

    @Test
    fun presetBarStoresAndRecallsDelegatedControls() {
        val box = newBox()
        val dir = Files.createTempDirectory("kf-delegates-").resolve("keys")
        val keys = KeyframeScope()
        var size by keys.slider(0.4, 0.1..1.0)
        var bar: PresetBar? = null
        box.controlGroup("look") { keys.controls(); bar = presetBar(keys, dir) }
        bar!!.store("small")
        size = 0.9
        assertTrue(bar!!.recall("small"))
        assertEquals(0.4, size)
        assertEquals(0.4, live(box).getValue("size").value)
    }
}

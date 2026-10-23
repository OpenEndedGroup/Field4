package afield.ui.output

import afield.animation.keyframes.KeyframeCollection
import afield.animation.keyframes.KeyframeScope
import afield.animation.keyframes.keyframeCheckbox
import afield.animation.keyframes.keyframeColorPicker
import afield.animation.keyframes.keyframeSlider
import afield.animation.keyframes.keyframeText
import afield.animation.keyframes.keyframeVec2Control
import afield.app.protocol.ControllerControl
import afield.document.graph.Box
import afield.document.persistence.Edn
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector4f
import afield.ui.controls.ControlLaw
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlPresetsTest {

    private class Look {
        var size = 1.0
        var offset = Vector2f()
        var tint = Vector4f(1f, 1f, 1f, 1f)
        var on = false
        var title = "calm"
    }

    private fun newBox(): Box = Box("presets-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }

    private fun tempKeys(): Path = Files.createTempDirectory("control-presets-test-").resolve("keys")

    /** The look as preset-able controls: every one is a `keyframe*` control on [scope]. */
    private fun Box.look(l: Look, scope: KeyframeScope, dir: Path): PresetBar {
        var bar: PresetBar? = null
        controlGroup("look") {
            keyframeSlider(scope, "size", "size", 0.0, 10.0, get = { l.size }, set = { l.size = it })
            keyframeVec2Control(scope, "offset", "offset", get = { l.offset }, set = { l.offset = Vector2f(it) })
            keyframeColorPicker(scope, "tint", "tint", get = { l.tint }, set = { l.tint = Vector4f(it) })
            keyframeCheckbox(scope, "on", "on", get = { l.on }, set = { l.on = it })
            keyframeText(scope, "title", "title", get = { l.title }, set = { l.title = it })
            bar = presetBar(scope, dir)
        }
        return bar!!
    }

    private fun Box.plainLook(l: Look) = controlGroup("look") {
        slider("size", 0.0, 10.0, initialValue = 1.0) { l.size = it }
        vec2Control("offset") { l.offset = it }
        colorPicker("tint") { l.tint = it }
        checkbox("on") { l.on = it }
        text("title", "calm") { l.title = it }
    }

    /** What a person moving a control does: the registry (which listeners hear), then the callback. */
    private fun Box.gesture(label: String, change: (ControllerControl) -> ControllerControl) {
        val (_, control) = controlGroupControlsWithKeys(this, "look").first { it.second.label == label }
        val next = updateControllerControlValue(control.id, change).single()
        when (next.type) {
            "slider" -> getSliderCallback(next.id)!!.invoke(next.value!!)
            "vec2" -> getVec2Callback(next.id)!!.invoke(Vector2f(next.x!!.toFloat(), next.y!!.toFloat()))
            "color" -> getColorCallback(next.id)!!.invoke(Vector4f(next.r!!.toFloat(), next.g!!.toFloat(), next.b!!.toFloat(), next.a!!.toFloat()))
            "checkbox" -> getCheckboxCallback(next.id)!!.invoke(next.checked!!)
            "string" -> getStringCallback(next.id)!!.invoke(next.text!!)
        }
    }

    private fun live(box: Box) = controllerControlsForBox(box.id).associateBy { it.label }

    @Test
    fun keysAreLabelDerivedAndStable() {
        val box = newBox()
        box.plainLook(Look())
        val keys = controlGroupControlsWithKeys(box, "look").map { it.first }
        assertEquals(listOf("slider/size#0", "vec2/offset#0", "color/tint#0", "checkbox/on#0", "string/title#0"), keys)
        box.plainLook(Look())
        assertEquals(keys, controlGroupControlsWithKeys(box, "look").map { it.first }, "a rerun names the same controls")
    }

    @Test
    fun presetsAreSavedKeysThatRecallAndMoveTheControls() {
        val box = newBox()
        val l = Look()
        val scope = KeyframeScope()
        val dir = tempKeys()
        val bar = box.look(l, scope, dir)
        assertEquals(emptyList(), bar.names)
        bar.store("calm")

        box.gesture("size") { it.copy(value = 8.0) }
        box.gesture("offset") { it.copy(x = 0.5, y = -0.5) }
        box.gesture("on") { it.copy(checked = true) }
        box.gesture("title") { it.copy(text = "storm") }
        bar.store("storm")
        assertEquals(listOf("calm", "storm"), bar.names)
        assertEquals(8.0, l.size)

        assertTrue(bar.recall("calm"))
        assertEquals(1.0, l.size)
        assertEquals(Vector2f(0f, 0f), l.offset)
        assertFalse(l.on)
        assertEquals("calm", l.title)
        assertEquals(1.0, live(box).getValue("size").value, "the visible control follows the recall")
        assertFalse(bar.recall("nope"))

        // A store is a keyframer save: history, and a folder the collection browser reads.
        bar.store("calm", note = "again")
        assertEquals(2, scope.keyVersions(bar.path("calm")).size)
        assertEquals(listOf("calm", "storm"), KeyframeCollection.load(dir).files.map { it.relative }.sorted())

        // A rerun lists the saved keys as recall buttons.
        box.look(l, scope, dir)
        val labels = live(box).keys
        assertTrue("▸ calm" in labels && "▸ storm" in labels, "$labels")
    }

    @Test
    fun theMorphSliderBlendsKeysAndMovesTheControls() {
        val box = newBox()
        val l = Look()
        val scope = KeyframeScope()
        val bar = box.look(l, scope, tempKeys())
        bar.store("a")
        box.gesture("size") { it.copy(value = 9.0) }
        box.gesture("offset") { it.copy(x = 1.0, y = 1.0) }
        box.gesture("tint") { it.copy(r = 0.0, g = 0.0, b = 0.0, a = 0.0) }
        box.gesture("on") { it.copy(checked = true) }
        bar.store("b")
        assertTrue(scope.capture().values.keys.none { it.contains(presetMorphLabel) }, "the morph slider is not in a key")

        bar.morph(0.25)
        assertEquals(3.0, l.size, 1e-9)
        assertEquals(0.25f, l.offset.x, 1e-6f)
        assertEquals(0.75f, l.tint.x, 1e-6f)
        assertFalse(l.on, "a flag holds until halfway")
        assertEquals(3.0, live(box).getValue("size").value!!, 1e-9, "the slider shows the blend")

        val morph = live(box).getValue(presetMorphLabel)
        getSliderCallback(morph.id)!!.invoke(0.75)
        assertEquals(7.0, l.size, 1e-9)
        assertTrue(l.on)
        assertNull(PresetBar(scope, tempKeys()).morph(0.5), "nothing to blend without two keys")
    }

    @Test
    fun interpolateAlongIsPiecewiseAndClamped() {
        val scope = KeyframeScope()
        val keys = listOf(scope.Key("k" to 0.0), scope.Key("k" to 10.0), scope.Key("k" to 0.0))
        assertEquals(5.0, scope.interpolateAlong(keys, 0.25).values["k"])
        assertEquals(10.0, scope.interpolateAlong(keys, 0.5).values["k"])
        assertEquals(5.0, scope.interpolateAlong(keys, 0.75).values["k"])
        assertEquals(0.0, scope.interpolateAlong(keys, 1.0).values["k"])
        assertEquals(0.0, scope.interpolateAlong(keys, -3.0).values["k"])
        assertEquals(keys[0], scope.interpolateAlong(keys.take(1), 0.7))
    }

    @Test
    fun savedKeyNamesSkipsHistories() {
        val scope = KeyframeScope()
        val dir = tempKeys()
        assertEquals(emptyList(), scope.savedKeyNames(dir))
        scope.Key("x" to 1.0).save(dir.resolve("b"))
        scope.Key("x" to 2.0).save(dir.resolve("a"))
        assertEquals(listOf("a", "b"), scope.savedKeyNames(dir))
    }

    @Test
    fun recordingBakesATakeIntoKeys() {
        val box = newBox()
        val l = Look()
        box.plainLook(l)
        val rec = box.recordControl("size", "look", curveName = "size-take")
        // A straight ramp and a hold: thinning keeps the corners, not every sample.
        for (i in 0..20) rec.capture(i * 0.05, i * 0.5)
        for (i in 21..40) rec.capture(i * 0.05, 10.0)
        val keys = rec.keys()
        assertEquals(0.0, keys.first().time, 1e-9)
        assertEquals(0.0, keys.first().value, 1e-9)
        assertEquals(10.0, keys.last().value, 1e-9)
        assertTrue(keys.size in 2..4, "a ramp and a hold need about three keys, got ${keys.size}")

        // A real gesture reaches the recorder through the same path a browser change takes.
        val live = box.recordControl("size", "look")
        box.gesture("size") { it.copy(value = 4.0) }
        assertEquals(1, live.sampleCount)
        box.gesture("on") { it.copy(checked = true) }
        assertEquals(1, live.sampleCount, "another control is not recorded")
    }

    @Test
    fun midiLearnBindsTheNextControlAndKnobAndPersists() {
        val box = newBox()
        val l = Look()
        box.controlGroup("look") {
            slider("cutoff", 20.0, 20000.0, initialValue = 1000.0, law = ControlLaw.LOGARITHMIC) { l.size = it }
            checkbox("on") { l.on = it }
        }
        val midi = box.midiLearn("look")
        assertTrue(midi === box.midiLearn("look"), "one map per box and channel, across reruns")
        midi.learn()
        midi.feed(0, 74, 10) // knob first ...
        assertTrue(midi.isLearning)
        val (_, cutoff) = controlGroupControlsWithKeys(box, "look").first { it.second.label == "cutoff" }
        updateControllerControlValue(cutoff.id) { it.copy(value = 500.0) } // ... then the control
        assertFalse(midi.isLearning)
        assertEquals(listOf(MidiBinding("slider/cutoff#0", 0, 74)), midi.bindings)

        midi.feed(0, 74, 127)
        assertEquals(20000.0, l.size, 1e-6)
        midi.feed(0, 74, 0)
        assertEquals(20.0, l.size, 1e-6)
        midi.feed(0, 74, 64)
        assertTrue(l.size in 600.0..700.0, "the knob follows the slider's log law: ${l.size}")
        midi.feed(1, 74, 127)
        assertTrue(l.size < 700.0, "another MIDI channel is not bound")

        assertTrue(midi.bind("on", cc = 20))
        midi.feed(0, 20, 100)
        assertTrue(l.on)
        midi.bind("cutoff", cc = 20) // re-learning a knob moves it
        assertEquals(listOf("slider/cutoff#0"), midi.bindings.map { it.key })

        val roundTrip = Edn.read(Edn.write(box.properties[controlMidiProperty]))
        assertEquals(box.properties[controlMidiProperty].toString().length > 0, true)
        val fresh = Box("midi-fresh-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }
        fresh.properties[controlMidiProperty] = roundTrip
        assertEquals(listOf(MidiBinding("slider/cutoff#0", 0, 20)), fresh.midiLearn("look").bindings)
    }
}

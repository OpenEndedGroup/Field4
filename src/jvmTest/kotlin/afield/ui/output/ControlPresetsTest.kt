package afield.ui.output

import afield.document.graph.Box
import afield.document.persistence.Edn
import afield.math.linalg.Vector2f
import afield.math.linalg.Vector4f
import afield.ui.controls.ControlLaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlPresetsTest {

    private class Look {
        var size = 0.0
        var offset = Vector2f()
        var tint = Vector4f(1f, 1f, 1f, 1f)
        var on = false
        var title = ""
        var morphCalls = 0
    }

    private fun newBox(): Box = Box("presets-${System.nanoTime()}").also { it.output.displayProxy = { _, _ -> } }

    private fun Box.look(l: Look, bar: Boolean = false) = controlGroup("look") {
        slider("size", 0.0, 10.0, initialValue = 1.0) { l.size = it }
        vec2Control("offset") { l.offset = it }
        colorPicker("tint") { l.tint = it }
        checkbox("on") { l.on = it }
        text("title", "calm") { l.title = it }
        if (bar) presetBar()
    }

    /** What a person moving a control does: the registry and the rerun cache, then the callback. */
    private fun Box.gesture(label: String, value: ControlValue) {
        val (key, control) = controlGroupControlsWithKeys(this, "look").first { it.second.label == label }
        applyControlValues(this, "look", mapOf(key to value), durable = true)
        check(key.isNotEmpty() && control.label == label)
    }

    @Test
    fun keysAreLabelDerivedAndStable() {
        val box = newBox()
        box.look(Look())
        val keys = controlGroupControlsWithKeys(box, "look").map { it.first }
        assertEquals(listOf("slider/size#0", "vec2/offset#0", "color/tint#0", "checkbox/on#0", "string/title#0"), keys)
        box.look(Look())
        assertEquals(keys, controlGroupControlsWithKeys(box, "look").map { it.first }, "a rerun names the same controls")
    }

    @Test
    fun saveRecallCallsCallbacksAndMovesTheControls() {
        val box = newBox()
        val l = Look()
        box.look(l)
        val presets = box.controlPresets("look")
        presets.save("calm")

        box.gesture("size", ControlValue.Num(8.0))
        box.gesture("offset", ControlValue.Vec(0.5, -0.5))
        box.gesture("tint", ControlValue.Rgba(1.0, 0.0, 0.0, 1.0))
        box.gesture("on", ControlValue.Flag(true))
        box.gesture("title", ControlValue.Text("storm"))
        presets.save("storm")
        assertEquals(listOf("calm", "storm"), presets.names)
        assertEquals(8.0, l.size)

        assertTrue(presets.recall("calm"))
        assertEquals(1.0, l.size)
        assertEquals(Vector2f(0f, 0f), l.offset)
        assertEquals(false, l.on)
        assertEquals("calm", l.title)
        val live = controllerControlsForBox(box.id).associateBy { it.label }
        assertEquals(1.0, live.getValue("size").value, "the visible control follows the recall")
        assertFalse(presets.recall("nope"))
    }

    @Test
    fun morphInterpolatesNumbersAndSnapsFlags() {
        val box = newBox()
        val l = Look()
        box.look(l)
        val presets = box.controlPresets("look")
        presets.save("a")
        box.gesture("size", ControlValue.Num(9.0))
        box.gesture("offset", ControlValue.Vec(1.0, 1.0))
        box.gesture("tint", ControlValue.Rgba(0.0, 0.0, 0.0, 0.0))
        box.gesture("on", ControlValue.Flag(true))
        box.gesture("title", ControlValue.Text("b"))
        presets.save("b")

        presets.morph(0.25, "a", "b")
        assertEquals(3.0, l.size, 1e-9)
        assertEquals(0.25f, l.offset.x, 1e-6f)
        assertEquals(0.75f, l.tint.x, 1e-6f)
        assertFalse(l.on, "a flag holds until halfway")
        assertEquals("calm", l.title)

        presets.morph(0.75, "a", "b")
        assertEquals(7.0, l.size, 1e-9)
        assertTrue(l.on)
        assertEquals("b", l.title)
    }

    @Test
    fun morphAcrossThreePresetsIsPiecewise() {
        val p = listOf(
            mapOf("k" to ControlValue.Num(0.0)),
            mapOf("k" to ControlValue.Num(10.0), "only" to ControlValue.Num(4.0)),
            mapOf("k" to ControlValue.Num(0.0)),
        )
        assertEquals(ControlValue.Num(5.0), morphControlValues(p, 0.25)["k"])
        assertEquals(ControlValue.Num(10.0), morphControlValues(p, 0.5)["k"])
        assertEquals(ControlValue.Num(5.0), morphControlValues(p, 0.75)["k"])
        assertEquals(ControlValue.Num(0.0), morphControlValues(p, 1.0)["k"])
        assertEquals(ControlValue.Num(4.0), morphControlValues(p, 0.25)["only"], "a key one side lacks keeps its value")
        assertEquals(ControlValue.Num(0.0), morphControlValues(p, -3.0)["k"], "t is clamped")
    }

    @Test
    fun presetsSurviveAnEdnRoundTripAndAFreshBox() {
        val box = newBox()
        val l = Look()
        box.look(l)
        box.gesture("size", ControlValue.Num(6.5))
        box.gesture("tint", ControlValue.Rgba(0.1, 0.2, 0.3, 0.4))
        box.gesture("title", ControlValue.Text("saved"))
        box.controlPresets("look").save("keep")

        val edn = Edn.write(box.properties[controlPresetsProperty])
        val reloaded = Edn.read(edn)

        val fresh = newBox()
        fresh.properties[controlPresetsProperty] = reloaded
        val l2 = Look()
        fresh.look(l2)
        val presets = fresh.controlPresets("look")
        assertEquals(listOf("keep"), presets.names)
        assertTrue(presets.recall("keep"))
        assertEquals(6.5, l2.size, 1e-9)
        assertEquals(0.4f, l2.tint.w, 1e-6f)
        assertEquals("saved", l2.title)
    }

    @Test
    fun storedValuesReadTolerantly() {
        assertEquals(ControlValue.Num(2.0), controlValueFromStored(mapOf("value" to 2L)))
        assertEquals(ControlValue.Flag(true), controlValueFromStored(mapOf("checked" to "true")))
        assertEquals(ControlValue.Vec(1.0, 2.0), controlValueFromStored(mapOf("x" to "1", "y" to 2)))
        assertNull(controlValueFromStored(mapOf("nothing" to 1)))
        listOf(ControlValue.Num(1.5), ControlValue.Vec(1.0, -1.0), ControlValue.Rgba(0.1, 0.2, 0.3, 0.4), ControlValue.Flag(false), ControlValue.Text("x"))
            .forEach { assertEquals(it, controlValueFromStored(controlValueToStored(it))) }
    }

    @Test
    fun theMorphSliderIsNotPartOfAPresetAndDrivesTheBlend() {
        val box = newBox()
        val l = Look()
        box.look(l, bar = true)
        val presets = box.controlPresets("look")
        presets.save("a")
        assertTrue(presets["a"]!!.keys.none { it.contains(presetMorphLabel) })
        box.gesture("size", ControlValue.Num(10.0))
        presets.save("b")
        box.look(l, bar = true) // rerun: a recall button per preset now exists
        val labels = controllerControlsForBox(box.id).map { it.label }
        assertTrue("▸ a" in labels && "▸ b" in labels, "$labels")

        val morph = controllerControlsForBox(box.id).first { it.label == presetMorphLabel }
        getSliderCallback(morph.id)!!.invoke(0.5)
        assertEquals(5.5, l.size, 1e-9)
    }

    @Test
    fun recordingBakesATakeIntoKeys() {
        val box = newBox()
        val l = Look()
        box.look(l)
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
        box.gesture("size", ControlValue.Num(4.0))
        assertEquals(1, live.sampleCount)
        box.gesture("on", ControlValue.Flag(true))
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

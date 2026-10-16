package afield.audio.synth

import afield.ui.controls.ControlLaw
import kotlin.test.Test
import kotlin.test.assertEquals

/** A Faust `[scale:...]` becomes the slider's law, so the slider moves the way the program declared. */
class FaustSliderLawTest {
    private fun slider(scale: FaustScale, min: Double) = FaustControl(
        type = FaustControlType.HSLIDER, label = "freq", groupPath = emptyList(), address = "/x/freq",
        index = 0, init = 220.0, min = min, max = 2000.0, step = 0.01, scale = scale,
    )

    @Test
    fun scalesMapToLaws() {
        assertEquals(ControlLaw.LOGARITHMIC, slider(FaustScale.LOG, 50.0).sliderLaw())
        assertEquals(ControlLaw.EXPONENTIAL, slider(FaustScale.EXP, 50.0).sliderLaw())
        assertEquals(ControlLaw.LINEAR, slider(FaustScale.LINEAR, 50.0).sliderLaw())
        assertEquals(ControlLaw.LINEAR, slider(FaustScale.LOG, 0.0).sliderLaw(), "no logarithm reaches zero")
    }
}

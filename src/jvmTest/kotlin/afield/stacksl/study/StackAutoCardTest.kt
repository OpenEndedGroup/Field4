package afield.stacksl.study

import afield.Box
import afield.four.modules.ShaderKind
import afield.stdlib.StackAutoNumbers
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The numbers card of a box's `stack.auto` slots ([StackAutoCard]), without a window. */
class StackAutoCardTest {
    private val program = "\n# soft bands\n0.35 0.9 uv 4.0 * fbm smoothstep\npalette saturate\n"

    private fun box(): Box {
        val box = Box("stack_auto_card_test")
        box.code = "module.program = \"\"\"$program\"\"\"\n"
        box.stackAutoShaderSources["program"] = program
        preprocess(box, "#version 450\nlayout(location = 0) out vec4 c;\n{{ stack.auto.program }}\nvoid main() { c = program(vec4(0.0)); }\n", ShaderKind.FRAGMENT_SHADER)
        return box
    }

    @Test
    fun eachNumberGetsAFieldSliderSayingWhatItDoes() {
        val html = StackAutoCard.html(box())
        assertEquals(3, Regex("field-slider\"[^>]*data-knob=").findAll(html).count())
        assertTrue("k0 <span class='purpose'>smoothstep low</span>" in html && "k2 <span class='purpose'>multiplies</span>" in html, html)
        assertTrue("data-act='keep'" in html, "the program is written out in the code, so Keep is offered")
        assertTrue("data-act='alone'" in html)
    }

    @Test
    fun aSlotWithNoNumbersStillHasItsPictureInTheShader() {
        val box = Box("stack_auto_card_plain")
        box.stackAutoShaderSources["program"] = "uv y"
        preprocess(box, "#version 450\nlayout(location = 0) out vec4 c;\n{{ stack.auto.program }}\nvoid main() { c = program(vec4(0.0)); }\n", ShaderKind.FRAGMENT_SHADER)
        val html = StackAutoCard.html(box)
        assertTrue("no numbers to turn" in html && "data-act='in-shader'" in html, html)
        assertTrue("field-slider\"" !in html.substringBefore("Show it in your shader"), "no sliders, and no Keep")
        assertTrue("data-act='keep'" !in html)
    }

    @Test
    fun aLabelIsFoundInsideTheKotlinStringAndResetPutsTheLiteralsBack() {
        val box = box()
        // The program starts on line 1 of the code, after `"""`; its line 3 is the code's line 3.
        assertEquals("k2 is line 3 of the box's code", StackAutoCard.handle(box, """{"action":"locate","slot":"program","knob":2}"""))
        StackAutoNumbers.set(box, "program", 2, 9.0)
        assertEquals(9.0, StackAutoNumbers.value(box, "program", 2))
        StackAutoCard.handle(box, """{"action":"reset","slot":"program"}""")
        assertEquals(4.0, StackAutoNumbers.value(box, "program", 2))
    }
}

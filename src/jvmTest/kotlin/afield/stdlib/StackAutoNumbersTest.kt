package afield.stdlib

import afield.Box
import afield.four.buffers.Structure
import afield.four.modules.Introspection
import afield.four.modules.ShaderKind
import afield.stacksl.study.StackSLStudyShader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A `stack.auto` slot's numbers in a uniform block of its own ([StackAutoNumbers]): what the template
 * expands to, that it is valid Vulkan GLSL whose one block keeps its layout whatever the program, and
 * how values are turned, seeded and carried across a rebuild — all without a window.
 */
class StackAutoNumbersTest {
    private val fragment = """
        #version 450
        layout(location = 0) in vec2 fragUV;
        layout(location = 0) out vec4 outColor;

        {{ stack.auto.program }}

        void main() { outColor = vec4(program(vec4(fragUV, 0.0, 0.0)).rgb, 1.0); }
    """.trimIndent()

    private val savedResolver = StackAutoNumbers.moduleStructures

    @AfterTest
    fun restore() { StackAutoNumbers.moduleStructures = savedResolver }

    private fun expand(program: String, box: Box = Box("stack_auto_numbers_test")): String {
        box.stackAutoShaderSources["program"] = program
        return preprocess(box, fragment, ShaderKind.FRAGMENT_SHADER)
    }

    private fun blockOf(glsl: String) = glsl.substring(glsl.indexOf("layout(std140"), glsl.indexOf("};", glsl.indexOf("layout(std140")) + 2)

    @Test
    fun aSlotsNumbersReadItsBlockOnceLiveAndTheirLiteralsUntil() {
        val glsl = expand("uv 4.0 * fbm palette saturate")
        assertTrue("uniform program_numbers" in glsl && "float program_live;" in glsl && "float program_wire_127;" in glsl, glsl)
        assertTrue("(program_live != 0.0 ? program_wire_0 : 4.0)" in glsl, glsl)
        val introspection = Introspection(glsl, ShaderKind.FRAGMENT_SHADER)
        assertTrue(introspection.valid, "the expanded shader compiles as Vulkan GLSL")
        val block = introspection.ubos.single { it.name == "program_numbers" }
        assertEquals(StackAutoNumbers.CAPACITY + 1, block.members.size)
    }

    @Test
    fun theBlockIsTheSameForABlankSlotAndABoundOne() {
        // So binding a program to a slot that started blank still updates the pipeline in place.
        assertEquals(blockOf(expand("")), blockOf(expand("uv 4.0 * fbm palette saturate")))
        assertEquals(blockOf(expand("0.5")), blockOf(expand("uv 7 * sin 0.2 * 0.5 + fbm")))
    }

    @Test
    fun aProgramWithMoreNumbersThanTheBlockHoldsIsCompiledAsItWas() {
        val program = "uv\n" + (1..StackAutoNumbers.CAPACITY + 5).joinToString("\n") { "${it / 1000.0} +" } + "\nsaturate"
        val glsl = expand(program)
        assertTrue("uniform program_numbers" in glsl, "the block is still there, for the ABI")
        assertFalse("program_live != 0.0" in glsl, "the numbers are baked in")
        assertTrue(Introspection(glsl, ShaderKind.FRAGMENT_SHADER).valid)
    }

    @Test
    fun theStudysOwnTemplateStillCompiles() {
        val box = Box("stack_auto_numbers_study")
        box.stackAutoShaderSources["program"] = "uv 5 * fbm palette saturate"
        assertTrue(Introspection(preprocess(box, StackSLStudyShader.FRAGMENT, ShaderKind.FRAGMENT_SHADER), ShaderKind.FRAGMENT_SHADER).valid)
    }

    @Test
    fun turningANumberSeedsTheBlockAndARebuildCarriesIt() {
        val box = Box("stack_auto_numbers_values")
        val glsl = expand("uv 4.0 * fbm 0.25 + saturate", box)
        val members = Introspection(glsl, ShaderKind.FRAGMENT_SHADER).ubos.single { it.name == "program_numbers" }.members
        val structure = Structure().setMembers(members)
        StackAutoNumbers.moduleStructures = { if (it === box) listOf(mapOf("program_numbers" to structure)) else emptyList() }

        assertEquals(4.0, StackAutoNumbers.value(box, "program", 0))
        assertEquals(null, structure.get<Any?>("program_live"), "nothing is written until something turns a number")
        StackAutoNumbers.set(box, "program", 1, 0.6)
        assertEquals(1f, structure.get<Any?>("program_live"))
        assertEquals(4f, structure.get<Any?>("program_wire_0"), "the rest are seeded from their literals")
        assertEquals(0.6f, structure.get<Any?>("program_wire_1"))

        // A keyframe binds a member directly; a full reload carries both kinds over.
        structure.bind("program_wire_0") { 9f }
        val rebuilt = Structure().setMembers(members)
        StackAutoNumbers.carry(box, mapOf("program_numbers" to structure), mapOf("program_numbers" to rebuilt))
        assertEquals(9f, rebuilt.get<Any?>("program_wire_0"))
        assertEquals(0.6f, rebuilt.get<Any?>("program_wire_1"))
        assertEquals(1f, rebuilt.get<Any?>("program_live"))

        // New text re-seeds from its literals; the same text again changes nothing.
        expand("uv 4.0 * fbm 0.25 + saturate", box)
        assertEquals(0.6f, structure.get<Any?>("program_wire_1"), "re-running the box does not undo a turn")
        expand("uv 3.0 * fbm 0.25 + saturate", box)
        assertEquals(3f, structure.get<Any?>("program_wire_0"))
        assertEquals(0.25f, structure.get<Any?>("program_wire_1"))
    }
}

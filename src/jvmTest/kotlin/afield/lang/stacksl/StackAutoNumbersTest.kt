package afield.lang.stacksl

import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.main
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.geometry.mesh.expanded
import afield.graphics.color.rgb
import afield.graphics.interaction.pointer
import afield.graphics.render.rangeTo
import afield.graphics.text.layout
import afield.io.osc.main
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.sin
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.functions.sin
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.rgb
import afield.math.linalg.times
import afield.math.statistics.main
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.simulation.automata.main
import afield.study.ui.numbers

import afield.ai.learning.glsl
import afield.data.collections.get
import afield.data.graph.fragment
import afield.graphics.shader.fragment
import afield.graphics.shader.preprocess
import afield.graphics.shader.stackAutoShaderSources
import afield.math.linalg.set
import afield.simulation.particles.glsl
import afield.document.graph.Box
import afield.graphics.vulkan.buffers.Structure
import afield.graphics.shader.Introspection
import afield.graphics.render.ModuleRebuild
import afield.graphics.shader.PrimitiveType
import afield.graphics.render.ReloadCarryOver
import afield.graphics.shader.ShaderKind
import afield.math.linalg.Vector2f
import arrow.core.left
import afield.lang.stacksl.study.StackSLStudyShader
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

    /** A full reload's carry, in the order every module pointer runs it: the generic carry, then the hooks. */
    private fun rebuild(box: Box, old: Map<String, Structure>, new: Map<String, Structure>) {
        val hook: (ModuleRebuild.Rebuild) -> Unit = { StackAutoNumbers.carry(box, it) }
        ModuleRebuild.hooks += hook
        try {
            ReloadCarryOver.finishAndNotify(Any(), null, emptyList(), emptyMap(), old, new)
        } finally {
            ModuleRebuild.hooks -= hook
        }
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
    fun aSlotWrittenAboveThePushBlockCanStillReadIt() {
        // The slot line sits above the push-constant block and the program reads ?pc.knobs; the
        // generated function is placed after the block, so it compiles.
        val above = """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            {{ stack.auto.program }}
            layout(push_constant) uniform PC { vec4 knobs; } pc;
            void main() { outColor = vec4(program(vec4(fragUV, 0.0, 0.0)).rgb, 1.0); }
        """.trimIndent()
        val box = Box("stack_auto_above_pc_test")
        box.stackAutoShaderSources["program"] = "uv ?pc.knobs +"
        val glsl = preprocess(box, above, ShaderKind.FRAGMENT_SHADER)
        assertTrue(glsl.indexOf("} pc;") < glsl.indexOf("vec4 program("), glsl)
        assertTrue(Introspection(glsl, ShaderKind.FRAGMENT_SHADER).valid, "the expanded shader compiles:\n$glsl")
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
        rebuild(box, mapOf("program_numbers" to structure), mapOf("program_numbers" to rebuilt))
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

    @Test
    fun aRebuildFillsInAfterTheGenericCarryAndNeverOverIt() {
        val box = Box("stack_auto_numbers_order")
        val glsl = expand("uv 4.0 * fbm 0.25 + saturate", box)
        val members = Introspection(glsl, ShaderKind.FRAGMENT_SHADER).ubos.single { it.name == "program_numbers" }.members
        StackAutoNumbers.moduleStructures = { emptyList() }
        StackAutoNumbers.live(box, "program")
        StackAutoNumbers.set(box, "program", 1, 0.6)

        // The same block under an older layout: wire 0 was a vec2, wire 1 an int, and each holds
        // something that no longer fits the float the shader now declares.
        val oldMembers = members.map {
            when (it.name) {
                "program_wire_0" -> it.copy(type = PrimitiveType.vec2.left())
                "program_wire_1" -> it.copy(type = PrimitiveType.int.left())
                else -> it
            }
        }
        val old = Structure().setMembers(oldMembers)
        old["program_live"] = 1f
        old["program_wire_0"] = Vector2f(1f, 1f)
        old.bind("program_wire_1") { 7 }
        old.bind("program_wire_2") { 0.5f }

        val rebuilt = Structure().setMembers(members)
        rebuild(box, mapOf("program_numbers" to old), mapOf("program_numbers" to rebuilt))

        assertEquals(null, rebuilt.bindingFor("program_wire_1"), "the generic carry dropped a binding whose member was retyped; the hook does not bring it back")
        assertEquals(0.6f, rebuilt.valueFor("program_wire_1"), "the number the carry left empty gets what was set on the slot")
        assertEquals(4f, rebuilt.valueFor("program_wire_0"), "or its literal")
        assertTrue(rebuilt.bindingFor("program_wire_2") != null, "a binding the carry kept stays")
        assertEquals(1f, rebuilt.valueFor("program_live"))

        // A block the generic carry had nothing for is seeded whole from the live slot.
        val fresh = Structure().setMembers(members)
        rebuild(box, emptyMap(), mapOf("program_numbers" to fresh))
        assertEquals(1f, fresh.valueFor("program_live"))
        assertEquals(0.6f, fresh.valueFor("program_wire_1"))
    }
}

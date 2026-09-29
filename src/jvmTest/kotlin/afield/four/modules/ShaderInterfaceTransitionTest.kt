package afield.four.modules

import afield.four.buffers.Structure
import afield.stdlib.kotlinLinesReferencing
import afield.stdlib.linalg.Vector3f
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reload-transition matrix, on real reflection and without a GPU: what a full shader reload sees
 * change between two interfaces ([shaderInterfaceTransition]), what it tells the person
 * ([ShaderInterfaceTransition.summary], [ShaderInterfaceTransition.staleNames]), and what the carry-over
 * does with values set against the old layout ([Structure.carryValuesFrom], the reflected-type check in
 * [Structure.set] and `writeToBuffer`).
 *
 * Buffer reallocation, zeroing and retirement ([ReloadCarryOver]) need a device, and so do compile and
 * link failures of the module itself; those are exercised by the `four.shader-reload.*` smokes.
 */
class ShaderInterfaceTransitionTest {
    private fun compute(body: String): Introspection = Introspection(
        "#version 450\nlayout(local_size_x = 1) in;\n$body\n", ShaderKind.COMPUTE_SHADER
    )

    private val out = "layout(std430, set = 0, binding = 1) buffer outbuf { float values[]; };\n"

    private fun transition(old: Introspection, new: Introspection) =
        shaderInterfaceTransition(listOf("compute" to old), listOf("compute" to new))

    private val base = compute(
        "layout(std140, set = 0, binding = 0) uniform Light { vec3 color; float gain; };\n$out" +
            "layout(push_constant) uniform P { float tint; float time; } pc;\n" +
            "void main() { values[0] = color.x * gain + pc.tint + pc.time; }"
    )

    @Test
    fun anUnchangedInterfaceIsQuiet() {
        val t = transition(base, base)
        assertTrue(t.quiet, t.summary())
        assertEquals("", t.summary())
        assertTrue(t.staleNames.isEmpty())
    }

    @Test
    fun aRemovedUniformBlockIsRemovedAndItsNameIsStale() {
        val t = transition(base, compute("$out layout(push_constant) uniform P { float tint; float time; } pc;\nvoid main() { values[0] = pc.tint + pc.time; }"))
        val light = t.bindings.single { it.kind == ShaderBindingKind.UNIFORM_BLOCK }
        assertEquals(ShaderBindingChange.REMOVED, light.change)
        assertEquals("Light", light.name)
        assertTrue("uniform block `Light` removed" in t.summary(), t.summary())
        assertTrue("Light" in t.staleNames, t.staleNames.toString())
    }

    @Test
    fun aRenamedBlockAtTheSameBindingWithTheSameLayoutIsARename() {
        val t = transition(base, compute(
            "layout(std140, set = 0, binding = 0) uniform Lamp { vec3 color; float gain; };\n$out" +
                "layout(push_constant) uniform P { float tint; float time; } pc;\n" +
                "void main() { values[0] = color.x * gain + pc.tint + pc.time; }"
        ))
        val lamp = t.bindings.single { it.kind == ShaderBindingKind.UNIFORM_BLOCK }
        assertEquals(ShaderBindingChange.RENAMED, lamp.change)
        assertEquals("Light", lamp.oldName)
        assertEquals("Lamp", lamp.name)
        assertEquals("Light", t.oldNameOf(ShaderBindingKind.UNIFORM_BLOCK, "Lamp"), "the reload looks up the old buffer by the old name")
        assertTrue("uniform block `Light` renamed to `Lamp`, buffer and values carried over" in t.summary(), t.summary())
        assertTrue("Light" in t.staleNames)
    }

    @Test
    fun aGrownBlockIsResizedAndSaysSo() {
        val t = transition(base, compute(
            "layout(std140, set = 0, binding = 0) uniform Light { vec3 color; float gain; vec4 extra; };\n$out" +
                "layout(push_constant) uniform P { float tint; float time; } pc;\n" +
                "void main() { values[0] = color.x * gain + extra.x + pc.tint + pc.time; }"
        ))
        val light = t.bindings.single { it.kind == ShaderBindingKind.UNIFORM_BLOCK }
        assertEquals(ShaderBindingChange.RESIZED, light.change)
        assertEquals(16, light.oldSize)
        assertEquals(32, light.newSize)
        assertEquals(listOf("extra" to ShaderBindingChange.ADDED), light.members.map { it.name to it.change })
        assertTrue("uniform block `Light` grew 16→32 bytes (`extra` added), buffer reallocated" in t.summary(), t.summary())
        assertTrue(t.staleNames.isEmpty(), "nothing the old code named has gone: ${t.staleNames}")
    }

    @Test
    fun aRetypedMemberRetypesTheBlockAndItsNameIsStale() {
        // vec3 color -> float color: std140 keeps the block at 16 bytes, but gain moves and color shrinks.
        val t = transition(base, compute(
            "layout(std140, set = 0, binding = 0) uniform Light { float color; float gain; };\n$out" +
                "layout(push_constant) uniform P { float tint; float time; } pc;\n" +
                "void main() { values[0] = color * gain + pc.tint + pc.time; }"
        ))
        val light = t.bindings.single { it.kind == ShaderBindingKind.UNIFORM_BLOCK }
        assertTrue(light.change == ShaderBindingChange.RETYPED || light.change == ShaderBindingChange.RESIZED, light.toString())
        val color = light.members.single { it.name == "color" }
        assertEquals(ShaderBindingChange.RETYPED, color.change)
        assertEquals("vec3", color.oldType)
        assertEquals("float", color.newType)
        assertTrue("`color` vec3→float" in t.summary(), t.summary())
        assertTrue("buffer reallocated" in t.summary(), t.summary())
        assertTrue(t.staleNames["color"]!!.contains("now float"), t.staleNames.toString())
    }

    @Test
    fun anAddedBindingIsAddedAndZeroInitialised() {
        val t = transition(base, compute(
            "layout(std140, set = 0, binding = 0) uniform Light { vec3 color; float gain; };\n$out" +
                "layout(std140, set = 0, binding = 2) uniform Extra { vec4 bias; };\n" +
                "layout(push_constant) uniform P { float tint; float time; } pc;\n" +
                "void main() { values[0] = color.x * gain + bias.x + pc.tint + pc.time; }"
        ))
        val extra = t.bindings.single { it.name == "Extra" }
        assertEquals(ShaderBindingChange.ADDED, extra.change)
        assertTrue("uniform block `Extra` added, zero-initialised" in t.summary(), t.summary())
    }

    @Test
    fun removingEveryPushConstantRemovesEachOne() {
        val t = transition(base, compute(
            "layout(std140, set = 0, binding = 0) uniform Light { vec3 color; float gain; };\n$out" +
                "void main() { values[0] = color.x * gain; }"
        ))
        val push = t.bindings.filter { it.kind == ShaderBindingKind.PUSH_CONSTANT }
        assertEquals(setOf("tint", "time"), push.map { it.name }.toSet())
        assertTrue(push.all { it.change == ShaderBindingChange.REMOVED })
        assertTrue("push constant `tint` removed" in t.summary() && "push constant `time` removed" in t.summary(), t.summary())
        assertEquals(setOf("tint", "time"), t.staleNames.keys)
    }

    @Test
    fun aRetypedPushConstantIsStale() {
        val t = transition(base, compute(
            "layout(std140, set = 0, binding = 0) uniform Light { vec3 color; float gain; };\n$out" +
                "layout(push_constant) uniform P { vec2 tint; float time; } pc;\n" +
                "void main() { values[0] = color.x * gain + pc.tint.x + pc.time; }"
        ))
        val tint = t.bindings.single { it.kind == ShaderBindingKind.PUSH_CONSTANT && it.name == "tint" }
        assertEquals(ShaderBindingChange.RETYPED, tint.change)
        assertTrue("push constant `tint` is now vec2 (was float)" in t.summary(), t.summary())
        assertTrue("tint" in t.staleNames)
    }

    @Test
    fun aStageThatComesOrGoesIsNamed() {
        val t = shaderInterfaceTransition(listOf("compute" to base), listOf("compute" to base, "extra" to base))
        assertEquals(listOf("extra"), t.stagesAdded)
        assertTrue("stage `extra` added" in t.summary())
        val back = shaderInterfaceTransition(listOf("compute" to base, "extra" to base), listOf("compute" to base))
        assertEquals(listOf("extra"), back.stagesRemoved)
        assertTrue("stage `extra` removed" in back.summary())
    }

    // ---- values across a reload -------------------------------------------------------------------

    private fun lightMembers(i: Introspection) = i.ubos.single { it.name == "Light" }.members

    private val retyped = compute(
        "layout(std140, set = 0, binding = 0) uniform Light { float color; float gain; };\n$out" +
            "void main() { values[0] = color * gain; }"
    )

    @Test
    fun valuesThatStillFitAreCarriedAndTheRestAreNamed() {
        val old = Structure().setMembers(lightMembers(base))
        old["color"] = Vector3f(1f, 2f, 3f)
        old["gain"] = 0.5f
        val bound = { 0.25f }
        old.bind("gain", bound)

        val next = Structure().setMembers(lightMembers(retyped))
        val dropped = next.carryValuesFrom(old, "Light")

        assertNull(next.valueFor("color"), "a vec3 cannot go into what is now a float")
        assertTrue(dropped.any { "`Light.color` dropped" in it && "float" in it }, dropped.toString())
        assertTrue(next.bindingFor("gain") === bound, "gain kept its type, so its binding is carried")
        assertTrue(next.dirty, "carried values are written on the next frame")
    }

    @Test
    fun setRefusesAnUnknownOrMistypedMemberAndStoresNothing() {
        val s = Structure().setMembers(lightMembers(retyped))
        s["tint"] = 1f
        assertNull(s.valueFor("tint"), "an unknown member is not stored")
        s["color"] = Vector3f(1f, 1f, 1f)
        assertNull(s.valueFor("color"), "a vec3 is not stored into a float")
        s["color"] = 2f
        assertEquals(2f, s.valueFor("color"))
    }

    @Test
    fun writeToBufferRefusesAValueThatWouldOverwriteTheNextMember() {
        // The old path: a value set against the old layout (vec3 color), still held when the layout
        // becomes float color, float gain. Written by its Kotlin type it would put 12 bytes at offset 0
        // and overwrite gain at offset 4.
        val s = Structure().setMembers(lightMembers(base))
        s["color"] = Vector3f(9f, 9f, 9f)
        s.setMembers(lightMembers(retyped))
        s["gain"] = 0.5f
        val reports = mutableListOf<String>()
        s.reportTo = { reports += it }
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        s.writeToBuffer(bytes, null) { error("no references here") }
        val gainOffset = lightMembers(retyped).single { it.name == "gain" }.offset
        assertEquals(0.5f, bytes.getFloat(gainOffset), "gain is intact")
        assertEquals(0f, bytes.getFloat(0), "color was not written")
        assertTrue(reports.single().contains("`color`") && reports.single().contains("float"), reports.toString())
        s.writeToBuffer(bytes, null) { error("no references here") }
        assertEquals(1, reports.size, "the same refusal is reported once, not every frame")
    }

    @Test
    fun theSummaryIsALineNoteOnTheShaderAndTheStaleCodeLinesAreMarked() {
        val box = afield.Box("shader-transition-notes")
        box.setPropertyRaw("code", "val m = computeModuleHere()\nm.Light.color = vec(1,0,0)\n")
        val marks = mutableListOf<Pair<String, Int>>()
        box.output.errorProxy = { message, property, line -> if (message != null) marks += property to line }
        val shader = "layout(std140, set = 0, binding = 0) uniform Light { float color; float gain; };\nvoid main() {}\n"
        val t = transition(base, retyped)

        val summary = afield.stdlib.ShaderTransitionNotes.record(box, listOf("compute"), t)

        assertEquals(t.summary(), summary)
        val first = afield.stdlib.LineNotes.note(box, "compute", shader, 1)
        assertTrue(first != null && "Last shader reload" in first.caption && "vec3→float" in first.caption, first?.caption)
        assertNull(afield.stdlib.LineNotes.note(box, "compute", shader, 2), "a line that declares nothing changed has no note")
        assertTrue("code" to 2 in marks, "the code line using `color` is marked: $marks")

        afield.stdlib.ShaderTransitionNotes.record(box, listOf("compute"), transition(base, base))
        assertNull(afield.stdlib.LineNotes.note(box, "compute", shader, 1), "a quiet reload clears the note")
    }

    @Test
    fun kotlinLinesThatUseANameAreFound() {
        val code = "val m = moduleHere()\nm.Light.color = vec(1,0,0)\n// m.Light.gain in a comment\nmesh.push[\"tint\"] = 1f\nval colorful = 3\n"
        assertEquals(listOf(2), kotlinLinesReferencing(code, "color"))
        assertEquals(listOf(4), kotlinLinesReferencing(code, "tint"))
        assertEquals(emptyList(), kotlinLinesReferencing(code, "gain"), "comments are not uses")
        assertFalse(kotlinLinesReferencing(code, "Light").isEmpty())
    }
}

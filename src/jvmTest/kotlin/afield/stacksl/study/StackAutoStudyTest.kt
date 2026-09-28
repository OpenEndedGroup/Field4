package afield.stacksl.study

import afield.Box
import afield.four.modules.ShaderKind
import afield.stdlib.SourceStudy
import afield.stdlib.StudyRejection
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A model's study of a `stack.auto` slot ([StackAutoSlotStudy]), without a window: what Inspect reads
 * from the host shader, and the check. Binding an edit live and capturing it in the shader need a
 * running module; they were checked by hand in a window (see LIVE_STUDY_REVIEW.md).
 */
class StackAutoStudyTest {
    private val folders = mutableListOf<java.io.File>()

    @AfterTest
    fun removeFolders() = folders.forEach { it.deleteRecursively() }

    private val fragment = "#version 450\nlayout(location = 0) in vec2 fragUV;\nlayout(location = 0) out vec4 outColor;\n" +
        "{{ stack.auto.program }}\nvoid main() { outColor = vec4(program(vec4(fragUV, 0.0, 0.0)).rgb * 0.5, 1.0); }\n"

    private fun study(): StackAutoSlotStudy {
        val box = Box("stack_auto_study_test")
        box.setPropertyRaw("fragment", fragment)
        box.stackAutoShaderSources["program"] = "0.35 0.9 uv 4.0 * fbm smoothstep palette saturate"
        preprocess(box, fragment, ShaderKind.FRAGMENT_SHADER)
        val dir = Files.createTempDirectory("stack-auto-study").toFile().also { folders += it }
        // No module or main thread here: an accepted program is simply the slot's new source.
        val publish = { expected: String, replacement: String ->
            if (box.stackAutoShaderSources["program"] != expected) false else { box.stackAutoShaderSources["program"] = replacement; true }
        }
        return StackAutoSlotStudy(box, "program", dir, publish).also { it.checkIn("first") }
    }

    @Test
    fun everyToolBuildsIntoARequestTheModelIsSent() {
        // The OpenAI client checks each tool's schema as it is added, and refuses one with no properties:
        // the first recorded turn (2026-09-28) failed on StackAutoSlotGuide before the model saw anything.
        val tools = listOf(StackAutoSlotGuide::class, InspectStackAutoSlot::class, EditStackAutoSlot::class, PatchStackAutoSlot::class,
            SetStackAutoNumbers::class, ObserveStackAutoSlot::class, SweepStackAutoNumber::class, RestoreStackAutoRevision::class)
        val request = com.openai.models.responses.ResponseCreateParams.builder().model("gpt-5").input("")
        tools.forEach { request.addTool(it.java) }
        assertEquals(tools.size, request.build().tools().get().size)
    }

    @Test
    fun inspectReadsHowTheHostUsesTheSlotAndWhatEachNumberDoes() {
        val facts = study().inspect().facts
        @Suppress("UNCHECKED_CAST")
        val host = facts["host"] as List<String>
        assertTrue(host.any { it.startsWith("fragment:4:") && "stack.auto.program" in it }, host.toString())
        assertTrue(host.any { "program(vec4(fragUV" in it }, "the line that calls it says what its input and output are: $host")
        @Suppress("UNCHECKED_CAST")
        val knobs = facts["knobs"] as List<String>
        assertEquals(listOf("k0 L1:1 0.35 → smoothstep low", "k1 L1:6 0.9 → smoothstep high", "k2 L1:13 4.0 → multiply b"), knobs)
    }

    @Test
    fun aProgramThatDoesNotCompileIntoTheSlotIsRefusedAndNothingChanges() {
        val s = study()
        val r = s.revisions.revision()
        val refused = assertFailsWith<StudyRejection> { s.edit(r.id, SourceStudy.hash(r.source), "uv 4.0 * nosuchword", "broken") }
        assertTrue("does not compile into stack.auto.program" in refused.message!!, refused.message)
        assertEquals(r.id, s.revisions.currentId())
    }
}

package afield.stacksl.study

import afield.Box
import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import afield.stdlib.SourceStudy
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import afield.testRepoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A replay of the recorded stack.auto conversation in `dev_docs/agents/demos/stackauto_conversation_polytope`,
 * without a window: every saved revision of both slots matches its id, and — put into the box's own
 * shaders beside the other slot's final program — compiles to SPIR-V; the box's code holds each slot's
 * `HEAD` verbatim, as the study writes it back.
 */
class StackAutoConversationExampleTest {
    private val folder = testRepoRoot().resolve("dev_docs/agents/demos/stackauto_conversation_polytope").toFile()
    private val slots = mapOf("explode" to ("vertex" to ShaderKind.VERTEX_SHADER), "face_decay" to ("fragment" to ShaderKind.FRAGMENT_SHADER))

    private fun revisions(slot: String) = File(folder, "studies/polytope_source/stack.auto/$slot/revisions")
    private fun head(slot: String) = File(revisions(slot), File(revisions(slot), "HEAD").readText().trim() + ".stacksl").readText()

    @Test
    fun everyRevisionOfBothSlotsCompilesIntoTheBoxsShaders() {
        slots.forEach { (slot, shader) ->
            val (property, kind) = shader
            val host = File(folder, "polytope_source_${property}_.glsl").readText()
            val saved = revisions(slot).listFiles().orEmpty().filter { it.extension == "stacksl" }.sortedBy { it.name.substringBefore('-').drop(1).toInt() }
            assertTrue(saved.size >= 3, "$slot should record a conversation, found ${saved.size} revisions")
            saved.forEach { file ->
                val source = file.readText()
                assertEquals(file.nameWithoutExtension.substringAfter('-'), SourceStudy.hash(source).take(12), "$slot ${file.name} should match its id")
                val box = Box("stackauto_conversation_replay")
                slots.keys.forEach { box.stackAutoShaderSources[it] = head(it) }
                box.stackAutoShaderSources[slot] = source
                val glsl = preprocess(box, host, kind)
                ShaderSPIRVUtils.compileShader("polytope.$property", glsl, kind) // throws on a compile error
            }
        }
    }

    @Test
    fun theBoxsCodeHoldsEachSlotsHead() {
        val code = File(folder, "polytope_source_code_.kt").readText()
        slots.keys.forEach { slot -> assertTrue(head(slot) in code, "the code should hold stack.auto.$slot's HEAD verbatim") }
    }
}

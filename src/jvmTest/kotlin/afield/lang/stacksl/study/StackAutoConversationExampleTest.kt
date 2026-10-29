package afield.lang.stacksl.study

import afield.document.graph.Box
import afield.graphics.shader.ShaderKind
import afield.graphics.shader.ShaderSPIRVUtils
import afield.study.source.SourceStudy
import afield.graphics.shader.preprocess
import afield.graphics.shader.stackAutoShaderSources
import afield.tools.docs.testRepoRoot
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
    fun theStarterIsWhereTheRecordingBegan() {
        // The download on public/docs/stackauto_conversation.md: each slot's program is its first revision,
        // and each host shader has, at the line numbers the first Inspect of that slot reported, the lines it read.
        val starter = File(folder.parentFile, "stackauto_conversation")
        val code = File(starter, "polytope_source_code_.kt").readText()
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        mapOf("explode" to "explode-01-burst", "face_decay" to "decay-01-embers").forEach { (slot, turn) ->
            val first = revisions(slot).listFiles().orEmpty().single { it.name.startsWith("r1-") && it.extension == "stacksl" }.readText()
            assertTrue(Regex("module\\.$slot =\\s*\"\"\"" + Regex.escape(first) + "\"\"\"").containsMatchIn(code), "the starter should hold stack.auto.$slot's r1")
            val host = mapper.readTree(File(folder, "conversations/$turn.json"))["before"]["facts"]["host"].map { it.asText() }
            assertTrue(host.size >= 2, "$turn should record the host lines it read")
            // The host may have gained lines above the slot since the recording (ac1bd079c's typed push
            // block added four), so the lines it read must appear in order, all shifted by one offset.
            val read = host.map { line ->
                val (property, number, text) = Regex("""^(\w+):(\d+): (.*)$""").find(line)!!.destructured
                Triple(property, number.toInt(), text)
            }
            val shader = File(starter, "polytope_source_${read.first().first}_.glsl").readLines()
            val shift = shader.indexOfFirst { it.trim() == read.first().third } - (read.first().second - 1)
            assertTrue(shift >= 0, "starter ${read.first().first} no longer holds `${read.first().third}`")
            read.forEach { (property, number, text) ->
                val lines = File(starter, "polytope_source_${property}_.glsl").readLines()
                assertEquals(text, lines.getOrNull(number - 1 + shift)?.trim(), "starter $property line $number (+$shift)")
            }
        }
    }

    @Test
    fun theBoxsCodeHoldsEachSlotsHead() {
        val code = File(folder, "polytope_source_code_.kt").readText()
        slots.keys.forEach { slot -> assertTrue(head(slot) in code, "the code should hold stack.auto.$slot's HEAD verbatim") }
    }
}

package afield.ai.core

import afield.Box
import afield.SessionInformation
import afield.ai.agent.FieldGraphTools
import afield.ai.agent.InFieldAgentHarnessTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The instruction block as [ContextFragment]s: that the parts join to exactly what [legacyBuildDynamicInstructions]
 * (a frozen copy of the pre-fragment builder) produced, for every mode, a few languages and a spread of tool
 * contexts; and that the parts are tagged so the bridges keep the request and drop the rest.
 */
class InstructionFragmentsTest {

    private fun document(): SessionInformation {
        val dir = Files.createTempDirectory("instruction-fragments-").toFile().apply { deleteOnExit() }
        val root = Box("root").also { it.name = "root" }
        val info = SessionInformation(filename = dir.resolve("doc").absolutePath, documentName = "doc", root = root, ephemeral = true)
        root.inside = info
        return info
    }

    private fun guides(): DocumentGuideTools {
        val dir = Files.createTempDirectory("instruction-guides-").toFile().apply { deleteOnExit() }
        dir.resolve("HANDOFF.md").writeText("# Handoff\n\nwhere we are\n")
        return DocumentGuideTools(dir) { false }
    }

    private val stub = ToolExecutionContext(
        attachments = listOf(ContextAttachment("afield://glsl-includes/manifest", "text", "manifest")),
        listDefinitions = { ListDefinitionsResult(emptyList()) },
        listFileContents = { ListContentsResult() },
        listKotlinContextVariables = { throw UnsupportedOperationException() },
        evaluateKotlinExpression = { throw UnsupportedOperationException() },
        editOtherFile = { throw UnsupportedOperationException() },
        compileKotlin = { throw UnsupportedOperationException() },
        reloadShaders = { throw UnsupportedOperationException() },
        captureRenderWindow = { throw UnsupportedOperationException() },
        listMacros = { throw UnsupportedOperationException() },
        codeUseQuery = { throw UnsupportedOperationException() },
        semanticRetrievalSearch = { throw UnsupportedOperationException() },
        agentMemory = AgentMemoryState(),
        workspaceSymbolSearch = { WorkspaceSymbolSearchResult(emptyList()) },
    )

    private fun contexts(): Map<String, ToolExecutionContext> {
        val host = InFieldAgentHarnessTest.FakeHost(emptyMap())
        return mapOf(
            "empty" to ToolExecutionContext(),
            "all-gates" to stub,
            "guides" to ToolExecutionContext(documentGuides = guides()),
            "graph-chat" to ToolExecutionContext(fieldGraph = FieldGraphTools(document(), host, lessons = false)),
            "graph-editor-blind" to ToolExecutionContext(fieldGraph = FieldGraphTools(document(), host,
                replyOwned = setOf("uid" to "code"), lessons = false, seesOutput = false)),
            "graph-lessons" to stub.copy(fieldGraph = FieldGraphTools(document(), host, lessons = true)),
            "programs-withheld" to stub.copy(withheld = setOf(afield.ai.core.program.RUN_TOOL_PROGRAM)),
        )
    }

    private val languages = listOf(KOTLIN_SCRIPT, GLSL_FRAGMENT)
    private val prompt = "Make it print\n  the squares, please"

    @Test
    fun theFragmentsJoinToExactlyThePreFragmentInstructions() {
        val mismatches = mutableListOf<String>()
        for ((name, ctx) in contexts()) for (lang in languages) for (mode in InstructionMode.entries) {
            val legacy = legacyBuildDynamicInstructions(mode, lang, prompt, "Unresolved reference: foo", 7, ctx)
            // Deliberate difference 2 (2026-10-02): the tool catalogue (deferred and extension tools) is new.
            var joined = buildInstructionFragments(mode, lang, prompt, "Unresolved reference: foo", 7, ctx)
                .filter { it.source != "tools.catalogue" }.joinToString("") { it.text }
            // The one deliberate difference (2026-10-02): a chat turn no longer repeats the request inside its
            // instructions, because chatGeneral's message ends with it anyway. Put it back and nothing else differs.
            if (mode == InstructionMode.Chat) {
                val contract = generalChatContract(lang)
                assertTrue(joined.startsWith(contract))
                joined = contract + "\n\nUser request: $prompt" + joined.removePrefix(contract)
            }
            if (legacy != joined) {
                val at = legacy.zip(joined).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(legacy.length, joined.length) else it }
                mismatches += "$name/${lang.name}/$mode differs at $at: legacy='${legacy.substring(at).take(60)}' joined='${joined.substring(at).take(60)}'"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun theRequestIsDurableOnlyWhereItAppearsNowhereElse() {
        val ctx = ToolExecutionContext()
        fun durable(mode: InstructionMode) =
            buildInstructionFragments(mode, KOTLIN_SCRIPT, prompt, "boom", 3, ctx).filter { it.retention == FragmentRetention.DURABLE }.map { it.source }
        assertEquals(listOf("core.request"), durable(InstructionMode.Inline))
        assertEquals(listOf("core.request"), durable(InstructionMode.Error))
        assertEquals(emptyList(), durable(InstructionMode.General))
        assertEquals(emptyList(), durable(InstructionMode.Chat))
    }

    @Test
    fun theGuideCatalogueIsTheVolatileToolFragment() {
        val fragments = buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, prompt, null, null, ToolExecutionContext(documentGuides = guides()))
        val guides = fragments.single { it.source == "guides" }
        assertEquals(Stability.VOLATILE, guides.stability)
        assertTrue(guides.text.contains("HANDOFF.md"))
        assertTrue(fragments.filter { it.source.startsWith("tools.") }.all { it.stability == Stability.STABLE })
    }

    @Test
    fun aTurnContextKeepsTheDurablePartsAndTheRest() {
        val tc = TurnContext(buildInstructionFragments(InstructionMode.Inline, KOTLIN_SCRIPT, prompt, null, 4, ToolExecutionContext()), "CODE")
        val durable = tc.durablePrompt()
        assertTrue(durable.startsWith("The user wants help starting at line 4."), durable.take(80))
        assertTrue(durable.endsWith("\n\nCODE"))
        assertTrue("Tools available:" !in durable)
        assertTrue(tc.allEphemeralText().contains("Tools available:"))
        assertEquals(buildDynamicInstructions(InstructionMode.Inline, KOTLIN_SCRIPT, prompt, null, 4, ToolExecutionContext()) + "\n\nCODE", tc.legacyPrompt())
    }
}

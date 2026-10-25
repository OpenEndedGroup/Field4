package afield.ai.conversation

import afield.ai.tools.AttachmentRequest
import afield.ai.tools.DocumentBoxNavigateRequest

import afield.ai.tools.AgentMemoryState
import afield.ai.tools.ContextAttachment
import afield.ai.tools.DocumentGuideTools
import afield.ai.tools.ListContentsResult
import afield.ai.tools.ListDefinitionsResult
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.WorkspaceSymbolSearchResult
import afield.runtime.languages.GLSL_FRAGMENT
import afield.runtime.languages.KOTLIN_SCRIPT

import afield.document.graph.Box
import afield.app.server.SessionInformation
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
            "programs-withheld" to stub.copy(withheld = setOf(afield.ai.tools.program.RUN_TOOL_PROGRAM)),
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
                .filter { it.source !in setOf("tools.catalogue", "tools.progress", "tools.house", "fences.catalogue") }.joinToString("") { it.text }
            // The one deliberate difference (2026-10-02): a chat turn no longer repeats the request inside its
            // instructions, because chatGeneral's message ends with it anyway. Put it back and nothing else differs.
            if (mode == InstructionMode.Chat) {
                val contract = generalChatContract(lang)
                assertTrue(joined.startsWith(contract))
                joined = contract + "\n\nUser request: $prompt" + joined.removePrefix(contract)
            }
            // House-workflow additions are intentional behavior changes, independently pinned below.
            joined = joined.lineSequence().filterNot { it.startsWith("- Use DocumentBoxNavigateRequest") }.joinToString("\n")
            joined = joined.replace(Regex("- AttachmentRequest: read bounded source excerpts[^\n]*"),
                "- AttachmentRequest: retrieve attached context by name/path (supports attached names and resolvable refs/paths; responses may suggest relatedGuides and relatedExamples).")
            if (legacy != joined) {
                val at = legacy.zip(joined).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(legacy.length, joined.length) else it }
                mismatches += "$name/${lang.name}/$mode differs at $at: legacy='${legacy.substring(at).take(60)}' joined='${joined.substring(at).take(60)}'"
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun houseGuidancePreservesProgressRevisionChecksAndBoundedReads() {
        val ctx = contexts().getValue("graph-lessons")
        val fragments = buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, prompt, null, null, ctx)
        assertTrue(fragments.single { it.source == "tools.progress" }.text.contains("observe the existing handle"))
        val builds = fragments.filter { it.source == "tools.house" }.joinToString { it.text }
        for (required in listOf("currency.isCurrent", "expectedRevision", "latest note version", "acceptance is not completion",
            "fromEarlierInputs=true", "openFindings", "Geometry notes from its findings carry the same fromEarlierInputs mark",
            "\"geometryView\" to mapOf(\"box\""))
            assertTrue(builds.contains(required), required)
        assertTrue(fragments.single { it.source == "core.contract" }.text.contains("DocumentBoxNavigateRequest"))
        val attachments = fragments.filter { it.source == "tools.attachments" }.joinToString { it.text }
        for (required in listOf("6000 characters", "expectedSourceHash", "searchMatched=false", "must not be used as a full-file replacement"))
            assertTrue(attachments.contains(required), required)
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
        // The catalogue is volatile by design (what is loaded changes within a conversation); the adverts are not.
        assertTrue(fragments.filter { it.source.startsWith("tools.") && it.source != "tools.catalogue" }.all { it.stability == Stability.STABLE })
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

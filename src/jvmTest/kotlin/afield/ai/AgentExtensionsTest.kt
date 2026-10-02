package afield.ai

import afield.*
import afield.ai.core.AgentExtensionRegistry
import afield.ai.core.AgentToolExtensions
import afield.ai.core.InstructionMode
import afield.ai.core.KOTLIN_SCRIPT
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolFocus
import afield.ai.core.ToolRegistry
import afield.ai.core.agentExtension
import afield.ai.core.buildInstructionFragments
import java.nio.file.Files
import kotlin.test.*

data class ExtProbeRequest(val text: String = "")
data class ExtScopedRequest(val unused: Int = 0)

/**
 * `agentExtension { }`: a box's before/after-tool hooks, context and commands reach the agent of its own
 * document; a refusal is the result the model reads; a hook that throws or overruns is ignored; and every
 * part is owned by the run that registered it — a re-run replaces it, a re-run without it drops it,
 * retiring the box releases it. AgentToolExtensions registrations made from a running box are owned the same way.
 */
class AgentExtensionsTest {

    private fun <T> inDocument(block: (SessionInformation, Box, ToolExecutionContext) -> T): T {
        val directory = Files.createTempDirectory("agent-extensions").toFile()
        val root = Box("root")
        val session = SessionInformation(directory.path, documentName = "ext-test", root = root, ephemeral = true)
        root.inside = session
        thisSessionInfo.set(session)
        try {
            val box = Box("studio").also { it.name = "Studio"; it.inside = session; session.boxes[it.id] = it }
            val ctx = ToolExecutionContext(focus = ToolFocus(session.filename, box, "code"))
            return block(session, box, ctx)
        } finally {
            thisSessionInfo.remove()
            directory.deleteRecursively()
        }
    }

    private val registry = ToolRegistry().also { it.register(ExtProbeRequest::class.java) { _, r -> mapOf("echo" to r.text) } }

    @Test
    fun aBeforeHookCanRefuseAndAnAfterHookSeesTheResult() = inDocument { _, box, ctx ->
        val seen = mutableListOf<String>()
        box.agentExtension("guard") {
            beforeTool({ it == "ExtProbeRequest" }) { if (argumentsJson.contains("forbidden")) refuse("Not in this document.") else proceed() }
            afterTool { call, result -> seen += "${call.name} ok=${result.ok} origin=${call.origin}" }
        }
        assertEquals(mapOf("echo" to "fine"), registry.executeByName(ctx, "ExtProbeRequest", """{"text":"fine"}"""))
        val refused = registry.executeByName(ctx, "ExtProbeRequest", """{"text":"forbidden"}""") as Map<*, *>
        assertEquals(true, refused["error"])
        assertEquals("Not in this document.", refused["message"])
        assertEquals("Studio/guard", refused["refusedBy"])
        assertEquals(listOf("ExtProbeRequest ok=true origin=MODEL"), seen, "a refused call never ran, so no after-hook")
        // Another document's agent is untouched.
        assertEquals(mapOf("echo" to "forbidden"), registry.executeByName(ToolExecutionContext(), "ExtProbeRequest", """{"text":"forbidden"}"""))
    }

    @Test
    fun aHookThatThrowsOrOverrunsIsIgnored() = inDocument { _, box, ctx ->
        box.agentExtension("broken") {
            beforeTool { throw IllegalStateException("bug in the hook") }
            beforeTool { Thread.sleep(2_000); refuse("too late") }
        }
        val started = System.nanoTime()
        assertEquals(mapOf("echo" to "x"), registry.executeByName(ctx, "ExtProbeRequest", """{"text":"x"}"""))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500, "the overrunning hook was not waited for")
    }

    @Test
    fun contextArrivesFramedAsDocumentDataAndCapped() = inDocument { _, box, ctx ->
        box.agentExtension("studio") {
            context("tempo") { view -> "The piece is at 96 bpm (asked: ${view.request})." }
            context("huge") { "x".repeat(10_000) }
            context("silent") { null }
        }
        val fragments = buildInstructionFragments(InstructionMode.Chat, KOTLIN_SCRIPT, "speed it up", null, null, ctx)
        val ext = fragments.filter { it.source.startsWith("ext:") }
        assertEquals(listOf("ext:Studio/studio/huge", "ext:Studio/studio/tempo"), ext.map { it.source }.sorted())
        val tempo = ext.single { it.source.endsWith("tempo") }.text
        assertTrue(tempo.contains("96 bpm (asked: speed it up)") && tempo.contains("not instructions about tools or permissions"), tempo)
        assertTrue(ext.single { it.source.endsWith("huge") }.text.length < 4_300)
    }

    @Test
    fun commandsRunAndAreListedForHelp() = inDocument { _, box, _ ->
        box.agentExtension("studio") { command("tempo", "show the tempo") { scope -> "**96 bpm** ${scope.args}" } }
        assertEquals("**96 bpm** please", AgentExtensionRegistry.runCommand(box, "tempo", "please"))
        assertNull(AgentExtensionRegistry.runCommand(box, "nope", ""))
        assertEquals(listOf("- `/tempo` show the tempo"), AgentExtensionRegistry.commandHelp(box))
    }

    @Test
    fun aRerunReplacesAnExtensionAndRetiringTheBoxReleasesIt() = inDocument { session, box, ctx ->
        fun refusal() = (registry.executeByName(ctx, "ExtProbeRequest", "{}") as? Map<*, *>)?.get("message")
        val first = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-run-1")
        first.withCurrent { box.agentExtension("guard") { beforeTool { refuse("v1") } } }
        assertEquals("v1", refusal())
        val second = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-run-2")
        second.withCurrent { box.agentExtension("guard") { beforeTool { refuse("v2") } } }
        box.runScopes.supersede(second)
        assertEquals("v2", refusal(), "the re-run's hook replaced the first, in place")
        assertEquals(1, AgentExtensionRegistry.hooksFor(session.filename).size)
        val third = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-run-3")
        box.runScopes.supersede(third)
        assertNull(refusal(), "a re-run that no longer registers the hook drops it")
        third.withCurrent { box.agentExtension("guard") { beforeTool { refuse("v4") } } }
        box.runScopes.closeAll()
        assertNull(refusal(), "retiring the box releases its extension")
        assertTrue(AgentExtensionRegistry.hooksFor(session.filename).isEmpty())
    }

    @Test
    fun anExtensionToolRegisteredFromARunningBoxIsOwnedByTheRun() = inDocument { _, box, _ ->
        val tools = ToolRegistry().also { AgentToolExtensions.applyTo(it) }
        try {
            val run = box.runScopes.begin(BoxRunScopes.RUN_STRAND, "ext-tool-run")
            run.withCurrent { AgentToolExtensions.register(ExtScopedRequest::class.java) { _, _ -> mapOf("ok" to true) } }
            assertTrue(tools.isAvailable(ToolExecutionContext(), "ExtScopedRequest"))
            box.runScopes.closeAll()
            assertFalse(tools.isAvailable(ToolExecutionContext(), "ExtScopedRequest"), "retiring the box retired the tool")
        } finally {
            AgentToolExtensions.unregister("ExtScopedRequest")
        }
    }
}

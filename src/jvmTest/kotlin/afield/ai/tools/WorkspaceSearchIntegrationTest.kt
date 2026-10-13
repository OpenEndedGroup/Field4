package afield.ai.tools

import afield.ai.conversation.IdeChatHelpers
import afield.ai.models.ScriptedLlmBridge
import afield.runtime.languages.LanguageSpec

import afield.document.graph.Box
import afield.app.server.SessionInformation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real registry/argument binding and helper contexts; no provider credentials or rg required. */
class WorkspaceSearchIntegrationTest {
    private val name = WorkspaceSearchRequest::class.java.simpleName
    private val arguments = """{"scope":"DOCUMENT_FILES","query":"needle"}"""
    private val answer = WorkspaceSearchResult(true, WorkspaceSearchScope.DOCUMENT_FILES, "/fixture",
        emptyList(), true, null, 1, 6, WorkspaceSearchSkips(), "fixture")

    @Test fun sharedRegistryExposesOneReadOnlyToolAndEnforcesAvailability() {
        val registry = ToolRegistry().also(::registerBuiltInAgentTools)
        var calls = 0
        val context = ToolExecutionContext(workspaceSearch = { calls++; assertEquals("needle", it.query); answer })
        for (face in ToolFace.entries) {
            val descriptor = registry.describe(context, face).filterIsInstance<JsonObject>()
                .single { it["name"]?.jsonPrimitive?.content == name }
            assertTrue(descriptor["inputSchema"].toString().contains("DOCUMENT_FILES"))
        }
        assertTrue(registry.metaOf(context, name)!!.readOnly)
        assertEquals(setOf(ToolEffect.FILESYSTEM_READ), registry.metaOf(context, name)!!.effects)
        assertEquals(answer, registry.executeByName(context, name, arguments))
        assertEquals(1, calls)
        for (blocked in listOf(ToolExecutionContext(), context.copy(withheld = setOf(name)))) {
            assertFalse(registry.isAvailable(blocked, name))
            assertFailsWith<IllegalStateException> { registry.executeByName(blocked, name, arguments) }
        }
        for (bad in listOf("""{"query":"needle"}""", """{"scope":0,"query":"needle"}"""))
            assertFailsWith<com.fasterxml.jackson.databind.JsonMappingException> { registry.executeByName(context, name, bad) }
        assertEquals(1, calls)
    }

    @Test fun allFourChatHelpersForwardTheProvider() {
        val language = LanguageSpec("text", { it }, { it })
        var calls = 0
        val provider: (WorkspaceSearchRequest) -> WorkspaceSearchResult = { calls++; answer }
        repeat(4) { mode ->
            val bridge = ScriptedLlmBridge(listOf(ScriptedLlmBridge.Step.Call(name, arguments), ScriptedLlmBridge.Step.Final("done")))
            val helpers = IdeChatHelpers(bridge, ephemeralInstructions = false)
            val state = bridge.startThread("search-test", "", "")
            when (mode) {
                0 -> helpers.chatFile(state, "", language, "find", workspaceSearch = provider)
                1 -> helpers.chatGeneral(state, "", language, "find", workspaceSearch = provider)
                2 -> helpers.chatInline(state, "", language, 0, "find", workspaceSearch = provider)
                3 -> helpers.chatError(state, "", language, "find", workspaceSearch = provider)
            }
            assertTrue(name in bridge.received.single().availableTools)
            assertEquals(mode + 1, calls)
        }
    }

    @Test fun backdoorUsesAttachedSavedDocumentWithoutSavingLiveSource() {
        val directory = Files.createTempDirectory("field-search-context")
        try {
            val source = directory.resolve("root_code_.kt")
            Files.writeString(source, "needle saved")
            val root = Box("root")
            val info = SessionInformation(filename = directory.toString(), documentName = "search-test", root = root, ephemeral = true)
            root.inside = info
            root.setPropertyRaw("code", "unsaved")
            val context = Ai4BackdoorTools.buildContext(info)
            val provider = assertNotNull(context.workspaceSearch)
            val result = provider(WorkspaceSearchRequest(WorkspaceSearchScope.DOCUMENT_FILES, "needle"))
            assertEquals("needle saved", result.matches.single().excerpt)
            assertEquals("unsaved", root.getPropertyRaw("code"))
            assertEquals("needle saved", Files.readString(source))
        } finally { directory.toFile().deleteRecursively() }
    }
}

package afield.ai.core

import afield.Box
import afield.SessionInformation
import afield.ai.agent.FieldGraphTools
import afield.ai.agent.InFieldAgentHarnessTest
import afield.ai.core.program.RUN_TOOL_PROGRAM
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@AgentToolMeta("probe", effects = [ToolEffect.READ_DOCUMENT])
data class ExpoDirectRequest(val unused: Int = 0)

@AgentToolMeta("deep", ToolExposure.DEFERRED, effects = [ToolEffect.READ_DOCUMENT])
data class ExpoDeferredRequest(val what: String = "")

@AgentToolMeta("bulk", ToolExposure.PROGRAM, effects = [ToolEffect.READ_DOCUMENT])
data class ExpoProgramRequest(val unused: Int = 0)

@AgentToolMeta("internal", ToolExposure.HIDDEN, effects = [])
data class ExpoHiddenRequest(val unused: Int = 0)

@AgentToolMeta("probe", effects = [ToolEffect.MUTATE_DOCUMENT])
data class ExpoMutateRequest(val unused: Int = 0)

data class ExpoUndeclaredRequest(val unused: Int = 0)

/**
 * Tool exposure (ToolMeta.kt): which face lists which tool, how a deferred tool is loaded, who may call a
 * hidden one, and the central refusal of a mutation on a read-only document.
 */
class ToolExposureTest {

    private val registry = ToolRegistry().also { tools ->
        registerBuiltInAgentTools(tools)
        listOf(ExpoDirectRequest::class.java, ExpoDeferredRequest::class.java, ExpoProgramRequest::class.java,
            ExpoHiddenRequest::class.java, ExpoMutateRequest::class.java, ExpoUndeclaredRequest::class.java).forEach { cls ->
            @Suppress("UNCHECKED_CAST")
            tools.register(cls as Class<Any>) { _, _ -> mapOf("ok" to true) }
        }
    }

    private fun names(ctx: ToolExecutionContext, face: ToolFace) =
        registry.describe(ctx, face).map { (it as JsonObject)["name"]!!.jsonPrimitive.content }.toSet()

    private val conversation = ToolExecutionContext(promoted = mutableSetOf())

    @Test
    fun eachFaceListsWhatItShould() {
        val model = names(conversation, ToolFace.MODEL)
        assertTrue("ExpoDirectRequest" in model)
        assertFalse("ExpoDeferredRequest" in model, "deferred until loaded")
        assertFalse("ExpoProgramRequest" in model, "program-only while programs are offered")
        assertFalse("ExpoHiddenRequest" in model)
        assertTrue("MacroRunRequest" !in model, "a core deferred tool")

        val mcp = names(conversation, ToolFace.MCP)
        assertTrue(setOf("ExpoDirectRequest", "ExpoDeferredRequest", "ExpoProgramRequest").all { it in mcp })
        assertFalse("ExpoHiddenRequest" in mcp)

        val program = names(conversation, ToolFace.PROGRAM)
        assertTrue("ExpoDeferredRequest" in program && "ExpoProgramRequest" in program && "ExpoHiddenRequest" !in program)
    }

    @Test
    fun aProgramOnlyToolIsDirectWhenThereAreNoPrograms() {
        val noPrograms = conversation.copy(withheld = setOf(RUN_TOOL_PROGRAM))
        assertTrue("ExpoProgramRequest" in names(noPrograms, ToolFace.MODEL))
    }

    @Test
    fun describeToolsLoadsADeferredNamespaceForTheConversation() {
        val ctx = ToolExecutionContext(promoted = mutableSetOf())
        assertTrue("DescribeToolsRequest" in names(ctx, ToolFace.MODEL))
        val result = registry.executeByName(ctx, "DescribeToolsRequest", """{"namespace":"deep"}""") as DescribeToolsResult
        assertTrue(result.success, result.message)
        assertEquals(listOf("ExpoDeferredRequest"), result.tools.map { it.name })
        assertTrue(result.tools.single().inputSchema.contains("what"))
        assertTrue("ExpoDeferredRequest" in names(ctx, ToolFace.MODEL), "loaded for the rest of the conversation")
        assertFalse("ExpoDeferredRequest" in names(ToolExecutionContext(promoted = mutableSetOf()), ToolFace.MODEL), "and only that one")

        // Everything deferred is loaded now, so the loader steps aside; a fresh conversation loads by name.
        assertFalse(registry.isAvailable(ctx, "DescribeToolsRequest"))
        val fresh = ToolExecutionContext(promoted = mutableSetOf())
        val byName = registry.executeByName(fresh, "DescribeToolsRequest", """{"names":["ExpoDeferred"]}""") as DescribeToolsResult
        assertEquals(listOf("ExpoDeferredRequest"), byName.tools.map { it.name })
    }

    @Test
    fun describeToolsIsNotOfferedWithoutAConversationToRememberIn() {
        assertFalse(registry.isAvailable(ToolExecutionContext(), "DescribeToolsRequest"))
    }

    @Test
    fun aHiddenToolAnswersOnlyTheBackdoor() {
        assertFailsWith<IllegalStateException> { registry.executeByName(conversation, "ExpoHiddenRequest", "{}") }
        assertFailsWith<IllegalStateException> { registry.executeByName(conversation, "ExpoHiddenRequest", "{}", CallOrigin.PROGRAM) }
        assertEquals(mapOf("ok" to true), registry.executeByName(conversation, "ExpoHiddenRequest", "{}", CallOrigin.BACKDOOR))
    }

    @Test
    fun aDeferredToolIsCallableEvenBeforeItIsLoaded() {
        assertEquals(mapOf("ok" to true), registry.executeByName(conversation, "ExpoDeferredRequest", "{}"))
    }

    @Test
    fun theMcpFaceCarriesEffectHints() {
        val mcp = registry.describe(conversation, ToolFace.MCP).map { it as JsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        val read = mcp.getValue("ExpoDirectRequest")
        assertEquals("true", read["annotations"]!!.let { (it as JsonObject)["readOnlyHint"]!!.jsonPrimitive.content })
        val write = mcp.getValue("ExpoMutateRequest")
        assertEquals("true", (write["annotations"] as JsonObject)["destructiveHint"]!!.jsonPrimitive.content)
        assertEquals("probe", (write["x-field"] as JsonObject)["namespace"]!!.jsonPrimitive.content)
        assertEquals("false", (mcp.getValue("ExpoUndeclaredRequest")["annotations"] as JsonObject)["readOnlyHint"]!!.jsonPrimitive.content,
            "an undeclared tool is never claimed read-only")
        assertFalse(registry.describe(conversation, ToolFace.MODEL).toString().contains("x-field"))
    }

    @Test
    fun theCatalogueNamesWhatTheSchemasDoNot() {
        val withMacros = conversation.copy(listMacros = { throw UnsupportedOperationException() },
            runMacro = { throw UnsupportedOperationException() })
        val catalogue = registry.catalogue(withMacros)
        assertTrue(catalogue.contains("deep: *ExpoDeferredRequest"), catalogue)
        assertTrue(catalogue.contains("macros:") && catalogue.contains("*MacroRunRequest!"), catalogue)
        assertTrue(catalogue.contains("bulk: °ExpoProgramRequest"), catalogue)
        assertFalse(catalogue.contains("ExpoHiddenRequest"))
        assertFalse(catalogue.contains("ExpoDirectRequest"), "a core DIRECT tool is already described by its schema")
        val loaded = conversation.copy(promoted = mutableSetOf("ExpoDeferredRequest"))
        assertTrue(registry.catalogue(loaded).contains("deep: ExpoDeferredRequest"))
    }

    private fun readOnlyDocument(): SessionInformation {
        val dir = Files.createTempDirectory("expo-readonly-").toFile().apply { deleteOnExit() }
        val root = Box("root").also { it.name = "root" }
        val info = SessionInformation(filename = dir.resolve("doc").absolutePath, documentName = "doc", root = root,
            ephemeral = true, readOnly = true)
        root.inside = info
        return info
    }

    @Test
    fun aMutationOnAReadOnlyDocumentIsRefusedCentrally() {
        val ctx = ToolExecutionContext(fieldGraph = FieldGraphTools(readOnlyDocument(), InFieldAgentHarnessTest.FakeHost(emptyMap())))
        listOf("NewBoxRequest" to """{"name":"X"}""", "ConnectBoxesRequest" to """{"parent":"a","child":"b"}""",
            "DeleteBoxRequest" to """{"box":"a"}""", "ExpoMutateRequest" to "{}").forEach { (tool, args) ->
            val failure = assertFailsWith<IllegalStateException>(tool) { registry.executeByName(ctx, tool, args) }
            assertTrue(failure.message!!.contains("read-only"), "$tool: ${failure.message}")
        }
        // Looking is still fine.
        registry.executeByName(ctx, "ListBoxesRequest", "{}")
    }
}

/**
 * Every core tool declares its effects, and the exposure classification is the one chosen from the tool-use
 * logs (dev_docs/ai/AI4_PI_LESSONS.md, "Exposure"): change it deliberately, here and there together.
 */
class ToolMetaPinTest {
    private val metas = ToolRegistry().also(::registerBuiltInAgentTools).coreMetas()

    @Test
    fun everyCoreToolDeclaresItsEffects() {
        val undeclared = metas.filterValues { ToolEffect.UNDECLARED in it.effects }.keys
        assertTrue(undeclared.isEmpty(), "core tools with undeclared effects: $undeclared")
    }

    @Test
    fun theDeferredToolsAreTheRarelyUsedOnes() {
        assertEquals(
            setOf(
                "CodeUseCallersForSourceLineRequest", "CodeUseEarlyDocumentSymbolsRequest", "CodeUseHotSymbolsRequest",
                "CodeUseReferencesForCodeRequest", "CodeUseRelatedSourceForCodeRequest", "CodeUseSemanticAnalyzeCodeRequest",
                "DocumentBoxNavigateRequest", "DocumentSourceExportRequest", "DocumentSourcePatchRequest", "ListDefinitionsRequest",
                "MacroListRequest", "MacroReadRequest", "MacroRunRequest", "MacroWriteRequest",
                "SemanticRetrievalRebuildRequest", "SemanticRetrievalStatusRequest",
            ),
            metas.filterValues { it.exposure == ToolExposure.DEFERRED }.keys
        )
        assertTrue(metas.values.none { it.exposure == ToolExposure.HIDDEN || it.exposure == ToolExposure.PROGRAM })
    }

    @Test
    fun theGraphToolsThatChangeTheDocumentSaySo() {
        val mutating = metas.filterKeys { it in afield.ai.agent.fieldGraphToolNames }.filterValues { it.mutates }.keys
        assertEquals(setOf("ConnectBoxesRequest", "DeleteBoxRequest", "DisconnectBoxesRequest", "NewBoxRequest",
            "PatchBoxTextRequest", "SetBoxPropertyRequest"), mutating)
    }
}

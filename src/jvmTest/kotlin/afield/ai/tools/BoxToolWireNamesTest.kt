package afield.ai.tools

import afield.ai.compat.Commentary
import afield.ai.compat.ToolExecutionContext
import afield.document.graph.Box
import afield.ai.completion.*
import afield.ai.conversation.*
import afield.ai.diagnostics.*
import afield.ai.models.*
import afield.ai.tools.*
import afield.runtime.languages.*
import com.openai.models.responses.ResponseCreateParams
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class BoxToolWireNamesTest {
    private fun tool(name: String) = BoxTool(name, "Read the design", "source", Box("source"), emptyList(), body = { "ok" })

    @Test fun toolSearchRequiresAnAvailableDeferredTool() {
        val registry = ToolRegistry()
        fun functionsOnly(ctx: ToolExecutionContext): Boolean {
            val request = ResponseCreateParams.builder().model("test").input("test")
            registry.addAllTo(request, ctx, deferLoading = true)
            return request.build().tools().orElse(emptyList()).all { it.function().isPresent }
        }
        assertTrue(functionsOnly(ToolExecutionContext()), "An empty registry must not request tool search")
        registry.register(Commentary::class.java) { _, _ -> "ok" }
        assertTrue(functionsOnly(ToolExecutionContext()), "Immediate-only tools must not request tool search")
        assertFalse(functionsOnly(ToolExecutionContext(dynamicTools = listOf(tool("geometry.measure")))))
    }

    @Test fun nullableInspectionOptionsRemainNullableInTheActualProviderSchema() {
        val registry = ToolRegistry()
        registry.register(afield.ai.agent.InspectGeometryViewRequest::class.java) { _, _ -> "ok" }
        val request = ResponseCreateParams.builder().model("test").input("test")
        registry.addAllTo(request, ToolExecutionContext(promoted = mutableSetOf("InspectGeometryViewRequest")))
        val schema = request.build().tools().orElseThrow().single().function().orElseThrow()
            .parameters().orElseThrow()._additionalProperties().getValue("properties")
            .convert(com.fasterxml.jackson.databind.JsonNode::class.java)!!
        for (name in listOf("pick", "region", "sectionNormal", "explode", "hidden", "xray", "xrayOpacity")) {
            assertTrue(schema.path(name).path("anyOf").any { it.path("type").asText() == "null" }, name)
        }
        assertEquals("string", schema.path("viewId").path("type").asText())
    }

    @Test fun blockingBoxToolCallsOnTheUiThreadFailBeforeDispatch() {
        val old = afield.runtime.lifecycle.Runloop.mainThread
        afield.runtime.lifecycle.Runloop.mainThread = Thread.currentThread()
        try {
            val error = assertFailsWith<IllegalStateException> {
                kotlinx.coroutines.runBlocking { tool("test").call("{}") }
            }
            assertTrue(error.message.orEmpty().contains("would deadlock"))
        } finally { afield.runtime.lifecycle.Runloop.mainThread = old }
    }

    @Test fun toolsAuthoredDuringAConversationAppearOnTheNextRoundAndRetireLive() {
        val doc = "tool-round-test-" + java.util.UUID.randomUUID()
        val owner = Box("owner")
        val ctx = ToolExecutionContext(focus=ToolFocus(doc,owner,"code"))
        val registry = ToolRegistry()
        try {
            assertTrue(registry.openAiDynamicToolsIn(ctx).isEmpty())
            val first=tool("geometry.measure")
            DocumentToolRegistry.register(doc,first)
            assertSame(first,registry.openAiDynamicToolsIn(ctx)[first.wireName])
            val updated=tool("geometry.measure")
            DocumentToolRegistry.register(doc,updated)
            assertSame(updated,registry.openAiDynamicToolsIn(ctx)[first.wireName])
            DocumentToolRegistry.remove(doc,first.name)
            assertTrue(registry.openAiDynamicToolsIn(ctx).isEmpty())
        } finally { DocumentToolRegistry.clearDocument(doc) }
    }

    @Test fun namespacedAndUnicodeNamesProduceDistinctBoundedAliases() {
        val names = listOf("house.inspect", "house_inspect", "house-inspect", "家.inspect", "x".repeat(300))
        val aliases = names.map(::boxToolWireName)
        assertEquals(aliases.size, aliases.toSet().size)
        aliases.forEach { assertTrue(it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) }
        assertEquals("house_inspect", aliases[1])
        assertEquals(aliases, names.map(::boxToolWireName))
        // A caller cannot take over another tool by authoring its generated alias.
        assertNotEquals(aliases[0], boxToolWireName(aliases[0]))
    }

    @Test fun providerDescriptorsAndDispatchUseTheSameAliasesWhileMcpKeepsAuthoredNames() {
        val authored = listOf(tool("house.inspect"), tool("house_inspect"))
        val ctx = ToolExecutionContext(dynamicTools = authored)
        val registry = ToolRegistry()
        val request = ResponseCreateParams.builder().model("test").input("test")
        registry.addAllTo(request, ctx)
        val functions = request.build().tools().orElseThrow().map { it.function().orElseThrow() }
        assertEquals(authored.map { it.wireName }.toSet(), functions.map { it.name() }.toSet())
        functions.forEach { function ->
            val resolved = registry.openAiDynamicToolsIn(ctx).getValue(function.name())
            assertSame(authored.single { it.wireName == function.name() }, resolved)
        }
        assertTrue(functions.single { it.name() == authored[0].wireName }.description().orElseThrow().contains("house.inspect"))
        assertEquals(authored.map { it.name }.toSet(), registry.describe(ctx, ToolFace.MCP).map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
    }

    @Test fun collisionIsReportedBeforeSendingARequest() {
        val ctx = ToolExecutionContext(dynamicTools = listOf(tool("house.inspect"), tool("house.inspect")))
        assertTrue(assertFailsWith<IllegalArgumentException> { ToolRegistry().openAiDynamicToolsIn(ctx) }.message.orEmpty().contains("house.inspect"))
    }
}

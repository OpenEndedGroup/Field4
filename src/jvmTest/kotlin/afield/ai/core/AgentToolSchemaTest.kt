package afield.ai.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A request class shaped like StartStudyBuildRequest was before its `request` became a JSON string. */
data class SchemaProbeMapRequest(val request: Map<String, String> = emptyMap())

/**
 * Every registered agent tool's request class survives openai-java's local strict-schema validation.
 *
 * [openAiTool] goes through `ResponseCreateParams.Builder.addTool(Class)`, which validates the derived
 * schema before anything is sent, and one failing tool fails the whole turn ("AI general failed: Local
 * validation failed for JSON schema derived from class ..."). A deferred tool only fails once a turn
 * loads its namespace, so a live chat can work for days before it meets the bad one. This test meets
 * them all at once, ungated.
 */
class AgentToolSchemaTest {

    @Test
    fun everyRegisteredToolHasAnOpenAiSchema() {
        val classes = ToolRegistry().also(::registerBuiltInAgentTools).handlerClasses()
        assertTrue("StartStudyBuildRequest" in classes, "the build tools should be registered: ${classes.keys}")
        val failures = classes.mapNotNull { (name, cls) ->
            @Suppress("UNCHECKED_CAST")
            runCatching { openAiTool(cls as Class<Any>) }.exceptionOrNull()?.let { "$name: ${it.message}" }
        }
        assertTrue(failures.isEmpty(), "tool request classes openai-java refuses (a Map field is the usual cause; " +
            "use a JSON string or a list of named pairs):\n" + failures.joinToString("\n"))
    }

    @Test
    fun theValidationThisTestReliesOnRefusesAMapField() {
        @Suppress("UNCHECKED_CAST")
        val refused = assertFailsWith<Exception> { openAiTool(SchemaProbeMapRequest::class.java as Class<Any>) }
        assertContains(refused.message.orEmpty(), "'properties' field is missing")
    }
}

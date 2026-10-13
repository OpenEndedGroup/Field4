package afield.ai.tools

import afield.ai.agent.StartStudyBuildRequest
import com.fasterxml.jackson.annotation.JsonClassDescription
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A request class shaped like StartStudyBuildRequest was before its `request` became a JSON string. */
@JsonClassDescription("A probe whose Map field OpenAI refuses.")
data class SchemaProbeMapRequest(val request: Map<String, String> = emptyMap())

/**
 * Every agent tool's request class survives openai-java's local strict-schema validation.
 *
 * [openAiTool] goes through `ResponseCreateParams.Builder.addTool(Class)`, which validates the derived
 * schema before anything is sent, and one failing tool fails the whole turn ("AI general failed: Local
 * validation failed for JSON schema derived from class ..."). A deferred tool only fails once a turn
 * loads its namespace, so a live chat can work for days before it meets the bad one. This test meets
 * them all at once, ungated.
 *
 * Two populations: the tools core registers, and every described data class in core's compiled output,
 * which reaches the tools that register themselves through [AgentToolExtensions] when their code first
 * runs (geometry files, line notes, the studies). `src/personal` is not on this classpath, and a box's
 * tools are compiled live; [AgentToolExtensions.register] refuses a bad class from either when it registers.
 */
class AgentToolSchemaTest {

    @Suppress("UNCHECKED_CAST")
    private fun refusal(cls: Class<*>): String? =
        runCatching { openAiTool(cls as Class<Any>) }.exceptionOrNull()?.let { "${cls.name}: ${it.message}" }

    private fun report(failures: List<String>) = "tool request classes openai-java refuses (a Map field is the usual " +
        "cause; use a JSON string or a list of a small data class):\n" + failures.joinToString("\n")

    @Test
    fun everyRegisteredToolHasAnOpenAiSchema() {
        val classes = ToolRegistry().also(::registerBuiltInAgentTools).handlerClasses()
        assertTrue("StartStudyBuildRequest" in classes, "the build tools should be registered: ${classes.keys}")
        val failures = classes.values.mapNotNull(::refusal)
        assertTrue(failures.isEmpty(), report(failures))
    }

    @Test
    fun everyDescribedRequestClassInCoreHasAnOpenAiSchema() {
        val root = File(StartStudyBuildRequest::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(root.isDirectory, "expected core's compiled classes as a directory, found $root")
        val loader = javaClass.classLoader
        val described = root.walk()
            .filter { it.isFile && it.name.endsWith(".class") && !Regex("\\$[0-9]|\\\$WhenMappings|-").containsMatchIn(it.name) }
            .mapNotNull { f ->
                val name = f.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
                runCatching { Class.forName(name, false, loader) }.getOrNull()
            }
            .filter { cls ->
                (cls.isAnnotationPresent(JsonClassDescription::class.java) || cls.isAnnotationPresent(AgentToolMeta::class.java)) &&
                    runCatching { cls.kotlin.isData }.getOrDefault(false)
            }
            .toList()
        // Self-registering tools this sweep exists for, so an empty or misdirected walk cannot pass.
        val names = described.map { it.simpleName }.toSet()
        listOf("StartStudyBuildRequest", "ListGeometryFiles", "ReadLineNotes", "WhyDoesThisResolve", "InspectRectSLStudy")
            .forEach { assertTrue(it in names, "the sweep should reach $it; it found ${described.size} classes under $root") }
        val failures = described.mapNotNull(::refusal)
        assertTrue(failures.isEmpty(), report(failures))
    }

    @Test
    fun theValidationThisTestReliesOnRefusesAMapField() {
        assertContains(refusal(SchemaProbeMapRequest::class.java).orEmpty(), "'properties' field is missing")
    }

    @Test
    fun anExtensionToolOpenAiWouldRefuseIsRefusedWhenItRegisters() {
        val refused = assertFailsWith<IllegalArgumentException> {
            AgentToolExtensions.register(SchemaProbeMapRequest::class.java) { _, _ -> mapOf("ok" to true) }
        }
        assertContains(refused.message.orEmpty(), "SchemaProbeMapRequest")
        assertContains(refused.message.orEmpty(), "A Map field is the usual cause")
        assertFalse("SchemaProbeMapRequest" in AgentToolExtensions.registeredNames())
    }
}

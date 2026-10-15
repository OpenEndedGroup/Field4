package afield.ai.tools

import afield.ai.tools.ToolSchema
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * The study tools' names and argument schemas, pinned.
 *
 * Models, recorded conversations and the conversation demos all call these tools by name with these
 * arguments, so a refactor of the study machinery (architecture review 3, 2026-09-29) must leave them
 * exactly as they were. Both faces are pinned: the schema core's own reflection gives the MCP face
 * ([ToolSchema.forClass]) and the one the OpenAI SDK derives from the class for the chat path
 * (`StructuredOutputsKt.extractSchema`, what `ResponseCreateParams.Builder.addTool(Class)` uses).
 *
 * A deliberate change regenerates the fixture: the failing run writes what it saw to
 * `build/study-tool-schemas.actual.json`; review it and copy it over
 * `src/jvmTest/resources/afield/ai/study_tool_schemas.json`.
 */
class StudyToolSchemaPinTest {
    private val classes: List<Class<*>> = listOf(
        afield.math.functions.FunctionStudyGuide::class.java, afield.math.functions.InspectFunctionStudy::class.java,
        afield.math.functions.EditFunctionStudy::class.java, afield.math.functions.PatchFunctionStudy::class.java,
        afield.math.functions.ExportFunctionStudy::class.java, afield.math.functions.ObserveFunctionStudy::class.java,
        afield.math.functions.SampleFunctionStudy::class.java, afield.math.functions.SelectFunctionStudy::class.java,
        afield.math.functions.CompareFunctionStudy::class.java,
        afield.lang.stacksl.study.StackSLStudyGuide::class.java, afield.lang.stacksl.study.InspectStackSLStudy::class.java,
        afield.lang.stacksl.study.EditStackSLStudy::class.java, afield.lang.stacksl.study.PatchStackSLStudy::class.java,
        afield.lang.stacksl.study.SetStackSLNumbers::class.java, afield.lang.stacksl.study.ObserveStackSLStudy::class.java,
        afield.lang.stacksl.study.TraceStackSLStudy::class.java, afield.lang.stacksl.study.KnobsStackSLStudy::class.java,
        afield.lang.stacksl.study.SweepStackSLNumber::class.java, afield.lang.stacksl.study.TryStackSLVariants::class.java,
        afield.lang.stacksl.study.AdoptStackSLVariant::class.java, afield.lang.stacksl.study.RestoreStackSLRevision::class.java,
        afield.lang.stacksl.study.CompareStackSLStudy::class.java, afield.lang.stacksl.study.ExportStackSLStudy::class.java,
        afield.lang.rectsl.study.RectSLStudyGuide::class.java, afield.lang.rectsl.study.InspectRectSLStudy::class.java,
        afield.lang.rectsl.study.EditRectSLStudy::class.java, afield.lang.rectsl.study.PatchRectSLStudy::class.java,
        afield.lang.rectsl.study.SetRectSLNumbers::class.java, afield.lang.rectsl.study.ObserveRectSLStudy::class.java,
        afield.lang.rectsl.study.TimelineRectSLStudy::class.java, afield.lang.rectsl.study.TraceRectSLStudy::class.java,
        afield.lang.rectsl.study.KnobsRectSLStudy::class.java, afield.lang.rectsl.study.SweepRectSLNumber::class.java,
        afield.lang.rectsl.study.TryRectSLVariants::class.java, afield.lang.rectsl.study.AdoptRectSLVariant::class.java,
        afield.lang.rectsl.study.RestoreRectSLRevision::class.java, afield.lang.rectsl.study.CompareRectSLStudy::class.java,
        afield.lang.rectsl.study.ExportRectSLStudy::class.java,
        afield.lang.stacksl.study.StackAutoSlotGuide::class.java, afield.lang.stacksl.study.InspectStackAutoSlot::class.java,
        afield.lang.stacksl.study.EditStackAutoSlot::class.java, afield.lang.stacksl.study.PatchStackAutoSlot::class.java,
        afield.lang.stacksl.study.SetStackAutoNumbers::class.java, afield.lang.stacksl.study.ObserveStackAutoSlot::class.java,
        afield.lang.stacksl.study.SweepStackAutoNumber::class.java, afield.lang.stacksl.study.RestoreStackAutoRevision::class.java,
        afield.lang.stacksl.study.CompareStackAutoSlot::class.java, afield.lang.stacksl.study.TryStackAutoVariants::class.java,
        afield.lang.stacksl.study.AdoptStackAutoVariant::class.java,
    )

    private val mapper = ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(SerializationFeature.INDENT_OUTPUT)

    // Internal in Kotlin, public in the bytecode: the exact derivation addTool(Class) goes through.
    private val extractSchema = Class.forName("com.openai.core.StructuredOutputsKt").getMethod("extractSchema", Class::class.java)
    private fun openAiSchema(cls: Class<*>): ObjectNode = extractSchema.invoke(null, cls) as ObjectNode

    private fun actual(): ObjectNode {
        val root = mapper.createObjectNode()
        classes.sortedBy { it.simpleName }.forEach { cls ->
            val entry = root.putObject(cls.simpleName)
            entry.put("description", cls.kotlin.annotations.filterIsInstance<JsonClassDescription>().firstOrNull()?.value)
            entry.set<ObjectNode>("mcp", mapper.readTree(ToolSchema.forClass(cls).toString()))
            entry.set<ObjectNode>("openai", openAiSchema(cls))
        }
        return root
    }

    @Test
    fun studyToolNamesAndSchemasAreUnchanged() {
        val seen = mapper.writeValueAsString(mapper.treeToValue(actual(), Any::class.java))
        val expected = javaClass.getResourceAsStream("/afield/ai/study_tool_schemas.json")?.bufferedReader()?.use { it.readText() }
        if (expected == null || mapper.readTree(expected) != mapper.readTree(seen)) {
            val out = File("build/study-tool-schemas.actual.json").absoluteFile
            out.parentFile.mkdirs(); out.writeText(seen)
            fail("Study tool names or schemas changed (or the pin is missing); what this run saw is in $out. " +
                "If the change is deliberate, review it and copy it over src/jvmTest/resources/afield/ai/study_tool_schemas.json.")
        }
    }
}

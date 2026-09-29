package afield.ai

import afield.ai.core.ToolSchema
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
        afield.stdlib.FunctionStudyGuide::class.java, afield.stdlib.InspectFunctionStudy::class.java,
        afield.stdlib.EditFunctionStudy::class.java, afield.stdlib.PatchFunctionStudy::class.java,
        afield.stdlib.ExportFunctionStudy::class.java, afield.stdlib.ObserveFunctionStudy::class.java,
        afield.stdlib.SampleFunctionStudy::class.java, afield.stdlib.SelectFunctionStudy::class.java,
        afield.stdlib.CompareFunctionStudy::class.java,
        afield.stacksl.study.StackSLStudyGuide::class.java, afield.stacksl.study.InspectStackSLStudy::class.java,
        afield.stacksl.study.EditStackSLStudy::class.java, afield.stacksl.study.PatchStackSLStudy::class.java,
        afield.stacksl.study.SetStackSLNumbers::class.java, afield.stacksl.study.ObserveStackSLStudy::class.java,
        afield.stacksl.study.TraceStackSLStudy::class.java, afield.stacksl.study.KnobsStackSLStudy::class.java,
        afield.stacksl.study.SweepStackSLNumber::class.java, afield.stacksl.study.TryStackSLVariants::class.java,
        afield.stacksl.study.AdoptStackSLVariant::class.java, afield.stacksl.study.RestoreStackSLRevision::class.java,
        afield.stacksl.study.CompareStackSLStudy::class.java, afield.stacksl.study.ExportStackSLStudy::class.java,
        afield.rectsl.study.RectSLStudyGuide::class.java, afield.rectsl.study.InspectRectSLStudy::class.java,
        afield.rectsl.study.EditRectSLStudy::class.java, afield.rectsl.study.PatchRectSLStudy::class.java,
        afield.rectsl.study.SetRectSLNumbers::class.java, afield.rectsl.study.ObserveRectSLStudy::class.java,
        afield.rectsl.study.TimelineRectSLStudy::class.java, afield.rectsl.study.TraceRectSLStudy::class.java,
        afield.rectsl.study.KnobsRectSLStudy::class.java, afield.rectsl.study.SweepRectSLNumber::class.java,
        afield.rectsl.study.TryRectSLVariants::class.java, afield.rectsl.study.AdoptRectSLVariant::class.java,
        afield.rectsl.study.RestoreRectSLRevision::class.java, afield.rectsl.study.CompareRectSLStudy::class.java,
        afield.rectsl.study.ExportRectSLStudy::class.java,
        afield.stacksl.study.StackAutoSlotGuide::class.java, afield.stacksl.study.InspectStackAutoSlot::class.java,
        afield.stacksl.study.EditStackAutoSlot::class.java, afield.stacksl.study.PatchStackAutoSlot::class.java,
        afield.stacksl.study.SetStackAutoNumbers::class.java, afield.stacksl.study.ObserveStackAutoSlot::class.java,
        afield.stacksl.study.SweepStackAutoNumber::class.java, afield.stacksl.study.RestoreStackAutoRevision::class.java,
        afield.stacksl.study.CompareStackAutoSlot::class.java, afield.stacksl.study.TryStackAutoVariants::class.java,
        afield.stacksl.study.AdoptStackAutoVariant::class.java,
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

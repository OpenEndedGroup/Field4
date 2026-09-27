package afield.examples.stacksl

/*
 * A document adding an AI4 tool of its own to its StackSL programs, from a box.
 *
 * `public/docs/stacksl_conversation.md` ("Adding a tool of your own") shows the part of the snippet
 * between `// example:start` and `// example:end`; `tools/package_stacksl_conversation.py` copies it
 * from here, so the page shows the code this smoke runs. The rest of the snippet is the check: the
 * tool answers a conversation focused on this box's program and is not offered to any other.
 */

/*
FIELD_TEST
id: stacksl.study.document-tool
runner: backdoor-box
tags: [stacksl, study, ai4, tools]
timeoutSec: 180
boxName: ColourTool
w: 460
h: 200
waitSec: 0.5
expect:
  outputContains:
    - "colours [#f3bc7c]"
    - "offered elsewhere false"
    - "retired true"
*/
private val DOCUMENT_TOOL_SNIPPET = """
// example:start
import afield.ai.core.AgentToolExtensions
import afield.stacksl.study.StackSLStudyShader
import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("The colours a StackSL program types, as the window shows them.")
data class ColoursOfStackSLStudy(val revision: String = "current")

val document = inside.filename
AgentToolExtensions.register(ColoursOfStackSLStudy::class.java,
    { it.focus?.document == document && it.focus?.property == "stackSL" }, scope = document) { ctx, _ ->
    val program = ctx.focus!!.box.getPropertyRaw("stackSL", "") as String
    StackSLStudyShader.colourKnobs(program).map { it.hex }
}
// example:end

thisBox.setPropertyRaw("stackSL", "[0.9,0.5,0.2] uv x *")
val focused = afield.ai.core.ToolExecutionContext(focus = afield.ai.core.ToolFocus(document, thisBox, "stackSL"))
println("colours " + AgentToolExtensions.execute(focused, "ColoursOfStackSLStudy", "{}"))
println("offered elsewhere " + AgentToolExtensions.isAvailable(afield.ai.core.ToolExecutionContext(), "ColoursOfStackSLStudy"))
AgentToolExtensions.unregister("ColoursOfStackSLStudy", scope = document)
println("retired " + !AgentToolExtensions.isAvailable(focused, "ColoursOfStackSLStudy"))
""".trimIndent()

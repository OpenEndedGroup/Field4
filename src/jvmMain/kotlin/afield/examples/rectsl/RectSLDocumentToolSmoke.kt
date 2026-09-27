package afield.examples.rectsl

/*
 * A document adding an AI4 tool of its own to its RectSL scenes, from a box.
 *
 * `public/docs/rectsl_conversation.md` ("Adding a tool of your own") shows the part of the snippet
 * between `// example:start` and `// example:end`; `tools/package_rectsl_conversation.py` copies it
 * from here, so the page shows the code this smoke runs. The rest of the snippet is the check: the
 * tool answers a conversation focused on this box's scene and is not offered to any other.
 */

/*
FIELD_TEST
id: rectsl.study.document-tool
runner: backdoor-box
tags: [rectsl, study, ai4, tools]
timeoutSec: 180
boxName: RectanglesTool
w: 460
h: 200
waitSec: 0.5
expect:
  outputContains:
    - "rectangles {1=[sun], 2=[moon]}"
    - "offered elsewhere false"
    - "retired true"
*/
private val DOCUMENT_TOOL_SNIPPET = """
// example:start
import afield.ai.core.AgentToolExtensions
import afield.rectsl.compileRectSLWithSource
import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("The rectangles a RectSL scene makes, by the line that makes them.")
data class RectanglesOfRectSLStudy(val revision: String = "current")

val document = inside.filename
AgentToolExtensions.register(RectanglesOfRectSLStudy::class.java,
    { it.focus?.document == document && it.focus?.property == "rectSL" }, scope = document) { ctx, _ ->
    val scene = ctx.focus!!.box.getPropertyRaw("rectSL", "") as String
    compileRectSLWithSource(scene).origins.entries.groupBy({ it.value.line }, { it.key }).toSortedMap()
}
// example:end

thisBox.setPropertyRaw("rectSL", "\"sun\" rect 0 2 ->xy 2 ->square ;\n\"moon\" rect 5 -2 ->xy 1 ->square ;")
val focused = afield.ai.core.ToolExecutionContext(focus = afield.ai.core.ToolFocus(document, thisBox, "rectSL"))
println("rectangles " + AgentToolExtensions.execute(focused, "RectanglesOfRectSLStudy", "{}"))
println("offered elsewhere " + AgentToolExtensions.isAvailable(afield.ai.core.ToolExecutionContext(), "RectanglesOfRectSLStudy"))
AgentToolExtensions.unregister("RectanglesOfRectSLStudy", scope = document)
println("retired " + !AgentToolExtensions.isAvailable(focused, "RectanglesOfRectSLStudy"))
""".trimIndent()

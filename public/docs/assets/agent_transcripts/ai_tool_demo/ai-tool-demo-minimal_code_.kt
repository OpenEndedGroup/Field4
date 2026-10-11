// Give Field's AI a tool of its own — the whole mechanism, in one box, and with no API key.
//
// A tool is an ordinary Kotlin class (which becomes the schema the model reads) plus an
// ordinary Kotlin lambda (which becomes what happens when the model calls it). Register the
// pair through afield.ai.core.AgentToolExtensions and the model can call it by name.
//
// Nothing in Field has to be edited to add one: the registration goes the other way.
//
//   public/docs/aiFeatures.md      — "Give the AI a tool of your own"
//   dev_docs/ai/AGENT_TOOL_EXTENSIONS.md — the design, and what is still not covered

import afield.ai.core.AgentToolExtensions
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolExecutionContext
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// ---------------------------------------------------------------- 1. the request class

// This class IS the schema. Its simple name is the tool's name, and these annotations are
// the only prompt the tool gets — write them for a reader who cannot see the code.
//
//   a parameter with no default  -> "required"
//   a parameter with a default   -> optional, and Kotlin's default is what happens
//   an enum                      -> {"type": "string", "enum": [...]}

@JsonClassDescription("Measure a rectangular room and say whether it meets a minimum floor area.")
data class MeasureRoom(
    @JsonPropertyDescription("The room's width in metres.")
    val width: Double,
    @JsonPropertyDescription("The room's depth in metres.")
    val depth: Double,
    @JsonPropertyDescription("The smallest floor area, in square metres, that counts as acceptable.")
    val minimumArea: Double = 11.0,
)

// ---------------------------------------------------------------- 2. the result

// SummarisesForToolLog is worth implementing on every result. Without it, Field's tool I/O
// log records only the class name, which cannot tell a turn where the model got what it
// needed from one where it got an error and gave up.

data class MeasureRoomResult(
    val area: Double,
    val perimeter: Double,
    val acceptable: Boolean,
    val note: String,
) : SummarisesForToolLog {
    override fun toolLogSummary() = "area=%.2f acceptable=%s".format(area, acceptable)
}

// ---------------------------------------------------------------- 3. register it

// Idempotent by tool NAME, so re-running this box replaces the tool rather than adding a
// second copy of it — and reaches an AI Chat that is already open, on its next turn.
// There is no order you have to follow.

AgentToolExtensions.register(MeasureRoom::class.java) { _, request ->
    val area = request.width * request.depth
    MeasureRoomResult(
        area = area,
        perimeter = 2 * (request.width + request.depth),
        acceptable = area >= request.minimumArea,
        note = if (area >= request.minimumArea)
            "%.2f m2 clears the %.1f m2 minimum by %.2f.".format(area, request.minimumArea, area - request.minimumArea)
        else
            "%.2f m2 is %.2f short of the %.1f m2 minimum.".format(area, request.minimumArea - area, request.minimumArea),
    )
}

// ---------------------------------------------------------------- 4. see it the way a model does

// execute() is the same entry point a model's tool call goes through: a name and a JSON
// string. So you can drive your own tool from a box, with no API key and no model, and see
// exactly what would come back.

val ctx = ToolExecutionContext()

println("--- the schema the model is shown ---")
println(AgentToolExtensions.describe(ctx).single { it.contains("\"MeasureRoom\"") })
println()

println("--- a call the model might make ---")
val big = AgentToolExtensions.execute(ctx, "MeasureRoom", """{"width": 4.2, "depth": 3.1}""") as MeasureRoomResult
println(big.note)
println("tool log line: " + big.toolLogSummary())
println()

// minimumArea was omitted above, so Kotlin's default (11.0) applied. Supply it and the
// same room becomes unacceptable — which is the whole point of testing the JSON path
// rather than calling the handler directly.
val strict = AgentToolExtensions.execute(
    ctx, "MeasureRoom", """{"width": 4.2, "depth": 3.1, "minimumArea": 15.0}"""
) as MeasureRoomResult
println(strict.note)
println()

println("registered right now: " + AgentToolExtensions.availableToolNames(ctx))
println()
println("Now select any box, press cmd-4, and ask: \"is a 4.2 by 3.1 metre room big enough?\"")
println("Edit the handler above, re-run this box, and ask again — the change lands immediately.")

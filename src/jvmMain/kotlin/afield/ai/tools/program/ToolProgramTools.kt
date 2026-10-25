package afield.ai.tools.program

import afield.ai.tools.CarriesToolImages
import afield.ai.models.LlmToolTranscriptEntry
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolImage
import afield.ai.tools.ToolRegistry
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.JsonNode

/**
 * # Tool programs: the model writes the loop, Field runs it
 *
 * A model working through tools one call per round pays for every round twice: a request carrying
 * the whole conversation so far, and a result that lands in that conversation for good. For "run
 * each of these twelve boxes and tell me which ones fail" that is twelve rounds and twelve results
 * the model reads in full, when what it needed was three names.
 *
 * [RunToolProgramRequest] is one tool whose argument is a short JavaScript program. The program can
 * call every other tool the conversation has, as a function — `tools.RunBox({box: "Cell03"})` — and
 * only what it returns, or prints with `text(...)`, goes back to the model, plus a one-line record per
 * call. The intermediate results stay in the program. The idea is Pi's "codemode"
 * (earendil-works/pi); the mapping onto Field is in `dev_docs/ai/AI4_PI_LESSONS.md`.
 *
 * ## What a program can reach
 *
 * Only the tools, and only through one string-in, string-out bridge: [ToolProgramRunner] builds a
 * GraalJS context with no host access, no IO, no threads, no processes, no environment and a
 * statement limit, so there is no `Java.type`, no `require`, no `fetch` and no timers. A tool call
 * from a program goes through [ToolRegistry.executeByName] — the same dispatcher a model's own call
 * goes through, with the same gates — so a program can do nothing the conversation could not do one
 * call at a time, and every edit it makes is recorded exactly as the direct call would record it.
 *
 * A program is not a transaction. One that fails halfway leaves its earlier edits in place; they are
 * recorded and undoable like any other agent edit, but nothing rolls them back.
 *
 * ## Why JavaScript and not the macro YAML
 *
 * What makes this pay is the middle of the program — filter, loop, branch on a result — and the
 * OperationTranscript YAML has no data flow at all: it is the durable, reversible record of
 * edits, and the edits a program makes still land there. Kotlin would be the house language, but every
 * program would pay a K2 compile, and box code can reach the whole JVM. JS is what models write
 * fluently as glue, and GraalJS is already on the classpath.
 */

/** The `RunToolProgram` tool: a JavaScript program that calls the conversation's other tools and returns one result. */
@JsonClassDescription(
    "Run a short JavaScript program that calls this conversation's other tools as functions and returns " +
        "one combined result. Use it when a step means four or more similar tool calls (every box, every value of a sweep); a single call is cheaper made directly. tools.NewBox({name, code}), tools.RunBox({box}), tools.ListBoxes({}) ... — the " +
        "same tools and arguments as direct calls, with the Request suffix dropped (box-authored tools by their " +
        "own name, tools[\"rectsl.run\"]({...}), or their listed name, tools.rectsl_run). Calls are synchronous. " +
        "A call that fails (could not run, or returned success:false / error:true) throws a ToolFailure whose " +
        ".result is the failed result, so later steps do not run unless you catch it; tools.X.raw(args) returns " +
        "the result without throwing, to inspect failures on purpose. A tool that ran but reports a verdict " +
        "(qualified:false, held, warnings) has not failed: stop on it with check(condition, message). " +
        "progress(...) shows a line to the person now; only what the program returns and what it passes to " +
        "text(...) comes back to you, with a one-line record of each call. show(result) forwards that result's " +
        "images; describe(\"RunBoxRequest\") returns a tool's input schema. Not a transaction: edits made before " +
        "a failure stay. No files, network, timers or Java."
)
@afield.ai.tools.AgentToolMeta("program", effects = [])
data class RunToolProgramRequest(
    @JsonPropertyDescription(
        "The body of an async JavaScript function (ES2024). `return` a value (it is JSON-serialised); " +
            "text(...) appends a line to the output."
    )
    val code: String,
    @JsonPropertyDescription("One line saying what the program does; shown in the panel and the tool log.")
    val purpose: String = "",
    @JsonPropertyDescription("Seconds before the program is stopped; default 60, at most 300. A tool call already running finishes first.")
    val timeoutSeconds: Double = 60.0,
    @JsonPropertyDescription("The most tool calls the program may make; default 40, at most 200.")
    val maxCalls: Int = 40,
)

/** One tool call a program made, as the model sees it: enough to tell what happened, never the result itself. */
data class ProgramToolCall(
    val n: Int,
    val tool: String,
    val args: String,
    val ok: Boolean,
    val elapsedMs: Long,
    val summary: String,
    val error: String? = null,
    /** Set when the result was too large to keep in the transcript: read it whole with [ReadToolEvidenceRequest]. */
    val evidence: String? = null,
)

/**
 * One nested call in full, for transcripts and logs rather than the model: the bridges expand these into
 * child [afield.ai.models.LlmToolTranscriptEntry]s with ids `<programCallId>/<n>`, so the tool I/O log, the
 * eval's tool-call records and `AgentLessons`' misuse count all see what the program did.
 */
data class NestedToolCall(
    val n: Int,
    val tool: String,
    val argumentsJson: String,
    val resultJson: String,
    val summary: String,
    val ok: Boolean,
    val elapsedMs: Long,
    /** Size and SHA-256 of the complete result, whether or not [resultJson] holds all of it. */
    val resultBytes: Int = 0,
    val resultSha256: String = "",
    /** Where the complete result is when [resultJson] is only a stub ([ToolEvidence]). */
    val evidence: String? = null,
)

/** A tool result made of other tool calls. Implemented by [RunToolProgramResult]; read by the bridges. */
interface CarriesNestedToolCalls {
    @get:JsonIgnore
    val nestedCalls: List<NestedToolCall>
}

data class RunToolProgramResult(
    val success: Boolean,
    /** `ok`, `tool-failed`, `check-failed`, `runtime`, `syntax`, `timeout`, `statement-limit`, `call-cap` or `unsettled`. */
    val stage: String,
    val message: String,
    /** What the program passed to text(...), one line per call, at most [ToolProgramRunner.MAX_OUTPUT_CHARS]. */
    val output: String,
    /** What the program returned, or `{"truncated": N, "preview": ...}` when it was too large. */
    val returned: JsonNode?,
    val calls: List<ProgramToolCall>,
    val totalCalls: Int,
    val failedCalls: Int,
    val elapsedMs: Long,
    /** True when output, the returned value, the call list or a nested result was cut to fit. */
    val truncated: Boolean,
    val imagesShown: Int,
    @get:JsonIgnore override val toolImages: List<ToolImage> = emptyList(),
    @get:JsonIgnore override val nestedCalls: List<NestedToolCall> = emptyList(),
    /** Nested results kept whole in [ToolEvidence] because they were too large for the transcript. */
    val nestedClipped: Int = 0,
) : CarriesToolImages, SummarisesForToolLog, CarriesNestedToolCalls {
    override fun toolLogSummary(): String =
        "program stage=$stage calls=$totalCalls failed=$failedCalls output=${output.length} chars " +
            "images=$imagesShown${if (truncated) " truncated" else ""} ($elapsedMs ms)"
}

const val RUN_TOOL_PROGRAM = "RunToolProgramRequest"

/**
 * Tools a program may not call: itself (no nesting), and `Commentary`, which the bridges read off the
 * model's own call arguments — inside a program it would vanish, so programs use `text(...)` instead.
 * `AwaitStudyBuildsRequest` ends the turn, which only a call the model makes itself can do.
 */
internal val NOT_CALLABLE_FROM_PROGRAMS = setOf(RUN_TOOL_PROGRAM, "Commentary", "DescribeToolsRequest", "AwaitStudyBuildsRequest")

/** `AFIELD_AI4_TOOL_PROGRAMS=off` (or `-Dafield.ai4.toolPrograms=off`) removes the tool everywhere. */
internal object ToolPrograms {
    val enabled: Boolean
        get() {
            val raw = System.getProperty("afield.ai4.toolPrograms") ?: System.getenv("AFIELD_AI4_TOOL_PROGRAMS")
            return raw == null || raw.trim().lowercase() !in setOf("0", "off", "false", "no")
        }
}

internal fun registerToolProgramTools(tools: ToolRegistry) {
    tools.register(RunToolProgramRequest::class.java, { ToolPrograms.enabled }) { ctx, req ->
        ToolProgramRunner(tools).run(ctx, req)
    }
    tools.register(ReadToolEvidenceRequest::class.java, { ToolPrograms.enabled }) { _, req -> readToolEvidence(req) }
}

/** Instruction text for the tool, added to the dynamic instructions when it is offered. */
internal fun toolProgramGuidance(): String = """
- RunToolProgramRequest: when a step means four or more similar tool calls — every box, every value of a
  sweep, a list of edits — write it as one short JavaScript program instead. `tools.X(args)` calls tool
  X (the Request suffix dropped) synchronously and returns its result object. A failed call (success:false,
  error:true, or refused) throws a ToolFailure with `.result`, so a failed check stops the edits after it;
  use `tools.X.raw(args)` when you want to look at failures without stopping. A result that ran but reports
  a verdict (qualified:false, a hold, warnings) does not throw: `check(r.qualified, "why")` stops there.
  `progress("Checking 12 windows")` tells the person what is happening; return the answer (or text(...)
  lines). Default limits: 40 calls, 60 s, never retried. For long jobs, start them and return the job
  handle rather than waiting inside the program. Edits are applied and recorded as they happen; a program
  that stops halfway does not undo them. A call record with `evidence` has its full result there
  (ReadToolEvidenceRequest).
""".trimIndent()

/** Exposed for [ToolExecutionContext] users that want to know whether a program can run under [ctx]. */
internal fun ToolRegistry.offersToolPrograms(ctx: ToolExecutionContext): Boolean = isAvailable(ctx, RUN_TOOL_PROGRAM)

/**
 * The child transcript entries for a result made of nested calls, ids `<parentCallId>/<n>`. Every bridge
 * appends these straight after the parent's own entry, so anything reading a turn's transcript — the eval's
 * tool records, `AgentLessons`' misuse count, the proposal archive — sees each call a program made.
 */
internal fun nestedTranscriptEntries(parentCallId: String, result: Any): List<LlmToolTranscriptEntry> =
    (result as? CarriesNestedToolCalls)?.nestedCalls.orEmpty().map { call ->
        LlmToolTranscriptEntry(
            name = call.tool,
            callId = "$parentCallId/${call.n}",
            argumentsJson = call.argumentsJson,
            resultSummary = call.summary,
            resultJson = call.resultJson,
            elapsedMs = call.elapsedMs,
            parentCallId = parentCallId,
        )
    }

/** The tool I/O log's `tool_call` + `tool_result` pair for each nested call, marked `depth: 1`. */
internal fun nestedToolIoEvents(round: Int, parentCallId: String, result: Any): List<Pair<String, Map<String, Any?>>> =
    (result as? CarriesNestedToolCalls)?.nestedCalls.orEmpty().flatMap { call ->
        val id = "$parentCallId/${call.n}"
        listOf(
            "tool_call" to mapOf(
                "round" to round, "toolName" to call.tool, "callId" to id, "parentCallId" to parentCallId,
                "depth" to 1, "arguments" to call.argumentsJson
            ),
            "tool_result" to mapOf(
                "round" to round, "toolName" to call.tool, "callId" to id, "parentCallId" to parentCallId,
                "depth" to 1, "summary" to call.summary, "elapsedMs" to call.elapsedMs, "result" to call.resultJson,
                "resultBytes" to call.resultBytes, "resultSha256" to call.resultSha256, "evidence" to call.evidence,
            ),
        )
    }

/**
 * A program's calls as the body of its "finished" line in the chat panel — Pi's nested call trace, in the
 * place Field already shows tool progress: one line per call with its time and outcome, then how it ended.
 * Null for a result that is not a program, so other tools keep their own body.
 */
internal fun nestedTraceMarkdown(result: Any): String? {
    val program = result as? RunToolProgramResult ?: return null
    // A markdown list, one call per item, the tool and its arguments as code spans so a `*` in box code
    // stays a `*` (the panel renders this as markdown).
    fun code(text: String) = "`" + text.replace("`", "'") + "`"
    return buildString {
        program.calls.forEach { call ->
            val args = call.args.replace("\n", " ").take(80)
            append("- ").append(if (call.ok) "✓ " else "✗ ").append(code(call.tool.removeSuffix("Request"))).append(' ').append(code(args))
            append(" — ").append(call.elapsedMs).append(" ms")
            if (!call.ok && call.error != null) append(" — ").append(call.error.take(120))
            if (call.evidence != null) append(" — full result ").append(code(call.evidence))
            append('\n')
        }
        if (program.totalCalls > program.calls.size) append("- … ").append(program.totalCalls - program.calls.size).append(" more\n")
        append("\nprogram ").append(program.stage).append(": ").append(program.totalCalls).append(" call(s), ")
            .append(program.failedCalls).append(" failed, ").append(program.elapsedMs).append(" ms")
        if (program.message.isNotBlank()) append(" — ").append(program.message.take(200))
    }
}

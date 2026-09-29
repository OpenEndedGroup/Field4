package afield.ai.agent

import afield.Box
import afield.BoxOutputLog
import afield.ExecutionReports
import afield.SessionInformation
import afield.ai.core.Ai4BackdoorTools
import afield.ai.core.LlmBridge
import afield.ai.core.LlmSendResult
import afield.ai.core.ScriptedLlmBridge
import afield.ai.core.ToolExecutionContext
import afield.ai.core.createLlmBridge
import afield.isTrue
import afield.sourceRevision
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A whole task handed to Field's own agent: a goal, a document, a tool-round budget, and checks that
 * decide — from the document and what its boxes said, not from the agent's final text — whether the
 * goal was met.
 *
 * This is the in-Field counterpart of an external agent driving `tools/field_backdoor.py`. The turn
 * runs through the same [LlmBridge] loop as the AI4 chat panel, with the native graph tools
 * ([FieldGraphTools]) wired, and needs no browser: [AgentTaskRunner.run] takes a [SessionInformation]
 * and returns an [AgentTaskResult].
 */
@Serializable
data class AgentTaskRequest(
    val goal: String,
    /** Checks that decide the verdict. Empty = verdict `unchecked`. */
    val checks: List<AgentCheck> = emptyList(),
    /** Tool calls applied before the agent's turn (not counted against its budget or in its log). */
    val setup: String = "",
    /** Box the conversation is about (uid or name); optional. */
    val focusBox: String = "",
    val maxToolRounds: Int = 24,
    /** `live` = the configured provider; `scripted` = replay [script]. */
    val mode: String = "live",
    /** JSON script for `scripted` mode; see [ScriptedLlmBridge.parse]. */
    val script: String = "",
    /** Model override for `live` mode; blank = the configured flagship. */
    val model: String = "",
    /** Appended to the harness instructions. */
    val extraInstructions: String = "",
    val taskId: String = "",
)

/**
 * One predicate over the document. Kept as data (not a lambda) so a task can arrive over HTTP or the
 * backdoor, and so a result can say exactly what was checked.
 *
 * - `box-exists` [box]
 * - `output-contains` [box] [text] — printed or displayed since the agent's turn began ([box] blank = any box)
 * - `output-ints-include` [box] [ints] — those integers appear, in order, in what it printed/displayed
 * - `property-contains` / `property-lacks` [box] [property] [text]
 * - `last-run-ok` [box] — its latest run/launch since the turn began succeeded, and was of its current code
 * - `connected` [parent] [child]
 * - `running` / `not-running` [box]
 */
@Serializable
data class AgentCheck(
    val kind: String,
    val box: String = "",
    val text: String = "",
    val property: String = "code",
    val parent: String = "",
    val child: String = "",
    val ints: List<Long> = emptyList(),
) {
    companion object {
        /**
         * The CLI spelling: `kind:arg:arg`. `output-contains:Primes:2, 3, 5`, `connected:A:B`,
         * `last-run-ok:Primes`, `output-ints-include:Primes:2,3,5,7`, `property-contains:Box:code:text`, `property-lacks:Box:code:text`.
         * Only the last argument may contain `:`.
         */
        fun parse(spec: String): AgentCheck {
            val kind = spec.substringBefore(':').trim()
            val rest = spec.substringAfter(':', "")
            return when (kind) {
                "box-exists", "last-run-ok", "running", "not-running" -> AgentCheck(kind, box = rest)
                "output-contains" -> AgentCheck(kind, box = rest.substringBefore(':'), text = rest.substringAfter(':', ""))
                "output-ints-include" -> AgentCheck(kind, box = rest.substringBefore(':'),
                    ints = rest.substringAfter(':', "").split(',', ' ').mapNotNull { it.trim().toLongOrNull() })
                "property-contains", "property-lacks" -> {
                    val parts = rest.split(':', limit = 3)
                    require(parts.size == 3) { "$kind:<box>:<property>:<text>" }
                    AgentCheck(kind, box = parts[0], property = parts[1], text = parts[2])
                }
                "connected" -> AgentCheck(kind, parent = rest.substringBefore(':'), child = rest.substringAfter(':', ""))
                else -> throw IllegalArgumentException("Unknown check kind '$kind'")
            }
        }
    }
}

@Serializable
data class AgentCheckOutcome(val check: AgentCheck, val passed: Boolean, val detail: String)

@Serializable
data class AgentToolCallRecord(
    val index: Int,
    val tool: String,
    val arguments: String,
    /** The tool's own verdict: false when it threw or returned `success: false` / `error: true`. */
    val ok: Boolean,
    val result: String,
)

@Serializable
data class AgentTaskResult(
    val taskId: String,
    val goal: String,
    val mode: String,
    val provider: String,
    val model: String,
    /** `pass`, `fail`, `budget-exhausted`, `error`, or `unchecked`. */
    val verdict: String,
    val checks: List<AgentCheckOutcome>,
    val toolCalls: List<AgentToolCallRecord>,
    val finalText: String,
    val rounds: Int,
    val maxToolRounds: Int,
    val hitToolRoundCap: Boolean,
    val elapsedMs: Long,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val documentName: String,
    val createdBoxes: List<String> = emptyList(),
    val setupCalls: List<AgentToolCallRecord> = emptyList(),
    val error: String = "",
)

/** What the agent is told, in addition to the goal. Short on purpose: the tool descriptions carry the rest. */
val AGENT_TASK_INSTRUCTIONS: String = """
You are Field's own agent, working directly inside a live Field document. Field is a live-coding
environment: a document is a graph of boxes, each holding Kotlin code that Field compiles and runs.

Work only through your tools. The ones for this task:
- ListBoxesRequest, NewBoxRequest, DeleteBoxRequest, GetBoxPropertyRequest, SetBoxPropertyRequest,
  ConnectBoxesRequest, DisconnectBoxesRequest: read and edit the box graph.
- RunBoxRequest runs a box once and returns its verdict; LaunchBoxRequest starts a box that keeps
  running (loops, animation); StopBoxRequest stops it; ReadBoxOutputRequest reads what it printed.
RunBoxRequest compiles the code itself and returns compile errors in `errors`, so you do not need
CompileKotlinRequest first. Every Run/Launch result has `success`. When it is false, read `error` and `errors`, fix the code,
and run again. Never claim success you have not seen in a tool result.

Box code conventions:
- The box is the implicit receiver. println(x) prints to the box's output.
- display("channel", "<html>") shows HTML in an output area named channel.
- A parent box shares a value with the boxes connected beneath it by writing `prop<Int>(level) = 3`;
  a child then reads it simply as `level`. Plain `val`s are local to their box. Run the parent first.
- A box that keeps running uses `loop { ... }`, which calls its body once per frame until stopped.

When the goal is met, answer with one or two sentences saying what you did and what the tools showed.
""".trimIndent()

object AgentTaskRunner {

    /**
     * Run [request] against [info]. Blocking; call it off the Runloop (the tools dispatch onto it).
     *
     * [contextFor] builds the tool context — by default the backdoor's full context (macros, guides,
     * Kotlin tools) plus [FieldGraphTools] on [host]. [bridgeFor] builds the model bridge — by default
     * [ScriptedLlmBridge] for `scripted` and [createLlmBridge] for `live`.
     */
    fun run(
        request: AgentTaskRequest,
        info: SessionInformation,
        host: FieldGraphHost = LiveFieldGraphHost,
        contextFor: (SessionInformation, FieldGraphTools, String?) -> ToolExecutionContext = { i, graph, focus ->
            Ai4BackdoorTools.buildContext(i, focus).copy(fieldGraph = graph)
        },
        bridgeFor: (AgentTaskRequest) -> LlmBridge = ::defaultBridge,
    ): AgentTaskResult {
        val taskId = request.taskId.ifBlank { "task-" + UUID.randomUUID().toString().take(8) }
        val started = System.nanoTime()
        val graph = FieldGraphTools(info, host)
        val budget = request.maxToolRounds.coerceIn(1, 64)
        var bridge: LlmBridge? = null
        var setupLog = emptyList<AgentToolCallRecord>()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000

        fun failure(message: String, sent: LlmSendResult? = null) = AgentTaskResult(
            taskId = taskId, goal = request.goal, mode = request.mode,
            provider = bridge?.modelIdentity?.provider ?: "", model = bridge?.modelIdentity?.requestedModel ?: request.model,
            verdict = "error", checks = emptyList(), toolCalls = sent?.let(::records).orEmpty(),
            finalText = sent?.text.orEmpty(), rounds = sent?.rounds ?: 0, maxToolRounds = budget,
            hitToolRoundCap = sent?.hitToolRoundCap ?: false, elapsedMs = elapsed(),
            documentName = info.documentName, createdBoxes = graph.created.toList(), setupCalls = setupLog,
            error = message
        )

        return try {
            val ctx = contextFor(info, graph, request.focusBox.ifBlank { null })

            if (request.setup.isNotBlank()) {
                val setup = ScriptedLlmBridge(ScriptedLlmBridge.parse(request.setup), "setup")
                val sent = setup.send(setup.startThread("setup", ""), "setup", ctx = ctx, maxToolRounds = 64)
                setupLog = records(sent)
                setupLog.firstOrNull { !it.ok && !it.tool.startsWith("RunBox") && !it.tool.startsWith("LaunchBox") }?.let {
                    // A setup run is allowed to fail — "fix the compile error" needs one — but a
                    // setup edit that fails means the task never existed.
                    return failure("Setup step ${it.index} (${it.tool}) failed: ${it.result.take(400)}")
                }
            }

            val marker = BoxOutputLog.mark()
            val b = bridgeFor(request)
            bridge = b
            val instructions = AGENT_TASK_INSTRUCTIONS +
                (if (request.extraInstructions.isNotBlank()) "\n\n" + request.extraInstructions else "")
            val thread = b.startThread("afield-task://${info.documentName}/$taskId", "", instructions)
            val prompt = buildString {
                appendLine("GOAL: ${request.goal}")
                appendLine()
                appendLine("Document: ${info.documentName}. Boxes now: " +
                    info.boxes.values.filter { !it.getPropertyRaw("_tombstone").isTrue() }
                        .joinToString(", ") { it.name.ifBlank { it.id } }.ifBlank { "(none)" })
                appendLine("You have at most $budget tool rounds.")
            }
            val sent = b.send(thread, prompt, ctx = ctx, maxToolRounds = budget)

            val outcomes = request.checks.map { evaluate(it, info, graph, marker) }
            val verdict = when {
                outcomes.isEmpty() -> "unchecked"
                outcomes.all { it.passed } -> "pass"
                sent.hitToolRoundCap -> "budget-exhausted"
                else -> "fail"
            }
            AgentTaskResult(
                taskId = taskId, goal = request.goal, mode = request.mode,
                provider = sent.modelIdentity?.provider ?: b.modelIdentity?.provider.orEmpty(),
                model = sent.modelIdentity?.let { it.responseModel?.takeIf { m -> m.isNotBlank() } ?: it.requestedModel } ?: b.modelIdentity?.requestedModel.orEmpty(),
                verdict = verdict, checks = outcomes, toolCalls = records(sent), finalText = sent.text,
                rounds = sent.rounds, maxToolRounds = budget, hitToolRoundCap = sent.hitToolRoundCap,
                elapsedMs = elapsed(), inputTokens = sent.inputTokens, outputTokens = sent.outputTokens,
                documentName = info.documentName, createdBoxes = graph.created.toList(), setupCalls = setupLog
            )
        } catch (t: Throwable) {
            failure("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun defaultBridge(request: AgentTaskRequest): LlmBridge = when (request.mode) {
        "scripted" -> {
            require(request.script.isNotBlank()) { "scripted mode needs a script" }
            ScriptedLlmBridge(ScriptedLlmBridge.parse(request.script))
        }
        "live" -> if (request.model.isNotBlank()) createLlmBridge(model = request.model) else createLlmBridge()
        else -> throw IllegalArgumentException("mode must be 'live' or 'scripted', not '${request.mode}'")
    }

    private fun records(sent: LlmSendResult): List<AgentToolCallRecord> =
        sent.toolTranscript.mapIndexed { index, entry ->
            val node = runCatching { afield.ai.core.aiToolMapper.readTree(entry.resultJson) }.getOrNull()
            val ok = node == null || !(node.get("success")?.asBoolean(true) == false || node.get("error")?.asBoolean(false) == true)
            AgentToolCallRecord(index + 1, entry.name, entry.argumentsJson.take(4000), ok, entry.resultJson.take(4000))
        }

    // ---- checks -----------------------------------------------------------------------------

    private fun find(info: SessionInformation, token: String): Box? {
        val live = info.boxes.values.filter { !it.getPropertyRaw("_tombstone").isTrue() }
        return live.firstOrNull { it.id == token } ?: live.filter { it.name == token }.let { if (it.size == 1) it[0] else null }
    }

    private fun said(box: Box, marker: Long): List<BoxOutputLog.Entry> =
        BoxOutputLog.since(box.id, marker).entries.filter { it.kind == "out" || it.kind == "display" }

    internal fun evaluate(check: AgentCheck, info: SessionInformation, graph: FieldGraphTools, marker: Long): AgentCheckOutcome {
        fun pass(detail: String) = AgentCheckOutcome(check, true, detail)
        fun fail(detail: String) = AgentCheckOutcome(check, false, detail)
        fun box(token: String): Box? = find(info, token)
        return when (check.kind) {
            "box-exists" -> box(check.box)?.let { pass("found ${it.id}") } ?: fail("no unique live box '${check.box}'")
            "output-contains" -> {
                val boxes = if (check.box.isBlank()) info.boxes.values.toList() else listOfNotNull(box(check.box))
                if (boxes.isEmpty()) return fail("no unique live box '${check.box}'")
                val hit = boxes.firstOrNull { b -> said(b, marker).any { it.text.contains(check.text) } }
                if (hit != null) pass("'${hit.name}' said it")
                else fail("not in output since the turn began: " +
                    boxes.flatMap { said(it, marker) }.takeLast(3).joinToString(" | ") { it.text.take(160) })
            }
            "output-ints-include" -> {
                val b = box(check.box) ?: return fail("no unique live box '${check.box}'")
                val texts = said(b, marker).map { it.text }
                val found = texts.any { text ->
                    val ints = Regex("-?\\d+").findAll(text).map { it.value.toLong() }.toList()
                    var i = 0
                    for (n in ints) if (i < check.ints.size && n == check.ints[i]) i++
                    i == check.ints.size
                }
                if (found) pass("found ${check.ints} in order")
                else fail("${check.ints} not found in order in: " + texts.takeLast(3).joinToString(" | ") { it.take(160) })
            }
            "property-contains" -> {
                val b = box(check.box) ?: return fail("no unique live box '${check.box}'")
                val value = b.getPropertyRaw(check.property) as? String ?: return fail("no text property ${check.property}")
                if (value.contains(check.text)) pass("contains it") else fail("${check.property} does not contain '${check.text}'")
            }
            "property-lacks" -> {
                val b = box(check.box) ?: return fail("no unique live box '${check.box}'")
                val value = b.getPropertyRaw(check.property) as? String ?: ""
                if (!value.contains(check.text)) pass("does not contain it") else fail("${check.property} contains '${check.text}'")
            }
            "last-run-ok" -> {
                val b = box(check.box) ?: return fail("no unique live box '${check.box}'")
                val verdicts = BoxOutputLog.since(b.id, marker).entries.filter { it.kind == "verdict" }
                val last = verdicts.lastOrNull() ?: return fail("not run since the turn began")
                if (last.success != true) return fail("latest run failed: ${last.text.take(300)}")
                val report = ExecutionReports.latest(b, "code")
                val current = (b.getPropertyRaw("code") as? String).orEmpty()
                if (report != null && report.revision != sourceRevision(current)) fail("its code changed after the successful run")
                else pass("latest run succeeded (${last.text.lineSequence().first()})")
            }
            "connected" -> {
                val p = box(check.parent) ?: return fail("no unique live box '${check.parent}'")
                val c = box(check.child) ?: return fail("no unique live box '${check.child}'")
                if (c in p.children) pass("'${p.name}' -> '${c.name}'") else fail("'${c.name}' is not a child of '${p.name}'")
            }
            "running" -> box(check.box)?.let { if (graph.isRunning(it)) pass("running") else fail("not running") }
                ?: fail("no unique live box '${check.box}'")
            "not-running" -> box(check.box)?.let { if (!graph.isRunning(it)) pass("not running") else fail("still running") }
                ?: fail("no unique live box '${check.box}'")
            else -> fail("unknown check kind '${check.kind}'")
        }
    }
}

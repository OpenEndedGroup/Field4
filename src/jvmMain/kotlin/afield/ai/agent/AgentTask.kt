package afield.ai.agent

import afield.Box
import afield.payload
import afield.BoxOutputLog
import afield.ExecutionReports
import afield.SessionInformation
import afield.ai.core.Ai4BackdoorTools
import afield.ai.core.LlmBridge
import afield.ai.core.LlmSendResult
import afield.ai.core.ScriptedLlmBridge
import afield.ai.core.ToolExecutionContext
import afield.ai.core.createLlmBridge
import afield.ai.core.createIdeBridge
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
    /**
     * Run the task *as* this box's AI4 panel turn (uid or unique name): it goes through the real
     * `ai-assist` handler with kind `general`, so the exchange is saved in the box's
     * `ai4_chat_history` and shows in the normal chat UI. Live mode only. The per-call tool log is
     * then not available here (the panel keeps it); the checks still decide the verdict.
     */
    val chatBox: String = "",
    /** `on` / `off` pins the eval-distilled lessons in the instructions ([AgentLessons]); blank = default. */
    val lessons: String = "",
    /** Withhold [ViewBoxOutputRequest] — the counterfactual for a visual task: can it be done without looking? */
    val blind: Boolean = false,
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
 * - `output-picture` [box] — a capture of its output area succeeds and is not blank (≥ 1% of pixels are not background)
 * - `output-colour` [box] [text] = `#rrggbb` [fraction] — at least that share of the capture is within 48 of that colour
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
    val fraction: Double = 0.0,
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
                "output-picture" -> AgentCheck(kind, box = rest)
                "output-colour" -> {
                    val parts = rest.split(':')
                    require(parts.size == 3) { "output-colour:<box>:#rrggbb:<fraction>" }
                    PictureStats.parseHex(parts[1])
                    AgentCheck(kind, box = parts[0], text = parts[1], fraction = parts[2].toDouble())
                }
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
    /** Set when the task ran as a box's panel turn ([AgentTaskRequest.chatBox]). */
    val chatBox: String = "",
    /** Failed tool calls that were mistakes about the tools, not the box's own errors ([AgentLessons.isMisuse]). */
    val misuseCalls: Int = 0,
    /** Tool results that attached a picture the model was shown. */
    val imagesSeen: Int = 0,
    /** Whether the eval-distilled lessons were in this turn's instructions. */
    val lessons: Boolean = false,
    /** The document as file refs spell it (`afield://<documentRef>/<uid>/code`): its directory's name. */
    val documentRef: String = "",
)

/**
 * What a headless task adds to the ordinary AI4 chat instructions ([afield.ai.core.buildDynamicInstructions],
 * which already describes the graph tools via [afield.ai.core.fieldGraphToolGuidance]). Only the
 * facts that differ from a person typing in the chat: nobody will answer a question, and the turn is
 * judged by checks.
 */
val HEADLESS_TASK_INSTRUCTIONS: String = """
This turn was handed to you as a task, not typed by a person watching: nobody can answer questions.
Work through your tools until the goal is met, then answer with one or two sentences saying what you
did and what the tool results showed. The task is judged by checks on the document afterwards.
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
        val graph = FieldGraphTools(info, host, lessons = AgentLessons.resolve(request.lessons), seesOutput = !request.blind)
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

            if (request.chatBox.isNotBlank()) return runInChatPanel(request, info, host, contextFor, bridgeFor, graph, setupLog, budget, taskId, started)

            val marker = BoxOutputLog.mark()
            val b = bridgeFor(request)
            bridge = b
            val extra = HEADLESS_TASK_INSTRUCTIONS +
                (if (request.extraInstructions.isNotBlank()) "\n\n" + request.extraInstructions else "")
            // The same thread instructions and the same chat turn as the AI4 document chat: this is a
            // headless entry into that turn, not a second harness.
            val thread = b.startThread("afield-task://${info.documentName}/$taskId", "",
                afield.ai.core.OpenAiIdeCodexBridge.defaultChatThreadInstructions)
            val prompt = buildString {
                appendLine("GOAL: ${request.goal}")
                appendLine()
                appendLine("Document: ${info.documentName}. Boxes now: " +
                    info.boxes.values.filter { !it.getPropertyRaw("_tombstone").isTrue() }
                        .joinToString(", ") { it.name.ifBlank { it.id } }.ifBlank { "(none)" })
                appendLine("You have at most $budget tool rounds.")
            }
            val sent = afield.ai.core.IdeChatHelpers(b).chatGeneral(
                thread, code = "", lang = afield.ai.core.KOTLIN_SCRIPT, userPrompt = prompt, ctx = ctx,
                maxToolRounds = budget, extraInstructions = extra)

            val shots = HashMap<String, BoxOutputShot>()
            val outcomes = request.checks.map { evaluate(it, info, graph, marker, shots) }
            val verdict = when {
                outcomes.isEmpty() -> "unchecked"
                outcomes.all { it.passed } -> "pass"
                sent.hitToolRoundCap -> "budget-exhausted"
                else -> "fail"
            }
            val calls = records(sent)
            AgentTaskResult(
                taskId = taskId, goal = request.goal, mode = request.mode,
                provider = sent.modelIdentity?.provider ?: b.modelIdentity?.provider.orEmpty(),
                model = sent.modelIdentity?.let { it.responseModel?.takeIf { m -> m.isNotBlank() } ?: it.requestedModel } ?: b.modelIdentity?.requestedModel.orEmpty(),
                verdict = verdict, checks = outcomes, toolCalls = calls, finalText = sent.text,
                rounds = sent.rounds, maxToolRounds = budget, hitToolRoundCap = sent.hitToolRoundCap,
                elapsedMs = elapsed(), inputTokens = sent.inputTokens, outputTokens = sent.outputTokens,
                documentName = info.documentName, createdBoxes = graph.created.toList(), setupCalls = setupLog,
                misuseCalls = calls.count(AgentLessons::isMisuse),
                imagesSeen = sent.toolTranscript.count { it.resultJson.contains("\"imageAttached\":true") },
                lessons = graph.lessons,
                documentRef = java.io.File(info.filename).name,
            )
        } catch (t: Throwable) {
            failure("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * The task as an AI4 panel turn in [AgentTaskRequest.chatBox]: its exchange is written into that
     * box's `ai4_chat_history`, the panel shows it, and the person can carry on the conversation.
     *
     * Live: the goal goes through the real `ai-assist` handler (kind `general`) — the same turn a
     * person's message starts — and the verdict is appended. Scripted: the replay runs through the
     * same tools as always, and the whole exchange, tool calls included, is written into the
     * transcript, labelled as a scripted replay.
     *
     * Fails fast, with the reason in the transcript *and* the result, when the box already has an AI
     * turn running or when the handler does not start one (AI setup failed).
     */
    private fun runInChatPanel(
        request: AgentTaskRequest, info: SessionInformation, host: FieldGraphHost,
        contextFor: (SessionInformation, FieldGraphTools, String?) -> ToolExecutionContext,
        bridgeFor: (AgentTaskRequest) -> LlmBridge, graph: FieldGraphTools,
        setupLog: List<AgentToolCallRecord>, budget: Int, taskId: String, started: Long,
    ): AgentTaskResult {
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val box = graph.resolve(request.chatBox)
        fun refuse(message: String): AgentTaskResult {
            AgentTaskTranscript.appendError(info, box, request.goal, message)
            return AgentTaskResult(taskId = taskId, goal = request.goal, mode = request.mode, provider = "", model = "",
                verdict = "error", checks = emptyList(), toolCalls = emptyList(), finalText = "", rounds = 0,
                maxToolRounds = budget, hitToolRoundCap = false, elapsedMs = elapsed(), documentName = info.documentName,
                setupCalls = setupLog, error = message, chatBox = box.id)
        }
        if (box.getPropertyRaw(afield.ai.core.AiBoxConversation.RUNNING_PROPERTY) == true)
            return refuse("'${box.name}' already has an AI turn running. Wait for it (or stop it) and ask again.")

        if (request.mode == "scripted") {
            val result = run(request.copy(chatBox = "", setup = ""), info, host, contextFor, bridgeFor)
                .let { it.copy(taskId = taskId, setupCalls = setupLog, chatBox = box.id, elapsedMs = elapsed()) }
            AgentTaskTranscript.appendScriptedTurn(info, box, request.goal, result)
            return result
        }
        require(request.mode == "live") { "mode must be 'live' or 'scripted', not '${request.mode}'" }

        val marker = BoxOutputLog.mark()
        val turn = runAsBoxChatTurn(info, box, request, budget)
        if (turn.error != null) return refuse(turn.error)
        val shots = HashMap<String, BoxOutputShot>()
        val outcomes = request.checks.map { evaluate(it, info, graph, marker, shots) }
        val result = AgentTaskResult(
            taskId = taskId, goal = request.goal, mode = request.mode,
            provider = turn.identity?.provider ?: "ai4-panel", model = turn.identity?.let { it.responseModel ?: it.requestedModel }.orEmpty(),
            verdict = when {
                outcomes.isEmpty() -> "unchecked"
                outcomes.all { it.passed } -> "pass"
                else -> "fail"
            },
            checks = outcomes, toolCalls = emptyList(), finalText = turn.reply, rounds = -1, maxToolRounds = budget,
            hitToolRoundCap = false, elapsedMs = elapsed(), documentName = info.documentName,
            setupCalls = setupLog, chatBox = box.id
        )
        AgentTaskTranscript.appendVerdict(info, box, result)
        return result
    }

    internal data class PanelTurn(val reply: String, val identity: afield.AiChatModelIdentity? = null, val error: String? = null)

    /** How long a turn that *did* start may run before this gives up on it (it keeps running). */
    internal var panelTurnLimitMs: Long = 10 * 60_000L

    /**
     * Dispatch the goal to the real `ai-assist` handler and wait for *this* turn to finish.
     *
     * The handler marks the box running synchronously, before it launches the turn, so "not running
     * right after dispatch" means it did not start (setup failed, or it refused) — reported at once,
     * never waited on. The reply is taken from what the history gained after the transcript as it
     * was before dispatch, and must follow a `<<you>>` block carrying this goal; anything else is an
     * error, never another turn's reply.
     */
    private fun runAsBoxChatTurn(info: SessionInformation, box: Box, request: AgentTaskRequest, budget: Int): PanelTurn {
        val runningKey = afield.ai.core.AiBoxConversation.RUNNING_PROPERTY
        val before = afield.ai.core.AiBoxConversation.read(box)
        val assist = afield.AiAssist(
            uid = box.id,
            prompt = (box.getPropertyRaw("code") as? String).orEmpty(),
            // Just the goal: this is an ordinary panel turn the person can see and continue, so the
            // headless "nobody can answer" framing does not apply, and it would be shown to them as
            // their own message. The panel's own round limit applies (maxToolRounds is not passed).
            extraPrompt = request.goal,
            property = "code",
            kind = "general",
        )
        val previous = afield.thisSessionInfo.get()
        afield.thisSessionInfo.set(info)
        try {
            afield.handle(afield.Message("ai-assist").payload(assist)) { }
        } finally {
            if (previous == null) afield.thisSessionInfo.remove() else afield.thisSessionInfo.set(previous)
        }
        if (box.getPropertyRaw(runningKey) != true) return PanelTurn("", error =
            "AI4 did not start a turn in '${box.name}': AI setup failed or no provider is configured (see Field's notifications and /welcome).")
        val deadline = System.currentTimeMillis() + panelTurnLimitMs
        while (box.getPropertyRaw(runningKey) == true) {
            if (System.currentTimeMillis() > deadline) return PanelTurn("", error =
                "The AI turn in '${box.name}' was still running after ${panelTurnLimitMs / 60_000} minutes; it was left running.")
            Thread.sleep(200)
        }
        val history = afield.ai.core.AiBoxConversation.read(box)
        val added = if (history.startsWith(before)) history.substring(before.length) else {
            val key = request.goal.trim().take(60)
            val at = history.lastIndexOf("<<you>>").takeIf { it >= 0 && history.indexOf(key, it) >= 0 }
                ?: return PanelTurn("", error = "Could not find this task's turn in '${box.name}'s chat history.")
            history.substring(at)
        }
        if (!added.contains(request.goal.trim().take(60))) return PanelTurn("", error = "The chat history gained a turn that is not this task's.")
        val last = added.substringAfterLast("<<ai>>").trimStart('\n')
        val (identity, text) = afield.AiChatModelIdentity.splitFromContent(last)
        return PanelTurn(text.substringBefore("<<progress").trim().take(4000), identity)
    }

    fun defaultBridge(request: AgentTaskRequest): LlmBridge = when (request.mode) {
        "scripted" -> {
            require(request.script.isNotBlank()) { "scripted mode needs a script" }
            ScriptedLlmBridge(ScriptedLlmBridge.parse(request.script))
        }
        "live" -> if (request.model.isNotBlank()) createLlmBridge(model = request.model) else createIdeBridge()
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

    internal fun evaluate(check: AgentCheck, info: SessionInformation, graph: FieldGraphTools, marker: Long,
                          shots: MutableMap<String, BoxOutputShot> = HashMap()): AgentCheckOutcome {
        fun pass(detail: String) = AgentCheckOutcome(check, true, detail)
        fun fail(detail: String) = AgentCheckOutcome(check, false, detail)
        fun box(token: String): Box? = find(info, token)
        // One capture per box per verdict: several picture checks on one box look at the same pixels.
        fun shot(b: Box): BoxOutputShot = shots.getOrPut(b.id) { graph.captureOutput(b, settleMs = 500) }
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
            "output-picture" -> {
                val b = box(check.box) ?: return fail("no unique live box '${check.box}'")
                val s = shot(b)
                val image = s.image ?: return fail("no picture: ${s.message}")
                val stats = PictureStats.of(image)
                val detail = "${image.width}x${image.height}, ink ${"%.3f".format(stats.inkFraction)}, ${stats.describeDominant()}" +
                    (if (s.path.isNotBlank()) ", ${s.path}" else "")
                if (stats.inkFraction >= 0.01) pass(detail) else fail("blank: $detail")
            }
            "output-colour" -> {
                val b = box(check.box) ?: return fail("no unique live box '${check.box}'")
                val s = shot(b)
                val image = s.image ?: return fail("no picture: ${s.message}")
                val share = PictureStats.coverage(image, check.text)
                val detail = "${"%.3f".format(share)} of ${image.width}x${image.height} is ${check.text} (need ${check.fraction}); " +
                    PictureStats.of(image).describeDominant()
                if (share >= check.fraction) pass(detail) else fail(detail)
            }
            else -> fail("unknown check kind '${check.kind}'")
        }
    }
}

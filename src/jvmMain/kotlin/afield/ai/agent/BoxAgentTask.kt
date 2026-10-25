package afield.ai.agent

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.history
import afield.ui.output.transcript
import afield.ui.visualization.typed

import afield.audio.synth.*
import afield.document.graph.Box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.map
import afield.ai.agent.AgentCheck
import afield.ai.agent.AgentTaskRequest
import afield.ai.agent.AgentTaskResult
import afield.ai.agent.AgentTaskRunner
import afield.ai.agent.FieldGraphTools
import afield.ai.agent.InFieldAgentEvals
import afield.ai.models.aiProviderStatus
import java.util.concurrent.CompletableFuture

/**
 * Hand [goal] to Field's own agent as an AI4 chat turn.
 *
 * The turn runs in the AI4 panel of [chatBox] (default: this box). A live turn goes through the same
 * `ai-assist` handler as a message typed into that panel, so it is saved in the box's chat history,
 * the panel renders it, and you can open the box's AI4 chat and carry on the conversation. The
 * [checks] are evaluated against the document afterwards, and their verdict (PASS / FAIL / …) is
 * appended to the transcript as a progress block.
 *
 * [mode]: `live` uses the configured AI provider; `scripted` replays [script] (a JSON list of tool
 * calls, see `ScriptedLlmBridge`) through the same tools with no model, and writes its tool calls into
 * the transcript labelled "scripted replay, no model was called"; `auto` is `live` when a provider key
 * is configured and `scripted` otherwise (it needs a [script] to fall back to).
 *
 * [chatBox] is a [Box] or a box name/uid. [checks] use the `AgentCheck.parse` spelling —
 * `last-run-ok:Primes`, `output-contains:Primes:29`.
 *
 * The task runs on a background thread, because the agent's tools wait for other boxes' verdicts and
 * those need the Runloop this box's code is running on. So this returns at once; the returned future
 * completes with the structured [AgentTaskResult] for code that wants it. Do not block on it from box
 * code. If observation expires while a live panel turn continues, its verdict is `running` and
 * `chatBox` identifies that continuing conversation. No completion checks or final verdict have
 * run; inspect the same chat instead of submitting the goal again.
 */
fun Box.askFieldAgent(
    goal: String,
    checks: List<String> = emptyList(),
    mode: String = "auto",
    script: String = "",
    maxToolRounds: Int = 16,
    chatBox: Any? = null,
): CompletableFuture<AgentTaskResult> {
    val info = inside
    val target: Box = when (chatBox) {
        null -> this
        is Box -> chatBox
        else -> FieldGraphTools(info).resolve(chatBox.toString())
    }
    val hasKey = runCatching { aiProviderStatus().let { it.hasOpenAiPlatformKey || it.hasOpenRouterApiKey || (it.provider == "local" && it.modelSettings.flagshipModel.isNotBlank()) } }.getOrDefault(false)
    val resolved = when (mode) {
        "auto" -> if (hasKey || script.isBlank()) "live" else "scripted"
        else -> mode
    }
    val request = AgentTaskRequest(
        goal = goal,
        checks = checks.map(AgentCheck::parse),
        mode = resolved,
        script = if (resolved == "scripted") script else "",
        maxToolRounds = maxToolRounds,
        chatBox = target.id,
    )
    val future = CompletableFuture<AgentTaskResult>()
    kotlin.concurrent.thread(name = "askFieldAgent-${target.id.take(8)}", isDaemon = true) {
        future.complete(AgentTaskRunner.run(request, info))
    }
    return future
}

/** The scripted solution of an eval task, for [askFieldAgent]'s `script` — a no-credentials fallback. */
fun fieldAgentEvalScript(id: String): String = InFieldAgentEvals.byId(id).solution

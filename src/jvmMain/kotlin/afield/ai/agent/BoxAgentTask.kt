package afield

import afield.ai.agent.AgentCheck
import afield.ai.agent.AgentTaskRequest
import afield.ai.agent.AgentTaskResult
import afield.ai.agent.AgentTaskRunner
import afield.ai.agent.FieldGraphTools
import afield.ai.agent.InFieldAgentEvals
import afield.ai.core.aiProviderStatus
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
 * code.
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
    val hasKey = runCatching { aiProviderStatus().let { it.hasOpenAiPlatformKey || it.hasOpenRouterApiKey } }.getOrDefault(false)
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

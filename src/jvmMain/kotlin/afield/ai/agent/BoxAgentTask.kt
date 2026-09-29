package afield

import afield.ai.agent.AgentCheck
import afield.ai.agent.AgentTaskRequest
import afield.ai.agent.AgentTaskResult
import afield.ai.agent.AgentTaskRunner
import afield.ai.agent.InFieldAgentEvals
import afield.ai.core.aiProviderStatus

/**
 * Hand [goal] to Field's own agent from a box, and show its tool log and verdict in this box's
 * output channel [channel].
 *
 * The agent works in this box's document: it can see, make, edit, connect, run and stop boxes there.
 * The task runs on a background thread — the agent's tools wait for other boxes' verdicts, and those
 * runs need the Runloop this box is running on — so this returns at once and the output area fills
 * in when the task finishes.
 *
 * [mode]: `live` uses the configured AI provider; `scripted` replays [script] (a JSON list of tool
 * calls, see `ScriptedLlmBridge`) through the same tools with no model; `auto` is `live` when a
 * provider key is configured and `scripted` otherwise. The output always says which one ran, because
 * a scripted pass says nothing about a model.
 *
 * [checks] use the `AgentCheck.parse` spelling — `last-run-ok:Primes`, `output-contains:Primes:29`.
 */
fun Box.askFieldAgent(
    goal: String,
    checks: List<String> = emptyList(),
    mode: String = "auto",
    script: String = "",
    maxToolRounds: Int = 16,
    channel: String = "agent",
) {
    val box = this
    val info = box.inside
    val hasKey = runCatching { aiProviderStatus().let { it.hasOpenAiPlatformKey || it.hasOpenRouterApiKey } }.getOrDefault(false)
    val resolved = when (mode) {
        "auto" -> if (hasKey) "live" else if (script.isNotBlank()) "scripted" else "live"
        else -> mode
    }
    val show = { html: String -> send(Message("set-output").payload(Set_output(box.id, "outputarea", channel, html))) }
    show(agentTaskCard(goal, resolved, null, note = "working…"))
    box.displayOpen(channel)
    val request = AgentTaskRequest(
        goal = goal,
        checks = checks.map(AgentCheck::parse),
        mode = resolved,
        script = if (resolved == "scripted") script else "",
        maxToolRounds = maxToolRounds,
    )
    kotlin.concurrent.thread(name = "askFieldAgent-${box.id.take(8)}", isDaemon = true) {
        val result = AgentTaskRunner.run(request, info)
        show(agentTaskCard(goal, resolved, result))
        box.displayOpen(channel)
    }
}

/** The scripted solution of an eval task, for [askFieldAgent]'s `script` — a no-credentials fallback. */
fun fieldAgentEvalScript(id: String): String = InFieldAgentEvals.byId(id).solution

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun agentTaskCard(goal: String, mode: String, result: AgentTaskResult?, note: String = ""): String = buildString {
    val verdict = result?.verdict ?: "running"
    val colour = when (verdict) { "pass" -> "#2e7d32"; "running" -> "#777"; "unchecked" -> "#8a6d00"; else -> "#b3261e" }
    val modeLabel = if (mode == "scripted") "SCRIPTED replay — no model was called" else "LIVE model"
    append("<div style='font:12px/1.45 ui-monospace,Menlo,monospace;padding:8px;max-width:760px'>")
    append("<div><b style='color:$colour;font-size:14px'>${esc(verdict.uppercase())}</b> &nbsp; $modeLabel")
    if (result != null) {
        append(" &nbsp; ${esc(result.provider)} ${esc(result.model)} &nbsp; rounds ${result.rounds}/${result.maxToolRounds}")
        append(" &nbsp; ${result.elapsedMs} ms")
        if (result.inputTokens != null) append(" &nbsp; tokens ${result.inputTokens} in / ${result.outputTokens} out")
    }
    append("</div><div style='margin:4px 0 8px 0'><i>${esc(goal)}</i></div>")
    if (note.isNotBlank()) append("<div>${esc(note)}</div>")
    if (result != null) {
        if (result.error.isNotBlank()) append("<div style='color:#b3261e'>error: ${esc(result.error)}</div>")
        append("<table style='border-collapse:collapse;margin-bottom:6px'>")
        result.toolCalls.forEach { call ->
            val mark = if (call.ok) "✓" else "✗"
            append("<tr><td style='padding:1px 6px;color:${if (call.ok) "#2e7d32" else "#b3261e"}'>$mark</td>")
            append("<td style='padding:1px 6px'><b>${esc(call.tool.removeSuffix("Request"))}</b></td>")
            append("<td style='padding:1px 6px;color:#9a9a9a'>${esc(call.arguments.take(140))}</td></tr>")
        }
        append("</table>")
        result.checks.forEach { c ->
            append("<div style='color:${if (c.passed) "#2e7d32" else "#b3261e"}'>${if (c.passed) "✓" else "✗"} ${esc(c.check.kind)} ")
            append("${esc(listOf(c.check.box, c.check.parent, c.check.child, c.check.text).filter { it.isNotBlank() }.joinToString(" "))}")
            append(" — ${esc(c.detail.take(200))}</div>")
        }
        if (result.finalText.isNotBlank()) append("<div style='margin-top:6px'>agent: ${esc(result.finalText.take(800))}</div>")
    }
    append("</div>")
}

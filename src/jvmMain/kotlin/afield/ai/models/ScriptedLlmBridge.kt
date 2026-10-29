/**
 * An [LlmBridge] that replays a fixed script of tool calls instead of asking a model.
 *
 * Tests and the no-credentials mode of agent tasks use it to drive the real tool dispatch;
 * [ScriptedLlmBridge] documents the script format.
 */

package afield.ai.models

import afield.ai.conversation.AiTurnInbox
import afield.ai.conversation.PendingToolOutput
import afield.ai.conversation.SuspendedRound
import afield.ai.conversation.SuspendsTurn
import afield.ai.conversation.TurnContext
import afield.ai.conversation.TurnSuspension
import afield.ai.conversation.aiToolFailureEndsTurn
import afield.ai.conversation.aiToolFailureMessage
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.aiToolMapper
import afield.ai.tools.registerBuiltInAgentTools
import afield.ai.conversation.AiChatModelIdentity
import java.util.UUID

/**
 * An [LlmBridge] with no model behind it: it replays a script of tool calls and a final answer
 * through the real [ToolRegistry], exactly as a provider bridge would, and records what it was given.
 *
 * # What it is for
 *
 * Every test of the AI4 loop so far faked the provider's HTTP endpoint. That tests the bridge's wire
 * handling, and it is the only way to; but it cannot test what happens *after* a tool call — whether
 * the tools do what their descriptions say, whether a task's checker agrees with the document, whether
 * a verdict is reported honestly — without writing an HTTP transcript for every case. This bridge
 * makes those tests a list of calls.
 *
 * It is also the no-credentials mode of `AgentTask` and of the demo document: a scripted run goes
 * through the same tool dispatch, the same Field execution and the same checker as a live one; only
 * the choice of calls is canned. Results carry `modelIdentity.provider == "scripted"` so a scripted
 * pass can never be mistaken for a model's.
 *
 * # Script
 *
 * [Step.Call] runs one tool; [Step.Final] ends the turn with text. A call whose result suspends the turn
 * ([SuspendsTurn]) ends it too, and the next [send] carries on with the step after it. Arguments are JSON with
 * `${Tool.field}` placeholders, replaced by `field` of the most recent result of the tool whose
 * simple name is `Tool` — `${NewBoxRequest.uid}` is the uid of the box the script just made. Each
 * [Step.Call] is one tool round. A script with no [Step.Final] ends with an empty answer; one that
 * outruns maxToolRounds stops there and reports [LlmSendResult.hitToolRoundCap], like a real bridge.
 */
class ScriptedLlmBridge(
    private val script: List<Step>,
    private val label: String = "scripted",
) : LlmBridge {

    sealed class Step {
        data class Call(val tool: String, val argumentsJson: String = "{}") : Step()
        data class Final(val text: String) : Step()
    }

    /** What one [send] was handed, for assertions. */
    data class Received(
        val instructions: String,
        val prompt: String,
        val fileText: String?,
        val maxToolRounds: Int,
        val availableTools: List<String>,
        /** The turn in parts, when the caller built one; [prompt] is then its legacy one-string form. */
        val turnContext: TurnContext? = null,
        /** The suspended round this turn opened with, when it resumed a suspended one. */
        val resumedRound: SuspendedRound? = null,
    )

    private val tools = ToolRegistry().also(::registerBuiltInAgentTools)
    private var cursor = 0
    val received = mutableListOf<Received>()
    /** The user messages handed over between rounds (AiTurnInbox), as the model would have read them. */
    val interjections = mutableListOf<String>()

    override val modelIdentity: AiChatModelIdentity get() = AiChatModelIdentity("scripted", label)

    override fun startThread(fileKey: String, fileText: String, instructions: String): LlmThreadState =
        LlmThreadState(
            localThreadId = UUID.randomUUID().toString(),
            conversationId = "scripted:${UUID.randomUUID()}",
            fileKey = fileKey,
            initialInstructions = instructions,
            initialFileText = fileText
        )

    override fun send(
        state: LlmThreadState,
        userPrompt: String,
        fileText: String?,
        ctx: ToolExecutionContext,
        vectorStoreIds: List<String>,
        maxOutputTokens: Long,
        maxToolRounds: Int,
        imageDataUrls: List<String>,
        onTextDelta: ((String) -> Unit)?,
        onProgress: ((LlmProgressUpdate) -> Unit)?,
        turnContext: TurnContext?
    ): LlmSendResult {
        val available = tools.describe(ctx).mapNotNull {
            ((it as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        received += Received(state.initialInstructions, userPrompt, fileText, maxToolRounds, available, turnContext, state.suspendedRound)
        state.suspendedRound = null

        val transcript = mutableListOf<LlmToolTranscriptEntry>()
        val callCtx = ctx.copy(
            reportProgress = onProgress ?: ctx.reportProgress,
            promoted = ctx.promoted ?: state.promotedTools,
            progress = ctx.progress ?: onProgress?.let { p -> { line: String -> p(LlmProgressUpdate(line, phase = "tool")) } },
        )
        val latestByTool = mutableMapOf<String, String>()
        var rounds = 0
        var hitCap = false
        var text = ""
        var suspension: TurnSuspension? = null
        while (cursor < script.size && suspension == null) {
            when (val step = script[cursor]) {
                is Step.Final -> { text = step.text; cursor++; break }
                is Step.Call -> {
                    if (rounds >= maxToolRounds) { hitCap = true; break }
                    cursor++
                    rounds++
                    val args = substitute(step.argumentsJson, latestByTool)
                    onProgress?.invoke(LlmProgressUpdate("Using ${step.tool}", args.take(700), phase = "tool"))
                    val started = System.nanoTime()
                    val result: Any = try {
                        tools.executeByName(callCtx, step.tool, args)
                    } catch (e: Throwable) {
                        if (aiToolFailureEndsTurn(e)) throw e
                        mapOf("error" to true, "tool" to step.tool, "message" to aiToolFailureMessage(e))
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    val json = runCatching { aiToolMapper.writeValueAsString(result) }.getOrElse { result.toString() }
                    latestByTool[step.tool] = json
                    transcript += LlmToolTranscriptEntry(
                        step.tool, "scripted-$rounds", args, json.take(300), json, elapsedMs = elapsedMs
                    )
                    transcript += afield.ai.tools.program.nestedTranscriptEntries("scripted-$rounds", result)
                    onProgress?.invoke(LlmProgressUpdate("${step.tool} finished",
                        afield.ai.tools.program.nestedTraceMarkdown(result) ?: json.take(300), phase = "tool_result"))
                    // A suspending tool ends this turn, as on the provider bridges; the script carries on in the
                    // turn that resumes it, and the inbox is left for a turn of its own.
                    suspension = (result as? SuspendsTurn)?.suspension
                    if (suspension != null) {
                        state.suspendedRound = SuspendedRound(listOf(PendingToolOutput("scripted-$rounds", step.tool, json)))
                        continue
                    }
                    // As the provider bridges do after a round; the script does not change, it is only recorded.
                    callCtx.inbox?.drainForModel()?.takeIf { it.isNotEmpty() }?.let { interjections += AiTurnInbox.modelText(it) }
                }
            }
        }
        onTextDelta?.invoke(text)
        return LlmSendResult(
            threadState = state,
            text = text,
            rawResponseId = "scripted-${UUID.randomUUID()}",
            toolTranscript = transcript,
            modelIdentity = modelIdentity,
            rounds = rounds,
            hitToolRoundCap = hitCap,
            suspension = suspension,
            inputTokens = 0,
            outputTokens = 0
        )
    }

    private fun substitute(template: String, latest: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m ->
            val (tool, field) = m.destructured
            val node = latest[tool]?.let { runCatching { aiToolMapper.readTree(it) }.getOrNull() }
                ?: throw IllegalStateException("Script refers to \${$tool.$field} before any $tool result.")
            val value = node.get(field) ?: throw IllegalStateException("$tool result has no field '$field'.")
            // Insert as the inside of a JSON string: the template writes the quotes.
            aiToolMapper.writeValueAsString(if (value.isTextual) value.asText() else value.toString()).removeSurrounding("\"")
        }

    companion object {
        /**
         * A placeholder names a tool, and every tool's simple name ends in `Request`; anything else in `${a.b}` form is
         * left alone, so box code written with Kotlin string templates (`"${it.name}"`) survives a script unchanged.
         */
        private val PLACEHOLDER = Regex("""\$\{([A-Z][A-Za-z0-9_]*Request)\.([A-Za-z0-9_]+)}""")

        /**
         * Parse a script from JSON: `[{"tool":"NewBoxRequest","arguments":{...}}, {"final":"done"}]`.
         * This is the form `agent-task --script` and the HTTP route accept.
         */
        fun parse(json: String): List<Step> {
            val root = aiToolMapper.readTree(json)
            require(root.isArray) { "A script is a JSON array of steps." }
            return root.map { node ->
                when {
                    node.has("final") -> Step.Final(node.get("final").asText())
                    node.has("tool") -> Step.Call(
                        node.get("tool").asText(),
                        node.get("arguments")?.let { aiToolMapper.writeValueAsString(it) } ?: "{}"
                    )
                    else -> throw IllegalArgumentException("Script step needs 'tool' or 'final': $node")
                }
            }
        }
    }
}

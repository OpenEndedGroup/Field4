package afield.ai.core

import afield.AiChatModelIdentity
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
 * [Step.Call] runs one tool; [Step.Final] ends the turn with text. Arguments are JSON with
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
    )

    private val tools = ToolRegistry().also(::registerBuiltInAgentTools)
    private var cursor = 0
    val received = mutableListOf<Received>()

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
        onProgress: ((LlmProgressUpdate) -> Unit)?
    ): LlmSendResult {
        val available = tools.describe(ctx).mapNotNull {
            ((it as? kotlinx.serialization.json.JsonObject)?.get("name") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        received += Received(state.initialInstructions, userPrompt, fileText, maxToolRounds, available)

        val transcript = mutableListOf<LlmToolTranscriptEntry>()
        val latestByTool = mutableMapOf<String, String>()
        var rounds = 0
        var hitCap = false
        var text = ""
        while (cursor < script.size) {
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
                        tools.executeByName(ctx, step.tool, args)
                    } catch (e: Exception) {
                        if (isAiTurnCancellation(e)) throw e
                        mapOf("error" to true, "tool" to step.tool, "message" to (e.message ?: e::class.java.name))
                    }
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    val json = runCatching { aiToolMapper.writeValueAsString(result) }.getOrElse { result.toString() }
                    latestByTool[step.tool] = json
                    transcript += LlmToolTranscriptEntry(
                        step.tool, "scripted-$rounds", args, json.take(300), json, elapsedMs = elapsedMs
                    )
                    onProgress?.invoke(LlmProgressUpdate("${step.tool} finished", json.take(300), phase = "tool_result"))
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
            inputTokens = 0,
            outputTokens = 0
        )
    }

    private fun substitute(template: String, latest: Map<String, String>): String =
        Regex("""\$\{([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)}""").replace(template) { m ->
            val (tool, field) = m.destructured
            val node = latest[tool]?.let { runCatching { aiToolMapper.readTree(it) }.getOrNull() }
                ?: throw IllegalStateException("Script refers to \${$tool.$field} before any $tool result.")
            val value = node.get(field) ?: throw IllegalStateException("$tool result has no field '$field'.")
            // Insert as the inside of a JSON string: the template writes the quotes.
            aiToolMapper.writeValueAsString(if (value.isTextual) value.asText() else value.toString()).removeSurrounding("\"")
        }

    companion object {
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

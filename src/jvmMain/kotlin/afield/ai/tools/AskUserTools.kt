package afield.ai.tools

import afield.ai.conversation.AskedQuestion
import afield.ai.conversation.SuspendsTurn
import afield.ai.conversation.TurnSuspension
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Asking the person a question mid-task: [AskUserQuestionRequest] ends the turn with a [TurnSuspension] carrying
 * the question. The box chat ends its transcript with a question row (`AiChatBlock.questionRow`), which the panel
 * shows with a button per suggested answer; the person's next message, a button's or typed, is the answer, and
 * the next turn opens with this call's output ahead of it, as any suspended turn does.
 */

@JsonClassDescription("Ask the person a question and end this turn until they answer. Use it only when you are blocked on a decision that is " +
    "theirs to make (which of two designs, whether to overwrite their work, what a vague request means) and cannot settle it from the " +
    "document, the code or a sensible default; do not use it to ask permission for ordinary steps, or to ask whether you are done. " +
    "Say what you found so far before calling it. Returns at once with suspended=true; call no other tool after it in this turn. " +
    "The chat shows the question with a button per choice, and the person may also type any answer: their next message is the answer, " +
    "and you carry on from there. A blank question, or more than 6 choices, fails with success=false and the turn goes on. " +
    "e.g. question = `Keep the old mesh as a layer, or replace it?`, choices = [`Keep it as a layer`, `Replace it`].")
@AgentToolMeta("core", ToolExposure.DIRECT, effects = [ToolEffect.AGENT_STATE])
data class AskUserQuestionRequest(
    @JsonPropertyDescription("The one question, in a sentence or two, with whatever the person needs to answer it. Markdown is allowed.")
    val question: String,
    @JsonPropertyDescription("Up to 6 short suggested answers (a few words each), your recommendation first; empty to ask for a free-text answer.")
    val choices: List<String> = emptyList(),
)

/** [AskUserQuestionRequest]'s result. A set [suspension] ends the turn; the model reads only the rest. */
data class AskUserQuestionResult(val success: Boolean, val message: String, val suspended: Boolean = false,
    @get:JsonIgnore override val suspension: TurnSuspension? = null) : SummarisesForToolLog, SuspendsTurn {
    override fun toolLogSummary() = "success=$success suspended=$suspended $message"
}

internal const val MAX_QUESTION_CHOICES = 6

internal fun askUserQuestion(q: AskUserQuestionRequest): AskUserQuestionResult {
    val question = q.question.trim()
    if (question.isEmpty()) return AskUserQuestionResult(false, "The question is blank; write the question you need answered.")
    val choices = q.choices.map { it.replace('\n', ' ').trim() }.filter { it.isNotEmpty() }.distinct()
    if (choices.size > MAX_QUESTION_CHOICES)
        return AskUserQuestionResult(false, "${choices.size} choices is too many; offer at most $MAX_QUESTION_CHOICES, or none for a free-text answer.")
    return AskUserQuestionResult(true, "Your turn ends here. The person's next message is their answer to this question; carry on from it. " +
        "Call no other tool in this turn.", suspended = true, suspension = TurnSuspension(question = AskedQuestion(question, choices)))
}

internal fun registerAskUserTools(tools: ToolRegistry) {
    // Only where the turn can end and resume: the box chat's general turn, as for AwaitStudyBuildsRequest.
    tools.register(AskUserQuestionRequest::class.java, { it.suspendable }) { _, q -> askUserQuestion(q) }
}

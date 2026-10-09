package afield.ai.core

/**
 * A tool result that ends the model's turn when [suspension] is set: the bridge finishes the round it is
 * in, makes no further model request, and returns the suspension in [OpenAiIdeCodexBridge.SendResult].
 * Someone else resumes the conversation later (`StudyBuildAwaits` for `AwaitStudyBuildsRequest`), so the
 * time spent waiting costs no model calls.
 *
 * The round's tool outputs, this one's included, are not sent at once; [SuspendedRound] keeps them for the
 * next turn, which opens with them ahead of its own prompt.
 */
interface SuspendsTurn {
    val suspension: TurnSuspension?
}

/** The study-build jobs a suspended turn waits for, and how long it may wait. */
data class TurnSuspension(
    val jobs: List<AwaitedBuild>,
    /** Epoch milliseconds after which the conversation resumes whatever the jobs' state. */
    val deadlineMs: Long,
) {
    /** One round may await more than once (two runners): wait for every job, until the later deadline. */
    fun merge(other: TurnSuspension): TurnSuspension =
        TurnSuspension((jobs + other.jobs).distinctBy { Triple(it.boxId, it.name, it.jobId) }, maxOf(deadlineMs, other.deadlineMs))

    companion object {
        fun of(results: List<Any?>): TurnSuspension? =
            results.mapNotNull { (it as? SuspendsTurn)?.suspension }.reduceOrNull(TurnSuspension::merge)
    }
}

/** One awaited attempt: the runner (its box and name), the job, and what to call it in the chat. */
data class AwaitedBuild(
    val boxId: String,
    val name: String,
    val jobId: String,
    val stage: String = "",
    val label: String = "",
    /** The runner's directory, so a job can still be read from disk when the runner is not running. */
    val directory: String = "",
)

/**
 * The tool outputs of the round that suspended a turn, which the provider has not seen yet. The next turn
 * sends them first: on OpenAI's chained responses its `previous_response_id` is still the response that
 * made these calls, and every call must have an output before anything else can follow it; on Anthropic,
 * whose history this bridge keeps, the `tool_use` blocks need their `tool_result`s in the next user message.
 */
data class SuspendedRound(val outputs: List<PendingToolOutput>)

/** A finished call's output as the provider will receive it. [isError] marks a failed call (Anthropic). */
data class PendingToolOutput(val callId: String, val name: String, val outputJson: String, val isError: Boolean = false)

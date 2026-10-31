package afield.ai.conversation

/**
 * What a chat turn does when a tool throws. Three answers live here: which failures end the turn rather
 * than becoming the call's output, what the model reads in place of a result, and what is written back
 * for a call the turn ended before answering.
 *
 * The provider's thread has to hold an output for every call it made, so a turn that stops mid-round
 * still writes one for each call left outstanding; without it, every later turn in that chat is refused
 * for a function call with no output.
 */

/**
 * Whether a tool's [failure] must end the turn instead of becoming the call's output: the turn's own
 * cancellation, or a [VirtualMachineError] the JVM may not survive (OutOfMemoryError, InternalError, ...).
 * A [StackOverflowError] is not one of them: once it has unwound to the tool loop, the stack is back, so it
 * is answered like any other failure. Everything else a tool throws, every other [Error] included, is the
 * call's output: on 2026-10-18 a box tool's StackOverflowError ended a turn with its function call
 * unanswered, and every later turn in that chat was refused with "No tool output found for function call".
 */
internal fun aiToolFailureEndsTurn(failure: Throwable): Boolean =
    isAiTurnCancellation(failure) || (failure is VirtualMachineError && failure !is StackOverflowError)

/**
 * What the model reads when a tool threw [failure]. An exception keeps its own message, which a tool
 * writes for the model; an [Error] rarely has one, so it is named, with the frame it was thrown from.
 */
internal fun aiToolFailureMessage(failure: Throwable): String {
    if (failure is Exception) return failure.message ?: failure::class.java.name
    val frame = failure.stackTrace.firstOrNull()?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" +
        (it.fileName?.let { file -> "($file${if (it.lineNumber > 0) ":${it.lineNumber}" else ""})" } ?: "") }.orEmpty()
    return "Tool failed: ${failure::class.java.simpleName}$frame" + failure.message?.let { ": $it" }.orEmpty()
}

/**
 * The output given to a call the turn ended before answering, so that the provider's thread holds an
 * output for every call it made and the next turn can follow it. [failure] is what ended the turn.
 */
internal fun aiToolNotRunOutput(callId: String, name: String, failure: Throwable): PendingToolOutput {
    val reason = if (isAiTurnCancellation(failure)) "the turn was cancelled"
        else "the turn ended with ${failure::class.java.simpleName}${failure.message?.let { ": $it" }.orEmpty()}"
    val json = afield.ai.tools.aiToolMapper.writeValueAsString(mapOf("error" to true, "aborted" to true, "tool" to name,
        "message" to "Not finished: $reason before this call returned. It may have partly run; check before repeating it."))
    return PendingToolOutput(callId, name, json, isError = true)
}

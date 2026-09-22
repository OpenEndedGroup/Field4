package afield

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Content identity, independent of save time or sidecar location. */
internal fun sourceRevision(source: String): String = MessageDigest.getInstance("SHA-256")
    .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * Verdict for one awaited setup/fragment execution. A running stripe is not a verdict,
 * and completing this scope does not assert that a GPU frame has rendered.
 */
internal class ExecutionVerdict(val uid: String, val property: String, source: String) {
    val revision = sourceRevision(source)
    private val failures = ArrayList<String>()

    @Synchronized fun observe(message: Message) {
        val payload = message.payload as? JsonObject ?: return
        fun value(key: String) = (payload[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (value("uid") != uid) return
        if (message.name == "execution-verdict") {
            if (value("success") == "false" && failures.size < 20)
                failures.add("$uid/$property [$revision]: " + value("error").ifBlank { "Execution failed before setup completed" })
            return
        }
        if (message.name !in setOf("output", "peer-output") || value("kind") !in setOf("error", "err")) return
        val text = value("html").trim()
        // An empty diagnostic clears an earlier editor marker; it is not a failure.
        if (text.isEmpty() || failures.size >= 20) return
        val at = value("property").ifBlank { property }
        failures.add("$uid/$at:${value("line")} [$revision]: ${text.take(4096)}")
    }

    @Synchronized fun check() {
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}

/** Last attempted and last successful setup, stored with the box rather than in a global UID map. */
internal object ExecutionReports {
    private const val KEY = "_executionReports"
    @Suppress("UNCHECKED_CAST")
    private fun reports(box: Box): java.util.concurrent.ConcurrentHashMap<String, ExecutionReport> = synchronized(box) {
        box.properties.getOrPut(KEY) { java.util.concurrent.ConcurrentHashMap<String, ExecutionReport>() }
            as java.util.concurrent.ConcurrentHashMap<String, ExecutionReport>
    }
    fun latest(box: Box, property: String, successful: Boolean = false): ExecutionReport? =
        reports(box)[if (successful) "success:$property" else "attempt:$property"]

    fun publish(box: Box, report: ExecutionReport) {
        reports(box)["attempt:${report.property}"] = report
        if (report.success && report.stage == "setup-complete" && report.mode != "fragment") reports(box)["success:${report.property}"] = report
    }
}

internal class ExecutionTrace(
    private val box: Box, private val property: String, source: String,
    private val requestId: String, private val mode: String,
    private val downstream: ((Message) -> Unit)?
) {
    private val verdict = ExecutionVerdict(box.id, property, source)
    private var completed = false

    /**
     * A caller that sent no request id is not awaiting a receipt, so there is nobody to correlate
     * one with. The trace still exists and still forwards, which is what lets an engine construct
     * one unconditionally and thread it through a single code path instead of carrying a nullable
     * reply and a branch at every output site.
     */
    private val awaited = requestId.isNotBlank()

    val reply: (Message) -> Unit =
        if (awaited) { message -> verdict.observe(message); downstream?.invoke(message) }
        else { message -> downstream?.invoke(message) }

    fun finish(success: Boolean, reason: String = "") {
        if (!awaited || completed) return
        completed = true
        val failure = runCatching { verdict.check() }.exceptionOrNull()?.message.orEmpty()
        val ok = success && failure.isEmpty()
        val result = ExecutionReport(
            box.id, property, verdict.revision, requestId, mode,
            if (ok) "setup-complete" else "failed", ok,
            listOf(reason, failure).filter { it.isNotBlank() }.joinToString("\n")
                .ifBlank { if (ok) "" else "Execution did not complete, or its launch was cancelled" }.take(32768)
        )
        ExecutionReports.publish(box, result)
        downstream?.invoke(Message("execution-verdict").payload(result))
    }

    companion object {
        /**
         * The receipt for one engine execution, made where the request arrives.
         *
         * Every engine but Kotlin's dispatches its work onto the runloop and returns before any of
         * it has run, so a receipt can only be *finished* from inside that dispatch — in the same
         * `finally` that lowers the running stripe. Building it here and finishing it there is what
         * keeps "the box ran" and "the receipt says the box ran" the same event rather than two
         * that happen to agree.
         *
         * The engine's own success flag is necessary but not sufficient: output routed through
         * [reply] is watched too, so a box that reports completion while printing an error still
         * yields a failed receipt. That is the rule this file is for — a receipt is earned by
         * observed behaviour, never invented from an engine's optimism.
         */
        fun forEngine(box: Box, execute: Execute, mode: String, reply: ((Message) -> Unit)?) =
            ExecutionTrace(box, execute.property, execute.code, execute.requestId, mode, reply)
    }
}

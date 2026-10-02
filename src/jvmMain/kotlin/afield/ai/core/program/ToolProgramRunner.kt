package afield.ai.core.program

import afield.Runloop
import afield.ai.core.CarriesToolImages
import afield.ai.core.SummarisesForToolLog
import afield.ai.core.ToolExecutionContext
import afield.ai.core.ToolImage
import afield.ai.core.ToolRegistry
import afield.ai.core.ToolFace
import afield.ai.core.CallOrigin
import afield.ai.core.aiToolMapper
import afield.ai.core.isAiTurnCancellation
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.SandboxPolicy
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one [RunToolProgramRequest] in a fresh, locked-down GraalJS context. See [RunToolProgramRequest]'s
 * file overview for what a program is for; this is how it is kept to its tools.
 *
 * ## The boundary
 *
 * Only strings cross it. The guest gets five host functions (`__call`, `__out`, `__show`, `__describe`,
 * `__settle`) and one JSON string of tool names; the [PRELUDE] builds the friendly `tools.X(...)`,
 * `text(...)` and `show(...)` surface from those in JS. The context denies host access, IO, threads,
 * processes, native access, polyglot access and the environment, under [SandboxPolicy.CONSTRAINED], with
 * a statement limit — so a program cannot reach a Java object even by accident.
 *
 * ## Ending a program
 *
 * A program ends by settling its promise ([stage] `ok` or `runtime`), by a syntax error, by running out of
 * statements, calls or time, or because its turn was cancelled. Time and cancellation are a watchdog's job:
 * it closes the context from its own thread, which Graal honours the next time the program thread is in
 * guest code — so a tool call already running is allowed to finish. A cancellation is rethrown as a
 * [CancellationException] so the turn ends rather than reporting a tool error, matching every bridge's
 * `isAiTurnCancellation` handling.
 *
 * ## Threads
 *
 * The program runs on the calling thread, which is the agent loop's: an IO thread for a panel turn, a
 * daemon for an agent task, the backdoor's scope for MCP. Never the Runloop — the graph tools a program
 * calls hop onto the Runloop and wait for it, which would deadlock there, so [run] refuses.
 */
internal class ToolProgramRunner(private val registry: ToolRegistry) {

    fun run(ctx: ToolExecutionContext, request: RunToolProgramRequest): RunToolProgramResult {
        check(!Runloop.isMainThread) { "RunToolProgramRequest cannot run on the Runloop thread." }
        val started = System.nanoTime()
        val timeoutMs = (request.timeoutSeconds.takeIf { it.isFinite() && it > 0 } ?: 60.0)
            .coerceAtMost(MAX_TIMEOUT_SECONDS) * 1000.0
        val state = ProgramState(
            ctx = ctx.copy(withheld = ctx.withheld + RUN_TOOL_PROGRAM),
            // OpenAI's strict schemas make every field required, so 0 arrives meaning "the default".
            maxCalls = (if (request.maxCalls <= 0) 40 else request.maxCalls).coerceAtMost(MAX_CALLS),
            deadlineNanos = started + (timeoutMs * 1_000_000).toLong(),
        )
        val callable = callableNames(ctx)
        val owner = Thread.currentThread()
        val context = newContext()
        var closing = false
        val tick = watchdog.scheduleAtFixedRate({
            val reason = when {
                owner.isInterrupted -> StopReason.CANCELLED
                System.nanoTime() > state.deadlineNanos -> StopReason.TIMEOUT
                else -> null
            }
            if (reason != null) state.stop.compareAndSet(null, reason)
            // Graal's close(true) interrupts whatever the thread is doing, host code included. A cancelled
            // turn wants exactly that; a timeout does not — it would cut a RunBox off mid-wait and the
            // interrupt would read as the turn being cancelled. So on a timeout, wait for a running tool
            // call to come back ([ProgramState.call] refuses the next one), then close.
            val stopping = state.stop.get()
            if (!closing && stopping != null && (stopping == StopReason.CANCELLED || !state.inCall)) {
                closing = true
                runCatching { context.close(true) }
            }
        }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)

        var stage = "ok"
        var message = ""
        try {
            val bindings = context.getBindings("js")
            bindings.putMember("__names", aiToolMapper.writeValueAsString(jsNames(callable)))
            bindings.putMember("__call", ProxyExecutable { args -> state.call(args[0].asString(), args[1].asString()) })
            bindings.putMember("__out", ProxyExecutable { args -> state.out(args[0].asString()); null })
            bindings.putMember("__show", ProxyExecutable { args -> state.show(args[0].asInt()); null })
            bindings.putMember("__describe", ProxyExecutable { args -> describe(ctx, args[0].asString()) })
            bindings.putMember("__settle", ProxyExecutable { args ->
                state.settle(args[0].asBoolean(), args[1].asString()); null
            })
            context.eval(Source.newBuilder("js", PRELUDE, "prelude.js").buildLiteral())
            context.eval(Source.newBuilder("js", wrap(request.code), "program.js").buildLiteral())
            // Every tool call is synchronous, so the program's promise has nothing to wait for but
            // microtasks, which Graal drains when the top-level eval returns. Drain once more in case,
            // then give up: a program awaiting something that never resolves has nothing to resolve it.
            if (state.settled == null) context.eval("js", "0")
            when (val settled = state.settled) {
                null -> { stage = "unsettled"; message = "The program awaited something that never resolves (there are no timers)." }
                else -> if (!settled) { stage = "runtime"; message = state.failure.take(2000) }
            }
        } catch (e: PolyglotException) {
            val fatal = state.fatal.get()
            when {
                fatal != null -> throw fatal
                state.stop.get() == StopReason.CANCELLED -> throw CancellationException("tool program cancelled with the turn")
                state.stop.get() == StopReason.TIMEOUT -> { stage = "timeout"; message = timeoutMessage(timeoutMs) }
                e.isSyntaxError -> {
                    stage = "syntax"
                    message = "${e.message}" + (e.sourceLocation?.let { " (line ${it.startLine})" } ?: "")
                }
                e.isCancelled || (e.message ?: "").contains("Statement count limit") -> {
                    stage = "statement-limit"; message = "The program ran more than $STATEMENT_LIMIT statements."
                }
                e.isHostException && isAiTurnCancellation(e.asHostException()) -> throw e.asHostException()
                else -> { stage = "runtime"; message = (e.message ?: e.toString()).take(2000) }
            }
        } finally {
            tick.cancel(false)
            runCatching { context.close(true) }
        }
        state.fatal.get()?.let { throw it }
        if (state.stop.get() == StopReason.TIMEOUT) {
            // Our own close(true) may have interrupted this thread on its way out; that interrupt is ours,
            // not the turn's, and leaving it set would cancel the turn at its next wait.
            Thread.interrupted()
            // The refused next call surfaces in JS as a rejection, which would otherwise read as `runtime`.
            if (stage != "syntax") { stage = "timeout"; message = timeoutMessage(timeoutMs) }
        }
        if (state.stop.get() == StopReason.CANCELLED || owner.isInterrupted) {
            throw CancellationException("tool program cancelled with the turn")
        }
        if (state.capHit && stage == "ok") stage = "call-cap"
        if (state.capHit && message.isBlank()) message = "The program reached its limit of ${state.maxCalls} tool calls."

        val returned = state.returnedJson?.let { json ->
            if (json.length <= MAX_RETURNED_CHARS) runCatching { aiToolMapper.readTree(json) }.getOrNull()
            else aiToolMapper.createObjectNode().put("truncated", json.length).put("preview", json.take(2000))
        }
        val visibleCalls = if (state.records.size <= MAX_VISIBLE_CALLS) state.records
            else state.records.take(MAX_VISIBLE_CALLS / 2) + state.records.takeLast(MAX_VISIBLE_CALLS / 2)
        return RunToolProgramResult(
            success = stage == "ok",
            stage = stage,
            message = message,
            output = state.output.toString(),
            returned = returned,
            calls = visibleCalls,
            totalCalls = state.records.size,
            failedCalls = state.records.count { !it.ok },
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
            truncated = state.outputTruncated || visibleCalls.size < state.records.size ||
                (state.returnedJson?.length ?: 0) > MAX_RETURNED_CHARS,
            imagesShown = state.images.size,
            toolImages = state.images.toList(),
            nestedCalls = state.nested.toList(),
        )
    }

    private fun timeoutMessage(timeoutMs: Double) =
        "Stopped after ${"%.1f".format(timeoutMs / 1000)} s; the output and calls so far are below."

    /** What the program may call: everything this conversation is offered, less [NOT_CALLABLE_FROM_PROGRAMS]. */
    internal fun callableNames(ctx: ToolExecutionContext): List<String> =
        registry.describe(ctx, ToolFace.PROGRAM).mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
            .filter { it !in NOT_CALLABLE_FROM_PROGRAMS }

    private fun describe(ctx: ToolExecutionContext, name: String): String {
        val full = if (registry.isAvailable(ctx, name)) name else "${name}Request"
        val descriptor = registry.describeOne(ctx, full) ?: return "null"
        return descriptor.toString()
    }

    private inner class ProgramState(val ctx: ToolExecutionContext, val maxCalls: Int, val deadlineNanos: Long) {
        val stop = AtomicReference<StopReason?>(null)
        /** True while a tool handler is running, so the watchdog lets it finish before closing on a timeout. */
        @Volatile
        var inCall = false
        /** A cancellation raised inside a tool call; rethrown after the program unwinds, whatever JS caught. */
        val fatal = AtomicReference<Throwable?>(null)
        val output = StringBuilder()
        var outputTruncated = false
        val records = mutableListOf<ProgramToolCall>()
        val nested = mutableListOf<NestedToolCall>()
        val results = mutableMapOf<Int, Any>()
        val images = mutableListOf<ToolImage>()
        var capHit = false
        var settled: Boolean? = null
        var failure = ""
        var returnedJson: String? = null

        fun call(name: String, argsJson: String): String {
            fatal.get()?.let { throw it }
            if (stop.get() != null) throw CancellationException("tool program stopped")
            val n = records.size + 1
            if (n > maxCalls) {
                capHit = true
                return envelope(n, ok = false, error = "Call limit reached ($maxCalls calls); no further tool calls run.")
            }
            if (name in NOT_CALLABLE_FROM_PROGRAMS) {
                return envelope(n, ok = false, error = "$name cannot be called from a program.")
            }
            val args = clampTimeout(argsJson)
            ctx.progress?.invoke("program: $name ($n)")
            val t0 = System.nanoTime()
            val outcome: Any = try {
                inCall = true
                try { registry.executeByName(ctx, name, args, CallOrigin.PROGRAM) } finally { inCall = false }
            } catch (e: Exception) {
                if (isAiTurnCancellation(e)) { fatal.compareAndSet(null, e); throw e }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val error = e.message ?: e::class.java.name
                record(n, name, args, ok = false, ms = ms, summary = error, error = error, resultJson = "")
                return envelope(n, ok = false, error = error)
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val json = runCatching { aiToolMapper.writeValueAsString(outcome) }.getOrElse { outcome.toString() }
            val ok = !failedResult(json)
            val summary = (outcome as? SummarisesForToolLog)?.toolLogSummary() ?: json
            results[n] = outcome
            record(n, name, args, ok = ok, ms = ms, summary = summary, error = null, resultJson = json)
            return "{\"ok\":true,\"n\":$n,\"result\":$json}"
        }

        private fun record(n: Int, name: String, args: String, ok: Boolean, ms: Long, summary: String, error: String?, resultJson: String) {
            records += ProgramToolCall(n, name, args.take(MAX_ARG_CHARS), ok, ms, summary.take(MAX_SUMMARY_CHARS), error?.take(MAX_SUMMARY_CHARS))
            nested += NestedToolCall(n, name, args, resultJson.take(MAX_NESTED_RESULT_CHARS), summary.take(300), ok, ms)
        }

        private fun envelope(n: Int, ok: Boolean, error: String): String =
            aiToolMapper.writeValueAsString(mapOf("ok" to ok, "n" to n, "error" to error))

        /** A nested call may not outlive the program: its own `timeoutSeconds` is cut to the time left. */
        private fun clampTimeout(argsJson: String): String {
            val node = runCatching { aiToolMapper.readTree(argsJson.ifBlank { "{}" }) }.getOrNull() as? ObjectNode
                ?: return argsJson
            val left = ((deadlineNanos - System.nanoTime()) / 1e9).coerceAtLeast(1.0)
            val asked = node.get("timeoutSeconds")?.takeIf { it.isNumber }?.asDouble() ?: return argsJson
            if (asked <= left) return argsJson
            node.put("timeoutSeconds", left)
            return aiToolMapper.writeValueAsString(node)
        }

        fun out(text: String) {
            if (outputTruncated) return
            val room = MAX_OUTPUT_CHARS - output.length
            if (text.length + 1 > room) {
                output.append(text.take((room - 1).coerceAtLeast(0))).append('\n')
                outputTruncated = true
            } else {
                output.append(text).append('\n')
            }
        }

        fun show(n: Int) {
            val carried = (results[n] as? CarriesToolImages)?.toolImages.orEmpty().filter { it.dataUrl.isNotBlank() }
            for (image in carried) {
                if (images.size >= MAX_IMAGES) return
                images += image
            }
        }

        fun settle(ok: Boolean, payload: String) {
            if (settled != null) return
            settled = ok
            if (ok) returnedJson = payload.takeIf { it != "null" } else failure = payload
        }
    }

    private enum class StopReason { TIMEOUT, CANCELLED }

    companion object {
        const val MAX_TIMEOUT_SECONDS = 300.0
        const val MAX_CALLS = 200
        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_RETURNED_CHARS = 16_000
        const val MAX_IMAGES = 4
        const val MAX_VISIBLE_CALLS = 60
        const val MAX_ARG_CHARS = 160
        const val MAX_SUMMARY_CHARS = 160
        const val MAX_NESTED_RESULT_CHARS = 8_000
        const val STATEMENT_LIMIT = 10_000_000L
        private const val WATCHDOG_MS = 100L

        private val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ai4-tool-program-watchdog").apply { isDaemon = true }
        }

        /** One engine for every program, so compiled prelude code is shared; contexts are never shared. */
        private val engine: Engine by lazy {
            Engine.newBuilder("js")
                .sandbox(SandboxPolicy.CONSTRAINED)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.WarnInterpreterOnly", "false")
                .build()
        }

        /** One instance, because contexts on a shared engine share code only under the same limits. */
        private val limits: ResourceLimits = ResourceLimits.newBuilder().statementLimit(STATEMENT_LIMIT, null).build()

        internal fun newContext(): Context =
            Context.newBuilder("js")
                .engine(engine)
                .sandbox(SandboxPolicy.CONSTRAINED)
                // The preset the CONSTRAINED policy requires (HostAccess.NONE is rejected: it allows mutable
                // target mappings). It exposes no Java member that is not @HostAccess.Export-ed, and the guest
                // is never handed a Java object anyway: only strings and the five proxies cross.
                .allowHostAccess(HostAccess.CONSTRAINED)
                .allowHostClassLookup { false }
                .allowHostClassLoading(false)
                .allowIO(IOAccess.NONE)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .allowCreateProcess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowValueSharing(false)
                .allowInnerContextOptions(false)
                .allowExperimentalOptions(false)
                .out(ByteArrayOutputStream())
                .err(ByteArrayOutputStream())
                .`in`(InputStream.nullInputStream())
                .resourceLimits(limits)
                .build()

        /** `RunBoxRequest` → `RunBox`; a name that would collide, or has no suffix, keeps its full spelling. */
        internal fun jsNames(fullNames: List<String>): List<List<String>> {
            val short = fullNames.associateWith { it.removeSuffix("Request") }
            val counts = short.values.groupingBy { it }.eachCount()
            return fullNames.flatMap { full ->
                val s = short.getValue(full)
                if (s != full && counts.getValue(s) == 1 && s !in fullNames) listOf(listOf(s, full), listOf(full, full))
                else listOf(listOf(full, full))
            }
        }

        /** The program body inside an async function, on line 1 so error line numbers match the model's. */
        internal fun wrap(code: String): String =
            "(async () => {" + code + "\n})().then(" +
                "v => __settle(true, JSON.stringify(v === undefined ? null : v) ?? \"null\"), " +
                "e => __settle(false, String((e && e.stack) || e)));"

        internal const val PRELUDE: String = """
// Host class lookup and loading are already denied, so these could not reach Java; removing them means a
// program never sees them either (the shell-ish load/print/quit family included).
for (const g of ["Java", "Packages", "java", "javax", "javafx", "com", "org", "edu", "net", "Polyglot",
                 "load", "loadWithNewGlobal", "print", "printErr", "quit", "exit", "read", "readline", "readbuffer"]) {
  try { delete globalThis[g]; } catch (e) {}
}
const tools =Object.freeze(Object.fromEntries(JSON.parse(__names).map(([js, full]) => [js, (a) => __invoke(full, a)])));
function __invoke(full, a) {
  const env = JSON.parse(__call(full, JSON.stringify(a === undefined ? {} : a)));
  if (!env.ok) { const e = new Error(full + ": " + env.error); e.tool = full; e.call = env.n; throw e; }
  const r = env.result;
  if (r !== null && typeof r === "object") Object.defineProperty(r, "${'$'}call", { value: env.n, enumerable: false });
  return r;
}
function text(...parts) { __out(parts.map(p => typeof p === "string" ? p : JSON.stringify(p)).join(" ")); }
const emit = text;
const console = Object.freeze({ log: text, info: text, warn: text, error: text });
function show(r) { __show(typeof r === "number" ? r : ((r && r.${'$'}call) || -1)); }
function describe(name) { return JSON.parse(__describe(String(name))); }
"""

        /** `success: false` or `error: true` at the top of a result, the same rule as the eval's tool records. */
        internal fun failedResult(json: String): Boolean = runCatching {
            val node: JsonNode = aiToolMapper.readTree(json)
            node.get("success")?.asBoolean(true) == false || node.get("error")?.asBoolean(false) == true
        }.getOrDefault(false)
    }
}

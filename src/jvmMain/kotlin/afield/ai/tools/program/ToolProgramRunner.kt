package afield.ai.tools.program

import afield.runtime.lifecycle.Runloop
import afield.ai.tools.CarriesToolImages
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolImage
import afield.ai.tools.ToolRegistry
import afield.ai.tools.ToolFace
import afield.ai.tools.CallOrigin
import afield.ai.tools.aiToolMapper
import afield.ai.conversation.isAiTurnCancellation
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
 * Only strings cross it. The guest gets six host functions (`__call`, `__out`, `__progress`, `__show`,
 * `__describe`, `__settle`) and one JSON string of tool names; the [PRELUDE] builds the friendly
 * `tools.X(...)`, `tools.X.raw(...)`, `check(...)`, `progress(...)`, `text(...)` and `show(...)` surface from
 * those in JS. The context denies host access, IO, threads,
 * processes, native access, polyglot access and the environment, under [SandboxPolicy.CONSTRAINED], with
 * a statement limit — so a program cannot reach a Java object even by accident.
 *
 * ## Ending a program
 *
 * A program ends by settling its promise (stage `ok`; or `tool-failed` for an uncaught failing call,
 * `check-failed` for an uncaught `check`, `runtime` for any other error), by a syntax error, by running out
 * of statements, calls or time, or because its turn was cancelled. Time and cancellation are a watchdog's job:
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
        val context = buildContextInterruptibly()
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
            bindings.putMember("__names", aiToolMapper.writeValueAsString(jsNames(callable) + aliases(ctx, callable)))
            bindings.putMember("__call", ProxyExecutable { args -> state.call(args[0].asString(), args[1].asString()) })
            bindings.putMember("__out", ProxyExecutable { args -> state.out(args[0].asString()); null })
            bindings.putMember("__progress", ProxyExecutable { args -> state.progress(args[0].asString()); null })
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
                else -> if (!settled) { stage = state.failureStage; message = state.failure.take(2000) }
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
                (state.returnedJson?.length ?: 0) > MAX_RETURNED_CHARS || state.nestedClipped > 0,
            nestedClipped = state.nestedClipped,
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
        /** How an unsettled-by-error program ended: `tool-failed`, `check-failed` or `runtime`. */
        var failureStage = "runtime"
        var returnedJson: String? = null
        /** Nested results too large for the transcript, stored whole in [ToolEvidence]. */
        var nestedClipped = 0
        /** The program's own latest progress line, which prefixes the per-call ones the panel shows. */
        var lastProgress: String? = null
        var progressLines = 0

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
            ctx.progress?.invoke((lastProgress?.let { "$it · " } ?: "program: ") + "${name.removeSuffix("Request")} ($n)")
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
            val failure = if (ok) null else failureText(json)
            record(n, name, args, ok = ok, ms = ms, summary = summary, error = failure, resultJson = json)
            // The program gets the whole result either way; `failed` decides whether tools.X() throws.
            return "{\"ok\":true,\"failed\":${!ok},\"n\":$n,\"failure\":${aiToolMapper.writeValueAsString(failure ?: "")},\"result\":$json}"
        }

        private fun record(n: Int, name: String, args: String, ok: Boolean, ms: Long, summary: String, error: String?, resultJson: String) {
            val bytes = resultJson.toByteArray(Charsets.UTF_8)
            val sha = ToolEvidence.sha256(bytes)
            // A result too large for the transcript is kept whole in ToolEvidence, and the transcript gets a
            // small stub that is still valid JSON and says where the rest is; a plain cut would be neither.
            val evidence = if (resultJson.length <= MAX_NESTED_RESULT_CHARS) null
                else runCatching { ToolEvidence.store.put(resultJson) }.getOrNull()
            val stored = when {
                resultJson.length <= MAX_NESTED_RESULT_CHARS -> resultJson
                else -> {
                    nestedClipped++
                    aiToolMapper.writeValueAsString(linkedMapOf(
                        "clipped" to true, "bytes" to bytes.size, "sha256" to sha,
                        "evidence" to (evidence?.id ?: "not stored"), "head" to resultJson.take(NESTED_HEAD_CHARS),
                    ))
                }
            }
            records += ProgramToolCall(n, name, args.take(MAX_ARG_CHARS), ok, ms, summary.take(MAX_SUMMARY_CHARS),
                error?.take(MAX_SUMMARY_CHARS), evidence = evidence?.id)
            nested += NestedToolCall(n, name, args, stored, summary.take(300), ok, ms,
                resultBytes = bytes.size, resultSha256 = sha, evidence = evidence?.id)
        }

        fun progress(text: String) {
            if (progressLines >= MAX_PROGRESS_LINES) return
            progressLines++
            val line = text.replace('\n', ' ').trim().take(MAX_PROGRESS_CHARS).ifEmpty { return }
            lastProgress = line
            ctx.progress?.invoke(line)
        }

        private fun envelope(n: Int, ok: Boolean, error: String): String =
            aiToolMapper.writeValueAsString(mapOf("ok" to ok, "n" to n, "error" to error))

        /**
         * A nested call may not outlive the program: its own `timeoutSeconds` or `waitSeconds` is cut to the
         * time left. Cutting a wait shortens how long the call waits, not the work it started: a tool that
         * starts a job and waits for it returns its handle when the wait ends, and the program (or the next
         * turn) observes that same job. Nothing here retries a call.
         */
        private fun clampTimeout(argsJson: String): String {
            val node = runCatching { aiToolMapper.readTree(argsJson.ifBlank { "{}" }) }.getOrNull() as? ObjectNode
                ?: return argsJson
            val left = ((deadlineNanos - System.nanoTime()) / 1e9).coerceAtLeast(1.0)
            var changed = false
            for (field in TIMEOUT_FIELDS) {
                val asked = node.get(field)?.takeIf { it.isNumber }?.asDouble() ?: continue
                if (asked > left) { node.put(field, left); changed = true }
            }
            return if (changed) aiToolMapper.writeValueAsString(node) else argsJson
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
            if (ok) { returnedJson = payload.takeIf { it != "null" }; return }
            // The rejection arrives as {kind, message, tool, call} from the wrapper.
            val node = runCatching { aiToolMapper.readTree(payload) }.getOrNull()
            failureStage = when (node?.get("kind")?.asText()) {
                "ToolFailure" -> "tool-failed"
                "CheckFailed" -> "check-failed"
                else -> "runtime"
            }
            failure = node?.get("message")?.asText() ?: payload
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
        /** How much of a clipped result the transcript stub keeps as a preview. */
        const val NESTED_HEAD_CHARS = 1_000
        const val MAX_PROGRESS_LINES = 200
        const val MAX_PROGRESS_CHARS = 200
        const val STATEMENT_LIMIT = 10_000_000L
        private const val WATCHDOG_MS = 100L
        /** Arguments that say how long a nested call may wait; each is cut to the time the program has left. */
        internal val TIMEOUT_FIELDS = listOf("timeoutSeconds", "waitSeconds")

        /**
         * Contexts are built on this thread, not the turn's: building one can swallow an interrupt that
         * arrives meanwhile, which used to leave a cancelled turn's program running until its own timeout.
         * The turn thread waits on the build with an interruptible get, so a cancellation always lands.
         */
        private val builder = Executors.newCachedThreadPool { r -> Thread(r, "ai4-tool-program-context").apply { isDaemon = true } }

        /** Replaceable in tests, to make the build slow enough to interrupt. */
        @Volatile
        internal var contextFactory: () -> Context = { newContext() }

        internal fun buildContextInterruptibly(): Context {
            if (Thread.currentThread().isInterrupted) throw CancellationException("tool program cancelled with the turn")
            val future = builder.submit<Context> { contextFactory() }
            return try {
                future.get()
            } catch (e: InterruptedException) {
                // The turn was cancelled while the context was built: close it when it arrives, and end.
                builder.submit { runCatching { future.get().close(true) } }
                throw CancellationException("tool program cancelled with the turn").apply { initCause(e) }
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }

        /** The text a failed result gives: its `message`, `error` text or `errors`, else the start of its JSON. */
        internal fun failureText(json: String): String = runCatching {
            val node: JsonNode = aiToolMapper.readTree(json)
            listOf("message", "error", "errors", "reason").firstNotNullOfOrNull { key ->
                node.get(key)?.takeIf { !it.isBoolean && !it.isNull }?.let { if (it.isTextual) it.asText() else it.toString() }
            } ?: json.take(200)
        }.getOrDefault(json.take(200))

        /**
         * Box-authored tools reach programs under their own names as well as their wire names, so the spelling
         * the docs and the tool's author use — `tools["rectsl.run"]` — works beside `tools.rectsl_run`.
         */
        internal fun aliases(ctx: ToolExecutionContext, callable: List<String>): List<List<String>> {
            val taken = callable.toSet()
            return ctx.dynamicTools.filter { it.wireName in taken && it.name != it.wireName && it.name !in taken }
                .map { listOf(it.name, it.wireName) }
        }

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
                "e => __settle(false, JSON.stringify({kind: (e && e.name) || \"Error\", message: String((e && e.stack) || e)})));"

        internal const val PRELUDE: String = """
// Host class lookup and loading are already denied, so these could not reach Java; removing them means a
// program never sees them either (the shell-ish load/print/quit family included).
for (const g of ["Java", "Packages", "java", "javax", "javafx", "com", "org", "edu", "net", "Polyglot",
                 "load", "loadWithNewGlobal", "print", "printErr", "quit", "exit", "read", "readline", "readbuffer"]) {
  try { delete globalThis[g]; } catch (e) {}
}
// tools.X(args) returns X's result, and throws a ToolFailure when the call could not run or its result says it
// failed (success: false, error: true): a failed step stops the steps after it unless the program catches it.
// e.result is the failed result. tools.X.raw(args) never throws for a failure: it returns the result as it is,
// or {error: true, message} for a call that could not run, for programs that look at failures on purpose.
function __fn(js, full) {
  const f = (a) => __invoke(js, full, a, false);
  f.raw = (a) => __invoke(js, full, a, true);
  return Object.freeze(f);
}
const __exact = Object.freeze(Object.fromEntries(JSON.parse(__names).map(([js, full]) => [js, __fn(js, full)])));
// Lenient spelling: routing_read, routingRead and RoutingRead reach routing.read when exactly one offered tool
// matches, ignoring case, punctuation, a Request suffix and a box tool's _field_..._<hash> wire wrapping. Only
// offered names are indexed, so nothing withheld becomes reachable; two candidates resolve to nothing.
function __norm(n) {
  return String(n).toLowerCase().replace(/^_field_/, "").replace(/_[0-9a-f]{12,}$/, "").replace(/request$/, "").replace(/[^a-z0-9]/g, "");
}
const __lenient = {};
for (const [js, full] of JSON.parse(__names)) { const k = __norm(js); (__lenient[k] = __lenient[k] || new Map()).set(full, __exact[js]); }
const tools = new Proxy(__exact, {
  get(target, name) {
    if (typeof name !== "string" || name in target) return target[name];
    const hits = __lenient[__norm(name)];
    return hits && hits.size === 1 ? hits.values().next().value : undefined;
  }
});
class ToolFailure extends Error {
  constructor(message, tool, call, result) { super(message); this.name = "ToolFailure"; this.tool = tool; this.call = call; this.result = result; }
}
class CheckFailed extends Error {
  constructor(message, details) { super(message); this.name = "CheckFailed"; this.details = details; }
}
function __invoke(js, full, a, raw) {
  const env = JSON.parse(__call(full, JSON.stringify(a === undefined ? {} : a)));
  if (!env.ok) {
    if (raw) return { error: true, message: env.error };
    throw new ToolFailure(js + " (call " + env.n + "): " + env.error, full, env.n, null);
  }
  const r = env.result;
  if (r !== null && typeof r === "object") Object.defineProperty(r, "${'$'}call", { value: env.n, enumerable: false });
  if (env.failed && !raw) throw new ToolFailure(js + " (call " + env.n + ") failed: " + env.failure, full, env.n, r);
  return r;
}
// check(condition, message, details): stop the program here unless condition holds. A tool that ran and reports
// a verdict (qualified: false, held, warnings) has not failed; check is how a program stops on that verdict.
function check(condition, message, details) {
  if (!condition) throw new CheckFailed("check failed: " + (message || "condition was false") +
    (details === undefined ? "" : " " + JSON.stringify(details)), details);
  return true;
}
// progress(...): a line the person sees in the panel now; text(...) lines come back to the model at the end.
function progress(...parts) { __progress(parts.map(p => typeof p === "string" ? p : JSON.stringify(p)).join(" ")); }
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

package afield.remotejs

import afield.*
import afield.logger.lprintln
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * SPIKE — a "Kotlin in the head" main language. A box flagged `mainLanguage = "kotlinjs"` (or its
 * legacy [isKotlinJsRoot] root flag) compiles its Kotlin source to JavaScript via
 * [KotlinToJsCompiler] (PLAIN_DCE: self-contained, tree-shaken, eval-able) and ships it to the
 * connected browser / visionOS head over the same WebSocket transport RemoteJS uses.
 *
 * This reuses RemoteJS's transport wholesale ([compileAndSendKotlin] → [executeRemoteJS] with
 * `doASTA = false`); it only swaps *what runs the box* (Kotlin→JS compile) for the JS path. Kotlin
 * gets native operator overloading (no ASTA), real types, and compile-time diagnostics at the
 * source line. See dev_docs/kotlin_to_js_spike.md.
 */

var Box.isKotlinJsRoot by Delegation<Boolean?>()

// Kotlin→JS compiles are CPU-heavy (~0.6–1.2s); keep them off the main runloop.
private val kotlinJsCompilePool = Executors.newSingleThreadExecutor { r ->
    Thread(r, "kotlinjs-compile").apply { isDaemon = true }
}

// Completion hits the out-of-process AA sidecar over HTTP; keep it off the runloop and separate from
// the compile pool so completion never queues behind a compile.
private val kotlinJsCompletionPool = Executors.newFixedThreadPool(2) { r ->
    Thread(r, "kotlinjs-complete").apply { isDaemon = true }
}

fun kotlinJsTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isKotlinJsRoot == true || it.isMainLanguage("kotlinjs") },
        runtimeClass = KotlinJsExecution::class,
        newExecution = { _ -> KotlinJsExecution() },
        // code_language: drives the editor's Monaco highlighter. Lowercase "kotlinjs" matches the
        // registered Monaco language (kotlinjs.js) across all editor resolution paths (one lowercases
        // it, others use it raw) — reuses Kotlin highlighting without the JVM-Kotlin providers.
        language = "kotlinjs",
        customization = "",
        notifyCategory = "kotlinjs",
    )
}

@RunsAt(plugins)
fun installKotlinJs() {
    TopologyWatcher("kotlinjs") { kotlinJsTopologyNeedsUpdating(it) }.start()
}

@RunsAt(early)
fun installKotlinJsMainLanguage() {
    Box.ioPersistentProperties.add("isKotlinJsRoot")
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = "kotlinjs",
            label = "Kotlin → head",
            runtimeClass = KotlinJsExecution::class,
            setLegacyRoot = { box, enabled -> box.isKotlinJsRoot = enabled },
            applyTopology = ::kotlinJsTopologyNeedsUpdating,
        ),
    )
}

class KotlinJsExecution : BoxExecution {

    override val languageName: String = "KotlinJS"
    private val launches = ConcurrentHashMap<String, KotlinJsLaunchTracker>()

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        runKotlin(inside, execute, launchable = false, code = execute.code, strand = "__fragment__", reply = reply)

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        runKotlin(inside, execute, launchable = false, code = execute.code, strand = "__fragment__", reply = reply)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) =
        runKotlin(
            inside,
            execute,
            launchable = true,
            code = code,
            strand = strand.ifBlank { "default" },
            reply = reply,
        )

    override fun stop(inside: Box) {
        val active = launches.entries.filter { it.value.box === inside }
        if (active.isNotEmpty()) {
            active.groupBy { it.value.selection.profile }.forEach { (profile, entries) ->
                entries.forEach { (key, tracker) ->
                    launches.remove(key, tracker)
                    tracker.cancel()
                }
                profile.stop(
                    inside,
                    entries.first().value.selection,
                    entries.map { it.value.lifecycleName },
                )
            }
            return
        }

        val selection = try {
            KotlinJsHeadProfiles.resolveForBox(inside, "")
        } catch (e: IllegalArgumentException) {
            inside.output.errorProxy(e.message ?: "Invalid Kotlin/JS head marker", "code", 0)
            return
        }
        if (selection.profile.connectedHeadCount() == 0 && selection.profile != KotlinJsHeadProfiles.visionMetal)
            notify("Warning! no connected ${selection.profile.id} heads", "kotlinjs")
        selection.profile.stop(inside, selection, listOf(kotlinJsLifecycleName(inside, "default")))
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        val exec = Execute(
            uid = executeSymbol.uid, type = "fragment", line = executeSymbol.line,
            code = "${executeSymbol.symbol}()", singleLine = true,
        )
        executeFragment(inside, exec, reply)
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        val pos = completionRequest.position
        fun empty() = Completion(boundsStart = pos, boundsEnd = pos, metadata = emptyList())
        val selection = try {
            KotlinJsHeadProfiles.resolveForBox(inside, completionRequest.allCode)
        } catch (e: IllegalArgumentException) {
            notify(e.message ?: "Invalid Kotlin/JS head marker", "kotlinjs_error")
            reply(Message("complete").payload(empty()))
            return
        }

        // Stage 1: reuse the rich K2 Analysis-API completion, but scope the analysis classpath to the
        // Kotlin stdlib only (KotlinJsExecution.kotlinStdlibRoots). The box has no JVM classpath — its
        // symbol universe is stdlib + its own declarations (present in `allCode`) + the selected head
        // profile's JVM-analysis-safe declaration model + keywords. Profile declarations stay at file
        // scope so nested types and member/callback inference work; their public callables are also
        // represented as local stubs for discoverability. The call can take up to its timeout, so run it
        // off the runloop and reply on completion.
        val stdlibRoots = AaCompletionClient.kotlinStdlibRoots()
        if (stdlibRoots.isEmpty()) {
            reply(Message("complete").payload(empty()))
            return
        }
        kotlinJsCompletionPool.submit {
            val completion = try {
                // The sidecar builds a fresh Analysis-API session per distinct classpath and answers
                // "warming up" (empty) on the first call(s) for a cold stdlib session. Retry a few
                // times so a user's first completion in a session isn't silently empty.
                var success: afield.CompletionResult.Success? = null
                var lastDetail = ""
                repeat(4) { attempt ->
                    if (success != null) return@repeat
                    val outcome = AaCompletionClient.complete(
                        snippet = completionRequest.allCode,
                        cursor = pos,
                        preamble = "",
                        declarations = selection.profile.completionDeclarations,
                        implicitReceivers = emptyList(),
                        classpathRootsOverride = stdlibRoots,
                        jsTarget = true,
                    )
                    lastDetail = outcome.detail
                    success = outcome.result as? afield.CompletionResult.Success
                    if (success == null && attempt < 3) Thread.sleep(300)
                }
                val built = success
                if (built == null) {
                    lprintln("info,kotlinjs", "completion backend=AA(stdlib) → empty ($lastDetail)")
                    empty()
                } else {
                    Completion(
                        boundsStart = built.bounds.start,
                        boundsEnd = built.bounds.end,
                        metadata = built.metadata.map {
                            Metadata(
                                icon = it.icon,
                                tail = it.tail,
                                text = it.text,
                                displayText = it.displayText,
                                documentation = "",
                            )
                        },
                    )
                }
            } catch (e: Exception) {
                lprintln("warning,kotlinjs", "completion failed: ${e.message}")
                empty()
            }
            reply(Message("complete").payload(completion))
        }
    }

    private fun runKotlin(
        inside: Box,
        execute: Execute,
        launchable: Boolean,
        code: String,
        strand: String,
        reply: (Message) -> Unit,
    ) {
        configureOutputProxies(inside, reply)

        val selection = try {
            KotlinJsHeadProfiles.resolveForBox(inside, code)
        } catch (e: IllegalArgumentException) {
            inside.output.errorProxy(e.message ?: "Invalid Kotlin/JS head marker", "code", 0)
            return
        }
        val connected = selection.profile.connectedHeadCount()

        if (connected == 0 && selection.profile != KotlinJsHeadProfiles.visionMetal)
            notify("Warning! no connected ${selection.profile.id} heads", "kotlinjs")
        else if (connected == 0)
            notify("compiling Kotlin → ${selection.profile.id}; program will replay when the head connects", "kotlinjs")
        else
            notify("compiling Kotlin → ${selection.profile.id} for $connected head(s)", "kotlinjs")

        if (!launchable) {
            runFragment(inside, execute, code, selection)
            return
        }

        val lifecycleName = kotlinJsLifecycleName(inside, strand)
        val key = "${inside.id}\u0000$strand"
        launches.remove(key)?.let { previous ->
            previous.cancel()
            previous.selection.profile.stop(inside, previous.selection, listOf(previous.lifecycleName))
        }

        val generation = synchronized(inside) {
            val next = (inside.runGeneration[strand] ?: -1) + 1
            inside.runGeneration[strand] = next
            inside.currentRunningStates[strand] = RunningState(next, "launch")
            next
        }
        send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand, "launch")))

        lateinit var tracker: KotlinJsLaunchTracker
        tracker = KotlinJsLaunchTracker(
            box = inside,
            strand = strand,
            generation = generation,
            lifecycleName = lifecycleName,
            selection = selection,
            notifyStopped = {
                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand, "launch")))
            },
            onFinished = { launches.remove(key, tracker) },
        )
        launches[key] = tracker

        kotlinJsCompilePool.submit {
            try {
                val sent = compileAndSendKotlin(
                    inside,
                    code,
                    launchable = true,
                    selectedHead = selection,
                    lifecycleName = lifecycleName,
                    onResponse = tracker::onResponse,
                )
                tracker.deliveryFinished(
                    sent = sent,
                    durableWithoutResponse = false,
                )
            } catch (e: Exception) {
                lprintln("error,kotlinjs", e.stackTraceToString())
                notify("Kotlin → JS failed: ${e.message}", "kotlinjs_error")
                inside.output.errorProxy(e.message ?: "compile error", "code", 0)
                tracker.cancel()
            }
        }
    }

    private fun runFragment(
        inside: Box,
        execute: Execute,
        code: String,
        selection: KotlinJsHeadSelection,
    ) {
        val watermark = ++inside.fragmentGeneration
        send(Message("is-running").payload(IsRunning(execute.uid, true, watermark, "__fragment__")))
        kotlinJsCompilePool.submit {
            try {
                compileAndSendKotlin(inside, code, launchable = false, selectedHead = selection)
            } catch (e: Exception) {
                lprintln("error,kotlinjs", e.stackTraceToString())
                notify("Kotlin → JS failed: ${e.message}", "kotlinjs_error")
                inside.output.errorProxy(e.message ?: "compile error", "code", 0)
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, watermark, "__fragment__")))
            }
        }
    }
}

internal class KotlinJsLaunchTracker(
    val box: Box,
    private val strand: String,
    private val generation: Int,
    val lifecycleName: String,
    val selection: KotlinJsHeadSelection,
    private val notifyStopped: () -> Unit,
    private val onFinished: () -> Unit,
) {
    private val expectedHeads = mutableSetOf<String>()
    private val activeHeads = mutableSetOf<String>()
    private val terminalHeads = mutableSetOf<String>()
    private var finished = false

    @Synchronized
    fun onResponse(response: KotlinJsHeadResponse): Boolean {
        if (finished || !isCurrent()) return false
        expectedHeads += response.expectedHeadIds
        expectedHeads += response.headId
        when (response.kind) {
            "RUN-START" -> {
                activeHeads += response.headId
                terminalHeads -= response.headId
            }
            "RUN-STOP", "ERROR" -> {
                activeHeads -= response.headId
                terminalHeads += response.headId
            }
            "SUCCESS" -> if (response.terminal && response.headId !in activeHeads) {
                terminalHeads += response.headId
            }
        }
        if (activeHeads.isEmpty() && expectedHeads.isNotEmpty() && terminalHeads.containsAll(expectedHeads)) {
            finish()
        }
        return !finished
    }

    @Synchronized
    fun deliveryFinished(sent: Boolean, durableWithoutResponse: Boolean) {
        if (finished || !isCurrent()) return
        if (!sent) {
            finish()
        } else if (durableWithoutResponse) {
            activeHeads += "durable-head"
        }
    }

    @Synchronized
    fun cancel() {
        if (!finished) finish()
    }

    private fun isCurrent(): Boolean = synchronized(box) {
        box.runGeneration[strand] == generation &&
            box.currentRunningStates[strand]?.watermark == generation
    }

    private fun finish() {
        if (finished) return
        finished = true
        synchronized(box) {
            if (box.currentRunningStates[strand]?.watermark == generation) {
                box.currentRunningStates.remove(strand)
                notifyStopped()
            }
            if (box.runGeneration[strand] == generation) {
                box.runGeneration[strand] = generation + 1
            }
        }
        onFinished()
    }
}

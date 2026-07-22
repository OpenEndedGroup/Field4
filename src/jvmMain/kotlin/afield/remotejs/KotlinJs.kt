package afield.remotejs

import afield.*
import afield.logger.lprintln
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.stdlib.*
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

fun kotlinJsTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isKotlinJsRoot == true || it.isMainLanguage("kotlinjs") },
        runtimeClass = KotlinJsExecution::class,
        newExecution = { _ -> KotlinJsExecution() },
        language = "KotlinJS",
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

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        runKotlin(inside, execute, launchable = false, code = execute.code, reply = reply)

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) =
        runKotlin(inside, execute, launchable = false, code = execute.code, reply = reply)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) =
        runKotlin(inside, execute, launchable = true, code = code, reply = reply)

    override fun stop(inside: Box) {
        if (knownOpenRemoteClients.isEmpty()) notify("Warning! no connected remote clients", "kotlinjs")
        afield.remotejs.stop(inside)
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        val exec = Execute(
            uid = executeSymbol.uid, type = "fragment", line = executeSymbol.line,
            code = "${executeSymbol.symbol}()", singleLine = true,
        )
        executeFragment(inside, exec, reply)
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {
        // Spike: no completion for Kotlin-in-the-head yet (the K2 Analysis-API completion machinery
        // exists for JVM Kotlin — wiring it to the JS classpath is a follow-up).
        reply(
            Message("complete").payload(
                Completion(
                    boundsStart = completionRequest.position,
                    boundsEnd = completionRequest.position,
                    metadata = emptyList(),
                ),
            ),
        )
    }

    private fun runKotlin(inside: Box, execute: Execute, launchable: Boolean, code: String, reply: (Message) -> Unit) {
        configureOutputProxies(inside, reply)
        val watermark = ++inside.fragmentGeneration
        send(Message("is-running").payload(IsRunning(execute.uid, true, watermark, "__fragment__")))

        if (knownOpenRemoteClients.isEmpty())
            notify("Warning! no connected remote clients", "kotlinjs")
        else
            notify("compiling Kotlin → JS for ${knownOpenRemoteClients.size} head(s)", "kotlinjs")

        kotlinJsCompilePool.submit {
            try {
                compileAndSendKotlin(inside, code, launchable)
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

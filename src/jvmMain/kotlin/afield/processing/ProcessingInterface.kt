@file:Suppress("UNCHECKED_CAST")

package afield.processing

import afield.*
import afield.logger.lprintln
import afield.macros.standardExecutionPractice
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.Delegation
import afield.stdlib.i
import afield.stdlib.times
import processing.core.PApplet
import processing.core.PConstants
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * A Processing applet wrapper that manages the setup/draw lifecycle
 * and allows code to be inserted/removed from the render loop.
 */
class Field4ProcessingApplet : PApplet() {

    // Map of strand names to code blocks
    private val strands = ConcurrentHashMap<String, () -> Unit>()

    // Flag to track if setup has been called
    private var setupComplete = AtomicBoolean(false)

    override fun settings() {
        val renderer = System.getenv("AFIELD_PROCESSING_RENDERER")?.lowercase()
        when (renderer) {
            "java2d" -> {
                lprintln("info,processing", "Using JAVA2D renderer for Processing")
                size(800, 600, PConstants.JAVA2D)
            }
            "p2d" -> {
                lprintln("info,processing", "Using P2D renderer for Processing")
                size(800, 600, PConstants.P2D)
            }
            else -> {
                lprintln("info,processing", "Using P3D renderer for Processing")
                size(800, 600, PConstants.P3D)
            }
        }
    }

    override fun setup() {
        frameRate(60f)
        setupComplete.set(true)
    }

    override fun draw() {
        background(0f)
        // Execute all registered strands
        strands.values.forEach { block ->
            try {
                block()
            } catch (e: Throwable) {
                lprintln("error,processing", "Error in draw loop: ${e.message}")
            }
        }
    }

    fun addStrand(name: String, code: () -> Unit) {
        strands[name] = code
        lprintln("info,processing", "Added strand: $name")
    }

    fun removeStrand(name: String) {
        strands.remove(name)
        lprintln("info,processing", "Removed strand: $name")
    }

    fun clearStrands() {
        strands.clear()
        lprintln("info,processing", "Cleared all strands")
    }

    fun isSetupComplete(): Boolean = setupComplete.get()
}

/**
 * Host for Processing execution context.
 * Manages a single Processing applet instance.
 */
class ProcessingHost {

    private var applet: Field4ProcessingApplet? = null
    private val bootOnce = AtomicBoolean(false)
    private val bootError = AtomicReference<Throwable?>(null)

    private fun boot() {
        if (bootOnce.getAndSet(true)) return

        lprintln("info,processing", "Booting Processing applet...")

        val runSketchLatch = CountDownLatch(1)
        val runSketchTask = {
            val instance = Field4ProcessingApplet()
            applet = instance
            val loader = Field4ProcessingApplet::class.java.classLoader
            val previousLoader = Thread.currentThread().contextClassLoader
            Thread.currentThread().contextClassLoader = loader
            try {
                try {
                    Class.forName("com.apple.eawt.QuitHandler", false, loader)
                } catch (e: Throwable) {
                    lprintln("warn,processing", "Missing com.apple.eawt.QuitHandler in classloader: ${loader}")
                }
                try {
                    val platformField = PApplet::class.java.getDeclaredField("platform")
                    platformField.isAccessible = true
                    platformField.setInt(null, PConstants.OTHER)
                    lprintln("info,processing", "Forced PApplet.platform to OTHER")
                } catch (e: Throwable) {
                    lprintln("warn,processing", "Failed to set PApplet.platform: ${e.message}")
                }
                PApplet.runSketch(arrayOf("Field4ProcessingApplet"), instance)
            } catch (e: Throwable) {
                bootError.set(e)
                lprintln("error,processing", "Processing runSketch failed: ${e.message}")
                lprintln("error,processing", e.stackTraceToString())
            } finally {
                Thread.currentThread().contextClassLoader = previousLoader
                runSketchLatch.countDown()
            }
        }

        val mainThread = Runloop.mainThread
        if (mainThread != null && Thread.currentThread() === mainThread) {
            lprintln("info,processing", "Running runSketch on Runloop main thread")
            runSketchTask()
        } else {
            lprintln("info,processing", "Scheduling runSketch on Runloop main thread")
            inMain {
                runSketchTask()
            }
            if (!runSketchLatch.await(5, TimeUnit.SECONDS)) {
                lprintln("warn,processing", "runSketch did not complete within timeout")
            }
        }

        // Wait for applet to be ready
        var attempts = 0
        while (applet?.isSetupComplete() != true && attempts < 100) {
            bootError.get()?.let { err ->
                lprintln("error,processing", "Processing boot error detected: ${err.message}")
                throw IllegalStateException("Processing applet failed to initialize", err)
            }
            Thread.sleep(50)
            attempts++
        }

        if (applet?.isSetupComplete() != true) {
            lprintln("error,processing", "Processing applet failed to initialize after waiting")
            throw IllegalStateException("Processing applet failed to initialize")
        }

        if (!waitForGraphics(5000)) {
            lprintln("warn,processing", "Processing graphics not initialized after waiting")
        }

        lprintln("info,processing", "Processing applet state: g=${applet?.g} surface=${applet?.surface}")

        lprintln("info,processing", "Processing applet booted successfully")
    }

    fun getApplet(): Field4ProcessingApplet {
        boot()
        return applet ?: throw IllegalStateException("Applet not initialized")
    }

    fun waitForGraphics(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (applet?.g != null) return true
            Thread.sleep(25)
        }
        return applet?.g != null
    }

    fun shutdown() {
        applet?.noLoop()
        applet?.dispose()
        applet = null
        bootOnce.set(false)
    }

    /**
     * Stop a strand from running in the draw loop.
     */
    fun stopStrand(strand: String) {
        applet?.removeStrand(strand)
    }

    /**
     * Stop all strands.
     */
    fun stopAll() {
        applet?.clearStrands()
    }
}

@RunsAt(plugins)
fun install() {
    TopologyWatcher("processing") { processingTopologyNeedsUpdating(it) }.start()
}

// Shared Processing host instance
private var sharedHost: ProcessingHost? = null

fun getSharedHost(): ProcessingHost {
    if (sharedHost == null) {
        sharedHost = ProcessingHost()
    }
    return sharedHost!!
}

class ProcessingExecution(val inside: Box) : BoxExecution {
    val host = getSharedHost()
    val p: Field4ProcessingApplet by lazy { host.getApplet() }

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        inMain {
            host.getApplet()
            if (!host.waitForGraphics(5000)) {
                reply(
                    Message("output").payload(
                        Output(
                            line = Math.max(0, execute.line),
                            kind = "error",
                            html = "Processing graphics not initialized yet",
                            frame = Runloop.runLoop.frame.toInt()
                        )
                    )
                )
                return@inMain
            }
            val ln = Math.max(0, execute.line)
            val code = ("\n" * ln) + execute.code.trimEnd()
            val dur = +execute.code.count { it == '\n' }

            val wm = ++inside.fragmentGeneration

            configureOutputProxies(inside, reply)

            send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))

            try {
                // Wrap the code to run in Processing context
                val wrappedCode = """
                    import processing.core.*
                    import afield.processing.*
                    val p = getSharedHost().getApplet()
                    with(p) {
                        $code
                    }
                """.trimIndent()

                val output = mutableListOf<String>()
                var hasError = false

                inside.output.proxy = { content, nl, line ->
                    val n = content?.toString() ?: ""
                    if (n.trim().isNotEmpty()) {
                        output += n
                        reply(
                            Message("output").payload(
                                Output(
                                    line = line.i,
                                    kind = "normal",
                                    html = n.trim() + (if (nl) "\n" else ""),
                                    frame = Runloop.runLoop.frame.toInt()
                                )
                            )
                        )
                    }
                }

                inside.output.errorProxy = { content, property, nl ->
                    val n = content?.toString() ?: ""
                    if (n.trim().isNotEmpty()) {
                        hasError = true
                        reply(
                            Message("output").payload(
                                Output(
                                    line = nl.i,
                                    kind = "err",
                                    property = property,
                                    html = escapeForHtml(n),
                                    frame = Runloop.runLoop.frame.toInt()
                                )
                            )
                        )
                    }
                }

                val returnValues: List<ResultValue?> = standardExecutionPractice(
                    inside.ki,
                    wrappedCode,
                    { compileError ->
                        hasError = true
                        lprintln("error,processing", "compiler error")
                        compileError.forEach {
                            lprintln("error,processing", "  ${it.code} ${it.message} ${it.location} ${it.severity}")
                            if (it.severity.ordinal > ScriptDiagnostic.Severity.WARNING.ordinal) {
                                if (it.location != null) {
                                    reply(
                                        Message("output").payload(
                                            Output(
                                                line = it.location!!.start.line,
                                                kind = "error",
                                                html = escapeForHtml(it.message),
                                                frame = Runloop.runLoop.frame.toInt()
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    },
                    { evalError ->
                        hasError = true
                        lprintln("error,processing", "eval error")
                        evalError.forEach {
                            lprintln("error,processing", "  ${it.code} ${it.message} ${it.location}")
                        }
                    },
                    { evalException ->
                        hasError = true
                        lprintln("error,processing", "exception thrown in fragment")
                        lprintln("error,processing", evalException.stackTraceToString())

                        reply(
                            Message("output").payload(
                                Output(
                                    line = ln + dur,
                                    kind = "error",
                                    html = escapeForHtml(evalException.message ?: evalException.javaClass.name),
                                    frame = Runloop.runLoop.frame.toInt()
                                )
                            )
                        )
                    },
                    implicits = listOf(inside)
                )

                if (!hasError) {
                    returnValues.forEach { returnValue ->
                        when (returnValue) {
                            is ResultValue.Unit -> {
                                if (output.isEmpty()) {
                                    reply(
                                        Message("output").payload(
                                            Output(
                                                line = dur,
                                                kind = "ok",
                                                html = "ok",
                                                frame = Runloop.runLoop.frame.toInt()
                                            )
                                        )
                                    )
                                }
                            }
                            is ResultValue.Value -> {
                                if (output.isEmpty()) {
                                    reply(
                                        Message("output").payload(
                                            Output(
                                                line = ln + dur,
                                                kind = "normal",
                                                html = returnValue.value.toString(),
                                                annotation = "<i>${returnValue.name}=</i>${returnValue.type}",
                                                frame = Runloop.runLoop.frame.toInt()
                                            )
                                        )
                                    )
                                }
                            }
                            null -> {
                                if (output.isEmpty()) {
                                    reply(
                                        Message("output").payload(
                                            Output(
                                                line = ln + dur,
                                                kind = "ok",
                                                html = "ok",
                                                frame = Runloop.runLoop.frame.toInt()
                                            )
                                        )
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
            } finally {
                send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
            }
        }
    }

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) {
        executeFragment(inside, execute, reply)
    }

    override fun launchAll(
        inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit
    ) {
        inMain {
            host.getApplet()
            if (!host.waitForGraphics(5000)) {
                inside.error("Processing graphics not initialized yet", line = execute.line, property = "code")
                return@inMain
            }
            val generation = inside.runGeneration.incrementAndGet(strand)
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))

            configureOutputProxies(inside, reply)

            try {
                // Wrap code to create a lambda that can be called each frame
                val wrappedCode = """
                    import processing.core.*
                    import afield.processing.*
                    val p = getSharedHost().getApplet()
                    val lambda: () -> Unit = {
                        with(p) {
                            $code
                        }
                    }
                    lambda
                """.trimIndent()

                var hasError = false

                val returnValues: List<ResultValue?> = standardExecutionPractice(
                    inside.ki,
                    wrappedCode,
                    { compileError ->
                        hasError = true
                        lprintln("error,processing", "compiler error in launch")
                        compileError.forEach {
                            if (it.severity.ordinal > ScriptDiagnostic.Severity.WARNING.ordinal) {
                                inside.error(it.message, line = it.location?.start?.line ?: -1, property = "code")
                            }
                        }
                        send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                    },
                    { evalError ->
                        hasError = true
                        lprintln("error,processing", "eval error in launch")
                        send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                    },
                    { evalException ->
                        hasError = true
                        lprintln("error,processing", "exception in launch: ${evalException.message}")
                        inside.error(evalException.message ?: "Unknown error", line = -1, property = "code")
                        send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                    },
                    implicits = listOf(inside)
                )

                if (!hasError) {
                    returnValues.forEach { returnValue ->
                        when (returnValue) {
                            is ResultValue.Value -> {
                                val lambda = returnValue.value as? (() -> Unit)
                                if (lambda != null) {
                                    p.addStrand(strand, lambda)
                                    lprintln("info,processing", "Launched code in strand '$strand'")
                                } else {
                                    lprintln("error,processing", "Failed to extract lambda from return value")
                                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                                }
                            }
                            else -> {
                                lprintln("error,processing", "Unexpected return value type: $returnValue")
                                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                lprintln("error,processing", "Exception in launchAll: ${e.message}")
                inside.error(e.message ?: "Unknown error", line = -1, property = "code")
                send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
            }
        }
    }

    override fun stop(inside: Box) {
        host.stopAll()
        // Update all strands to show they're not running
        inside.runGeneration.keys.forEach { strand ->
            val generation = inside.runGeneration[strand] ?: 0
            send(Message("is-running").payload(IsRunning("", false, generation, strand)))
        }
    }

    override fun complete(
        inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit
    ) {
        // For now, delegate to Kotlin completion
        // The Processing API will be available via the 'p' variable
        inside.ke.complete(inside, completionRequest, reply)
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For Processing, execute the symbol as a function call
        val functionCall = "${executeSymbol.symbol}()"

        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = functionCall,
            singleLine = true
        )

        executeFragment(inside, exec, reply)
    }

    override val languageName: String
        get() = "processing"
}

private fun escapeForHtml(text: String?): String {
    if (text == null) return ""
    return text.replace("<", "&lt;").replace(">", "&gt;")
}

var Box.isProcessingRoot by Delegation<Boolean?>()

@RunsAt(early)
fun installProcessing() {
    Box.ioPersistentProperties.add("isProcessingRoot")
}

fun processingTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isProcessingRoot },
        runtimeClass = ProcessingExecution::class,
        newExecution = { child -> ProcessingExecution(child) },
        language = "Processing",
        customization = """this.style.border = "1px dashed #4B8BBE"; this.style.margin = "-5px" """,
        notifyCategory = "processing"
    )
}

private fun MutableMap<String, Int>.incrementAndGet(name: String): Int {
    val next = (this[name] ?: 0) + 1
    this[name] = next
    return next
}

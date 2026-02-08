@file:Suppress("UNCHECKED_CAST")

package afield.processing

import afield.*
import afield.logger.lprintln
import afield.macros.standardExecutionPractice
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.stdlib.Delegation
import processing.core.PApplet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
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
        size(800, 600, P2D)
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
    private var frame: JFrame? = null
    private val bootOnce = AtomicBoolean(false)
    
    private fun boot() {
        if (bootOnce.getAndSet(true)) return
        
        lprintln("info,processing", "Booting Processing applet...")
        
        // Create and start the Processing applet on the EDT
        SwingUtilities.invokeLater {
            applet = Field4ProcessingApplet()
            frame = JFrame("Field4 Processing")
            
            frame?.let { f ->
                applet?.let { a ->
                    f.add(a)
                    f.pack()
                    f.isVisible = true
                    f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                    
                    a.init()
                }
            }
        }
        
        // Wait for applet to be ready
        var attempts = 0
        while (applet?.isSetupComplete() != true && attempts < 100) {
            Thread.sleep(50)
            attempts++
        }
        
        if (applet?.isSetupComplete() != true) {
            lprintln("error,processing", "Processing applet failed to initialize after waiting")
            throw IllegalStateException("Processing applet failed to initialize")
        }
        
        lprintln("info,processing", "Processing applet booted successfully")
    }
    
    fun getApplet(): Field4ProcessingApplet {
        boot()
        return applet ?: throw IllegalStateException("Applet not initialized")
    }
    
    fun shutdown() {
        applet?.noLoop()
        applet?.dispose()
        frame?.dispose()
        applet = null
        frame = null
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
            val generation = inside.runGeneration.get(strand)
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

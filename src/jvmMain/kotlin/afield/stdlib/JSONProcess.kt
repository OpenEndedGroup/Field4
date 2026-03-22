package afield.stdlib

import afield.Box
import afield.ReturnValueRegistry
import afield.RunsAt
import afield.logger.lprintln
import afield.returnValueRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class JSONProcess(
    workingDirectory: String? = null,
    vararg command: String,
    val env: Map<String, String> = emptyMap()
) {
    private val workingDirectoryFile = workingDirectory?.let(::expandUserPath)
    val command: List<String> = command.toList()

    private val latestStdoutLineRef = AtomicReference<String?>(null)
    private val latestJsonLineRef = AtomicReference<String?>(null)
    private val latestStderrLineRef = AtomicReference<String?>(null)
    private val lastErrorRef = AtomicReference<String?>(null)

    @Volatile
    private var process: Process? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    val output: JSONObject
        get() = latestJsonLineRef.get()?.let(::JSONObject) ?: JSONObject()

    val outputText: String?
        get() = latestJsonLineRef.get()

    val latestStdoutLine: String?
        get() = latestStdoutLineRef.get()

    val latestStderrLine: String?
        get() = latestStderrLineRef.get()

    val lastError: String?
        get() = lastErrorRef.get()

    fun hasOutput(): Boolean = latestJsonLineRef.get() != null

    override fun toString(): String {
        return "JSONProcess(command=${command.joinToString(" ")}, running=$running, exitCode=$exitCode)"
    }

    internal suspend fun runForBox(box: Box, strand: String, generation: Int) {
        stop()

        if (command.isEmpty()) {
            lastErrorRef.set("No command provided")
            return
        }

        val resolvedCommand = command.mapIndexed { index, part ->
            if (index == 0) expandUserPath(part).path else part
        }

        val started = try {
            ProcessBuilder(resolvedCommand).apply {
                workingDirectoryFile?.let(::directory)
                environment().putAll(env)
            }.start()
        } catch (t: Throwable) {
            val message = t.message ?: t.toString()
            lastErrorRef.set("Failed to start process: $message")
            lprintln("error,jsonprocess", "Failed to start ${resolvedCommand.joinToString(" ")}: $message")
            return
        }

        process = started
        running = true
        exitCode = null
        lastErrorRef.set(null)

        val completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            stop()
        }

        try {
            val stdoutThread = thread(name = "json-process-stdout", isDaemon = true) {
                runCatching {
                    started.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach(::handleStdoutLine)
                    }
                }
            }
            val stderrThread = thread(name = "json-process-stderr", isDaemon = true) {
                runCatching {
                    started.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach(::handleStderrLine)
                    }
                }
            }

            while (started.isAlive && insideLifecycleStillValid(box, strand, generation)) {
                delay(50)
            }

            if (started.isAlive) {
                destroyProcess(started)
            }

            exitCode = withContext(Dispatchers.IO) { started.waitFor() }
            stdoutThread.join(250)
            stderrThread.join(250)
        } finally {
            completionHandle?.dispose()
            process = null
            running = false
        }
    }

    @Synchronized
    fun stop() {
        val proc = process ?: return
        destroyProcess(proc)
        process = null
        running = false
    }

    private fun handleStdoutLine(line: String) {
        latestStdoutLineRef.set(line)
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        try {
            JSONObject(trimmed)
            latestJsonLineRef.set(trimmed)
            lastErrorRef.set(null)
        } catch (t: Throwable) {
            val message = "Ignoring non-JSON stdout line: ${t.message ?: trimmed}"
            lastErrorRef.set(message)
            lprintln("warn,jsonprocess", message)
        }
    }

    private fun handleStderrLine(line: String) {
        latestStderrLineRef.set(line)
        if (line.isNotBlank()) {
            lastErrorRef.set(line)
            lprintln("warn,jsonprocess", line)
        }
    }

    private fun insideLifecycleStillValid(box: Box, strand: String, generation: Int): Boolean {
        return running && box.runGeneration[strand] == generation
    }

    private fun destroyProcess(process: Process) {
        runCatching {
            process.destroy()
            if (!process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun expandUserPath(path: String): File {
        if (path == "~") return File(System.getProperty("user.home"))
        if (path.startsWith("~/")) return File(System.getProperty("user.home"), path.removePrefix("~/"))
        return File(path)
    }
}

@RunsAt(returnValueRegistration, priority = 12)
fun registerJSONProcessReturnValue() {
    ReturnValueRegistry.handlers += handler@{ inside, returnValue, strand, generation, notify ->
        val process = returnValue as? JSONProcess ?: return@handler false

        CoroutineScope(Dispatchers.Default + Box.boxContextElement(inside)).launch {
            notify(true, strand, generation)
            val job = coroutineContext[Job]!!
            inside.jobs += job
            try {
                process.runForBox(inside, strand, generation)
            } finally {
                process.stop()
                notify(false, strand, generation)
            }
        }
        true
    }
}

package afield.ai.models

import afield.ai.diagnostics.log
import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.diagnostics.logging.lprintln
import afield.lang.python.PythonInterface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Local speech-to-text for AI4 push-to-talk.
 *
 * The browser records while the push-to-talk key is held and sends the clip as `ai-transcribe`;
 * this hands it to `python/fieldvoice/whisper_worker.py`, a Python process started on first use and
 * kept alive so the model loads once (`large-v3-turbo` on a GPU, `small.en` on a CPU). The worker
 * is an optional dependency (`uv sync --inexact --group voice`); without it the reply says how to
 * install it.
 *
 * The interpreter is `FIELD_VOICE_PYTHON` if set, otherwise the one Field's Python boxes resolved
 * (the same venv the `voice` group is installed into), otherwise the checkout's `.venv`
 * (`Scripts\python.exe` on Windows).
 *
 * One clip is transcribed at a time, on a dedicated thread: a second press queues behind the first.
 */
object VoiceTranscriber {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "field-voice-transcriber").apply { isDaemon = true }
    }
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var replies: LinkedBlockingQueue<String>? = null
    private var stderrLog: File? = null
    private var backend = ""
    private var nextId = 0

    /** Longest wait for the worker's ready line; the first start downloads the model (~1.6 GB). */
    internal var startupTimeoutMillis = envMillis("FIELD_VOICE_STARTUP_TIMEOUT_MS", 15 * 60_000L)
    /** Longest wait for one clip's transcript before the worker is presumed hung and restarted. */
    internal var replyTimeoutMillis = envMillis("FIELD_VOICE_TIMEOUT_MS", 60_000L)
    /** The worker command; tests substitute a fake worker. */
    internal var workerCommand: () -> List<String> = { listOf(pythonExecutable(), "-u", workerScript().path) }
    internal var notifier: (String) -> Unit = { text -> runCatching { notify(text, "AI4 voice") } }

    private fun envMillis(name: String, default: Long) =
        System.getenv(name)?.toLongOrNull()?.takeIf { it > 0 } ?: default

    fun install() {
        handlers["ai-transcribe"] = mutableSetOf({ message, reply ->
            val request = Json.decodeFromJsonElement<AiTranscribeRequest>(message.payload!!)
            executor.execute {
                val response = runCatching { transcribe(request) }.getOrElse {
                    lprintln("warning,ai4", "Voice transcription failed: ${it.message}")
                    AiTranscribeResponse(false, message = it.message ?: it.javaClass.simpleName)
                }
                reply(Message("ai-transcribe").payload(response))
            }
            Handled.yes
        })
        // Sent when push-to-talk is first pressed, so the model loads while you are still talking.
        handlers["ai-transcribe-warm"] = mutableSetOf({ _, reply ->
            executor.execute {
                val response = runCatching { ensureWorker(); AiTranscribeResponse(true, backend = backend) }
                    .getOrElse { AiTranscribeResponse(false, message = it.message ?: it.javaClass.simpleName) }
                reply(Message("ai-transcribe-warm").payload(response))
            }
            Handled.yes
        })
    }

    private fun pythonExecutable(): String {
        System.getenv("FIELD_VOICE_PYTHON")?.takeIf { it.isNotBlank() }?.let { return it }
        PythonInterface.resolvedPythonExecutable?.takeIf { File(it).canExecute() }?.let { return it }
        val windows = System.getProperty("os.name").lowercase().contains("windows")
        val venv = File(System.getProperty("user.dir"), ".venv")
        val candidates = if (windows) listOf("Scripts/python.exe") else listOf("bin/python3", "bin/python")
        candidates.map { File(venv, it) }.firstOrNull { it.canExecute() }?.let { return it.path }
        return if (windows) "python" else "python3"
    }

    private fun workerScript(): File {
        val candidates = listOf(File(System.getProperty("user.dir"), "python")) +
            (System.getenv("FIELD_PYTHONPATH")?.split(File.pathSeparator).orEmpty().map(::File))
        return candidates.map { File(it, "fieldvoice/whisper_worker.py") }.firstOrNull { it.isFile }
            ?: error("python/fieldvoice/whisper_worker.py not found beside Field.")
    }

    private fun ensureWorker() {
        if (process?.isAlive == true) return
        stopWorker()
        val command = workerCommand()
        notifier("Loading Whisper (the first use downloads the model)...")
        val log = File.createTempFile("field-voice-worker-", ".log").apply { deleteOnExit() }
        val started = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.to(log))
            .start()
        // stdout is drained by its own thread so every read below can time out.
        val queue = LinkedBlockingQueue<String>()
        Thread({
            runCatching { started.inputStream.bufferedReader().forEachLine { queue.put(it) } }
            queue.put(EOF)
        }, "field-voice-worker-stdout").apply { isDaemon = true }.start()
        val ready = nextJson(queue, startupTimeoutMillis)
        if (ready == null || ready["ready"]?.jsonPrimitive?.booleanOrNull != true) {
            started.destroyForcibly()
            started.waitFor(2, TimeUnit.SECONDS)
            val why = ready?.get("error")?.jsonPrimitive?.content
                ?: if (started.isAlive || queue.isEmpty()) "the worker did not report ready within ${startupTimeoutMillis / 1000} s"
                else "the worker exited during startup"
            val stderr = stderrTail(log)
            log.delete()
            error("Voice input unavailable with ${command.first()}: $why$stderr")
        }
        backend = ready["backend"]?.jsonPrimitive?.content.orEmpty()
        process = started
        input = started.outputStream.bufferedWriter()
        replies = queue
        stderrLog = log
        val model = ready["model"]?.jsonPrimitive?.content.orEmpty()
        val note = ready["note"]?.jsonPrimitive?.content.orEmpty()
        lprintln("info,ai4", "Voice worker ready: $backend $model via ${command.first()}")
        notifier("Voice input ready: $model, $backend" + if (note.isNotBlank()) ". $note" else "")
        if (note.isNotBlank()) lprintln("warning,ai4", "Voice worker: $note")
    }

    private val EOF = "\u0000eof"

    /** The next JSON object line, skipping anything else the worker printed; null on EOF or timeout. */
    private fun nextJson(queue: LinkedBlockingQueue<String>, timeoutMillis: Long): JsonObject? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return null
            val line = queue.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (line == EOF) { queue.put(EOF); return null }
            val parsed = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (parsed != null) return parsed
        }
    }

    /** The end of what the worker wrote to stderr (a Python traceback, usually), for an error message. */
    private fun stderrTail(log: File?): String {
        val text = runCatching { log?.readText().orEmpty() }.getOrDefault("")
        val tail = text.trim().takeLast(1500)
        return if (tail.isBlank()) "" else "\nWorker stderr:\n$tail"
    }

    /** The reply for request [id]. A reply to an earlier request (one that timed out) is dropped. */
    private fun awaitReply(id: Int): JsonObject? {
        val queue = replies ?: return null
        val deadline = System.nanoTime() + replyTimeoutMillis * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            val reply = nextJson(queue, left) ?: return null
            if (reply["id"]?.jsonPrimitive?.intOrNull == id) return reply
            lprintln("warning,ai4", "Voice worker: dropped a reply for request ${reply["id"]}, waiting for $id")
        }
    }

    /** Stops the worker; the next request starts a fresh one. */
    internal fun forceRestart() = stopWorker()

    private fun stopWorker() {
        process?.destroyForcibly()
        process = null
        input = null
        replies = null
        stderrLog?.delete()
        stderrLog = null
    }

    internal fun transcribe(request: AiTranscribeRequest): AiTranscribeResponse {
        ensureWorker()
        val clip = File.createTempFile("field-voice-", ".f32")
        try {
            clip.writeBytes(Base64.getDecoder().decode(request.pcm))
            val id = ++nextId
            val line = buildJsonObject {
                put("id", id)
                put("pcm", clip.path)
                put("sampleRate", request.sampleRate)
                put("prompt", request.vocabulary)
            }.toString()
            val result = try {
                input!!.apply { write(line); newLine(); flush() }
                awaitReply(id)
            } catch (t: Throwable) {
                null
            } ?: run {
                // The stdout reader preserves EOF in the queue. It can see the closed pipe
                // before Process.isAlive observes the exit, especially under test load.
                val alive = process?.isAlive == true && replies?.peek() != EOF
                process?.destroyForcibly()
                process?.waitFor(2, TimeUnit.SECONDS)
                val stderr = stderrTail(stderrLog)
                stopWorker()
                error(
                    (if (alive) "The voice worker did not answer within ${replyTimeoutMillis / 1000} s and was restarted"
                    else "The voice worker stopped") + "; it will start again on the next press.$stderr"
                )
            }
            if (result["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                return AiTranscribeResponse(false, message = result["error"]?.jsonPrimitive?.content ?: "transcription failed")
            }
            return AiTranscribeResponse(
                success = true,
                text = result["text"]?.jsonPrimitive?.content.orEmpty(),
                millis = result["millis"]?.jsonPrimitive?.intOrNull ?: 0,
                backend = backend
            )
        } finally {
            clip.delete()
        }
    }
}

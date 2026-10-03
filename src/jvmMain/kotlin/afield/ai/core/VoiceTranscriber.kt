package afield.ai.core

import afield.*
import afield.logger.lprintln
import afield.python.PythonInterface
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
    private var output: BufferedReader? = null
    private var backend = ""
    private var nextId = 0

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
        val python = pythonExecutable()
        val script = workerScript()
        notify("Loading Whisper (the first use downloads the model)...", "AI4 voice")
        val started = ProcessBuilder(python, "-u", script.path)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val reader = started.inputStream.bufferedReader()
        val ready = reader.readLine()?.let { Json.parseToJsonElement(it).jsonObject }
        if (ready == null || ready["ready"]?.jsonPrimitive?.booleanOrNull != true) {
            started.destroyForcibly()
            val why = ready?.get("error")?.jsonPrimitive?.content ?: "the worker exited during startup"
            error("Voice input unavailable with $python: $why")
        }
        backend = ready["backend"]?.jsonPrimitive?.content.orEmpty()
        process = started
        input = started.outputStream.bufferedWriter()
        output = reader
        lprintln("info,ai4", "Voice worker ready: $backend ${ready["model"]?.jsonPrimitive?.content.orEmpty()} via $python")
        val model = ready["model"]?.jsonPrimitive?.content.orEmpty()
        val note = ready["note"]?.jsonPrimitive?.content.orEmpty()
        notify("Voice input ready: $model, $backend" + if (note.isNotBlank()) ". $note" else "", "AI4 voice")
        if (note.isNotBlank()) lprintln("warning,ai4", "Voice worker: $note")
    }

    private fun transcribe(request: AiTranscribeRequest): AiTranscribeResponse {
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
                output!!.readLine()?.let { Json.parseToJsonElement(it) as JsonObject }
            } catch (t: Throwable) {
                null
            } ?: run {
                process?.destroyForcibly()
                process = null
                error("The voice worker stopped; it will restart on the next press.")
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

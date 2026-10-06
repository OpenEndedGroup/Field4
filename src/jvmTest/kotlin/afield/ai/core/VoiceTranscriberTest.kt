package afield.ai.core

import afield.AiTranscribeRequest
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

/**
 * Drives [VoiceTranscriber] against a fake worker (a short Python script speaking the
 * `whisper_worker.py` line protocol), so no model is needed. Pins the three failure paths:
 * a hung worker times out and is restarted, a crashing worker's stderr reaches the error,
 * and a reply carrying another request's id is not taken as this request's transcript.
 */
class VoiceTranscriberTest {
    private val dir = Files.createTempDirectory("voice-transcriber-test").toFile()
    private val savedCommand = VoiceTranscriber.workerCommand
    private val savedTimeout = VoiceTranscriber.replyTimeoutMillis
    private val savedStartup = VoiceTranscriber.startupTimeoutMillis
    private val savedNotifier = VoiceTranscriber.notifier

    private val python = listOf("python3", "/usr/bin/python3", "/opt/homebrew/bin/python3")
        .firstOrNull { runCatching { ProcessBuilder(it, "-c", "pass").start().waitFor() == 0 }.getOrDefault(false) }

    @BeforeTest fun setUp() {
        VoiceTranscriber.notifier = {}
        VoiceTranscriber.startupTimeoutMillis = 20_000
    }

    @AfterTest fun tearDown() {
        VoiceTranscriber.forceRestart()
        VoiceTranscriber.workerCommand = savedCommand
        VoiceTranscriber.replyTimeoutMillis = savedTimeout
        VoiceTranscriber.startupTimeoutMillis = savedStartup
        VoiceTranscriber.notifier = savedNotifier
        dir.deleteRecursively()
    }

    private fun fakeWorker(body: String): List<String> {
        val script = File(dir, "worker${System.nanoTime()}.py")
        script.writeText("import json, sys, os\n$body\n")
        return listOf(python!!, "-u", script.path)
    }

    private val request = AiTranscribeRequest(Base64.getEncoder().encodeToString(ByteArray(64)), 16000, "fromContext")

    private fun VoiceTranscriber.transcribeWith(command: List<String>) =
        runCatching { workerCommand = { command }; forceRestart(); transcribe(request) }

    @Test
    fun aHungWorkerTimesOutAndIsRestarted() {
        assumePython()
        val marker = File(dir, "started-once")
        VoiceTranscriber.replyTimeoutMillis = 1500
        // First process answers ready and then never replies; the second one answers.
        val command = fakeWorker("""
            first = not os.path.exists(${pyString(marker.path)})
            open(${pyString(marker.path)}, "w").close()
            print(json.dumps({"ready": True, "backend": "fake", "model": "m"}))
            for line in sys.stdin:
                if first: continue
                r = json.loads(line)
                print(json.dumps({"id": r["id"], "ok": True, "text": "second worker", "millis": 1}))
        """.trimIndent())
        val hung = VoiceTranscriber.transcribeWith(command)
        val error = hung.exceptionOrNull()?.message.orEmpty()
        assertTrue("did not answer within 1 s and was restarted" in error, error)
        // No forced restart here: the transcriber itself must start a fresh worker.
        val next = VoiceTranscriber.transcribe(request)
        assertTrue(next.success, next.message)
        assertEquals("second worker", next.text)
    }

    @Test
    fun aCrashingWorkersStderrIsInTheError() {
        assumePython()
        val crash = VoiceTranscriber.transcribeWith(fakeWorker("""
            sys.stderr.write("Traceback: ImportError: libfake.so missing\n")
            sys.exit(3)
        """.trimIndent()))
        val error = crash.exceptionOrNull()?.message.orEmpty()
        assertTrue("worker exited during startup" in error, error)
        assertTrue("libfake.so missing" in error, error)

        // A worker that dies mid-request reports its stderr too.
        val midway = VoiceTranscriber.transcribeWith(fakeWorker("""
            print(json.dumps({"ready": True, "backend": "fake", "model": "m"}))
            sys.stdin.readline()
            sys.stderr.write("Segfault-ish: metal device lost\n")
            sys.exit(1)
        """.trimIndent()))
        val midError = midway.exceptionOrNull()?.message.orEmpty()
        assertTrue("The voice worker stopped" in midError, midError)
        assertTrue("metal device lost" in midError, midError)
    }

    @Test
    fun aReplyForAnotherRequestIsNotThisTranscript() {
        assumePython()
        val result = VoiceTranscriber.transcribeWith(fakeWorker("""
            print(json.dumps({"ready": True, "backend": "fake", "model": "m"}))
            for line in sys.stdin:
                r = json.loads(line)
                print("progress noise, not json")
                print(json.dumps({"id": r["id"] - 1, "ok": True, "text": "stale transcript"}))
                print(json.dumps({"id": r["id"], "ok": True, "text": "this transcript", "millis": 5}))
        """.trimIndent())).getOrThrow()
        assertTrue(result.success, result.message)
        assertEquals("this transcript", result.text)
        assertEquals(5, result.millis)
    }

    /**
     * The real worker and model, opt-in: `FIELD_VOICE_LIVE_CLIP=<16 kHz float32 .f32 file>` and
     * `FIELD_VOICE_PYTHON=<venv python with the voice group>`. Prints the transcript and timings.
     */
    @Test
    fun liveClipThroughTheRealWorker() {
        val clip = System.getenv("FIELD_VOICE_LIVE_CLIP")?.let(::File)?.takeIf { it.isFile } ?: return
        VoiceTranscriber.workerCommand = savedCommand
        VoiceTranscriber.forceRestart()
        val live = AiTranscribeRequest(Base64.getEncoder().encodeToString(clip.readBytes()), 16000,
            "Field, Kotlin, AI4. fromContext, Renderer, moduleHere, fragment.")
        for (round in listOf("cold", "warm")) {
            val t0 = System.nanoTime()
            val r = VoiceTranscriber.transcribe(live)
            println("VOICE_LIVE $round wall=${(System.nanoTime() - t0) / 1_000_000}ms worker=${r.millis}ms ok=${r.success} backend=${r.backend} text=${r.text}")
            assertTrue(r.success, r.message)
            assertTrue("fromContext" in r.text, r.text)
        }
    }

    private fun assumePython() {
        if (python == null) error("python3 is required for VoiceTranscriberTest")
    }

    private fun pyString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

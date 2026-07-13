package afield.four.stream

import afield.logger.lprintln
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Launches and drives the `field-encode` Swift co-process (`native/field-encode`) — the macOS
 * VideoToolbox sender for the visionOS stereo/flat docks (see
 * `dev_docs/vision_frontend.md` -> "The sender seam" and `native/field-encode/WIRE_FORMAT.md`).
 *
 * This is pure JVM glue: it spawns the binary, writes newline-delimited JSON commands to its
 * stdin, and reads JSON events from its stdout. **Pixels never cross this pipe** — the helper
 * attaches to Field's RTT by global `IOSurfaceID` (passed once in [open]) and streams HEVC
 * straight to the device over UDP. Field's only jobs are: hand over the surface IDs, and tick
 * [frame] once each eye's render fence has signalled.
 *
 * The child is stateless and disposable: if it dies, call [start] again and re-[open].
 */
class FieldEncodeProcess(
    private val binaryPath: String = defaultBinaryPath(),
    private val workingDirectory: String? = null,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdin: BufferedWriter? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastEvent: JSONObject? = null
        private set

    /** Invoked for every JSON event line on the helper's stdout (opened/encoded/error/...). */
    var onEvent: ((JSONObject) -> Unit)? = null

    /** Invoked once when the process exits, with its exit code. */
    var onExit: ((Int) -> Unit)? = null

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val bin = File(binaryPath)
        if (!bin.canExecute()) {
            lprintln("error,field-encode",
                "binary not executable: $binaryPath — build it: (cd native/field-encode && swift build -c release)")
            return false
        }
        val proc = try {
            ProcessBuilder(binaryPath).apply {
                workingDirectory?.let { directory(File(it)) }
            }.start()
        } catch (t: Throwable) {
            lprintln("error,field-encode", "failed to start: ${t.message ?: t}")
            return false
        }
        process = proc
        stdin = proc.outputStream.bufferedWriter()
        running = true

        thread(name = "field-encode-stdout", isDaemon = true) {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        runCatching { JSONObject(trimmed) }.getOrNull()?.let { ev ->
                            lastEvent = ev
                            onEvent?.invoke(ev)
                        }
                    }
                }
            }
        }
        thread(name = "field-encode-stderr", isDaemon = true) {
            runCatching {
                proc.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { if (it.isNotBlank()) lprintln("info,field-encode", it) }
                }
            }
        }
        thread(name = "field-encode-wait", isDaemon = true) {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            running = false
            onExit?.invoke(code)
        }
        return true
    }

    /**
     * Allocate a ring of `count` IOSurfaces in the helper and get their global IDs back (via an
     * `{"ev":"allocated","ids":[…]}` event on [onEvent]). Field imports these by ID as its RTT
     * backing — they carry a real pixelFormat, unlike MoltenVK's export. Precedes [open].
     */
    fun alloc(width: Int, height: Int, count: Int) = send(
        JSONObject().put("cmd", "alloc")
            .put("width", width).put("height", height).put("count", count)
    )

    /**
     * Set up the encoder + UDP stream to `dest` ("host:port"). Pass `surfaces` (global IOSurfaceIDs)
     * to look them up here (legacy); omit to use the ring created by a prior [alloc].
     */
    fun open(
        width: Int, height: Int, fps: Int, bitrateKbps: Int,
        dest: String, codec: String = "hevc", idrIntervalMs: Int = 1000,
        surfaces: List<Long> = emptyList(), stereo: Boolean = false,
    ) = send(
        JSONObject()
            .put("cmd", "open")
            .apply { if (surfaces.isNotEmpty()) put("surfaces", JSONArray(surfaces)) }
            .put("width", width).put("height", height).put("fps", fps)
            .put("bitrateKbps", bitrateKbps).put("dest", dest)
            .put("codec", codec).put("idrIntervalMs", idrIntervalMs)
            .put("stereo", stereo)
    )

    /** Signal that ring surface [surface] is rendered and ready to encode now. */
    fun frame(surface: Int, seq: Long, ptsMicros: Long, forceIdr: Boolean = false) = send(
        JSONObject().put("cmd", "frame").put("surface", surface)
            .put("seq", seq).put("ptsMicros", ptsMicros).put("forceIdr", forceIdr)
    )

    fun bitrate(kbps: Int) = send(JSONObject().put("cmd", "bitrate").put("kbps", kbps))

    /** Fire the helper's self-contained smoke test (synthetic frames, no RTT needed). */
    fun selftest(
        dest: String, frames: Int = 120, width: Int = 1280, height: Int = 720,
        fps: Int = 60, bitrateKbps: Int = 20000, codec: String = "hevc",
    ) = send(
        JSONObject().put("cmd", "selftest").put("dest", dest).put("frames", frames)
            .put("width", width).put("height", height).put("fps", fps)
            .put("bitrateKbps", bitrateKbps).put("codec", codec)
    )

    fun send(cmd: JSONObject) {
        val writer = stdin ?: run {
            lprintln("warn,field-encode", "not running; dropped ${cmd.optString("cmd")}")
            return
        }
        synchronized(writer) {
            runCatching { writer.write(cmd.toString()); writer.write("\n"); writer.flush() }
                .onFailure { lprintln("warn,field-encode", "write failed: ${it.message}") }
        }
    }

    /** Graceful: send `close`, then close stdin (helper exits its readLine loop), then reap. */
    fun close() {
        runCatching { send(JSONObject().put("cmd", "close")) }
        stop()
    }

    @Synchronized
    fun stop() {
        val proc = process ?: return
        runCatching { stdin?.close() }
        runCatching {
            proc.destroy()
            if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(300, TimeUnit.MILLISECONDS)
            }
        }
        process = null
        stdin = null
        running = false
    }

    companion object {
        /** Prefer the release build, fall back to debug; relative to the field4 repo root (cwd). */
        fun defaultBinaryPath(): String {
            val base = "native/field-encode/.build"
            val release = File("$base/release/field-encode")
            val debug = File("$base/debug/field-encode")
            return when {
                release.canExecute() -> release.path
                debug.canExecute() -> debug.path
                else -> release.path
            }
        }
    }
}

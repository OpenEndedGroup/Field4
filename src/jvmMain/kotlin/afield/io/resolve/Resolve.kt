package afield.io.resolve

/**
 * Reads timelines and markers, and reads or moves the playhead of a running DaVinci Resolve,
 * through Resolve's own Python scripting API.
 *
 * [Resolve] is the whole surface: [Resolve.markers] for the current timeline or one by name,
 * [Resolve.timelines] for every timeline in the open project, [Resolve.clipMarkers] for markers
 * that sit on clips rather than on the timeline, and [Resolve.playhead]. Every marker comes back as
 * a [ResolveMarker] with its frame counted from the start of its timeline, the same position as a
 * record frame and timecode on the timeline's own clock, its time in seconds, its color, name, note
 * and the script-only custom data.
 *
 * Resolve's scripting module is a CPython extension, so the calls run in a Python worker process,
 * `python -m fieldresolve` from this checkout's `python/` directory, which Field starts on first use
 * and keeps for the rest of the session. Resolve must be running with a project open, and
 * Preferences > System > General > External scripting using must be Local; otherwise each call
 * fails with [ResolveUnavailable] saying so. `Resolve.on("macbook-pro-2")` asks a Resolve Studio on
 * another machine instead, which must have that setting at Network.
 */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One marker on a Resolve timeline (or, from [Resolve.clipMarkers], on a clip, placed on its
 * timeline).
 *
 * [frame] counts from the first frame of the timeline, so frame 0 is the timeline's start whatever
 * timecode it starts at. [recordFrame] and [timecode] are the same position on the timeline's own
 * clock (a timeline starting at 01:00:00:00 at 24 fps puts frame 0 at record frame 86400).
 * [seconds] is `frame / fps`.
 */
@Serializable
data class ResolveMarker(
    /** Frames from the start of the timeline. */
    val frame: Int,
    /** The same position counted on the timeline's clock, as Resolve numbers record frames. */
    val recordFrame: Int,
    /** The record timecode, `HH:MM:SS:FF` (`;` before the frames for drop-frame timelines). */
    val timecode: String,
    /** Seconds from the start of the timeline. */
    val seconds: Double,
    /** Length in frames; 1 for a marker on a single frame. */
    val duration: Int,
    /** Resolve's color name: Blue, Cyan, Green, Yellow, Red, Pink, Purple, Fuchsia, Rose, Lavender, Sky, Mint, Lemon, Sand, Cocoa or Cream. */
    val color: String,
    /** The marker's name, as shown in the marker dialog. */
    val name: String,
    /** The marker's note: its text annotation. */
    val note: String,
    /** Data a script attached to the marker; Resolve's UI never shows it. */
    val customData: String = "",
    /** For a clip marker, the clip's name; null for a timeline marker. */
    val clip: String? = null,
    /** For a clip marker, `"video"` or `"audio"`. */
    val trackType: String? = null,
    /** For a clip marker, its track number, from 1. */
    val track: Int? = null,
    /** For a clip marker, its frame in the clip's source media. */
    val clipFrame: Int? = null,
) {
    /** True when [atFrame] (from the timeline start) falls within this marker's duration. */
    fun covers(atFrame: Int) = atFrame >= frame && atFrame < frame + maxOf(1, duration)
}

/** A timeline in the open Resolve project, with its markers in frame order. */
@Serializable
data class ResolveTimeline(
    val name: String,
    /** Position in the project's timeline list, from 1. */
    val index: Int,
    val uniqueId: String,
    /** True for the timeline open in Resolve's viewer. */
    val current: Boolean,
    /** Record frame of the first frame. */
    val startFrame: Int,
    /** Record frame just past the last frame. */
    val endFrame: Int,
    val startTimecode: String,
    val fps: Double,
    val dropFrame: Boolean,
    val markers: List<ResolveMarker> = emptyList(),
) {
    /** Frames in the timeline. */
    val length: Int get() = endFrame - startFrame

    /** Markers whose span includes [frame] (from the timeline start). */
    fun markersAt(frame: Int) = markers.filter { it.covers(frame) }

    /** Markers of one color, compared without regard to case. */
    fun markersColored(color: String) = markers.filter { it.color.equals(color, ignoreCase = true) }
}

/** Where the playhead of Resolve's current timeline is. */
@Serializable
data class ResolvePlayhead(
    val timeline: String,
    val timecode: String,
    val recordFrame: Int,
    /** Frames from the start of the timeline, comparable with [ResolveMarker.frame]. */
    val frame: Int,
    val seconds: Double,
    val fps: Double,
)

/** What the running Resolve reports about itself. */
@Serializable
data class ResolveStatus(
    val product: String,
    val version: String,
    /** The open page: media, cut, edit, fusion, color, fairlight or deliver. */
    val page: String? = null,
    val project: String? = null,
    val timelineCount: Int = 0,
    /** Name of the current timeline. */
    val timeline: String? = null,
    /** The machine asked, as given to [Resolve.on]; null for this machine. */
    val host: String? = null,
)

/** Resolve is not running, has no project open, refuses external scripts, or its scripting module is missing. */
class ResolveUnavailable(message: String) : IllegalStateException(message)

/**
 * One DaVinci Resolve: its timelines, their markers, and the playhead. [Resolve] is the one on this
 * machine; [Resolve.on] gives the one on another machine.
 *
 * Each call asks Resolve again, so what comes back is what the project holds at that moment.
 */
open class ResolveConnection internal constructor(
    /** The machine this Resolve runs on, a host name or address; null for this machine. */
    val host: String?,
) {
    /** True when this Resolve answers [status] right now. */
    val available: Boolean get() = runCatching { status() }.isSuccess

    /** Product, version, open page, project and current timeline. */
    fun status(): ResolveStatus = Resolve.decode(Resolve.call("status", null, host))

    /** Every timeline in the open project, in the project's order, each with its markers. */
    fun timelines(): List<ResolveTimeline> = Resolve.decode(Resolve.call("timelines", null, host))

    /** The current timeline (null [name]) or the one called [name], with its markers. */
    fun timeline(name: String? = null): ResolveTimeline = Resolve.decode(Resolve.call("timeline", name, host))

    /** The timeline markers of the current timeline (null [timeline]) or the one so named, earliest first. */
    fun markers(timeline: String? = null): List<ResolveMarker> = Resolve.decode(Resolve.call("markers", timeline, host))

    /**
     * Markers attached to the clips on a timeline's video and audio tracks, placed on that timeline.
     * A marker on a frame that the edit has trimmed away is left out.
     */
    fun clipMarkers(timeline: String? = null): List<ResolveMarker> = Resolve.decode(Resolve.call("clipMarkers", timeline, host))

    /** The playhead of the current timeline; Resolve reports none for other timelines. */
    fun playhead(): ResolvePlayhead = Resolve.decode(Resolve.call("playhead", null, host))

    /** Read only if [timeline] is already open; never switches the editor's current timeline. */
    fun playhead(timeline: String): ResolvePlayhead = Resolve.decode(Resolve.call("playhead", timeline, host))

    /** Move the current timeline's playhead to [frame], counted from its start, and return the observed position. */
    fun setPlayhead(frame: Int, timeline: String? = null): ResolvePlayhead {
        require(frame >= 0) { "Playhead frame must be non-negative" }
        return Resolve.decode(Resolve.call("setPlayhead", timeline, host, mapOf("frame" to JsonPrimitive(frame))))
    }

    /** Move to an absolute record timecode, including the timeline's start-timecode offset. */
    fun setPlayhead(timecode: String, timeline: String? = null): ResolvePlayhead =
        Resolve.decode(Resolve.call("setPlayhead", timeline, host, mapOf("timecode" to JsonPrimitive(timecode))))

    /** Move to seconds from the timeline start, rounded to the nearest timeline frame. */
    fun setPlayheadSeconds(seconds: Double, timeline: String? = null): ResolvePlayhead {
        require(seconds.isFinite() && seconds >= 0) { "Playhead seconds must be finite and non-negative" }
        return Resolve.decode(Resolve.call("setPlayhead", timeline, host, mapOf("seconds" to JsonPrimitive(seconds))))
    }

    override fun toString() = "Resolve on ${host ?: "this machine"}"
}

/**
 * The DaVinci Resolve running on this machine, and [on] for one running on another.
 *
 * ```
 * for (m in Resolve.markers()) println("${m.timecode} ${m.color} ${m.name}: ${m.note}")
 * val edit = Resolve.timeline("Edit 2")
 * val reds = edit.markersColored("Red")
 * val other = Resolve.on("macbook-pro-2").markers()
 * ```
 *
 * Calls run one at a time, whichever machine they go to; one that gets no answer within
 * [timeoutSeconds] stops the worker, and the next call starts a fresh one.
 */
object Resolve : ResolveConnection(null) {
    /**
     * The Resolve on [host], a host name or address (a Tailscale name works). That machine's
     * Resolve Studio must allow it: Preferences > System > General > External scripting using >
     * Network. It listens on port 1144, and answers on a port from 49152 to 65535.
     */
    fun on(host: String): ResolveConnection = ResolveConnection(host.trim().ifEmpty { null })

    /** How long one call waits for Resolve before giving up. */
    @Volatile
    var timeoutSeconds: Long = 15

    /** Stop the worker; the next call starts another. */
    fun close() = synchronized(this) { worker?.stop(); worker = null }

    /** Extra environment for workers started from now on; `ResolveTest` sets `FIELDRESOLVE_FAKE=1` here. */
    internal var workerEnvironment: Map<String, String> = emptyMap()

    internal val json = Json { ignoreUnknownKeys = true }

    internal inline fun <reified T> decode(e: JsonElement): T = json.decodeFromJsonElement(e)

    private var worker: Worker? = null
    private var nextId = 0L

    internal fun call(op: String, timeline: String?, host: String?, arguments: Map<String, JsonElement> = emptyMap()): JsonElement = synchronized(this) {
        val w = worker?.takeIf { it.alive } ?: Worker.start().also { worker = it }
        val id = ++nextId
        val request = JsonObject(buildMap {
            put("id", JsonPrimitive(id)); put("op", JsonPrimitive(op))
            if (timeline != null) put("timeline", JsonPrimitive(timeline))
            if (host != null) put("host", JsonPrimitive(host))
            putAll(arguments)
        })
        val where = host?.let { " on $it" }.orEmpty()
        val line = try {
            w.exchange(request.toString(), timeoutSeconds)
        } catch (e: TimeoutException) {
            w.stop(); worker = null
            throw ResolveUnavailable("Resolve$where did not answer '$op' within $timeoutSeconds s (a modal dialog open in Resolve blocks scripts); the worker was stopped")
        }
        val reply = json.parseToJsonElement(line).jsonObject
        check(reply["id"]?.jsonPrimitive?.contentOrNull == id.toString()) { "Resolve worker answered request ${reply["id"]}, expected $id" }
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull == true) return reply["result"]!!
        val message = reply["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
        when (reply["kind"]?.jsonPrimitive?.contentOrNull) {
            "unavailable" -> throw ResolveUnavailable(message)
            "missing" -> throw NoSuchElementException(message)
            else -> error("Resolve$where '$op' failed: $message")
        }
    }

    /** One `python -m fieldresolve` process, answering one JSON line per request line. */
    private class Worker(private val process: Process) {
        private val toWorker: BufferedWriter = process.outputStream.bufferedWriter()
        private val fromWorker: BufferedReader = process.inputStream.bufferedReader()
        private val io = Executors.newSingleThreadExecutor { Thread(it, "resolve-worker-io").apply { isDaemon = true } }

        val alive get() = process.isAlive

        fun exchange(line: String, timeoutSeconds: Long): String {
            val f = io.submit<String> {
                toWorker.write(line); toWorker.newLine(); toWorker.flush()
                fromWorker.readLine() ?: throw ResolveUnavailable("the Resolve worker exited (code ${runCatching { process.waitFor(1, TimeUnit.SECONDS); process.exitValue() }.getOrNull()}); log: ${logFile()}")
            }
            return try { f.get(timeoutSeconds, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }
        }

        fun stop() {
            runCatching { toWorker.close() }
            io.shutdownNow()
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        companion object {
            fun start(): Worker {
                val pythonDir = pythonDir() ?: throw ResolveUnavailable("python/fieldresolve not found above ${System.getProperty("user.dir")}: run Field from its checkout")
                val python = python(pythonDir.parentFile) ?: throw ResolveUnavailable("no Python 3 interpreter found for the Resolve worker; set AFIELD_RESOLVE_PYTHON to one")
                val process = ProcessBuilder(python, "-s", "-m", "fieldresolve", "serve")
                    .directory(pythonDir)
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile()))
                    .apply {
                        environment()["PYTHONPATH"] = pythonDir.path
                        environment()["PYTHONUNBUFFERED"] = "1"
                        environment().remove("PYTHONHOME")
                        environment().putAll(workerEnvironment)
                    }
                    .start()
                return Worker(process)
            }
        }
    }

    /** The `python/` directory holding `fieldresolve`, found above the working directory. */
    fun pythonDir(): File? = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "python") }
        .firstOrNull { File(it, "fieldresolve/__init__.py").isFile }

    /**
     * The interpreter the worker runs on: `AFIELD_RESOLVE_PYTHON`, else the checkout's `.venv`, else
     * the first `python3` on `PATH` or in the usual install places. Resolve's module needs only
     * Python 3.6 or later and nothing from the venv.
     */
    fun python(checkout: File): String? {
        System.getenv("AFIELD_RESOLVE_PYTHON")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val venv = if (windows) File(checkout, ".venv/Scripts/python.exe") else File(checkout, ".venv/bin/python")
        if (venv.canExecute()) return venv.path
        val names = if (windows) listOf("python.exe", "python3.exe") else listOf("python3")
        val dirs = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() } +
            listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        return dirs.flatMap { d -> names.map { Paths.get(d, it) } }.firstOrNull { Files.isExecutable(it) }?.toString()
    }

    /** Where the worker's stderr goes. */
    fun logFile(): File = File(System.getProperty("java.io.tmpdir"), "field-resolve-worker.log")
}

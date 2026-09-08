package afield.layout

import afield.four.util.OS
import afield.four.util.OSType
import afield.logger.lprintln
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Field's client for the `native/field-d2-layout` helper — d2's layout engines, TALA in
 * particular, over a one-shot subprocess.
 *
 * **The helper is optional and this class is built around that.** Field does not build it, no
 * Gradle task references it, and it is absent in a fresh checkout. Every entry point here answers
 * `null` rather than throwing when the binary is missing, is the wrong protocol, times out, or
 * reports an error, and callers are expected to fall back to whatever they did before rather than
 * surface a failure. See `native/field-d2-layout/README.md` for the build, the wire format, and
 * why this is a subprocess and not a wasm module hosted in-process like libfaust.
 *
 * One process per call. That looks profligate and is not: the helper starts in low single-digit
 * milliseconds against layouts that cost hundreds, so spawning is lost in the noise, and a
 * short-lived process means no state to corrupt and nothing to reap if Field exits mid-layout.
 * Repeated identical requests are served from [cache] instead, which is what actually matters —
 * a Petri animation redraws the same net many times.
 */
object D2Layout {

    /** Must match `protocolVersion` in the helper's `main.go`. */
    private const val PROTOCOL = 1

    private const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * Where the helper is expected, relative to the repo root (the server's cwd).
     *
     * `.exe` on Windows because that is what `go build` writes there; without it the binary is
     * built, present, and never found.
     */
    private val defaultPath: String
        get() = "native/field-d2-layout/build/field-d2-layout" + if (OS == OSType.WINDOWS) ".exe" else ""

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Identical requests are common — a Petri drawing is rebuilt per frame from the same net —
     * and a layout is a pure function of its request, so the answer is worth keeping. Bounded
     * because a document being edited produces a new request on every change.
     */
    private const val CACHE_LIMIT = 256
    private val cache = ConcurrentHashMap<String, Response>()

    // ── wire types ─────────────────────────────────────────────────────────────────────────

    @Serializable
    data class Node(
        val id: String,
        val w: Double,
        val h: Double,
        /** Nests this node inside another by id; null or empty means the graph root. */
        val parent: String? = null,
    )

    @Serializable
    data class Edge(
        val src: String,
        val dst: String,
        /**
         * Echoed back in the response. Without it, parallel edges between the same pair cannot
         * be told apart on the way home.
         */
        val id: String? = null,
    )

    @Serializable
    data class Placed(
        val id: String,
        val parent: String = "",
        val x: Double,
        val y: Double,
        /** The laid-out size, which is not the requested one for a container: see [layout]. */
        val w: Double,
        val h: Double,
    ) {
        val centreX: Double get() = x + w / 2.0
        val centreY: Double get() = y + h / 2.0
    }

    @Serializable
    data class Routed(
        val id: String,
        val src: String,
        val dst: String,
        /** Absolute even under [Coordinates.RELATIVE] — a route may cross a nest boundary. */
        val route: List<List<Double>> = emptyList(),
    )

    @Serializable
    data class Bounds(val x: Double, val y: Double, val w: Double, val h: Double)

    @Serializable
    data class Response(
        val protocol: Int = 0,
        val ok: Boolean = false,
        val error: String? = null,
        val engine: String? = null,
        val elapsedMs: Double = 0.0,
        val bounds: Bounds? = null,
        val nodes: List<Placed> = emptyList(),
        val edges: List<Routed> = emptyList(),
        @SerialName("d2Version") val d2Version: String? = null,
        val engines: List<String> = emptyList(),
    )

    @Serializable
    private data class Request(
        val op: String = "layout",
        val engine: String,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val seeds: List<Long>? = null,
        val coordinates: String,
        val direction: String? = null,
        val timeoutMs: Int,
    )

    @Serializable
    private data class VersionRequest(val op: String = "version")

    /** d2's three bundled engines. TALA is the one built for diagrams of labelled boxes. */
    enum class Engine(val wire: String) { TALA("tala"), DAGRE("dagre"), ELK("elk") }

    /**
     * Whether a nested node's position is measured from the graph origin or from its parent.
     *
     * [RELATIVE] is the form Field's nested canvases want: a child's frame is expressed inside the
     * nest, not on the document. Root-level nodes are the same under both.
     */
    enum class Coordinates(val wire: String) { ABSOLUTE("absolute"), RELATIVE("relative") }

    /**
     * A bias on the whole layout, or [NONE] to let the engine decide.
     *
     * Cheap and effective: on a 30-node Petri net it took the crossing count from 5 to 0 at no
     * measurable cost, where quadrupling the seed budget only reached 3 and took four times as
     * long. Which direction wins depends on the graph, so there is no good default -- callers
     * either know their shape (a pipeline reads [RIGHT]) or try several and score the results.
     */
    enum class Direction(val wire: String?) {
        NONE(null), UP("up"), DOWN("down"), LEFT("left"), RIGHT("right")
    }

    // ── availability ───────────────────────────────────────────────────────────────────────

    /**
     * Set by tests to point at a binary that is present, or deliberately absent. Production code
     * leaves it null and the env var is the supported override for a packaged run.
     */
    @Volatile
    internal var binaryOverride: String? = null

    private fun binaryPath(): String =
        binaryOverride ?: System.getenv("AFIELD_D2_LAYOUT_BIN") ?: defaultPath

    /**
     * The helper's reported d2 version, or null if it is unusable.
     *
     * Probed once and remembered, including the negative answer: a missing binary must not cost a
     * process spawn on every frame. The protocol number is checked rather than trusted. A stale
     * helper left over from an older checkout would otherwise accept requests and quietly ignore
     * fields it does not know about — the failure this repo already hit with `field-encode`.
     */
    @Volatile
    private var probe: Probe? = null

    private class Probe(val version: String?)

    /**
     * Forgets the availability probe and every memoised layout.
     *
     * For a test that needs both the present and absent paths, and for building the helper under
     * a Field that already decided it was missing.
     */
    fun reset() {
        probe = null
        clearCache()
    }

    /** True when a `go` executable is on PATH. Cheap: no subprocess, just a look along PATH. */
    private fun goOnPath(): Boolean {
        val exe = if (OS == OSType.WINDOWS) "go.exe" else "go"
        return (System.getenv("PATH") ?: "")
            .split(File.pathSeparatorChar)
            .any { it.isNotBlank() && File(it, exe).canExecute() }
    }

    /**
     * What to tell someone who has not got a working helper.
     *
     * Two different problems wear the same symptom and the fix for one is no use for the other: a
     * checkout that already has a Go toolchain needs only the build line, and one that does not
     * needs to install Go first. Only the hint for the platform in front of us is printed — three
     * sets of instructions in a log line is how people learn to skip log lines.
     */
    internal fun buildHint(): String {
        val build = if (OS == OSType.WINDOWS) {
            "cd native\\field-d2-layout; go build -o build\\field-d2-layout.exe ."
        } else {
            "(cd native/field-d2-layout && go build -o build/field-d2-layout .)"
        }
        if (goOnPath()) return "Build it: $build"
        val install = when (OS) {
            OSType.MACOS -> "brew install go"
            OSType.WINDOWS -> "winget install GoLang.Go"
            // Distro packages routinely lag, and d2 wants a recent toolchain.
            OSType.LINUX -> "your package manager, or https://go.dev/dl/ if it is older than 1.21"
            else -> "https://go.dev/dl/"
        }
        return "Go is not on PATH — install it ($install), then: $build"
    }

    val version: String?
        get() = (probe ?: Probe(computeVersion()).also { probe = it }).version

    private fun computeVersion(): String? {
        val bin = File(binaryPath())
        if (!bin.canExecute()) {
            lprintln("info,d2-layout",
                "field-d2-layout is not built at ${bin.path} — TALA layout unavailable, " +
                    "callers will fall back. ${buildHint()}")
            return null
        }
        val res = invoke(json.encodeToString(VersionRequest()), DEFAULT_TIMEOUT_MS) ?: return null
        return when {
            !res.ok -> {
                lprintln("error,d2-layout", "version probe failed: ${res.error}")
                null
            }
            res.protocol != PROTOCOL -> {
                lprintln("error,d2-layout",
                    "field-d2-layout speaks protocol ${res.protocol}, Field expects $PROTOCOL. " +
                        "It is stale. ${buildHint()}")
                null
            }
            else -> res.d2Version ?: "unknown"
        }
    }

    /** True when the helper is present and speaks a protocol this Field understands. */
    val available: Boolean get() = version != null

    /**
     * What to show a user who asked for something this helper does, when it cannot be had.
     *
     * Null when the helper is fine. Otherwise the same platform-aware line that goes to the log:
     * build it, or install Go first. A caller that has no fallback -- laying out boxes has none,
     * where a Petri drawing can use its own solver -- should put this in front of the user rather
     * than doing nothing.
     */
    val unavailableHint: String? get() = if (available) null else buildHint()

    // ── layout ─────────────────────────────────────────────────────────────────────────────

    /**
     * Lays [nodes] out under [edges], or returns null if the helper cannot do it.
     *
     * Sizes go in and come back, and they are not always the same: a container's size is derived
     * from the children the engine placed inside it, so a node declared 50x50 with two children
     * returns much larger. Callers must read [Placed.w]/[Placed.h] rather than reuse what they
     * sent.
     *
     * [seeds] selects TALA's deterministic attempts; null uses the engine default of 1, 2, 3.
     * The same request always produces the same drawing, so a seed is part of the input, not a
     * source of noise.
     */
    fun layout(
        nodes: List<Node>,
        edges: List<Edge>,
        engine: Engine = Engine.TALA,
        coordinates: Coordinates = Coordinates.ABSOLUTE,
        seeds: List<Long>? = null,
        direction: Direction = Direction.NONE,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Response? {
        if (nodes.isEmpty()) return null
        if (!available) return null

        val body = json.encodeToString(
            Request(
                engine = engine.wire,
                nodes = nodes,
                edges = edges,
                seeds = seeds,
                coordinates = coordinates.wire,
                direction = direction.wire,
                timeoutMs = timeoutMs.toInt(),
            )
        )
        cache[body]?.let { return it }

        val res = invoke(body, timeoutMs + SPAWN_GRACE_MS) ?: return null
        if (!res.ok) {
            lprintln("error,d2-layout", "${engine.wire} layout failed: ${res.error}")
            return null
        }
        // Clearing wholesale rather than evicting by age: the cost of a miss is one subprocess,
        // and an LRU here would be more machinery than the problem deserves.
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[body] = res
        return res
    }

    /** Drops every memoised layout. For a test, or after rebuilding the helper under a live Field. */
    fun clearCache() = cache.clear()

    // ── process ────────────────────────────────────────────────────────────────────────────

    /**
     * The helper enforces its own deadline and answers with an error, so this margin only covers
     * spawning and the pipes. A process still alive past it is not answering at all.
     */
    private const val SPAWN_GRACE_MS = 5_000L

    private fun invoke(requestJson: String, timeoutMs: Long): Response? {
        val process = try {
            ProcessBuilder(binaryPath()).start()
        } catch (t: Throwable) {
            lprintln("error,d2-layout", "could not start ${binaryPath()}: ${t.message ?: t}")
            return null
        }

        // stderr is drained on its own thread. Reading it after stdout would deadlock the moment
        // the helper wrote more than a pipe buffer's worth of diagnostics before we got there.
        val stderr = StringBuilder()
        val drain = thread(name = "d2-layout-stderr", isDaemon = true) {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderr) { stderr.appendLine(line) }
                }
            }
        }

        val text = try {
            process.outputStream.bufferedWriter().use { it.write(requestJson); it.newLine() }
            process.inputStream.bufferedReader().readText()
        } catch (t: Throwable) {
            process.destroyForcibly()
            lprintln("error,d2-layout", "talking to the helper failed: ${t.message ?: t}")
            return null
        }

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            lprintln("error,d2-layout", "helper did not exit within ${timeoutMs}ms; killed")
            return null
        }
        drain.join(500)

        val diagnostics = synchronized(stderr) { stderr.toString().trim() }
        if (text.isBlank()) {
            lprintln("error,d2-layout",
                "helper produced no response (exit ${process.exitValue()})" +
                    if (diagnostics.isEmpty()) "" else ": $diagnostics")
            return null
        }
        return try {
            // One request in, so the first line is the whole answer.
            json.decodeFromString<Response>(text.lineSequence().first { it.isNotBlank() })
        } catch (t: Throwable) {
            lprintln("error,d2-layout", "unreadable response: ${t.message ?: t}")
            null
        }
    }
}

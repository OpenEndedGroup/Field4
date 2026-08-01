package afield.morpho

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.io.IOAccess
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

/**
 * Hosts MorphoHDL inside Field by running morpho's **own** compiler on GraalJS.
 *
 * Field already depends on `org.graalvm.polyglot:js` and `:wasm` (see `build.gradle.kts`, and
 * `afield.four.synth.Dough` for the established idiom), so bridging morpho costs a driver script
 * and this file rather than a port. That matters beyond convenience: a port would be Field's
 * opinion of what MorphoHDL means, and would drift. This is upstream's parser, upstream's width
 * inference and upstream's expansion, byte-identical to the tag recorded in
 * `morpho/PROVENANCE.md`, with Field supplying only simulation, layout and drawing.
 *
 * Everything guest-side is funnelled through [withJs] under one lock. A Graal [Context] is not
 * safe for concurrent access, and Field will happily execute two boxes at once.
 */
object MorphoRuntime {

    private val lock = ReentrantLock()

    /**
     * Where the vendored tree lives as real files.
     *
     * ES module imports need a filesystem, so a packaged run unpacks the resources to a temp
     * directory once. A dev checkout skips that and reads `src/jvmMain/resources` in place, so
     * editing the driver and re-running picks the change up without a rebuild.
     */
    val root: Path by lazy { materialize() }

    private val VENDORED = listOf(
        "morpho-field-driver.mjs",
        "morpho/js/parser.js",
        "morpho/js/primitives.js",
        "morpho/js/compiler.js",
        "morpho/js/utils.js",
        "morpho/tiny_morpho.py",
        "morpho/graphs_engine/main.wasm",
    )

    private fun materialize(): Path {
        val dev = Paths.get("./src/jvmMain/resources").toAbsolutePath().normalize()
        if (VENDORED.all { dev.resolve(it).exists() }) return dev

        val tmp = Files.createTempDirectory("field-morpho")
        tmp.toFile().deleteOnExit()
        VENDORED.forEach { rel ->
            val stream = MorphoRuntime::class.java.getResourceAsStream("/$rel")
                ?: error("morpho resource missing from the classpath: /$rel")
            val target = tmp.resolve(rel)
            Files.createDirectories(target.parent)
            stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
        return tmp
    }

    private val context: Context by lazy {
        Context.newBuilder("js", "wasm")
            .allowIO(IOAccess.ALL)
            .allowHostAccess(HostAccess.ALL)
            // Required for js.webassembly: the JS WebAssembly API is implemented on top of the
            // "wasm" language, so JS must be allowed to reach it.
            .allowPolyglotAccess(PolyglotAccess.ALL)
            .allowExperimentalOptions(true)
            .option("js.esm-eval-returns-exports", "true")
            .option("js.webassembly", "true")
            .build()
    }

    private fun module(fileName: String): Value = withJs {
        context.eval(
            Source.newBuilder("js", root.resolve(fileName).toFile())
                .mimeType("application/javascript+module")
                .build()
        )
    }

    private val driver: Value by lazy { module("morpho-field-driver.mjs") }
    private val layoutModule: Value by lazy { module("morpho-field-layout.mjs") }

    internal fun <T> withJs(block: () -> T): T = lock.withLock {
        context.enter()
        try {
            block()
        } finally {
            context.leave()
        }
    }

    internal fun call(member: String, vararg args: Any?): Value =
        withJs { driver.getMember(member).execute(*args) }

    /**
     * Ask whatever the guest is currently doing to stop, from *another* thread.
     *
     * Hand-written MorphoHDL is written by someone who wants a circuit, so nothing in the box
     * language needs this. Generated MorphoHDL is not: `materialize` recurses once per cell
     * expansion (`compiler.js:1084`) and only bottoms out when a `SPLIT` runs out of bits, so a
     * synthesised body whose self-call does not narrow its arguments never returns.
     * [afield.morpho.MorphoEvaluator] arms this before every compile so a bad candidate costs one
     * timeout instead of the process.
     *
     * Interrupting is safe here and closing would not be: a [Context] survives an interrupt and
     * stays usable, which matters because every other morpho box in the document shares this one.
     * It must not be called from a thread that is inside [withJs] — hence "another thread".
     *
     * @return true if the guest acknowledged within [timeout].
     */
    internal fun interruptGuest(timeout: java.time.Duration = java.time.Duration.ofSeconds(2)): Boolean =
        runCatching { context.interrupt(timeout); true }.getOrDefault(false)

    internal fun callLayout(member: String, vararg args: Any?): Value =
        withJs { layoutModule.getMember(member).execute(*args) }

    /**
     * The MorphoHDL standard library — 8 LUTs and 26 cells covering adders, multipliers,
     * shifters, muxes and the growth shapes.
     *
     * It is read out of `tiny_morpho.py` between the `# MORPHO_BEGIN` / `# MORPHO_END` markers,
     * because that is where upstream keeps it: the JavaScript parses the very bytes the Python
     * executes. One copy, two implementations — which is exactly what makes the differential test
     * meaningful.
     */
    val standardLibrary: String by lazy {
        val text = Files.readString(root.resolve("morpho/tiny_morpho.py"))
        val begin = text.indexOf(BEGIN_MARKER)
        val end = text.indexOf(END_MARKER)
        require(begin >= 0 && end > begin) {
            "tiny_morpho.py is missing its $BEGIN_MARKER / $END_MARKER markers"
        }
        text.substring(begin + BEGIN_MARKER.length, end).trim()
    }

    private const val BEGIN_MARKER = "# MORPHO_BEGIN"
    private const val END_MARKER = "# MORPHO_END"

    /** Parse [source] and report what it defines, without compiling anything. */
    fun inspect(source: String): String = morphoErrors { call("inspect", source).asString() }

    /** Names of the cells a source defines, in declaration order. */
    fun cellNames(source: String): List<String> =
        Regex("\"name\":\"([A-Za-z_]\\w*)\"").findAll(inspect(source)).map { it.groupValues[1] }.toList()

    /**
     * Compile [design] at [signature] and return a handle that has not been expanded yet.
     *
     * @param optimize run morpho's constant propagation and dead-code elimination during
     *   expansion. Upstream's demo defaults this on; leaving it off shows the unoptimised
     *   structure, which is usually the more legible thing to watch grow.
     * @param maxFanout insert buffer trees above this fanout, or 0 for unlimited.
     */
    fun compile(
        source: String = standardLibrary,
        design: String,
        signature: IntArray,
        optimize: Boolean = true,
        maxFanout: Int = 0,
    ): MorphoSession = morphoErrors {
        val state = call("create", source, design, signature.joinToString(","), optimize, maxFanout)
        MorphoSession(design, signature, state)
    }

    /**
     * Translate a guest failure into something a box can show on the right line.
     *
     * morpho's parser attaches a `line` to syntax errors; its compiler mostly throws plain
     * `Error`s, so the line is often absent and the message is all there is.
     */
    internal fun <T> morphoErrors(block: () -> T): T = try {
        block()
    } catch (e: PolyglotException) {
        if (!e.isGuestException) throw e
        val line = runCatching {
            e.guestObject?.takeIf { it.hasMember("line") }?.getMember("line")?.asInt()
        }.getOrNull() ?: e.sourceLocation?.startLine?.takeIf { it > 0 }
        throw MorphoException(e.message ?: "morpho error", line, e)
    }
}

/** A MorphoHDL parse, compile or expansion failure, with a source line where morpho supplied one. */
class MorphoException(
    message: String,
    val line: Int?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * One circuit being grown.
 *
 * A session starts as a single unexpanded cell and is expanded a budget at a time by [grow], which
 * is what makes growth watchable rather than a thing that has already happened by the time the box
 * finishes running. Snapshot with [netlist] whenever you want to draw.
 */
class MorphoSession(
    val design: String,
    val signature: IntArray,
    /** The guest-side growth handle. Shared with [MorphoLayout3D], which lays out the same graph. */
    internal val state: Value,
) : Closeable {

    private var closed = false

    /**
     * Expand up to [budget] cells and return how many actually expanded; 0 means fully grown.
     *
     * @param method `"largest"` expands the biggest estimated subtree first, so the circuit's
     *   overall shape appears early and fills in. `"bfs"` walks cell order, so it grows outward
     *   from the inputs. Upstream defaults to largest-first and so do we.
     */
    fun grow(budget: Int = Int.MAX_VALUE, method: String = "largest"): Int {
        check(!closed) { "session for $design has been closed" }
        return MorphoRuntime.morphoErrors {
            MorphoRuntime.call("grow", state, budget, method).asInt()
        }
    }

    /** Expand to completion, returning the total number of expansions. */
    fun growFully(method: String = "largest"): Int {
        var total = 0
        while (true) {
            val n = grow(Int.MAX_VALUE, method)
            if (n == 0) break
            total += n
        }
        return total
    }

    fun isFullyGrown(): Boolean =
        MorphoRuntime.call("isFullyGrown", state).asBoolean()

    /** Copy the current circuit out of the guest. Safe to call between [grow] steps. */
    fun netlist(): MorphoNetlist = MorphoRuntime.withJs {
        val ex = MorphoRuntime.call("extract", state)
        val names = ex.getMember("names").asString()
        MorphoNetlist(
            nCells = ex.getMember("nCells").asInt(),
            nNets = ex.getMember("nNets").asInt(),
            nPins = ex.getMember("nPins").asInt(),
            steps = ex.getMember("steps").asInt(),
            meta = ex.getMember("cellMeta").`as`(IntArray::class.java),
            cellPinCount = ex.getMember("cellPinCount").`as`(IntArray::class.java),
            pinNet = ex.getMember("pinNet").`as`(IntArray::class.java),
            netDriver = ex.getMember("netDriver").`as`(IntArray::class.java),
            inputCells = ex.getMember("inputCells").`as`(IntArray::class.java),
            outputCells = ex.getMember("outputCells").`as`(IntArray::class.java),
            names = if (names.isEmpty()) emptyList() else names.split('\n'),
        )
    }

    /** Signal arrival times from morpho's own `updateTimes()`. */
    fun timing(): MorphoTiming = MorphoRuntime.withJs {
        val arr = MorphoRuntime.call("timing", state).`as`(FloatArray::class.java)
        MorphoTiming(arr, arr.size / 2)
    }

    override fun close() {
        closed = true
    }
}

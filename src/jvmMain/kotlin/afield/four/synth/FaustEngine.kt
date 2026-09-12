@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The Faust compiler, running inside Field.
 *
 * [Faust](https://faust.grame.fr) is a functional language for signal processing that compiles to
 * efficient code. Field embeds the compiler itself as WebAssembly, in
 * `src/jvmMain/resources/faust/` with a `PROVENANCE.md` beside it, and runs it in the same GraalVM
 * runtime that already hosts `dough.wasm`. There is nothing to install on any platform: the build
 * that loads is the one committed in the repository, and `synth.faust.smoke` asserts its version
 * against `EXPECTED_LIBFAUST_VERSION`.
 *
 * ## Two halves, opposite performance
 *
 * Measured on an aarch64 Mac under GraalVM CE 25.0.2, and the reason the object is shaped the way it
 * is:
 *
 *  * **Generated DSP is fast.** `compute` on a compiled program takes ~7.6µs of the 2667µs a
 *    128-frame block has at 48kHz; end to end through [FaustDsp] it is ~60µs, and what separates
 *    those two figures is the JVM↔WebAssembly boundary rather than Faust. `synth.faust.smoke`
 *    asserts a floor of ten times realtime, so playing is not a performance problem here.
 *  * **The compiler is slow.** `compile` settles at ~600ms once Truffle has warmed, against ~30ms
 *    for the same call under Node's V8, and the *first* compile of a process pays 13–22s of warm-up.
 *
 * Three things follow:
 *
 *  1. The compiler gets **its own GraalVM context**, never shared with a [FaustDsp]. A 600ms compile
 *     holding a lock the audio thread wants every 2.67ms would be audible as a dropout.
 *  2. `warmInBackground` pays the 13–22s while Field is starting, rather than at the moment someone
 *     first runs a box.
 *  3. `compile` is memoised on the hash of (source, arguments), so re-running an unchanged box, or
 *     undoing back to a program already seen, costs nothing.
 *
 * ## Errors
 *
 * The vendored compiler is an Emscripten build with exception catching disabled, so *every* failure
 * arrives as an opaque `"Exception catching is disabled"` RuntimeError. The real message is only
 * available afterwards, from `getErrorAfterException()`, and it is well formed:
 * `"probe:2 : ERROR : undefined symbol : sawtoothX"`. [FaustCompileError] parses that into something
 * an editor marker can use.
 */

/**
 * Compiles Faust source to a [FaustProgram], once per distinct (source, arguments) pair.
 *
 * Process-wide, and boots the compiler on first use. `warmInBackground` moves that cost to startup;
 * `shutdown` releases the context so a headless run can exit.
 */
object FaustEngine {

    /** The libfaust build vendored in `resources/faust`; asserted by the smoke test. */
    const val EXPECTED_LIBFAUST_VERSION: String = "2.86.2"

    /**
     * Default compiler arguments.
     *
     * `-I libraries/` points at the standard library inside the Emscripten preload package, which is
     * how `import("stdfaust.lib")` resolves without a filesystem.
     */
    const val DEFAULT_ARGS: String = "-I libraries/"

    private val lock = Any()

    /** How many separate evals to give Emscripten's ready promise. See [ensureLoadedLocked]. */
    private const val STARTUP_POLL_ATTEMPTS = 64

    // One Engine shared by the compiler context and every FaustDsp context, so Truffle's compiled
    // code for the wasm runtime is shared rather than re-JITted per DSP. This matters: it is the
    // difference between one warm-up and one per box.
    internal val sharedEngine: Engine by lazy {
        Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .build()
    }

    private var context: Context? = null
    private var compiler: Value? = null
    private var version: String = ""

    private val cache = ConcurrentHashMap<String, FaustProgram>()

    @Volatile
    private var warming = false

    /**
     * Start loading and warming the compiler on a background thread.
     *
     * Idempotent and non-blocking. Call it early — the first [compile] in a process is 13–22s of
     * Truffle warm-up, and the only good time to spend that is before anybody is waiting.
     */
    fun warmInBackground() {
        synchronized(lock) {
            if (warming || compiler != null) return
            warming = true
        }
        thread(isDaemon = true, name = "faust-warm") {
            runCatching {
                // A trivial program, but a real compile: it is the compile path itself that is slow
                // to warm, not the loading of the module.
                compile("__warm", "process = _;")
            }.onFailure { println("Faust: background warm-up failed: $it") }
        }
    }

    /** The libfaust version actually loaded, e.g. `2.86.2`. Boots the compiler if needed. */
    fun libfaustVersion(): String {
        ensureLoaded()
        return version
    }

    /**
     * Compile Faust source to a WebAssembly module plus its metadata.
     *
     * @param name module name; also the name Faust puts in front of a diagnostic's line number
     * @param source Faust source, with any `#faust` header already removed — see [stripFaustHeader]
     * @throws FaustCompileError with the compiler's own message and, where it gave one, a line
     */
    fun compile(name: String, source: String, args: String = DEFAULT_ARGS): FaustProgram {
        val key = cacheKey(source, args)
        cache[key]?.let { return it.copy(name = name) }

        val program = synchronized(lock) {
            // Re-check inside the lock: two boxes running the same code at once should compile once.
            cache[key]?.let { return@synchronized it }
            ensureLoadedLocked()
            val ctx = context!!
            ctx.enter()
            try {
                compileLocked(name, source, args).also { cache[key] = it }
            } finally {
                ctx.leave()
            }
        }
        return program.copy(name = name)
    }

    /**
     * The Faust block diagram for `source`, as SVG.
     *
     * Returns the top-level `process.svg` and every sub-diagram it links to, keyed by filename, so a
     * viewer can follow the links Faust puts in the SVG without calling back into the compiler.
     *
     * `generateAuxFiles` **throws and still succeeds**: it writes the SVG set into the in-memory
     * filesystem and only then fails trying a C++ backend this build does not include. So the throw
     * is swallowed and the files are read regardless — if they are genuinely absent the map comes
     * back empty, which callers treat as "no diagram" rather than as an error.
     */
    fun blockDiagram(name: String, source: String, args: String = DEFAULT_ARGS): Map<String, String> {
        synchronized(lock) {
            ensureLoadedLocked()
            val ctx = context!!
            ctx.enter()
            try {
                val safe = sanitiseModuleName(name)
                val result = ctx.eval(js("__faustDiagram(${jsString(safe)}, ${jsString(source)}, ${jsString(args)})"))
                if (!result.hasMembers()) return emptyMap()
                val out = LinkedHashMap<String, String>()
                for (fileName in result.memberKeys) {
                    out[fileName] = result.getMember(fileName).asString()
                }
                return out
            } finally {
                ctx.leave()
            }
        }
    }

    /** Drop the memoised programs. Used by tests that need a cold-ish measurement. */
    fun clearCache() {
        cache.clear()
    }

    /**
     * Close the compiler and release its context.
     *
     * Field itself never needs this — the compiler is process-wide and boots once. It exists so a
     * headless run can exit naturally instead of being held open by a live GraalVM context, which is
     * the difference between a smoke test that passes and one that hangs.
     */
    fun shutdown() {
        synchronized(lock) {
            compiler = null
            version = ""
            warming = false
            context?.let { ctx -> runCatching { ctx.close(true) } }
            context = null
            cache.clear()
        }
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private fun cacheKey(source: String, args: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(source.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(args.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureLoaded() {
        synchronized(lock) { ensureLoadedLocked() }
    }

    private fun ensureLoadedLocked() {
        if (compiler != null) return

        val ctx = Context.newBuilder("js", "wasm")
            .engine(sharedEngine)
            .allowExperimentalOptions(true)
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup { true }
            .allowAllAccess(true)
            .allowPolyglotAccess(PolyglotAccess.ALL)
            .option("js.webassembly", "true")
            .option("wasm.UseUnsafeMemory", "true")
            .build()

        ctx.enter()
        try {
            ctx.eval(js(FAUST_HOST_SHIM))

            val bindings = ctx.getBindings("js")
            bindings.putMember("__faustWasmBytes", resourceBytes("libfaust-wasm.wasm"))
            bindings.putMember("__faustDataBytes", resourceBytes("libfaust-wasm.data"))

            ctx.eval(js(resourceText("libfaust-wasm.js")))
            ctx.eval(js("__faustBoot()"))

            // Each __faustPoll() is a fresh eval, which is what lets the pending microtask that
            // resolves Emscripten's ready promise actually run. In practice it is done on the first
            // poll; the loop is here so a build that defers more work cannot turn into a silent hang.
            var ready = false
            var failure: String? = null
            for (attempt in 0 until STARTUP_POLL_ATTEMPTS) {
                val state = ctx.eval(js("__faustPoll()"))
                val error = state.getMember("error")
                if (!error.isNull) {
                    failure = error.asString()
                    break
                }
                if (state.getMember("done").asBoolean() && state.getMember("hasCompiler").asBoolean()) {
                    ready = true
                    break
                }
            }
            if (failure != null) error("libfaust failed to start: $failure")
            if (!ready) error("libfaust did not finish starting after $STARTUP_POLL_ATTEMPTS polls")

            compiler = bindings.getMember("__faustCompiler")
            version = ctx.eval(js("__faustCompiler.version()")).asString()
            context = ctx
        } catch (t: Throwable) {
            runCatching { ctx.leave() }
            runCatching { ctx.close(true) }
            throw IllegalStateException("Faust: could not start the compiler: ${t.message}", t)
        }
        // Deliberately not in a finally: on success the context stays open for the process, and the
        // enter() above is balanced here.
        runCatching { ctx.leave() }
    }

    /** Caller holds [lock] and has entered the context. */
    private fun compileLocked(name: String, source: String, args: String): FaustProgram {
        val ctx = context!!
        val safe = sanitiseModuleName(name)
        val result = ctx.eval(
            js("__faustCompile(${jsString(safe)}, ${jsString(source)}, ${jsString(args)})")
        )

        val error = result.getMember("error")
        if (!error.isNull) {
            throw FaustCompileError.parse(error.asString(), safe)
        }

        val wasm = result.getMember("wasm").let { value ->
            val n = value.arraySize.toInt()
            ByteArray(n) { i -> (value.getArrayElement(i.toLong()).asInt() and 0xFF).toByte() }
        }
        val dspJson = result.getMember("json").asString()
        return FaustProgram(
            name = safe,
            source = source,
            wasm = wasm,
            dspJson = dspJson,
            ui = FaustUi.parse(dspJson)
        )
    }

    private fun js(code: String): Source = Source.newBuilder("js", code, "faust-host.js").buildLiteral()

    private fun jsString(s: String): String = Json.encodeToString(kotlinx.serialization.serializer(), s)

    /**
     * Faust uses the module name as a C identifier and as the diagnostic prefix, so a box title or a
     * path would break the compile in a way that looks like a syntax error in the user's program.
     */
    private fun sanitiseModuleName(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")
        val trimmed = cleaned.trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        return if (trimmed.isEmpty()) "faust" else trimmed.take(48)
    }

    private fun resourceBytes(fileName: String): ByteArray {
        FaustEngine::class.java.getResourceAsStream("/faust/$fileName")?.use { return it.readBytes() }
        val onDisk = File("./src/jvmMain/resources/faust/$fileName")
        require(onDisk.isFile) { "faust/$fileName not on the classpath or at ${onDisk.absolutePath}" }
        return onDisk.readBytes()
    }

    private fun resourceText(fileName: String): String = resourceBytes(fileName).toString(Charsets.UTF_8)
}

/**
 * Remove a leading `#faust` marker line, returning the source and the number of lines removed.
 *
 * Unlike Sonic Pi — where `#` is a comment and so `#sonicpi` can stay in the program that Sonic Pi
 * then counts — `#` is not a comment in Faust, and a `#faust` line would be a syntax error. So the
 * header has to go, and every line number the compiler reports afterwards is short by however many
 * lines went with it. This is the RectSL arrangement rather than the Sonic Pi one.
 *
 * @return the source with the header removed, and the line offset to add to a reported line to get
 *   back to the line of the original text
 */
fun stripFaustHeader(source: String): Pair<String, Int> {
    val lines = source.lines()
    var i = 0
    // Leading blank lines are part of the header: a Kotlin """ string usually starts with a newline.
    while (i < lines.size && lines[i].isBlank()) i++
    if (i < lines.size && lines[i].trimStart().startsWith("#faust", ignoreCase = true)) {
        return lines.drop(i + 1).joinToString("\n") to (i + 1)
    }
    return source to 0
}

/** A compiled Faust program: the WebAssembly module and what the compiler said about it. */
data class FaustProgram(
    val name: String,
    /** The source that was compiled, with any `#faust` header already removed. */
    val source: String,
    val wasm: ByteArray,
    val dspJson: String,
    val ui: FaustUi
) {
    val inputs: Int get() = ui.inputs
    val outputs: Int get() = ui.outputs

    // Data class over a ByteArray: identity by content, so two programs that compiled to the same
    // module compare equal rather than by array identity.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaustProgram) return false
        return name == other.name && dspJson == other.dspJson && wasm.contentEquals(other.wasm)
    }

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + dspJson.hashCode()) + wasm.contentHashCode()

    override fun toString(): String = synthPrettyObject(
        this,
        "name" to name,
        "wasmBytes" to wasm.size,
        "inputs" to inputs,
        "outputs" to outputs,
        "controls" to ui.controls.size
    )
}

/**
 * A Faust compile error, with the line the compiler blamed.
 *
 * [line] is a line of the source that was *handed to the compiler*, so a caller that stripped a
 * `#faust` header has to add its offset back before putting a marker on a box. See
 * [stripFaustHeader].
 */
class FaustCompileError(
    val faustMessage: String,
    val line: Int?,
    val raw: String
) : Exception(faustMessage) {

    companion object {
        // e.g. "probe:2 : ERROR : undefined symbol : sawtoothX"
        private val located = Regex("""^\s*(\S+?):(\d+)\s*:\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)

        fun parse(raw: String, moduleName: String): FaustCompileError {
            val trimmed = raw.trim()
            val first = trimmed.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: trimmed
            val match = located.matchEntire(first)
            if (match != null && match.groupValues[1].endsWith(moduleName)) {
                return FaustCompileError(
                    faustMessage = match.groupValues[3].trim().removePrefix("ERROR :").trim(),
                    line = match.groupValues[2].toIntOrNull(),
                    raw = trimmed
                )
            }
            return FaustCompileError(trimmed.ifEmpty { "unknown Faust compile error" }, null, trimmed)
        }
    }
}

/**
 * What a Faust program declares about its own controls, flattened.
 *
 * Faust programs describe their user interface in the source — `hslider`, `button`, `hgroup` and so
 * on — and the compiler hands that back as JSON. This is the thing that makes a Faust box feel like
 * part of Field rather than a text area that makes noise: the controls are generated, not written.
 * See `faustControls` for the bridge onto Field's own control surface.
 */
data class FaustUi(
    val inputs: Int,
    val outputs: Int,
    /** Every control, groups flattened. Alphabetical within a group — see [writable]. */
    val controls: List<FaustControl>
) {
    /**
     * Controls a person can move.
     *
     * In the order Faust lists them, which is **alphabetical within each group** rather than the order
     * they were declared — `cutoff` comes before `freq` however the source was written. Look a control
     * up by label rather than by position.
     */
    val writable: List<FaustControl> get() = controls.filter { !it.isReadOnly }

    /** `hbargraph`/`vbargraph`: values the DSP reports back, for meters. */
    val meters: List<FaustControl> get() = controls.filter { it.isReadOnly }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(dspJson: String): FaustUi {
            val root = json.parseToJsonElement(dspJson).jsonObject
            val controls = mutableListOf<FaustControl>()
            // Faust wraps every program in one implicit group named after the module. That name is
            // Field's own (`box_a1b2c3`), so carrying it into the labels would put the box id in
            // front of every slider. Skip the wrapper and keep only groups the author wrote.
            root["ui"]?.jsonArray?.let { top ->
                val implicit = top.size == 1 &&
                    (top[0] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull?.endsWith("group") == true
                if (implicit) {
                    val wrapper = top[0].jsonObject
                    collect(wrapper["items"]?.jsonArray ?: JsonArray(emptyList()), emptyList(), controls)
                } else {
                    collect(top, emptyList(), controls)
                }
            }
            return FaustUi(
                inputs = root["inputs"]?.jsonPrimitive?.intOrNull ?: 0,
                outputs = root["outputs"]?.jsonPrimitive?.intOrNull ?: 0,
                controls = controls
            )
        }

        private fun collect(items: JsonArray, path: List<String>, out: MutableList<FaustControl>) {
            for (element in items) {
                val item = element as? JsonObject ?: continue
                val type = item["type"]?.jsonPrimitive?.contentOrNull ?: continue
                val label = item["label"]?.jsonPrimitive?.contentOrNull ?: ""
                if (type.endsWith("group")) {
                    // Groups only contribute their label to the path; Field's control surface is
                    // flat, so nesting becomes a qualified name rather than a nested widget.
                    collect(item["items"]?.jsonArray ?: JsonArray(emptyList()), path + label, out)
                    continue
                }
                val meta = item["meta"]?.jsonArray.orEmpty()
                out += FaustControl(
                    type = FaustControlType.of(type) ?: continue,
                    label = label,
                    groupPath = path,
                    address = item["address"]?.jsonPrimitive?.contentOrNull ?: "/$label",
                    index = item["index"]?.jsonPrimitive?.intOrNull ?: -1,
                    init = item["init"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    min = item["min"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    max = item["max"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                    step = item["step"]?.jsonPrimitive?.doubleOrNull ?: 0.01,
                    unit = meta.metaValue("unit"),
                    scale = FaustScale.of(meta.metaValue("scale")),
                    tooltip = meta.metaValue("tooltip")
                )
            }
        }

        private fun List<kotlinx.serialization.json.JsonElement>?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
            this ?: emptyList()

        /** Faust metadata is a list of single-entry objects, not one object. */
        private fun List<kotlinx.serialization.json.JsonElement>.metaValue(key: String): String? {
            for (entry in this) {
                val obj = entry as? JsonObject ?: continue
                obj[key]?.jsonPrimitive?.contentOrNull?.let { return it }
            }
            return null
        }
    }
}

/** The Faust control widgets Field knows how to show. */
enum class FaustControlType(val faustName: String) {
    HSLIDER("hslider"),
    VSLIDER("vslider"),
    NENTRY("nentry"),
    BUTTON("button"),
    CHECKBOX("checkbox"),
    HBARGRAPH("hbargraph"),
    VBARGRAPH("vbargraph");

    companion object {
        fun of(name: String): FaustControlType? = entries.firstOrNull { it.faustName == name }
    }
}

/** `[scale:...]` metadata — how a slider's travel maps onto its value. */
enum class FaustScale {
    LINEAR, LOG, EXP;

    companion object {
        fun of(name: String?): FaustScale = when (name?.lowercase()) {
            "log" -> LOG
            "exp" -> EXP
            else -> LINEAR
        }
    }
}

/**
 * One control declared by a Faust program.
 *
 * [index] is the byte offset of this parameter inside the DSP's own WebAssembly memory, which is why
 * setting a value is a float store rather than a message — see [FaustDsp.setParam].
 */
data class FaustControl(
    val type: FaustControlType,
    val label: String,
    val groupPath: List<String>,
    val address: String,
    val index: Int,
    val init: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String? = null,
    val scale: FaustScale = FaustScale.LINEAR,
    val tooltip: String? = null
) {
    /** A bargraph is the DSP talking to the user, not the other way round. */
    val isReadOnly: Boolean
        get() = type == FaustControlType.HBARGRAPH || type == FaustControlType.VBARGRAPH

    val isMomentary: Boolean get() = type == FaustControlType.BUTTON
    val isToggle: Boolean get() = type == FaustControlType.CHECKBOX

    /** Label with its groups, e.g. `filter/cutoff`, for a flat control surface. */
    val qualifiedLabel: String
        get() = (groupPath.filter { it.isNotBlank() } + label).joinToString("/")

    /** Label with its unit, e.g. `cutoff (Hz)`. */
    val displayLabel: String
        get() = if (unit.isNullOrBlank()) qualifiedLabel else "$qualifiedLabel ($unit)"
}

// The host side of the Emscripten contract, and the reason this is a hand-written shim rather than
// an import of faustwasm's own loader: that loader is an ES module graph that expects a bundler, a
// filesystem and fetch. All Field needs is the factory, called with both binaries as bytes.
//
// Environment detection is the sharp edge. Emscripten picks one of web / worker / node / shell at
// startup; this build was not compiled with 'shell', and looking like 'node' sends it to
// require('fs'). So it is shown a *web* environment: an object `window`, a `document` with a script
// src, and a `location` that has a **pathname** — the .data preloader calls
// `window.location.pathname.toString()`. Deliberately no `importScripts` (that reads as a worker)
// and no `process` (that reads as node). Because `wasmBinary` and `getPreloadedPackage` are supplied
// directly, no fetch or XHR path is ever reached.
private const val FAUST_HOST_SHIM: String = """
var window = globalThis;
var document = { currentScript: { src: "file:///libfaust-wasm.js" } };
var location = { href: "file:///libfaust-wasm.js", pathname: "/libfaust-wasm.js" };

// Emscripten defers startup through these when it has outstanding dependencies. In practice this
// build needs none, but a queue that is never drained would hang rather than fail loudly.
var __faustTimers = [];
function setTimeout(fn, ms) { __faustTimers.push(fn); return __faustTimers.length; }
function clearTimeout(id) {}
function setImmediate(fn) { __faustTimers.push(fn); return __faustTimers.length; }
function __faustDrain() {
  var n = 0;
  while (__faustTimers.length > 0 && n < 10000) { var f = __faustTimers.shift(); try { f(); } catch (e) {} n++; }
  return n;
}
var performance = { now: function () { return 0; } };
var crypto = { getRandomValues: function (a) { for (var i = 0; i < a.length; i++) a[i] = 0; return a; } };

var __faustState = { done: false, error: null, mod: null };
var __faustCompiler = null;

function __faustBoot() {
  try {
    FaustModule({
      wasmBinary: new Uint8Array(Java.from(__faustWasmBytes)).buffer,
      getPreloadedPackage: function (name, size) {
        return new Uint8Array(Java.from(__faustDataBytes)).buffer;
      },
      print: function (t) { console.log("[faust] " + t); },
      printErr: function (t) { console.log("[faust] " + t); }
    }).then(function (m) {
      __faustState.done = true;
      __faustState.mod = m;
      __faustCompiler = new m.libFaustWasm();
    }, function (e) {
      __faustState.error = "libfaust rejected: " + String(e);
    });
  } catch (e) {
    __faustState.error = "libfaust threw: " + String(e);
  }
  __faustDrain();
  return !__faustState.error;
}

// Startup completes on the microtask queue, and that only runs as an eval unwinds — so readiness
// cannot be observed inside __faustBoot, only from a *later* eval. Hence a poll rather than a
// return value.
function __faustPoll() {
  __faustDrain();
  return { done: !!__faustState.done, error: __faustState.error, hasCompiler: !!__faustCompiler };
}

// Every libfaust call has to funnel through this. The build has exception catching disabled, so a
// thrown error carries no information at all; the real message is only in getErrorAfterException(),
// and the compiler must be told to clean up before it is used again.
function __faustGuard(fn) {
  try {
    return { error: null, value: fn() };
  } catch (e) {
    var message = "";
    try { message = String(__faustCompiler.getErrorAfterException() || ""); } catch (e2) {}
    try { __faustCompiler.cleanupAfterException(); } catch (e2) {}
    if (!message) message = String(e);
    return { error: message, value: null };
  }
}

function __faustCompile(name, source, args) {
  var r = __faustGuard(function () { return __faustCompiler.createDSPFactory(name, source, args, true); });
  if (r.error) return { error: r.error, wasm: null, json: null };
  var factory = r.value;
  // `data` is an embind std::vector<uint8_t>, not a typed array: size()/get(i), and it must be
  // deleted or the compiler's heap grows with every compile.
  var vec = factory.data;
  var n = vec.size();
  var bytes = new Uint8Array(n);
  for (var i = 0; i < n; i++) bytes[i] = vec.get(i);
  var json = factory.json;
  try { vec.delete(); } catch (e) {}
  return { error: null, wasm: bytes, json: json };
}

function __faustDiagram(name, source, args) {
  // Throws *after* writing the SVGs — it goes on to try a C++ backend that is not built. So the
  // error is ignored and the filesystem is read either way.
  __faustGuard(function () { return __faustCompiler.generateAuxFiles(name, source, args + " -svg"); });
  var dir = name + "-svg";
  var out = {};
  try {
    var names = __faustState.mod.FS.readdir(dir);
    for (var i = 0; i < names.length; i++) {
      var f = names[i];
      if (f === "." || f === "..") continue;
      try { out[f] = __faustState.mod.FS.readFile(dir + "/" + f, { encoding: "utf8" }); } catch (e) {}
    }
  } catch (e) {}
  return out;
}
"""

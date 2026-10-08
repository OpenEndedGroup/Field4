@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.Box
import afield.LiveOutputIframe
import afield.ai.core.BoxTool
import afield.ai.core.ToolEffect
import afield.ai.core.aiTool
import afield.displayOpen
import afield.exposeOutputIframeTools
import afield.outputIframe
import afield.outputRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/**
 * [ORCA](https://github.com/hundredrabbits/Orca), Hundred Rabbits' two-dimensional livecoding
 * sequencer, running in one of this box's outputs, with everything it plays coming back to Kotlin.
 *
 * ```kotlin
 * orca()
 * ```
 *
 * is a working ORCA: click into it and type. Its operators that would talk to the outside world —
 * `:` and `%` (MIDI notes), `!` (control change), `?` (pitch bend), `;` (UDP) and `=` (OSC) — arrive
 * here, one frame at a time, as [onNote], [onCC], [onBend], [onUdp] and [onOsc]. To hear the notes:
 *
 * ```kotlin
 * val synth = PatSynthBank()
 * orca().onNote { synth.play(it) }
 * synth
 * ```
 *
 * Input goes the other way: `o[x, y]` reads a cell and `o[x, y] = 'C'` sets one, [bang] fires the
 * operators beside a cell, [variable] holds an ORCA variable, [write] puts text on the grid, [grid]
 * replaces all of it, [command] runs anything ORCA's commander takes (`"bpm:140"`, `"play"`, ...),
 * and [onChange] / [onCell] report the cells ORCA changes, so a box can play a part in the grid as
 * one more operator. [eval] and [js] run JavaScript in ORCA's page, for patching ORCA itself. A [grid] passed here is
 * loaded when ORCA starts; without one, the box reopens the grid it had when last edited, which is
 * saved with the document. [exposeTools] lets AI4 read and write the grid.
 *
 * ORCA itself is upstream's code, unmodified, in `vendor/orca/`; `orca_field.js` wraps its IO
 * objects and talks to this class through [afield.outputRequests]. Callbacks run on the Runloop with
 * this box in context, so they can `display`, `print` and touch box state.
 *
 * @param cols the output's width when it opens, in ORCA cells. ORCA fits its grid to the output,
 *   and keeps doing so when the output is resized.
 * @param rows the output's height when it opens, in ORCA cells; at least ten, so ORCA's status lines fit.
 * @param guide show ORCA's operator guide when it starts (Escape hides it).
 */
fun Box.orca(
    channel: String = "orca",
    grid: String? = null,
    bpm: Int? = null,
    cols: Int = 64,
    rows: Int = 24,
    guide: Boolean = false,
): Orca {
    require(cols in 4..400 && rows in 4..200) { "cols must be 4..400 and rows 4..200" }
    val orca = Orca(this, channel, grid?.trimIndent() ?: Orca.savedGrid(this, channel), bpm, guide)
    val binding = outputRequests("orca-" + channel.replace(Regex("[^A-Za-z0-9_-]"), "_").take(70)) { orca.receive(it) }
    val width = cols * 10 + 60
    // ORCA draws its two status lines inside a canvas a fifth taller than the grid, which only has
    // room for them from ten rows up; any shorter and the output crops them.
    val height = maxOf(rows, 10) * 15 + 90
    display(channel, """<iframe src="/orca.html?binding=${binding.id}" allow="midi" """ +
        """style="border:0;display:block;background:#000;width:${width}px;height:${height}px"></iframe>""")
    displayOpen(channel)
    return orca
}

/** One `:` (or `%`, [mono]) note. [frames] is ORCA's length; [seconds] is that at the current bpm. */
data class OrcaNote(
    val channel: Int,
    val octave: Int,
    /** The note as ORCA writes it: `C` to `B`, lowercase for sharps, and its wrapped letters. */
    val note: String,
    val midi: Int,
    /** 0 to 1; ORCA's velocity digit over 16. */
    val velocity: Double,
    val frames: Int,
    val seconds: Double,
    val mono: Boolean,
    /** The ORCA frame that played it. */
    val frame: Int,
    /** The [System.nanoTime] it should sound at: when ORCA played it, plus [Orca.latency]. */
    val time: Long,
) {
    val hz: Double get() = 440.0 * 2.0.pow((midi - 69) / 12.0)
}

/** One `!` control change; [value] is already scaled to 0..127, [knob] already offset as ORCA sends it. */
data class OrcaCC(val channel: Int, val knob: Int, val value: Int, val frame: Int)

/** One `?` pitch bend, as the two 7-bit halves ORCA sends. */
data class OrcaBend(val channel: Int, val lsb: Int, val msb: Int, val frame: Int)

/** A cell that changed between two reports. */
data class OrcaCell(val x: Int, val y: Int, val before: Char, val after: Char)

/** One `=` message: `/` plus its path, and the values of the glyphs after it. */
data class OrcaOsc(val path: String, val args: List<Int>, val text: String, val frame: Int)

/**
 * An ORCA: what it is doing, what it plays, and how to change it. [Box.orca] makes one in a box's
 * output; [OrcaHeadless] makes one in the JVM with no page, for rendering and tests.
 */
class Orca internal constructor(
    /** The box whose output holds it; null for an [OrcaHeadless]. */
    private val box: Box?,
    val channel: String,
    private val initialGrid: String?,
    private val initialBpm: Int?,
    private val showGuide: Boolean,
) {
    companion object {
        /** The grid each output had when last edited, as `{channel: grid}` JSON, saved with the document. */
        private const val SAVED = "orcaGrids"

        init {
            // Registered on first use, as syncToProperty does, rather than in Box's own list, which
            // every saved document copies into its header. A document saved after this lists it,
            // and loading that document merges it back before any orca() runs.
            Box.ioPersistentProperties.add(SAVED)
        }

        internal fun savedGrid(box: Box, channel: String): String? =
            savedGrids(box)[channel]?.jsonPrimitive?.contentOrNull

        private fun savedGrids(box: Box): JsonObject =
            (box.getPropertyRaw(SAVED) as? String)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())
    }

    private val outgoing = ConcurrentLinkedQueue<JsonObject>()
    private val notes = CopyOnWriteArrayList<(OrcaNote) -> Unit>()
    private val ccs = CopyOnWriteArrayList<(OrcaCC) -> Unit>()
    private val bends = CopyOnWriteArrayList<(OrcaBend) -> Unit>()
    private val udps = CopyOnWriteArrayList<(String) -> Unit>()
    private val oscs = CopyOnWriteArrayList<(OrcaOsc) -> Unit>()
    private val frames = CopyOnWriteArrayList<(Orca) -> Unit>()
    private val changes = CopyOnWriteArrayList<(OrcaCell) -> Unit>()
    private val evals = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    /** Cells this box set that ORCA has not reported back yet, with the report that will show them. */
    private val pendingCells = ConcurrentHashMap<Pair<Int, Int>, Pair<Char, Long>>()
    private var rows: List<String> = emptyList()
    private var lastSaved: String? = null

    // More than one page can show this output: a second tab, or the browser AI4 takes pictures in.
    // The first to report is the one that plays and takes commands; the others are stopped mirrors
    // of its grid, so a picture of any of them shows the board. A silent player is replaced.
    private var player: String? = null
    private var playerSeen = 0L
    internal var playerTimeoutNanos = 2_000_000_000L
    private val mirrors = HashMap<String, String?>()
    private val waiting = CopyOnWriteArrayList<Pair<Long, CompletableDeferred<Unit>>>()
    private var offsetNanos: Long? = null
    private var lastError: String? = null

    /** How far behind ORCA a note's [OrcaNote.time] is set, in seconds: room for the trip to Field. */
    var latency: Double = 0.05

    private var reportedGrid = ""
    private var reportedBpm = initialBpm ?: 120

    /**
     * The grid as ORCA last reported it, rows separated by newlines. Setting it replaces the whole
     * grid; short rows are padded with empty cells, and common indentation is removed.
     */
    var grid: String
        get() = reportedGrid
        set(value) { send("load", "grid" to JsonPrimitive(value.trimIndent())) }

    var width: Int = 0; private set
    var height: Int = 0; private set
    var frame: Int = 0; private set
    var playing: Boolean = false; private set

    /** ORCA's tempo; setting it is the commander's `bpm:`, clamped by ORCA to 60..300. */
    var bpm: Int
        get() = reportedBpm
        set(value) { send("bpm", "value" to JsonPrimitive(value)) }

    /** This frame's `V` variables. */
    var variables: Map<String, String> = emptyMap(); private set

    /** Where ORCA's cursor is. */
    var cursor: Pair<Int, Int> = 0 to 0; private set

    /** Reports received from the page; zero until ORCA has started. */
    var reports: Long = 0; private set

    /** ORCA's own operator reference, as its page generated it. */
    var operators: String = ""; private set

    private val recentEvents = ArrayDeque<String>()

    /** The last 64 messages ORCA sent, one line each, newest last. */
    val recent: List<String> get() = recentEvents.toList()

    /** Put [text] on the grid with its top-left at ([x], [y]); newlines start a new row at [x]. */
    fun write(x: Int, y: Int, text: String) =
        send("write", "x" to JsonPrimitive(x), "y" to JsonPrimitive(y), "text" to JsonPrimitive(text))

    /**
     * The glyph at ([x], [y]), `'.'` for an empty cell or one off the grid. It is the grid ORCA last
     * reported, with any cell this box has [set] since then showing the value it was set to.
     */
    operator fun get(x: Int, y: Int): Char =
        pendingCells[x to y]?.first ?: rows.getOrNull(y)?.getOrNull(x) ?: '.'

    /**
     * Put one [glyph] at ([x], [y]) at ORCA's next frame. Unlike [write] it stays out of ORCA's undo
     * history and is not saved as an edit, so a box can set cells every frame: `o[4, 2] = 'C'`.
     */
    operator fun set(x: Int, y: Int, glyph: Char) {
        pendingCells[x to y] = glyph to reports + 2
        send("set", "x" to JsonPrimitive(x), "y" to JsonPrimitive(y), "glyph" to JsonPrimitive(glyph.toString()))
    }

    /**
     * Run [javascript] in ORCA's page, at global scope beside ORCA's `client` and `library`, and
     * suspend until its value comes back as JSON with the next report; a promise is awaited first.
     * Anything goes: read ORCA's state, restyle it, replace an operator in `library`, wrap
     * `client.run`. A JavaScript error comes back as an [IllegalStateException] carrying its stack.
     *
     * ```kotlin
     * go { println(o.eval("client.orca.w + ' x ' + client.orca.h")) }
     * ```
     */
    suspend fun eval(javascript: String, timeoutMillis: Long = 5_000): JsonElement {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<JsonElement>()
        evals[id] = result
        send("eval", "id" to JsonPrimitive(id), "js" to JsonPrimitive(javascript))
        try {
            return withTimeout(timeoutMillis) { result.await() }
        } finally {
            evals.remove(id)
        }
    }

    /** Run [javascript] in ORCA's page at its next frame, as [eval] does, without waiting for a value. */
    fun js(javascript: String) = send("eval", "js" to JsonPrimitive(javascript))

    /** Put a `*` at ([x], [y]): next frame, the operators beside it fire, and ORCA erases it. */
    fun bang(x: Int, y: Int) = set(x, y, '*')

    /**
     * Hold ORCA variable [name] at [value] every frame, as if a `V` on the grid wrote it, so `V`
     * reads it there; null lets it go. A `V` on the grid that writes the same name wins that frame.
     */
    fun variable(name: Char, value: Char?) =
        send("variable", "name" to JsonPrimitive(name.toString()), "value" to (value?.let { JsonPrimitive(it.toString()) } ?: JsonNull))

    /** Run a commander string, as if typed after `Cmd-K`: `"bpm:140"`, `"write:D8;4;2"`, `"find:aa"`, ... */
    fun command(text: String) = send("command", "text" to JsonPrimitive(text))

    fun play() = send("play")
    fun stop() = send("stop")

    fun onNote(callback: (OrcaNote) -> Unit) = apply { notes.add(callback) }
    fun onCC(callback: (OrcaCC) -> Unit) = apply { ccs.add(callback) }
    fun onBend(callback: (OrcaBend) -> Unit) = apply { bends.add(callback) }
    fun onUdp(callback: (String) -> Unit) = apply { udps.add(callback) }
    fun onOsc(callback: (OrcaOsc) -> Unit) = apply { oscs.add(callback) }
    /** After every report: once a frame while ORCA plays, a few times a second while it is paused. */
    fun onFrame(callback: (Orca) -> Unit) = apply { frames.add(callback) }

    /**
     * Every cell that changed since the last report, whoever changed it: an operator, the keyboard,
     * this box. Called row by row, before [onFrame]. A report can cover more than one frame when the
     * page is busy, so a cell that changed and changed back in between is not seen.
     */
    fun onChange(callback: (OrcaCell) -> Unit) = apply { changes.add(callback) }

    /** [onChange], for one cell: [callback] gets the glyph it changed to. */
    fun onCell(x: Int, y: Int, callback: (Char) -> Unit) = onChange { if (it.x == x && it.y == y) callback(it.after) }

    /**
     * Suspend until ORCA has applied everything sent so far and reported the result, or [timeoutMillis]
     * passes. Commands travel in the reply to one report, so their effect shows in the one after.
     */
    suspend fun sync(timeoutMillis: Long = 3_000): Boolean {
        val done = CompletableDeferred<Unit>()
        waiting.add((reports + 2) to done)
        return withTimeoutOrNull(timeoutMillis) { done.await() } != null
    }

    /** The output's iframe, for the live-iframe bridge's `evaluate`, `screenshot`, ... in the Electron app. */
    val page: LiveOutputIframe get() = owner.outputIframe(channel)

    private val owner: Box get() = box ?: throw IllegalStateException("A headless ORCA has no box output")

    /**
     * Give AI4 tools to read this ORCA, write to it, command it and run JavaScript in its page
     * (`<name>_read`, `_write`, `_load`, `_command`, `_js`), plus the generic live-iframe tools (`iframe_<name>_screenshot`, ...) when
     * [iframeTools] is true. Re-running the box replaces them; not calling this removes them.
     */
    fun exposeTools(name: String = "orca", iframeTools: Boolean = true): List<BoxTool> {
        // The tool DSL's scopes have their own `box`, `name` and `frame()`; say which ORCA is meant.
        val orca = this
        val box = owner
        val where = "output '$channel' of box '${box.name.ifBlank { box.id }}'"
        val read = box.aiTool("${name}_read") {
            namespace("orca")
            effects(ToolEffect.READ_DOCUMENT)
            describe("Read the ORCA livecoding grid in $where: rows prefixed with their y (x counts from 0 at the " +
                "first glyph), size, frame, bpm, whether it plays, the last messages it sent (notes, cc, udp, osc), " +
                "and ORCA's operator reference.")
            action {
                mapOf(
                    "grid" to orca.grid.lines().mapIndexed { y, row -> "%3d %s".format(y, row) }.joinToString("\n"),
                    "width" to orca.width, "height" to orca.height, "frame" to orca.frame, "bpm" to orca.bpm,
                    "playing" to orca.playing, "cursor" to listOf(orca.cursor.first, orca.cursor.second),
                    "recent" to orca.recent.takeLast(32), "operators" to orca.operators,
                )
            }
        }
        val write = box.aiTool("${name}_write") {
            namespace("orca")
            effects(ToolEffect.CONTROL_RUNTIME)
            describe("Write glyphs onto the ORCA grid in $where with the top-left at (x, y). Newlines in text " +
                "start the next row at the same x; '.' is an empty cell. Returns the grid afterwards.")
            val x by param<Int>("Column, from 0.")
            val y by param<Int>("Row, from 0.")
            val text by param<String>("Glyphs to write, possibly several rows.")
            action { orca.write(x(), y(), text()); orca.sync(); mapOf("grid" to orca.grid) }
        }
        val load = box.aiTool("${name}_load") {
            namespace("orca")
            effects(ToolEffect.CONTROL_RUNTIME)
            describe("Replace the whole ORCA grid in $where with these rows (all the same width, '.' for empty). " +
                "Returns the grid afterwards.")
            val rows by param<String>("The new grid, one row per line.")
            action { orca.grid = rows(); orca.sync(); mapOf("grid" to orca.grid) }
        }
        val command = box.aiTool("${name}_command") {
            namespace("orca")
            effects(ToolEffect.CONTROL_RUNTIME)
            describe("Run an ORCA commander string in $where: play, stop, run, bpm:140, frame:0, " +
                "write:TEXT;x;y, find:TEXT, select:x;y;w;h, erase, copy, paste.")
            val text by param<String>("The command.")
            action { orca.command(text()); orca.sync(); mapOf("bpm" to orca.bpm, "playing" to orca.playing, "frame" to orca.frame) }
        }
        val javascript = box.aiTool("${name}_js") {
            namespace("orca")
            effects(ToolEffect.EXECUTE_CODE, ToolEffect.CONTROL_RUNTIME)
            describe("Run JavaScript in the page of the ORCA in $where, at global scope where ORCA's `client` " +
                "(client.orca: the grid, client.clock, client.theme, client.io) and `library` (the operators, by " +
                "lowercase glyph) live, and return its JSON value. Use it to inspect or patch ORCA itself: " +
                "restyle it, redefine an operator, wrap client.run. Works in any browser, not only Electron.")
            val code by param<String>("JavaScript expression, or statements whose last value is returned.")
            action { mapOf("json" to orca.eval(code()).toString()) }
        }
        return listOf(read, write, load, command, javascript) +
            (if (iframeTools) box.exposeOutputIframeTools(channel, name) else emptyList())
    }

    private fun send(op: String, vararg fields: Pair<String, JsonElement>) {
        outgoing.add(JsonObject(mapOf("op" to JsonPrimitive(op)) + fields))
    }

    internal fun receive(data: JsonElement): JsonElement {
        val received = System.nanoTime()
        val report = data.jsonObject
        val commands = mutableListOf<JsonObject>()
        val page = report["page"]?.jsonPrimitive?.contentOrNull ?: ""
        if (player != null && page != player && received - playerSeen < playerTimeoutNanos) return mirror(page)
        if (player != null && page != player) {
            // Taking over from a player that went quiet: this page may have been a stopped mirror.
            mirrors.remove(page)
            commands += op("load", "grid" to JsonPrimitive(reportedGrid))
            if (playing) commands += op("play")
        }
        player = page
        playerSeen = received
        if (report.bool("hello")) {
            initialGrid?.let { commands += op("load", "grid" to JsonPrimitive(it)) }
            initialBpm?.let { commands += op("bpm", "value" to JsonPrimitive(it)) }
            commands += op("guide", "on" to JsonPrimitive(showGuide))
            report["operators"]?.jsonPrimitive?.contentOrNull?.let { operators = it }
        }

        frame = report.int("frame") ?: frame
        reportedBpm = report.int("bpm") ?: reportedBpm
        playing = report["paused"]?.jsonPrimitive?.booleanOrNull?.not() ?: playing
        val before = rows
        report["grid"]?.jsonPrimitive?.contentOrNull?.let { reportedGrid = it; rows = it.lines() }
        width = report.int("w") ?: width
        height = report.int("h") ?: height
        report["cursor"]?.jsonObject?.let { cursor = (it.int("x") ?: 0) to (it.int("y") ?: 0) }
        variables = report["variables"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        if (report.bool("edited")) save()
        for (element in report["results"]?.jsonArray.orEmpty()) {
            val result = element.jsonObject
            val waiting = evals.remove(result["id"]?.jsonPrimitive?.contentOrNull ?: continue) ?: continue
            val error = result["error"]?.jsonPrimitive?.contentOrNull
            if (error != null) waiting.completeExceptionally(IllegalStateException("JavaScript in ORCA's page failed: $error"))
            else waiting.complete(result["value"] ?: JsonNull)
        }

        // The page's clock and this JVM's differ by a constant plus the trip; the quickest trip seen
        // (allowed to creep up 0.1 ms a report, for drift) puts each event back where ORCA played it.
        val sentMillis = report["t"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val observed = received - (sentMillis * 1e6).toLong()
        offsetNanos = offsetNanos?.let { minOf(it + 100_000, observed) } ?: observed
        val frameSeconds = 60.0 / bpm.coerceAtLeast(1) / 4.0

        for (element in report["events"]?.jsonArray.orEmpty()) {
            val e = element.jsonObject
            val at = ((e["at"]?.jsonPrimitive?.doubleOrNull ?: sentMillis) * 1e6).toLong() + offsetNanos!!
            val f = e.int("frame") ?: frame
            when (val kind = e["kind"]?.jsonPrimitive?.contentOrNull) {
                "note", "mono" -> {
                    val length = e.int("length") ?: 1
                    val note = OrcaNote(
                        channel = e.int("channel") ?: 0, octave = e.int("octave") ?: 3,
                        note = e["note"]?.jsonPrimitive?.contentOrNull ?: "", midi = e.int("midi") ?: -1,
                        velocity = ((e.int("velocity") ?: 15) / 16.0).coerceIn(0.0, 1.0),
                        frames = length, seconds = length * frameSeconds, mono = kind == "mono", frame = f,
                        time = at + (latency * 1e9).toLong(),
                    )
                    remember("$f ${if (note.mono) "%" else ":"} ch${note.channel} ${note.note}${note.octave} midi ${note.midi} vel ${e.int("velocity")} len $length")
                    if (note.midi >= 0) notes.forEach { call { it(note) } }
                }
                "cc" -> OrcaCC(e.int("channel") ?: 0, e.int("knob") ?: 0, e.int("value") ?: 0, f).let { cc ->
                    remember("$f ! ch${cc.channel} knob ${cc.knob} value ${cc.value}")
                    ccs.forEach { call { it(cc) } }
                }
                "pb" -> OrcaBend(e.int("channel") ?: 0, e.int("lsb") ?: 0, e.int("msb") ?: 0, f).let { pb ->
                    remember("$f ? ch${pb.channel} lsb ${pb.lsb} msb ${pb.msb}")
                    bends.forEach { call { it(pb) } }
                }
                "udp" -> (e["message"]?.jsonPrimitive?.contentOrNull ?: "").let { message ->
                    remember("$f ; $message")
                    udps.forEach { call { it(message) } }
                }
                "osc" -> OrcaOsc(
                    e["path"]?.jsonPrimitive?.contentOrNull ?: "/",
                    e["args"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: 0 }.orEmpty(),
                    e["message"]?.jsonPrimitive?.contentOrNull ?: "", f,
                ).let { osc ->
                    remember("$f = ${osc.path} ${osc.args}")
                    oscs.forEach { call { it(osc) } }
                }
            }
        }
        reports++
        pendingCells.entries.removeIf { it.value.second <= reports }
        if (rows !== before && changes.isNotEmpty() && before.isNotEmpty()) {
            for (y in 0 until minOf(before.size, rows.size)) {
                val old = before[y]; val new = rows[y]
                for (x in 0 until minOf(old.length, new.length)) {
                    if (old[x] != new[x]) OrcaCell(x, y, old[x], new[x]).let { cell -> changes.forEach { call { it(cell) } } }
                }
            }
        }
        frames.forEach { call { it(this) } }
        waiting.filter { it.first <= reports }.forEach { waiting.remove(it); it.second.complete(Unit) }
        // Drained last, so what the callbacks above asked for goes out in this reply.
        while (true) commands += outgoing.poll() ?: break
        return buildJsonObject { put("commands", JsonArray(commands)) }
    }

    /** A page that is not the player: keep it stopped, showing the player's grid. */
    private fun mirror(page: String): JsonElement {
        val commands = mutableListOf<JsonObject>()
        if (page !in mirrors) {
            commands += op("guide", "on" to JsonPrimitive(false))
            commands += op("stop")
            mirrors[page] = null
        }
        if (mirrors[page] != reportedGrid) {
            commands += op("load", "grid" to JsonPrimitive(reportedGrid))
            mirrors[page] = reportedGrid
        }
        return buildJsonObject { put("commands", JsonArray(commands)) }
    }

    private fun save() {
        val box = box ?: return
        if (grid == lastSaved) return
        lastSaved = grid
        val all = savedGrids(box).toMutableMap()
        all[channel] = JsonPrimitive(grid)
        box.setPropertyRaw(SAVED, JsonObject(all).toString())
    }

    private fun remember(line: String) {
        recentEvents.addLast(line)
        while (recentEvents.size > 64) recentEvents.removeFirst()
    }

    private inline fun call(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            // With no box to paint the error on, a headless render fails rather than carry on wrong.
            val box = box ?: throw e
            val message = "orca: ${e.message ?: e.javaClass.simpleName}"
            if (message != lastError) { lastError = message; box.error(message) }
        }
    }

    private fun op(op: String, vararg fields: Pair<String, JsonElement>) =
        JsonObject(mapOf("op" to JsonPrimitive(op)) + fields)

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
}

/**
 * Play [note] on this bank at [OrcaNote.time]: held for ORCA's length, at its velocity.
 * The bank has to be playing — returned from the box — for the note to be heard.
 */
fun PatSynthBank.play(note: OrcaNote, pan: Double = 0.5, wave: PatWave = voice.wave, gain: Double = 1.0,
                      cutoffHz: Double? = null) {
    val delay = ((note.time - System.nanoTime()) * sampleRate / 1e9).toLong().coerceAtLeast(0)
    schedule(orcaSynthNote(note, frame + delay, pan, wave, gain, cutoffHz))
}

/** [note] as a [PatSynthNote] on this bank, starting at [startFrame] on its clock. */
internal fun PatSynthBank.orcaSynthNote(note: OrcaNote, startFrame: Long, pan: Double, wave: PatWave,
                                        gain: Double, cutoffHz: Double?) = PatSynthNote(
    startFrame = startFrame,
    holdFrames = (note.seconds * sampleRate).toInt(),
    tailFrames = (voice.decay * 4.0 * sampleRate).toInt().coerceAtLeast(1),
    hz = note.hz,
    wave = wave,
    gain = note.velocity * gain,
    pan = pan.coerceIn(0.0, 1.0),
    cutoffHz = cutoffHz,
    seed = note.midi.toLong(),
)

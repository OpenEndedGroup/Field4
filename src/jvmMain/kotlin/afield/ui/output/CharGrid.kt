@file:afield.runtime.completion.CompletionCategory("Visualization")

package afield.ui.output

import afield.audio.synth.sound as doughSound
import afield.document.graph.Box
import afield.runtime.lifecycle.AnimationClock
import afield.runtime.lifecycle.Frames
import afield.runtime.lifecycle.frames
import afield.runtime.lifecycle.loop
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A grid of glyphs that box code writes into, cell by cell, shown in an output panel: generative
 * text art in the spirit of 10 PRINT, ported from the madp charGrid demo.
 *
 * The program writes **glyph indices** with `SET`, and a charset string (`glyphs`) turns each index
 * into a character; a cell can also carry a CSS colour and raw CSS such as `transform:rotate(45deg)`.
 * [charGrid] runs its block once per tick with the previous tick readable through `PREV`; [charStream]
 * runs one long suspending program in which every `SET` waits for the next tick. A glyph can be a
 * dough sound ([Box.sound]), so every `SET` of it plays. The panel pulls only the cells that changed
 * since it last asked ([CharGridBuffer.update]), so a 200 × 100 grid at 24 fps sends a frame's
 * changes, not the whole grid.
 */

/** Where the `SET(g)` cursor is: the column and row the next cursor `SET` writes. */
data class CharGridCursor(val x: Int, val y: Int)

/**
 * The cells of a char grid and the encoder that sends their changes to the panel.
 *
 * Each cell holds a raw glyph index (what `GET` returns; the panel shows it modulo the charset), an
 * optional CSS colour, optional per-cell CSS, and whether it has been set since the last [clear]
 * (an unset cell is blank). [snapshot] copies glyphs and colours into the `PREV` buffer. Colours and
 * styles are interned into string tables, so the wire carries small integers. Not thread-safe: the
 * box's program and the panel's requests both run on Field's main loop.
 */
class CharGridBuffer(cols: Int, rows: Int, glyphs: String) {
    /** Columns, clamped to 1..[MAX_COLS]. */
    val cols: Int = cols.coerceIn(1, MAX_COLS)

    /** Rows, clamped to 1..[MAX_ROWS]. */
    val rows: Int = rows.coerceIn(1, MAX_ROWS)

    /** The charset, as given (a space if empty). */
    val glyphs: String = glyphs.ifEmpty { " " }

    /** The charset split into characters (code points), the panel's lookup table. */
    val glyphList: List<String> = this.glyphs.codePoints().toArray().map { String(Character.toChars(it)) }

    private val n = this.cols * this.rows
    private val glyph = IntArray(n)
    private val shown = BooleanArray(n)
    private val color = IntArray(n)
    private val style = IntArray(n)
    private val prevGlyph = IntArray(n)
    private val prevColor = IntArray(n)
    private val changed = IntArray(n)

    private val colors = arrayListOf("")
    private val colorIds = hashMapOf("" to 0)
    private val styles = arrayListOf("")
    private val styleIds = hashMapOf("" to 0)
    private val cssVars = LinkedHashMap<String, String>()
    private var cssChanged = 0

    private var version = 1
    private var dirty = false

    /** Bumped when the string tables are rebuilt; a panel on another epoch gets a full frame. */
    var epoch: Int = 0
        private set

    /** The cursor column. */
    var cursorX: Int = 0

    /** The cursor row. */
    var cursorY: Int = 0

    /** The tick number shown in the panel's status line. */
    var frame: Int = 0

    private fun index(x: Int, y: Int) = if (x < 0 || x >= cols || y < 0 || y >= rows) -1 else y * cols + x

    private fun intern(s: String, table: ArrayList<String>, ids: HashMap<String, Int>) =
        ids.getOrPut(s) { table.add(s); table.size - 1 }

    private fun write(i: Int, g: Int, isShown: Boolean, c: Int, s: Int) {
        if (glyph[i] == g && shown[i] == isShown && color[i] == c && style[i] == s) return
        glyph[i] = g; shown[i] = isShown; color[i] = c; style[i] = s
        changed[i] = version
        dirty = true
    }

    /**
     * Write glyph [g] at ([x], [y]); [color] `null` keeps the cell's colour, [style] `null` clears its
     * CSS (the demo's rules). Returns false, writing nothing, outside the grid. Moves the cursor there.
     */
    fun set(x: Int, y: Int, g: Int, color: String? = null, style: String? = null): Boolean {
        val i = index(x, y)
        if (i < 0) return false
        val c = if (color == null) this.color[i] else intern(color, colors, colorIds)
        write(i, g, true, c, if (style == null) 0 else intern(style, styles, styleIds))
        cursorX = x; cursorY = y
        return true
    }

    /** Write glyph [g] at the cursor and advance it, wrapping at the right and scrolling at the bottom. Returns where it wrote. */
    fun setAtCursor(g: Int, color: String? = null, style: String? = null): CharGridCursor {
        cursorX = cursorX.coerceIn(0, cols - 1); cursorY = cursorY.coerceIn(0, rows - 1)
        val at = CharGridCursor(cursorX, cursorY)
        val i = cursorY * cols + cursorX
        val c = if (color == null) this.color[i] else intern(color, colors, colorIds)
        write(i, g, true, c, if (style == null) 0 else intern(style, styles, styleIds))
        cursorX++
        if (cursorX >= cols) newLine()
        return at
    }

    /** Move the cursor to the start of the next line, scrolling the grid up a row at the bottom. */
    fun newLine() {
        cursorX = 0
        cursorY++
        if (cursorY >= rows) {
            scrollUp()
            cursorY = rows - 1
        }
    }

    /** Move every row up one; the bottom row comes in blank. */
    fun scrollUp() {
        for (i in 0 until n - cols) write(i, glyph[i + cols], shown[i + cols], color[i + cols], style[i + cols])
        for (i in n - cols until n) write(i, 0, false, 0, 0)
    }

    /** The raw glyph index at ([x], [y]), 0 outside the grid or in a blank cell. */
    fun get(x: Int, y: Int): Int = index(x, y).let { if (it < 0) 0 else glyph[it] }

    /** The glyph index at ([x], [y]) as of the last [snapshot]. */
    fun prev(x: Int, y: Int): Int = index(x, y).let { if (it < 0) 0 else prevGlyph[it] }

    /** The colour at ([x], [y]) as of the last [snapshot], `""` if none. */
    fun prevColor(x: Int, y: Int): String = index(x, y).let { if (it < 0) "" else colors[prevColor[it]] }

    /** The colour at ([x], [y]) now, `""` if none. */
    fun colorAt(x: Int, y: Int): String = index(x, y).let { if (it < 0) "" else colors[color[it]] }

    /** The per-cell CSS at ([x], [y]) now, `""` if none. */
    fun styleAt(x: Int, y: Int): String = index(x, y).let { if (it < 0) "" else styles[style[it]] }

    /** Whether ([x], [y]) has been set since the last [clear] (and not scrolled out). */
    fun isShown(x: Int, y: Int): Boolean = index(x, y).let { it >= 0 && shown[it] }

    /** Copy the glyphs and colours into the `PREV` buffer. */
    fun snapshot() {
        glyph.copyInto(prevGlyph)
        color.copyInto(prevColor)
    }

    /** Blank every cell, zero `PREV`, drop the CSS variables and home the cursor. */
    fun clear() {
        for (i in 0 until n) write(i, 0, false, 0, 0)
        prevGlyph.fill(0); prevColor.fill(0)
        if (cssVars.isNotEmpty()) { cssVars.clear(); cssChanged = version; dirty = true }
        cursorX = 0; cursorY = 0
    }

    /** Set a CSS custom property on every cell, `--spin` to `"30deg"` say; `null` removes it. */
    fun cssVar(name: String, value: String?) {
        val old = cssVars[name]
        if (old == value) return
        if (value == null) cssVars.remove(name) else cssVars[name] = value
        cssChanged = version; dirty = true
    }

    /** The index of [ch] in the charset, 0 if it is not there. */
    fun glyphIndex(ch: String): Int = glyphList.indexOf(ch).coerceAtLeast(0)

    /** The colour and style strings interned so far, `""` included. */
    val tableSize: Int get() = colors.size + styles.size

    /**
     * Rebuild the colour and style tables from the cells in use when they hold more than [limit]
     * strings, so a program that makes a new colour every frame does not grow them without end. The
     * panel then gets one full frame. Returns whether it rebuilt.
     */
    fun compact(limit: Int = TABLE_LIMIT): Boolean {
        if (tableSize <= limit) return false
        fun rebuild(ids: Array<IntArray>, table: ArrayList<String>, map: HashMap<String, Int>) {
            val old = ArrayList(table)
            table.clear(); map.clear(); table.add(""); map[""] = 0
            for (cells in ids) for (i in cells.indices) cells[i] = intern(old[cells[i]], table, map)
        }
        rebuild(arrayOf(color, prevColor), colors, colorIds)
        rebuild(arrayOf(style), styles, styleIds)
        epoch++
        return true
    }

    /**
     * The panel's poll: what changed since the panel's [knownVersion] of [knownEpoch], given that it
     * already holds [knownColors] colour and [knownStyles] style strings. Returns a JSON object:
     * `e` epoch, `v` version, `full`, `cols`, `rows`, `frame`, `glyphs` (on a full frame), `c0`/`c`
     * and `s0`/`s` (new table strings from those indices), `css` (the variables, when they changed),
     * and `d`, the changed cells as flat `[index, glyph, colour id, style id, …]` with glyph `-1` for
     * a blank cell and the glyph already taken modulo the charset.
     */
    fun update(knownEpoch: Int, knownVersion: Int, knownColors: Int, knownStyles: Int): String {
        if (dirty) { version++; dirty = false }
        val current = version - 1
        val full = knownEpoch != epoch || knownVersion > current || knownVersion < 0
        val since = if (full) -1 else knownVersion
        val c0 = if (full) 0 else knownColors.coerceIn(0, colors.size)
        val s0 = if (full) 0 else knownStyles.coerceIn(0, styles.size)
        val out = StringBuilder(64 + 16 * min(n, 4096))
        out.append("{\"e\":").append(epoch).append(",\"v\":").append(current)
            .append(",\"full\":").append(full)
            .append(",\"cols\":").append(cols).append(",\"rows\":").append(rows)
            .append(",\"frame\":").append(frame)
        if (full) {
            out.append(",\"glyphs\":[")
            glyphList.forEachIndexed { k, g -> if (k > 0) out.append(','); out.append(JsonPrimitive(g).toString()) }
            out.append(']')
        }
        out.append(",\"c0\":").append(c0).append(",\"c\":[")
        for (k in c0 until colors.size) { if (k > c0) out.append(','); out.append(JsonPrimitive(colors[k]).toString()) }
        out.append("],\"s0\":").append(s0).append(",\"s\":[")
        for (k in s0 until styles.size) { if (k > s0) out.append(','); out.append(JsonPrimitive(styles[k]).toString()) }
        out.append(']')
        if (full || cssChanged > since) {
            out.append(",\"css\":").append(JsonPrimitive(cssVars.entries.joinToString("") { "${it.key}:${it.value};" }).toString())
        }
        out.append(",\"d\":[")
        var first = true
        val count = glyphList.size
        for (i in 0 until n) {
            if (changed[i] <= since) continue
            if (!first) out.append(',')
            first = false
            out.append(i).append(',').append(if (shown[i]) Math.floorMod(glyph[i], count) else -1)
                .append(',').append(color[i]).append(',').append(style[i])
        }
        out.append("]}")
        return out.toString()
    }

    companion object {
        /** The widest grid, as in the demo. */
        const val MAX_COLS = 200

        /** The tallest grid, as in the demo. */
        const val MAX_ROWS = 100

        /** How many colour and style strings a grid keeps before [compact] rebuilds its tables. */
        const val TABLE_LIMIT = 4096
    }
}

/** What a glyph's sound lambda sees: the glyph index [N] being set, where, and on which tick. */
class GlyphSound(
    /** The glyph index being set (taken modulo the charset). */
    val N: Int,
    /** The cell's column. */
    val x: Int,
    /** The cell's row. */
    val y: Int,
    /** The tick. */
    val frame: Int,
)

/**
 * Turns `SET`s into dough events: a per-glyph path table and/or a path lambda, and, in frame mode, the
 * demo's auto-gain over one tick's SETs.
 *
 * In stream mode every SET plays at once ([onSet] with `immediate`). In frame mode a tick can set a
 * thousand cells; the demo starts a voice for each at gain `1 / (enabled · √cells)`. This collects the
 * tick by resolved path and [flush] plays each distinct path once at that gain times `√count`, the
 * level of `count` uncorrelated voices, at most [maxVoices] paths a tick (a random subset beyond that,
 * raised to keep the level). A path with its own `/gain/` plays once, as written.
 */
class CharGridSounds(
    private val sounds: List<String>,
    private val sound: (GlyphSound.() -> String?)?,
    private val cells: Int,
    /** The most distinct paths one frame-mode tick plays. */
    val maxVoices: Int = 24,
    private val play: (path: String, key: String) -> Unit,
) {
    private val pending = LinkedHashMap<String, Int>()
    private val enabled = HashSet<Int>()

    /** Whether any glyph can sound at all. */
    val hasSounds: Boolean = sound != null || sounds.any { it.isNotBlank() }

    /** The path glyph [N] at ([x], [y]) on tick [frame] plays, or `null`. */
    fun resolve(N: Int, x: Int, y: Int, frame: Int): String? {
        val path = if (sound != null) GlyphSound(N, x, y, frame).sound() else sounds.getOrNull(N)
        return path?.takeIf { it.isNotBlank() }
    }

    /** A SET of glyph [N]: played now if [immediate], else counted towards this tick's [flush]. */
    fun onSet(N: Int, x: Int, y: Int, frame: Int, immediate: Boolean) {
        if (!hasSounds) return
        val path = resolve(N, x, y, frame) ?: return
        enabled += N
        if (immediate) play(path, "chargrid:$N")
        else pending[path] = (pending[path] ?: 0) + 1
    }

    /** The demo's frame-mode gain for one voice: `1 / (enabled glyphs · √cells)`. */
    fun voiceGain(): Double = 1.0 / (max(1, enabled.size) * sqrt(cells.toDouble()))

    /** Play this tick's collected paths and forget them. */
    fun flush() {
        if (pending.isEmpty()) return
        val groups = pending.entries.map { it.key to it.value }
        pending.clear()
        val chosen = if (groups.size <= maxVoices) groups else groups.shuffled().take(maxVoices)
        val total = groups.sumOf { it.second }.toDouble()
        val raise = sqrt(total / chosen.sumOf { it.second })
        val g0 = voiceGain()
        for ((path, count) in chosen) {
            if (path.contains("/gain/")) play(path, "chargrid:$path")
            else play(path + "/gain/" + "%.4f".format(java.util.Locale.ROOT, g0 * sqrt(count.toDouble()) * raise), "chargrid:$path")
        }
    }
}

/** Ticks at [fps] by [AnimationClock]; falls back into step rather than bursting after a stall. */
internal class CharGridClock(fps: Double) {
    private val period = 1.0 / fps.coerceIn(0.1, 240.0)
    private var next = Double.NaN

    fun due(): Boolean {
        val now = AnimationClock.nowSeconds()
        if (next.isNaN() || now - next > 0.25) next = now
        if (now < next) return false
        next += period
        return true
    }
}

/**
 * What both char grid programs see: the size, the charset, the tick, and every verb except `SET`
 * (which [CharGridFrame] and [CharGridStream] give their own timing).
 */
abstract class CharGridScope internal constructor(
    /** The grid's cells; what the panel shows. */
    val grid: CharGridBuffer,
    internal val sounds: CharGridSounds,
) {
    /** Columns. */
    val COLS: Int get() = grid.cols

    /** Rows. */
    val ROWS: Int get() = grid.rows

    /** The charset: glyph index `i` shows as `glyphs[i % glyphs.length]`. */
    val glyphs: String get() = grid.glyphs

    /** The tick, from 0. */
    var frame: Int = 0
        internal set

    internal abstract val streaming: Boolean

    internal fun write(x: Int, y: Int, g: Int, color: String?, style: String?) {
        if (grid.set(x, y, g, color, style)) sounds.onSet(Math.floorMod(g, grid.glyphList.size), x, y, frame, streaming)
    }

    internal fun writeAtCursor(g: Int, color: String?, style: String?) {
        val at = grid.setAtCursor(g, color, style)
        sounds.onSet(Math.floorMod(g, grid.glyphList.size), at.x, at.y, frame, streaming)
    }

    /** The glyph index at ([x], [y]) now; 0 outside the grid. */
    fun GET(x: Int, y: Int): Int = grid.get(x, y)

    /** The glyph index at ([x], [y]) on the previous tick; 0 outside the grid. */
    fun PREV(x: Int, y: Int): Int = grid.prev(x, y)

    /** The colour at ([x], [y]) on the previous tick; `""` if none. */
    fun PREVC(x: Int, y: Int): String = grid.prevColor(x, y)

    /** The index of [ch] in [glyphs], 0 if absent. */
    fun glyphIndex(ch: Char): Int = grid.glyphIndex(ch.toString())

    /** The index of [ch] (one character) in [glyphs], 0 if absent. */
    fun glyphIndex(ch: String): Int = grid.glyphIndex(ch)

    /** Write [str] from ([x], [y]) rightwards as glyphs of [glyphs], stopping at the right edge. Does not wait. */
    fun setText(x: Int, y: Int, str: String, color: String? = null) {
        var gx = x
        str.codePoints().forEach { cp ->
            if (gx < COLS) write(gx, y, grid.glyphIndex(String(Character.toChars(cp))), color, null)
            gx++
        }
    }

    /** Write [str] at the cursor, `\n` starting a new line. Does not wait, in stream mode either. */
    fun print(str: String, color: String? = null) {
        str.codePoints().forEach { cp ->
            if (cp == '\n'.code) grid.newLine()
            else writeAtCursor(grid.glyphIndex(String(Character.toChars(cp))), color, null)
        }
    }

    /** Move the cursor to the start of the next line, scrolling at the bottom. */
    fun newLine() = grid.newLine()

    /** Blank the grid, zero `PREV`, drop CSS variables and home the cursor. */
    fun clearGrid() = grid.clear()

    /** Move the cursor. */
    fun CURSOR(x: Int, y: Int) { grid.cursorX = x; grid.cursorY = y }

    /** Where the cursor is. */
    fun getCursor(): CharGridCursor = CharGridCursor(grid.cursorX, grid.cursorY)

    /** Set CSS custom property [name] (`"--spin"`) on every cell, for use in per-cell styles; `null` removes it. */
    fun CSSVAR(name: String, value: Any?) = grid.cssVar(name, value?.toString())

    /** A random number in 0 until 1 (the demo's `Math.random()`). */
    fun rand(): Double = Math.random()

    /** A random integer in 0 until [n]. */
    fun rand(n: Int): Int = (Math.random() * n).toInt()
}

/** The receiver of [charGrid]'s block, which runs once per tick. */
class CharGridFrame internal constructor(grid: CharGridBuffer, sounds: CharGridSounds) : CharGridScope(grid, sounds) {
    override val streaming get() = false

    /** Write glyph [g] at the cursor and advance it; [color] `null` keeps the cell's colour, [style] `null` clears its CSS. */
    fun SET(g: Int, color: String? = null, style: String? = null) = writeAtCursor(g, color, style)

    /** Write glyph [g] at ([x], [y]); nothing outside the grid. [color] `null` keeps the cell's colour, [style] `null` clears its CSS. */
    fun SET(x: Int, y: Int, g: Int, color: String? = null, style: String? = null) = write(x, y, g, color, style)
}

/** The receiver of [charStream]'s program: every [SET] waits for the next tick, as does [waitFrame]. */
class CharGridStream internal constructor(
    grid: CharGridBuffer,
    sounds: CharGridSounds,
    private val clock: CharGridClock,
    private val frames: Frames,
) : CharGridScope(grid, sounds) {
    override val streaming get() = true

    /** Wait for the next tick and return its number. `PREV` then holds the grid as it was at that tick. */
    suspend fun waitFrame(): Int {
        while (!clock.due()) frames.frame()
        frame++
        grid.frame = frame
        grid.compact()
        grid.snapshot()
        return frame
    }

    /** Write glyph [g] at the cursor, advance it, and wait for the next tick. */
    suspend fun SET(g: Int, color: String? = null, style: String? = null) {
        writeAtCursor(g, color, style)
        waitFrame()
    }

    /** Write glyph [g] at ([x], [y]) and wait for the next tick. */
    suspend fun SET(x: Int, y: Int, g: Int, color: String? = null, style: String? = null) {
        write(x, y, g, color, style)
        waitFrame()
    }
}

/** The charset the demo opens with. */
const val CHARGRID_GLYPHS = "░▒▓█╔╗╚╝║═"

/** Printable ASCII, space to tilde: the charset for `print` and `setText` programs, so `glyphIndex(ch)` finds any key. */
val CHARGRID_ASCII: String = (32..126).map { it.toChar() }.joinToString("")

/** Courier New's advance and the demo's 1.2em rows, at 16 px: the panel's natural cell size. */
private const val CELL_W = 9.6
private const val CELL_H = 19.2

/** Show [grid] in an output panel on [channel], pulling its changes. */
private fun Box.showCharGrid(grid: CharGridBuffer, channel: String, stretch: Boolean, width: Int?) {
    val binding = outputRequests("chargrid-$channel") { request ->
        val o = request as? JsonObject
        fun int(k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull ?: -1
        JsonPrimitive(grid.update(int("e"), int("v"), int("nc"), int("ns")))
    }
    val sx = if (stretch) 1.7 else 1.0
    val naturalW = (grid.cols * CELL_W + 10) * sx + 32
    val naturalH = grid.rows * CELL_H + 10 + 40
    val w = width ?: min(ceil(naturalW).toInt(), 520)
    val h = ceil(naturalH * w / naturalW).toInt()
    display(
        channel,
        """<iframe src="/chargrid_view.html?binding=${binding.id}&stretch=${if (stretch) 1 else 0}" """ +
            """style="border:0;display:block;background:#0d0d0d;width:${w}px;height:${h}px"></iframe>""",
    )
    displayOpen(channel)
}

private fun Box.charGridSounds(grid: CharGridBuffer, sounds: List<String>, sound: (GlyphSound.() -> String?)?, fps: Double) =
    CharGridSounds(sounds, sound, grid.cols * grid.rows) { path, key -> doughSound(path, key = key, minInterval = 0.5 / fps) }

private fun Box.reportOnce(last: String?, t: Throwable, what: String): String {
    val message = t.message ?: t.toString()
    if (message != last) this.error("$what threw: $message")
    return message
}

/**
 * A char grid whose [body] runs once per tick, the demo's **frame** mode.
 *
 * ```
 * charGrid(glyphs = "░▒▓█") {
 *     for (y in 0 until ROWS) for (x in 0 until COLS) {
 *         val v = sin(x * 0.3 + frame * 0.05) + sin(y * 0.3 + frame * 0.035)
 *         SET(x, y, ((v + 2) / 4 * glyphs.length).toInt(), "hsl(${(x + y) * 4 % 360},60%,45%)")
 *     }
 * }
 * ```
 *
 * Make it the box's last expression: it returns the animation, so stopping the box stops the grid,
 * and running it again starts afresh. Inside [body]: `SET(x, y, g, color, style)` (or `SET(g, …)` at
 * the cursor), `GET`, `PREV` and `PREVC` (the grid as the previous tick left it), `setText`, `print`,
 * `clearGrid`, `CURSOR`, `CSSVAR`, `frame`, `COLS`, `ROWS`, `glyphs`. State that must last between
 * ticks goes in a `val` or `var` above the call. The grid is [cols] by [rows] (at most 200 × 100) and
 * ticks [fps] times a second by [AnimationClock]. [stretch] widens it 1.7× as charGrid does (`false`
 * is charGrid 2). [sounds] gives glyph `i` the dough path `sounds[i]` (`""` for none), or [sound]
 * computes one from `N`, `x`, `y`, `frame`; a tick's SETs play with the demo's auto-gain (see
 * [CharGridSounds]). The panel is [width] pixels wide (by default the grid's own width, at most
 * 520) and the grid scales to fit it; [channel] names the output panel. An exception is shown on the
 * box once per message, and the grid keeps ticking.
 */
fun Box.charGrid(
    cols: Int = 40,
    rows: Int = 25,
    glyphs: String = CHARGRID_GLYPHS,
    fps: Double = 24.0,
    stretch: Boolean = true,
    sounds: List<String> = emptyList(),
    sound: (GlyphSound.() -> String?)? = null,
    channel: String = "chargrid",
    width: Int? = null,
    body: CharGridFrame.() -> Unit,
): Frames {
    val grid = CharGridBuffer(cols, rows, glyphs)
    val scope = CharGridFrame(grid, charGridSounds(grid, sounds, sound, fps))
    val clock = CharGridClock(fps)
    showCharGrid(grid, channel, stretch, width)
    var lastError: String? = null
    var tick = 0
    return loop {
        if (!clock.due()) return@loop
        scope.frame = tick
        grid.frame = tick
        grid.compact()
        grid.snapshot()
        try {
            scope.body()
            lastError = null
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            lastError = reportOnce(lastError, t, "charGrid { }")
        }
        scope.sounds.flush()
        tick++
    }
}

/**
 * A char grid run by one long [program], the demo's **stream** mode: every `SET` writes and then waits
 * for the next tick, and `waitFrame()` waits a tick, so `while (true) SET(…)` draws one cell a tick.
 *
 * ```
 * charStream(glyphs = "╱╲") {
 *     clearGrid()
 *     while (true) SET(rand(2), "#50fa7b")
 * }
 * ```
 *
 * `setText` and `print` write at once without waiting, as in the demo. The arguments are
 * [charGrid]'s; sounds play as each SET happens, as written (no auto-gain). When [program] returns the
 * grid stays as it was left.
 */
fun Box.charStream(
    cols: Int = 40,
    rows: Int = 25,
    glyphs: String = CHARGRID_GLYPHS,
    fps: Double = 24.0,
    stretch: Boolean = true,
    sounds: List<String> = emptyList(),
    sound: (GlyphSound.() -> String?)? = null,
    channel: String = "chargrid",
    width: Int? = null,
    program: suspend CharGridStream.() -> Unit,
): Frames {
    val grid = CharGridBuffer(cols, rows, glyphs)
    val gridSounds = charGridSounds(grid, sounds, sound, fps)
    val clock = CharGridClock(fps)
    showCharGrid(grid, channel, stretch, width)
    return frames {
        val scope = CharGridStream(grid, gridSounds, clock, this)
        clock.due()
        try {
            scope.program()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            reportOnce(null, t, "charStream { }")
        }
    }
}

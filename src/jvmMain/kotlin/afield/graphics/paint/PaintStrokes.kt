@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

import afield.geometry.path.FLine
import afield.math.linalg.Vec4
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Paint strokes that stay data: [PaintStrokes] keeps every stroke as a [StrokeRecord] with an
 * address (`s12`), edits them by address with JSON ops, undoes a batch at a time, and paints the
 * record list onto a fresh [PaintCanvas] whenever it is asked for a picture.
 *
 * The method — strokes as addressable data, batches of JSON ops, a shared undo history, notes
 * pinned on the picture for the agent to read — follows [Klad](https://github.com/klad-app/klad).
 * No Klad code is used; the paint is Field's own simulation. Tests: `PaintStrokesTest`.
 */

/**
 * One stroke of a [PaintStrokes], as data.
 *
 * @property id the number in the stroke's address, `s<id>`; it never changes and is never reused.
 * @property points the path, `[x0, y0, x1, y1, ...]` in canvas units (y down). One point is a dab.
 * @property pressure pressure at the start and the end of the movement, 0..1.
 * @property brush a [BrushKind] name.
 * @property width brush width in canvas units.
 * @property color `#rrggbb`, sRGB.
 * @property hiding how much one coat hides what is under it ([PaintBrush.load]).
 * @property group a name several strokes share, addressed as `@name`.
 * @property hidden a hidden stroke stays in the list but is not painted.
 */
@Serializable
data class StrokeRecord(
    val id: Int,
    val points: List<Double>,
    val pressure: List<Double> = listOf(0.8, 0.8),
    val brush: String = "Round",
    val width: Double = 20.0,
    val color: String = "#000000",
    val hiding: Double = 0.92,
    val group: String? = null,
    val hidden: Boolean = false,
) {
    /** `s<id>`. */
    val address: String get() = "s$id"

    /** The stroke's path as an [FLine]: a dab is a single point. */
    fun line(): FLine {
        val f = FLine().moveTo(points[0], points[1])
        for (i in 2 until points.size - 1 step 2) f.lineTo(points[i], points[i + 1])
        return f
    }

    /** Distance from ([x], [y]) to the nearest point of the path, canvas units. */
    fun distanceTo(x: Double, y: Double): Double {
        if (points.size < 4) return hypot(points[0] - x, points[1] - y)
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 3 step 2) {
            val ax = points[i]; val ay = points[i + 1]; val bx = points[i + 2]; val by = points[i + 3]
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(ax + t * dx - x, ay + t * dy - y))
        }
        return best
    }

    /** `[minX, minY, maxX, maxY]` of the path, grown by half the brush width. */
    fun bounds(): List<Double> {
        val xs = points.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = points.filterIndexed { i, _ -> i % 2 == 1 }
        val r = width / 2
        return listOf(xs.min() - r, ys.min() - r, xs.max() + r, ys.max() + r)
    }
}

/** A note pinned on the picture at ([x], [y]), canvas units: the human's steering channel. */
@Serializable
data class PinnedNote(val x: Double, val y: Double, val text: String)

@Serializable
private data class StrokeSheetState(val next: Int, val strokes: List<StrokeRecord>, val notes: List<PinnedNote>)

/**
 * A painting kept as an ordered list of addressable strokes, re-painted from that list.
 *
 * ```
 * val sheet = PaintStrokes(700, aspect = 1.4)
 * val leaf = sheet.add(FLine().moveTo(300, 300).lineTo(420, 260), color = "#4a7a2a", width = 30.0, group = "leaves")
 * sheet.apply("""[{"op": "recolor", "at": "@leaves", "color": "#b5651d"}]""")
 * display("main", sheet.png())
 * sheet.undo()
 * ```
 *
 * Every stroke is painted with a fresh brush and its own seed, both taken from its address, onto
 * a fresh canvas, in list order. So the same list always paints the same pixels, and an edit to
 * one stroke changes nothing that does not touch it. Strokes that do touch it change too: wet
 * paint is picked up and mixed, so a stroke laid after a recoloured one drags some of the new
 * colour along. That is what re-painting a physical simulation means.
 *
 * Addresses are `s12` for one stroke, `@name` for every stroke of a group, `*` for all; several
 * may be given separated by commas. [apply] takes a JSON array of ops (see [apply]); each call is
 * one step of the history that [undo] and [redo] walk.
 */
class PaintStrokes(
    /** Pixel width of the picture. */
    val widthPx: Int = 700,
    /** width / height. */
    val aspect: Double = 1.4,
    /** Seeds the canvas; each stroke mixes in its own id. */
    val seed: Long = 1,
    /** The ground colour, sRGB. */
    val ground: Vec4 = Vec4(0.97f, 0.95f, 0.9f, 1f),
) {
    private var next = 1
    private val list = mutableListOf<StrokeRecord>()
    private val pinned = mutableListOf<PinnedNote>()
    private val past = ArrayDeque<StrokeSheetState>()
    private val future = ArrayDeque<StrokeSheetState>()

    /** The strokes, in painting order. */
    val strokes: List<StrokeRecord> get() = list.toList()

    /** The notes pinned on the picture. */
    val notes: List<PinnedNote> get() = pinned.toList()

    /** Height in canvas units (the width is always 1000). */
    val heightUnits: Double get() = 1000.0 / aspect

    /** How many steps [undo] can take back. */
    val undoDepth: Int get() = past.size

    /** The stroke at [address] (`s12`), or `null`. */
    operator fun get(address: String): StrokeRecord? = list.firstOrNull { it.address == address.trim() }

    /**
     * Add a stroke along [line] (each subpath sampled every 8 units and joined) and return its
     * address. One history step.
     */
    fun add(
        line: FLine, color: String = "#000000", brush: BrushKind = BrushKind.Round, width: Double = 20.0,
        pressure: Pair<Double, Double> = 0.8 to 0.8, hiding: Double = 0.92, group: String? = null,
    ): String {
        val pts = line.pieces().flatMap { p -> p.sampleByDistance(8f).flatMap { listOf(it.x.toDouble(), it.y.toDouble()) } }
        return add(pts, color, brush, width, pressure, hiding, group)
    }

    /** Add a stroke through `[x0, y0, x1, y1, ...]` (one point is a dab) and return its address. */
    fun add(
        points: List<Double>, color: String = "#000000", brush: BrushKind = BrushKind.Round, width: Double = 20.0,
        pressure: Pair<Double, Double> = 0.8 to 0.8, hiding: Double = 0.92, group: String? = null,
    ): String {
        checkpoint()
        return insert(StrokeRecord(next, points, listOf(pressure.first, pressure.second), brush.name, width, color, hiding, group))
    }

    /** Pin a note at ([x], [y]). One history step. */
    fun pin(x: Double, y: Double, text: String) {
        checkpoint()
        pinned += PinnedNote(x, y, text)
    }

    /** The addresses of strokes passing within [radius] of ([x], [y]), nearest first. */
    fun near(x: Double, y: Double, radius: Double = 60.0): List<String> =
        list.filter { !it.hidden }.map { it to it.distanceTo(x, y) }.filter { it.second <= radius + it.first.width / 2 }
            .sortedBy { it.second }.map { it.first.address }

    /** The addresses [selector] names: `s12`, `@group`, `*`, or several joined with commas. */
    fun select(selector: String): List<String> = selector.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { s ->
        when {
            s == "*" -> list.map { it.address }
            s.startsWith("@") -> list.filter { it.group == s.drop(1) }.map { it.address }
            else -> listOfNotNull(get(s)?.address)
        }
    }.distinct().also { require(it.isNotEmpty()) { "no stroke at '$selector'" } }

    /**
     * Apply a batch of JSON ops — an array of objects, or one object — as one history step. If any
     * op fails nothing changes. Returns the addresses each op touched, in order.
     *
     * Every op has `"op"` and, except `add` and `pin`, `"at"` (a selector, as [select]):
     * - `recolor` `color`; `restyle` any of `width`, `brush`, `hiding`, `pressure` `[start, end]`;
     * - `move` `dx`, `dy`; `bend` `dx`, `dy` — the middle of the path moves that far, the ends stay;
     * - `delete`; `hide`, `show`; `group` `name`;
     * - `reorder` `to` (an index in painting order; `-1` is last, painted on top);
     * - `add` `points`, and `color`, `brush`, `width`, `pressure`, `hiding`, `group` as [add];
     * - `pin` `x`, `y`, `text`; `unpin` `text`.
     */
    fun apply(json: String): List<List<String>> {
        val el = Json.parseToJsonElement(json)
        val ops = if (el is JsonArray) el.map { it.jsonObject } else listOf(el.jsonObject)
        val before = state()
        return try {
            ops.map { applyOne(it) }.also { past.addLast(before); future.clear() }
        } catch (e: Exception) {
            restore(before)
            throw IllegalArgumentException("ops not applied: ${e.message}", e)
        }
    }

    private fun applyOne(op: JsonObject): List<String> {
        fun str(k: String) = op[k]?.jsonPrimitive?.contentOrNull
        fun num(k: String) = op[k]?.jsonPrimitive?.doubleOrNull
        fun need(k: String) = num(k) ?: error("'${str("op")}' needs a number '$k'")
        when (val kind = str("op") ?: error("an op needs \"op\"")) {
            "add" -> {
                val pts = op["points"]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: error("points are numbers") } ?: error("'add' needs points")
                require(pts.size >= 2 && pts.size % 2 == 0) { "points are [x0, y0, x1, y1, ...]" }
                val p = pressureOf(op["pressure"]) ?: listOf(0.8, 0.8)
                val b = str("brush")?.let { kindOf(it).name } ?: "Round"
                return listOf(insert(StrokeRecord(next, pts, p, b, num("width") ?: 20.0, colorOf(str("color") ?: "#000000"), num("hiding") ?: 0.92, str("group"))))
            }
            "pin" -> { pinned += PinnedNote(need("x"), need("y"), str("text") ?: error("'pin' needs text")); return emptyList() }
            "unpin" -> { val t = str("text"); require(pinned.removeAll { it.text == t }) { "no note '$t'" }; return emptyList() }
        }
        val kind = str("op")!!
        val at = select(str("at") ?: error("'$kind' needs \"at\""))
        when (kind) {
            "recolor" -> { val c = colorOf(str("color") ?: error("'recolor' needs color")); edit(at) { it.copy(color = c) } }
            "restyle" -> edit(at) {
                it.copy(width = num("width") ?: it.width, brush = str("brush")?.let { b -> kindOf(b).name } ?: it.brush,
                    hiding = num("hiding") ?: it.hiding, pressure = pressureOf(op["pressure"]) ?: it.pressure)
            }
            "move" -> { val dx = num("dx") ?: 0.0; val dy = num("dy") ?: 0.0; edit(at) { r -> r.copy(points = r.points.mapIndexed { i, v -> v + if (i % 2 == 0) dx else dy }) } }
            "bend" -> {
                val dx = num("dx") ?: 0.0; val dy = num("dy") ?: 0.0
                edit(at) { r ->
                    val n = r.points.size / 2
                    r.copy(points = r.points.mapIndexed { i, v ->
                        val w = if (n < 2) 1.0 else sin(PI * (i / 2) / (n - 1))
                        v + w * if (i % 2 == 0) dx else dy
                    })
                }
            }
            "delete" -> list.removeAll { it.address in at }
            "hide" -> edit(at) { it.copy(hidden = true) }
            "show" -> edit(at) { it.copy(hidden = false) }
            "group" -> { val g = str("name"); edit(at) { it.copy(group = g) } }
            "reorder" -> {
                val to = op["to"]?.jsonPrimitive?.intOrNull ?: error("'reorder' needs an index 'to'")
                val moving = list.filter { it.address in at }
                list.removeAll(moving)
                val idx = if (to < 0) list.size + 1 + to else to.coerceAtMost(list.size)
                list.addAll(idx.coerceIn(0, list.size), moving)
            }
            else -> error("unknown op '$kind'")
        }
        return at
    }

    /** Take back the last step ([add], [pin] or one [apply] batch). Returns whether there was one. */
    fun undo(): Boolean {
        val s = past.removeLastOrNull() ?: return false
        future.addLast(state()); restore(s); return true
    }

    /** Put back what [undo] took. */
    fun redo(): Boolean {
        val s = future.removeLastOrNull() ?: return false
        past.addLast(state()); restore(s); return true
    }

    /** Paint the visible strokes, in order, onto a fresh canvas. */
    fun render(): PaintCanvas {
        val canvas = PaintCanvas(widthPx, aspect, ground = PaintGround.Bare, groundColor = ground, seed = seed)
        for (r in list) if (!r.hidden) paint(canvas, r)
        return canvas
    }

    /** [render] as PNG. */
    fun png(relief: Double = 0.6): ByteArray = render().use { it.png(relief = relief) }

    /** An independent sheet with the same strokes, notes and seed, and an empty history: edit it and keep this one. */
    fun copy(): PaintStrokes = PaintStrokes(widthPx, aspect, seed, ground).also { it.restore(state()) }

    /** The strokes and notes as JSON, for an agent to read: `{"next": .., "strokes": [...], "notes": [...]}`. */
    fun toJson(): String = json.encodeToString(state())

    /**
     * What an agent needs to steer by: every note with the addresses of the strokes under it, then
     * one line per stroke (address, group, colour, brush, bounds).
     */
    fun describe(): String = buildString {
        for (n in pinned) appendLine("note at (${n.x.toInt()}, ${n.y.toInt()}): \"${n.text}\" — strokes near it: ${near(n.x, n.y).joinToString(", ").ifEmpty { "none" }}")
        for (r in list) {
            val b = r.bounds().map { it.toInt() }
            appendLine("${r.address}${r.group?.let { " @$it" } ?: ""} ${r.color} ${r.brush} w${r.width.toInt()} bounds ${b}${if (r.hidden) " hidden" else ""}")
        }
    }

    private fun paint(canvas: PaintCanvas, r: StrokeRecord) {
        val strokeSeed = seed * 1_000_003 + r.id
        canvas.brush(kindOf(r.brush), r.width, seed = strokeSeed).use { b ->
            b.load(hex(r.color), hiding = r.hiding)
            if (r.points.size < 4) canvas.touch(b, r.points[0], r.points[1], pressure = r.pressure[0], seed = strokeSeed)
            else canvas.stroke(r.line(), b, pressure = r.pressure[0]..r.pressure.getOrElse(1) { r.pressure[0] }, seed = strokeSeed)
        }
    }

    private fun insert(r: StrokeRecord): String { list += r; next = r.id + 1; return r.address }

    private fun edit(at: List<String>, f: (StrokeRecord) -> StrokeRecord) {
        for (i in list.indices) if (list[i].address in at) list[i] = f(list[i])
    }

    private fun checkpoint() { past.addLast(state()); future.clear() }
    private fun state() = StrokeSheetState(next, list.toList(), pinned.toList())
    private fun restore(s: StrokeSheetState) {
        next = s.next; list.clear(); list += s.strokes; pinned.clear(); pinned += s.notes
    }

    companion object {
        private val json = Json { encodeDefaults = true; prettyPrint = false }

        /** A sheet with the strokes and notes of [toJson]; its history starts empty. */
        fun fromJson(text: String, widthPx: Int = 700, aspect: Double = 1.4, seed: Long = 1): PaintStrokes =
            PaintStrokes(widthPx, aspect, seed).also { it.restore(json.decodeFromString<StrokeSheetState>(text)) }

        private fun kindOf(name: String): BrushKind =
            BrushKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: error("no brush '$name'; one of ${BrushKind.entries.joinToString()}")

        private fun colorOf(s: String): String { hex(s); return s }

        private fun pressureOf(e: JsonElement?): List<Double>? = e?.let { p ->
            if (p is JsonArray) p.map { it.jsonPrimitive.doubleOrNull ?: error("pressure is a number or [start, end]") }
            else listOf(p.jsonPrimitive.doubleOrNull ?: error("pressure is a number or [start, end]")).let { it + it }
        }

        private fun hex(s: String): Vec4 {
            val h = s.removePrefix("#")
            require(h.length == 6 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "colour '$s' is not #rrggbb" }
            val v = h.toInt(16)
            return Vec4(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f, 1f)
        }
    }
}

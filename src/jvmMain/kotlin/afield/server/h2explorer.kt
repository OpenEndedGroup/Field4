package afield.server

import afield.H2Property
import afield.H2Reflect
import afield.Handled
import afield.RunsAt
import afield.h2GetRawValue
import afield.inMain
import afield.installHandlers
import afield.rawHandlers
import afield.thisSession
import io.ktor.websocket.Frame
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import afield.stdlib.linalg.Vector2dc
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.Vector3dc
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4dc
import afield.stdlib.linalg.Vector4fc
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.abs
import kotlin.math.max

private const val H2_EXPLORER_CHANNEL = "__h2_explorer__"
private const val H2_EXPLORER_DEFAULT_WINDOW = 320
private const val H2_EXPLORER_MAX_STRIDE = 16
private const val H2_EXPLORER_STRING_CLIP = 240

@Serializable
private data class H2Request(
    val channel: String? = null,
    val reqId: String,
    val op: String,
    val root: String? = null,
    val start: Int? = null,
    val count: Int? = null,
    val stride: Int? = null,
    /**
     * Row indices from the retained root to the value being viewed, outermost first.
     *
     * The client owns the path and the server re-resolves it from the root on every request, so a
     * drill mints no new token and inherits the root's ten-minute lifetime rather than starting its
     * own. `[7, 3]` means "row 7 of the root, then row 3 of that".
     */
    val path: List<Int>? = null
)

@Serializable
private data class H2Row(
    val index: Int,
    val key: String? = null,
    val cells: List<String>,
    /** Whether this row holds a value `.h2` would explore, so the client can offer to enter it. */
    val enterable: Boolean = false
)

@Serializable
private data class H2OpenResponse(
    val reqId: String,
    val ok: Boolean = true,
    val type: String = "open",
    val kind: String,
    val title: String,
    val totalRows: Int,
    val minStride: Int,
    val maxStride: Int,
    val defaultStride: Int,
    val columnNames: List<String>,
    val hasKeys: Boolean = false,
    val textColumns: List<Int> = emptyList(),
    /** One label per path element, for the client's trail back out. Empty at the root. */
    val breadcrumb: List<String> = emptyList(),
    /** The path actually resolved, which is the request's path truncated where it stopped. */
    val path: List<Int> = emptyList()
)

@Serializable
private data class H2WindowResponse(
    val reqId: String,
    val ok: Boolean = true,
    val type: String = "window",
    val kind: String,
    val start: Int,
    val count: Int,
    val totalRows: Int,
    val stride: Int,
    val columnNames: List<String>,
    val hasKeys: Boolean = false,
    val textColumns: List<Int> = emptyList(),
    val rows: List<H2Row>
)

@Serializable
private data class H2ErrorResponse(
    val reqId: String,
    val ok: Boolean = false,
    val type: String = "error",
    val error: String
)

private interface H2Source {
    val kind: String
    val title: String
    val minStride: Int
    val maxStride: Int
    val defaultStride: Int
    val hasKeys: Boolean get() = false

    /**
     * Column indices the client must render as text: no decimal alignment, no z-score banding.
     *
     * The client decides numeric-ness per *cell* and then pads every numeric cell to the column's
     * widest fraction (`makeAlignedNumberNode`), which is right for a buffer or a numeric stream and
     * wrong for a heterogeneous column. A property table's value column holds `7`, `0.25` and
     * `trail-7` at once, so an `Int` property rendered as `7.00` — measured, not hypothetical — and
     * the column's mean and standard deviation were computed over whichever cells happened to
     * parse. Only the source knows a column is heterogeneous, so only the source can say.
     */
    val textColumns: List<Int> get() = emptyList()

    fun normalizeStride(raw: Int?): Int {
        val s = (raw ?: defaultStride).coerceIn(minStride, maxStride)
        return s
    }

    fun totalRows(stride: Int): Int
    fun columns(stride: Int): List<String>
    fun rows(start: Int, count: Int, stride: Int): List<H2Row>

    /**
     * The value behind row [index], or null when this source's rows are not values you can enter.
     *
     * Null is the default and the honest answer for the buffer and primitive-array sources: their
     * rows are numbers, and a number is already wholly on screen. Only the three sources whose rows
     * stand for *other objects* — a list's elements, a map's values, an object's properties —
     * override it.
     *
     * Row-index addressing rather than a key or a name, because that is what the client already
     * has: the window it rendered is a range of indices, so a click knows its index and nothing
     * else. It is also why [childAt] must agree with [rows] about ordering, which is why both read
     * the same backing collection in the same iteration order.
     */
    fun childAt(index: Int): Any? = null
}

/**
 * Whether row [index] of [source] holds something worth entering.
 *
 * Asks `LazyValueVisualizer.canExplore`, which is the same gate that decides whether `.h2` opens an
 * explorer at all, so a chevron appears exactly where typing `.h2` on that value would have given
 * you one. Not a structural test written here: this file's `makeSource` is already one of several
 * ladders over this value space, and a fourth opinion would offer rows that open empty.
 */
private fun rowIsEnterable(source: H2Source, index: Int): Boolean =
    h2CanEnter(source.childAt(index))

/**
 * Whether [child] is worth offering a way into.
 *
 * Type policy is `canExplore`'s and stays there. This adds one thing it cannot answer, because it is
 * a question about the instance rather than the type: an *empty* container is explorable — `.h2` on
 * an empty map opens a table of nothing, which is honest at the top level where you asked for it —
 * but as a row it would be a chevron that opens nothing, and having clicked it you would have to
 * find your way back for no information.
 *
 * Only the three kinds whose emptiness is O(1) are checked. Deliberately not `totalRows` on a
 * constructed source, which is the fully general answer and the wrong trade: `ListSource`'s
 * constructor samples up to 512 elements to pick its mode, and this runs once per visible row, so a
 * windowful of rows would sample tens of thousands of elements to decide which chevrons to draw.
 */
private fun h2CanEnter(child: Any?): Boolean {
    if (!afield.LazyValueVisualizer.canExplore(child)) return false
    return when (child) {
        is Collection<*> -> child.isNotEmpty()
        is Map<*, *> -> child.isNotEmpty()
        is Array<*> -> child.isNotEmpty()
        else -> true
    }
}

private fun ceilDiv(value: Int, by: Int): Int {
    if (value <= 0) return 0
    return (value + by - 1) / by
}

private fun chooseDefaultStride(totalValues: Int): Int {
    return when {
        totalValues > 0 && totalValues % 4 == 0 -> 4
        totalValues > 0 && totalValues % 3 == 0 -> 3
        else -> 1
    }
}

private fun fmtNumber(n: Number): String {
    val d = n.toDouble()
    return when {
        d.isNaN() -> "NaN"
        d.isInfinite() -> if (d > 0.0) "Infinity" else "-Infinity"
        abs(d) < 0.001 && d != 0.0 -> "%.3e".format(d)
        abs(d) > 10000.0 -> "%.3e".format(d)
        else -> "%.6f".format(d).trimEnd('0').trimEnd('.')
    }
}

private fun clipText(text: String, maxChars: Int = H2_EXPLORER_STRING_CLIP): String {
    return if (text.length <= maxChars) text else text.take(maxChars - 3) + "..."
}

private fun preview(value: Any?): String {
    return when (value) {
        null -> "null"
        is Number -> fmtNumber(value)
        is Boolean, is Char -> value.toString()
        is String -> clipText(value.replace('\n', ' '))
        is Vector2fc -> "(${fmtNumber(value.x())}, ${fmtNumber(value.y())})"
        is Vector2dc -> "(${fmtNumber(value.x())}, ${fmtNumber(value.y())})"
        is Vector3fc -> "(${fmtNumber(value.x())}, ${fmtNumber(value.y())}, ${fmtNumber(value.z())})"
        is Vector3dc -> "(${fmtNumber(value.x())}, ${fmtNumber(value.y())}, ${fmtNumber(value.z())})"
        is Vector4fc -> "(${fmtNumber(value.x())}, ${fmtNumber(value.y())}, ${fmtNumber(value.z())}, ${fmtNumber(value.w())})"
        is Vector4dc -> "(${fmtNumber(value.x())}, ${fmtNumber(value.y())}, ${fmtNumber(value.z())}, ${fmtNumber(value.w())})"
        is List<*> -> "List(size=${value.size})"
        is Map<*, *> -> "Map(size=${value.size})"
        is Array<*> -> "Array(size=${value.size})"
        is ByteArray -> "ByteArray(size=${value.size})"
        is ShortArray -> "ShortArray(size=${value.size})"
        is IntArray -> "IntArray(size=${value.size})"
        is LongArray -> "LongArray(size=${value.size})"
        is FloatArray -> "FloatArray(size=${value.size})"
        is DoubleArray -> "DoubleArray(size=${value.size})"
        is BooleanArray -> "BooleanArray(size=${value.size})"
        is CharArray -> "CharArray(size=${value.size})"
        is FloatBuffer -> "FloatBuffer(pos=${value.position()}, limit=${value.limit()}, cap=${value.capacity()})"
        is IntBuffer -> "IntBuffer(pos=${value.position()}, limit=${value.limit()}, cap=${value.capacity()})"
        else -> clipText(value.toString().replace('\n', ' '))
    }
}

private fun vectorCells(value: Any?): List<String>? {
    return when (value) {
        is Vector2fc -> listOf(fmtNumber(value.x()), fmtNumber(value.y()))
        is Vector2dc -> listOf(fmtNumber(value.x()), fmtNumber(value.y()))
        is Vector3fc -> listOf(fmtNumber(value.x()), fmtNumber(value.y()), fmtNumber(value.z()))
        is Vector3dc -> listOf(fmtNumber(value.x()), fmtNumber(value.y()), fmtNumber(value.z()))
        is Vector4fc -> listOf(fmtNumber(value.x()), fmtNumber(value.y()), fmtNumber(value.z()), fmtNumber(value.w()))
        is Vector4dc -> listOf(fmtNumber(value.x()), fmtNumber(value.y()), fmtNumber(value.z()), fmtNumber(value.w()))
        else -> null
    }
}

/**
 * One row of a list-of-maps, as one cell per declared column.
 *
 * Beyond the sampled prefix a row is not guaranteed to carry the same keys, so a missing key is an
 * empty cell rather than a shifted row, and a row that is not a map at all keeps its summary in the
 * first cell. Either way the cell count matches [ListSource.columns], which is what the client pads
 * its table against.
 */
private fun recordCells(value: Any?, columns: List<String>): List<String> {
    if (value !is Map<*, *>) {
        return List(columns.size) { if (it == 0) preview(value) else "" }
    }
    val byName = LinkedHashMap<String, Any?>(value.size)
    value.forEach { (k, v) -> byName[k.toString()] = v }
    return columns.map { name -> if (byName.containsKey(name)) preview(byName[name]) else "" }
}

private class ScalarSource(private val value: Any?) : H2Source {
    override val kind: String = "scalar"
    override val title: String = value?.javaClass?.simpleName ?: "null"
    override val minStride: Int = 1
    override val maxStride: Int = 1
    override val defaultStride: Int = 1
    override fun totalRows(stride: Int): Int = 1
    override fun columns(stride: Int): List<String> = listOf("value")
    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        if (start > 0 || count <= 0) return emptyList()
        return listOf(H2Row(index = 0, cells = listOf(preview(value))))
    }
}

private class FloatBufferSource(buffer: FloatBuffer) : H2Source {
    private val data = buffer.asReadOnlyBuffer()
    private val totalValues = data.limit()

    override val kind: String = "floatbuffer"
    override val title: String = "FloatBuffer"
    override val minStride: Int = 1
    override val maxStride: Int = H2_EXPLORER_MAX_STRIDE
    override val defaultStride: Int = chooseDefaultStride(totalValues)

    override fun totalRows(stride: Int): Int = ceilDiv(totalValues, stride.coerceAtLeast(1))

    override fun columns(stride: Int): List<String> {
        val s = stride.coerceAtLeast(1)
        return (0 until s).map { "c$it" }
    }

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val s = stride.coerceAtLeast(1)
        val totalRows = totalRows(s)
        val safeStart = start.coerceIn(0, totalRows)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, totalRows)
        val out = ArrayList<H2Row>(safeEnd - safeStart)
        for (row in safeStart until safeEnd) {
            val base = row * s
            val cells = ArrayList<String>(s)
            for (c in 0 until s) {
                val idx = base + c
                cells.add(if (idx < totalValues) fmtNumber(data.get(idx)) else "")
            }
            out.add(H2Row(index = row, cells = cells))
        }
        return out
    }
}

private class IntBufferSource(buffer: IntBuffer) : H2Source {
    private val data = buffer.asReadOnlyBuffer()
    private val totalValues = data.limit()

    override val kind: String = "intbuffer"
    override val title: String = "IntBuffer"
    override val minStride: Int = 1
    override val maxStride: Int = H2_EXPLORER_MAX_STRIDE
    override val defaultStride: Int = chooseDefaultStride(totalValues)

    override fun totalRows(stride: Int): Int = ceilDiv(totalValues, stride.coerceAtLeast(1))

    override fun columns(stride: Int): List<String> {
        val s = stride.coerceAtLeast(1)
        return (0 until s).map { "c$it" }
    }

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val s = stride.coerceAtLeast(1)
        val totalRows = totalRows(s)
        val safeStart = start.coerceIn(0, totalRows)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, totalRows)
        val out = ArrayList<H2Row>(safeEnd - safeStart)
        for (row in safeStart until safeEnd) {
            val base = row * s
            val cells = ArrayList<String>(s)
            for (c in 0 until s) {
                val idx = base + c
                cells.add(if (idx < totalValues) data.get(idx).toString() else "")
            }
            out.add(H2Row(index = row, cells = cells))
        }
        return out
    }
}

private class NumericArraySource(
    override val kind: String,
    override val title: String,
    private val totalValues: Int,
    private val valueAt: (Int) -> String
) : H2Source {
    override val minStride: Int = 1
    override val maxStride: Int = H2_EXPLORER_MAX_STRIDE
    override val defaultStride: Int = chooseDefaultStride(totalValues)

    override fun totalRows(stride: Int): Int = ceilDiv(totalValues, stride.coerceAtLeast(1))

    override fun columns(stride: Int): List<String> {
        val s = stride.coerceAtLeast(1)
        return (0 until s).map { "c$it" }
    }

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val s = stride.coerceAtLeast(1)
        val totalRows = totalRows(s)
        val safeStart = start.coerceIn(0, totalRows)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, totalRows)
        val out = ArrayList<H2Row>(safeEnd - safeStart)
        for (row in safeStart until safeEnd) {
            val base = row * s
            val cells = ArrayList<String>(s)
            for (c in 0 until s) {
                val idx = base + c
                cells.add(if (idx < totalValues) valueAt(idx) else "")
            }
            out.add(H2Row(index = row, cells = cells))
        }
        return out
    }
}

private class ListSource(private val list: List<*>) : H2Source {
    private enum class Mode { GENERIC, VECTOR, NUMERIC_STREAM, RECORD }

    private val mode: Mode
    private val vectorColumns: List<String>
    private val recordColumns: List<String>

    init {
        var allNumeric = true
        var vectorDim: Int? = null
        // A list of same-shaped maps is a table, not a column of summaries. Track whether every
        // sampled element is a Map with the *same* key sequence; the first element's keys become
        // the columns, in its iteration order, so a LinkedHashMap keeps the producer's ordering.
        var recordKeys: List<String>? = null
        var recordUniform = list.isNotEmpty()
        val sampleCount = minOf(list.size, 512)
        for (i in 0 until sampleCount) {
            val v = list[i]
            if (v !is Number) allNumeric = false
            if (recordUniform) {
                if (v is Map<*, *> && v.isNotEmpty()) {
                    val keys = v.keys.map { it.toString() }
                    if (recordKeys == null) recordKeys = keys else if (recordKeys != keys) recordUniform = false
                } else {
                    recordUniform = false
                }
            }
            val cells = vectorCells(v)
            val dim = cells?.size
            if (dim == null) {
                if (v != null) vectorDim = -1
            } else if (vectorDim == null) {
                vectorDim = dim
            } else if (vectorDim != dim) {
                vectorDim = -1
            }
        }

        when {
            recordUniform && recordKeys != null -> {
                mode = Mode.RECORD
                recordColumns = recordKeys
                vectorColumns = emptyList()
            }
            vectorDim != null && vectorDim > 1 -> {
                mode = Mode.VECTOR
                recordColumns = emptyList()
                vectorColumns = (0 until vectorDim).map {
                    when (it) {
                        0 -> "x"
                        1 -> "y"
                        2 -> "z"
                        3 -> "w"
                        else -> "c$it"
                    }
                }
            }
            allNumeric && list.isNotEmpty() -> {
                mode = Mode.NUMERIC_STREAM
                recordColumns = emptyList()
                vectorColumns = emptyList()
            }
            else -> {
                mode = Mode.GENERIC
                recordColumns = emptyList()
                vectorColumns = emptyList()
            }
        }
    }

    override val kind: String = "list"
    override val title: String = list::class.simpleName ?: "List"
    override val minStride: Int = 1
    override val maxStride: Int = if (mode == Mode.NUMERIC_STREAM) H2_EXPLORER_MAX_STRIDE else 1
    override val defaultStride: Int = if (mode == Mode.NUMERIC_STREAM) chooseDefaultStride(list.size) else 1

    override fun totalRows(stride: Int): Int {
        return when (mode) {
            Mode.NUMERIC_STREAM -> ceilDiv(list.size, stride.coerceAtLeast(1))
            else -> list.size
        }
    }

    override fun columns(stride: Int): List<String> {
        return when (mode) {
            Mode.GENERIC -> listOf("value")
            Mode.RECORD -> recordColumns
            Mode.VECTOR -> vectorColumns
            Mode.NUMERIC_STREAM -> (0 until stride.coerceAtLeast(1)).map { "c$it" }
        }
    }

    /**
     * The element at [index] — except in `NUMERIC_STREAM`, where a row is not an element.
     *
     * That mode packs `stride` numbers per row, so row 3 at stride 4 covers elements 12..15 and
     * there is no single value a click could mean. Null there is the difference between "cannot
     * enter" and "enters the wrong thing". The other three modes are one row per element, and
     * `VECTOR` needs no special case: `canExplore` refuses a vector, because `.h` draws its
     * components better than a property list would.
     */
    override fun childAt(index: Int): Any? =
        if (mode == Mode.NUMERIC_STREAM) null else list.getOrNull(index)

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val s = stride.coerceAtLeast(1)
        val totalRows = totalRows(s)
        val safeStart = start.coerceIn(0, totalRows)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, totalRows)
        val out = ArrayList<H2Row>(safeEnd - safeStart)

        when (mode) {
            Mode.GENERIC -> {
                for (row in safeStart until safeEnd) {
                    out.add(
                        H2Row(
                            index = row,
                            cells = listOf(preview(list[row])),
                            enterable = h2CanEnter(list[row])
                        )
                    )
                }
            }
            Mode.RECORD -> {
                for (row in safeStart until safeEnd) {
                    out.add(
                        H2Row(
                            index = row,
                            cells = recordCells(list[row], recordColumns),
                            // A record row is already flattened to columns, but entering it shows the
                            // whole map, including any key the uniform-column set left out.
                            enterable = h2CanEnter(list[row])
                        )
                    )
                }
            }
            Mode.VECTOR -> {
                for (row in safeStart until safeEnd) {
                    val cells = vectorCells(list[row]) ?: listOf(preview(list[row]))
                    out.add(H2Row(index = row, cells = cells))
                }
            }
            Mode.NUMERIC_STREAM -> {
                for (row in safeStart until safeEnd) {
                    val base = row * s
                    val cells = ArrayList<String>(s)
                    for (c in 0 until s) {
                        val idx = base + c
                        val cell = list.getOrNull(idx)
                        cells.add(if (cell is Number) fmtNumber(cell) else if (cell == null) "" else preview(cell))
                    }
                    out.add(H2Row(index = row, cells = cells))
                }
            }
        }

        return out
    }
}

private class MapSource(private val map: Map<*, *>) : H2Source {
    override val kind: String = "map"
    override val title: String = map::class.simpleName ?: "Map"
    override val minStride: Int = 1
    override val maxStride: Int = 1
    override val defaultStride: Int = 1
    override val hasKeys: Boolean = true

    override fun totalRows(stride: Int): Int = map.size
    override fun columns(stride: Int): List<String> = listOf("value")

    /** The entry *value* at [index]; a map's keys are already whole in the key column. */
    override fun childAt(index: Int): Any? {
        if (index < 0 || index >= map.size) return null
        var i = 0
        for ((_, v) in map) {
            if (i == index) return v
            i++
        }
        return null
    }

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val safeStart = start.coerceIn(0, map.size)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, map.size)
        if (safeStart >= safeEnd) return emptyList()

        val out = ArrayList<H2Row>(safeEnd - safeStart)
        var i = 0
        for ((k, v) in map) {
            if (i >= safeEnd) break
            if (i >= safeStart) {
                out.add(
                    H2Row(
                        index = i,
                        key = preview(k),
                        cells = listOf(preview(v)),
                        enterable = h2CanEnter(v)
                    )
                )
            }
            i++
        }
        return out
    }
}

/**
 * Test seam: the column names and the first [count] rows of cells the explorer would stream for
 * [value], without a server, a socket or a browser.
 *
 * This exists because everything else in this file is private, so the row *content* — as opposed to
 * the paging around it — had no reachable oracle. `.h2` on a DataFrame streamed `Map(size=3)` for
 * every row for exactly that reason: the offset arithmetic was covered and the cells were not.
 */
internal fun h2ExplorerCellsForTesting(
    value: Any?,
    start: Int = 0,
    count: Int = 4,
    path: List<Int> = emptyList(),
): Pair<List<String>, List<List<String>>> {
    val (source, _, _) = sourceAtPath(value, path)
    val stride = source.normalizeStride(null)
    return source.columns(stride) to source.rows(start, count, stride).map { it.cells }
}

/**
 * Test seam for the drill: what the explorer would show at [path], and how it labels the trail.
 *
 * Returns the resolved source's title, the breadcrumb, the path that actually resolved (a stale or
 * out-of-range step truncates it), and each row's key with whether it can be entered. Exists for the
 * same reason [h2ExplorerCellsForTesting] does — everything in this file is private, so without a
 * seam the drill's only oracle would be a browser.
 */
internal fun h2ExplorerDrillForTesting(
    value: Any?,
    path: List<Int> = emptyList(),
    count: Int = 32,
): H2DrillProbe {
    val (source, breadcrumb, resolved) = sourceAtPath(value, path)
    val stride = source.normalizeStride(null)
    val rows = source.rows(0, count, stride)
    return H2DrillProbe(
        title = source.title,
        kind = source.kind,
        totalRows = source.totalRows(stride),
        breadcrumb = breadcrumb,
        resolvedPath = resolved,
        rows = rows.map { it.key to it.enterable }
    )
}

/** What [h2ExplorerDrillForTesting] observed. */
internal data class H2DrillProbe(
    val title: String,
    val kind: String,
    val totalRows: Int,
    val breadcrumb: List<String>,
    val resolvedPath: List<Int>,
    val rows: List<Pair<String?, Boolean>>
)

/**
 * A domain object as one row per property: the property name in the key column, then its value and
 * declared type.
 *
 * `hasKeys` is on, and the name goes in the *key* column rather than a data column, because the
 * client styles the key column as left-aligned text with its own show/hide toggle
 * (`getColumnDefs` in `h2_explorer.html`) while every `columnNames` column is styled `"num"` and
 * right-aligns. A right-aligned property name is the tell that this got wired the other way round.
 *
 * Rows come from [H2Reflect.properties] — the same enumerator the `/tree` and `/browse` `Browsable`
 * reads — so the iframe and the outliner cannot report different children for one object. A
 * property whose getter threw arrives carrying its failure text and occupies a row; it is not
 * dropped.
 */
private class PropertyRowsSource(private val value: Any) : H2Source {
    private val properties: List<H2Property> by lazy { H2Reflect.properties(value) }

    override val kind: String = "object"
    override val title: String = H2Reflect.summaryLine(value)
    override val minStride: Int = 1
    override val maxStride: Int = 1
    override val defaultStride: Int = 1
    override val hasKeys: Boolean = true

    override fun totalRows(stride: Int): Int = properties.size
    override fun columns(stride: Int): List<String> = listOf("value", "type")

    /** The property's read value, and null for one whose getter threw — there is nothing to open. */
    override fun childAt(index: Int): Any? =
        properties.getOrNull(index)?.takeIf { it.failure == null }?.value

    // Both: the value column mixes numbers, strings, booleans and collection summaries, and the
    // type column is names. Neither is a numeric series.
    override val textColumns: List<Int> = listOf(0, 1)

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val safeStart = start.coerceIn(0, properties.size)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, properties.size)
        if (safeStart >= safeEnd) return emptyList()
        return (safeStart until safeEnd).map { i ->
            val p = properties[i]
            H2Row(
                index = i,
                key = p.label,
                cells = listOf(p.cellText, p.typeName),
                // A property whose getter threw has no value to enter, whatever its declared type.
                enterable = p.failure == null && h2CanEnter(p.value)
            )
        }
    }
}

/**
 * The [H2Source] for [value], consulting [afield.H2ValueRegistry] first.
 *
 * The registry lookup is not optional now that rows can be entered. It used to be: both callers
 * passed a root, `visualize` had already substituted the adapter's value before retaining it under
 * the token, and this surface was flat, so no nested value ever reached here. A drill changes that —
 * every value below the root arrives through [sourceAtPath] — and without the lookup a nested `Box`
 * would be handed to `PropertyRowsSource`, which reflects, which reads `Box.ki`, which compiles a K2
 * REPL engine for a box nobody ran. The comment that used to sit on the reflective arm below
 * predicted exactly this and asked whoever added the drill to fix it here.
 *
 * One hop, like `wrapForInspector`: an adapter's substitute is dispatched structurally and never
 * re-consults the registry, and `!==` keeps an adapter that substitutes nothing from recursing.
 */
private fun makeSource(value: Any?): H2Source {
    if (value != null) {
        val adapted = afield.H2ValueRegistry.adapterFor(value)?.invoke(value)
        if (adapted != null && adapted.value !== value) return makeSourceUnadapted(adapted.value)
    }
    return makeSourceUnadapted(value)
}

private fun makeSourceUnadapted(value: Any?): H2Source {
    return when (value) {
        null -> ScalarSource(null)
        is FloatBuffer -> FloatBufferSource(value)
        is IntBuffer -> IntBufferSource(value)
        is ByteArray -> NumericArraySource("bytearray", "ByteArray", value.size) { value[it].toString() }
        is ShortArray -> NumericArraySource("shortarray", "ShortArray", value.size) { value[it].toString() }
        is IntArray -> NumericArraySource("intarray", "IntArray", value.size) { value[it].toString() }
        is LongArray -> NumericArraySource("longarray", "LongArray", value.size) { value[it].toString() }
        is FloatArray -> NumericArraySource("floatarray", "FloatArray", value.size) { fmtNumber(value[it]) }
        is DoubleArray -> NumericArraySource("doublearray", "DoubleArray", value.size) { fmtNumber(value[it]) }
        is BooleanArray -> ListSource(value.asList())
        is CharArray -> ListSource(value.asList())
        is List<*> -> ListSource(value)
        is Array<*> -> ListSource(value.asList())
        is Map<*, *> -> MapSource(value)
        // After `List`, so a list keeps its no-copy path: a `Set` or a `Queue` is snapshotted,
        // because `rows(start, count)` addresses by index and only a `List` answers that in place.
        is Collection<*> -> ListSource(value.toList())
        // The reflective default. `ScalarSource` — one row, one cell, `toString()` — remains the
        // answer for anything `H2Reflect` declines, which is what every non-collection used to get
        // and is why forcing a domain object through the gate alone would have shown an empty frame.
        //
        else -> if (H2Reflect.isExplorableObject(value)) PropertyRowsSource(value) else ScalarSource(value)
    }
}

/**
 * Walk [path] from [rootValue], returning the source to render and one label per step taken.
 *
 * Clamps rather than fails. A path can go stale between requests — the value behind it is live box
 * state, a map can lose the key you were inside, a list can shorten — and the useful answer then is
 * the deepest still-valid ancestor, not an error dialog. So a step that cannot be resolved ends the
 * walk, and the returned path is the prefix that worked, which the client adopts as its new
 * position. That is why [H2OpenResponse] echoes `path` back rather than trusting the request's.
 *
 * Labels come from the row the step entered, so the trail reads in the reader's own terms —
 * `properties` / `children` / `[0]` — rather than as indices.
 */
private fun sourceAtPath(rootValue: Any?, path: List<Int>): Triple<H2Source, List<String>, List<Int>> {
    var source = makeSource(rootValue)
    val labels = ArrayList<String>(path.size)
    val taken = ArrayList<Int>(path.size)

    for (index in path) {
        val child = source.childAt(index) ?: break
        if (!h2CanEnter(child)) break
        val label = source.rows(index, 1, source.normalizeStride(null))
            .firstOrNull()
            ?.let { it.key ?: it.cells.firstOrNull() }
            ?: "[$index]"
        source = makeSource(child)
        labels.add(label)
        taken.add(index)
    }
    return Triple(source, labels, taken)
}

private object H2ExplorerProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun handle(text: String): String? {
        val req = runCatching { json.decodeFromString(H2Request.serializer(), text) }.getOrNull() ?: return null
        if (req.channel != H2_EXPLORER_CHANNEL) return null

        val token = req.root ?: return json.encodeToString(H2ErrorResponse(req.reqId, error = "missing root token"))
        val value = h2GetRawValue(token) ?: return json.encodeToString(H2ErrorResponse(req.reqId, error = "unknown or expired root token"))
        val (source, breadcrumb, resolvedPath) = sourceAtPath(value, req.path ?: emptyList())

        return when (req.op) {
            "open" -> {
                val stride = source.normalizeStride(req.stride)
                json.encodeToString(
                    H2OpenResponse.serializer(),
                    H2OpenResponse(
                        reqId = req.reqId,
                        kind = source.kind,
                        title = source.title,
                        totalRows = source.totalRows(stride),
                        minStride = source.minStride,
                        maxStride = source.maxStride,
                        defaultStride = source.defaultStride,
                        columnNames = source.columns(stride),
                        hasKeys = source.hasKeys,
                        textColumns = source.textColumns,
                        breadcrumb = breadcrumb,
                        path = resolvedPath
                    )
                )
            }

            "window" -> {
                val stride = source.normalizeStride(req.stride)
                val totalRows = source.totalRows(stride)
                val requestedCount = max(1, req.count ?: H2_EXPLORER_DEFAULT_WINDOW)
                val start = (req.start ?: 0).coerceIn(0, totalRows)
                val safeStart = if (start >= totalRows) max(0, totalRows - 1) else start
                val rows = source.rows(safeStart, requestedCount, stride)
                json.encodeToString(
                    H2WindowResponse.serializer(),
                    H2WindowResponse(
                        reqId = req.reqId,
                        kind = source.kind,
                        start = safeStart,
                        count = rows.size,
                        totalRows = totalRows,
                        stride = stride,
                        columnNames = source.columns(stride),
                        hasKeys = source.hasKeys,
                        textColumns = source.textColumns,
                        rows = rows
                    )
                )
            }

            else -> json.encodeToString(H2ErrorResponse(req.reqId, error = "unknown op ${req.op}"))
        }
    }
}

@RunsAt(installHandlers)
fun installH2ExplorerHandler() {
    rawHandlers.getOrPut(H2_EXPLORER_CHANNEL) { mutableSetOf() }.add { text ->
        val session = thisSession.get()
        val out = H2ExplorerProtocol.handle(text) ?: return@add Handled.no
        inMain {
            session.send(Frame.Text(out))
        }
        Handled.yes
    }
}

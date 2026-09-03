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
    val stride: Int? = null
)

@Serializable
private data class H2Row(
    val index: Int,
    val key: String? = null,
    val cells: List<String>
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
    val textColumns: List<Int> = emptyList()
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

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val s = stride.coerceAtLeast(1)
        val totalRows = totalRows(s)
        val safeStart = start.coerceIn(0, totalRows)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, totalRows)
        val out = ArrayList<H2Row>(safeEnd - safeStart)

        when (mode) {
            Mode.GENERIC -> {
                for (row in safeStart until safeEnd) {
                    out.add(H2Row(index = row, cells = listOf(preview(list[row]))))
                }
            }
            Mode.RECORD -> {
                for (row in safeStart until safeEnd) {
                    out.add(H2Row(index = row, cells = recordCells(list[row], recordColumns)))
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
                        cells = listOf(preview(v))
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
): Pair<List<String>, List<List<String>>> {
    val source = makeSource(value)
    val stride = source.normalizeStride(null)
    return source.columns(stride) to source.rows(start, count, stride).map { it.cells }
}

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

    // Both: the value column mixes numbers, strings, booleans and collection summaries, and the
    // type column is names. Neither is a numeric series.
    override val textColumns: List<Int> = listOf(0, 1)

    override fun rows(start: Int, count: Int, stride: Int): List<H2Row> {
        val safeStart = start.coerceIn(0, properties.size)
        val safeEnd = (safeStart + count.coerceAtLeast(0)).coerceIn(safeStart, properties.size)
        if (safeStart >= safeEnd) return emptyList()
        return (safeStart until safeEnd).map { i ->
            val p = properties[i]
            H2Row(index = i, key = p.label, cells = listOf(p.cellText, p.typeName))
        }
    }
}

private fun makeSource(value: Any?): H2Source {
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
        else -> if (H2Reflect.isExplorableObject(value)) PropertyRowsSource(value) else ScalarSource(value)
    }
}

private object H2ExplorerProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun handle(text: String): String? {
        val req = runCatching { json.decodeFromString(H2Request.serializer(), text) }.getOrNull() ?: return null
        if (req.channel != H2_EXPLORER_CHANNEL) return null

        val token = req.root ?: return json.encodeToString(H2ErrorResponse(req.reqId, error = "missing root token"))
        val value = h2GetRawValue(token) ?: return json.encodeToString(H2ErrorResponse(req.reqId, error = "unknown or expired root token"))
        val source = makeSource(value)

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
                        textColumns = source.textColumns
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

package afield.server

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
import org.joml.Vector2dc
import afield.stdlib.linalg.Vector2fc
import org.joml.Vector3dc
import afield.stdlib.linalg.Vector3fc
import org.joml.Vector4dc
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
    val hasKeys: Boolean = false
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
    private enum class Mode { GENERIC, VECTOR, NUMERIC_STREAM }

    private val mode: Mode
    private val vectorColumns: List<String>

    init {
        var allNumeric = true
        var vectorDim: Int? = null
        val sampleCount = minOf(list.size, 512)
        for (i in 0 until sampleCount) {
            val v = list[i]
            if (v !is Number) allNumeric = false
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
            vectorDim != null && vectorDim > 1 -> {
                mode = Mode.VECTOR
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
                vectorColumns = emptyList()
            }
            else -> {
                mode = Mode.GENERIC
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
        else -> ScalarSource(value)
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
                        hasKeys = source.hasKeys
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

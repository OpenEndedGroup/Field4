package afield

import afield.graphics.FLine
import afield.logger.lprintln
import afield.stdlib.Quantity
import afield.stdlib.autoScaleExpression
import afield.stdlib.formatExpression
import afield.stdlib.formatQuantityDisplay
import afield.stdlib.formatUnitSuperscripts
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.v3
import eu.sirotin.kotunil.core.*
import io.ktor.websocket.send
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.script.experimental.api.ResultValue
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.round
import kotlin.math.sqrt

private const val SPREADSHEET_CHANNEL = "__spreadsheet__"
private const val DEFAULT_SHEET_ROWS = 20
private const val DEFAULT_SHEET_COLS = 12
private const val DEFAULT_SHEET_COL_WIDTH = 120
private const val DEFAULT_SHEET_ROW_HEIGHT = 32
private const val MAX_SHEET_ROWS = 512
private const val MAX_SHEET_COLS = 256
private const val SPREADSHEET_POLL_HOOK = "__spreadsheet_poll__"

@Serializable
data class SpreadsheetStoredCell(
    val row: Int,
    val col: Int,
    val input: String
)

@Serializable
data class SpreadsheetStoredState(
    val rows: Int = DEFAULT_SHEET_ROWS,
    val cols: Int = DEFAULT_SHEET_COLS,
    val colWidths: List<Double> = emptyList(),
    val rowHeights: List<Double> = emptyList(),
    val cells: List<SpreadsheetStoredCell> = emptyList()
)

class SpreadsheetStateBuilder internal constructor(state: SpreadsheetStoredState? = null) {
    var rows: Int = state?.rows ?: DEFAULT_SHEET_ROWS
    var cols: Int = state?.cols ?: DEFAULT_SHEET_COLS

    private val colWidths = linkedMapOf<Int, Int>()
    private val rowHeights = linkedMapOf<Int, Int>()
    private val cells = linkedMapOf<String, SpreadsheetStoredCell>()

    init {
        state?.colWidths.orEmpty().forEachIndexed { index, width ->
            colWidths[index] = width.toInt()
        }
        state?.rowHeights.orEmpty().forEachIndexed { index, height ->
            rowHeights[index] = height.toInt()
        }
        state?.cells.orEmpty().forEach { cell ->
            cells["${cell.row}:${cell.col}"] = cell
        }
    }

    fun cell(ref: String, input: Any?) {
        setCell(parseBuilderCellRef(ref), input)
    }

    fun formula(ref: String, expression: String) {
        val trimmed = expression.trim()
        cell(ref, if (trimmed.startsWith("=")) trimmed else "=$trimmed")
    }

    fun clear(ref: String) {
        cells.remove(cellKey(parseBuilderCellRef(ref)))
    }

    fun clearAll() {
        cells.clear()
    }

    fun columnWidth(label: String, width: Int) {
        val col = columnLabelToIndex(label)
            ?: error("Unknown spreadsheet column '$label'")
        columnWidthAt(col, width)
    }

    fun columnWidthAt(col: Int, width: Int) {
        colWidths[col] = width
    }

    fun rowHeight(rowNumber: Int, height: Int) {
        rowHeightAt(rowNumber - 1, height)
    }

    fun rowHeightAt(row: Int, height: Int) {
        rowHeights[row] = height
    }

    operator fun set(ref: String, input: Any?) {
        cell(ref, input)
    }

    operator fun get(ref: String): String? =
        cells[cellKey(parseBuilderCellRef(ref))]?.input

    internal fun build(): SpreadsheetStoredState {
        val safeRows = rows.coerceIn(1, MAX_SHEET_ROWS)
        val safeCols = cols.coerceIn(1, MAX_SHEET_COLS)
        val state = SpreadsheetStoredState(
            rows = safeRows,
            cols = safeCols,
            colWidths = List(safeCols) { index -> (colWidths[index] ?: DEFAULT_SHEET_COL_WIDTH).toDouble() },
            rowHeights = List(safeRows) { index -> (rowHeights[index] ?: DEFAULT_SHEET_ROW_HEIGHT).toDouble() },
            cells = cells.values.toList()
        )
        return normalizeStoredState(state)
    }

    private fun parseBuilderCellRef(ref: String): CellCoord =
        cellRefToCoord(ref.trim()) ?: error("Unknown spreadsheet cell reference '$ref'")

    private fun setCell(coord: CellCoord, input: Any?) {
        val text = spreadsheetCellInputText(input)
        if (text.isBlank()) {
            cells.remove(cellKey(coord))
        } else {
            cells[cellKey(coord)] = SpreadsheetStoredCell(coord.row, coord.col, text)
        }
        rows = maxOf(rows, coord.row + 1)
        cols = maxOf(cols, coord.col + 1)
    }

    private fun cellKey(coord: CellCoord): String = "${coord.row}:${coord.col}"
}

fun spreadsheetState(init: SpreadsheetStateBuilder.() -> Unit): SpreadsheetStoredState =
    SpreadsheetStateBuilder().apply(init).build()

fun sheetState(init: SpreadsheetStateBuilder.() -> Unit): SpreadsheetStoredState =
    spreadsheetState(init)

@Serializable
data class SpreadsheetCellSnapshot(
    val row: Int,
    val col: Int,
    val input: String,
    val type: String,
    val display: String,
    val errorDetail: String? = null,
    val htmlDisplay: String? = null
)

val SpreadsheetCellSnapshot.ref: String
    get() = cellLabel(row, col)

@Serializable
data class SpreadsheetSnapshot(
    val uid: String,
    val property: String,
    val rows: Int,
    val cols: Int,
    val colWidths: List<Int>,
    val rowHeights: List<Int>,
    val cells: List<SpreadsheetCellSnapshot>,
    val version: Long
)

fun SpreadsheetStoredState.cell(ref: String): SpreadsheetStoredCell? {
    val coord = cellRefToCoord(ref.trim()) ?: return null
    return cells.firstOrNull { it.row == coord.row && it.col == coord.col }
}

operator fun SpreadsheetStoredState.get(ref: String): SpreadsheetStoredCell? =
    cell(ref)

fun SpreadsheetSnapshot.cell(ref: String): SpreadsheetCellSnapshot? {
    val coord = cellRefToCoord(ref.trim()) ?: return null
    return cells.firstOrNull { it.row == coord.row && it.col == coord.col }
}

operator fun SpreadsheetSnapshot.get(ref: String): SpreadsheetCellSnapshot? =
    cell(ref)

@Serializable
data class SpreadsheetCompletionItem(
    val label: String,
    val insertText: String,
    val kind: String = "item",
    val detail: String = "",
    val documentation: String = "",
    val replaceStart: Int,
    val replaceEnd: Int,
    val cursorOffset: Int? = null,
    val selectInsertedText: Boolean = false
)

private object SpreadsheetProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class Request(
        val channel: String? = null,
        val reqId: String = "",
        val op: String,
        val uid: String? = null,
        val property: String = "spreadsheet",
        val watch: Boolean = true,
        val state: SpreadsheetStoredState? = null,
        val text: String? = null,
        val cursor: Int? = null
    )

    @Serializable
    data class SnapshotEnvelope(
        val reqId: String? = null,
        val ok: Boolean = true,
        val type: String = "sheet",
        val snapshot: SpreadsheetSnapshot
    )

    @Serializable
    data class CompletionEnvelope(
        val reqId: String,
        val ok: Boolean = true,
        val type: String = "completion",
        val items: List<SpreadsheetCompletionItem>
    )

    @Serializable
    data class ErrorEnvelope(
        val reqId: String? = null,
        val ok: Boolean = false,
        val error: String
    )
}

private data class SpreadsheetKey(
    val documentFilename: String,
    val uid: String,
    val property: String
)

private data class SpreadsheetClient(
    val key: Any,
    val send: (String) -> Unit
) {
    override fun equals(other: Any?): Boolean = other is SpreadsheetClient && other.key === key
    override fun hashCode(): Int = System.identityHashCode(key)
}

private data class SpreadsheetRef(
    val uid: String,
    val name: String
)

internal enum class SpreadsheetBoxMemberAccess {
    GET,
    PROP,
    VARIABLE
}

internal data class SpreadsheetBoxDependency(
    val boxId: String,
    val member: String,
    val access: SpreadsheetBoxMemberAccess
)

internal data class CellCoord(
    val row: Int,
    val col: Int
)

private data class ParsedCellRef(
    val row: Int,
    val col: Int,
    val endExclusive: Int
)

private data class SheetWordFragment(
    val start: Int,
    val end: Int,
    val text: String
)

private data class EvalCell(
    val input: String,
    val type: String,
    val value: Any?,
    val display: String,
    val errorDetail: String?,
    val htmlDisplay: String? = null
)

private data class EvalResult(
    val value: Any?,
    val errorDetail: String? = null
)

private data class GeneralCompletion(
    val label: String,
    val insertText: String,
    val kind: String,
    val detail: String,
    val documentation: String = "",
    val cursorOffset: Int? = null,
    val selectInsertedText: Boolean = false
)

private sealed interface CompletionContext {
    data class BoxTarget(val replaceStart: Int, val replaceEnd: Int, val query: String) : CompletionContext
    data class BoxMember(val ref: String, val replaceStart: Int, val replaceEnd: Int, val query: String) : CompletionContext
    data class SelfMember(val replaceStart: Int, val replaceEnd: Int, val query: String) : CompletionContext
    data class General(val replaceStart: Int, val replaceEnd: Int, val query: String) : CompletionContext
}

object SpreadsheetEvalHelpers {
    internal val currentRuntime = ThreadLocal<SpreadsheetRuntime?>()

    private fun runtime(): SpreadsheetRuntime =
        currentRuntime.get() ?: error("Spreadsheet runtime is not available on this thread")

    @JvmStatic
    fun cell(ref: String): SpreadsheetScalar = runtime().cell(ref)

    @JvmStatic
    fun range(ref: String): List<SpreadsheetScalar> = runtime().range(ref)

    @JvmStatic
    fun box(ref: String): SpreadsheetBoxBinding = runtime().box(ref)

    @JvmStatic
    val self: SpreadsheetBoxBinding
        get() = runtime().self()

    @JvmStatic
    fun sum(values: Any?): Double = flattenNumeric(values).sum()

    @JvmStatic
    fun avg(values: Any?): Double {
        val flattened = flattenNumeric(values)
        return if (flattened.isEmpty()) 0.0 else flattened.sum() / flattened.size
    }

    @JvmStatic
    fun mean(values: Any?): Double = avg(values)

    @JvmStatic
    fun min(values: Any?): Double? = flattenNumeric(values).minOrNull()

    @JvmStatic
    fun max(values: Any?): Double? = flattenNumeric(values).maxOrNull()

    @JvmStatic
    fun count(values: Any?): Int = flattenAny(values).size

    @JvmStatic
    fun nonEmpty(values: Any?): List<Any?> = flattenAny(values).filterNot { it == null || it == "" }

    private fun flattenAny(value: Any?): List<Any?> = when (value) {
        null -> emptyList()
        is SpreadsheetScalar -> flattenAny(value.raw)
        is Iterable<*> -> value.flatMap { flattenAny(it) }
        is Array<*> -> value.flatMap { flattenAny(it) }
        else -> listOf(value)
    }

    private fun flattenNumeric(value: Any?): List<Double> =
        flattenAny(value).mapNotNull { numericValue(it) }
}

private fun <T> withSpreadsheetRuntime(runtime: SpreadsheetRuntime, block: () -> T): T {
    val previous = SpreadsheetEvalHelpers.currentRuntime.get()
    SpreadsheetEvalHelpers.currentRuntime.set(runtime)
    return try {
        block()
    } finally {
        SpreadsheetEvalHelpers.currentRuntime.set(previous)
    }
}

private fun unwrapSpreadsheetValue(value: Any?): Any? = when (value) {
    is SpreadsheetScalar -> value.raw
    else -> value
}

private enum class SpreadsheetBinaryOp(val symbol: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
    REMAINDER("%")
}

private data class SpreadsheetFLineSummary(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val centerX: Double,
    val centerY: Double,
    val diagonal: Double
) {
    fun center2(): Vec2 = Vec2(centerX.toFloat(), centerY.toFloat())
    fun center3(): Vec3 = Vec3(centerX.toFloat(), centerY.toFloat(), 0f)
    fun bounds4(): Vec4 = Vec4(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
}

private val SPREADSHEET_COMPONENT_REGEX = Regex("""[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?""")

private fun summarizeFLine(line: FLine): SpreadsheetFLineSummary {
    val bounds = runCatching { line.fast2DBounds() }.getOrNull()
    if (bounds == null) {
        return SpreadsheetFLineSummary(
            x = 0.0,
            y = 0.0,
            w = 0.0,
            h = 0.0,
            centerX = 0.0,
            centerY = 0.0,
            diagonal = 0.0
        )
    }
    val centerX = bounds.x + bounds.w * 0.5
    val centerY = bounds.y + bounds.h * 0.5
    return SpreadsheetFLineSummary(
        x = bounds.x,
        y = bounds.y,
        w = bounds.w,
        h = bounds.h,
        centerX = centerX,
        centerY = centerY,
        diagonal = sqrt(bounds.w * bounds.w + bounds.h * bounds.h)
    )
}

private fun spreadsheetLooseNumericComponents(value: Any?): List<Double>? {
    val normalized = unwrapSpreadsheetValue(value)
    return when (normalized) {
        null -> emptyList()
        is Double -> listOf(normalized)
        is Float -> listOf(normalized.toDouble())
        is Int -> listOf(normalized.toDouble())
        is Long -> listOf(normalized.toDouble())
        is Short -> listOf(normalized.toDouble())
        is Byte -> listOf(normalized.toDouble())
        is Number -> listOf(normalized.toDouble())
        is Boolean -> listOf(if (normalized) 1.0 else 0.0)
        is String -> SPREADSHEET_COMPONENT_REGEX.findAll(normalized).mapNotNull { it.value.toDoubleOrNull() }.toList().ifEmpty { null }
        is Vec2 -> listOf(normalized.x.toDouble(), normalized.y.toDouble())
        is Vec3 -> listOf(normalized.x.toDouble(), normalized.y.toDouble(), normalized.z.toDouble())
        is Vec4 -> listOf(normalized.x.toDouble(), normalized.y.toDouble(), normalized.z.toDouble(), normalized.w.toDouble())
        is Iterable<*> -> normalized.flatMap { spreadsheetLooseNumericComponents(it).orEmpty() }
        is Array<*> -> normalized.flatMap { spreadsheetLooseNumericComponents(it).orEmpty() }
        is IntArray -> normalized.map { it.toDouble() }
        is LongArray -> normalized.map { it.toDouble() }
        is FloatArray -> normalized.map { it.toDouble() }
        is DoubleArray -> normalized.toList()
        is ShortArray -> normalized.map { it.toDouble() }
        is ByteArray -> normalized.map { it.toDouble() }
        is BooleanArray -> normalized.map { if (it) 1.0 else 0.0 }
        else -> null
    }
}

private fun spreadsheetVectorFromLooseComponents(value: Any?, size: Int): FloatArray? {
    val components = spreadsheetLooseNumericComponents(value) ?: return null
    if (components.isEmpty()) return FloatArray(size)
    if (components.size == 1) return FloatArray(size) { components.first().toFloat() }
    return FloatArray(size) { index ->
        if (index < components.size) components[index].toFloat() else 0f
    }
}

private fun spreadsheetActualVectorDimension(value: Any?): Int? = when (unwrapSpreadsheetValue(value)) {
    is Vec2 -> 2
    is Vec3 -> 3
    is Vec4 -> 4
    else -> null
}

private fun spreadsheetBinaryComponent(op: SpreadsheetBinaryOp, left: Double, right: Double): Double = when (op) {
    SpreadsheetBinaryOp.ADD -> left + right
    SpreadsheetBinaryOp.SUBTRACT -> left - right
    SpreadsheetBinaryOp.MULTIPLY -> left * right
    SpreadsheetBinaryOp.DIVIDE -> left / right
    SpreadsheetBinaryOp.REMAINDER -> left % right
}

private fun spreadsheetListElements(value: Any?): List<Any?>? {
    val normalized = unwrapSpreadsheetValue(value)
    return when (normalized) {
        is Iterable<*> -> normalized.map { unwrapSpreadsheetValue(it) }
        is Array<*> -> normalized.map { unwrapSpreadsheetValue(it) }
        is IntArray -> normalized.map { it }
        is LongArray -> normalized.map { it }
        is FloatArray -> normalized.map { it }
        is DoubleArray -> normalized.toList()
        is ShortArray -> normalized.map { it }
        is ByteArray -> normalized.map { it }
        is BooleanArray -> normalized.map { it }
        else -> null
    }
}

private fun spreadsheetBroadcastLists(
    left: List<Any?>,
    right: List<Any?>,
    op: (Any?, Any?) -> Any?
): List<Any?> {
    if (left.isEmpty() || right.isEmpty()) return emptyList()
    if (left.size == right.size) return left.indices.map { index -> unwrapSpreadsheetValue(op(left[index], right[index])) }
    if (left.size == 1) return right.map { unwrapSpreadsheetValue(op(left.first(), it)) }
    if (right.size == 1) return left.map { unwrapSpreadsheetValue(op(it, right.first())) }
    error("Cannot broadcast lists of size ${left.size} and ${right.size}")
}

private object SpreadsheetDynamicRegistry {
    private data class BinaryRule(
        val name: String,
        val matches: (Any?, Any?) -> Boolean,
        val apply: (Any?, Any?) -> Any?
    )

    private data class CoercionRule<T>(
        val name: String,
        val apply: (Any?) -> T?
    )

    private val binaryRules = SpreadsheetBinaryOp.entries.associateWith { mutableListOf<BinaryRule>() }
    private val scalarRules = mutableListOf<CoercionRule<Double>>()
    private val vec2Rules = mutableListOf<CoercionRule<Vec2>>()
    private val vec3Rules = mutableListOf<CoercionRule<Vec3>>()
    private val vec4Rules = mutableListOf<CoercionRule<Vec4>>()

    init {
        registerBuiltins()
    }

    fun binary(op: SpreadsheetBinaryOp, left: Any?, right: Any?): Any? {
        val normalizedLeft = unwrapSpreadsheetValue(left)
        val normalizedRight = unwrapSpreadsheetValue(right)
        for (rule in binaryRules.getValue(op)) {
            if (!rule.matches(normalizedLeft, normalizedRight)) continue
            return unwrapSpreadsheetValue(rule.apply(normalizedLeft, normalizedRight))
        }
        return fallbackBinary(op, normalizedLeft, normalizedRight)
    }

    fun scalar(value: Any?): Double? = coerce(scalarRules, unwrapSpreadsheetValue(value))

    fun requireScalar(value: Any?): Double =
        scalar(value) ?: error("Expected a scalar-like value, found '${formatSpreadsheetValue(value)}'")

    fun vec2(value: Any?): Vec2? = coerce(vec2Rules, unwrapSpreadsheetValue(value))

    fun requireVec2(value: Any?): Vec2 =
        vec2(value) ?: error("Expected a Vec2-like value, found '${formatSpreadsheetValue(value)}'")

    fun vec3(value: Any?): Vec3? = coerce(vec3Rules, unwrapSpreadsheetValue(value))

    fun requireVec3(value: Any?): Vec3 =
        vec3(value) ?: error("Expected a Vec3-like value, found '${formatSpreadsheetValue(value)}'")

    fun vec4(value: Any?): Vec4? = coerce(vec4Rules, unwrapSpreadsheetValue(value))

    fun requireVec4(value: Any?): Vec4 =
        vec4(value) ?: error("Expected a Vec4-like value, found '${formatSpreadsheetValue(value)}'")

    fun component(value: Any?, index: Int): Any? {
        val normalized = unwrapSpreadsheetValue(value)
        return when (normalized) {
            null -> null
            is Vec2 -> listOf(normalized.x.toDouble(), normalized.y.toDouble()).getOrNull(index)
            is Vec3 -> listOf(normalized.x.toDouble(), normalized.y.toDouble(), normalized.z.toDouble()).getOrNull(index)
            is Vec4 -> listOf(normalized.x.toDouble(), normalized.y.toDouble(), normalized.z.toDouble(), normalized.w.toDouble()).getOrNull(index)
            is FLine -> {
                val summary = summarizeFLine(normalized)
                listOf(summary.x, summary.y, summary.w, summary.h).getOrNull(index)
            }

            else -> spreadsheetListElements(normalized)?.getOrNull(index)
                ?: spreadsheetLooseNumericComponents(normalized)?.getOrNull(index)
                ?: if (index == 0) scalar(normalized) else null
        }
    }

    private fun registerBuiltins() {
        registerScalar("number") { value ->
            when (value) {
                is Double -> value
                is Float -> value.toDouble()
                is Int -> value.toDouble()
                is Long -> value.toDouble()
                is Short -> value.toDouble()
                is Byte -> value.toDouble()
                is Number -> value.toDouble()
                else -> null
            }
        }
        registerScalar("boolean") { value ->
            if (value is Boolean) {
                if (value) 1.0 else 0.0
            } else {
                null
            }
        }
        registerScalar("string-double") { value ->
            if (value is String) value.trim().toDoubleOrNull() else null
        }
        registerScalar("vector-length") { value ->
            when (value) {
                is Vec2 -> value.length().toDouble()
                is Vec3 -> value.length().toDouble()
                is Vec4 -> value.length().toDouble()
                else -> null
            }
        }
        registerScalar("fline-bounds") { value ->
            if (value is FLine) summarizeFLine(value).diagonal else null
        }
        registerScalar("numeric-components") { value ->
            val components = spreadsheetLooseNumericComponents(value) ?: return@registerScalar null
            when (components.size) {
                0 -> 0.0
                1 -> components.first()
                else -> sqrt(components.sumOf { it * it })
            }
        }

        registerVec2("vec2") { value ->
            when (value) {
                is Vec2 -> Vec2(value)
                is Vec3 -> Vec2(value.x, value.y)
                is Vec4 -> Vec2(value.x, value.y)
                is FLine -> summarizeFLine(value).center2()
                else -> spreadsheetVectorFromLooseComponents(value, 2)?.let { Vec2(it[0], it[1]) }
            }
        }
        registerVec3("vec3") { value ->
            when (value) {
                is Vec2 -> Vec3(value.x, value.y, 0f)
                is Vec3 -> Vec3(value)
                is Vec4 -> Vec3(value.x, value.y, value.z)
                is FLine -> summarizeFLine(value).center3()
                else -> spreadsheetVectorFromLooseComponents(value, 3)?.let { Vec3(it[0], it[1], it[2]) }
            }
        }
        registerVec4("vec4") { value ->
            when (value) {
                is Vec2 -> Vec4(value.x, value.y, 0f, 0f)
                is Vec3 -> Vec4(value.x, value.y, value.z, 0f)
                is Vec4 -> Vec4(value)
                is FLine -> summarizeFLine(value).bounds4()
                else -> spreadsheetVectorFromLooseComponents(value, 4)?.let { Vec4(it[0], it[1], it[2], it[3]) }
            }
        }

        SpreadsheetBinaryOp.entries.forEach { op ->
            registerBinary(
                op,
                "list-broadcast",
                { left, right -> spreadsheetListElements(left) != null || spreadsheetListElements(right) != null },
                { left, right ->
                val leftList = spreadsheetListElements(left)
                val rightList = spreadsheetListElements(right)
                when {
                    leftList != null && rightList != null -> spreadsheetBroadcastLists(leftList, rightList) { a, b -> binary(op, a, b) }
                    leftList != null -> leftList.map { unwrapSpreadsheetValue(binary(op, it, right)) }
                    rightList != null -> rightList.map { unwrapSpreadsheetValue(binary(op, left, it)) }
                    else -> emptyList<Any?>()
                }
                }
            )
        }

        registerBinary(SpreadsheetBinaryOp.ADD, "fline-left", { left, _ -> left is FLine }) { left, right ->
            applyFLineBinary(left as FLine, SpreadsheetBinaryOp.ADD, right)
        }
        registerBinary(SpreadsheetBinaryOp.SUBTRACT, "fline-left", { left, _ -> left is FLine }) { left, right ->
            applyFLineBinary(left as FLine, SpreadsheetBinaryOp.SUBTRACT, right)
        }
        registerBinary(SpreadsheetBinaryOp.MULTIPLY, "fline-left", { left, _ -> left is FLine }) { left, right ->
            applyFLineBinary(left as FLine, SpreadsheetBinaryOp.MULTIPLY, right)
        }
        registerBinary(SpreadsheetBinaryOp.DIVIDE, "fline-left", { left, _ -> left is FLine }) { left, right ->
            applyFLineBinary(left as FLine, SpreadsheetBinaryOp.DIVIDE, right)
        }
        registerBinary(SpreadsheetBinaryOp.REMAINDER, "fline-left", { left, _ -> left is FLine }) { left, right ->
            applyFLineBinary(left as FLine, SpreadsheetBinaryOp.REMAINDER, right)
        }
        registerBinary(SpreadsheetBinaryOp.ADD, "fline-right", { _, right -> right is FLine }) { left, right ->
            applyFLineBinary(right as FLine, SpreadsheetBinaryOp.ADD, left)
        }
        registerBinary(SpreadsheetBinaryOp.MULTIPLY, "fline-right", { _, right -> right is FLine }) { left, right ->
            applyFLineBinary(right as FLine, SpreadsheetBinaryOp.MULTIPLY, left)
        }

        SpreadsheetBinaryOp.entries.forEach { op ->
            registerBinary(
                op,
                "vector",
                { left, right -> spreadsheetActualVectorDimension(left) != null || spreadsheetActualVectorDimension(right) != null },
                { left, right -> applyVectorBinary(op, left, right) }
            )
        }

        registerScalar("quantity") { value ->
            when (value) {
                is Quantity -> value.displayValue
                is Expression -> value.value
                else -> null
            }
        }

        SpreadsheetBinaryOp.entries.forEach { op ->
            registerBinary(
                op,
                "quantity",
                { left, right -> left is Quantity || left is Expression || right is Quantity || right is Expression },
                { left, right -> applyQuantityBinary(op, left, right) }
            )
        }
    }

    private fun fallbackBinary(op: SpreadsheetBinaryOp, left: Any?, right: Any?): Any? = when (op) {
        SpreadsheetBinaryOp.ADD -> {
            val leftNumeric = scalar(left)
            val rightNumeric = scalar(right)
            when {
                leftNumeric != null && rightNumeric != null -> leftNumeric + rightNumeric
                left == null -> right
                right == null -> left
                else -> "${formatSpreadsheetValue(left)}${formatSpreadsheetValue(right)}"
            }
        }

        SpreadsheetBinaryOp.SUBTRACT -> requireScalar(left) - requireScalar(right)
        SpreadsheetBinaryOp.MULTIPLY -> requireScalar(left) * requireScalar(right)
        SpreadsheetBinaryOp.DIVIDE -> requireScalar(left) / requireScalar(right)
        SpreadsheetBinaryOp.REMAINDER -> requireScalar(left) % requireScalar(right)
    }

    private fun applyFLineBinary(line: FLine, op: SpreadsheetBinaryOp, other: Any?): Any {
        val vectorDimension = spreadsheetActualVectorDimension(other)
        val scalarValue = scalar(other)?.toFloat() ?: 0f
        return when (op) {
            SpreadsheetBinaryOp.ADD -> when (vectorDimension) {
                2 -> line + requireVec2(other)
                3, 4 -> line + requireVec3(other)
                else -> line + Vec3(scalarValue, scalarValue, scalarValue)
            }

            SpreadsheetBinaryOp.SUBTRACT -> when (vectorDimension) {
                2 -> line - requireVec2(other)
                3, 4 -> line - requireVec3(other)
                else -> line - Vec3(scalarValue, scalarValue, scalarValue)
            }

            SpreadsheetBinaryOp.MULTIPLY -> when (vectorDimension) {
                2 -> line * requireVec2(other)
                3, 4 -> line * requireVec3(other)
                else -> line * requireScalar(other)
            }

            SpreadsheetBinaryOp.DIVIDE -> when (vectorDimension) {
                2 -> {
                    val divisor = requireVec2(other)
                    line.transform { point -> Vec3(point.x / divisor.x, point.y / divisor.y, point.z) }
                }

                3, 4 -> {
                    val divisor = requireVec3(other)
                    line.transform { point -> Vec3(point.x / divisor.x, point.y / divisor.y, point.z / divisor.z) }
                }

                else -> {
                    val divisor = requireScalar(other).toFloat()
                    line.transform { point -> Vec3(point.x / divisor, point.y / divisor, point.z / divisor) }
                }
            }

            SpreadsheetBinaryOp.REMAINDER -> when (vectorDimension) {
                2 -> {
                    val divisor = requireVec2(other)
                    line.transform { point -> Vec3(point.x % divisor.x, point.y % divisor.y, point.z) }
                }

                3, 4 -> {
                    val divisor = requireVec3(other)
                    line.transform { point -> Vec3(point.x % divisor.x, point.y % divisor.y, point.z % divisor.z) }
                }

                else -> {
                    val divisor = requireScalar(other).toFloat()
                    line.transform { point -> Vec3(point.x % divisor, point.y % divisor, point.z % divisor) }
                }
            }
        }
    }

    private fun toExpression(value: Any?): Expression? = when (value) {
        is Quantity -> value.expression
        is Expression -> value
        is Number -> Expression(value.toDouble(), eu.sirotin.kotunil.core.Dimensions(emptySet()))
        else -> {
            val d = scalar(value)
            if (d != null) Expression(d, eu.sirotin.kotunil.core.Dimensions(emptySet())) else null
        }
    }

    private fun quantityDisplayUnit(value: Any?): Expression? = when (value) {
        is Quantity -> value.displayUnit
        else -> null
    }

    private fun applyQuantityBinary(op: SpreadsheetBinaryOp, left: Any?, right: Any?): Any? {
        val leftExpr = toExpression(left) ?: error("Cannot convert '${formatSpreadsheetValue(left)}' to a unit expression")
        val rightExpr = toExpression(right) ?: error("Cannot convert '${formatSpreadsheetValue(right)}' to a unit expression")
        return when (op) {
            SpreadsheetBinaryOp.ADD -> Quantity(leftExpr + rightExpr, quantityDisplayUnit(left) ?: quantityDisplayUnit(right))
            SpreadsheetBinaryOp.SUBTRACT -> Quantity(leftExpr - rightExpr, quantityDisplayUnit(left) ?: quantityDisplayUnit(right))
            SpreadsheetBinaryOp.MULTIPLY -> Quantity(leftExpr * rightExpr)
            SpreadsheetBinaryOp.DIVIDE -> Quantity(leftExpr / rightExpr)
            SpreadsheetBinaryOp.REMAINDER -> Quantity(leftExpr % rightExpr)
        }
    }

    private fun applyVectorBinary(op: SpreadsheetBinaryOp, left: Any?, right: Any?): Any {
        val dimension = maxOf(spreadsheetActualVectorDimension(left) ?: 0, spreadsheetActualVectorDimension(right) ?: 0)
        return when (dimension) {
            2 -> {
                val a = requireVec2(left)
                val b = requireVec2(right)
                Vec2(
                    spreadsheetBinaryComponent(op, a.x.toDouble(), b.x.toDouble()).toFloat(),
                    spreadsheetBinaryComponent(op, a.y.toDouble(), b.y.toDouble()).toFloat()
                )
            }

            3 -> {
                val a = requireVec3(left)
                val b = requireVec3(right)
                Vec3(
                    spreadsheetBinaryComponent(op, a.x.toDouble(), b.x.toDouble()).toFloat(),
                    spreadsheetBinaryComponent(op, a.y.toDouble(), b.y.toDouble()).toFloat(),
                    spreadsheetBinaryComponent(op, a.z.toDouble(), b.z.toDouble()).toFloat()
                )
            }

            else -> {
                val a = requireVec4(left)
                val b = requireVec4(right)
                Vec4(
                    spreadsheetBinaryComponent(op, a.x.toDouble(), b.x.toDouble()).toFloat(),
                    spreadsheetBinaryComponent(op, a.y.toDouble(), b.y.toDouble()).toFloat(),
                    spreadsheetBinaryComponent(op, a.z.toDouble(), b.z.toDouble()).toFloat(),
                    spreadsheetBinaryComponent(op, a.w.toDouble(), b.w.toDouble()).toFloat()
                )
            }
        }
    }

    private fun registerBinary(
        op: SpreadsheetBinaryOp,
        name: String,
        matches: (Any?, Any?) -> Boolean,
        apply: (Any?, Any?) -> Any?
    ) {
        binaryRules.getValue(op) += BinaryRule(name, matches, apply)
    }

    private fun registerScalar(name: String, apply: (Any?) -> Double?) {
        scalarRules += CoercionRule(name, apply)
    }

    private fun registerVec2(name: String, apply: (Any?) -> Vec2?) {
        vec2Rules += CoercionRule(name, apply)
    }

    private fun registerVec3(name: String, apply: (Any?) -> Vec3?) {
        vec3Rules += CoercionRule(name, apply)
    }

    private fun registerVec4(name: String, apply: (Any?) -> Vec4?) {
        vec4Rules += CoercionRule(name, apply)
    }

    private fun <T> coerce(rules: List<CoercionRule<T>>, value: Any?): T? {
        for (rule in rules) {
            val result = runCatching { rule.apply(value) }.getOrNull()
            if (result != null) return result
        }
        return null
    }
}

class SpreadsheetScalar internal constructor(val raw: Any?) : Comparable<Any?> {
    private fun otherValue(other: Any?): Any? = unwrapSpreadsheetValue(other)

    val d: Double
        get() = SpreadsheetDynamicRegistry.requireScalar(raw)

    val v2: Vec2
        get() = SpreadsheetDynamicRegistry.requireVec2(raw)

    val v3: Vec3
        get() = SpreadsheetDynamicRegistry.requireVec3(raw)

    val v4: Vec4
        get() = SpreadsheetDynamicRegistry.requireVec4(raw)

    val x: SpreadsheetScalar
        get() = SpreadsheetScalar(SpreadsheetDynamicRegistry.component(raw, 0))

    val y: SpreadsheetScalar
        get() = SpreadsheetScalar(SpreadsheetDynamicRegistry.component(raw, 1))

    val z: SpreadsheetScalar
        get() = SpreadsheetScalar(SpreadsheetDynamicRegistry.component(raw, 2))

    val w: SpreadsheetScalar
        get() = SpreadsheetScalar(SpreadsheetDynamicRegistry.component(raw, 3))

    operator fun get(index: Int): SpreadsheetScalar =
        SpreadsheetScalar(SpreadsheetDynamicRegistry.component(raw, index))

    operator fun plus(other: Any?): SpreadsheetScalar =
        SpreadsheetScalar(SpreadsheetDynamicRegistry.binary(SpreadsheetBinaryOp.ADD, raw, otherValue(other)))

    operator fun minus(other: Any?): SpreadsheetScalar =
        SpreadsheetScalar(SpreadsheetDynamicRegistry.binary(SpreadsheetBinaryOp.SUBTRACT, raw, otherValue(other)))

    operator fun times(other: Any?): SpreadsheetScalar =
        SpreadsheetScalar(SpreadsheetDynamicRegistry.binary(SpreadsheetBinaryOp.MULTIPLY, raw, otherValue(other)))

    operator fun div(other: Any?): SpreadsheetScalar =
        SpreadsheetScalar(SpreadsheetDynamicRegistry.binary(SpreadsheetBinaryOp.DIVIDE, raw, otherValue(other)))

    operator fun rem(other: Any?): SpreadsheetScalar =
        SpreadsheetScalar(SpreadsheetDynamicRegistry.binary(SpreadsheetBinaryOp.REMAINDER, raw, otherValue(other)))

    operator fun unaryMinus(): SpreadsheetScalar {
        val value = unwrapSpreadsheetValue(raw)
        return when (spreadsheetActualVectorDimension(value)) {
            2 -> SpreadsheetScalar(Vec2(SpreadsheetDynamicRegistry.requireVec2(value)).mul(-1f))
            3 -> SpreadsheetScalar(Vec3(SpreadsheetDynamicRegistry.requireVec3(value)).mul(-1f))
            4 -> SpreadsheetScalar(Vec4(SpreadsheetDynamicRegistry.requireVec4(value)).mul(-1f))
            else -> SpreadsheetScalar(-SpreadsheetDynamicRegistry.requireScalar(value))
        }
    }

    operator fun unaryPlus(): SpreadsheetScalar = this

    override fun compareTo(other: Any?): Int {
        val left = unwrapSpreadsheetValue(raw)
        val right = otherValue(other)
        val leftNumeric = SpreadsheetDynamicRegistry.scalar(left)
        val rightNumeric = SpreadsheetDynamicRegistry.scalar(right)
        return if (leftNumeric != null && rightNumeric != null) {
            leftNumeric.compareTo(rightNumeric)
        } else {
            formatSpreadsheetValue(left).compareTo(formatSpreadsheetValue(right))
        }
    }

    override fun equals(other: Any?): Boolean = unwrapSpreadsheetValue(raw) == otherValue(other)

    override fun hashCode(): Int = unwrapSpreadsheetValue(raw)?.hashCode() ?: 0

    override fun toString(): String = formatSpreadsheetValue(raw)
}

operator fun Number.plus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).plus(other)
operator fun Number.minus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).minus(other)
operator fun Number.times(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).times(other)
operator fun Number.div(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).div(other)
operator fun Number.rem(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).rem(other)

operator fun Vec2.plus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).plus(other)
operator fun Vec2.minus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).minus(other)
operator fun Vec2.times(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).times(other)
operator fun Vec2.div(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).div(other)
operator fun Vec2.rem(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).rem(other)

operator fun Vec3.plus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).plus(other)
operator fun Vec3.minus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).minus(other)
operator fun Vec3.times(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).times(other)
operator fun Vec3.div(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).div(other)
operator fun Vec3.rem(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).rem(other)

operator fun Vec4.plus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).plus(other)
operator fun Vec4.minus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).minus(other)
operator fun Vec4.times(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).times(other)
operator fun Vec4.div(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).div(other)
operator fun Vec4.rem(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).rem(other)

operator fun FLine.plus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).plus(other)
operator fun FLine.minus(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).minus(other)
operator fun FLine.times(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).times(other)
operator fun FLine.div(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).div(other)
operator fun FLine.rem(other: SpreadsheetScalar): SpreadsheetScalar = SpreadsheetScalar(this).rem(other)

fun spreadsheetValue(value: Any?): SpreadsheetScalar = SpreadsheetScalar(value)
fun sheetValue(value: Any?): SpreadsheetScalar = spreadsheetValue(value)

class SpreadsheetBoxBinding internal constructor(
    private val touchMember: (String, SpreadsheetBoxMemberAccess) -> Unit,
    private val memberNamesProvider: () -> List<String>,
    private val target: Box
) {
    val id: String
        get() = target.id

    val name: String
        get() = target.name.ifBlank { target.id }

    operator fun get(member: String): SpreadsheetScalar {
        touchMember(member, SpreadsheetBoxMemberAccess.GET)
        target.ki.context.vars[member]?.value?.let { return SpreadsheetScalar(unwrapSpreadsheetValue(it)) }
        return SpreadsheetScalar(unwrapSpreadsheetValue(target.getPropertyRaw(member, null)))
    }

    fun prop(member: String): SpreadsheetScalar {
        touchMember(member, SpreadsheetBoxMemberAccess.PROP)
        return SpreadsheetScalar(unwrapSpreadsheetValue(target.getPropertyRaw(member, null)))
    }

    fun variable(member: String): SpreadsheetScalar {
        touchMember(member, SpreadsheetBoxMemberAccess.VARIABLE)
        return SpreadsheetScalar(unwrapSpreadsheetValue(target.ki.context.vars[member]?.value))
    }

    fun keys(): List<String> = memberNamesProvider()

    override fun toString(): String = "box(${name.ifBlank { id }})"
}

internal class SpreadsheetRuntime(
    private val info: SessionInformation,
    private val currentBox: Box,
    private val evaluateRef: (CellCoord) -> Any?
) {
    private val boxDependencies = linkedSetOf<SpreadsheetBoxDependency>()

    fun cell(ref: String): SpreadsheetScalar {
        val coord = cellRefToCoord(ref) ?: error("Unknown cell reference '$ref'")
        return SpreadsheetScalar(unwrapSpreadsheetValue(evaluateRef(coord)))
    }

    fun range(ref: String): List<SpreadsheetScalar> {
        val parts = ref.split(':', limit = 2)
        if (parts.size != 2) error("Malformed range '$ref'")
        val start = cellRefToCoord(parts[0]) ?: error("Unknown cell reference '${parts[0]}'")
        val end = cellRefToCoord(parts[1]) ?: error("Unknown cell reference '${parts[1]}'")
        val rowStart = minOf(start.row, end.row)
        val rowEnd = maxOf(start.row, end.row)
        val colStart = minOf(start.col, end.col)
        val colEnd = maxOf(start.col, end.col)
        val values = mutableListOf<SpreadsheetScalar>()
        for (row in rowStart..rowEnd) {
            for (col in colStart..colEnd) {
                val value = unwrapSpreadsheetValue(evaluateRef(CellCoord(row, col)))
                if (!(value == null || value == "")) {
                    values += SpreadsheetScalar(value)
                }
            }
        }
        return values
    }

    fun box(ref: String): SpreadsheetBoxBinding {
        val box = resolveBoxReference(ref)
            ?: error("Unknown box reference '$ref'")
        return bindSpreadsheetBox(this, box)
    }

    fun self(): SpreadsheetBoxBinding = bindSpreadsheetBox(this, currentBox)

    fun touchBoxDependency(box: Box, member: String, access: SpreadsheetBoxMemberAccess) {
        boxDependencies += SpreadsheetBoxDependency(boxId = box.id, member = member, access = access)
    }

    fun memberNamesFor(box: Box): List<String> =
        buildSet {
            addAll(box.properties.keys)
            addAll(box.ki.context.vars.keys)
        }.sorted()

    fun boxDependencies(): Set<SpreadsheetBoxDependency> = boxDependencies.toSet()

    private fun resolveBoxReference(ref: String): Box? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "self") return currentBox
        info.boxes[trimmed]?.let { return it }
        if (trimmed == "root") return info.root
        val matches = info.boxes.values.filter { it.name.trim() == trimmed }
        return if (matches.size == 1) matches.first() else null
    }
}

private fun bindSpreadsheetBox(runtime: SpreadsheetRuntime, target: Box): SpreadsheetBoxBinding =
    SpreadsheetBoxBinding(
        touchMember = { member, access -> runtime.touchBoxDependency(target, member, access) },
        memberNamesProvider = { runtime.memberNamesFor(target) },
        target = target
    )

private class SpreadsheetEvaluator(private val cachePrefix: String) {
    private val lock = Any()
    private val compiled = ConcurrentHashMap<String, KotlinInterface.CompiledSnippet>()
    private val compiler = KotlinInterface(filePrefix = cachePrefix)

    fun evaluate(expression: String, runtime: SpreadsheetRuntime): EvalResult {
        val source = buildSource(expression)
        val snippet = synchronized(lock) {
            compiled[source] ?: runBlocking {
                compiler.compileSnippet(source, implicits = emptyList(), onCompileError = {})
            }?.also {
                compiled[source] = it
            }
        } ?: return EvalResult(value = null, errorDetail = "Formula compilation failed")

        return withSpreadsheetRuntime(runtime) {
            try {
                val result = snippet.evalAndWait(
                    updateContext = false,
                    implicits = emptyList(),
                    onEvalError = {},
                    onEvalException = {}
                )
                when (result) {
                    is ResultValue.Value -> EvalResult(result.value)
                    is ResultValue.Error -> EvalResult(null, result.error.message ?: result.error.toString())
                    is ResultValue.NotEvaluated -> EvalResult(null, "Formula was not evaluated")
                    null -> EvalResult(null, "Formula evaluation returned no result")
                    else -> EvalResult(null, result.toString())
                }
            } catch (t: Throwable) {
                EvalResult(null, t.message ?: t.toString())
            }
        }
    }

    private fun buildSource(expression: String): String = """
        import afield.*
        import afield.graphics.*
        import afield.stdlib.*
        import kotlin.math.*
        import eu.sirotin.kotunil.core.*
        import eu.sirotin.kotunil.base.*
        import eu.sirotin.kotunil.derived.*
        import eu.sirotin.kotunil.specialunits.*
        run {
            val self = afield.SpreadsheetEvalHelpers.self
            fun cell(ref: String) = afield.SpreadsheetEvalHelpers.cell(ref)
            fun range(ref: String) = afield.SpreadsheetEvalHelpers.range(ref)
            fun box(ref: String) = afield.SpreadsheetEvalHelpers.box(ref)
            fun sum(values: Any?) = afield.SpreadsheetEvalHelpers.sum(values)
            fun avg(values: Any?) = afield.SpreadsheetEvalHelpers.avg(values)
            fun mean(values: Any?) = afield.SpreadsheetEvalHelpers.mean(values)
            fun min(values: Any?) = afield.SpreadsheetEvalHelpers.min(values)
            fun max(values: Any?) = afield.SpreadsheetEvalHelpers.max(values)
            fun count(values: Any?) = afield.SpreadsheetEvalHelpers.count(values)
            fun nonEmpty(values: Any?) = afield.SpreadsheetEvalHelpers.nonEmpty(values)
            $expression
        }
    """.trimIndent()
}

private class SpreadsheetDocument(
    private val key: SpreadsheetKey
) {
    private val evaluator = SpreadsheetEvaluator(
        cachePrefix = "sheet_${safeForId(key.uid)}_${safeForId(key.property)}"
    )
    private val lock = Any()
    private var storedState: SpreadsheetStoredState = normalizeStoredState(null)
    private var persistedJson: String? = null
    private var version: Long = 0
    private var cachedSnapshot: SpreadsheetSnapshot? = null
    private var cachedEvalCells: Map<String, EvalCell> = emptyMap()
    private var lastExternalBoxDeps: Set<SpreadsheetBoxDependency> = emptySet()
    private var lastObservedExternalValues: Map<SpreadsheetBoxDependency, String> = emptyMap()
    val subscribers: MutableSet<SpreadsheetClient> = CopyOnWriteArraySet()

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    fun removeClient(clientKey: Any) {
        val matches = subscribers.filter { it.key === clientKey }.toSet()
        if (matches.isNotEmpty()) {
            subscribers.removeAll(matches)
        }
    }

    fun snapshot(box: Box, info: SessionInformation, forceReload: Boolean = false, forceRecompute: Boolean = false): SpreadsheetSnapshot {
        synchronized(lock) {
            reloadFromBox(box, forceReload)
            if (cachedSnapshot == null || forceRecompute) {
                if (forceRecompute) version += 1
                cachedSnapshot = computeSnapshot(box, info)
            }
            return cachedSnapshot!!
        }
    }

    fun cellValue(
        box: Box,
        info: SessionInformation,
        ref: String,
        forceReload: Boolean = false,
        forceRecompute: Boolean = true
    ): Any? {
        synchronized(lock) {
            reloadFromBox(box, forceReload)
            val coord = cellRefToCoord(ref.trim()) ?: error("Unknown spreadsheet cell reference '$ref'")
            if (coord.row !in 0 until storedState.rows || coord.col !in 0 until storedState.cols) {
                error("Cell ${cellLabel(coord.row, coord.col)} is outside the sheet")
            }
            if (cachedSnapshot == null || forceRecompute) {
                if (forceRecompute) version += 1
                cachedSnapshot = computeSnapshot(box, info)
            }
            return cachedEvalCells["${coord.row}:${coord.col}"]?.value ?: ""
        }
    }

    fun update(box: Box, info: SessionInformation, nextState: SpreadsheetStoredState): SpreadsheetSnapshot {
        synchronized(lock) {
            storedState = writeStoredSpreadsheetState(box, key.property, nextState)
            persistedJson = encodeStoredSpreadsheetState(storedState)
            version += 1
            cachedSnapshot = computeSnapshot(box, info)
            return cachedSnapshot!!
        }
    }

    fun pollExternalDependencies(box: Box, info: SessionInformation): SpreadsheetSnapshot? {
        synchronized(lock) {
            if (lastExternalBoxDeps.isEmpty()) {
                lastObservedExternalValues = emptyMap()
                return null
            }
            val observedNow = captureExternalDependencyFingerprints(info)
            if (observedNow == lastObservedExternalValues) return null
            version += 1
            cachedSnapshot = computeSnapshot(box, info)
            return cachedSnapshot!!
        }
    }

    private fun reloadFromBox(box: Box, forceReload: Boolean) {
        val raw = box.getPropertyRaw(key.property, null) as? String
        if (!forceReload && raw == persistedJson) return
        persistedJson = raw
        storedState = decodeStoredState(raw)
        cachedSnapshot = null
        cachedEvalCells = emptyMap()
        version += 1
    }

    private fun computeSnapshot(box: Box, info: SessionInformation): SpreadsheetSnapshot {
        val cellInputs = mutableMapOf<String, String>()
        storedState.cells.forEach { cell ->
            if (cell.row in 0 until storedState.rows && cell.col in 0 until storedState.cols) {
                cellInputs["${cell.row}:${cell.col}"] = cell.input
            }
        }

        val memo = mutableMapOf<String, EvalCell>()
        val visiting = mutableSetOf<String>()
        val externalDeps = linkedSetOf<SpreadsheetBoxDependency>()

        fun evaluateCell(row: Int, col: Int): EvalCell {
            val keyString = "$row:$col"
            memo[keyString]?.let { return it }
            if (keyString in visiting) {
                return EvalCell(
                    input = cellInputs[keyString] ?: "",
                    type = "formula",
                    value = null,
                    display = "#CYCLE",
                    errorDetail = "Circular reference detected at ${cellLabel(row, col)}"
                ).also { memo[keyString] = it }
            }

            val input = cellInputs[keyString].orEmpty()
            val type = classifyCellInput(input)
            if (type == "empty") {
                return EvalCell(input, type, "", "", null).also { memo[keyString] = it }
            }
            if (type == "text") {
                return EvalCell(input, type, input, input, null).also { memo[keyString] = it }
            }
            if (type == "number") {
                val number = input.trim().toDoubleOrNull()
                val display = formatSpreadsheetValue(number)
                return EvalCell(input, type, number, display, null).also { memo[keyString] = it }
            }

            visiting += keyString
            return try {
                val expr = input.removePrefix("=").trim()
                if (expr.isEmpty()) {
                    EvalCell(input, "formula", "", "", null)
                } else {
                    val rewritten = rewriteSpreadsheetFormula(expr)
                    val runtime = SpreadsheetRuntime(info, box) { coord ->
                        if (coord.row !in 0 until storedState.rows || coord.col !in 0 until storedState.cols) {
                            error("Cell ${cellLabel(coord.row, coord.col)} is outside the sheet")
                        }
                        evaluateCell(coord.row, coord.col).value
                    }
                    val evalResult = evaluator.evaluate(rewritten, runtime)
                    externalDeps += runtime.boxDependencies()
                    if (evalResult.errorDetail != null) {
                        EvalCell(input, "formula", null, "#ERR", evalResult.errorDetail)
                    } else {
                        EvalCell(
                            input = input,
                            type = "formula",
                            value = evalResult.value,
                            display = formatSpreadsheetValue(evalResult.value),
                            errorDetail = null,
                            htmlDisplay = spreadsheetHtmlDisplay(evalResult.value)
                        )
                    }
                }
            } catch (t: Throwable) {
                EvalCell(input, "formula", null, "#ERR", t.message ?: t.toString())
            }.also {
                memo[keyString] = it
                visiting -= keyString
            }
        }

        for (row in 0 until storedState.rows) {
            for (col in 0 until storedState.cols) {
                val maybeInput = cellInputs["$row:$col"]
                if (!maybeInput.isNullOrBlank()) {
                    evaluateCell(row, col)
                }
            }
        }

        lastExternalBoxDeps = externalDeps.toSet()
        lastObservedExternalValues = captureExternalDependencyFingerprints(info)
        cachedEvalCells = memo.toMap()

        val cells = memo.entries
            .mapNotNull { (keyString, cell) ->
                val parts = keyString.split(':', limit = 2)
                val row = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val col = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                if (cell.type == "empty" && cell.display.isEmpty()) return@mapNotNull null
                SpreadsheetCellSnapshot(
                    row = row,
                    col = col,
                    input = cell.input,
                    type = cell.type,
                    display = cell.display,
                    errorDetail = cell.errorDetail,
                    htmlDisplay = cell.htmlDisplay
                )
            }
            .sortedWith(compareBy<SpreadsheetCellSnapshot> { it.row }.thenBy { it.col })

        return SpreadsheetSnapshot(
            uid = key.uid,
            property = key.property,
            rows = storedState.rows,
            cols = storedState.cols,
            colWidths = storedState.colWidths.map { it.toInt() },
            rowHeights = storedState.rowHeights.map { it.toInt() },
            cells = cells,
            version = version
        )
    }

    private fun captureExternalDependencyFingerprints(info: SessionInformation): Map<SpreadsheetBoxDependency, String> =
        lastExternalBoxDeps.associateWith { dependency ->
            val target = when (dependency.boxId) {
                "root" -> info.root
                else -> info.boxes[dependency.boxId]
            }
            externalDependencyFingerprint(target, dependency)
        }

    private fun externalDependencyFingerprint(target: Box?, dependency: SpreadsheetBoxDependency): String {
        if (target == null) {
            return "missing:${dependency.boxId}:${dependency.member}:${dependency.access}"
        }
        if (target.getPropertyRaw("_tombstone").isTrue()) {
            return "tombstone:${dependency.boxId}:${dependency.member}:${dependency.access}"
        }
        val value = when (dependency.access) {
            SpreadsheetBoxMemberAccess.GET -> target.ki.context.vars[dependency.member]?.value
                ?: target.getPropertyRaw(dependency.member, null)

            SpreadsheetBoxMemberAccess.PROP -> target.getPropertyRaw(dependency.member, null)
            SpreadsheetBoxMemberAccess.VARIABLE -> target.ki.context.vars[dependency.member]?.value
        }
        return fingerprintSpreadsheetDependencyValue(value)
    }
}

private object SpreadsheetService {
    private val documents = ConcurrentHashMap<SpreadsheetKey, SpreadsheetDocument>()

    fun detachClient(clientKey: Any) {
        documents.values.forEach { it.removeClient(clientKey) }
    }

    fun currentState(box: Box, property: String): SpreadsheetStoredState =
        decodeStoredState(box.getPropertyRaw(normalizedSpreadsheetProperty(property), null) as? String)

    fun snapshot(
        box: Box,
        info: SessionInformation,
        property: String,
        forceReload: Boolean = false,
        forceRecompute: Boolean = true
    ): SpreadsheetSnapshot {
        val normalizedProperty = normalizedSpreadsheetProperty(property)
        registerSpreadsheetProperty(normalizedProperty)
        val key = SpreadsheetKey(info.filename, box.id, normalizedProperty)
        val document = documents.computeIfAbsent(key) { SpreadsheetDocument(key) }
        return document.snapshot(box, info, forceReload = forceReload, forceRecompute = forceRecompute)
    }

    fun cellValue(
        box: Box,
        info: SessionInformation,
        property: String,
        ref: String,
        forceReload: Boolean = false,
        forceRecompute: Boolean = true
    ): Any? {
        val normalizedProperty = normalizedSpreadsheetProperty(property)
        registerSpreadsheetProperty(normalizedProperty)
        val key = SpreadsheetKey(info.filename, box.id, normalizedProperty)
        val document = documents.computeIfAbsent(key) { SpreadsheetDocument(key) }
        return document.cellValue(box, info, ref, forceReload = forceReload, forceRecompute = forceRecompute)
    }

    fun applyProgrammaticState(
        box: Box,
        info: SessionInformation,
        property: String,
        nextState: SpreadsheetStoredState,
        broadcast: Boolean = true
    ): SpreadsheetStoredState {
        val normalizedProperty = normalizedSpreadsheetProperty(property)
        registerSpreadsheetProperty(normalizedProperty)
        val normalizedState = normalizeStoredState(nextState)
        val key = SpreadsheetKey(info.filename, box.id, normalizedProperty)
        val document = documents[key]
        if (document == null) {
            return writeStoredSpreadsheetState(box, normalizedProperty, normalizedState)
        }
        val snapshot = document.update(box, info, normalizedState)
        if (broadcast) {
            broadcast(document, snapshot, key)
        }
        return normalizedState
    }

    fun handle(text: String, info: SessionInformation?, clientKey: Any, push: (String) -> Unit): String {
        val req = try {
            SpreadsheetProtocol.json.decodeFromString(SpreadsheetProtocol.Request.serializer(), text)
        } catch (t: Throwable) {
            lprintln("warning,spreadsheet,ws", "[sheet-handle] failed to decode request: ${t.message}")
            return ""
        }
        if (req.channel != null && req.channel != SPREADSHEET_CHANNEL) return ""

        lprintln("debug,spreadsheet,ws", "[sheet-handle] op=${req.op} reqId=${req.reqId} uid=${req.uid} property=${req.property}")

        val sessionInfo = info
            ?: return error(req.reqId, "Spreadsheet session is not attached yet. Wait for hello to complete and retry.")
        val uid = req.uid?.trim().orEmpty()
        if (uid.isEmpty()) {
            return error(req.reqId, "Missing spreadsheet box uid")
        }

        val normalizedProperty = normalizedSpreadsheetProperty(req.property)
        registerSpreadsheetProperty(normalizedProperty)

        val box = runCatching { box(uid, createIfMissing = false, root = sessionInfo) }
            .getOrElse { return error(req.reqId, "Unknown spreadsheet box '$uid'") }
        val key = SpreadsheetKey(sessionInfo.filename, uid, normalizedProperty)
        val document = documents.computeIfAbsent(key) { SpreadsheetDocument(key) }
        val client = SpreadsheetClient(clientKey, send = push)

        return try {
            val startMs = System.currentTimeMillis()
            val result = when (req.op) {
                "open" -> {
                    if (req.watch) document.subscribers += client
                    val snapshot = document.snapshot(box, sessionInfo, forceRecompute = true)
                    SpreadsheetProtocol.json.encodeToString(
                        SpreadsheetProtocol.SnapshotEnvelope.serializer(),
                        SpreadsheetProtocol.SnapshotEnvelope(reqId = req.reqId, snapshot = snapshot)
                    )
                }

                "update" -> {
                    if (req.watch) document.subscribers += client
                    val nextState = req.state ?: normalizeStoredState(null)
                    val snapshot = document.update(box, sessionInfo, nextState)
                    broadcast(document, snapshot, key)
                    SpreadsheetProtocol.json.encodeToString(
                        SpreadsheetProtocol.SnapshotEnvelope.serializer(),
                        SpreadsheetProtocol.SnapshotEnvelope(reqId = req.reqId, snapshot = snapshot)
                    )
                }

                "refresh" -> {
                    if (req.watch) document.subscribers += client
                    val snapshot = document.snapshot(box, sessionInfo, forceReload = true, forceRecompute = true)
                    broadcast(document, snapshot, key)
                    SpreadsheetProtocol.json.encodeToString(
                        SpreadsheetProtocol.SnapshotEnvelope.serializer(),
                        SpreadsheetProtocol.SnapshotEnvelope(reqId = req.reqId, snapshot = snapshot)
                    )
                }

                "complete" -> {
                    val formula = req.text ?: ""
                    val cursor = (req.cursor ?: formula.length).coerceIn(0, formula.length)
                    val items = completionsFor(box, sessionInfo, formula, cursor)
                    SpreadsheetProtocol.json.encodeToString(
                        SpreadsheetProtocol.CompletionEnvelope.serializer(),
                        SpreadsheetProtocol.CompletionEnvelope(reqId = req.reqId, items = items)
                    )
                }

                "unsubscribe" -> {
                    document.subscribers -= client
                    SpreadsheetProtocol.json.encodeToString(
                        SpreadsheetProtocol.CompletionEnvelope.serializer(),
                        SpreadsheetProtocol.CompletionEnvelope(reqId = req.reqId, items = emptyList())
                    )
                }

                else -> error(req.reqId, "Unknown spreadsheet op '${req.op}'")
            }
            val elapsed = System.currentTimeMillis() - startMs
            lprintln("debug,spreadsheet,ws", "[sheet-handle] op=${req.op} reqId=${req.reqId} completed in ${elapsed}ms, response=${result.length} chars")
            result
        } catch (t: Throwable) {
            lprintln("error,spreadsheet,ws", "[sheet-handle] op=${req.op} reqId=${req.reqId} threw ${t::class.simpleName}: ${t.message}")
            error(req.reqId, t.message ?: t.toString())
        }
    }

    fun pollLiveSheets() {
        val broadcasts = mutableListOf<Triple<SpreadsheetKey, SpreadsheetDocument, SpreadsheetSnapshot>>()
        val removals = mutableListOf<SpreadsheetKey>()

        documents.forEach { (key, document) ->
            if (!document.hasSubscribers()) return@forEach

            val info = afield.documents[key.documentFilename]
            if (info == null) {
                removals += key
                return@forEach
            }

            val box = when (key.uid) {
                "root" -> info.root
                else -> info.boxes[key.uid]
            }
            if (box == null || box.getPropertyRaw("_tombstone").isTrue()) {
                removals += key
                return@forEach
            }

            val snapshot = runCatching { document.pollExternalDependencies(box, info) }
                .onFailure { lprintln("warning,spreadsheet", "live spreadsheet polling failed for ${key.uid}/${key.property}: ${it.message}") }
                .getOrNull()
                ?: return@forEach

            broadcasts += Triple(key, document, snapshot)
        }

        broadcasts.forEach { (key, document, snapshot) ->
            broadcast(document, snapshot, key)
        }
        removals.forEach { key ->
            documents.remove(key)
            lprintln("info,spreadsheet", "stopped live polling for ${key.uid}/${key.property}")
        }
    }

    private fun broadcast(document: SpreadsheetDocument, snapshot: SpreadsheetSnapshot, key: SpreadsheetKey) {
        val payload = SpreadsheetProtocol.json.encodeToString(
            SpreadsheetProtocol.SnapshotEnvelope.serializer(),
            SpreadsheetProtocol.SnapshotEnvelope(snapshot = snapshot)
        )
        val deadClients = mutableListOf<SpreadsheetClient>()
        document.subscribers.forEach { client ->
            runCatching { client.send(payload) }
                .onFailure { deadClients += client }
        }
        if (deadClients.isNotEmpty()) {
            document.subscribers.removeAll(deadClients.toSet())
        }
        lprintln(
            "info,spreadsheet",
            "broadcast sheet ${key.uid}/${key.property} v${snapshot.version} to ${document.subscribers.size} subscribers"
        )
    }

    private fun error(reqId: String?, message: String): String =
        SpreadsheetProtocol.json.encodeToString(
            SpreadsheetProtocol.ErrorEnvelope.serializer(),
            SpreadsheetProtocol.ErrorEnvelope(reqId = reqId, error = message)
        )

    private fun completionsFor(box: Box, info: SessionInformation, formula: String, cursor: Int): List<SpreadsheetCompletionItem> {
        val context = completionContextFor(formula, cursor)
        return when (context) {
            is CompletionContext.BoxTarget -> {
                val suggestions = availableBoxRefs(info)
                    .filter { matchesCompletionQuery(it.name, context.query) || matchesCompletionQuery(it.uid, context.query) }
                    .distinctBy { it.uid }
                    .sortedWith(compareBy<SpreadsheetRef> { if (it.name == it.uid) 1 else 0 }.thenBy { it.name.lowercase() })
                    .map { ref ->
                        SpreadsheetCompletionItem(
                            label = ref.name,
                            insertText = ref.name,
                            kind = "box",
                            detail = ref.uid,
                            documentation = "Box reference",
                            replaceStart = context.replaceStart,
                            replaceEnd = context.replaceEnd
                        )
                    }
                suggestions
            }

            is CompletionContext.BoxMember -> {
                val target = resolveBox(info, box, context.ref)
                val names = memberNamesFor(target)
                names.filter { matchesCompletionQuery(it, context.query) }
                    .sorted()
                    .map { name ->
                        SpreadsheetCompletionItem(
                            label = name,
                            insertText = name,
                            kind = "member",
                            detail = "Box value",
                            documentation = "Value from ${target?.name?.ifBlank { target.id } ?: context.ref}",
                            replaceStart = context.replaceStart,
                            replaceEnd = context.replaceEnd
                        )
                    }
            }

            is CompletionContext.SelfMember -> {
                memberNamesFor(box)
                    .filter { matchesCompletionQuery(it, context.query) }
                    .sorted()
                    .map { name ->
                        SpreadsheetCompletionItem(
                            label = name,
                            insertText = name,
                            kind = "member",
                            detail = "Current box value",
                            documentation = "Current box variable or property",
                            replaceStart = context.replaceStart,
                            replaceEnd = context.replaceEnd
                        )
                    }
            }

            is CompletionContext.General -> generalCompletions()
                .filter { matchesCompletionQuery("${it.label} ${it.detail}", context.query) }
                .map { item ->
                    SpreadsheetCompletionItem(
                        label = item.label,
                        insertText = item.insertText,
                        kind = item.kind,
                        detail = item.detail,
                        documentation = item.documentation,
                        replaceStart = context.replaceStart,
                        replaceEnd = context.replaceEnd,
                        cursorOffset = item.cursorOffset,
                        selectInsertedText = item.selectInsertedText
                    )
                }
        }.take(40)
    }

    private fun availableBoxRefs(info: SessionInformation): List<SpreadsheetRef> =
        info.boxes.values.map { box ->
            val displayName = box.name.trim().ifBlank { box.id }
            SpreadsheetRef(uid = box.id, name = displayName)
        }

    private fun memberNamesFor(box: Box?): List<String> {
        if (box == null) return emptyList()
        return buildSet {
            addAll(box.properties.keys)
            addAll(box.ki.context.vars.keys)
        }.sorted()
    }

    private fun resolveBox(info: SessionInformation, currentBox: Box, ref: String): Box? {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed == "self") return currentBox
        info.boxes[trimmed]?.let { return it }
        if (trimmed == "root") return info.root
        val matches = info.boxes.values.filter { it.name.trim() == trimmed }
        return if (matches.size == 1) matches.first() else null
    }

    private fun completionContextFor(formula: String, cursor: Int): CompletionContext {
        val beforeCursor = formula.take(cursor)

        val boxMemberPattern = Regex("""box\s*\(\s*(['"])([^'"]*)\1\s*\)\s*\[\s*(['"])([^'"]*)$""")
        boxMemberPattern.find(beforeCursor)?.let { match ->
            val query = match.groupValues[4]
            return CompletionContext.BoxMember(
                ref = match.groupValues[2],
                replaceStart = cursor - query.length,
                replaceEnd = cursor,
                query = query
            )
        }

        val selfMemberPattern = Regex("""self\s*\[\s*(['"])([^'"]*)$""")
        selfMemberPattern.find(beforeCursor)?.let { match ->
            val query = match.groupValues[2]
            return CompletionContext.SelfMember(
                replaceStart = cursor - query.length,
                replaceEnd = cursor,
                query = query
            )
        }

        val boxTargetPattern = Regex("""box\s*\(\s*(['"])([^'"]*)$""")
        boxTargetPattern.find(beforeCursor)?.let { match ->
            val query = match.groupValues[2]
            return CompletionContext.BoxTarget(
                replaceStart = cursor - query.length,
                replaceEnd = cursor,
                query = query
            )
        }

        val fragment = wordFragmentAround(formula, cursor)
        return CompletionContext.General(
            replaceStart = fragment.start,
            replaceEnd = fragment.end,
            query = fragment.text
        )
    }

    private fun generalCompletions(): List<GeneralCompletion> = listOf(
        GeneralCompletion(
            label = "sum",
            insertText = "sum(range(\"A1:A4\"))",
            kind = "function",
            detail = "Sum numeric values",
            documentation = "Adds every numeric value in a range or list.",
            cursorOffset = 11
        ),
        GeneralCompletion(
            label = "avg",
            insertText = "avg(range(\"A1:A4\"))",
            kind = "function",
            detail = "Average numeric values",
            documentation = "Returns the arithmetic mean of numeric values.",
            cursorOffset = 11
        ),
        GeneralCompletion(
            label = "min",
            insertText = "min(range(\"A1:A4\"))",
            kind = "function",
            detail = "Minimum numeric value",
            documentation = "Returns the smallest numeric value in a range or list.",
            cursorOffset = 11
        ),
        GeneralCompletion(
            label = "max",
            insertText = "max(range(\"A1:A4\"))",
            kind = "function",
            detail = "Maximum numeric value",
            documentation = "Returns the largest numeric value in a range or list.",
            cursorOffset = 11
        ),
        GeneralCompletion(
            label = "count",
            insertText = "count(range(\"A1:A4\"))",
            kind = "function",
            detail = "Count non-empty values",
            documentation = "Counts non-empty values in a range or list.",
            cursorOffset = 13
        ),
        GeneralCompletion(
            label = "box",
            insertText = "box(\"Box Name\")[\"value\"]",
            kind = "box",
            detail = "Reference another Field box",
            documentation = "References a variable or property on another box by unique name or uid.",
            cursorOffset = 5
        ),
        GeneralCompletion(
            label = "self",
            insertText = "self[\"value\"]",
            kind = "box",
            detail = "Reference the current box",
            documentation = "References a variable or property on the box that owns this spreadsheet.",
            cursorOffset = 6
        ),
        GeneralCompletion(
            label = "cell",
            insertText = "cell(\"A1\")",
            kind = "function",
            detail = "Explicit single-cell lookup",
            documentation = "Equivalent to writing A1 directly inside a formula.",
            cursorOffset = 6
        ),
        GeneralCompletion(
            label = "range",
            insertText = "range(\"A1:B8\")",
            kind = "function",
            detail = "Explicit range lookup",
            documentation = "Returns a row-major list of non-empty values.",
            cursorOffset = 7
        ),
        GeneralCompletion(
            label = "if",
            insertText = "if (true) 1 else 0",
            kind = "snippet",
            detail = "Kotlin conditional expression",
            documentation = "Use Kotlin's normal conditional expression syntax.",
            cursorOffset = 4
        ),
        GeneralCompletion(
            label = "listOf",
            insertText = "listOf(A1, A2, A3)",
            kind = "function",
            detail = "Build a Kotlin list",
            documentation = "Constructs a Kotlin list from cell values or literals.",
            cursorOffset = 7
        ),
        // ── Units (via KotUniL + pipe syntax) ────────────────────────
        GeneralCompletion(label = "| m", insertText = "| m", kind = "unit", detail = "metres", documentation = "Tag value with metres (SI length)."),
        GeneralCompletion(label = "| mm", insertText = "| mm", kind = "unit", detail = "millimetres", documentation = "Tag value with millimetres."),
        GeneralCompletion(label = "| cm", insertText = "| cm", kind = "unit", detail = "centimetres", documentation = "Tag value with centimetres."),
        GeneralCompletion(label = "| km", insertText = "| km", kind = "unit", detail = "kilometres", documentation = "Tag value with kilometres."),
        GeneralCompletion(label = "| kg", insertText = "| kg", kind = "unit", detail = "kilograms", documentation = "Tag value with kilograms (SI mass)."),
        GeneralCompletion(label = "| g", insertText = "| g", kind = "unit", detail = "grams", documentation = "Tag value with grams."),
        GeneralCompletion(label = "| mg", insertText = "| mg", kind = "unit", detail = "milligrams", documentation = "Tag value with milligrams."),
        GeneralCompletion(label = "| t", insertText = "| t", kind = "unit", detail = "metric tons", documentation = "Tag value with metric tons (1000 kg)."),
        GeneralCompletion(label = "| s", insertText = "| s", kind = "unit", detail = "seconds", documentation = "Tag value with seconds (SI time)."),
        GeneralCompletion(label = "| ms", insertText = "| ms", kind = "unit", detail = "milliseconds", documentation = "Tag value with milliseconds."),
        GeneralCompletion(label = "| min", insertText = "| min", kind = "unit", detail = "minutes", documentation = "Tag value with minutes (60 s)."),
        GeneralCompletion(label = "| h", insertText = "| h", kind = "unit", detail = "hours", documentation = "Tag value with hours (3600 s)."),
        GeneralCompletion(label = "| N", insertText = "| N", kind = "unit", detail = "newtons", documentation = "Tag value with newtons (SI force)."),
        GeneralCompletion(label = "| Pa", insertText = "| Pa", kind = "unit", detail = "pascals", documentation = "Tag value with pascals (SI pressure)."),
        GeneralCompletion(label = "| J", insertText = "| J", kind = "unit", detail = "joules", documentation = "Tag value with joules (SI energy)."),
        GeneralCompletion(label = "| W", insertText = "| W", kind = "unit", detail = "watts", documentation = "Tag value with watts (SI power)."),
        GeneralCompletion(label = "| V", insertText = "| V", kind = "unit", detail = "volts", documentation = "Tag value with volts (SI voltage)."),
        GeneralCompletion(label = "| A", insertText = "| A", kind = "unit", detail = "amperes", documentation = "Tag value with amperes (SI current)."),
        GeneralCompletion(label = "| Hz", insertText = "| Hz", kind = "unit", detail = "hertz", documentation = "Tag value with hertz (SI frequency)."),
        GeneralCompletion(label = "| l", insertText = "| l", kind = "unit", detail = "litres", documentation = "Tag value with litres."),
        GeneralCompletion(label = "| ha", insertText = "| ha", kind = "unit", detail = "hectares", documentation = "Tag value with hectares."),
        GeneralCompletion(label = "| K", insertText = "| K", kind = "unit", detail = "kelvin", documentation = "Tag value with kelvin (SI temperature).")
    )
}

internal fun detachSpreadsheetClient(clientKey: Any) {
    SpreadsheetService.detachClient(clientKey)
}

private fun normalizeStoredState(state: SpreadsheetStoredState?): SpreadsheetStoredState {
    val rows = (state?.rows ?: DEFAULT_SHEET_ROWS).coerceIn(1, MAX_SHEET_ROWS)
    val cols = (state?.cols ?: DEFAULT_SHEET_COLS).coerceIn(1, MAX_SHEET_COLS)
    val colWidths = MutableList<Double>(cols) { index ->
        (state?.colWidths?.getOrNull(index)?.toInt()?.coerceIn(48, 480) ?: DEFAULT_SHEET_COL_WIDTH).toDouble()
    }
    val rowHeights = MutableList<Double>(rows) { index ->
        (state?.rowHeights?.getOrNull(index)?.toInt()?.coerceIn(22, 240) ?: DEFAULT_SHEET_ROW_HEIGHT).toDouble()
    }
    val cells = linkedMapOf<String, SpreadsheetStoredCell>()
    state?.cells.orEmpty().forEach { cell ->
        if (cell.row !in 0 until rows || cell.col !in 0 until cols) return@forEach
        if (cell.input.isBlank()) return@forEach
        cells["${cell.row}:${cell.col}"] = SpreadsheetStoredCell(cell.row, cell.col, cell.input)
    }
    return SpreadsheetStoredState(
        rows = rows,
        cols = cols,
        colWidths = colWidths,
        rowHeights = rowHeights,
        cells = cells.values.toList()
    )
}

private fun normalizedSpreadsheetProperty(property: String): String =
    property.trim().ifBlank { "spreadsheet" }

private fun encodeStoredSpreadsheetState(state: SpreadsheetStoredState): String =
    SpreadsheetProtocol.json.encodeToString(normalizeStoredState(state))

private fun writeStoredSpreadsheetState(box: Box, property: String, state: SpreadsheetStoredState): SpreadsheetStoredState {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    val normalizedState = normalizeStoredState(state)
    box.setPropertyRaw(normalizedProperty, encodeStoredSpreadsheetState(normalizedState), doResync = false)
    if (Box.ioSeparateProperties.containsKey(normalizedProperty) && box.isInsideInitialized() && !box.inside.ephemeral) {
        box.inside.pendingSidecarSaves += box.id to normalizedProperty
    }
    return normalizedState
}

private fun decodeStoredState(raw: String?): SpreadsheetStoredState {
    if (raw.isNullOrBlank()) return normalizeStoredState(null)
    return runCatching {
        SpreadsheetProtocol.json.decodeFromString(SpreadsheetStoredState.serializer(), raw)
    }.getOrElse {
        lprintln("warning,spreadsheet", "failed to decode stored spreadsheet state: ${it.message}")
        normalizeStoredState(null)
    }
}

private fun classifyCellInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "empty"
    if (trimmed.startsWith("=")) return "formula"
    if (trimmed.toDoubleOrNull() != null) return "number"
    return "text"
}

private fun spreadsheetCellInputText(input: Any?): String = when (input) {
    null -> ""
    is SpreadsheetScalar -> formatSpreadsheetValue(input.raw)
    is String -> input
    is CharSequence -> input.toString()
    is Number, is Boolean -> formatSpreadsheetValue(input)
    else -> input.toString()
}

private fun rewriteSpreadsheetFormula(input: String): String {
    val out = StringBuilder(input.length + 32)
    var index = 0
    var quote: Char? = null
    while (index < input.length) {
        val ch = input[index]
        if (quote != null) {
            out.append(ch)
            if (ch == '\\' && quote != '`' && index + 1 < input.length) {
                out.append(input[index + 1])
                index += 2
                continue
            }
            if (ch == quote) quote = null
            index += 1
            continue
        }
        if (ch == '\'' || ch == '"' || ch == '`') {
            quote = ch
            out.append(ch)
            index += 1
            continue
        }
        val parsedFirst = parseCellRefAt(input, index)
        if (parsedFirst != null && isRefBoundaryBefore(input, index)) {
            val colonIndex = parsedFirst.endExclusive
            if (colonIndex < input.length && input[colonIndex] == ':') {
                val parsedSecond = parseCellRefAt(input, colonIndex + 1)
                if (parsedSecond != null && isRefBoundaryAfter(input, parsedSecond.endExclusive)) {
                    out.append("""range("${cellLabel(parsedFirst.row, parsedFirst.col)}:${cellLabel(parsedSecond.row, parsedSecond.col)}")""")
                    index = parsedSecond.endExclusive
                    continue
                }
            }
            if (isRefBoundaryAfter(input, parsedFirst.endExclusive)) {
                out.append("""cell("${cellLabel(parsedFirst.row, parsedFirst.col)}")""")
                index = parsedFirst.endExclusive
                continue
            }
        }
        out.append(ch)
        index += 1
    }
    return rewritePipeUnits(out.toString())
}

private val PIPE_UNIT_PATTERN = Regex("""\|\s*([A-Za-z_`][A-Za-z0-9_`]*)""")

private fun rewritePipeUnits(input: String): String {
    val out = StringBuilder(input.length + 32)
    var index = 0
    var quote: Char? = null
    while (index < input.length) {
        val ch = input[index]
        if (quote != null) {
            out.append(ch)
            if (ch == '\\' && quote != '`' && index + 1 < input.length) {
                out.append(input[index + 1])
                index += 2
                continue
            }
            if (ch == quote) quote = null
            index += 1
            continue
        }
        if (ch == '\'' || ch == '"' || ch == '`') {
            quote = ch
            out.append(ch)
            index += 1
            continue
        }
        if (ch == '|') {
            val remaining = input.substring(index)
            val match = PIPE_UNIT_PATTERN.matchAt(remaining, 0)
            if (match != null) {
                val unitIdent = match.groupValues[1]
                out.append(".withUnit($unitIdent)")
                index += match.value.length
                continue
            }
        }
        out.append(ch)
        index += 1
    }
    return out.toString()
}

private fun parseCellRefAt(source: String, start: Int): ParsedCellRef? {
    var index = start
    if (index >= source.length) return null
    if (source[index] == '$') index += 1
    val colStart = index
    while (index < source.length && source[index].isLetter()) index += 1
    if (index == colStart) return null
    if (index < source.length && source[index] == '$') index += 1
    val rowStart = index
    while (index < source.length && source[index].isDigit()) index += 1
    if (index == rowStart) return null

    val colEnd = if (rowStart > colStart && source[rowStart - 1] == '$') rowStart - 1 else rowStart
    val colText = source.substring(colStart, colEnd)
    val rowDigits = source.substring(rowStart, index)
    val row = rowDigits.toIntOrNull()?.minus(1) ?: return null
    if (row < 0) return null
    var col = 0
    colText.uppercase().forEach { letter ->
        col = col * 26 + (letter.code - 'A'.code + 1)
    }
    return ParsedCellRef(row = row, col = col - 1, endExclusive = index)
}

private fun isRefBoundaryBefore(source: String, index: Int): Boolean {
    if (index <= 0) return true
    val prev = source[index - 1]
    return !(prev.isLetterOrDigit() || prev == '_' || prev == ']')
}

private fun isRefBoundaryAfter(source: String, index: Int): Boolean {
    if (index >= source.length) return true
    val next = source[index]
    return !(next.isLetterOrDigit() || next == '_' || next == '[')
}

private fun cellRefToCoord(ref: String): CellCoord? {
    val parsed = parseCellRefAt(ref.trim(), 0) ?: return null
    if (parsed.endExclusive != ref.trim().length) return null
    return CellCoord(parsed.row, parsed.col)
}

private fun columnLabelToIndex(label: String): Int? {
    val text = label.trim().uppercase()
    if (text.isEmpty() || text.any { !it.isLetter() }) return null
    var col = 0
    text.forEach { letter ->
        col = col * 26 + (letter.code - 'A'.code + 1)
    }
    return col - 1
}

private fun cellLabel(row: Int, col: Int): String {
    var label = ""
    var current = col
    while (current >= 0) {
        label = ('A'.code + (current % 26)).toChar() + label
        current = current / 26 - 1
    }
    return "$label${row + 1}"
}

private fun fingerprintSpreadsheetDependencyValue(value: Any?): String = when (value) {
    is SpreadsheetScalar -> fingerprintSpreadsheetDependencyValue(value.raw)
    null -> "null"
    is Unit -> "unit"
    is String -> "string:${value.length}:$value"
    is Boolean -> "bool:$value"
    is Vec2 -> "vec2:${value.x},${value.y}"
    is Vec3 -> "vec3:${value.x},${value.y},${value.z}"
    is Vec4 -> "vec4:${value.x},${value.y},${value.z},${value.w}"
    is FLine -> summarizeFLine(value).let { "fline:${it.x},${it.y},${it.w},${it.h}" }
    is ByteArray -> value.joinToString(prefix = "bytes[", postfix = "]")
    is ShortArray -> value.joinToString(prefix = "shorts[", postfix = "]")
    is IntArray -> value.joinToString(prefix = "ints[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "longs[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "floats[", postfix = "]")
    is DoubleArray -> value.joinToString(prefix = "doubles[", postfix = "]")
    is BooleanArray -> value.joinToString(prefix = "bools[", postfix = "]")
    is CharArray -> "chars[${value.concatToString()}]"
    is Array<*> -> value.joinToString(prefix = "array[", postfix = "]") { fingerprintSpreadsheetDependencyValue(it) }
    is Iterable<*> -> value.joinToString(prefix = "iter[", postfix = "]") { fingerprintSpreadsheetDependencyValue(it) }
    is Map<*, *> -> value.entries
        .sortedBy { fingerprintSpreadsheetDependencyValue(it.key) }
        .joinToString(prefix = "map{", postfix = "}") {
            "${fingerprintSpreadsheetDependencyValue(it.key)}=${fingerprintSpreadsheetDependencyValue(it.value)}"
        }

    is Quantity -> "quantity:${value.value}:${value.unitSymbols}:${value.displaySymbol}"
    is Expression -> "expression:${value.value}:${value.unitSymbols()}"
    is Number -> "number:${value.javaClass.name}:${value}"
    else -> "${value.javaClass.name}:${value}"
}

private fun formatSpreadsheetValue(value: Any?): String = when (value) {
    is SpreadsheetScalar -> formatSpreadsheetValue(value.raw)
    null -> ""
    is Unit -> ""
    is Quantity -> {
        val (v, sym) = formatQuantityDisplay(value)
        if (sym.isNotEmpty()) "${formatNumber(v)} $sym" else formatNumber(v)
    }
    is Expression -> {
        val (v, sym) = formatExpression(value)
        if (sym.isNotEmpty()) "${formatNumber(v)} $sym" else formatNumber(value.value)
    }
    is Double -> formatNumber(value)
    is Float -> formatNumber(value.toDouble())
    is Number -> value.toString()
    is Boolean -> value.toString()
    is String -> value
    is Vec2 -> "vec(${formatNumber(value.x.toDouble())}, ${formatNumber(value.y.toDouble())})"
    is Vec3 -> "vec(${formatNumber(value.x.toDouble())}, ${formatNumber(value.y.toDouble())}, ${formatNumber(value.z.toDouble())})"
    is Vec4 -> "vec(${formatNumber(value.x.toDouble())}, ${formatNumber(value.y.toDouble())}, ${formatNumber(value.z.toDouble())}, ${formatNumber(value.w.toDouble())})"
    is FLine -> summarizeFLine(value).let {
        "FLine(${formatNumber(it.x)}, ${formatNumber(it.y)}, ${formatNumber(it.w)}, ${formatNumber(it.h)})"
    }
    is SpreadsheetBoxBinding -> value.name
    is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
    is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
    is IntArray -> value.joinToString(prefix = "[", postfix = "]")
    is LongArray -> value.joinToString(prefix = "[", postfix = "]")
    is FloatArray -> value.joinToString(prefix = "[", postfix = "]") { formatNumber(it.toDouble()) }
    is DoubleArray -> value.joinToString(prefix = "[", postfix = "]") { formatNumber(it) }
    is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
    is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { formatSpreadsheetValue(it) }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { formatSpreadsheetValue(it) }
    else -> value.toString()
}

private fun isSpreadsheetScalarDisplay(value: Any?): Boolean {
    val raw = unwrapSpreadsheetValue(value)
    return when (raw) {
        null, is Unit -> true
        is Number, is Boolean -> true
        is Quantity, is Expression -> true
        is String -> raw.length <= 120
        else -> false
    }
}

private fun spreadsheetHtmlDisplay(value: Any?): String? {
    if (isSpreadsheetScalarDisplay(value)) return null
    return try {
        ValueVisualizer.visualize(unwrapSpreadsheetValue(value))
    } catch (_: Throwable) {
        null
    }
}

private fun formatNumber(value: Double): String {
    val rounded = round(value * 10_000.0) / 10_000.0
    val safe = if (rounded == -0.0) 0.0 else rounded
    val fixed = "%,.4f".format(java.util.Locale.US, safe)
    return fixed
        .replace(",", "")
        .replace(Regex("""\.?0+$"""), "")
}

private fun numericValue(value: Any?): Double? = SpreadsheetDynamicRegistry.scalar(value)

private fun safeForId(value: String): String =
    value.replace(Regex("""[^A-Za-z0-9_]+"""), "_").trim('_').ifBlank { UUID.randomUUID().toString().replace("-", "_") }

private fun wordFragmentAround(text: String, cursor: Int): SheetWordFragment {
    var start = cursor
    var end = cursor
    while (start > 0 && isCompletionWordChar(text[start - 1])) start -= 1
    while (end < text.length && isCompletionWordChar(text[end])) end += 1
    return SheetWordFragment(start, end, text.substring(start, cursor))
}

private fun isCompletionWordChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '_' || ch == '.'

private fun matchesCompletionQuery(label: String, query: String): Boolean {
    if (query.isBlank()) return true
    val haystack = label.lowercase()
    val needle = query.lowercase()
    return haystack.contains(needle)
}

fun Box.spreadsheet(
    channel: String = "spreadsheet",
    property: String = "spreadsheet",
    height: Int = 360,
    defaultState: SpreadsheetStoredState? = null
) {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    registerSpreadsheetProperty(normalizedProperty)
    defaultState?.let { seedSpreadsheet(property = normalizedProperty, state = it) }
    val info = inside
    val params = mutableListOf(
        "uid=${urlEncode(id)}",
        "property=${urlEncode(normalizedProperty)}",
        "file=${urlEncode(info.documentName)}",
        "user=${urlEncode("spreadsheet")}"
    )
    if (info.ephemeral) {
        params += "scratch=1"
        params += "sessionKey=${urlEncode(info.filename)}"
    }
    val displayChannel = if (channel == normalizedProperty) channel else "$channel/$normalizedProperty"
    display(
        displayChannel,
        """
        <iframe src="/spreadsheet.html?${params.joinToString("&")}" loading="lazy"
            style="border:none;padding:0;margin:0;width:100%;height:${height}px;display:block;background:transparent;"></iframe>
        """.trimIndent()
    )
}

fun Box.spreadsheet(
    channel: String = "spreadsheet",
    property: String = "spreadsheet",
    height: Int = 360,
    defaultState: SpreadsheetStateBuilder.() -> Unit
) = spreadsheet(channel = channel, property = property, height = height, defaultState = spreadsheetState(defaultState))

fun Box.sheet(
    channel: String = "spreadsheet",
    property: String = "spreadsheet",
    height: Int = 360,
    defaultState: SpreadsheetStoredState? = null
) = spreadsheet(channel = channel, property = property, height = height, defaultState = defaultState)

fun Box.sheet(
    channel: String = "spreadsheet",
    property: String = "spreadsheet",
    height: Int = 360,
    defaultState: SpreadsheetStateBuilder.() -> Unit
) = spreadsheet(channel = channel, property = property, height = height, defaultState = defaultState)

fun Box.getSpreadsheetState(property: String = "spreadsheet"): SpreadsheetStoredState {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    registerSpreadsheetProperty(normalizedProperty)
    return if (isInsideInitialized()) {
        SpreadsheetService.currentState(this, normalizedProperty)
    } else {
        decodeStoredState(getPropertyRaw(normalizedProperty, null) as? String)
    }
}

fun Box.sheetState(property: String = "spreadsheet"): SpreadsheetStoredState =
    getSpreadsheetState(property)

fun Box.getSpreadsheetSnapshot(
    property: String = "spreadsheet",
    forceReload: Boolean = false,
    forceRecompute: Boolean = true
): SpreadsheetSnapshot {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    registerSpreadsheetProperty(normalizedProperty)
    if (!isInsideInitialized()) error("Spreadsheet snapshot requires an initialized box")
    return SpreadsheetService.snapshot(this, inside, normalizedProperty, forceReload = forceReload, forceRecompute = forceRecompute)
}

fun Box.sheetSnapshot(
    property: String = "spreadsheet",
    forceReload: Boolean = false,
    forceRecompute: Boolean = true
): SpreadsheetSnapshot =
    getSpreadsheetSnapshot(property = property, forceReload = forceReload, forceRecompute = forceRecompute)

fun Box.getSpreadsheetCellValue(
    ref: String,
    property: String = "spreadsheet",
    forceReload: Boolean = false,
    forceRecompute: Boolean = true
): Any? {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    registerSpreadsheetProperty(normalizedProperty)
    if (!isInsideInitialized()) error("Spreadsheet cell lookup requires an initialized box")
    return SpreadsheetService.cellValue(
        this,
        inside,
        normalizedProperty,
        ref,
        forceReload = forceReload,
        forceRecompute = forceRecompute
    )
}

fun Box.getSpreadsheetCell(
    ref: String,
    property: String = "spreadsheet",
    forceReload: Boolean = false,
    forceRecompute: Boolean = true
): SpreadsheetScalar =
    SpreadsheetScalar(
        getSpreadsheetCellValue(
            ref,
            property = property,
            forceReload = forceReload,
            forceRecompute = forceRecompute
        )
    )

fun Box.sheetCellValue(
    ref: String,
    property: String = "spreadsheet",
    forceReload: Boolean = false,
    forceRecompute: Boolean = true
): Any? =
    getSpreadsheetCellValue(
        ref,
        property = property,
        forceReload = forceReload,
        forceRecompute = forceRecompute
    )

fun Box.sheetCell(
    ref: String,
    property: String = "spreadsheet",
    forceReload: Boolean = false,
    forceRecompute: Boolean = true
): SpreadsheetScalar =
    getSpreadsheetCell(
        ref,
        property = property,
        forceReload = forceReload,
        forceRecompute = forceRecompute
    )

fun Box.setSpreadsheetState(
    state: SpreadsheetStoredState,
    property: String = "spreadsheet",
    broadcast: Boolean = true
): SpreadsheetStoredState {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    registerSpreadsheetProperty(normalizedProperty)
    return if (isInsideInitialized()) {
        SpreadsheetService.applyProgrammaticState(this, inside, normalizedProperty, state, broadcast = broadcast)
    } else {
        writeStoredSpreadsheetState(this, normalizedProperty, state)
    }
}

fun Box.setSpreadsheetState(
    property: String = "spreadsheet",
    broadcast: Boolean = true,
    init: SpreadsheetStateBuilder.() -> Unit
): SpreadsheetStoredState =
    setSpreadsheetState(spreadsheetState(init), property = property, broadcast = broadcast)

fun Box.seedSpreadsheet(
    property: String = "spreadsheet",
    state: SpreadsheetStoredState,
    broadcast: Boolean = true
): SpreadsheetStoredState {
    val normalizedProperty = normalizedSpreadsheetProperty(property)
    registerSpreadsheetProperty(normalizedProperty)
    val raw = getPropertyRaw(normalizedProperty, null) as? String
    if (!raw.isNullOrBlank()) {
        return decodeStoredState(raw)
    }
    return setSpreadsheetState(state, property = normalizedProperty, broadcast = broadcast)
}

fun Box.seedSpreadsheet(
    property: String = "spreadsheet",
    broadcast: Boolean = true,
    init: SpreadsheetStateBuilder.() -> Unit
): SpreadsheetStoredState =
    seedSpreadsheet(property = property, state = spreadsheetState(init), broadcast = broadcast)

fun Box.seedSheet(
    property: String = "spreadsheet",
    state: SpreadsheetStoredState,
    broadcast: Boolean = true
): SpreadsheetStoredState = seedSpreadsheet(property = property, state = state, broadcast = broadcast)

fun Box.editSpreadsheet(
    property: String = "spreadsheet",
    broadcast: Boolean = true,
    edit: SpreadsheetStateBuilder.() -> Unit
): SpreadsheetStoredState {
    val builder = SpreadsheetStateBuilder(getSpreadsheetState(property))
    builder.edit()
    return setSpreadsheetState(builder.build(), property = property, broadcast = broadcast)
}

fun Box.editSheet(
    property: String = "spreadsheet",
    broadcast: Boolean = true,
    edit: SpreadsheetStateBuilder.() -> Unit
): SpreadsheetStoredState = editSpreadsheet(property = property, broadcast = broadcast, edit = edit)

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun registerSpreadsheetProperty(property: String) {
    val normalized = normalizedSpreadsheetProperty(property)
    Box.ioPersistentProperties += normalized
    Box.ioSeparateProperties.putIfAbsent(normalized, "json")
}

@RunsAt(installHandlers)
fun installSpreadsheetSupport() {
    registerSpreadsheetProperty("spreadsheet")
    Runloop.runLoop.preRun[SPREADSHEET_POLL_HOOK] = sequence {
        while (true) {
            runCatching { SpreadsheetService.pollLiveSheets() }
                .onFailure { lprintln("warning,spreadsheet", "spreadsheet live polling failed: ${it.message}") }
            yield(Unit)
        }
    }

    rawHandlers.getOrPut(SPREADSHEET_CHANNEL) { mutableSetOf() }.add { text ->
        val ws = thisSession.get()
        val info = thisSessionInfo.get()
        val reqSnippet = text.take(120)
        lprintln("debug,spreadsheet,ws", "[sheet-ws] raw handler received (${text.length} chars): $reqSnippet")
        inMain {
            lprintln("debug,spreadsheet,ws", "[sheet-ws] inMain entered for: $reqSnippet")
            try {
                val response = SpreadsheetService.handle(text, info = info, clientKey = ws) { payload ->
                    lprintln("debug,spreadsheet,ws", "[sheet-ws] broadcast push (${payload.length} chars)")
                    inMain {
                        try {
                            ws.send(payload)
                        } catch (t: Throwable) {
                            lprintln("warning,spreadsheet,ws", "[sheet-ws] broadcast send failed: ${t.message}")
                        }
                    }
                }
                if (response.isNotEmpty()) {
                    lprintln("debug,spreadsheet,ws", "[sheet-ws] sending response (${response.length} chars)")
                    ws.send(response)
                    lprintln("debug,spreadsheet,ws", "[sheet-ws] response sent OK")
                } else {
                    lprintln("debug,spreadsheet,ws", "[sheet-ws] handle returned empty response")
                }
            } catch (t: Throwable) {
                lprintln("error,spreadsheet,ws", "[sheet-ws] inMain block failed: ${t::class.simpleName}: ${t.message}")
                t.printStackTrace()
            }
        }
        Handled.yes
    }
}

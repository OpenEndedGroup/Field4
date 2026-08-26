@file:afield.CompletionCategory("Visualization")

package afield

import afield.server.Browsable
import afield.server.Inspector
import afield.stdlib.Quantity
import afield.stdlib.formatExpression
import afield.stdlib.formatQuantityDisplay
import eu.sirotin.kotunil.core.Expression
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.abs

/**
 * The lazy half of value display: rather than inline a large structure, show one summary line plus
 * links into the server-side browsers.
 *
 * [LazyValueVisualizer.visualize] — reached in a box as [h2] — passes anything small or
 * unrecognised straight to `ValueVisualizer`. For maps, lists, arrays and `FloatBuffer` /
 * `IntBuffer` it instead retains the value under a token and emits a summary, `outliner` and
 * `columns` links, and an embedded `/h2-explorer` iframe. Those tokens live in an in-memory
 * retention table (10 minutes, 256 entries), so the links only resolve against the running server
 * and go stale after that.
 *
 * The private `Browsable` wrappers behind those routes page rather than copy: children are grouped
 * into range-labelled directories of at most 96 entries, and a map with more than 20,000 entries is
 * iterated on demand instead of snapshotted. [registerH2Value] adds a per-type adapter returning an
 * [H2ValueSnapshot] when a type should be summarised or unwrapped differently, and
 * [lazyInlineExpanderHtml] is the inline "… +N more" link `ValueVisualizer` emits for elided
 * content.
 */

private const val H2_MAX_CHILDREN_PER_DIR = 96
private const val H2_LIST_LEAF_SIZE = 128
private const val H2_MAP_LEAF_SIZE = 96
private const val H2_FLOAT_ROWS_LEAF_SIZE = 128
private const val H2_IFRAME_HEIGHT_PX = 253
private const val H2_EMBED_EXPLORER_PATH = "/h2-explorer"
private const val H2_OUTLINER_PATH = "/tree"
private const val H2_COLUMNS_PATH = "/browse"
private const val INLINE_EXPAND_PATH = "/lazy-value-fragment"
private const val H2_TOKEN_MAX = 256
private const val H2_TOKEN_TTL_MS = 10 * 60 * 1000L
private const val H2_MAP_SNAPSHOT_THRESHOLD = 20_000
private const val H2_OUTPUT_VIEW_THRESHOLD = 32

data class H2ValueSnapshot(
    val value: Any?,
    val summary: String? = null,
    val title: String? = null
)

object H2ValueRegistry {
    private val adapters = mutableMapOf<Class<*>, (Any) -> H2ValueSnapshot>()

    fun <T : Any> register(clazz: Class<T>, adapter: (T) -> H2ValueSnapshot) {
        @Suppress("UNCHECKED_CAST")
        adapters[clazz] = adapter as (Any) -> H2ValueSnapshot
    }

    /**
     * The `.h2` adapter registered for [value]'s type, or null.
     *
     * Same resolution rule, and same fix, as [ValuePrinterRegistry.getPrinter]: the superclass chain
     * most-derived first, and at each step that class's **whole** interface closure rather than only
     * the interfaces the runtime class declares. Kept identical, because the two
     * registries are the pair of extension points a third-party type reaches Field through, and
     * having them disagree about what "registered for this type" means is the bug twice.
     */
    internal fun adapt(value: Any): H2ValueSnapshot? {
        var clazz: Class<*>? = value::class.java
        while (clazz != null) {
            adapters[clazz]?.let { return it(value) }
            findInInterfaceClosure(clazz.interfaces)?.let { return it(value) }
            clazz = clazz.superclass
        }
        return null
    }

    /** Breadth-first over [roots] and everything they extend, so declared interfaces win over theirs. */
    private fun findInInterfaceClosure(roots: Array<Class<*>>): ((Any) -> H2ValueSnapshot)? {
        val queue = ArrayDeque(roots.toList())
        val seen = HashSet<Class<*>>(roots.toList())
        while (queue.isNotEmpty()) {
            val iface = queue.removeFirst()
            adapters[iface]?.let { return it }
            iface.interfaces.forEach { if (seen.add(it)) queue.addLast(it) }
        }
        return null
    }
}

private object H2TokenRetention {
    private data class Entry(val raw: Any?, val createdAtMs: Long)

    private val retained = LinkedHashMap<String, Entry>()

    @Synchronized
    fun remember(token: String, raw: Any?) {
        val now = System.currentTimeMillis()
        retained[token] = Entry(raw, now)
        cleanup(now)
    }

    @Synchronized
    fun fetch(token: String): Any? {
        val now = System.currentTimeMillis()
        cleanup(now)
        return retained[token]?.raw
    }

    @Synchronized
    private fun cleanup(now: Long) {
        val it = retained.entries.iterator()
        while (it.hasNext()) {
            val (_, entry) = it.next()
            if (now - entry.createdAtMs > H2_TOKEN_TTL_MS) {
                it.remove()
            }
        }
        while (retained.size > H2_TOKEN_MAX) {
            val eldestKey = retained.entries.firstOrNull()?.key ?: break
            retained.remove(eldestKey)
        }
    }
}

object LazyValueVisualizer {
    fun visualize(value: Any?, appendable: Boolean = false): String {
        val adapted = value?.let { H2ValueRegistry.adapt(it) }
        if (adapted == null && !isHeavyStructure(value)) return ValueVisualizer.visualize(value, appendable = appendable)

        val rawValue = adapted?.value ?: value
        val wrapped = wrapForInspector(rawValue, title = adapted?.title ?: "value")
        val token = "h2-${UUID.randomUUID()}"
        H2TokenRetention.remember(token, rawValue)
        val outlinerPath = Inspector.linkPathFor(wrapped ?: "null", browsePath = H2_OUTLINER_PATH, uid = token)
        val columnsPath = Inspector.linkPathFor(wrapped ?: "null", browsePath = H2_COLUMNS_PATH, uid = token)
        val embeddedPath = "$H2_EMBED_EXPLORER_PATH?root=$token"

        return buildString {
            append("<div class='value-viz-h2")
            if (appendable) append(" afield-appendable-output")
            append("' style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.35;display:flex;flex-direction:column;align-items:stretch;width:100%;height:100%;min-height:0;box-sizing:border-box;'>")
            append("<div style='display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:6px;'>")
            append("<span style='color:#9cf;'>")
            append(escapeHtml(adapted?.summary ?: summary(rawValue)))
            append("</span>")
            append("<a href='")
            append(escapeHtmlAttr(outlinerPath))
            append("' target='_blank' style='color:#8cf;text-decoration:none;'>outliner</a>")
            append("<a href='")
            append(escapeHtmlAttr(columnsPath))
            append("' target='_blank' style='color:#777;text-decoration:none;'>columns</a>")
            append("</div>")
            append("<iframe data-afield-autosize='off' src='")
            append(escapeHtmlAttr(embeddedPath))
            append("' loading='lazy' style='position:static;display:block;width:100%;height:${H2_IFRAME_HEIGHT_PX}px;flex:1 1 auto;min-height:0;border:1px solid #333;border-radius:4px;background:#000;'></iframe>")
            append("</div>")
        }
    }

    private fun summary(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "${value::class.simpleName ?: "Map"} (size=${value.size})"
        is List<*> -> unitCollectionSummary(value, value::class.simpleName ?: "List")
            ?: "${value::class.simpleName ?: "List"} (size=${value.size})"
        is Array<*> -> unitCollectionSummary(value.asList(), value::class.simpleName ?: "Array")
            ?: "${value::class.simpleName ?: "Array"} (size=${value.size})"
        is IntArray -> "IntArray (size=${value.size})"
        is LongArray -> "LongArray (size=${value.size})"
        is FloatArray -> "FloatArray (size=${value.size})"
        is DoubleArray -> "DoubleArray (size=${value.size})"
        is FloatBuffer -> "FloatBuffer (pos=${value.position()}, limit=${value.limit()}, capacity=${value.capacity()})"
        is IntBuffer -> "IntBuffer (pos=${value.position()}, limit=${value.limit()}, capacity=${value.capacity()})"
        else -> value::class.simpleName ?: value::class.java.simpleName
    }

    private fun unitCollectionSummary(values: List<*>, kind: String): String? {
        if (values.isEmpty()) return null
        val symbols = values.mapNotNull {
            when (it) {
                is Quantity -> formatQuantityDisplay(it).second
                is Expression -> formatExpression(it).second
                else -> return null
            }
        }.distinct()
        val suffix = if (symbols.size == 1 && symbols.single().isNotBlank()) " · ${symbols.single()}" else " · mixed units"
        return "$kind (size=${values.size})$suffix"
    }

    private fun isHeavyStructure(value: Any?): Boolean {
        return value is Map<*, *> ||
            value is List<*> ||
            value is Array<*> ||
            value is IntArray ||
            value is LongArray ||
            value is FloatArray ||
            value is DoubleArray ||
            value is FloatBuffer ||
            value is IntBuffer
    }
}

internal fun rememberLazyValueToken(value: Any?): String {
    val token = "lazy-${UUID.randomUUID()}"
    H2TokenRetention.remember(token, value)
    return token
}

internal fun h2ExplorerPathForToken(token: String): String = "$H2_EMBED_EXPLORER_PATH?root=$token"

private fun collectionSize(value: Any?): Int? = when (value) {
    is Collection<*> -> value.size
    is Map<*, *> -> value.size
    is Array<*> -> value.size
    is ByteArray -> value.size
    is ShortArray -> value.size
    is IntArray -> value.size
    is LongArray -> value.size
    is FloatArray -> value.size
    is DoubleArray -> value.size
    is BooleanArray -> value.size
    is CharArray -> value.size
    is FloatBuffer -> value.limit()
    is IntBuffer -> value.limit()
    else -> null
}

internal fun shouldOfferH2OutputAreaViewer(value: Any?): Boolean {
    val size = collectionSize(value) ?: return false
    if (size < H2_OUTPUT_VIEW_THRESHOLD) return false
    return when (value) {
        is List<*>, is Array<*>,
        is ByteArray, is ShortArray, is IntArray, is LongArray,
        is FloatArray, is DoubleArray, is BooleanArray, is CharArray,
        is FloatBuffer, is IntBuffer -> true
        else -> false
    }
}

internal fun lazyInlineExpanderHtml(
    value: Any?,
    label: String,
    kind: String,
    offset: Int,
    depth: Int = 0,
    title: String? = null,
    ownerUid: String? = Box.context.get()?.id,
    offerOutputAreaViewer: Boolean = shouldOfferH2OutputAreaViewer(value)
): String {
    val token = rememberLazyValueToken(value)
    val expandPath = "$INLINE_EXPAND_PATH?root=$token&kind=$kind&offset=$offset&depth=$depth"
    val outputAreaPath = if (offerOutputAreaViewer) h2ExplorerPathForToken(token) else null
    val outputAreaTitle = title?.ifBlank { null } ?: "Columns"

    return buildString {
        append("<span class='afield-lazy-inline'>")
        append("<a href='#' class='afield-lazy-inline-link' data-afield-lazy-src='")
        append(escapeHtmlAttr(expandPath))
        append("' onclick='window.afieldExpandLazyValue && window.afieldExpandLazyValue(this); return false;'")
        append(" style='color:#aaa;text-decoration:underline;text-underline-offset:2px;text-decoration-thickness:1px;cursor:pointer;'>")
        append(escapeHtml(label))
        append("</a>")
        if (outputAreaPath != null && !ownerUid.isNullOrBlank()) {
            append("<span style='color:#666;'> </span>")
            append("<a href='#' class='afield-lazy-inline-link afield-lazy-inline-side-link'")
            append(" data-afield-view-src='")
            append(escapeHtmlAttr(outputAreaPath))
            append("' data-afield-box-uid='")
            append(escapeHtmlAttr(ownerUid))
            append("' data-afield-view-title='")
            append(escapeHtmlAttr(outputAreaTitle))
            append("' onclick='window.afieldOpenLazyValueInOutputArea && window.afieldOpenLazyValueInOutputArea(this); return false;'")
            append(" style='color:#777;text-decoration:underline;text-underline-offset:2px;text-decoration-thickness:1px;cursor:pointer;'>")
            append("columns")
            append("</a>")
        }
        append("</span>")
    }
}

private fun wrapForInspector(value: Any?, title: String? = null): Any? {
    return when (value) {
        null -> null
        is Quantity -> QuantityBrowsable(value)
        is Expression -> UnitExpressionBrowsable(value)
        is PagedListBrowsable -> value
        is PagedMapBrowsable -> value
        is FloatBufferBrowsable -> value
        is IntBufferBrowsable -> value
        is Map<*, *> -> PagedMapBrowsable.forMap(value, title)
        is List<*> -> PagedListBrowsable(value, title = title)
        is Array<*> -> PagedListBrowsable(value.asList(), title = title)
        is ByteArray -> PagedListBrowsable(value.asList(), title = title)
        is ShortArray -> PagedListBrowsable(value.asList(), title = title)
        is IntArray -> IntBufferBrowsable(IntBuffer.wrap(value), title = title)
        is LongArray -> PagedListBrowsable(value.asList(), title = title)
        is FloatArray -> FloatBufferBrowsable(FloatBuffer.wrap(value), title = title)
        is DoubleArray -> PagedListBrowsable(value.asList(), title = title)
        is BooleanArray -> PagedListBrowsable(value.asList(), title = title)
        is CharArray -> PagedListBrowsable(value.asList(), title = title)
        is FloatBuffer -> FloatBufferBrowsable(value, title = title)
        is IntBuffer -> IntBufferBrowsable(value, title = title)
        else -> value
    }
}

private class QuantityBrowsable(private val quantity: Quantity) : Browsable {
    override fun heading(): String = quantity.toString()

    override fun dir(): Map<String, Any?> = linkedMapOf(
        "display value" to quantity.displayValue,
        "display unit" to quantity.displaySymbol,
        "SI value" to quantity.value,
        "SI unit" to quantity.unitSymbols.trim()
    )

    override fun html(): String = ValueVisualizer.visualize(quantity)
}

private class UnitExpressionBrowsable(private val expression: Expression) : Browsable {
    private val formatted by lazy { formatExpression(expression) }

    override fun heading(): String = formatted.let { (value, symbol) ->
        if (symbol.isBlank()) value.toString() else "$value $symbol"
    }

    override fun dir(): Map<String, Any?> = linkedMapOf(
        "SI value" to expression.value,
        "SI unit" to expression.unitSymbols().trim()
    )

    override fun html(): String = ValueVisualizer.visualize(expression)
}

private fun rangeLabel(start: Int, endExclusive: Int): String {
    val last = endExclusive - 1
    return if (start > last) "[empty]" else "[$start..$last]"
}

private fun ceilDiv(value: Int, by: Int): Int {
    if (value <= 0) return 0
    return (value + by - 1) / by
}

private fun computeChildSpan(count: Int, leafSpan: Int): Int {
    var span = leafSpan.coerceAtLeast(1)
    while (ceilDiv(count, span) > H2_MAX_CHILDREN_PER_DIR) {
        val next = span * H2_MAX_CHILDREN_PER_DIR
        if (next <= span) {
            return count.coerceAtLeast(leafSpan)
        }
        span = next
    }
    return span
}

private fun clip(text: String, maxChars: Int = 64): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars - 3) + "..."
}

private fun keyPreview(key: Any?): String {
    if (key == null) return "null"
    return clip(key.toString().replace('\n', ' '))
}

private fun fmtFloat(n: Float): String {
    return when {
        n.isNaN() -> "NaN"
        n.isInfinite() -> if (n > 0f) "Infinity" else "-Infinity"
        abs(n) < 0.001f && n != 0f -> "%.2e".format(n)
        abs(n) > 10000f -> "%.2e".format(n)
        else -> "%.4f".format(n).trimEnd('0').trimEnd('.')
    }
}

private class PagedListBrowsable(
    private val list: List<*>,
    private val title: String? = null,
    private val start: Int = 0,
    private val endExclusive: Int = list.size
) : Browsable {

    private val safeStart = start.coerceIn(0, list.size)
    private val safeEnd = endExclusive.coerceIn(safeStart, list.size)
    private val count = safeEnd - safeStart

    override fun heading(): String {
        val base = title ?: "List"
        return if (safeStart == 0 && safeEnd == list.size) "$base (${list.size})" else "$base ${rangeLabel(safeStart, safeEnd)}"
    }

    override fun dir(): Map<String, Any?>? {
        if (count <= 0) return null
        if (count <= H2_LIST_LEAF_SIZE) {
            val out = LinkedHashMap<String, Any?>(count)
            for (i in safeStart until safeEnd) {
                out["[$i]"] = wrapForInspector(list[i], title = "[$i]")
            }
            return out
        }

        val childSpan = computeChildSpan(count, H2_LIST_LEAF_SIZE)
        val out = LinkedHashMap<String, Any?>()
        var s = safeStart
        while (s < safeEnd) {
            val e = minOf(s + childSpan, safeEnd)
            out[rangeLabel(s, e)] = PagedListBrowsable(list, title = title, start = s, endExclusive = e)
            s = e
        }
        return out
    }

    override fun html(): String = "<i>${escapeHtml(heading())}</i>"
}

private data class MapEntryRecord(
    val index: Int,
    val key: Any?,
    val value: Any?
)

private interface MapEntrySource {
    val size: Int
    fun slice(start: Int, endExclusive: Int): List<MapEntryRecord>
}

private class SnapshotMapEntrySource(map: Map<*, *>) : MapEntrySource {
    private val entries: List<Pair<Any?, Any?>> = map.entries.map { it.key to it.value }
    override val size: Int get() = entries.size

    override fun slice(start: Int, endExclusive: Int): List<MapEntryRecord> {
        val safeStart = start.coerceIn(0, size)
        val safeEnd = endExclusive.coerceIn(safeStart, size)
        if (safeStart >= safeEnd) return emptyList()
        val out = ArrayList<MapEntryRecord>(safeEnd - safeStart)
        for (i in safeStart until safeEnd) {
            val (k, v) = entries[i]
            out.add(MapEntryRecord(i, k, v))
        }
        return out
    }
}

private class IteratingMapEntrySource(private val map: Map<*, *>) : MapEntrySource {
    override val size: Int get() = map.size

    override fun slice(start: Int, endExclusive: Int): List<MapEntryRecord> {
        val safeStart = start.coerceIn(0, size)
        val safeEnd = endExclusive.coerceIn(safeStart, size)
        if (safeStart >= safeEnd) return emptyList()
        val out = ArrayList<MapEntryRecord>(safeEnd - safeStart)
        var index = 0
        for ((k, v) in map) {
            if (index >= safeEnd) break
            if (index >= safeStart) out.add(MapEntryRecord(index, k, v))
            index++
        }
        return out
    }
}

private class MapEntryBrowsable(private val record: MapEntryRecord) : Browsable {
    override fun heading(): String = "Entry [${record.index}]"

    override fun dir(): Map<String, Any?> {
        return linkedMapOf(
            "key" to wrapForInspector(record.key, "key"),
            "value" to wrapForInspector(record.value, "value")
        )
    }

    override fun html(): String {
        return "<div>Entry [${record.index}]</div>"
    }
}

private class PagedMapBrowsable(
    private val source: MapEntrySource,
    private val title: String? = null,
    private val start: Int = 0,
    private val endExclusive: Int = source.size
) : Browsable {

    companion object {
        fun forMap(map: Map<*, *>, title: String? = null): PagedMapBrowsable {
            val src = if (map.size <= H2_MAP_SNAPSHOT_THRESHOLD) SnapshotMapEntrySource(map) else IteratingMapEntrySource(map)
            return PagedMapBrowsable(src, title = title, start = 0, endExclusive = src.size)
        }
    }

    private val safeStart = start.coerceIn(0, source.size)
    private val safeEnd = endExclusive.coerceIn(safeStart, source.size)
    private val count = safeEnd - safeStart

    override fun heading(): String {
        val base = title ?: "Map"
        return if (safeStart == 0 && safeEnd == source.size) "$base (${source.size})" else "$base ${rangeLabel(safeStart, safeEnd)}"
    }

    override fun dir(): Map<String, Any?>? {
        if (count <= 0) return null
        if (count <= H2_MAP_LEAF_SIZE) {
            val out = LinkedHashMap<String, Any?>(count)
            source.slice(safeStart, safeEnd).forEach { entry ->
                out["[${entry.index}] ${keyPreview(entry.key)}"] = MapEntryBrowsable(entry)
            }
            return out
        }

        val childSpan = computeChildSpan(count, H2_MAP_LEAF_SIZE)
        val out = LinkedHashMap<String, Any?>()
        var s = safeStart
        while (s < safeEnd) {
            val e = minOf(s + childSpan, safeEnd)
            out[rangeLabel(s, e)] = PagedMapBrowsable(source, title = title, start = s, endExclusive = e)
            s = e
        }
        return out
    }

    override fun html(): String = "<i>${escapeHtml(heading())}</i>"
}

private class FloatBufferBrowsable(
    buffer: FloatBuffer,
    private val title: String? = null
) : Browsable {

    private val readOnly: FloatBuffer = buffer.asReadOnlyBuffer()
    private val position: Int = buffer.position()
    private val limit: Int = buffer.limit()
    private val capacity: Int = buffer.capacity()

    override fun heading(): String = title ?: "FloatBuffer"

    override fun dir(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["position"] = position
        out["limit"] = limit
        out["capacity"] = capacity

        for (stride in 1..16) {
            val rows = ceilDiv(limit, stride)
            out["stride $stride ($rows rows)"] = FloatBufferRowsBrowsable(
                buffer = readOnly,
                stride = stride,
                rowStart = 0,
                rowEndExclusive = rows,
                title = "stride $stride"
            )
        }
        return out
    }

    override fun html(): String {
        return "<div>FloatBuffer pos=$position limit=$limit capacity=$capacity</div>"
    }
}

private class FloatBufferRowsBrowsable(
    private val buffer: FloatBuffer,
    private val stride: Int,
    private val rowStart: Int,
    private val rowEndExclusive: Int,
    private val title: String
) : Browsable {

    private val safeStride = stride.coerceAtLeast(1)
    private val totalValues = buffer.limit()
    private val totalRows = ceilDiv(totalValues, safeStride)
    private val safeStart = rowStart.coerceIn(0, totalRows)
    private val safeEnd = rowEndExclusive.coerceIn(safeStart, totalRows)
    private val rowCount = safeEnd - safeStart

    override fun heading(): String {
        return "$title ${rangeLabel(safeStart, safeEnd)}"
    }

    override fun dir(): Map<String, Any?>? {
        if (rowCount <= 0) return null
        if (rowCount <= H2_FLOAT_ROWS_LEAF_SIZE) return null

        val childSpan = computeChildSpan(rowCount, H2_FLOAT_ROWS_LEAF_SIZE)
        val out = LinkedHashMap<String, Any?>()
        var s = safeStart
        while (s < safeEnd) {
            val e = minOf(s + childSpan, safeEnd)
            out[rangeLabel(s, e)] = FloatBufferRowsBrowsable(
                buffer = buffer,
                stride = safeStride,
                rowStart = s,
                rowEndExclusive = e,
                title = title
            )
            s = e
        }
        return out
    }

    override fun html(): String {
        if (rowCount <= 0) return "<i>empty</i>"
        if (rowCount > H2_FLOAT_ROWS_LEAF_SIZE) {
            return "<div>${escapeHtml(heading())}</div>"
        }

        return FieldMarkdown.card(
            """
            {muted:rows $safeStart..${safeEnd - 1} | stride $safeStride}

            ${bufferPageTable(safeStart, safeEnd, safeStride, totalValues) { fmtFloat(buffer.get(it)) }}
            """
        )
    }
}

/**
 * One page of a strided buffer as a table: a row index, then [stride] right-aligned cells drawn by
 * [cell], with anything past [total] left blank.
 *
 * The Float and Int browsers below had a copy of this each, identical but for the cell formatter.
 * `maxRows` is pinned to the page height because the pager has already done the bounding — Table's
 * own elision would silently cut a page short.
 */
private fun bufferPageTable(
    start: Int,
    end: Int,
    stride: Int,
    total: Int,
    cell: (Int) -> String,
): String {
    val columns = (0 until stride).map { it.toString() }
    val page = Table(listOf("row") + columns)
        .rows(
            (start until end).map { row ->
                listOf<Any?>(row) + columns.indices.map { c ->
                    val index = row * stride + c
                    if (index < total) cell(index) else null
                }
            }
        )
        .role("row", CellRole.ACCENT)
        .align("row", Align.LEFT)
    columns.forEach { page.align(it, Align.RIGHT) }
    page.maxRows = (end - start).coerceAtLeast(1)
    return page.toHTML()
}

private class IntBufferBrowsable(
    buffer: IntBuffer,
    private val title: String? = null
) : Browsable {

    private val readOnly: IntBuffer = buffer.asReadOnlyBuffer()
    private val position: Int = buffer.position()
    private val limit: Int = buffer.limit()
    private val capacity: Int = buffer.capacity()

    override fun heading(): String = title ?: "IntBuffer"

    override fun dir(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["position"] = position
        out["limit"] = limit
        out["capacity"] = capacity

        for (stride in 1..16) {
            val rows = ceilDiv(limit, stride)
            out["stride $stride ($rows rows)"] = IntBufferRowsBrowsable(
                buffer = readOnly,
                stride = stride,
                rowStart = 0,
                rowEndExclusive = rows,
                title = "stride $stride"
            )
        }
        return out
    }

    override fun html(): String {
        return "<div>IntBuffer pos=$position limit=$limit capacity=$capacity</div>"
    }
}

private class IntBufferRowsBrowsable(
    private val buffer: IntBuffer,
    private val stride: Int,
    private val rowStart: Int,
    private val rowEndExclusive: Int,
    private val title: String
) : Browsable {

    private val safeStride = stride.coerceAtLeast(1)
    private val totalValues = buffer.limit()
    private val totalRows = ceilDiv(totalValues, safeStride)
    private val safeStart = rowStart.coerceIn(0, totalRows)
    private val safeEnd = rowEndExclusive.coerceIn(safeStart, totalRows)
    private val rowCount = safeEnd - safeStart

    override fun heading(): String {
        return "$title ${rangeLabel(safeStart, safeEnd)}"
    }

    override fun dir(): Map<String, Any?>? {
        if (rowCount <= 0) return null
        if (rowCount <= H2_FLOAT_ROWS_LEAF_SIZE) return null

        val childSpan = computeChildSpan(rowCount, H2_FLOAT_ROWS_LEAF_SIZE)
        val out = LinkedHashMap<String, Any?>()
        var s = safeStart
        while (s < safeEnd) {
            val e = minOf(s + childSpan, safeEnd)
            out[rangeLabel(s, e)] = IntBufferRowsBrowsable(
                buffer = buffer,
                stride = safeStride,
                rowStart = s,
                rowEndExclusive = e,
                title = title
            )
            s = e
        }
        return out
    }

    override fun html(): String {
        if (rowCount <= 0) return "<i>empty</i>"
        if (rowCount > H2_FLOAT_ROWS_LEAF_SIZE) {
            return "<div>${escapeHtml(heading())}</div>"
        }

        return FieldMarkdown.card(
            """
            {muted:rows $safeStart..${safeEnd - 1} | stride $safeStride}

            ${bufferPageTable(safeStart, safeEnd, safeStride, totalValues) { buffer.get(it).toString() }}
            """
        )
    }
}

private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun escapeHtmlAttr(text: String): String = escapeHtml(text)

val Any?.h2: String
    get() = LazyValueVisualizer.visualize(this)

inline fun <reified T : Any> registerH2Value(noinline adapter: (T) -> H2ValueSnapshot) {
    H2ValueRegistry.register(T::class.java, adapter)
}

internal fun h2GetRawValue(token: String): Any? = H2TokenRetention.fetch(token)

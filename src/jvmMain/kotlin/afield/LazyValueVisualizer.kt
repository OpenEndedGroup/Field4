package afield

import afield.server.Browsable
import afield.server.Inspector
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.abs

private const val H2_MAX_CHILDREN_PER_DIR = 96
private const val H2_LIST_LEAF_SIZE = 128
private const val H2_MAP_LEAF_SIZE = 96
private const val H2_FLOAT_ROWS_LEAF_SIZE = 128
private const val H2_IFRAME_HEIGHT_PX = 253
private const val H2_EMBED_EXPLORER_PATH = "/h2-explorer"
private const val H2_OUTLINER_PATH = "/tree"
private const val H2_COLUMNS_PATH = "/browse"
private const val H2_TOKEN_MAX = 256
private const val H2_TOKEN_TTL_MS = 10 * 60 * 1000L
private const val H2_MAP_SNAPSHOT_THRESHOLD = 20_000

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
    fun visualize(value: Any?): String {
        if (!isHeavyStructure(value)) return ValueVisualizer.visualize(value)

        val wrapped = wrapForInspector(value, title = "value")
        val token = "h2-${UUID.randomUUID()}"
        H2TokenRetention.remember(token, value)
        val outlinerPath = Inspector.linkPathFor(wrapped ?: "null", browsePath = H2_OUTLINER_PATH, uid = token)
        val columnsPath = Inspector.linkPathFor(wrapped ?: "null", browsePath = H2_COLUMNS_PATH, uid = token)
        val embeddedPath = "$H2_EMBED_EXPLORER_PATH?root=$token"

        return buildString {
            append("<div class='value-viz-h2' style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.35;display:flex;flex-direction:column;align-items:stretch;width:100%;height:100%;box-sizing:border-box;'>")
            append("<div style='display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:6px;'>")
            append("<span style='color:#9cf;'>")
            append(escapeHtml(summary(value)))
            append("</span>")
            append("<a href='")
            append(escapeHtmlAttr(outlinerPath))
            append("' target='_blank' style='color:#8cf;text-decoration:none;'>outliner</a>")
            append("<a href='")
            append(escapeHtmlAttr(columnsPath))
            append("' target='_blank' style='color:#777;text-decoration:none;'>columns</a>")
            append("</div>")
            append("<iframe src='")
            append(escapeHtmlAttr(embeddedPath))
            append("' loading='lazy' style='position:static;display:block;width:100%;height:${H2_IFRAME_HEIGHT_PX}px;flex:1 1 auto;min-height:0;border:1px solid #333;border-radius:4px;background:#000;'></iframe>")
            append("</div>")
        }
    }

    private fun summary(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "${value::class.simpleName ?: "Map"} (size=${value.size})"
        is List<*> -> "${value::class.simpleName ?: "List"} (size=${value.size})"
        is Array<*> -> "${value::class.simpleName ?: "Array"} (size=${value.size})"
        is FloatBuffer -> "FloatBuffer (pos=${value.position()}, limit=${value.limit()}, capacity=${value.capacity()})"
        else -> value::class.simpleName ?: value::class.java.simpleName
    }

    private fun isHeavyStructure(value: Any?): Boolean {
        return value is Map<*, *> || value is List<*> || value is Array<*> || value is FloatBuffer
    }
}

private fun wrapForInspector(value: Any?, title: String? = null): Any? {
    return when (value) {
        null -> null
        is PagedListBrowsable -> value
        is PagedMapBrowsable -> value
        is FloatBufferBrowsable -> value
        is Map<*, *> -> PagedMapBrowsable.forMap(value, title)
        is List<*> -> PagedListBrowsable(value, title = title)
        is Array<*> -> PagedListBrowsable(value.asList(), title = title)
        is FloatBuffer -> FloatBufferBrowsable(value, title = title)
        else -> value
    }
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

        return buildString {
            append("<div style='font-family:\"Iosevka Web\",monospace;font-size:11px;line-height:1.3;'>")
            append("<div style='margin-bottom:6px;color:#bbb;'>")
            append("rows ")
            append(safeStart)
            append("..")
            append(safeEnd - 1)
            append(" | stride ")
            append(safeStride)
            append("</div>")
            append("<table style='border-collapse:collapse;width:100%;'>")
            append("<thead><tr>")
            append("<th style='text-align:left;padding:2px 6px;border-bottom:1px solid #333;color:#aaa;'>row</th>")
            for (c in 0 until safeStride) {
                append("<th style='text-align:right;padding:2px 6px;border-bottom:1px solid #333;color:#aaa;'>")
                append(c)
                append("</th>")
            }
            append("</tr></thead><tbody>")

            for (row in safeStart until safeEnd) {
                append("<tr>")
                append("<td style='padding:2px 6px;border-bottom:1px solid #222;color:#9cf;'>")
                append(row)
                append("</td>")
                for (c in 0 until safeStride) {
                    val index = row * safeStride + c
                    append("<td style='padding:2px 6px;border-bottom:1px solid #222;text-align:right;color:#ddd;'>")
                    if (index < totalValues) {
                        append(escapeHtml(fmtFloat(buffer.get(index))))
                    } else {
                        append("&nbsp;")
                    }
                    append("</td>")
                }
                append("</tr>")
            }

            append("</tbody></table></div>")
        }
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

internal fun h2GetRawValue(token: String): Any? = H2TokenRetention.fetch(token)

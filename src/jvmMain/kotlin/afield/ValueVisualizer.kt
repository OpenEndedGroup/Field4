package afield

import afield.graphics.FLine
import org.joml.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.reflect.full.memberProperties

/**
 * Interface for objects that can provide their own HTML representation
 */
interface ToHTML {
    fun toHTML(): String
}

/**
 * Registry for custom value printers
 */
object ValuePrinterRegistry {
    private val printers = mutableMapOf<Class<*>, (Any) -> String>()

    /**
     * Register a custom printer for a specific type
     */
    fun <T : Any> register(clazz: Class<T>, printer: (T) -> String) {
        @Suppress("UNCHECKED_CAST")
        printers[clazz] = printer as (Any) -> String
    }

    /**
     * Get custom printer for a value, checking class hierarchy
     */
    internal fun getPrinter(value: Any): ((Any) -> String)? {
        // Check exact match first
        printers[value::class.java]?.let { return it }

        // Check superclasses and interfaces
        var clazz: Class<*>? = value::class.java
        while (clazz != null) {
            printers[clazz]?.let { return it }
            clazz = clazz.superclass
        }

        // Check interfaces
        value::class.java.interfaces.forEach { iface ->
            printers[iface]?.let { return it }
        }

        return null
    }
}

/**
 * Compact, monochrome visualizer for common Kotlin and JOML types
 */
object ValueVisualizer {
    private const val MAX_LIST_PREVIEW = 5
    private const val MAX_STRING_LENGTH = 100
    private const val MAX_MAP_ENTRIES = 5
    private const val MAX_DEPTH = 3
    private const val FLINE_THUMBNAIL_WIDTH = 136
    private const val FLINE_THUMBNAIL_HEIGHT = 84
    private const val FLINE_SIGNATURE_FULL_MAX_NODES = 80
    private const val FLINE_SIGNATURE_PREVIEW_MAX_NODES = 900
    private const val FLINE_THUMBNAIL_MAX_COMPLEXITY = 350
    private const val FLINE_LENGTH_MAX_COMPLEXITY = 1200
    private const val FLINE_SIGNATURE_HEAD = 42
    private const val FLINE_SIGNATURE_TAIL = 22

    private data class ThumbnailResult(
        val svg: String?,
        val reason: String? = null
    )

    /**
     * Visualize any value and return compact HTML representation
     */
    fun visualize(value: Any?): String {
        return buildString {
            append("<div class='value-viz' style='font-family: \"Iosevka Web\", monospace; font-size: 12px; color: #ccc; line-height: 1.4;'>")
            // Use identity hash set to track visited objects and detect circular references
            val visited = mutableSetOf<Int>()
            append(visualizeValue(value, 0, visited))
            append("</div>")
        }
    }

    private fun visualizeValue(value: Any?, depth: Int, visited: MutableSet<Int>): String {
        // Check depth limit
        if (depth > MAX_DEPTH) return "<span style='color: #888;'>…</span>"

        return when (value) {
            null -> "<span style='color: #888;'>null</span>"

            // Check for ToHTML interface first
            is ToHTML -> value.toHTML()

            // Check custom printers
            else -> {
                // For complex objects, check for circular references
                if (shouldCheckCircular(value)) {
                    val identityHash = System.identityHashCode(value)
                    if (identityHash in visited) {
                        return "<span style='color: #f88;'>[circular reference]</span>"
                    }
                    visited.add(identityHash)
                }

                ValuePrinterRegistry.getPrinter(value)?.invoke(value) ?: visualizeBuiltIn(value, depth, visited)
            }
        }
    }

    /**
     * Determine if a value should be checked for circular references.
     * Primitives and immutable types don't need circular reference checks.
     */
    private fun shouldCheckCircular(value: Any): Boolean {
        return when (value) {
            // Skip primitives and value types
            is Number, is Boolean, is Char, is String -> false
            // Skip JOML types (they are value types)
            is Vector2fc, is Vector3fc, is Vector4fc,
            is Vector2dc, is Vector3dc, is Vector4dc,
            is Quaternionfc, is Quaterniondc,
            is Matrix2fc, is Matrix3fc, is Matrix4fc,
            is Matrix2dc, is Matrix3dc, is Matrix4dc -> false
            // Check data classes, collections and other objects
            else -> true
        }
    }

    private fun visualizeBuiltIn(value: Any, depth: Int, visited: MutableSet<Int>): String = when (value) {
        // Primitives
        is Boolean -> "<span style='color: #f88;'>${value}</span>"
        is Number -> formatNumber(value)
        is Char -> "<span style='color: #8f8;'>'${escape(value.toString())}'</span>"
        is String -> formatString(value)

        // JOML vectors
        is Vector2fc -> formatVec2(value)
        is Vector3fc -> formatVec3(value)
        is Vector4fc -> formatVec4(value)
        is Vector2dc -> formatVec2(Vector2f(value.x().toFloat(), value.y().toFloat()))
        is Vector3dc -> formatVec3(Vector3f(value.x().toFloat(), value.y().toFloat(), value.z().toFloat()))
        is Vector4dc -> formatVec4(Vector4f(value.x().toFloat(), value.y().toFloat(), value.z().toFloat(), value.w().toFloat()))

        // JOML quaternions
        is Quaternionfc -> formatQuat(value)
        is Quaterniondc -> formatQuat(Quaternionf(value.x().toFloat(), value.y().toFloat(), value.z().toFloat(), value.w().toFloat()))

        // JOML matrices
        is Matrix2fc -> formatMatrix(value)
        is Matrix3fc -> formatMatrix(value)
        is Matrix4fc -> formatMatrix(value)
//        is Matrix2dc -> formatMatrix(Matrix2f(value))
//        is Matrix3dc -> formatMatrix(Matrix3f(value))
//        is Matrix4dc -> formatMatrix(Matrix4f(value))

        // Collections
        is Map<*, *> -> formatMap(value, depth, visited)
        is List<*> -> formatList(value, depth, visited)
        is Set<*> -> formatSet(value, depth, visited)
        is Array<*> -> formatArray(value, depth, visited)
        is ByteArray -> formatByteArray(value)
        is IntArray -> formatIntArray(value)
        is FloatArray -> formatFloatArray(value)
        is DoubleArray -> formatDoubleArray(value)
        is FLine -> formatFLine(value)

        // Data classes
        else -> if (value::class.isData) {
            formatDataClass(value, depth, visited)
        } else {
            // Fallback
            "<span style='color: #aaa;'>${escape(value.toString())}</span>"
        }
    }

    private fun formatNumber(n: Number): String {
        val formatted = when (n) {
            is Float -> {
                when {
                    n.isNaN() -> "NaN"
                    n.isInfinite() -> if (n > 0) "∞" else "-∞"
                    abs(n) < 0.001f && n != 0f -> "%.3e".format(n)
                    abs(n) > 10000f -> "%.3e".format(n)
                    else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
                }
            }
            is Double -> {
                when {
                    n.isNaN() -> "NaN"
                    n.isInfinite() -> if (n > 0) "∞" else "-∞"
                    abs(n) < 0.001 && n != 0.0 -> "%.3e".format(n)
                    abs(n) > 10000.0 -> "%.3e".format(n)
                    else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
                }
            }
            else -> n.toString()
        }
        return "<span style='color: #8cf;'>$formatted</span>"
    }

    private data class FLineBounds(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    ) {
        val width: Double get() = (maxX - minX).toDouble()
        val height: Double get() = (maxY - minY).toDouble()
    }

    private fun formatFLine(line: FLine): String {
        val nodes = line.nodes
        if (nodes.isEmpty()) {
            return "<span style='color: #9cf;'>FLine</span> <span style='color: #888;'>[empty]</span>"
        }

        var moveCount = 0
        var lineCount = 0
        var cubicCount = 0
        var closeCount = 0
        nodes.forEach { node ->
            when (node) {
                is FLine.MoveTo -> moveCount++
                is FLine.LineTo -> lineCount++
                is FLine.CubicTo -> cubicCount++
                is FLine.Close -> closeCount++
            }
        }

        val nodeCount = nodes.size
        val complexity = moveCount + lineCount + closeCount + cubicCount * 4
        val showThumbnail = complexity <= FLINE_THUMBNAIL_MAX_COMPLEXITY
        val showLength = complexity <= FLINE_LENGTH_MAX_COMPLEXITY

        val signature = if (nodeCount <= FLINE_SIGNATURE_PREVIEW_MAX_NODES) {
            buildString(nodeCount) {
                nodes.forEach { node ->
                    append(
                        when (node) {
                            is FLine.MoveTo -> 'M'
                            is FLine.LineTo -> 'L'
                            is FLine.CubicTo -> 'C'
                            is FLine.Close -> 'Z'
                        }
                    )
                }
            }
        } else null

        val shownSignature = signature?.let {
            if (nodeCount <= FLINE_SIGNATURE_FULL_MAX_NODES) {
                it
            } else {
                val head = it.take(FLINE_SIGNATURE_HEAD)
                val tail = it.takeLast(FLINE_SIGNATURE_TAIL)
                "$head … $tail"
            }
        }

        val length = if (showLength) runCatching { line.cursor(0.5f).lengthD().toDouble() }.getOrNull() else null
        val bounds = computeFLineBounds(line)
        val thumbnail = if (showThumbnail) renderFLineThumbnail(line) else ThumbnailResult(
            svg = null,
            reason = "complex path"
        )

        return buildString {
            append("<div style='display:inline-block; max-width: 320px;'>")
            append("<div><span style='color: #9cf;'>FLine</span> <span style='color: #666;'>(</span>")
            append(formatNumberToken(nodeCount))
            append("<span style='color: #666;'> nodes)</span></div>")

            append("<div style='color: #888;'>")
            append("M=")
            append(formatNumberToken(moveCount))
            append(" L=")
            append(formatNumberToken(lineCount))
            append(" C=")
            append(formatNumberToken(cubicCount))
            if (closeCount > 0) {
                append(" Z=")
                append(formatNumberToken(closeCount))
            }
            if (bounds != null) {
                append(" • b=")
                append(formatNumericLiteral(bounds.width))
                append("×")
                append(formatNumericLiteral(bounds.height))
            }
            if (length != null) {
                append(" • len=")
                append(formatNumericLiteral(length))
            }
            append("</div>")

            if (shownSignature != null) {
                append("<div style='color: #9f9; letter-spacing: 0.04em;'>")
                append(escape(shownSignature))
                append("</div>")
            }

            if (thumbnail.svg != null) {
                append("<div style='margin-top: 4px; border: 1px solid #333; border-radius: 4px; background: #111; width: ${FLINE_THUMBNAIL_WIDTH}px; height: ${FLINE_THUMBNAIL_HEIGHT}px; overflow: hidden;'>")
                append(thumbnail.svg)
                append("</div>")
            } else if (thumbnail.reason != null) {
                append("<div style='color: #666;'>thumbnail unavailable (${escape(thumbnail.reason)})</div>")
            }

            append("</div>")
        }
    }

    private fun renderFLineThumbnail(line: FLine): ThumbnailResult {
        val normalized = runCatching { normalizeFLineForThumbnail(line) }
            .getOrElse { return ThumbnailResult(svg = null, reason = "normalize failed") }

        val pathData = buildSvgPathData(normalized)
        if (pathData.isBlank()) return ThumbnailResult(svg = null, reason = "no drawable segments")

        val svg = buildString {
            append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $FLINE_THUMBNAIL_WIDTH $FLINE_THUMBNAIL_HEIGHT' width='$FLINE_THUMBNAIL_WIDTH' height='$FLINE_THUMBNAIL_HEIGHT'>")
            append("<rect x='0' y='0' width='$FLINE_THUMBNAIL_WIDTH' height='$FLINE_THUMBNAIL_HEIGHT' fill='transparent'/>")
            append("<path d='")
            append(pathData)
            append("' fill='none' stroke='#9cf' stroke-width='1.3' stroke-linecap='round' stroke-linejoin='round' vector-effect='non-scaling-stroke'/>")
            append("</svg>")
        }

        return ThumbnailResult(svg = svg)
    }

    private fun buildSvgPathData(line: FLine): String {
        if (line.nodes.isEmpty()) return ""

        val out = StringBuilder(line.nodes.size * 12)
        var hasDrawableSegment = false
        var penIsDown = false

        line.nodes.forEach { node ->
            when (node) {
                is FLine.MoveTo -> {
                    val p = node.to
                    if (!p.x.isFinite() || !p.y.isFinite()) return@forEach
                    out.append('M').append(svgNum(p.x)).append(' ').append(svgNum(p.y)).append(' ')
                    penIsDown = true
                }
                is FLine.LineTo -> {
                    val p = node.to
                    if (!p.x.isFinite() || !p.y.isFinite()) return@forEach
                    if (!penIsDown) {
                        out.append('M').append(svgNum(p.x)).append(' ').append(svgNum(p.y)).append(' ')
                        penIsDown = true
                    } else {
                        out.append('L').append(svgNum(p.x)).append(' ').append(svgNum(p.y)).append(' ')
                        hasDrawableSegment = true
                    }
                }
                is FLine.CubicTo -> {
                    val end = node.to
                    val c1 = node.c1
                    val c2 = node.c2
                    if (!end.x.isFinite() || !end.y.isFinite() || !c1.x.isFinite() || !c1.y.isFinite() || !c2.x.isFinite() || !c2.y.isFinite()) return@forEach
                    if (!penIsDown) {
                        out.append('M').append(svgNum(end.x)).append(' ').append(svgNum(end.y)).append(' ')
                        penIsDown = true
                    } else {
                        out.append('C')
                            .append(svgNum(c1.x)).append(' ').append(svgNum(c1.y)).append(' ')
                            .append(svgNum(c2.x)).append(' ').append(svgNum(c2.y)).append(' ')
                            .append(svgNum(end.x)).append(' ').append(svgNum(end.y)).append(' ')
                        hasDrawableSegment = true
                    }
                }
                is FLine.Close -> {
                    if (penIsDown) {
                        out.append('Z').append(' ')
                        hasDrawableSegment = true
                    }
                }
            }
        }

        return if (hasDrawableSegment) out.toString().trim() else ""
    }

    private fun svgNum(n: Float): String {
        return runCatching {
            if (!n.isFinite()) "0"
            else "%.3f".format(n).trimEnd('0').trimEnd('.')
        }.getOrDefault("0")
    }

    private fun normalizeFLineForThumbnail(
        line: FLine,
        width: Float = FLINE_THUMBNAIL_WIDTH.toFloat(),
        height: Float = FLINE_THUMBNAIL_HEIGHT.toFloat(),
        padding: Float = 6f
    ): FLine {
        val b = computeFLineBounds(line) ?: return line.copy()

        val sourceW = max((b.maxX - b.minX), 1f)
        val sourceH = max((b.maxY - b.minY), 1f)
        val contentW = max(width - padding * 2f, 1f)
        val contentH = max(height - padding * 2f, 1f)
        val scale = min(contentW / sourceW, contentH / sourceH)
        val offsetX = (width - sourceW * scale) * 0.5f - b.minX * scale
        val offsetY = (height - sourceH * scale) * 0.5f - b.minY * scale

        return line.transform { p ->
            Vector3f(
                p.x * scale + offsetX,
                p.y * scale + offsetY,
                p.z
            )
        }
    }

    private fun computeFLineBounds(line: FLine): FLineBounds? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var seen = false

        line.nodes.forEach { node ->
            node.storage.forEach { p ->
                if (!p.x.isFinite() || !p.y.isFinite()) return@forEach
                seen = true
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
        }

        if (!seen) return null
        return FLineBounds(minX, minY, maxX, maxY)
    }

    private fun formatString(s: String): String {
        val escaped = escape(s)
        return if (s.length > MAX_STRING_LENGTH) {
            val preview = escaped.substring(0, MAX_STRING_LENGTH)
            """<span style='color: #8f8;'>"$preview…"</span> <span style='color: #888;'>(</span>${formatNumberToken(s.length)}<span style='color: #888;'> chars)</span>"""
        } else {
            "<span style='color: #8f8;'>\"$escaped\"</span>"
        }
    }

    private fun formatTypeName(name: String, color: String): String =
        "<span style='color: $color;'>${escape(name)}</span>"

    private fun formatPunctuation(token: String): String =
        "<span style='color: #777;'>${escape(token)}</span>"

    private fun formatIdentifier(name: String): String =
        "<span style='color: #bbb;'>${escape(name)}</span>"

    private fun formatNumberToken(n: Int): String =
        "<span style='color: #8cf;'>$n</span>"

    private fun formatNumberToken(n: Long): String =
        "<span style='color: #8cf;'>$n</span>"

    private fun formatNumberToken(n: String): String =
        "<span style='color: #8cf;'>${escape(n)}</span>"

    private fun formatNumericLiteral(n: Float): String =
        "<span style='color: #8cf;'>${fmt(n)}</span>"

    private fun formatNumericLiteral(n: Double): String =
        "<span style='color: #8cf;'>${fmt(n)}</span>"

    private fun formatSizeSuffix(size: Int): String =
        """ <span style='color: #666;'>(</span>${formatNumberToken(size)}<span style='color: #666;'>)</span>"""

    private fun formatMoreSuffix(extra: Int, noun: String): String =
        """ <span style='color: #888;'>… +</span>${formatNumberToken(extra)}<span style='color: #888;'> more $noun</span>"""

    private fun formatStatsSummary(stats: Stats): String {
        return buildString {
            append(" <span style='color: #888;'>[μ=</span>")
            append(formatNumericLiteral(stats.mean))
            append("<span style='color: #888;'>, σ=</span>")
            append(formatNumericLiteral(stats.stdDev))
            append("<span style='color: #888;'>, range=</span>")
            append(formatNumericLiteral(stats.min))
            append("<span style='color: #888;'>…</span>")
            append(formatNumericLiteral(stats.max))
            append("<span style='color: #888;'>]</span>")
        }
    }

    private fun formatVec2(v: Vector2fc): String {
        return buildString {
            append(formatTypeName("vec2", "#fc8"))
            append(formatPunctuation("("))
            append(formatNumericLiteral(v.x()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.y()))
            append(formatPunctuation(")"))
        }
    }

    private fun formatVec3(v: Vector3fc): String {
        return buildString {
            append(formatTypeName("vec3", "#fc8"))
            append(formatPunctuation("("))
            append(formatNumericLiteral(v.x()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.y()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.z()))
            append(formatPunctuation(")"))
        }
    }

    private fun formatVec4(v: Vector4fc): String {
        return buildString {
            append(formatTypeName("vec4", "#fc8"))
            append(formatPunctuation("("))
            append(formatNumericLiteral(v.x()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.y()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.z()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.w()))
            append(formatPunctuation(")"))
        }
    }

    private fun formatQuat(q: Quaternionfc): String {
        return buildString {
            append(formatTypeName("quat", "#f8c"))
            append(formatPunctuation("("))
            append(formatNumericLiteral(q.x()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(q.y()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(q.z()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(q.w()))
            append(formatPunctuation(")"))
        }
    }

    private fun formatMatrix(m: Matrix2fc): String {
        return buildString {
            append(formatTypeName("mat2", "#cf8"))
            append(" ")
            append(formatPunctuation("["))
            append(formatNumericLiteral(m.m00()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(m.m10()))
            append(formatPunctuation(" / "))
            append(formatNumericLiteral(m.m01()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(m.m11()))
            append(formatPunctuation("]"))
        }
    }

    private fun formatMatrix(m: Matrix3fc): String {
        val det = m.determinant()
        return buildString {
            append(formatTypeName("mat3", "#cf8"))
            append(" ")
            append(formatPunctuation("["))
            append(formatIdentifier("det"))
            append(formatPunctuation("="))
            append(formatNumericLiteral(det))
            append(formatPunctuation("]"))
        }
    }

    private fun formatMatrix(m: Matrix4fc): String {
        val det = m.determinant()
        val translation = Vector3f()
        m.getTranslation(translation)
        return buildString {
            append(formatTypeName("mat4", "#cf8"))
            append(" ")
            append(formatPunctuation("["))
            append(formatIdentifier("det"))
            append(formatPunctuation("="))
            append(formatNumericLiteral(det))
            append(formatPunctuation(", "))
            append(formatIdentifier("t"))
            append(formatPunctuation("=("))
            append(formatNumericLiteral(translation.x))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(translation.y))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(translation.z))
            append(formatPunctuation(")"))
            append(formatPunctuation("]"))
        }
    }

    private fun formatList(list: List<*>, depth: Int, visited: MutableSet<Int>): String {
        if (list.isEmpty()) return "<span style='color: #888;'>[empty list]</span>"

        val size = list.size
        return buildString {
            append("<span style='color: #aaa;'>[</span>")

            val preview = list.take(MAX_LIST_PREVIEW)
            preview.forEachIndexed { i, item ->
                if (i > 0) append("<span style='color: #888;'>, </span>")
                append(visualizeValue(item, depth + 1, visited))
            }

            if (size > MAX_LIST_PREVIEW) {
                append(formatMoreSuffix(size - MAX_LIST_PREVIEW, "items"))

                // Add summary stats if all numbers
                if (list.all { it is Number }) {
                    @Suppress("UNCHECKED_CAST")
                    val numbers = list as List<Number>
                    val stats = calculateStats(numbers)
                    append(formatStatsSummary(stats))
                }
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(size))
        }
    }

    private fun formatSet(set: Set<*>, depth: Int, visited: MutableSet<Int>): String {
        if (set.isEmpty()) return "<span style='color: #888;'>{empty set}</span>"

        val size = set.size
        return buildString {
            append("<span style='color: #aaa;'>{</span>")

            val preview = set.take(MAX_LIST_PREVIEW)
            preview.forEachIndexed { i, item ->
                if (i > 0) append("<span style='color: #888;'>, </span>")
                append(visualizeValue(item, depth + 1, visited))
            }

            if (size > MAX_LIST_PREVIEW) {
                append(formatMoreSuffix(size - MAX_LIST_PREVIEW, "items"))
            }

            append("<span style='color: #aaa;'>}</span>")
            append(formatSizeSuffix(size))
        }
    }

    private fun formatMap(map: Map<*, *>, depth: Int, visited: MutableSet<Int>): String {
        if (map.isEmpty()) return "<span style='color: #888;'>{empty map}</span>"

        val size = map.size
        return buildString {
            append("<div style='margin-left: ${depth * 10}px;'>")
            append("<span style='color: #aaa;'>{</span>")

            val entries = map.entries.take(MAX_MAP_ENTRIES).toList()
            val hasMore = size > MAX_MAP_ENTRIES

            entries.forEachIndexed { i, (key, value) ->
                val needsComma = i < entries.lastIndex || hasMore
                append("<div style='margin-left: 10px; display: grid; grid-template-columns: max-content max-content 1fr; column-gap: 6px; align-items: start;'>")
                append("<div>")
                append(visualizeValue(key, depth + 1, visited))
                append("</div>")
                append("<div style='color: #888; justify-self: center;'>→</div>")
                append("<div style='min-width: 0; text-align: left; overflow-wrap: anywhere; padding-left: 1.5em; text-indent:-1.5em;'>")
                append(visualizeValue(value, depth + 1, visited))
                if (needsComma) append("<span style='color: #888;'>,</span>")
                append("</div>")
                append("</div>")
            }

            if (hasMore) {
                append("<div style='margin-left: 10px;'>")
                append(formatMoreSuffix(size - MAX_MAP_ENTRIES, "entries"))
                append("</div>")
            }

            append("<span style='color: #aaa;'>}</span>")
            append(formatSizeSuffix(size))
            append("</div>")
        }
    }

    private fun formatArray(arr: Array<*>, depth: Int, visited: MutableSet<Int>): String {
        return formatList(arr.toList(), depth, visited)
    }

    private fun formatByteArray(arr: ByteArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty bytes]</span>"

        return buildString {
            append("<span style='color: #c8f;'>bytes[</span>")
            val preview = arr.take(8)
            append(preview.joinToString(" ") { formatNumberToken("%02x".format(it)) })
            if (arr.size > 8) {
                append(" <span style='color: #888;'>…</span>")
            }
            append("<span style='color: #c8f;'>]</span>")
            append(formatSizeSuffix(arr.size))
        }
    }

    private fun formatIntArray(arr: IntArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty ints]</span>"

        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(MAX_LIST_PREVIEW)
            append("<span style='color: #8cf;'>")
            append(preview.joinToString(", "))
            append("</span>")

            if (arr.size > MAX_LIST_PREVIEW) {
                append(formatMoreSuffix(arr.size - MAX_LIST_PREVIEW, "items"))
                val stats = calculateStats(arr.map { it.toDouble() })
                append(formatStatsSummary(stats))
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(arr.size))
        }
    }

    private fun formatFloatArray(arr: FloatArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty floats]</span>"

        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(MAX_LIST_PREVIEW)
            append("<span style='color: #8cf;'>")
            append(preview.joinToString(", ") { fmt(it) })
            append("</span>")

            if (arr.size > MAX_LIST_PREVIEW) {
                append(formatMoreSuffix(arr.size - MAX_LIST_PREVIEW, "items"))
                val stats = calculateStats(arr.map { it.toDouble() })
                append(formatStatsSummary(stats))
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(arr.size))
        }
    }

    private fun formatDoubleArray(arr: DoubleArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty doubles]</span>"

        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(MAX_LIST_PREVIEW)
            append("<span style='color: #8cf;'>")
            append(preview.joinToString(", ") { fmt(it) })
            append("</span>")

            if (arr.size > MAX_LIST_PREVIEW) {
                append(formatMoreSuffix(arr.size - MAX_LIST_PREVIEW, "items"))
                val stats = calculateStats(arr.toList())
                append(formatStatsSummary(stats))
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(arr.size))
        }
    }

    private fun formatDataClass(obj: Any, depth: Int, visited: MutableSet<Int>): String {
        val className = obj::class.simpleName ?: "DataClass"
        
        return try {
            val properties = obj::class.memberProperties
            if (properties.isEmpty()) {
                return "<span style='color: #cc8;'>$className()</span>"
            }

            return buildString {
                append("<div style='margin-left: ${depth * 10}px;'>")
                append("<span style='color: #cc8;'>$className</span> <span style='color: #aaa;'>{</span>")

                val entries = properties.toList()
                entries.forEachIndexed { i, prop ->
                    val needsComma = i < entries.lastIndex
                    val propValue = try {
                        prop.getter.call(obj)
                    } catch (e: Exception) {
                        "[error: ${e.message}]"
                    }
                    
                    append("<div style='margin-left: 10px; display: grid; grid-template-columns: max-content max-content 1fr; column-gap: 6px; align-items: start;'>")
                    append("<div style='color: #aaf;'>${prop.name}</div>")
                    append("<div style='color: #888; justify-self: center;'>→</div>")
                    append("<div style='min-width: 0; text-align: left; overflow-wrap: anywhere; padding-left: 1.5em; text-indent:-1.5em;'>")
                    append(visualizeValue(propValue, depth + 1, visited))
                    if (needsComma) append("<span style='color: #888;'>,</span>")
                    append("</div>")
                    append("</div>")
                }

                append("<span style='color: #aaa;'>}</span>")
                append("</div>")
            }
        } catch (e: Exception) {
            "<span style='color: #f88;'>$className [reflection failed: ${e.message}]</span>"
        }
    }


    private fun fmt(n: Float): String {
        return when {
            n.isNaN() -> "NaN"
            n.isInfinite() -> if (n > 0) "∞" else "-∞"
            abs(n) < 0.001f && n != 0f -> "%.2e".format(n)
            abs(n) > 1000f -> "%.2e".format(n)
            else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
        }
    }

    private fun fmt(n: Double): String {
        return when {
            n.isNaN() -> "NaN"
            n.isInfinite() -> if (n > 0) "∞" else "-∞"
            abs(n) < 0.001 && n != 0.0 -> "%.2e".format(n)
            abs(n) > 1000.0 -> "%.2e".format(n)
            else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
        }
    }

    private fun escape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private data class Stats(
        val mean: Double,
        val stdDev: Double,
        val min: Double,
        val max: Double
    )

    private fun calculateStats(numbers: List<Number>): Stats {
        if (numbers.isEmpty()) return Stats(0.0, 0.0, 0.0, 0.0)

        val doubles = numbers.map { it.toDouble() }
        val mean = doubles.average()
        val variance = doubles.map { (it - mean).pow(2) }.average()
        val stdDev = sqrt(variance)
        val min = doubles.minOrNull() ?: 0.0
        val max = doubles.maxOrNull() ?: 0.0

        return Stats(mean, stdDev, min, max)
    }
}

/**
 * Extension function to visualize a value in a Box's display output
 */
fun Box.viz(channel: String = "viz", value: Any?) {
    display(channel, ValueVisualizer.visualize(value))
}

val Any?.h : String
    get() = ValueVisualizer.visualize(this)

/**
 * Register a custom printer for a type
 */
inline fun <reified T : Any> registerValuePrinter(noinline printer: (T) -> String) {
    ValuePrinterRegistry.register(T::class.java, printer)
}

package afield

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Vector4fc
import afield.graphics.FLine
import afield.stdlib.Functions
import afield.stdlib.linalg.*
import space.kscience.plotly.Plot
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

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
 * Render a Kotlin value as a compact, escaped HTML fragment for Field output areas.
 *
 * Dispatch order is [ToHTML], a [ValuePrinterRegistry] match, built-in formatters, then reflective
 * or `toString()` fallback. Built-ins cover primitives, arrays and collections, Field linalg,
 * [FLine], [Functions.Fn], Plotly plots, and other repository types. Numeric series may add summary
 * statistics and guarded SVG sparklines; vectors, matrices, FLines, and functions may add guarded
 * thumbnails. The renderer uses a curated per-type color palette, not a monochrome theme.
 *
 * Lists, maps, strings, fields, nesting, plots, and thumbnails have explicit bounds below. Circular
 * detection is identity-based and scoped to the current recursion path, so a repeated acyclic value
 * is not mislabeled. User-supplied [ToHTML] and registered-printer output is trusted HTML; ordinary
 * strings, characters, keys, and reflective values are escaped by their formatters.
 *
 * [visualize] returns the outer `.value-viz` element. [Box.viz] sends it to a display channel,
 * [Any.h] returns it directly, and [registerValuePrinter] extends the registry process-wide.
 */
object ValueVisualizer {
    private const val MAX_LIST_PREVIEW = 20
    private const val LIST_INLINE_ELISION_THRESHOLD = 30
    private const val LIST_EXPANSION_CHUNK = 8
    private const val MAX_STRING_LENGTH = 100
    private const val STRING_EXPANSION_CHUNK = 160
    private const val MAX_MAP_ENTRIES = 5
    private const val MAP_EXPANSION_CHUNK = 6
    private const val MAX_DEPTH = 3
    private const val MAX_OBJECT_FIELDS = 8

    // Only elide struct fields/entries when it actually saves space: a "… +N more" line costs
    // one line, so hiding 1–2 items behind it saves nothing (and "+1 more fields" reads badly).
    // Elide only when the hidden count would be > ELISION_SLACK, i.e. at least 3.
    private const val ELISION_SLACK = 2
    private const val OBJECT_EXPANSION_CHUNK = 8
    private const val BYTE_EXPANSION_CHUNK = 16
    private const val FLINE_THUMBNAIL_WIDTH = 136
    private const val FLINE_THUMBNAIL_HEIGHT = 84
    private const val FLINE_SIGNATURE_FULL_MAX_NODES = 80
    private const val FLINE_SIGNATURE_PREVIEW_MAX_NODES = 900
    private const val FLINE_THUMBNAIL_MAX_COMPLEXITY = 350
    private const val FLINE_LENGTH_MAX_COMPLEXITY = 1200
    private const val FLINE_SIGNATURE_HEAD = 42
    private const val FLINE_SIGNATURE_TAIL = 22
    private const val FN_PLOT_WIDTH = 248
    private const val FN_PLOT_HEIGHT = 104
    private const val FN_PLOT_GUTTER_LEFT = 38
    private const val FN_PLOT_GUTTER_BOTTOM = 14
    private const val FN_PLOT_PAD_TOP = 8
    private const val FN_PLOT_PAD_RIGHT = 8
    private const val FN_PLOT_SAMPLES = 160
    private const val VEC_THUMBNAIL_SIZE = 84
    private const val MATRIX_CELL_SIZE = 24
    private const val SPARKLINE_WIDTH = 120
    private const val SPARKLINE_HEIGHT = 22
    private const val SPARKLINE_MAX_SAMPLES = 240
    private const val SPARKLINE_MIN_POINTS = 3
    private const val INLINE_STRUCT_MAX_FIELDS = 3
    private const val INLINE_STRUCT_MAX_CHARS = 64
    private const val INDENT_STEP_PX = 12
    private const val BOX_SYMBOL = "\u25A1"
    private val UNDERSCORE_UUID_REGEX = Regex(
        "^_?([0-9a-f]{8})_([0-9a-f]{4})_([0-9a-f]{4})_([0-9a-f]{4})_([0-9a-f]{12})$",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    private val BOX_ID_TOKEN_REGEX = Regex(
        pattern = "(?<![A-Za-z0-9_])(_[0-9a-f]{8}_[0-9a-f]{4}_[0-9a-f]{4}_[0-9a-f]{4}_[0-9a-f]{12})(__(\\d+))?(@[0-9a-f]+)?(?![A-Za-z0-9_])",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private data class ThumbnailResult(
        val svg: String?,
        val reason: String? = null
    )

    /** One rendered line of a struct tree: an indentation level plus inline HTML content. */
    private data class VizLine(val indent: Int, val html: String, val isClose: Boolean = false)

    /** How a single field value renders relative to its key's line. */
    private sealed interface FieldValue {
        /** Fits on the key's line (leaf, function, or a collapsed struct). */
        data class Inline(val html: String) : FieldValue
        /** Opaque multi-line HTML (thumbnail, plot, long list) — placed as-is, not cuddled. */
        data class Block(val html: String) : FieldValue
        /** A cuddlable multi-line struct, as a relative-indent line model. */
        data class Struct(val lines: List<VizLine>) : FieldValue
    }

    /** A raw (unrendered) struct field: its key HTML, the value, and its declared type if known. */
    private data class RawField(val keyHtml: String, val rawValue: Any?, val declaredType: KType? = null)

    private data class BoxReference(
        val uid: String,
        val name: String
    )

    private data class RenderedListElement(
        val html: String,
        val repeatCount: Int = 1
    )

    /**
     * Visualize any value and return compact HTML representation
     */
    fun visualize(value: Any?, appendable: Boolean = false): String {
        return buildString {
            append("<div class='value-viz")
            if (appendable) append(" afield-appendable-output")
            append("' style='font-family: \"Iosevka Web\", monospace; font-size: 12px; color: #ccc; line-height: 1.4; font-feature-settings: \"calt\" 1, \"dlig\" 1; font-variant-ligatures: contextual;'>")
            // Track only the current recursion path, using reference equality.
            val visited = newIdentitySet()
            append(visualizeValue(value, 0, visited))
            append("</div>")
        }
    }

    private fun newIdentitySet(): MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())

    private fun visualizeValue(
        value: Any?,
        depth: Int,
        visited: MutableSet<Any>,
        respectDepthLimit: Boolean = true
    ): String {
        // Check depth limit
        if (respectDepthLimit && depth > MAX_DEPTH) return formatDepthLimitedValue(value)

        return when (value) {
            null -> "<span style='color: #888;'>null</span>"

            // Check for ToHTML interface first
            is ToHTML -> rewriteBoxReferencesInHtml(value.toHTML())

            // Check custom printers
            else -> {
                // For complex objects, check for circular references
                val trackCircular = shouldCheckCircular(value)
                if (trackCircular) {
                    if (value in visited) {
                        return "<span style='color: #f88;'>[circular reference]</span>"
                    }
                    visited.add(value)
                }

                try {
                    ValuePrinterRegistry.getPrinter(value)?.invoke(value) ?: visualizeBuiltIn(value, depth, visited, respectDepthLimit)
                } finally {
                    if (trackCircular) visited.remove(value)
                }
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
            // Skip linalg value types (they are value types)
            is Vector2fc, is Vector3fc, is Vector4fc,
            is Vector2dc, is Vector3dc, is Vector4dc,
            is Quaternionfc,
            is Matrix2fc, is Matrix3fc, is Matrix4fc,
            is Matrix2dc, is Matrix3dc, is Matrix4dc -> false
            // Check data classes, collections and other objects
            else -> true
        }
    }

    private fun visualizeBuiltIn(
        value: Any,
        depth: Int,
        visited: MutableSet<Any>,
        respectDepthLimit: Boolean
    ): String = when (value) {
        // Primitives
        is Boolean -> "<span style='color: #f88;'>${value}</span>"
        is Number -> formatNumber(value)
        is Char -> "<span style='color: #8f8;'>'${escape(value.toString())}'</span>"
        is String -> formatString(value)

        // Field vectors
        is Vector2fc -> formatVec2(value)
        is Vector3fc -> formatVec3(value)
        is Vector4fc -> formatVec4(value)
        is Vector2dc -> formatVec2(Vector2f(value.x().toFloat(), value.y().toFloat()))
        is Vector3dc -> formatVec3(Vector3f(value.x().toFloat(), value.y().toFloat(), value.z().toFloat()))
        is Vector4dc -> formatVec4(Vector4f(value.x().toFloat(), value.y().toFloat(), value.z().toFloat(), value.w().toFloat()))

        // Field quaternions
        is Quaternionfc -> formatQuat(value)

        // Field matrices — both the single-precision (…fc) and the generated
        // double-precision (…dc) families render as diverging heatmaps. (There is still no
        // double-precision quaternion type, so quaternions stay single-precision above.)
        is Matrix2fc -> formatMatrix(value)
        is Matrix3fc -> formatMatrix(value)
        is Matrix4fc -> formatMatrix(value)
        is Matrix2dc -> formatMatrix(value)
        is Matrix3dc -> formatMatrix(value)
        is Matrix4dc -> formatMatrix(value)

        // Collections
        is Map<*, *> -> formatMap(value, depth, visited, respectDepthLimit)
        is List<*> -> formatList(value, depth, visited, respectDepthLimit = respectDepthLimit)
        is Set<*> -> formatSet(value, depth, visited, respectDepthLimit)
        is Array<*> -> formatArray(value, depth, visited, respectDepthLimit)
        is ByteArray -> formatByteArray(value)
        is IntArray -> formatIntArray(value)
        is FloatArray -> formatFloatArray(value)
        is DoubleArray -> formatDoubleArray(value)
        is FLine -> formatFLine(value)
        is Functions.Domain -> formatDomain(value)
        is Functions.Fn -> formatFn(value, depth, visited, respectDepthLimit)
        is afield.stdlib.FnFitRun -> formatFnFitRun(value)
        is Box -> formatBox(value)
        is Plot -> value.toHtml()
        is Function<*> -> formatFunction(value, null)

        // Data classes
        else -> if (value::class.isData) {
            formatDataClass(value, depth, visited, respectDepthLimit)
        } else {
            formatFallbackObject(value)
        }
    }

    private fun formatDepthLimitedValue(value: Any?): String = when (value) {
        null -> "<span style='color: #888;'>null</span>"
        is Boolean -> "<span style='color: #f88;'>${value}</span>"
        is Number -> formatNumber(value)
        is Char -> "<span style='color: #8f8;'>'${escape(value.toString())}'</span>"
        is String -> formatString(value)
        else -> lazyInlineExpanderHtml(
            value = value,
            label = "…",
            kind = "full",
            offset = 0,
            title = value::class.simpleName ?: value::class.java.simpleName
        )
    }

    private fun formatFallbackObject(value: Any): String {
        val className = value::class.simpleName ?: value::class.java.simpleName.ifBlank { "Object" }
        val rendered = value.toString()
        val preview = rendered.take(MAX_STRING_LENGTH)

        return buildString {
            append("<span style='color: #cc8;'>")
            append(escape(className))
            append("</span>")
            if (preview.isNotBlank()) {
                append("<span style='color: #666;'> </span>")
                append("<span style='color: #aaa;'>")
                append(escape(preview))
                append("</span>")
            }
            if (rendered.length > MAX_STRING_LENGTH) {
                append(" ")
                append(
                    lazyInlineExpanderHtml(
                        value = value,
                        label = "… +${rendered.length - MAX_STRING_LENGTH} chars",
                        kind = "full",
                        offset = 0,
                        title = className
                    )
                )
            }
        }
    }

    /**
     * Render a function/lambda value as its type signature — e.g. `(Double) -> Double` —
     * rather than the JVM's `…$$Lambda/0x…@hash` toString, which is noise to a human.
     * Prefers the statically declared property type (which carries the real parameter and
     * return types); falls back to arity when only the runtime value is known.
     */
    private fun formatFunction(value: Any, declaredType: KType?): String {
        val sig = declaredType?.let { functionSignatureFromType(it) }
            ?: functionSignatureFromArity(value)
        return "<span style='color: #8cc8b8;'>${escape(sig)}</span>"
    }

    private fun functionSignatureFromType(type: KType): String? {
        val classifier = type.classifier as? KClass<*> ?: return null
        if (classifier.simpleName?.startsWith("Function") != true) return null
        val args = type.arguments
        if (args.isEmpty()) return "() -> ?"
        val params = args.dropLast(1).joinToString(", ") { renderTypeProjection(it) }
        return "($params) -> ${renderTypeProjection(args.last())}"
    }

    private fun renderTypeProjection(proj: KTypeProjection): String {
        val t = proj.type ?: return "*"
        functionSignatureFromType(t)?.let { return it }
        val name = (t.classifier as? KClass<*>)?.simpleName ?: "?"
        return name + if (t.isMarkedNullable) "?" else ""
    }

    private fun functionSignatureFromArity(value: Any): String {
        val arity = functionArity(value) ?: return "ƒ"
        return "(" + List(arity) { "·" }.joinToString(", ") + ") -> ·"
    }

    /** Best-effort arity by walking the value's type hierarchy for a `kotlin.jvm.functions.FunctionN`. */
    private fun functionArity(value: Any): Int? {
        val queue = ArrayDeque<Class<*>>()
        val seen = HashSet<Class<*>>()
        queue.add(value.javaClass)
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            val n = c.name
            if (n.startsWith("kotlin.jvm.functions.Function")) {
                return n.removePrefix("kotlin.jvm.functions.Function").toIntOrNull()
            }
            c.superclass?.let { queue.add(it) }
            c.interfaces.forEach { queue.add(it) }
        }
        return null
    }

    private fun formatBox(box: Box): String {
        val displayName = box.name.ifBlank { box.id }
        val escapedUid = escapeHtmlText(box.id)
        return buildString {
            append("<a href='#box:")
            append(escapedUid)
            append("' data-box-uid='")
            append(escapedUid)
            append("' style='text-decoration:none;cursor:pointer;'>")
            append("<span style='color:#fff;font-weight:300;'>")
            append(BOX_SYMBOL)
            append("</span>")
            append("<span style='color:#fff;font-weight:300;font-style:italic;'>")
            append(escapeHtmlText(displayName))
            append("</span>")
            append("</a>")
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

    private fun compressSignatureRuns(sig: String): String = buildString {
        var i = 0
        while (i < sig.length) {
            val ch = sig[i]
            if (ch in "MLCZ") {
                var run = 1
                while (i + run < sig.length && sig[i + run] == ch) run++
                if (run > 6) {
                    append("(${ch}x$run)")
                } else {
                    repeat(run) { append(ch) }
                }
                i += run
            } else {
                append(ch)
                i++
            }
        }
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
            val raw = if (nodeCount <= FLINE_SIGNATURE_FULL_MAX_NODES) {
                it
            } else {
                val head = it.take(FLINE_SIGNATURE_HEAD)
                val tail = it.takeLast(FLINE_SIGNATURE_TAIL)
                "$head … $tail"
            }
            compressSignatureRuns(raw)
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

    /**
     * Renders an [Functions.Fn] as its usual reflective field tree, with a small,
     * safe line plot appended below — the same spirit as [formatFLine]'s thumbnail.
     *
     * The reflective tree (domain / evalFn / graph) is kept verbatim via
     * [formatDataClass]; the plot is a bonus that never replaces it. Rendering the
     * plot is wrapped in [runCatching] so a misbehaving `evalFn` can only cost us
     * the thumbnail, never the whole value display.
     */
    /**
     * A [Functions.Domain] is fully described by its bounds, so render it compactly rather than
     * as an 8-field reflective tree (center/length/isFinite/… are all redundant): an unbounded
     * domain is just `Domain { ∞ }`, a point is `Domain { x }`, everything else `Domain { a..b }`
     * (with ±∞ for a half-open end).
     */
    /**
     * Concise render of a [afield.stdlib.FnFitRun]: the RMSE it started at and landed on, the
     * budget it spent, and a sparkline of the whole loss trajectory — instead of dumping the
     * (often hundreds long) `steps`/`losses` arrays that the generic data-class renderer would.
     */
    private fun formatFnFitRun(run: afield.stdlib.FnFitRun): String = buildString {
        val arrow = if (run.improved) "↓" else "→"
        val arrowColour = if (run.improved) "#8f8" else "#f88"
        append("<div>")
        append("<span style='color: #cc8;'>FnFitRun</span> ")
        append("<span style='color: #aaa;'>RMSE </span>")
        append("<span style='color: #8cf;'>${fmt(run.initialRmse)}</span>")
        append("<span style='color: $arrowColour;'> $arrow </span>")
        append("<span style='color: #8f8;'>${fmt(run.finalRmse)}</span>")
        append(
            "<span style='color: #888;'>  ${run.iterations} steps · ${run.evaluations} evals · " +
                "${run.parameters.size} params</span>"
        )
        append("</div>")
        if (run.losses.size >= SPARKLINE_MIN_POINTS) {
            append("<div style='margin-top: 2px;'>")
            append(renderNumericSparkline(run.losses))
            append("</div>")
        }
    }

    private fun formatDomain(d: Functions.Domain): String {
        val inner = when {
            d._isEmpty -> "<span style='color: #888;'>∅</span>"
            !d.min.isFinite() && !d.max.isFinite() -> "<span style='color: #888;'>∞</span>"
            d.min == d.max -> domainBound(d.min)
            else -> domainBound(d.min) + "<span style='color: #777;'>..</span>" + domainBound(d.max)
        }
        return "<span style='color: #cc8;'>Domain</span> <span style='color: #aaa;'>{</span> " +
            inner + " <span style='color: #aaa;'>}</span>"
    }

    private fun domainBound(v: Double): String = when {
        v == Double.POSITIVE_INFINITY -> "<span style='color: #888;'>∞</span>"
        v == Double.NEGATIVE_INFINITY -> "<span style='color: #888;'>-∞</span>"
        else -> formatNumericLiteral(v)
    }

    private fun formatFn(
        fn: Functions.Fn,
        depth: Int,
        visited: MutableSet<Any>,
        respectDepthLimit: Boolean
    ): String {
        val tree = formatDataClass(fn, depth, visited, respectDepthLimit)
        val plot = runCatching { renderFnPlot(fn) }.getOrNull()
        if (plot == null) return tree
        return buildString {
            append("<div>")
            append(tree)
            append(
                "<div style='margin-left: 12px; margin-top: 4px; border: 1px solid #333; " +
                    "border-radius: 4px; background: #111; width: ${FN_PLOT_WIDTH}px; " +
                    "height: ${FN_PLOT_HEIGHT}px; overflow: hidden;'>"
            )
            append(plot)
            append("</div>")
            append("</div>")
        }
    }

    /**
     * Samples [fn] over a finite window of its domain and builds an SVG polyline.
     *
     * Mirrors the NaN/∞-safe sampling used by `Fn.plotHtml`: unbounded domains fall
     * back to a small preview window, non-finite samples become gaps in the path,
     * and an all-non-finite function yields no plot (returns `null`).
     */
    private fun renderFnPlot(fn: Functions.Fn): String? {
        // Resolve a finite x-window (plotSafe): finite non-empty domain as-is, a
        // point domain widened to ±0.5, anything unbounded falls back to 0..1.
        val d = fn.domain
        val xMin: Double
        val xMax: Double
        when {
            d.isFinite && d.length > 0.0 -> { xMin = d.min; xMax = d.max }
            d.min.isFinite() && d.length == 0.0 -> { xMin = d.min - 0.5; xMax = d.min + 0.5 }
            else -> { xMin = 0.0; xMax = 1.0 }
        }
        if (!xMin.isFinite() || !xMax.isFinite() || xMax <= xMin) return null

        val count = FN_PLOT_SAMPLES
        val step = (xMax - xMin) / (count - 1).toDouble()
        val ys = DoubleArray(count) { i ->
            val x = xMin + step * i.toDouble()
            runCatching { fn.evalSafe(x, outside = Double.NaN, edge = Functions.EdgeMode.ZERO) }
                .getOrElse { Double.NaN }
                .let { if (it.isFinite()) it else Double.NaN }
        }

        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (y in ys) {
            if (!y.isFinite()) continue
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        if (!lo.isFinite() || !hi.isFinite()) return null // nothing finite to draw

        var yLo = lo
        var yHi = hi
        if (yLo == yHi) {
            val pad = max(1.0, abs(yLo) * 0.1)
            yLo -= pad; yHi += pad
        } else {
            val pad = (yHi - yLo) * 0.08
            yLo -= pad; yHi += pad
        }

        val plotW = (FN_PLOT_WIDTH - FN_PLOT_GUTTER_LEFT - FN_PLOT_PAD_RIGHT).toDouble()
        val plotH = (FN_PLOT_HEIGHT - FN_PLOT_PAD_TOP - FN_PLOT_GUTTER_BOTTOM).toDouble()
        val left = FN_PLOT_GUTTER_LEFT.toDouble()
        val top = FN_PLOT_PAD_TOP.toDouble()
        val ySpan = yHi - yLo

        fun px(i: Int): Double = left + (i.toDouble() / (count - 1).toDouble()) * plotW
        fun py(y: Double): Double = top + (1.0 - (y - yLo) / ySpan) * plotH

        // Build the path, lifting the pen across non-finite gaps.
        val path = StringBuilder(count * 12)
        var penDown = false
        for (i in 0 until count) {
            val y = ys[i]
            if (!y.isFinite()) { penDown = false; continue }
            val cx = plotNum(px(i)); val cy = plotNum(py(y))
            if (!penDown) { path.append('M').append(cx).append(' ').append(cy).append(' '); penDown = true }
            else path.append('L').append(cx).append(' ').append(cy).append(' ')
        }
        if (path.isBlank()) return null

        return buildString {
            append(
                "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $FN_PLOT_WIDTH $FN_PLOT_HEIGHT' " +
                    "width='$FN_PLOT_WIDTH' height='$FN_PLOT_HEIGHT'>"
            )
            // zero baseline, when the range straddles it
            if (yLo < 0.0 && yHi > 0.0) {
                val zy = plotNum(py(0.0))
                append("<line x1='${plotNum(left)}' y1='$zy' x2='${plotNum(left + plotW)}' y2='$zy' stroke='#444' stroke-width='1' stroke-dasharray='2 2'/>")
            }
            // the curve
            append("<path d='")
            append(path.toString().trim())
            append("' fill='none' stroke='#6cf' stroke-width='1.3' stroke-linecap='round' stroke-linejoin='round' vector-effect='non-scaling-stroke'/>")
            // faint value-range labels (top-left = max, bottom-left = min)
            append("<text x='3' y='${FN_PLOT_PAD_TOP + 3}' fill='#666' font-size='8' font-family='monospace'>${escape(plotLabel(yHi))}</text>")
            append("<text x='3' y='${FN_PLOT_HEIGHT - FN_PLOT_GUTTER_BOTTOM}' fill='#666' font-size='8' font-family='monospace'>${escape(plotLabel(yLo))}</text>")
            // faint domain labels along the bottom
            append("<text x='${plotNum(left)}' y='${FN_PLOT_HEIGHT - 3}' fill='#666' font-size='8' font-family='monospace'>${escape(plotLabel(xMin))}</text>")
            append("<text x='$FN_PLOT_WIDTH' y='${FN_PLOT_HEIGHT - 3}' fill='#666' font-size='8' font-family='monospace' text-anchor='end'>${escape(plotLabel(xMax))}</text>")
            append("</svg>")
        }
    }

    private fun plotNum(n: Double): String = runCatching {
        if (!n.isFinite()) "0" else "%.2f".format(n).trimEnd('0').trimEnd('.')
    }.getOrDefault("0")

    private fun plotLabel(n: Double): String = runCatching {
        when {
            !n.isFinite() -> if (n > 0) "∞" else "-∞"
            n == 0.0 -> "0"
            abs(n) >= 100000.0 || abs(n) < 0.001 -> "%.1e".format(n)
            else -> "%.3f".format(n).trimEnd('0').trimEnd('.')
        }
    }.getOrDefault("?")

    private fun formatString(s: String): String {
        return if (s.length > MAX_STRING_LENGTH) {
            val preview = escape(s.take(MAX_STRING_LENGTH))
            buildString {
                append("<span style='color: #8f8;'>")
                append(preview)
                append("</span>")
                append(" ")
                append(
                    lazyInlineExpanderHtml(
                        value = s,
                        label = "… +${s.length - MAX_STRING_LENGTH} chars",
                        kind = "string",
                        offset = MAX_STRING_LENGTH,
                        title = "String"
                    )
                )
                append(" <span style='color: #888;'>(</span>")
                append(formatNumberToken(s.length))
                append("<span style='color: #888;'> chars)</span>")
            }
        } else {
            "<span style='color: #8f8;'>${escape(s)}</span>"
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

    private fun isBlockVisualization(html: String): Boolean =
        html.trimStart().startsWith("<div")

    private fun shouldShowInlineSeparator(previousHtml: String, currentHtml: String): Boolean =
        !isBlockVisualization(previousHtml) && !isBlockVisualization(currentHtml)

    private fun visibleTextLength(html: String): Int =
        html.replace(Regex("<[^>]*>"), "").length

    /** `key -> ` opener shared by inline and multi-line struct rows. */
    private fun keyArrow(keyHtml: String, keyColor: String?): String {
        val keyStyle = if (keyColor != null) "color: $keyColor;" else ""
        return "<span style='$keyStyle'>$keyHtml</span><span style='color: #777;'> -&gt; </span>"
    }

    private const val BRACE_OPEN = "<span style='color: #aaa;'>{</span>"
    private const val BRACE_CLOSE = "<span style='color: #aaa;'>}</span>"

    /**
     * Render one field value. Struct-like values (maps, data classes) recurse as
     * [FieldValue.Struct] so their braces can cuddle with the parent; other values defer to
     * [visualizeValue] and are classified inline vs. block. Honors the depth limit and the
     * circular-reference guard exactly as [visualizeValue] would.
     */
    private fun renderFieldValue(
        raw: Any?,
        declaredType: KType?,
        depth: Int,
        visited: MutableSet<Any>,
        respectDepthLimit: Boolean
    ): FieldValue {
        if (raw is Function<*>) return FieldValue.Inline(formatFunction(raw, declaredType))
        if (raw != null && respectDepthLimit && depth > MAX_DEPTH) {
            return FieldValue.Inline(formatDepthLimitedValue(raw))
        }
        // Types with a dedicated compact renderer (e.g. Domain) must fall through to
        // visualizeValue rather than be expanded as a generic reflective struct here.
        val isStructLike = raw != null && raw !is ToHTML && raw !is Functions.Domain &&
            ValuePrinterRegistry.getPrinter(raw) == null &&
            ((raw is Map<*, *> && raw.isNotEmpty()) || raw::class.isData)
        if (isStructLike) {
            if (raw in visited) return FieldValue.Inline("<span style='color: #f88;'>[circular reference]</span>")
            visited.add(raw!!)
            try {
                return when (raw) {
                    is Map<*, *> -> structValueFromMap(raw, depth, visited, respectDepthLimit)
                    else -> structValueFromDataClass(raw, depth, visited, respectDepthLimit)
                }
            } finally {
                visited.remove(raw)
            }
        }
        val html = visualizeValue(raw, depth + 1, visited, respectDepthLimit)
        return if (isBlockVisualization(html)) FieldValue.Block(html) else FieldValue.Inline(html)
    }

    /**
     * Core struct renderer shared by data classes and maps. Collapses onto ONE line when the
     * struct is small and none of its fields are block-level; otherwise emits a line model
     * with cuddled braces — a single struct-valued field cuddles its opening brace
     * (`Outer { k -> Inner {`) and trailing closing braces collapse together (`}}`) instead of
     * marching down the left margin.
     */
    private fun renderStructValue(
        typeName: String?,
        typeColor: String,
        keyColor: String?,
        rawFields: List<RawField>,
        hasMore: Boolean,
        trailing: String,
        depth: Int,
        visited: MutableSet<Any>,
        respectDepthLimit: Boolean,
        moreHtml: () -> String
    ): FieldValue {
        val header = if (typeName != null) "<span style='color: $typeColor;'>${escape(typeName)}</span> " else ""
        val fields = rawFields.map { rf ->
            rf.keyHtml to renderFieldValue(rf.rawValue, rf.declaredType, depth + 1, visited, respectDepthLimit)
        }

        // Inline collapse: small, no block/struct children, short.
        val inlineable = !hasMore && fields.size <= INLINE_STRUCT_MAX_FIELDS &&
            fields.all { it.second is FieldValue.Inline }
        if (inlineable) {
            val approxLen = fields.sumOf {
                visibleTextLength(it.first) + visibleTextLength((it.second as FieldValue.Inline).html) + 4
            }
            if (fields.isEmpty() || approxLen <= INLINE_STRUCT_MAX_CHARS) {
                return FieldValue.Inline(buildString {
                    append(header).append(BRACE_OPEN)
                    if (fields.isNotEmpty()) {
                        append(" ")
                        append(fields.joinToString("<span style='color:#666;'>, </span>") {
                            keyArrow(it.first, keyColor) + (it.second as FieldValue.Inline).html
                        })
                        append(" ")
                    }
                    append(BRACE_CLOSE).append(trailing)
                })
            }
        }

        return FieldValue.Struct(buildStructLines(header, keyColor, fields, hasMore, trailing, moreHtml))
    }

    /** Build the (relative-indent) line model for a multi-line struct, applying brace cuddling. */
    private fun buildStructLines(
        header: String,
        keyColor: String?,
        fields: List<Pair<String, FieldValue>>,
        hasMore: Boolean,
        trailing: String,
        moreHtml: () -> String
    ): List<VizLine> {
        val out = ArrayList<VizLine>()

        // Open-cuddle: a struct with exactly one struct-valued field pulls its opening brace
        // up onto the child's opening line (`Outer { k -> Inner {`).
        if (!hasMore && fields.size == 1 && fields[0].second is FieldValue.Struct) {
            val (keyHtml, fv) = fields[0]
            val lines = (fv as FieldValue.Struct).lines
            lines.forEachIndexed { i, l ->
                when (i) {
                    0 -> out.add(VizLine(0, "$header$BRACE_OPEN " + keyArrow(keyHtml, keyColor) + l.html))
                    else -> out.add(VizLine(l.indent, l.html, l.isClose))
                }
            }
            // Only brace-only closes (no trailing size suffix) cuddle; a `} (n)` stays on its own line.
        out.add(VizLine(0, BRACE_CLOSE + trailing, isClose = trailing.isEmpty()))
            return cuddleCloses(out)
        }

        out.add(VizLine(0, "$header$BRACE_OPEN"))
        fields.forEach { (keyHtml, fv) -> out.addAll(fieldToLines(keyHtml, fv, keyColor, 1)) }
        if (hasMore) out.add(VizLine(1, moreHtml()))
        // Only brace-only closes (no trailing size suffix) cuddle; a `} (n)` stays on its own line.
        out.add(VizLine(0, BRACE_CLOSE + trailing, isClose = trailing.isEmpty()))
        return cuddleCloses(out)
    }

    /** A single field rendered as lines at [baseIndent]: key on the first line, nested struct re-based under it. */
    private fun fieldToLines(keyHtml: String, fv: FieldValue, keyColor: String?, baseIndent: Int): List<VizLine> =
        when (fv) {
            is FieldValue.Inline -> listOf(VizLine(baseIndent, keyArrow(keyHtml, keyColor) + fv.html))
            is FieldValue.Block -> listOf(VizLine(baseIndent, keyArrow(keyHtml, keyColor) + fv.html))
            is FieldValue.Struct -> fv.lines.mapIndexed { i, l ->
                if (i == 0) VizLine(baseIndent, keyArrow(keyHtml, keyColor) + l.html)
                else VizLine(l.indent + baseIndent, l.html, l.isClose)
            }
        }

    /**
     * Render a run of already-collected fields for a lazy "… +N more" expansion. The expander it
     * replaces already sits inside an indented line, so fields render at relative indent 0 (they
     * inherit that indent) — otherwise the arriving fields get an extra level of indentation.
     */
    private fun renderContinuationFields(
        rawFields: List<RawField>,
        keyColor: String?,
        depth: Int,
        visited: MutableSet<Any>,
        moreExpander: String?
    ): String {
        val out = ArrayList<VizLine>()
        rawFields.forEach { rf ->
            val fv = renderFieldValue(rf.rawValue, rf.declaredType, depth + 1, visited, respectDepthLimit = false)
            out.addAll(fieldToLines(rf.keyHtml, fv, keyColor, 0))
        }
        if (moreExpander != null) out.add(VizLine(0, moreExpander))
        return linesToHtml(out)
    }

    /** Collapse runs of adjacent closing-brace lines into one `}}` line at the outermost indent. */
    private fun cuddleCloses(lines: List<VizLine>): List<VizLine> {
        val res = ArrayList<VizLine>()
        for (l in lines) {
            val prev = res.lastOrNull()
            if (l.isClose && prev != null && prev.isClose) {
                res[res.size - 1] = VizLine(l.indent, prev.html + l.html, isClose = true)
            } else {
                res.add(l)
            }
        }
        return res
    }

    private fun linesToHtml(lines: List<VizLine>): String = buildString {
        append("<div>")
        lines.forEach {
            append("<div style='padding-left: ${it.indent * INDENT_STEP_PX}px;'>")
            append(it.html)
            append("</div>")
        }
        append("</div>")
    }

    private fun fieldValueToHtml(fv: FieldValue): String = when (fv) {
        is FieldValue.Inline -> fv.html
        is FieldValue.Block -> fv.html
        is FieldValue.Struct -> linesToHtml(fv.lines)
    }

    private fun previewCountForListLike(size: Int): Int {
        if (size <= 0) return 0
        return if (size > LIST_INLINE_ELISION_THRESHOLD) MAX_LIST_PREVIEW else size
    }

    private fun formatMoreSuffix(
        extra: Int,
        noun: String,
        value: Any?,
        title: String,
        kind: String,
        offset: Int,
        depth: Int
    ): String {
        if (extra <= 0) return ""
        return " " + lazyInlineExpanderHtml(
            value = value,
            label = "… +$extra more $noun",
            kind = kind,
            offset = offset,
            depth = depth,
            title = title
        )
    }

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
        val text = buildString {
            append(formatTypeName("vec2", "#fc8"))
            append(formatPunctuation("("))
            append(formatNumericLiteral(v.x()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.y()))
            append(formatPunctuation(")"))
        }
        return withLinalgThumbnail(text, VEC_THUMBNAIL_SIZE, VEC_THUMBNAIL_SIZE) {
            renderVec2Thumbnail(v.x(), v.y())
        }
    }

    private fun formatVec3(v: Vector3fc): String {
        val text = buildString {
            append(formatTypeName("vec3", "#fc8"))
            append(formatPunctuation("("))
            append(formatNumericLiteral(v.x()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.y()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(v.z()))
            append(formatPunctuation(")"))
        }
        return withLinalgThumbnail(text, VEC_THUMBNAIL_SIZE, VEC_THUMBNAIL_SIZE) {
            renderVec3Thumbnail(v.x(), v.y(), v.z())
        }
    }

    private fun formatVec4(v: Vector4fc): String {
        val text = buildString {
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
        // The dominant payload is the text; the panel just draws the (x, y) arrow.
        return withLinalgThumbnail(text, VEC_THUMBNAIL_SIZE, VEC_THUMBNAIL_SIZE) {
            renderVec2Thumbnail(v.x(), v.y())
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
        val text = buildString {
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
        val rows = listOf(
            listOf(m.m00(), m.m10()),
            listOf(m.m01(), m.m11())
        )
        val side = 2 * MATRIX_CELL_SIZE
        return withLinalgThumbnail(text, side, side) { renderMatrixThumbnail(rows) }
    }

    private fun formatMatrix(m: Matrix3fc): String {
        val det = m.determinant()
        val text = buildString {
            append(formatTypeName("mat3", "#cf8"))
            append(" ")
            append(formatPunctuation("["))
            append(formatIdentifier("det"))
            append(formatPunctuation("="))
            append(formatNumericLiteral(det))
            append(formatPunctuation("]"))
        }
        val rows = listOf(
            listOf(m.m00(), m.m10(), m.m20()),
            listOf(m.m01(), m.m11(), m.m21()),
            listOf(m.m02(), m.m12(), m.m22())
        )
        val side = 3 * MATRIX_CELL_SIZE
        return withLinalgThumbnail(text, side, side) { renderMatrixThumbnail(rows) }
    }

    private fun formatMatrix(m: Matrix4fc): String {
        val det = m.determinant()
        val translation = Vector3f()
        m.getTranslation(translation)
        val text = buildString {
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
        val rows = listOf(
            listOf(m.m00(), m.m10(), m.m20(), m.m30()),
            listOf(m.m01(), m.m11(), m.m21(), m.m31()),
            listOf(m.m02(), m.m12(), m.m22(), m.m32()),
            listOf(m.m03(), m.m13(), m.m23(), m.m33())
        )
        val side = 4 * MATRIX_CELL_SIZE
        return withLinalgThumbnail(text, side, side) { renderMatrixThumbnail(rows) }
    }

    // ---- double-precision matrices ------------------------------------------
    // Siblings of the …fc formatters above for the generated f64 matrix family
    // (Matrix2d/Matrix3d/Matrix4d). Heatmap cells take Float, so the f64 entries are
    // narrowed only for the thumbnail; the terse header prints the full-precision values.

    private fun formatMatrix(m: Matrix2dc): String {
        val text = buildString {
            append(formatTypeName("mat2d", "#cf8"))
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
        val rows = listOf(
            listOf(m.m00().toFloat(), m.m10().toFloat()),
            listOf(m.m01().toFloat(), m.m11().toFloat())
        )
        val side = 2 * MATRIX_CELL_SIZE
        return withLinalgThumbnail(text, side, side) { renderMatrixThumbnail(rows) }
    }

    private fun formatMatrix(m: Matrix3dc): String {
        val det = m.determinant()
        val text = buildString {
            append(formatTypeName("mat3d", "#cf8"))
            append(" ")
            append(formatPunctuation("["))
            append(formatIdentifier("det"))
            append(formatPunctuation("="))
            append(formatNumericLiteral(det))
            append(formatPunctuation("]"))
        }
        val rows = listOf(
            listOf(m.m00().toFloat(), m.m10().toFloat(), m.m20().toFloat()),
            listOf(m.m01().toFloat(), m.m11().toFloat(), m.m21().toFloat()),
            listOf(m.m02().toFloat(), m.m12().toFloat(), m.m22().toFloat())
        )
        val side = 3 * MATRIX_CELL_SIZE
        return withLinalgThumbnail(text, side, side) { renderMatrixThumbnail(rows) }
    }

    private fun formatMatrix(m: Matrix4dc): String {
        val det = m.determinant()
        val text = buildString {
            append(formatTypeName("mat4d", "#cf8"))
            append(" ")
            append(formatPunctuation("["))
            append(formatIdentifier("det"))
            append(formatPunctuation("="))
            append(formatNumericLiteral(det))
            append(formatPunctuation(", "))
            append(formatIdentifier("t"))
            append(formatPunctuation("=("))
            append(formatNumericLiteral(m.m30()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(m.m31()))
            append(formatPunctuation(", "))
            append(formatNumericLiteral(m.m32()))
            append(formatPunctuation(")"))
            append(formatPunctuation("]"))
        }
        val rows = listOf(
            listOf(m.m00().toFloat(), m.m10().toFloat(), m.m20().toFloat(), m.m30().toFloat()),
            listOf(m.m01().toFloat(), m.m11().toFloat(), m.m21().toFloat(), m.m31().toFloat()),
            listOf(m.m02().toFloat(), m.m12().toFloat(), m.m22().toFloat(), m.m32().toFloat()),
            listOf(m.m03().toFloat(), m.m13().toFloat(), m.m23().toFloat(), m.m33().toFloat())
        )
        val side = 4 * MATRIX_CELL_SIZE
        return withLinalgThumbnail(text, side, side) { renderMatrixThumbnail(rows) }
    }

    // ---- linalg thumbnails --------------------------------------------------

    /**
     * Appends a small, GUARDED inline SVG thumbnail beneath the terse text [head],
     * mirroring the discipline of [formatFn]/[renderFLineThumbnail]: the text is kept
     * verbatim and the thumbnail is a bonus. [render] runs inside [runCatching]; if it
     * throws or returns `null` (e.g. a non-finite value), the plain inline text is
     * returned unchanged so a misbehaving value costs only the thumbnail.
     */
    private fun withLinalgThumbnail(
        head: String,
        width: Int,
        height: Int,
        render: () -> String?
    ): String {
        val svg = runCatching(render).getOrNull() ?: return head
        return buildString {
            append("<div>")
            append(head)
            append(
                "<div style='margin-top: 4px; border: 1px solid #333; border-radius: 4px; " +
                    "background: #111; width: ${width}px; height: ${height}px; overflow: hidden;'>"
            )
            append(svg)
            append("</div>")
            append("</div>")
        }
    }

    /**
     * A 2D vector as an auto-scaled arrow from a centered origin, over faint axes.
     * Non-finite components yield `null` (thumbnail skipped); a zero vector draws just
     * the axes and an origin dot.
     */
    private fun renderVec2Thumbnail(x: Float, y: Float): String? {
        if (!x.isFinite() || !y.isFinite()) return null
        val size = VEC_THUMBNAIL_SIZE.toDouble()
        val cx = size / 2.0
        val cy = size / 2.0
        val pad = 12.0
        val avail = size / 2.0 - pad
        val maxComp = max(abs(x), abs(y)).toDouble()
        val scale = if (maxComp > 1e-6) avail / maxComp else 0.0
        val tipX = cx + x * scale
        val tipY = cy - y * scale // screen y grows downward; math +y points up
        return buildString {
            append(svgOpen(VEC_THUMBNAIL_SIZE, VEC_THUMBNAIL_SIZE))
            appendAxis(this, 4.0, cy, size - 4.0, cy) // horizontal
            appendAxis(this, cx, 4.0, cx, size - 4.0) // vertical
            if (scale <= 0.0) {
                append("<circle cx='${plotNum(cx)}' cy='${plotNum(cy)}' r='2.5' fill='#6cf'/>")
            } else {
                appendArrow(this, cx, cy, tipX, tipY, "#6cf")
            }
            append("</svg>")
        }
    }

    /**
     * A 3D vector as an isometric arrow with a fixed-pixel x/y/z reference frame. The
     * frame is decoupled from the data scale so it can never overflow; the arrow is
     * auto-scaled to fit. Non-finite components yield `null`.
     */
    private fun renderVec3Thumbnail(x: Float, y: Float, z: Float): String? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val size = VEC_THUMBNAIL_SIZE.toDouble()
        val cx = size / 2.0
        val cy = size / 2.0
        val cos30 = 0.8660254
        val sin30 = 0.5
        // Isometric projection into screen-space offsets (before scaling): +x goes
        // right-down, +z left-down, +y straight up.
        fun projX(vx: Double, vy: Double, vz: Double) = (vx - vz) * cos30
        fun projY(vx: Double, vy: Double, vz: Double) = (vx + vz) * sin30 - vy
        val px = projX(x.toDouble(), y.toDouble(), z.toDouble())
        val py = projY(x.toDouble(), y.toDouble(), z.toDouble())
        val pad = 14.0
        val avail = size / 2.0 - pad
        val extent = max(abs(px), abs(py))
        val scale = if (extent > 1e-6) avail / extent else 0.0
        val frameLen = 16.0
        return buildString {
            append(svgOpen(VEC_THUMBNAIL_SIZE, VEC_THUMBNAIL_SIZE))
            // reference frame (fixed pixel length), faint, with tiny axis labels
            fun frame(dx: Double, dy: Double, label: String) {
                val ex = cx + dx * frameLen
                val ey = cy + dy * frameLen
                append("<line x1='${plotNum(cx)}' y1='${plotNum(cy)}' x2='${plotNum(ex)}' y2='${plotNum(ey)}' stroke='#3a3a3a' stroke-width='1'/>")
                append("<text x='${plotNum(cx + dx * (frameLen + 4.0))}' y='${plotNum(cy + dy * (frameLen + 4.0) + 2.5)}' fill='#666' font-size='7' font-family='monospace' text-anchor='middle'>${escape(label)}</text>")
            }
            frame(cos30, sin30, "x")
            frame(-cos30, sin30, "z")
            frame(0.0, -1.0, "y")
            if (scale <= 0.0) {
                append("<circle cx='${plotNum(cx)}' cy='${plotNum(cy)}' r='2.5' fill='#6cf'/>")
            } else {
                appendArrow(this, cx, cy, cx + px * scale, cy + py * scale, "#6cf")
            }
            append("</svg>")
        }
    }

    /**
     * A matrix as a compact colored cell grid — a diverging heatmap around zero.
     * Neutral `#222` at zero, blending to `#6cf` for positive and `#f83` for negative
     * entries by |value| / maxAbs. Non-finite entries get a distinct gray cell. Values
     * are labelled for 2×2 and 3×3 grids (4×4 is colors-only to avoid clutter).
     */
    private fun renderMatrixThumbnail(rows: List<List<Float>>): String? {
        val n = rows.size
        if (n == 0) return null
        val cell = MATRIX_CELL_SIZE
        val side = n * cell
        var maxAbs = 0.0
        rows.forEach { row -> row.forEach { v -> if (v.isFinite()) maxAbs = max(maxAbs, abs(v.toDouble())) } }
        val showText = n <= 4
        return buildString {
            append(svgOpen(side, side))
            for (r in rows.indices) {
                val row = rows[r]
                for (c in row.indices) {
                    val v = row[c]
                    val x = c * cell
                    val y = r * cell
                    val fill = matrixCellColor(v, maxAbs)
                    append("<rect x='$x' y='$y' width='$cell' height='$cell' fill='$fill' stroke='#111' stroke-width='1'/>")
                    if (showText && v.isFinite()) {
                        append(
                            "<text x='${x + cell / 2}' y='${y + cell / 2 + 3}' fill='#eee' font-size='7' " +
                                "font-family='monospace' text-anchor='middle'>${escape(matrixCellLabel(v))}</text>"
                        )
                    }
                }
            }
            append("</svg>")
        }
    }

    private fun svgOpen(width: Int, height: Int): String =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $width $height' width='$width' height='$height'>"

    private fun appendAxis(sb: StringBuilder, x1: Double, y1: Double, x2: Double, y2: Double) {
        sb.append("<line x1='${plotNum(x1)}' y1='${plotNum(y1)}' x2='${plotNum(x2)}' y2='${plotNum(y2)}' stroke='#2f2f2f' stroke-width='1'/>")
    }

    /** Draws an arrow shaft plus a filled triangular head from ([x1],[y1]) to ([x2],[y2]). */
    private fun appendArrow(sb: StringBuilder, x1: Double, y1: Double, x2: Double, y2: Double, color: String) {
        sb.append("<line x1='${plotNum(x1)}' y1='${plotNum(y1)}' x2='${plotNum(x2)}' y2='${plotNum(y2)}' stroke='$color' stroke-width='1.6' stroke-linecap='round'/>")
        val ang = atan2(y2 - y1, x2 - x1)
        val headLen = 6.0
        val spread = 0.42
        val hx1 = x2 - headLen * cos(ang - spread)
        val hy1 = y2 - headLen * sin(ang - spread)
        val hx2 = x2 - headLen * cos(ang + spread)
        val hy2 = y2 - headLen * sin(ang + spread)
        sb.append(
            "<polygon points='${plotNum(x2)},${plotNum(y2)} ${plotNum(hx1)},${plotNum(hy1)} " +
                "${plotNum(hx2)},${plotNum(hy2)}' fill='$color'/>"
        )
    }

    private fun matrixCellColor(v: Float, maxAbs: Double): String {
        if (!v.isFinite()) return "#4a3030"
        if (maxAbs <= 0.0) return "#222222"
        val t = min(1.0, abs(v.toDouble()) / maxAbs)
        // neutral (34,34,34) -> #6cf (102,204,255) for +, -> #f83 (248,136,51) for -
        val target = if (v >= 0f) Triple(102, 204, 255) else Triple(248, 136, 51)
        val rr = (34 + (target.first - 34) * t).toInt()
        val gg = (34 + (target.second - 34) * t).toInt()
        val bb = (34 + (target.third - 34) * t).toInt()
        return "#%02x%02x%02x".format(rr.coerceIn(0, 255), gg.coerceIn(0, 255), bb.coerceIn(0, 255))
    }

    private fun matrixCellLabel(v: Float): String = runCatching {
        val a = abs(v)
        when {
            a == 0f -> "0"
            a >= 1000f || a < 0.01f -> "%.0e".format(v)
            else -> "%.2f".format(v).trimEnd('0').trimEnd('.')
        }
    }.getOrDefault("?")

    private fun formatRepeatSuffix(count: Int): String {
        if (count <= 1) return ""
        return " <span style='color: #777;'>same ref x</span>${formatNumberToken(count)}"
    }

    private fun sameReferenceRunLength(list: List<*>, start: Int, endExclusive: Int): Int {
        if (start >= endExclusive) return 0
        val first = list[start] ?: return 1
        if (!shouldCheckCircular(first)) return 1

        var count = 1
        while (start + count < endExclusive && list[start + count] === first) {
            count++
        }
        return count
    }

    private fun renderListPreviewElements(
        list: List<*>,
        start: Int,
        previewEndExclusive: Int,
        listEndExclusive: Int,
        depth: Int,
        visited: MutableSet<Any>,
        respectDepthLimit: Boolean
    ): Pair<List<RenderedListElement>, Int> {
        val elements = mutableListOf<RenderedListElement>()
        var i = start
        while (i < previewEndExclusive) {
            val runLength = sameReferenceRunLength(list, i, listEndExclusive).coerceAtLeast(1)
            val rendered = visualizeValue(list[i], depth + 1, visited, respectDepthLimit)
            elements += RenderedListElement(rendered, runLength)
            i += runLength
        }
        return elements to i
    }

    private fun StringBuilder.appendRenderedListElements(elements: List<RenderedListElement>) {
        elements.forEachIndexed { i, element ->
            if (i > 0 && shouldShowInlineSeparator(elements[i - 1].html, element.html)) {
                append("<span style='color: #888;'>, </span>")
            }
            append(element.html)
            append(formatRepeatSuffix(element.repeatCount))
        }
    }

    private fun formatList(
        list: List<*>,
        depth: Int,
        visited: MutableSet<Any>,
        expandValue: Any? = list,
        expandTitle: String = list::class.simpleName ?: "List",
        respectDepthLimit: Boolean = true
    ): String {
        if (list.isEmpty()) return "<span style='color: #888;'>[empty list]</span>"

        val size = list.size
        val previewCount = previewCountForListLike(size)
        return buildString {
            append("<span style='color: #aaa;'>[</span>")

            val (renderedPreview, nextOffset) = renderListPreviewElements(
                list = list,
                start = 0,
                previewEndExclusive = previewCount,
                listEndExclusive = size,
                depth = depth,
                visited = visited,
                respectDepthLimit = respectDepthLimit
            )
            appendRenderedListElements(renderedPreview)

            if (size > nextOffset) {
                append(
                    formatMoreSuffix(
                        size - nextOffset,
                        "items",
                        expandValue,
                        expandTitle,
                        "list",
                        nextOffset,
                        depth
                    )
                )

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
            append(renderNumericSparkline(numericSeriesForSparkline(list)))
        }
    }

    private fun formatSet(set: Set<*>, depth: Int, visited: MutableSet<Any>, respectDepthLimit: Boolean): String {
        if (set.isEmpty()) return "<span style='color: #888;'>{empty set}</span>"

        val size = set.size
        val previewCount = previewCountForListLike(size)
        return buildString {
            append("<span style='color: #aaa;'>{</span>")

            val preview = set.take(previewCount)
            val renderedPreview = preview.map { item ->
                visualizeValue(item, depth + 1, visited, respectDepthLimit)
            }
            renderedPreview.forEachIndexed { i, rendered ->
                if (i > 0 && shouldShowInlineSeparator(renderedPreview[i - 1], rendered)) {
                    append("<span style='color: #888;'>, </span>")
                }
                append(rendered)
            }

            if (size > previewCount) {
                append(
                    formatMoreSuffix(
                        size - previewCount,
                        "items",
                        set,
                        set::class.simpleName ?: "Set",
                        "list",
                        previewCount,
                        depth
                    )
                )
            }

            append("<span style='color: #aaa;'>}</span>")
            append(formatSizeSuffix(size))
        }
    }

    private fun formatMap(map: Map<*, *>, depth: Int, visited: MutableSet<Any>, respectDepthLimit: Boolean): String {
        if (map.isEmpty()) return "<span style='color: #888;'>{empty map}</span>"
        return fieldValueToHtml(structValueFromMap(map, depth, visited, respectDepthLimit))
    }

    private fun structValueFromMap(
        map: Map<*, *>, depth: Int, visited: MutableSet<Any>, respectDepthLimit: Boolean
    ): FieldValue {
        val size = map.size
        val hasMore = size > MAX_MAP_ENTRIES + ELISION_SLACK
        val shown = if (hasMore) MAX_MAP_ENTRIES else size
        val entries = map.entries.take(shown).toList()
        val rawFields = entries.map { (key, value) ->
            RawField(visualizeValue(key, depth + 1, visited, respectDepthLimit), value)
        }
        return renderStructValue(
            typeName = null,
            typeColor = "",
            keyColor = null,
            rawFields = rawFields,
            hasMore = hasMore,
            trailing = formatSizeSuffix(size),
            depth = depth,
            visited = visited,
            respectDepthLimit = respectDepthLimit,
            moreHtml = {
                formatMoreSuffix(
                    size - MAX_MAP_ENTRIES,
                    "entries",
                    map,
                    map::class.simpleName ?: "Map",
                    "map",
                    MAX_MAP_ENTRIES,
                    depth
                )
            }
        )
    }

    private fun formatArray(arr: Array<*>, depth: Int, visited: MutableSet<Any>, respectDepthLimit: Boolean): String {
        return formatList(
            arr.toList(),
            depth,
            visited,
            expandValue = arr,
            expandTitle = arr::class.simpleName ?: "Array",
            respectDepthLimit = respectDepthLimit
        )
    }

    private fun formatByteArray(arr: ByteArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty bytes]</span>"

        return buildString {
            append("<span style='color: #c8f;'>bytes[</span>")
            val preview = arr.take(8)
            append(preview.joinToString(" ") { formatNumberToken("%02x".format(it)) })
            if (arr.size > 8) {
                append(" ")
                append(
                    lazyInlineExpanderHtml(
                        value = arr,
                        label = "… +${arr.size - 8} more bytes",
                        kind = "bytes",
                        offset = 8,
                        title = arr::class.simpleName ?: "ByteArray"
                    )
                )
            }
            append("<span style='color: #c8f;'>]</span>")
            append(formatSizeSuffix(arr.size))
        }
    }

    private fun formatIntArray(arr: IntArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty ints]</span>"

        val previewCount = previewCountForListLike(arr.size)
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(previewCount)
            append("<span style='color: #8cf;'>")
            append(preview.joinToString(", "))
            append("</span>")

            if (arr.size > previewCount) {
                append(
                    formatMoreSuffix(
                        arr.size - previewCount,
                        "items",
                        arr,
                        arr::class.simpleName ?: "IntArray",
                        "list",
                        previewCount,
                        0
                    )
                )
                val stats = calculateStats(arr.map { it.toDouble() })
                append(formatStatsSummary(stats))
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(arr.size))
            append(renderNumericSparkline(arr.map { it.toDouble() }))
        }
    }

    private fun formatFloatArray(arr: FloatArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty floats]</span>"

        val previewCount = previewCountForListLike(arr.size)
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(previewCount)
            append("<span style='color: #8cf;'>")
            append(preview.joinToString(", ") { fmt(it) })
            append("</span>")

            if (arr.size > previewCount) {
                append(
                    formatMoreSuffix(
                        arr.size - previewCount,
                        "items",
                        arr,
                        arr::class.simpleName ?: "FloatArray",
                        "list",
                        previewCount,
                        0
                    )
                )
                val stats = calculateStats(arr.map { it.toDouble() })
                append(formatStatsSummary(stats))
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(arr.size))
            append(renderNumericSparkline(arr.map { it.toDouble() }))
        }
    }

    private fun formatDoubleArray(arr: DoubleArray): String {
        if (arr.isEmpty()) return "<span style='color: #888;'>[empty doubles]</span>"

        val previewCount = previewCountForListLike(arr.size)
        return buildString {
            append("<span style='color: #aaa;'>[</span>")
            val preview = arr.take(previewCount)
            append("<span style='color: #8cf;'>")
            append(preview.joinToString(", ") { fmt(it) })
            append("</span>")

            if (arr.size > previewCount) {
                append(
                    formatMoreSuffix(
                        arr.size - previewCount,
                        "items",
                        arr,
                        arr::class.simpleName ?: "DoubleArray",
                        "list",
                        previewCount,
                        0
                    )
                )
                val stats = calculateStats(arr.toList())
                append(formatStatsSummary(stats))
            }

            append("<span style='color: #aaa;'>]</span>")
            append(formatSizeSuffix(arr.size))
            append(renderNumericSparkline(arr.toList()))
        }
    }

    /**
     * Reduces a heterogeneous list to a numeric series suitable for a sparkline,
     * or an empty list when it is not worth drawing.
     *
     * A list qualifies only when it is *predominantly* numeric: at least
     * [SPARKLINE_MIN_POINTS] elements are [Number] and those make up the majority
     * (>=60%). Non-numeric elements are kept as [Double.NaN] so they show up as
     * gaps at their real index rather than silently shifting the curve.
     */
    private fun numericSeriesForSparkline(list: List<*>): List<Double> {
        if (list.size < SPARKLINE_MIN_POINTS) return emptyList()
        val numericCount = list.count { it is Number }
        if (numericCount < SPARKLINE_MIN_POINTS) return emptyList()
        if (numericCount.toDouble() < list.size * 0.6) return emptyList()
        return list.map { (it as? Number)?.toDouble() ?: Double.NaN }
    }

    /**
     * Builds a small inline SVG sparkline for a numeric series, mirroring the
     * NaN/∞-safe sampling of [renderFnPlot]: non-finite samples become pen-up gaps,
     * a flat/degenerate range is padded so it still draws, and series longer than
     * [SPARKLINE_MAX_SAMPLES] are downsampled (with the original count noted in the
     * tooltip). Returns "" whenever there is nothing worth drawing — fewer than
     * [SPARKLINE_MIN_POINTS] finite values — so callers can append it
     * unconditionally. The whole render is wrapped in [runCatching] so a bad
     * element costs only the sparkline, never the value display.
     */
    private fun renderNumericSparkline(values: List<Double>): String =
        runCatching { buildNumericSparkline(values) }.getOrDefault("")

    private fun buildNumericSparkline(values: List<Double>): String {
        if (values.size < SPARKLINE_MIN_POINTS) return ""

        val total = values.size
        val downsampled = total > SPARKLINE_MAX_SAMPLES
        val samples: List<Double> = if (!downsampled) values else {
            val step = total.toDouble() / SPARKLINE_MAX_SAMPLES.toDouble()
            List(SPARKLINE_MAX_SAMPLES) { i -> values[(i * step).toInt().coerceIn(0, total - 1)] }
        }

        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        var finite = 0
        for (v in samples) {
            if (!v.isFinite()) continue
            finite++
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (finite < SPARKLINE_MIN_POINTS) return "" // not enough finite data to be meaningful

        var yLo = lo
        var yHi = hi
        if (yLo == yHi) {
            val pad = max(1.0, abs(yLo) * 0.1)
            yLo -= pad; yHi += pad
        } else {
            val pad = (yHi - yLo) * 0.08
            yLo -= pad; yHi += pad
        }
        val ySpan = yHi - yLo
        if (!ySpan.isFinite() || ySpan <= 0.0) return ""

        val n = samples.size
        val w = SPARKLINE_WIDTH.toDouble()
        val h = SPARKLINE_HEIGHT.toDouble()
        val padY = 2.0
        val plotH = h - padY * 2.0

        fun px(i: Int): Double = if (n <= 1) w * 0.5 else (i.toDouble() / (n - 1).toDouble()) * w
        fun py(y: Double): Double = padY + (1.0 - (y - yLo) / ySpan) * plotH

        // Build the path, lifting the pen across non-finite gaps (as renderFnPlot does).
        val path = StringBuilder(n * 10)
        var penDown = false
        for (i in 0 until n) {
            val y = samples[i]
            if (!y.isFinite()) { penDown = false; continue }
            val cx = plotNum(px(i)); val cy = plotNum(py(y))
            if (!penDown) { path.append('M').append(cx).append(' ').append(cy).append(' '); penDown = true }
            else path.append('L').append(cx).append(' ').append(cy).append(' ')
        }
        if (path.isBlank()) return ""

        val note = if (downsampled) " (downsampled $total→$n)" else ""
        return buildString {
            append("<span style='display: inline-block; vertical-align: middle; margin-left: 6px; ")
            append("border: 1px solid #333; border-radius: 3px; background: #111; ")
            append("width: ${SPARKLINE_WIDTH}px; height: ${SPARKLINE_HEIGHT}px; overflow: hidden;' ")
            append("title='sparkline of $total value${if (total == 1) "" else "s"}$note'>")
            append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 $SPARKLINE_WIDTH $SPARKLINE_HEIGHT' ")
            append("width='$SPARKLINE_WIDTH' height='$SPARKLINE_HEIGHT'>")
            // faint dashed zero baseline when the *data* straddles zero
            // (test against lo/hi, not the padded range, so a ramp from 0 up
            // doesn't spuriously grow a baseline once padding dips below zero)
            if (lo < 0.0 && hi > 0.0) {
                val zy = plotNum(py(0.0))
                append("<line x1='0' y1='$zy' x2='$SPARKLINE_WIDTH' y2='$zy' stroke='#444' stroke-width='1' stroke-dasharray='2 2'/>")
            }
            append("<path d='")
            append(path.toString().trim())
            append("' fill='none' stroke='#6cf' stroke-width='1.2' stroke-linecap='round' stroke-linejoin='round' vector-effect='non-scaling-stroke'/>")
            append("</svg>")
            append("</span>")
        }
    }

    private fun formatDataClass(obj: Any, depth: Int, visited: MutableSet<Any>, respectDepthLimit: Boolean): String {
        val className = obj::class.simpleName ?: "DataClass"
        return try {
            fieldValueToHtml(structValueFromDataClass(obj, depth, visited, respectDepthLimit))
        } catch (e: Exception) {
            "<span style='color: #f88;'>$className [reflection failed: ${e.message}]</span>"
        }
    }

    private fun structValueFromDataClass(
        obj: Any, depth: Int, visited: MutableSet<Any>, respectDepthLimit: Boolean
    ): FieldValue {
        val className = obj::class.simpleName ?: "DataClass"
        val properties = obj::class.memberProperties
        if (properties.isEmpty()) {
            return FieldValue.Inline("<span style='color: #cc8;'>$className()</span>")
        }

        val entries = properties.toList()
        val hasMore = entries.size > MAX_OBJECT_FIELDS + ELISION_SLACK
        val previewEntries = if (hasMore) entries.take(MAX_OBJECT_FIELDS) else entries

        val rawFields = previewEntries.map { prop ->
            val propValue = try {
                prop.isAccessible = true
                prop.getter.call(obj)
            } catch (e: Exception) {
                "[error: ${e.message}]"
            }
            RawField(escape(prop.name), propValue, prop.returnType)
        }

        return renderStructValue(
            typeName = className,
            typeColor = "#cc8",
            keyColor = "#aaf",
            rawFields = rawFields,
            hasMore = hasMore,
            trailing = "",
            depth = depth,
            visited = visited,
            respectDepthLimit = respectDepthLimit,
            moreHtml = {
                lazyInlineExpanderHtml(
                    value = obj,
                    label = "… +${entries.size - previewEntries.size} more fields",
                    kind = "object",
                    offset = previewEntries.size,
                    depth = depth,
                    title = className
                )
            }
        )
    }

    internal fun renderLazyFragment(token: String, kind: String, offset: Int, depth: Int): String? {
        val value = h2GetRawValue(token) ?: return null
        val safeOffset = offset.coerceAtLeast(0)
        val safeDepth = depth.coerceAtLeast(0)
        val visited = newIdentitySet()

        return when (kind.lowercase()) {
            "full" -> visualizeValue(value, safeDepth, visited, respectDepthLimit = false)
            "string" -> renderStringContinuation(value as? String ?: return null, safeOffset)
            "list" -> renderListContinuation(value, safeOffset, safeDepth, visited)
            "map" -> renderMapContinuation(value as? Map<*, *> ?: return null, safeOffset, safeDepth, visited)
            "object" -> renderDataClassContinuation(value, safeOffset, safeDepth, visited)
            "bytes" -> renderByteArrayContinuation(value as? ByteArray ?: return null, safeOffset)
            else -> null
        }
    }

    private fun renderStringContinuation(value: String, offset: Int): String {
        val safeStart = offset.coerceIn(0, value.length)
        if (safeStart >= value.length) return ""
        val nextEnd = min(value.length, safeStart + STRING_EXPANSION_CHUNK)

        return buildString {
            append("<span style='color: #8f8;'>")
            append(escape(value.substring(safeStart, nextEnd)))
            append("</span>")
            if (nextEnd < value.length) {
                append(" ")
                append(
                    lazyInlineExpanderHtml(
                        value = value,
                        label = "… +${value.length - nextEnd} chars",
                        kind = "string",
                        offset = nextEnd,
                        title = "String"
                    )
                )
            }
        }
    }

    private fun renderByteArrayContinuation(value: ByteArray, offset: Int): String {
        val safeStart = offset.coerceIn(0, value.size)
        if (safeStart >= value.size) return ""
        val nextEnd = min(value.size, safeStart + BYTE_EXPANSION_CHUNK)

        return buildString {
            append(value.sliceArray(safeStart until nextEnd).joinToString(" ") { formatNumberToken("%02x".format(it)) })
            if (nextEnd < value.size) {
                append(" ")
                append(
                    lazyInlineExpanderHtml(
                        value = value,
                        label = "… +${value.size - nextEnd} more bytes",
                        kind = "bytes",
                        offset = nextEnd,
                        title = value::class.simpleName ?: "ByteArray"
                    )
                )
            }
        }
    }

    private fun renderListContinuation(value: Any?, offset: Int, depth: Int, visited: MutableSet<Any>): String? {
        val list = toListLike(value) ?: return null
        val safeStart = offset.coerceIn(0, list.size)
        if (safeStart >= list.size) return ""
        val nextEnd = min(list.size, safeStart + LIST_EXPANSION_CHUNK)
        val title = valueTitle(value, "List")

        return buildString {
            val (rendered, nextOffset) = renderListPreviewElements(
                list = list,
                start = safeStart,
                previewEndExclusive = nextEnd,
                listEndExclusive = list.size,
                depth = depth,
                visited = visited,
                respectDepthLimit = false
            )
            appendRenderedListElements(rendered)
            if (nextOffset < list.size) {
                append(
                    formatMoreSuffix(
                        list.size - nextOffset,
                        "items",
                        value,
                        title,
                        "list",
                        nextOffset,
                        depth
                    )
                )
            }
        }
    }

    private fun renderMapContinuation(
        value: Map<*, *>,
        offset: Int,
        depth: Int,
        visited: MutableSet<Any>
    ): String {
        val entries = value.entries.toList()
        val safeStart = offset.coerceIn(0, entries.size)
        if (safeStart >= entries.size) return ""
        val nextEnd = min(entries.size, safeStart + MAP_EXPANSION_CHUNK)
        val hasMore = nextEnd < entries.size
        val title = valueTitle(value, "Map")

        val rawFields = entries.subList(safeStart, nextEnd).map { (key, entryValue) ->
            RawField(visualizeValue(key, depth + 1, visited, respectDepthLimit = false), entryValue)
        }
        val more = if (hasMore) {
            formatMoreSuffix(entries.size - nextEnd, "entries", value, title, "map", nextEnd, depth)
        } else null
        return renderContinuationFields(rawFields, keyColor = null, depth = depth, visited = visited, moreExpander = more)
    }

    private fun renderDataClassContinuation(
        value: Any?,
        offset: Int,
        depth: Int,
        visited: MutableSet<Any>
    ): String {
        val obj = value ?: return ""
        if (!obj::class.isData) {
            return visualizeValue(obj, depth, visited, respectDepthLimit = false)
        }

        val entries = obj::class.memberProperties.toList()
        val safeStart = offset.coerceIn(0, entries.size)
        if (safeStart >= entries.size) return ""
        val nextEnd = min(entries.size, safeStart + OBJECT_EXPANSION_CHUNK)
        val hasMore = nextEnd < entries.size
        val className = valueTitle(obj, "DataClass")

        val rawFields = entries.subList(safeStart, nextEnd).map { prop ->
            val propValue = try {
                prop.isAccessible = true
                prop.getter.call(obj)
            } catch (e: Exception) {
                "[error: ${e.message}]"
            }
            RawField(escape(prop.name), propValue, prop.returnType)
        }
        val more = if (hasMore) {
            lazyInlineExpanderHtml(
                value = obj,
                label = "… +${entries.size - nextEnd} more fields",
                kind = "object",
                offset = nextEnd,
                depth = depth,
                title = className
            )
        } else null
        return renderContinuationFields(rawFields, keyColor = "#aaf", depth = depth, visited = visited, moreExpander = more)
    }

    private fun toListLike(value: Any?): List<*>? = when (value) {
        is List<*> -> value
        is Set<*> -> value.toList()
        is Array<*> -> value.toList()
        is ByteArray -> value.asList()
        is ShortArray -> value.asList()
        is IntArray -> value.asList()
        is LongArray -> value.asList()
        is FloatArray -> value.asList()
        is DoubleArray -> value.asList()
        is BooleanArray -> value.asList()
        is CharArray -> value.asList()
        else -> null
    }

    private fun valueTitle(value: Any?, fallback: String): String {
        val title = value?.let { it::class.simpleName ?: it::class.java.simpleName }
        return title?.ifBlank { fallback } ?: fallback
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

    private fun lookupBoxReference(boxId: String): BoxReference? {
        val candidates = candidateBoxIds(boxId)
        val currentSession: SessionInformation? = thisSessionInfo.get()
        currentSession?.let { session ->
            candidates.forEach { candidate ->
                session.boxes[candidate]?.let { currentBox ->
                    return BoxReference(
                        uid = currentBox.id,
                        name = currentBox.name.ifBlank { currentBox.id }
                    )
                }
            }
        }

        documents.values.forEach { sessionInfo ->
            candidates.forEach { candidateId ->
                val candidate = sessionInfo.boxes[candidateId] ?: return@forEach
                return BoxReference(
                    uid = candidate.id,
                    name = candidate.name.ifBlank { candidate.id }
                )
            }
        }

        return null
    }

    private fun candidateBoxIds(boxId: String): List<String> {
        val candidates = linkedSetOf<String>()
        candidates += boxId
        candidates += boxId.lowercase()
        if (boxId.startsWith("_")) {
            val noPrefix = boxId.drop(1)
            candidates += noPrefix
            candidates += noPrefix.lowercase()
        }

        UNDERSCORE_UUID_REGEX.matchEntire(boxId)?.let { m ->
            val hyphenUuid = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}-${m.groupValues[4]}-${m.groupValues[5]}"
            candidates += hyphenUuid
            candidates += hyphenUuid.lowercase()
        }

        return candidates.toList()
    }

    private fun rewriteBoxReferencesInHtml(html: String): String {
        if (html.length < 37 || html.indexOf('_') < 0) return html

        val out = StringBuilder(html.length + 32)
        var cursor = 0
        while (cursor < html.length) {
            val tagStart = html.indexOf('<', cursor)
            if (tagStart < 0) {
                out.append(rewriteBoxReferencesInText(html.substring(cursor), escapePlainText = false))
                break
            }

            if (tagStart > cursor) {
                out.append(rewriteBoxReferencesInText(html.substring(cursor, tagStart), escapePlainText = false))
            }

            val tagEnd = html.indexOf('>', tagStart + 1)
            if (tagEnd < 0) {
                out.append(rewriteBoxReferencesInText(html.substring(tagStart), escapePlainText = false))
                break
            }

            out.append(html, tagStart, tagEnd + 1)
            cursor = tagEnd + 1
        }
        return out.toString()
    }

    private fun rewriteBoxReferencesInText(text: String, escapePlainText: Boolean): String {
        if (text.length < 37 || text.indexOf('_') < 0) {
            return if (escapePlainText) escapeHtmlText(text) else text
        }

        val nameCache = mutableMapOf<String, BoxReference?>()
        val out = StringBuilder(text.length + 32)
        var cursor = 0
        var replacedAny = false

        BOX_ID_TOKEN_REGEX.findAll(text).forEach { match ->
            val boxId = match.groups[1]?.value ?: return@forEach
            val boxRef = nameCache.getOrPut(boxId) { lookupBoxReference(boxId) } ?: return@forEach

            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start < cursor) return@forEach

            val prefix = text.substring(cursor, start)
            out.append(if (escapePlainText) escapeHtmlText(prefix) else prefix)
            out.append(formatStyledBoxReference(boxRef))
            cursor = endExclusive
            replacedAny = true
        }

        if (!replacedAny) {
            return if (escapePlainText) escapeHtmlText(text) else text
        }

        val suffix = text.substring(cursor)
        out.append(if (escapePlainText) escapeHtmlText(suffix) else suffix)
        return out.toString()
    }

    private fun escape(s: String): String {
        return rewriteBoxReferencesInText(s, escapePlainText = true)
    }

    private fun formatStyledBoxReference(boxRef: BoxReference): String {
        val escapedUid = escapeHtmlText(boxRef.uid)
        return buildString {
            append("<a href='#box:")
            append(escapedUid)
            append("' data-box-uid='")
            append(escapedUid)
            append("' style='text-decoration:none;cursor:pointer;'>")
            append("<span style='color:#fff;font-weight:300;'>")
            append(BOX_SYMBOL)
            append("</span>")
            append("<span style='color:#fff;font-weight:300;font-style:italic;'>")
            append(escapeHtmlText(boxRef.name))
            append("</span>")
            append("</a>")
        }
    }

    private fun escapeHtmlText(text: String): String {
        return text.replace("&", "&amp;")
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

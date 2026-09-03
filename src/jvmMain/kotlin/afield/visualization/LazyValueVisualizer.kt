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
        if (adapted == null && !isExplorable(value)) return ValueVisualizer.visualize(value, appendable = appendable)

        val rawValue = adapted?.value ?: value
        // `wrapUnadapted`, not `wrapForInspector`: `adapted` above is this value's registry lookup,
        // and `rawValue` is already the substitute. The front door would only run the registry a
        // second time over that substitute.
        val wrapped = wrapUnadapted(rawValue, title = adapted?.title ?: "value")
        val token = "h2-${UUID.randomUUID()}"
        H2TokenRetention.remember(token, rawValue)
        val outlinerPath = Inspector.linkPathFor(wrapped ?: "null", browsePath = H2_OUTLINER_PATH, uid = token)
        val columnsPath = Inspector.linkPathFor(wrapped ?: "null", browsePath = H2_COLUMNS_PATH, uid = token)
        val embeddedPath = "$H2_EMBED_EXPLORER_PATH?root=$token"
        val ownerUid = Box.context.get()?.id
        val titleForViews = adapted?.title?.ifBlank { null } ?: "Value"

        return buildString {
            append("<div class='value-viz-h2")
            if (appendable) append(" afield-appendable-output")
            append("' style='font-family:\"Iosevka Web\",monospace;font-size:12px;color:#ccc;line-height:1.35;display:flex;flex-direction:column;align-items:stretch;width:100%;height:100%;min-height:0;box-sizing:border-box;'>")
            append("<div style='display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-bottom:6px;'>")
            append("<span style='color:#9cf;'>")
            append(escapeHtml(adapted?.summary ?: summary(rawValue)))
            append("</span>")
            append(outputAreaLinkHtml(outlinerPath, "outliner", "#8cf", ownerUid, titleForViews))
            append(outputAreaLinkHtml(columnsPath, "columns", "#777", ownerUid, titleForViews))
            append("</div>")
            append("<iframe data-afield-autosize='off' src='")
            append(escapeHtmlAttr(embeddedPath))
            append("' loading='lazy' style='position:static;display:block;width:100%;height:${H2_IFRAME_HEIGHT_PX}px;flex:1 1 auto;min-height:0;border:1px solid #333;border-radius:4px;background:#000;'></iframe>")
            append("</div>")
        }
    }

    /**
     * One of the panel's two view links, opening **in the box's own output area** rather than a
     * browser tab.
     *
     * `afieldOpenLazyValueInOutputArea` (`jsMain/Outputs.kt`) has done this since before `.h2` grew
     * an explorer: it reads the three `data-afield-view-*`/`data-afield-box-uid` attributes and hands
     * them to `upsertLazyValueViewer`, which opens a disclosure in the output area with the iframe
     * inside it. The inline `.h` expander [lazyInlineExpanderHtml] renders its own "columns" link
     * exactly this way. These two links were the only place in the file that did not, and a `.h2`
     * panel is the surface most likely to be clicked, so a value explored from a box left the box.
     *
     * `target="_blank"` stays as the `href` fallback, which is what happens when the page's JS is
     * absent — a docs capture, a saved page, an offline viewer. The handler returns false, so the
     * navigation only occurs when nothing claimed the click.
     *
     * [ownerUid] is null outside a box's execution context. There is no output area to open into
     * then, so the link degrades to the old behaviour rather than becoming inert.
     */
    private fun outputAreaLinkHtml(
        path: String,
        label: String,
        colour: String,
        ownerUid: String?,
        viewTitle: String
    ): String = buildString {
        append("<a href='")
        append(escapeHtmlAttr(path))
        if (!ownerUid.isNullOrBlank()) {
            append("' data-afield-view-src='")
            append(escapeHtmlAttr(path))
            append("' data-afield-box-uid='")
            append(escapeHtmlAttr(ownerUid))
            append("' data-afield-view-title='")
            append(escapeHtmlAttr("$viewTitle — $label"))
            append("' onclick='window.afieldOpenLazyValueInOutputArea && window.afieldOpenLazyValueInOutputArea(this); return false;'")
            append(" style='color:$colour;text-decoration:none;cursor:pointer;'>")
        } else {
            append("' target='_blank' style='color:$colour;text-decoration:none;'>")
        }
        append(label)
        append("</a>")
    }

    private fun summary(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "${value::class.simpleName ?: "Map"} (size=${value.size})"
        is List<*> -> unitCollectionSummary(value, value::class.simpleName ?: "List")
            ?: "${value::class.simpleName ?: "List"} (size=${value.size})"
        is Array<*> -> unitCollectionSummary(value.asList(), value::class.simpleName ?: "Array")
            ?: "${value::class.simpleName ?: "Array"} (size=${value.size})"
        is Collection<*> -> "${value::class.simpleName ?: "Collection"} (size=${value.size})"
        is IntArray -> "IntArray (size=${value.size})"
        is LongArray -> "LongArray (size=${value.size})"
        is FloatArray -> "FloatArray (size=${value.size})"
        is DoubleArray -> "DoubleArray (size=${value.size})"
        is FloatBuffer -> "FloatBuffer (pos=${value.position()}, limit=${value.limit()}, capacity=${value.capacity()})"
        is IntBuffer -> "IntBuffer (pos=${value.position()}, limit=${value.limit()}, capacity=${value.capacity()})"
        // A reflected domain object gets a property count, because "Foo" alone tells a person
        // nothing about whether the tree below it is worth opening.
        else -> if (H2Reflect.isExplorableObject(value)) H2Reflect.summaryLine(value)
        else value::class.simpleName ?: value::class.java.simpleName
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

    /**
     * Whether `.h2` engages for [value] at all, as opposed to handing it straight to `.h`.
     *
     * The union of two rules, kept separate because they are settled by different arguments:
     *
     *  - [isHeavyStructure] — a collection or buffer. **There is no size threshold here, and that
     *    is the point.** This gate used to be strictly narrower than the machinery behind it: it
     *    admitted `IntArray` but not `ByteArray`, `List` but not `Set`, while `wrapForInspector`
     *    below and `makeSource` in `h2explorer.kt` both already rendered all of them. The gate's job
     *    is to admit exactly what the three downstream surfaces can draw, so the rule is now
     *    "anything with indexable elements", and any future addition belongs in all three places or
     *    none.
     *  - [H2Reflect.isExplorableObject] — a domain object with properties worth a tree. See that
     *    function for why the property floor is 2 rather than `.h`'s elision point.
     */
    private fun isExplorable(value: Any?): Boolean =
        isHeavyStructure(value) || H2Reflect.isExplorableObject(value)

    /**
     * A collection, array or buffer — something whose *elements* are the content.
     *
     * `Collection` rather than `List` covers a `Set`, an `ArrayDeque` and a `Queue`; the price is
     * that [wrapForInspector] and `makeSource` snapshot a non-`List` collection with `toList()`,
     * because both surfaces address rows by index and only a `List` can answer that without a copy.
     * A `List` therefore keeps its no-copy path deliberately, ahead of the general case.
     */
    private fun isHeavyStructure(value: Any?): Boolean {
        return value is Map<*, *> ||
            value is Collection<*> ||
            value is Array<*> ||
            value is ByteArray ||
            value is ShortArray ||
            value is IntArray ||
            value is LongArray ||
            value is FloatArray ||
            value is DoubleArray ||
            value is BooleanArray ||
            value is CharArray ||
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

/**
 * The `Browsable` (or bare value) that `/tree` and `/browse` show for [value].
 *
 * `internal` rather than private because `H2Reflect`'s reflective tree re-enters it for each
 * property: a property holding a list must get the same paging a top-level list gets, or the two
 * routes disagree about the same object depending on how you reached it.
 *
 * ## A registered adapter wins at every depth, not only at the root
 *
 * [LazyValueVisualizer.visualize] consults [H2ValueRegistry] for the value a person typed `.h2` on,
 * and until this function did too, nothing consulted it again. That made the registry a *root-only*
 * mechanism, which is not what it reads like at its call sites, and it broke the one invariant the
 * `Box` adapter exists to hold: the adapter reports `liveKi` rather than reading `Box.ki` — a
 * `lazyVar` that compiles a K2 REPL engine on read — and then hands `box.properties` to the map
 * pager. `children`, `parents`, `nested` and `nestedIn` are `Delegation` properties, so they sit in
 * that bag holding raw `Box` objects, the pager resolves an element through this function, and the
 * reflective default took whatever the arms below did not claim. A neighbouring box reached at
 * `properties -> children -> [0]` was therefore reflected and its `ki` read. `H2Reflect`'s own KDoc
 * states why that must not happen; the guard simply did not survive one hop.
 *
 * Adapters are also the better rendering wherever they apply, so this is a fix and a feature: a
 * nested `DataFrame` or `FrameBufferGroup` now pages through its own adapter instead of being
 * reflected into `position`/`limit`.
 *
 * The dispatch is deliberately **one hop, not a loop**. An adapter's substitute is handed to
 * [wrapUnadapted], which never re-consults the registry, so a pair of adapters that substituted for
 * each other cannot spin. `H2ValueSnapshot.value` also defaults to substituting nothing, so an
 * adapter written only to supply a `summary` hands back the object it was given — the `!==` check is
 * what keeps that case a leaf rather than an infinite regress. All three adapters in the tree today
 * substitute (`Box` -> map, `DataFrame` -> list, `FrameBufferGroup` -> array), so both guards are
 * load-bearing for the next adapter rather than for these.
 */
internal fun wrapForInspector(value: Any?, title: String? = null): Any? {
    val adapted = value?.let { H2ValueRegistry.adapt(it) }
    if (adapted != null && adapted.value !== value) {
        return wrapUnadapted(adapted.value, adaptedLabel(title, adapted))
    }
    return wrapUnadapted(value, title)
}

/**
 * The node label for a value an adapter substituted.
 *
 * Same `label — summary` shape `H2ReflectiveBrowsable.heading()` uses, and for the same reason: a
 * nested box reached at `[0]` reads `[0] — Box neighbour`, because `[0]` alone does not say which
 * box and the adapter's own title does not say where it sits. A caller with no label, or the top
 * level's placeholder `"value"`, takes the adapter's title unchanged.
 */
private fun adaptedLabel(callerTitle: String?, adapted: H2ValueSnapshot): String? {
    val own = adapted.title ?: adapted.summary
    if (own.isNullOrBlank()) return callerTitle
    if (callerTitle.isNullOrBlank() || callerTitle == "value") return own
    return "$callerTitle — $own"
}

/**
 * [wrapForInspector] with the registry already consulted.
 *
 * Split out so that the adapter lookup happens exactly once per value: [LazyValueVisualizer.visualize]
 * has already adapted the root before it wraps, and calling the front door there would run the
 * registry over the substitute for nothing.
 */
private fun wrapUnadapted(value: Any?, title: String? = null): Any? {
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
        // After `List`, so a list keeps the no-copy path: a `Set` or a `Queue` is snapshotted
        // because the pager addresses rows by index and only a `List` can do that in place.
        is Collection<*> -> PagedListBrowsable(value.toList(), title = title)
        // The reflective default, reached only for types with no registered adapter — `wrapForInspector`
        // has already taken those. Returns null for anything `H2Reflect` declines — a scalar, a
        // platform type, a value whose `.h` rendering is already the whole story — and then the raw
        // value falls through to the Inspector's leaf renderer, exactly as before this existed.
        else -> H2Reflect.browsableFor(value, title) ?: value
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

/**
 * Test seam: the value a registered `.h2` adapter substitutes for [value], or [value] itself when
 * no adapter matches.
 *
 * [H2ValueRegistry] is an object with an `internal adapt`, and the substitution it performs is the
 * whole content of a hand-written adapter — so without this, an adapter's *shape* (does an `Fn`
 * become an x/f(x) table?) could only be checked through the full HTML string, which is a test that
 * passes on a coincidence.
 */
internal fun h2AdaptedValue(value: Any): Any? = H2ValueRegistry.adapt(value)?.value ?: value

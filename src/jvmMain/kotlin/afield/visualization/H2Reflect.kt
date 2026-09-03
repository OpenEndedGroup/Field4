@file:afield.CompletionCategory("Visualization")

package afield

import afield.server.Browsable
import afield.stdlib.Quantity
import afield.stdlib.linalg.Matrix2dc
import afield.stdlib.linalg.Matrix2fc
import afield.stdlib.linalg.Matrix3dc
import afield.stdlib.linalg.Matrix3fc
import afield.stdlib.linalg.Matrix4dc
import afield.stdlib.linalg.Matrix4fc
import afield.stdlib.linalg.Quaternionfc
import afield.stdlib.linalg.Vector2dc
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.Vector3dc
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4dc
import afield.stdlib.linalg.Vector4fc
import eu.sirotin.kotunil.core.Expression
import java.nio.Buffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * The reflective half of `.h2`: what a domain object's *children* are, computed once and shared by
 * every surface that shows them.
 *
 * `.h2` renders a value three separate times — the `Browsable` tree behind `/tree` and `/browse`,
 * the row/column stream behind the embedded `/h2-explorer` iframe, and the summary line in the
 * widget itself. Before this file, only collections had all three; anything else fell through
 * [LazyValueVisualizer.visualize]'s opening guard to `ValueVisualizer` and `.h2` was byte-for-byte
 * `.h`. Registering a per-type [H2ValueSnapshot] adapter was the only escape, and the registry
 * shipped on 2026-06-14 carrying one adapter written in the same commit (`FrameBufferGroup`) and had
 * gained exactly one more before this file existed — `DataFrame`, on 2026-08-21, thirteen days
 * earlier. Two, against **54** `registerValuePrinter<` call sites on the older `.h` seam, 50 of them
 * production (`git grep -c 'registerValuePrinter<' -- '*.kt'` over 7 files, with 3 occurrences in a
 * test and 1 in a `dev_docs` sample). So the registration strategy was never going to lift the
 * population.
 *
 * [properties] is deliberately the *single* enumerator. `/tree` reading one set of children and the
 * iframe reading another is the kind of divergence you only notice when a person says "it says four
 * fields over here and six over there", so both go through here.
 *
 * ## What this does not know
 *
 * A `KProperty1` getter is opaque: there is no way to tell a field read from a computed property
 * that opens a file, allocates a GPU buffer or — the case that actually bit — builds a K2 REPL
 * engine. `Box.ki` is a `lazyVar` that compiles on read, which is exactly why [Box] is served by a
 * hand-written adapter in `H2DomainAdapters.kt` rather than by this file. The two narrowings in
 * [readsWellInline] (platform packages, and types whose `.h` rendering *is* the value) are the
 * general mitigation; they are not a proof. If a type's getters are expensive or effectful, give it
 * an adapter.
 */

/** One reflectively-read property of a domain object. [failure] is non-null when its getter threw. */
internal data class H2Property(
    val name: String,
    val typeName: String,
    val value: Any?,
    val failure: String? = null
) {
    /** The tree/table label. A failed read is marked, because a silently missing row is a lie. */
    val label: String get() = if (failure == null) name else "$FAILURE_MARK $name"

    /** What the value cell shows: the value, or the exception that stopped us reading it. */
    val cellText: String get() = failure ?: h2PreviewOf(value)

    companion object {
        const val FAILURE_MARK = "⚠"
    }
}

/**
 * Why 512: the cap is not about rendering — both surfaces page — but about the reflective read
 * itself, which calls every getter before anything is displayed. A type with more than 512
 * properties is either generated or a mistake, and either way the person is better served by a
 * truncation they can see than by a stall they cannot.
 */
private const val H2_REFLECT_MAX_PROPERTIES = 512

/**
 * Why 6: the Inspector resolves `dir()` lazily, so an unbounded tree costs nothing to *build* — the
 * cap exists so a person clicking downwards eventually hits a floor rather than descending through
 * framework plumbing forever. Six is two more than [afield.ValueVisualizer]'s inline `MAX_DEPTH` of
 * 3, because clicking is a deliberate act and inlining is not.
 */
private const val H2_REFLECT_MAX_DEPTH = 6

/**
 * Why 2, and specifically **not** `.h`'s elision point:
 *
 * `ValueVisualizer` starts hiding fields at `MAX_OBJECT_FIELDS + ELISION_SLACK` = 10, so "engage
 * `.h2` where `.h` stops showing you everything" would put this at 10. That is the wrong rule,
 * because `.h2` is not chosen for you — it is eight characters a person types by hand, and someone
 * who wanted the compact form typed `.h`. So the threshold is not "where `.h` becomes inadequate"
 * but "where there is a tree at all": zero properties has no children, one is a wrapper that reads
 * better inline than behind an iframe, two or more is something to browse.
 */
private const val H2_REFLECT_MIN_PROPERTIES = 2

/**
 * Package prefixes whose types are never reflected into.
 *
 * This is the narrowing that keeps "`.h2` explores everything" from meaning "`.h2` dumps every
 * object on the heap". A `Thread`, a `ClassLoader`, a `File` or a `Pattern` expanded by property is
 * a JVM field dump — no structure a person recognises, and the highest concentration of getters
 * with real side effects. Classes with an *empty* package are admitted on purpose: that is where a
 * box's own compiled declarations land.
 */
private val H2_PLATFORM_PACKAGE_PREFIXES = listOf(
    "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx."
)

/**
 * Types a library has declared unreflectable, by [H2Reflect.refuseReflection].
 *
 * The package-prefix list above cannot express this. A third-party type whose compact `.h` rendering
 * *is* the whole story is not a platform type and has no adapter to register — there was simply no
 * way to say "on the `.h2` surface too, use `.h`", so the reflective default claimed it.
 *
 * Measured cost of not having this seam: `DataFrameVisualizer.install()` registers `.h` printers for
 * `DataColumn` and `GroupBy` and states in a comment that both "fall through to the default lazy
 * rendering" because their compact form is already the whole story — and the reflective default
 * silently reversed that, showing three reflected properties instead of `columnHead` /
 * `groupBySummary`. (`DataRow` was named in the same comment and was never affected: Kotlin
 * reflection reports zero readable properties on `DataRowImpl`, so it fails the two-property floor
 * on its own.)
 */
private val h2RefusedTypes = ConcurrentHashMap.newKeySet<Class<*>>()

internal object H2Reflect {

    /**
     * Declare that `.h2` must not reflect into [clazz] or its subtypes — its `.h` rendering is the
     * value, and an expanded property list would be a regression rather than more detail.
     *
     * Assignability rather than exact identity, because the type worth naming is nearly always the
     * public interface (`DataColumn`) and the value at runtime is nearly always a private
     * implementation of it (`ValueColumnImpl`). Same resolution intent as [H2ValueRegistry.adapt]
     * and `ValuePrinterRegistry.getPrinter`; a plain assignability scan is enough here because the
     * set holds a handful of entries and the alternative is three subtly different rules.
     *
     * Call it beside the `.h` printer it defends, so the decision and its reason stay in one place.
     */
    fun refuseReflection(clazz: Class<*>) {
        h2RefusedTypes.add(clazz)
    }

    /**
     * `memberProperties` per class, cached.
     *
     * Not a micro-optimisation: the first `memberProperties` call on a class builds a whole
     * `KClass` implementation out of the JVM metadata, and the explorer re-derives a value's
     * children on every window request the iframe scrolls into. Uncached, scrolling a wide object
     * re-runs Kotlin reflection per frame.
     */
    private val propertyCache = ConcurrentHashMap<Class<*>, List<KProperty1<Any, *>>>()

    /** Sentinel for the cache: `ConcurrentHashMap` cannot hold a null value. */
    private val NONE: List<KProperty1<Any, *>> = emptyList()

    /**
     * The public, no-argument properties of [value]'s type, in a **stable** order: primary
     * constructor parameters first, in declaration order, then everything else by name.
     *
     * `memberProperties` order is not contractual — it comes out of the JVM metadata and has
     * changed across Kotlin releases — so a test written against it is a test that fails on a
     * compiler bump. Constructor order is what the person typed and is the order they read the
     * class in.
     */
    @Suppress("UNCHECKED_CAST")
    private fun propertiesOf(clazz: Class<*>): List<KProperty1<Any, *>> =
        propertyCache.getOrPut(clazz) {
            // Kotlin reflection throws on synthetic, local and anonymous classes, on classes whose
            // metadata references something absent from the classpath, and on a few lambda shapes.
            // A failure here means "not explorable", never a broken box.
            runCatching {
                val kClass = clazz.kotlin
                val members = kClass.memberProperties
                    .filter { it.visibility == KVisibility.PUBLIC }
                    .map { it as KProperty1<Any, *> }

                val constructorOrder: Map<String, Int> = runCatching {
                    kClass.primaryConstructor?.parameters
                        ?.mapIndexedNotNull { i, p -> p.name?.let { it to i } }
                        ?.toMap()
                }.getOrNull() ?: emptyMap()

                members.sortedWith(
                    compareBy(
                        { constructorOrder[it.name] ?: Int.MAX_VALUE },
                        { it.name }
                    )
                )
            }.getOrDefault(NONE)
        }

    /**
     * How many properties [value] would expose, **without calling a single getter**.
     *
     * Split out from [properties] on purpose: [isExplorable] runs on every `.h2` call, and a gate
     * that had to read every value to decide whether to show it would pay the side-effect cost of
     * exploring an object it then declined to explore.
     */
    fun readablePropertyCount(value: Any): Int = propertiesOf(value::class.java).size

    /**
     * [value]'s properties, read. A getter that throws yields an [H2Property] carrying the failure
     * rather than being dropped — see [H2Property.failure].
     */
    fun properties(value: Any): List<H2Property> {
        val props = propertiesOf(value::class.java)
        val visible = if (props.size > H2_REFLECT_MAX_PROPERTIES) props.take(H2_REFLECT_MAX_PROPERTIES) else props
        val out = ArrayList<H2Property>(visible.size + 1)

        visible.forEach { prop ->
            val typeName = renderType(prop.returnType)
            // Throwable, not Exception: a getter that hits an assertion, a StackOverflowError from a
            // recursive computed property, or a NoClassDefFoundError from a half-loaded dependency
            // are all things a person exploring a live object will meet, and none of them should
            // take down the whole panel.
            try {
                prop.isAccessible = true
                out.add(H2Property(prop.name, typeName, prop.getter.call(value)))
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                out.add(
                    H2Property(
                        name = prop.name,
                        typeName = typeName,
                        value = null,
                        failure = "getter threw: ${cause::class.simpleName ?: "Throwable"}" +
                            (cause.message?.let { ": ${clipPreview(it)}" } ?: "")
                    )
                )
            }
        }

        if (props.size > visible.size) {
            out.add(
                H2Property(
                    name = "… +${props.size - visible.size} more properties",
                    typeName = "",
                    value = null,
                    failure = "not read: over the ${H2_REFLECT_MAX_PROPERTIES}-property reflective cap"
                )
            )
        }
        return out
    }

    /** `Name · 4 properties` — the one line worth having above a reflected object. */
    fun summaryLine(value: Any): String {
        val name = value::class.simpleName ?: value::class.java.simpleName.ifBlank { "Object" }
        val n = readablePropertyCount(value)
        return "$name · $n ${if (n == 1) "property" else "properties"}"
    }

    /**
     * Whether `.h2` should reflect into [value] rather than hand it to `.h`.
     *
     * Two conditions, both necessary: the type must not be one whose `.h` rendering already *is*
     * the value ([readsWellInline]), and it must have at least [H2_REFLECT_MIN_PROPERTIES]
     * properties to browse.
     */
    fun isExplorableObject(value: Any?): Boolean {
        if (value == null) return false
        if (readsWellInline(value)) return false
        return readablePropertyCount(value) >= H2_REFLECT_MIN_PROPERTIES
    }

    /**
     * Types `.h2` must leave to `.h`.
     *
     * Each entry is a case where the compact renderer is strictly better, not merely shorter: a
     * matrix reads as a diverging heatmap, a vector as its components, a `Quantity` as a number and
     * a unit symbol. Expanding any of them into `{ m00 -> 1.0, m01 -> 0.0, … }` behind an iframe is
     * a regression, and `UnitValueVisualizerTest` pins the `Quantity` half of that as a contract.
     */
    private fun readsWellInline(value: Any): Boolean = when (value) {
        // No structure to browse.
        is Number, is Boolean, is Char, is CharSequence, is Enum<*> -> true

        // The unit-aware formatter is the point of these two.
        is Quantity, is Expression -> true

        // Linalg value types: `ValueVisualizer` draws matrices as heatmaps and vectors as
        // components. Both `…fc` (single) and `…dc` (generated double) families.
        is Vector2fc, is Vector3fc, is Vector4fc,
        is Vector2dc, is Vector3dc, is Vector4dc,
        is Quaternionfc,
        is Matrix2fc, is Matrix3fc, is Matrix4fc,
        is Matrix2dc, is Matrix3dc, is Matrix4dc -> true

        // `to` built almost every Pair in this codebase, and `.h` renders it as such.
        is Pair<*, *>, is Triple<*, *, *> -> true

        // A lambda's properties are its captured environment, under synthetic names.
        is Function<*> -> true

        // Reflection on reflection.
        is KClass<*>, is Class<*> -> true

        // Buffers, collections and maps are handled by the *collection* half of the gate, which
        // already has paging and stride machinery. Reflecting them would find `position`/`limit`
        // rather than the data.
        is Buffer, is Collection<*>, is Map<*, *> -> true

        else -> isPlatformType(value::class.java) || isRefusedType(value::class.java)
    }

    /** True for a JVM/Kotlin platform type. An empty package is a box's own compiled code — admitted. */
    private fun isPlatformType(clazz: Class<*>): Boolean {
        val name = clazz.name
        if (!name.contains('.')) return false
        return H2_PLATFORM_PACKAGE_PREFIXES.any { name.startsWith(it) }
    }

    /** True when [clazz] or any supertype of it was passed to [refuseReflection]. */
    private fun isRefusedType(clazz: Class<*>): Boolean =
        h2RefusedTypes.any { it.isAssignableFrom(clazz) }

    private fun renderType(type: KType): String {
        val base = (type.classifier as? KClass<*>)?.simpleName ?: type.toString().substringAfterLast('.')
        val args = type.arguments
        val rendered = if (args.isEmpty()) base else "$base<${args.size}>"
        return if (type.isMarkedNullable) "$rendered?" else rendered
    }

    /**
     * A reflective [Browsable] for [value], or null when [value] is not an explorable object.
     *
     * [path] is an *identity* chain of the ancestors already on screen, so a self-referential object
     * renders `[circular reference]` as a leaf instead of letting a person click downwards forever.
     * `ValueVisualizer` threads a `visited` set for the same reason on the inline path; this is the
     * tree equivalent, and the tree needs it more, because `dir()` is resolved lazily on click and
     * so has no natural bottom.
     */
    fun browsableFor(value: Any?, title: String? = null, depth: Int = 0, path: List<Any> = emptyList()): Browsable? {
        if (!isExplorableObject(value)) return null
        return H2ReflectiveBrowsable(value!!, title, depth, path)
    }
}

/**
 * A domain object as a tree node: children are its properties, resolved on click.
 *
 * `dir()` re-enters [wrapForInspector], so a property holding a list keeps the existing paging, a
 * property holding another domain object keeps reflecting (to [H2_REFLECT_MAX_DEPTH]) and a property
 * holding a scalar becomes a leaf. `html()` is a `property | type | value` card so that clicking a
 * node shows its scalars immediately rather than an empty pane — which is what a wide object looked
 * like when the only thing behind it was `toString()`.
 */
private class H2ReflectiveBrowsable(
    private val value: Any,
    private val title: String?,
    private val depth: Int,
    private val path: List<Any>
) : Browsable {

    private val properties: List<H2Property> by lazy { H2Reflect.properties(value) }

    override fun heading(): String {
        val summary = H2Reflect.summaryLine(value)
        return if (title.isNullOrBlank() || title == "value") summary else "$title — $summary"
    }

    override fun dir(): Map<String, Any?>? {
        if (depth >= H2_REFLECT_MAX_DEPTH) return null
        if (properties.isEmpty()) return null

        val nextPath = path + value
        val out = LinkedHashMap<String, Any?>(properties.size)
        properties.forEach { prop ->
            val child = prop.value
            out[prop.label] = when {
                prop.failure != null -> H2FailedPropertyBrowsable(prop)
                child != null && nextPath.any { it === child } -> H2CircularBrowsable(child)
                else -> H2Reflect.browsableFor(child, prop.name, depth + 1, nextPath)
                    ?: wrapForInspector(child, title = prop.name)
            }
        }
        return out
    }

    override fun html(): String = h2PropertyCardHtml(heading(), properties)
}

/** A property whose getter threw, as its own node — visible rather than absent. */
private class H2FailedPropertyBrowsable(private val property: H2Property) : Browsable {
    override fun heading(): String = "${H2Property.FAILURE_MARK} ${property.name}"
    override fun dir(): Map<String, Any?>? = null
    override fun html(): String =
        "<div style='color:#f88;'>${h2Escape(property.name)}: ${h2Escape(property.failure ?: "unreadable")}</div>"
}

/** The point where the object graph loops back on itself. */
private class H2CircularBrowsable(private val target: Any) : Browsable {
    override fun heading(): String = "[circular reference]"
    override fun dir(): Map<String, Any?>? = null
    override fun html(): String =
        "<div style='color:#f88;'>[circular reference → ${h2Escape(target::class.simpleName ?: "?")}]</div>"
}

/**
 * `property | type | value`, as a [Table].
 *
 * `maxRows` is pinned to the row count because the reflective cap in [H2Reflect.properties] has
 * already done the bounding — Table's own elision would silently cut the list short a second time,
 * which is the trap `bufferPageTable` in `LazyValueVisualizer.kt` documents for the same reason.
 */
internal fun h2PropertyCardHtml(heading: String, properties: List<H2Property>): String {
    if (properties.isEmpty()) return "<i>${h2Escape(heading)}</i>"
    val table = Table(listOf("property", "type", "value"), title = heading)
        .rows(properties.map { listOf<Any?>(it.label, it.typeName, it.cellText) })
        .role("property", CellRole.ACCENT)
        .align("property", Align.LEFT)
        .align("type", Align.LEFT)
        .align("value", Align.LEFT)
    table.maxRows = properties.size.coerceAtLeast(1)
    return table.toHTML()
}

/**
 * A one-line preview of a property value for a table cell.
 *
 * Collections summarise rather than expand — the person clicks through to them, and a cell that
 * spilled a 100k-element list would make the table unreadable and the socket frame enormous.
 */
internal fun h2PreviewOf(value: Any?): String = when (value) {
    null -> "null"
    is CharSequence -> clipPreview(value.toString().replace('\n', ' '))
    is Map<*, *> -> "${value::class.simpleName ?: "Map"}(size=${value.size})"
    is Collection<*> -> "${value::class.simpleName ?: "Collection"}(size=${value.size})"
    is Array<*> -> "Array(size=${value.size})"
    is ByteArray -> "ByteArray(size=${value.size})"
    is ShortArray -> "ShortArray(size=${value.size})"
    is IntArray -> "IntArray(size=${value.size})"
    is LongArray -> "LongArray(size=${value.size})"
    is FloatArray -> "FloatArray(size=${value.size})"
    is DoubleArray -> "DoubleArray(size=${value.size})"
    is BooleanArray -> "BooleanArray(size=${value.size})"
    is CharArray -> "CharArray(size=${value.size})"
    is Buffer -> "${value::class.simpleName}(pos=${value.position()}, limit=${value.limit()}, cap=${value.capacity()})"
    // `toString` can throw as readily as a getter can, and on a half-constructed object it usually
    // does. A cell is not worth an exception escaping into the panel.
    else -> runCatching { clipPreview(value.toString().replace('\n', ' ')) }
        .getOrElse { "${value::class.simpleName ?: "?"} [toString threw]" }
}

private const val H2_PREVIEW_CLIP = 160

private fun clipPreview(text: String): String =
    if (text.length <= H2_PREVIEW_CLIP) text else text.take(H2_PREVIEW_CLIP - 3) + "..."

private fun h2Escape(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

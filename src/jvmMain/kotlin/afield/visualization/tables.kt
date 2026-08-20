@file:afield.CompletionCategory("Visualization")

package afield

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Tabular output — the structure, and the one renderer for it.
 *
 * [Table] is the counterpart of `showSignals` for data that is rows and columns rather than a
 * signal: build it, hand it to [Box.showTable] (or just let a box's last expression be one), and it
 * arrives on screen in the repo's output style. It implements [ToHTML], so `ValueVisualizer`
 * dispatches it ahead of everything else — `display(channel, table)`, `table.h`, and a returned
 * `Table` all render through [Table.toHTML] with no extra wiring.
 *
 * Why this exists: a box that wants a table has, until now, hand-rolled
 * `<table style='border-collapse:collapse…'>` with its own font stack, paddings and greys. A dozen
 * files under `src/` do exactly that and no two agree, which is a lot of markup standing between a
 * reader and what the box is actually saying.
 *
 * The part that is not just formatting is [Table.expect]. A table in a doc or a live box usually
 * carries a *claim* — "every builder advanced one frame per pump", "no residual exceeds 1e-6" — and
 * a reader is otherwise asked to verify it by scanning cells. State the claim and the cells that
 * break it colour themselves, the footnote says how many did, and [Table.deviations] hands the same
 * list back to a test. That is the difference between showing a grid and showing an assertion you
 * can watch fail.
 *
 * Trust model, same as the rest of value display: text produced by default formatting is escaped;
 * a string returned from a [Table.format] lambda is trusted HTML and inserted as-is.
 */

/** Horizontal alignment of a [Table] column. Numeric columns default to [RIGHT], everything else [LEFT]. */
enum class Align { LEFT, RIGHT, CENTER }

/** One cell that failed a [Table.expect] check: its row index, column name, value, and the reason given. */
data class Deviation(val row: Int, val column: String, val value: Any?, val because: String?)

/**
 * Muted grey for headers, notes and elisions — the same `#888` the rest of value display uses.
 *
 * Still a hex literal, unlike [CellRole]. Moving these two defaults onto the `--field-*` tokens is
 * the right end state and a small change, but it shifts the colour of every table already captured
 * in the docs, so it wants its own pass rather than riding along with a figure.
 */
const val TABLE_MUTED: String = "#888"

/** Default body text colour, matching `ValueVisualizer`'s wrapper. See [TABLE_MUTED] on the hex. */
const val TABLE_TEXT: String = "#ccc"

/**
 * The semantic colour roles, shared with `markdown()`'s `{role:text}` spans — same six names, same
 * `field-md-*` classes, so the same `--field-*` design token backs both. A cell that means "good"
 * and a markdown span that means "good" are therefore the same colour by construction, and neither
 * spells a hex literal.
 */
enum class CellRole(internal val cssClass: String) {
    ACCENT("field-md-accent"),
    MUTED("field-md-muted"),
    DIM("field-md-dim"),
    OK("field-md-ok"),
    WARN("field-md-warn"),
    BAD("field-md-bad"),
}

/**
 * Rows, columns, and the claims made about them.
 *
 * Construct with the column names, append rows, and decorate:
 *
 * ```
 * Table(listOf("builder", "calls", "frameNumber"))
 *     .row("eachFrame { }", 7, 7)
 *     .accent("frameNumber")
 *     .expect("frameNumber", because = "one frame per pump") { it == 7 }
 * ```
 *
 * Every decorator names its column with a *string*, so a typo or a rename would otherwise be a
 * silent no-op — the alignment never applies, the expectation never runs, and nothing fails. They
 * therefore throw [IllegalArgumentException] listing the real column names instead.
 *
 * Rendering is bounded: at most [maxRows] rows are drawn and the rest become a muted "… N more
 * rows" line, so a `Table` over something large is still safe to display.
 */
class Table(
    header: List<String>,
    /** Optional line drawn above the table, in the accent colour. */
    var title: String? = null,
    /** Optional footnote spanning every column. Deviation counts are appended to it. */
    var note: String? = null,
) : ToHTML {

    /** The column names, in order. */
    val header: List<String> = header.toList()

    private val cells = mutableListOf<List<Any?>>()

    /** The rows appended so far, each padded to [header]'s width. */
    val rows: List<List<Any?>> get() = cells

    /** How many rows to draw before eliding the tail. */
    var maxRows: Int = 64

    private val aligns = mutableMapOf<String, Align>()
    private val colors = mutableMapOf<String, String>()
    private val roles = mutableMapOf<String, CellRole>()
    private val bars = mutableMapOf<String, Pair<Double?, Int>>()
    private val roleFns = mutableMapOf<String, (Any?) -> CellRole?>()
    private val formats = mutableMapOf<String, (Any?) -> String>()
    private val checks = mutableListOf<Triple<String?, String?, (Int, String, Any?) -> Boolean>>()

    init {
        require(header.isNotEmpty()) { "a Table needs at least one column" }
    }

    private fun columnIndex(column: String): Int {
        val i = header.indexOf(column)
        require(i >= 0) { "no column named `$column` in this table; columns are $header" }
        return i
    }

    /** Appends one row. Short rows are padded with `null`; a row wider than [header] is an error. */
    fun row(vararg values: Any?): Table = row(values.asList())

    /** Appends one row from a list. Short rows are padded with `null`; too-wide rows are an error. */
    fun row(values: List<Any?>): Table {
        require(values.size <= header.size) {
            "row has ${values.size} cells but the table has ${header.size} columns ($header)"
        }
        cells += values + List(header.size - values.size) { null }
        return this
    }

    /** Appends many rows. */
    fun rows(values: List<List<Any?>>): Table = apply { values.forEach { row(it) } }

    /** Overrides the inferred alignment of a column. */
    fun align(column: String, align: Align): Table = apply { aligns[header[columnIndex(column)]] = align }

    /** Colours a column's cells. */
    fun color(column: String, color: String): Table = apply { colors[header[columnIndex(column)]] = color }

    /**
     * Colours these columns from [signalPalette], in order — so a table and a plot of the same
     * quantities agree on which colour means which series.
     */
    fun accent(vararg columns: String): Table = apply {
        columns.forEachIndexed { i, c -> color(c, signalPalette[i % signalPalette.size]) }
    }

    /** Gives a column's cells a semantic [CellRole] instead of a colour. */
    fun role(column: String, role: CellRole): Table = apply { roles[header[columnIndex(column)]] = role }

    /**
     * Gives each cell of a column a [CellRole] chosen from its value — the per-*cell* counterpart of
     * [color], for a column that means something different row by row (ran / did not run, converged
     * / diverged). Return null to leave a cell alone.
     */
    fun roleBy(column: String, role: (Any?) -> CellRole?): Table =
        apply { roleFns[header[columnIndex(column)]] = role }

    /**
     * Draws a numeric column's cells as proportional bars *as well as* text — the shape a ranked
     * score wants: top-k labels, vote tallies, per-stage timings.
     *
     * The bar is drawn in `currentColor`, so it inherits whatever [role] or [color] the column
     * carries and brings no palette of its own. [max] defaults to the column's own largest
     * magnitude, which is what you want for a tally; pass `1.0` for a probability, so the bar means
     * "of the whole" rather than "of the biggest one present". Composes with [format], which still
     * owns the text.
     */
    fun bar(column: String, max: Double? = null, width: Int = 120): Table =
        apply { bars[header[columnIndex(column)]] = max to width }

    /**
     * Formats a column's cells. The returned string is **trusted HTML** and is not escaped, which is
     * how a cell gets a link, a unit, or a nested fragment; escape it yourself if it came from
     * outside.
     */
    fun format(column: String, format: (Any?) -> String): Table =
        apply { formats[header[columnIndex(column)]] = format }

    /**
     * States a claim about one column: cells for which [ok] is false take [CellRole.BAD], gain a
     * tooltip, are counted in the footnote, and appear in [deviations].
     */
    fun expect(column: String, because: String? = null, ok: (Any?) -> Boolean): Table {
        val name = header[columnIndex(column)]
        checks += Triple(name, because, { _, _, v -> ok(v) })
        return this
    }

    /** States a claim about every cell, given its row index, column name and value. */
    fun expect(because: String? = null, ok: (row: Int, column: String, value: Any?) -> Boolean): Table {
        checks += Triple(null, because, ok)
        return this
    }

    /**
     * Every cell that failed an [expect] check — the same list the rendering colours, so a test can
     * assert on the claim the figure makes rather than on its markup.
     */
    val deviations: List<Deviation>
        get() = buildList {
            cells.forEachIndexed { r, row ->
                header.forEachIndexed { c, name ->
                    val value = row[c]
                    checks.forEach { (only, because, ok) ->
                        if ((only == null || only == name) && !ok(r, name, value)) {
                            add(Deviation(r, name, value, because))
                        }
                    }
                }
            }
        }

    private fun failure(rowIndex: Int, column: String, value: Any?): String? {
        checks.forEach { (only, because, ok) ->
            if ((only == null || only == column) && !ok(rowIndex, column, value)) {
                return because ?: "outside the expected range"
            }
        }
        return null
    }

    /**
     * The role a cell carries: a failed [expect] outranks everything, then [roleBy], then [role].
     * Takes [failed] rather than re-deriving it, so the checks run once per cell and not twice.
     */
    private fun roleOf(column: String, value: Any?, failed: Boolean): CellRole? {
        if (failed) return CellRole.BAD
        roleFns[column]?.invoke(value)?.let { return it }
        return roles[column]
    }

    private fun barMax(column: String): Double {
        val c = header.indexOf(column)
        return cells.mapNotNull { (it[c] as? Number)?.toDouble()?.let(::abs) }.maxOrNull() ?: 0.0
    }

    /** The bar graphic for one cell, or "" when the column has none or the value is not a number. */
    private fun barHtml(column: String, value: Any?): String {
        val (explicitMax, width) = bars[column] ?: return ""
        val v = (value as? Number)?.toDouble() ?: return ""
        val max = explicitMax ?: barMax(column)
        if (max <= 0.0 || !max.isFinite() || !v.isFinite()) return ""
        val pct = String.format(Locale.US, "%.2f", (abs(v) / max * 100.0).coerceIn(0.0, 100.0))
        return "<span style='display:inline-block;vertical-align:middle;width:${width}px;height:8px;" +
            "background:color-mix(in srgb,currentColor 16%,transparent)'>" +
            "<span style='display:block;height:100%;width:$pct%;background:currentColor'></span>" +
            "</span> "
    }

    // A barred column left-aligns unless told otherwise: right-aligned bars would end together and
    // start ragged, which is the one arrangement that cannot be read.
    private fun alignOf(column: String): Align {
        aligns[column]?.let { return it }
        if (column in bars) return Align.LEFT
        val c = header.indexOf(column)
        val values = cells.mapNotNull { it[c] }
        return if (values.isNotEmpty() && values.all { it is Number }) Align.RIGHT else Align.LEFT
    }

    private fun text(column: String, value: Any?): String =
        formats[column]?.invoke(value) ?: escapeTableHtml(defaultCellText(value))

    /** A plain-text rendering, for a terminal, a log, or a test's failure message. */
    fun toText(): String {
        val shown = cells.take(maxRows)
        val widths = header.mapIndexed { c, name ->
            maxOf(name.length, shown.maxOfOrNull { defaultCellText(it[c]).length } ?: 0)
        }
        fun pad(s: String, w: Int, align: Align) =
            if (align == Align.RIGHT) s.padStart(w) else s.padEnd(w)
        return buildString {
            title?.let { appendLine(it) }
            appendLine(header.mapIndexed { c, n -> pad(n, widths[c], alignOf(n)) }.joinToString("  ").trimEnd())
            shown.forEach { row ->
                appendLine(
                    header.mapIndexed { c, n -> pad(defaultCellText(row[c]), widths[c], alignOf(n)) }
                        .joinToString("  ").trimEnd()
                )
            }
            if (cells.size > shown.size) appendLine("… ${cells.size - shown.size} more rows")
            footnote()?.let { appendLine(it) }
        }
    }

    private fun footnote(): String? {
        val failed = deviations.size
        val base = note?.takeIf { it.isNotBlank() }
        return when {
            failed == 0 -> base
            base == null -> "$failed cell${if (failed == 1) "" else "s"} outside expectation"
            else -> "$base — $failed cell${if (failed == 1) "" else "s"} outside expectation"
        }
    }

    /** The rendered table — dispatched by `ValueVisualizer` for `display`, `.h` and box return values. */
    override fun toHTML(): String = buildString {
        val shown = cells.take(maxRows)
        fun padding(c: Int) = if (c == 0) "3px 0" else "3px 0 3px 16px"

        append("<div style=\"font:12px/1.4 'Iosevka Web',monospace;color:$TABLE_TEXT\">")
        title?.let {
            append("<div style='color:${signalPalette[0]};margin-bottom:5px'>${escapeTableHtml(it)}</div>")
        }
        append("<table style='border-collapse:collapse'>")

        append("<thead><tr>")
        header.forEachIndexed { c, name ->
            append(
                "<th style='padding:${padding(c)};color:$TABLE_MUTED;font-weight:normal;" +
                    "text-align:${alignOf(name).css}'>${escapeTableHtml(name)}</th>"
            )
        }
        append("</tr></thead>")

        append("<tbody>")
        shown.forEachIndexed { r, row ->
            append("<tr>")
            header.forEachIndexed { c, name ->
                val why = failure(r, name, row[c])
                val role = roleOf(name, row[c], failed = why != null)
                append("<td")
                role?.let { append(" class='${it.cssClass}'") }
                append(" style='padding:${padding(c)};text-align:${alignOf(name).css}")
                if (role == null) colors[name]?.let { append(";color:$it") }
                append("'")
                why?.let { append(" title='${escapeTableHtml(it)}'") }
                append(">")
                append(barHtml(name, row[c]))
                append(text(name, row[c]))
                append("</td>")
            }
            append("</tr>")
        }
        append("</tbody>")

        val elided = cells.size - shown.size
        val foot = footnote()
        if (elided > 0 || foot != null) {
            append("<tfoot>")
            if (elided > 0) {
                append(
                    "<tr><td colspan='${header.size}' style='padding:4px 0 0 0;color:$TABLE_MUTED'>" +
                        "… $elided more row${if (elided == 1) "" else "s"}</td></tr>"
                )
            }
            foot?.let {
                append(
                    "<tr><td colspan='${header.size}' style='padding:10px 0 0 0;color:$TABLE_MUTED'>" +
                        escapeTableHtml(it) + "</td></tr>"
                )
            }
            append("</tfoot>")
        }
        append("</table></div>")
    }

    /** A shape summary; [toText] is the one that prints the contents. */
    override fun toString(): String = "Table(${header.size} columns, ${cells.size} rows)"
}

private val Align.css: String
    get() = when (this) {
        Align.LEFT -> "left"
        Align.RIGHT -> "right"
        Align.CENTER -> "center"
    }

/**
 * A [Table] from named columns — the column-major entry point, shaped like `showSignals`:
 * `table("pump" to pumps, "calls" to calls)`. Columns of unequal length are padded with `null`.
 */
fun table(vararg columns: Pair<String, List<Any?>>): Table {
    require(columns.isNotEmpty()) { "a Table needs at least one column" }
    val t = Table(columns.map { it.first })
    val height = columns.maxOf { it.second.size }
    for (r in 0 until height) t.row(columns.map { it.second.getOrNull(r) })
    return t
}

/**
 * A [Table] over a list of records — a uniform list of data classes (columns from the primary
 * constructor), of maps (columns from the keys), or of equal-length lists (columns by index).
 *
 * Returns `null` for anything else, so a caller can fall back rather than get a one-column table of
 * `toString()`. This is the shape `ValueVisualizer.formatList` currently calls "a table of records"
 * in a comment and then does not draw.
 */
fun recordTable(items: List<*>): Table? {
    if (items.size < 2 || items.any { it == null }) return null
    val first = items.first()!!

    if (items.all { it is Map<*, *> }) {
        val keys = LinkedHashSet<String>()
        items.forEach { (it as Map<*, *>).keys.forEach { k -> keys += k.toString() } }
        if (keys.isEmpty()) return null
        val t = Table(keys.toList())
        items.forEach { item ->
            val m = (item as Map<*, *>).entries.associate { (k, v) -> k.toString() to v }
            t.row(keys.map { m[it] })
        }
        return t
    }

    if (items.all { it is List<*> }) {
        val width = (first as List<*>).size
        if (width < 2 || items.any { (it as List<*>).size != width }) return null
        val t = Table((0 until width).map { it.toString() })
        items.forEach { t.row(it as List<*>) }
        return t
    }

    val type = first::class
    if (!type.isData || items.any { it!!::class != type }) return null

    // Constructor order, not `memberProperties` order: the latter is alphabetical, which would
    // scramble the column order a data class declares.
    val props = runCatching {
        val declared = type.primaryConstructor?.parameters?.mapNotNull { it.name } ?: return@runCatching null
        val byName = type.memberProperties.associateBy { it.name }
        declared.mapNotNull { name -> byName[name] }
    }.getOrNull()?.takeIf { it.size >= 2 } ?: return null

    // A private or otherwise inaccessible record would read back as a row of nulls, which is worse
    // than no table at all — so an unreadable property declines the whole thing rather than lying.
    val t = Table(props.map { it.name })
    for (item in items) {
        val row = props.map { p ->
            runCatching { p.getter.isAccessible = true; p.getter.call(item) }.getOrElse { return null }
        }
        t.row(row)
    }
    return t
}

/**
 * Put a [Table] on the screen and hand it back — the terse "this table, on screen, now" helper, the
 * tabular counterpart of `showSignals`.
 *
 * The table is already displayed on return; the [Table] comes back only so a caller can read
 * [Table.deviations] or keep decorating a copy. Do not `display` it again.
 */
fun Box.showTable(table: Table, channel: String = "output"): Table {
    display(channel, table)
    return table
}

/** Builds a [Table] from named columns with [table] and displays it. */
fun Box.showTable(
    vararg columns: Pair<String, List<Any?>>,
    channel: String = "output",
    title: String? = null,
    note: String? = null,
): Table = showTable(table(*columns).also { it.title = title; it.note = note }, channel)

/**
 * Displays a list of records as a table via [recordTable], falling back to a single `value` column
 * when the list is not uniform enough to have columns.
 */
fun Box.showTable(
    items: List<*>,
    channel: String = "output",
    title: String? = null,
    note: String? = null,
): Table {
    val t = recordTable(items) ?: Table(listOf("value")).also { items.forEach { v -> it.row(v) } }
    t.title = title
    t.note = note
    return showTable(t, channel)
}

/**
 * Compact default text for a cell: whole-valued floats lose their `.0` and the rest keep four
 * decimals — except magnitudes that four decimals would destroy, which go to scientific notation.
 * A converged residual of `1e-9` must not print as `0`.
 */
internal fun defaultCellText(value: Any?): String = when (value) {
    null -> ""
    is Float -> formatCellNumber(value.toDouble())
    is Double -> formatCellNumber(value)
    else -> value.toString()
}

private fun formatCellNumber(d: Double): String = when {
    !d.isFinite() -> d.toString()
    d == 0.0 -> "0"
    d == floor(d) && abs(d) < 1e15 -> d.toLong().toString()
    abs(d) < 1e-4 || abs(d) >= 1e15 -> String.format(Locale.US, "%.3e", d)
    else -> String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')
}

private fun escapeTableHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

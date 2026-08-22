package afield

import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.DataRow
import org.jetbrains.kotlinx.dataframe.api.GroupBy

/**
 * Makes Kotlin DataFrame values render as Field values — `.h`, `.h2`, `display` and box return
 * values — without DataFrame having to know Field exists.
 *
 * `DataFrame` is a third-party interface, so it cannot implement [ToHTML] the way [Table] and [Img]
 * do. Both registries exist for exactly this: [ValuePrinterRegistry] for the compact `.h` form, and
 * [H2ValueRegistry] for `.h2`. Everything here is a thin adapter onto primitives that already exist,
 * so a frame gets the same styling, the same expander and the same paging as every other value.
 *
 * ### `.h` — a head, and the shape
 *
 * [table] already renders a column-oriented table in the repo's output style, so a frame's compact
 * form is `table(...)` over the first [HEAD_ROWS] rows plus a shape line. Only the head is touched:
 * `take` on each column's iterator, never `toList()` on the frame, so printing a million-row frame
 * costs the same as printing ten rows.
 *
 * ### `.h2` — a streaming scroller, for free
 *
 * [H2ValueSnapshot] *adapts* one value into another for the lazy renderer, rather than producing
 * HTML. So `.h2` on a frame adapts it into [LazyRows] — a `List<Map<String, Any?>>` view that reads a
 * row only when asked — and the h2 **explorer** (`h2explorer.kt`, reached as an iframe onto
 * `/h2-explorer?root=<token>` and fed over the `/ws` `__h2_explorer__` channel) streams windows of it
 * by index. That is what makes this a real scroller for a large frame rather than a big HTML dump: the
 * server holds the frame (not a rendering of it) behind an `.h2` token, and each scroll materialises
 * only the window being looked at.
 *
 * Do not confuse that path with `ValueVisualizer.renderListContinuation`, which serves
 * `/lazy-value-fragment` — a *different* renderer for expanding a value in place. This KDoc named the
 * wrong one until 2026-08-22, and the cost was concrete: the smoke test exercised
 * `renderLazyFragment` believing it was the `.h2` path, so every row of a `.h2` frame rendered as the
 * string `Map(size=3)` in the actual widget and nothing failed.
 *
 * Doing it this way rather than with a bespoke widget also means a frame nested *inside* another
 * value — a list of frames, a data class with a frame field — expands with the same machinery.
 *
 * ### Registration
 *
 * [install] is idempotent and called from `Server.main`. A bare `jvm-main` harness does not run
 * that, so tests call it explicitly; see `DataFrameVisualizerSmokeTest`.
 */
object DataFrameVisualizer {

    /** Rows shown in the compact `.h` form. `.h2` streams the whole frame instead. */
    const val HEAD_ROWS: Int = 10

    @Volatile
    private var installed = false

    /** Register every DataFrame renderer. Idempotent, and safe to call from any thread. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true

        ValuePrinterRegistry.register(DataFrame::class.java) { frame -> frameHead(frame) }
        ValuePrinterRegistry.register(DataColumn::class.java) { column -> columnHead(column) }
        ValuePrinterRegistry.register(DataRow::class.java) { row -> rowFacts(row) }
        ValuePrinterRegistry.register(GroupBy::class.java) { grouped -> groupBySummary(grouped) }

        // `.h2` on a frame streams every row; the other three are small enough that their compact
        // form is already the whole story, so they fall through to the default lazy rendering.
        H2ValueRegistry.register(DataFrame::class.java) { frame ->
            H2ValueSnapshot(
                value = LazyRows(frame),
                summary = shape(frame),
                title = "DataFrame"
            )
        }
    }

    /** `12 rows × 3 columns`, the line worth having above any table. */
    private fun shape(frame: DataFrame<*>): String =
        "${frame.rowsCount()} rows × ${frame.columnsCount()} columns"

    /**
     * The head of a frame as a [Table], plus its shape.
     *
     * Deliberately `take(HEAD_ROWS)` per column rather than materialising the frame: this is the
     * form that shows up in a box output on every execution, so it has to stay cheap for a frame far
     * larger than the screen.
     */
    private fun frameHead(frame: DataFrame<*>): String {
        val columns = frame.columns()
        if (columns.isEmpty()) return "<span style='color: $TABLE_MUTED;'>empty DataFrame</span>"

        val head: List<Pair<String, List<Any?>>> =
            columns.map { column -> column.name() to headCells(column) }
        val rendered = table(*head.toTypedArray()).toHTML()
        val hidden = frame.rowsCount() - HEAD_ROWS
        val note = if (hidden > 0) " — first $HEAD_ROWS; use <code>.h2</code> to scroll all of them" else ""
        return rendered + "<div style='color: $TABLE_MUTED; font-size: 90%;'>${shape(frame)}$note</div>"
    }

    /**
     * The first [HEAD_ROWS] cells of a column, by index.
     *
     * Indexing rather than `take`: DataFrame declares its own `take` on a column and Kotlin declares
     * one on `Iterable`, so the call is ambiguous — and indexing is what actually guarantees only the
     * head is touched, which is the point for a frame far larger than the screen.
     */
    private fun headCells(column: DataColumn<*>): List<Any?> {
        val n = minOf(HEAD_ROWS, column.size())
        return (0 until n).map { column[it] }
    }

    /** A single column: its name and element type, then its head. */
    private fun columnHead(column: DataColumn<*>): String {
        val cells: Pair<String, List<Any?>> = column.name() to headCells(column)
        val rendered = table(cells).toHTML()
        val hidden = column.size() - HEAD_ROWS
        val note = if (hidden > 0) " — first $HEAD_ROWS of ${column.size()}" else ""
        return rendered +
            "<div style='color: $TABLE_MUTED; font-size: 90%;'>${column.type()}$note</div>"
    }

    /** A row reads as label/value pairs, which is what [facts] is for. */
    private fun rowFacts(row: DataRow<*>): String =
        facts(row.df().columns().map { column -> column.name() to row[column.name()] }).toHTML()

    /** A grouped frame: how many groups, keyed by what. */
    private fun groupBySummary(grouped: GroupBy<*, *>): String {
        val keys = grouped.keys
        return facts(
            "groups" to keys.rowsCount(),
            "keyed by" to keys.columnNames().joinToString(", "),
        ).toHTML() + keys.let { frameHead(it) }
    }

    /**
     * A frame seen as its rows, materialised one at a time.
     *
     * This is what turns `.h2` into a scroller rather than a dump. The lazy visualizer pages a list
     * by index, so holding the frame and answering [get] on demand means only the rows actually being
     * looked at are ever built — the whole point when the frame is larger than memory would like.
     */
    private class LazyRows(private val frame: DataFrame<*>) : AbstractList<Map<String, Any?>>() {
        private val names = frame.columns().map { it.name() }

        override val size: Int get() = frame.rowsCount()

        override fun get(index: Int): Map<String, Any?> {
            val row = frame[index]
            return LinkedHashMap<String, Any?>(names.size).apply {
                names.forEach { name -> put(name, row[name]) }
            }
        }
    }
}

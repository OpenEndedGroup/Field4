@file:afield.CompletionCategory("Visualization")

package afield

import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.DataFrame

/**
 * A typed frame, charting its own columns.
 *
 * `DataFrameVisualizer` already teaches a frame to render as a [Table] for `.h` and to stream its
 * rows for `.h2`, which is the right compact form: a frame is rows and columns first. What it had no
 * answer for is the other question a reader has about a numeric column, which is *what shape is it*.
 *
 * This is that answer, and it is thin — nine lines of column selection over [Chart].
 * The interesting decision is which columns, and the rule is the one that makes `frame.chart()` with
 * no arguments do the obvious thing:
 *
 *  - a **numeric** x column and the rest numeric: lines against it, one series per column;
 *  - a **categorical** first column (strings, or anything not numeric) and one numeric column: bars,
 *    with the category names on the axis — which is what a `groupBy(...).count()` looks like, and the
 *    commonest frame anyone actually plots;
 *  - no x column named and none obvious: the row index.
 *
 * Non-numeric cells inside a numeric column become gaps rather than zeroes. A missing measurement is
 * not a measurement of zero, and drawing it as one is the single most common way a plot lies.
 */

/** How many series `chart()` will draw before it stops adding them. */
private const val MAX_AUTO_SERIES = 6

/** Whether a column's values can be plotted on a numeric axis. */
private fun DataColumn<*>.isNumericColumn(): Boolean {
    val n = minOf(size(), 32)
    if (n == 0) return false
    var seen = 0
    for (i in 0 until n) {
        val v = this[i] ?: continue
        if (v !is Number) return false
        seen++
    }
    return seen > 0
}

private fun DataColumn<*>.numbers(): List<Number> =
    (0 until size()).map { (this[it] as? Number) ?: Double.NaN }

private fun DataColumn<*>.strings(): List<String> =
    (0 until size()).map { this[it]?.toString() ?: "" }

/**
 * A [Chart] of this frame's numeric columns.
 *
 * ```
 * val people = dataFrameOf("name", "age")("Alice", 15, "Bob", 30, "Cara", 45)
 * people.chart()          // bars: age per name
 * people.chart(x = "age") // age on the x axis
 * ```
 *
 * @param x the column to put on the x axis, or `null` to choose one — the first numeric column if
 *   the frame has two or more, otherwise the first column as categories, otherwise the row index.
 * @param y the columns to draw, or `null` for every remaining numeric column (at most
 *   [MAX_AUTO_SERIES], because a legend with fifteen entries in it is not a figure).
 * @param points also draw a dot per datum, which is what makes the figure clickable. On by default
 *   and automatically dropped for a frame with more rows than [Chart.pickLimit], where individual
 *   dots would be neither visible nor worth a callback each.
 */
fun DataFrame<*>.chart(
    x: String? = null,
    y: List<String>? = null,
    title: String? = null,
    points: Boolean = true,
): Chart {
    val columns = columns()
    require(columns.isNotEmpty()) { "an empty DataFrame has nothing to chart" }

    val byName = columns.associateBy { it.name() }
    fun column(name: String): DataColumn<*> = byName[name]
        ?: throw IllegalArgumentException(
            "no column named `$name` in this frame; columns are ${columns.map { it.name() }}"
        )

    val numeric = columns.filter { it.isNumericColumn() }
    require(numeric.isNotEmpty()) {
        "no numeric column to chart; columns are ${columns.map { "${it.name()}: ${it.type()}" }}"
    }

    val xColumn = x?.let { column(it) }
        ?: if (numeric.size >= 2) numeric.first()
        else if (!columns.first().isNumericColumn()) columns.first()
        else null

    val yColumns = (y?.map { column(it) } ?: numeric.filter { it !== xColumn })
        .filter { it.isNumericColumn() }
        .take(MAX_AUTO_SERIES)
    require(yColumns.isNotEmpty()) {
        "nothing left to plot against `${xColumn?.name() ?: "the row index"}`"
    }

    val chart = Chart(
        title = title,
        xLabel = xColumn?.name() ?: "row",
        yLabel = if (yColumns.size == 1) yColumns.first().name() else null,
    )
    chart.note = "${rowsCount()} rows × ${columnsCount()} columns"

    // A categorical x with a single measurement is a bar chart, and pretending otherwise (indices on
    // the axis, a line joining unrelated categories) is the wrong picture rather than a plain one.
    if (xColumn != null && !xColumn.isNumericColumn()) {
        // One series only: a second bars mark would land on the same slots and hide the first. Say
        // so in the footnote rather than silently drawing one column and dropping the rest.
        val only = yColumns.first()
        chart.bars(only.name(), xColumn.strings(), only.numbers())
        if (yColumns.size > 1) {
            chart.note = "${chart.note} — showing `${only.name()}`; " +
                "pass y = listOf(…) for ${yColumns.drop(1).joinToString { "`${it.name()}`" }}"
        }
        return chart
    }

    val xs = xColumn?.numbers() ?: (0 until rowsCount()).map { it }
    val drawPoints = points && rowsCount() <= chart.pickLimit
    yColumns.forEach { c ->
        chart.line(c.name(), xs, c.numbers())
        if (drawPoints) chart.points(c.name(), xs, c.numbers(), color = chart.marks.last().color)
    }
    return chart
}

/**
 * A [DataFrame] charted straight into an output channel — the plot counterpart of letting a frame be
 * a box's last expression, which gives you the table.
 */
fun Box.showChart(
    frame: DataFrame<*>,
    x: String? = null,
    y: List<String>? = null,
    title: String? = null,
    channel: String = "output",
): Chart = showChart(frame.chart(x, y, title), channel)

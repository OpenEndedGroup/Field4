package afield.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `.h2` scroller renders a list of same-shaped maps as a TABLE, not a column of summaries.
 *
 * `.h2` on a DataFrame adapts the frame into a `List<Map<String, Any?>>` (`DataFrameVisualizer`'s
 * `LazyRows`) and hands it to this explorer. Every row rendered as the string `Map(size=3)` until
 * 2026-08-22, and none of the existing coverage could see it: the smoke test drives
 * `ValueVisualizer.renderLazyFragment` — the `/lazy-value-fragment` renderer — while the `.h2`
 * widget is an iframe onto `/h2-explorer`, which is this file. Paging was asserted; cells were not.
 */
class H2ExplorerRecordRowsTest {

    private fun rowsOf(n: Int): List<Map<String, Any?>> =
        (0 until n).map { r -> linkedMapOf<String, Any?>("i" to r, "label" to "row-$r", "square" to r.toLong() * r) }

    @Test
    fun `the premise - the explorer classifies this shape as a table at all`() {
        // Assert the premise first, so a change in how the explorer picks a source fails HERE
        // loudly rather than turning every assertion below into a tautology. The generic path
        // yields exactly one column named "value"; anything else means a record table was chosen.
        val (columns, _) = h2ExplorerCellsForTesting(rowsOf(3), start = 0, count = 1)
        assertTrue(columns.size > 1, "expected a multi-column table, got $columns")
        assertEquals(listOf("i", "label", "square"), columns, "key order is column order")
    }

    @Test
    fun `uniform maps become one column per key, carrying real cell values`() {
        val (columns, cells) = h2ExplorerCellsForTesting(rowsOf(15_000), start = 0, count = 3)

        assertEquals(listOf("i", "label", "square"), columns, "columns come from the row keys, in order")
        assertEquals(3, cells.size)
        cells.forEachIndexed { row, r ->
            assertEquals(columns.size, r.size, "row $row must have one cell per column")
        }
        assertEquals("row-0", cells[0][1])
        assertEquals("row-1", cells[1][1])
        assertEquals("row-2", cells[2][1])
        assertEquals("0", cells[0][0])
        assertEquals("1", cells[1][0])
        assertEquals("4", cells[2][2], "square of 2")
    }

    /**
     * The counterfactual, floored at a number rather than "differs at all": the regression rendered
     * EVERY row as the same summary string, so the bug's signature is a collapse in distinct cells.
     */
    @Test
    fun `the rendering is not a column of identical summaries`() {
        val (columns, cells) = h2ExplorerCellsForTesting(rowsOf(200), start = 0, count = 20)
        val flat = cells.flatten()

        assertTrue(flat.isNotEmpty(), "no cells at all would make every assertion below vacuous")
        assertTrue(flat.none { it.startsWith("Map(size=") }, "a row must not render as its own summary")
        // 20 rows x 3 columns of genuinely varying data. The regression produced exactly 1.
        assertTrue(
            flat.distinct().size >= 40,
            "expected >= 40 distinct cells across 20 rows x ${columns.size} columns, got ${flat.distinct().size}",
        )
    }

    @Test
    fun `paging keeps a row's own cells - offset 9000 is not offset 0`() {
        val (_, first) = h2ExplorerCellsForTesting(rowsOf(15_000), start = 0, count = 2)
        val (_, deep) = h2ExplorerCellsForTesting(rowsOf(15_000), start = 9_000, count = 2)

        assertEquals("row-0", first[0][1])
        assertEquals("row-9000", deep[0][1])
        assertEquals("row-9001", deep[1][1])
    }

    @Test
    fun `a ragged list keeps its summary rather than shifting cells`() {
        // Beyond the sampled prefix a row need not carry the same keys. A missing key must be an
        // empty cell and a non-map row must not shift the table; either way the cell count holds.
        val ragged = listOf<Any?>(
            linkedMapOf<String, Any?>("i" to 0, "label" to "row-0"),
            linkedMapOf<String, Any?>("i" to 1),
            "not a map",
        )
        val (columns, cells) = h2ExplorerCellsForTesting(ragged, start = 0, count = 3)
        cells.forEachIndexed { row, r ->
            assertEquals(columns.size, r.size, "row $row must still have one cell per column")
        }
    }

    @Test
    fun `a list of plain values is untouched by the record path`() {
        val (columns, cells) = h2ExplorerCellsForTesting(listOf("a", "b", "c"), start = 0, count = 3)
        assertEquals(listOf("value"), columns, "a non-record list keeps the generic single column")
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), cells)
    }
}

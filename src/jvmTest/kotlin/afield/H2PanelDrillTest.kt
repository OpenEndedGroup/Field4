package afield

import afield.server.h2ExplorerDrillForTesting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel navigates in place.
 *
 * Before this, `/h2-explorer` handled exactly two ops — `open` and `window` — and rendered one flat
 * table of the root's rows. A nested value became an `h2PreviewOf` text cell, so a `Box`'s property
 * bag read `Map(size=19)` and there was nothing to click: the only way in was the `outliner` and
 * `columns` links, which open a different UI. The complaint that produced this was exactly that.
 *
 * The drill is a request `path` of row indices, re-resolved from the retained root each time, so it
 * mints no token and cannot outlive the root it addresses.
 */
class H2PanelDrillTest {

    private data class Leaf(val a: Int, val b: String)

    @Test
    fun `the premise - a nested map used to be a text cell with nothing behind it`() {
        val nested = mapOf("outer" to mapOf("inner" to 1, "other" to 2))
        val root = h2ExplorerDrillForTesting(nested)

        // The cell text is still the summary -- that part was never wrong.
        assertEquals(1, root.totalRows)
        // What changed is that the row now declares itself enterable.
        assertEquals(listOf<Pair<String?, Boolean>>("outer" to true), root.rows)
        assertTrue(root.breadcrumb.isEmpty(), "the root has no trail")
    }

    @Test
    fun `entering a row shows that value's own rows, with a breadcrumb`() {
        val nested = mapOf("outer" to mapOf("inner" to 1, "other" to 2))

        val inner = h2ExplorerDrillForTesting(nested, path = listOf(0))
        assertEquals(2, inner.totalRows, "we are inside the inner map now")
        assertEquals(listOf("inner", "other"), inner.rows.map { it.first })
        assertEquals(listOf("outer"), inner.breadcrumb, "the trail names the row we entered")
        assertEquals(listOf(0), inner.resolvedPath)

        // Scalars are the bottom: nothing to enter.
        assertTrue(inner.rows.none { it.second }, "an Int row must not offer to be entered")
    }

    @Test
    fun `a Box's property bag is reachable in one click, and its rows are entered by adapter`() {
        registerH2DomainAdapters()
        val container = Box("drill-container").also { it.name = "container" }
        val neighbour = Box("drill-neighbour").also { it.name = "neighbour" }
        container.children.add(neighbour)
        neighbour.parents.add(container)

        // The root is the Box adapter's map, exactly as `visualize` retains it.
        val adapted = h2AdaptedValue(container)
        val root = h2ExplorerDrillForTesting(adapted)
        val propertiesRow = root.rows.indexOfFirst { it.first == "properties" }
        assertTrue(propertiesRow >= 0, "expected a `properties` row; got ${root.rows.map { it.first }}")
        assertTrue(root.rows[propertiesRow].second, "the property bag must be enterable")

        val bag = h2ExplorerDrillForTesting(adapted, path = listOf(propertiesRow))
        assertEquals(listOf("properties"), bag.breadcrumb)
        assertTrue(bag.totalRows > 3, "the bag has real entries; got ${bag.totalRows}")

        // And the row that was the whole point: a raw Box inside the bag, entered through its
        // ADAPTER rather than reflected. `engine` is an adapter-only row; `ki` is reflection's.
        val childrenRow = bag.rows.indexOfFirst { it.first == "children" }
        assertTrue(childrenRow >= 0, "expected a `children` row; got ${bag.rows.map { it.first }}")
        val children = h2ExplorerDrillForTesting(adapted, path = listOf(propertiesRow, childrenRow))
        assertEquals(1, children.totalRows, "one neighbour")

        val box = h2ExplorerDrillForTesting(adapted, path = listOf(propertiesRow, childrenRow, 0))
        val keys = box.rows.mapNotNull { it.first }
        assertTrue(keys.any { "engine" in it }, "a nested Box must arrive via its adapter; got $keys")
        assertFalse(keys.any { it == "ki" }, "a nested Box must not be reflected; got $keys")
        assertNull(neighbour.liveKi, "and entering it must not compile an engine")
        assertEquals(listOf("properties", "children"), box.breadcrumb.take(2))
    }

    @Test
    fun `a stale or out-of-range path truncates to the deepest step that still resolves`() {
        val nested = mapOf("outer" to mapOf("inner" to 1))

        // Row 9 does not exist; row 0 does.
        val clamped = h2ExplorerDrillForTesting(nested, path = listOf(0, 9, 4))
        assertEquals(listOf(0), clamped.resolvedPath, "the walk stops at the last valid step")
        assertEquals(listOf("outer"), clamped.breadcrumb, "and the trail matches the path")
        assertEquals(1, clamped.totalRows, "we are in the inner map, not lost")

        // Entering a scalar is refused rather than producing a one-cell dead end.
        val intoScalar = h2ExplorerDrillForTesting(nested, path = listOf(0, 0))
        assertEquals(listOf(0), intoScalar.resolvedPath, "a scalar row cannot be entered")
    }

    @Test
    fun `a numeric stream row is not enterable, because a row there is not one value`() {
        // A list of numbers renders as a strided stream: row 3 at stride 4 covers four elements, so
        // there is no single value a click could mean.
        val numbers = (1..64).map { it.toDouble() }
        val probe = h2ExplorerDrillForTesting(numbers)
        assertTrue(probe.rows.isNotEmpty(), "premise: the stream rendered rows")
        assertTrue(probe.rows.none { it.second }, "no stream row may claim to be enterable")

        // Whereas a list of maps -- a record table -- is one row per element and can be entered.
        val records = listOf(mapOf("a" to 1, "b" to 2), mapOf("a" to 3, "b" to 4))
        val table = h2ExplorerDrillForTesting(records)
        assertTrue(table.rows.all { it.second }, "each record row holds a whole map")
        val first = h2ExplorerDrillForTesting(records, path = listOf(0))
        assertEquals(listOf("a", "b"), first.rows.map { it.first })
    }

    @Test
    fun `a reflected object's properties are enterable only when they hold something`() {
        val holder = mapOf("leaf" to Leaf(1, "x"), "empty" to emptyMap<String, Int>())
        val root = h2ExplorerDrillForTesting(holder)
        val byKey = root.rows.associate { it.first to it.second }

        assertEquals(true, byKey["leaf"], "a two-property data class is worth entering")
        assertEquals(false, byKey["empty"], "an empty map has nothing behind it")

        val leaf = h2ExplorerDrillForTesting(holder, path = listOf(0))
        assertEquals(listOf("a", "b"), leaf.rows.map { it.first })
        assertTrue(leaf.rows.none { it.second }, "an Int and a String are the bottom")
        assertNotNull(leaf.title)
    }
}

package afield.ui.visualization.treemap

import afield.document.graph.Box
import afield.geometry.path.FLine
import afield.graphics.render.FLineIntersection
import afield.graphics.render.caption
import afield.graphics.render.key
import afield.graphics.render.onClick
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.test.*

class TreemapTest {
    private val shown = mutableListOf<String>()
    private fun box() = Box("treemap-${System.nanoTime()}").also { it.output.displayProxy = { _, html -> shown += html.toString() } }

    private fun sample() = treemapTree("root") {
        branch("a") { leaf("a1", 6.0); leaf("a2", 6.0); leaf("a3", 4.0) }
        branch("b") { leaf("b1", 3.0); leaf("b2", 2.0); leaf("b3", 2.0); leaf("b4", 1.0) }
        leaf("c", 6.0)
    }

    private fun noPadding(tiling: TreemapTiling) =
        TreemapLayoutOptions(tiling = tiling, paddingInner = 0.0, paddingOuter = 0.0, minCellSize = 0.0)

    private val tilings = listOf(
        TreemapTiling.Squarify(), TreemapTiling.Resquarify(), TreemapTiling.Binary,
        TreemapTiling.SliceDice, TreemapTiling.Slice, TreemapTiling.Dice,
    )

    @Test fun everyTilingGivesAreasInProportionToValueAndTilesWithoutOverlap() {
        for (tiling in tilings) {
            val root = sample()
            val cells = layoutTreemap(root, 0.0, 0.0, 600.0, 400.0, noPadding(tiling))
            val leaves = cells.filter { it.node.isLeaf }
            assertEquals(root.leaves().count(), leaves.size, "$tiling lays out every leaf")
            for (c in leaves) assertEquals(c.node.value / root.value * 600.0 * 400.0, c.area, 1e-6, "$tiling area of ${c.node.key}")
            assertEquals(600.0 * 400.0, leaves.sumOf { it.area }, 1e-6, "$tiling covers the whole rectangle")
            for (i in leaves.indices) for (j in i + 1 until leaves.size) {
                val p = leaves[i]
                val q = leaves[j]
                val ox = min(p.x1, q.x1) - max(p.x0, q.x0)
                val oy = min(p.y1, q.y1) - max(p.y0, q.y0)
                assertTrue(ox <= 1e-9 || oy <= 1e-9, "$tiling: ${p.node.key} and ${q.node.key} overlap")
            }
            // Children sit inside their parents.
            val byNode = cells.associateBy { it.node }
            for (c in cells) {
                val parent = c.node.parent?.let { byNode[it] } ?: continue
                assertTrue(c.x0 >= parent.x0 - 1e-9 && c.x1 <= parent.x1 + 1e-9 && c.y0 >= parent.y0 - 1e-9 && c.y1 <= parent.y1 + 1e-9, "$tiling: ${c.node.key} escapes its parent")
            }
        }
    }

    @Test fun squarifyMatchesD3OnItsOwnExample() {
        // d3-hierarchy's squarify test: values 6 6 4 3 2 2 1 in a 6x4 rectangle at the default (golden) ratio.
        val root = treemapTree { listOf(6, 6, 4, 3, 2, 2, 1).forEachIndexed { i, v -> leaf("n$i", v.toDouble()) } }
        val cells = layoutTreemap(root, 0.0, 0.0, 6.0, 4.0, noPadding(TreemapTiling.Squarify())).drop(1)
        val expected = listOf(
            doubleArrayOf(0.0, 0.0, 3.0, 2.0), doubleArrayOf(0.0, 2.0, 3.0, 4.0), doubleArrayOf(3.0, 0.0, 4.71, 2.33),
            doubleArrayOf(4.71, 0.0, 6.0, 2.33), doubleArrayOf(3.0, 2.33, 5.4, 3.17), doubleArrayOf(3.0, 3.17, 5.4, 4.0),
            doubleArrayOf(5.4, 2.33, 6.0, 4.0),
        )
        cells.zip(expected).forEach { (c, e) ->
            listOf(c.x0, c.y0, c.x1, c.y1).zip(e.toList()).forEach { (got, want) -> assertEquals(want, got, 0.01, "cell ${c.node.name}") }
        }
    }

    @Test fun squarifyKeepsCellsCloseToSquare() {
        val root = treemapTree { repeat(60) { leaf("n$it", 1.0 + (it * 37 % 11)) } }
        val squarified = worstAspectRatio(layoutTreemap(root, 0.0, 0.0, 800.0, 500.0, noPadding(TreemapTiling.Squarify())).drop(1))
        val sliced = worstAspectRatio(layoutTreemap(root, 0.0, 0.0, 800.0, 500.0, noPadding(TreemapTiling.Slice)).drop(1))
        assertTrue(squarified < 4.0, "squarify's worst cell is $squarified")
        assertTrue(squarified < sliced / 10, "squarify $squarified against slice $sliced")
    }

    @Test fun resquarifyKeepsItsRowsWhenValuesChange() {
        val root = sample()
        val rows = TreemapRows.create()
        val o = noPadding(TreemapTiling.Resquarify()).copy(sort = false)
        val before = layoutTreemap(root, 0.0, 0.0, 600.0, 400.0, o, rows = rows).associate { it.node.key to it }
        root.find("root/b/b4")!!.weight = 3.0
        root.find("root/c")!!.weight = 2.0
        val after = layoutTreemap(root, 0.0, 0.0, 600.0, 400.0, o, rows = rows).associate { it.node.key to it }
        // Same rows: cells that shared a row edge still do, so an animation between the two only stretches cells.
        val inB = before.values.filter { it.node.parent?.key == "root/b" }
        for (p in inB) for (q in inB) {
            val p1 = after.getValue(p.node.key)
            val q1 = after.getValue(q.node.key)
            if (abs(p.y0 - q.y0) < 1e-9) assertEquals(p1.y0, q1.y0, 1e-9, "${p.node.name} and ${q.node.name} stay in one row")
            if (abs(p.x0 - q.x0) < 1e-9) assertEquals(p1.x0, q1.x0, 1e-9, "${p.node.name} and ${q.node.name} stay in one column")
        }
        assertEquals(after.getValue("root/b/b4").area / after.getValue("root/b/b3").area, 1.5, 1e-6)
        // A new child set squarifies afresh rather than reusing rows that no longer fit it.
        root.find("root/b")!!.leaf("b5", 1.0)
        val fresh = layoutTreemap(root, 0.0, 0.0, 600.0, 400.0, o, rows = rows)
        assertNotNull(fresh.firstOrNull { it.node.key == "root/b/b5" })
    }

    @Test fun paddingSeparatesSiblingsAndHeadersMakeRoomForBranchNames() {
        val root = sample()
        val cells = layoutTreemap(root, 0.0, 0.0, 600.0, 400.0, TreemapLayoutOptions(paddingInner = 4.0, paddingOuter = 6.0, paddingTop = 16.0, headerMinSize = 30.0))
        val a = cells.first { it.node.key == "root/a" }
        val kids = cells.filter { it.node.parent === a.node }
        assertTrue(kids.all { it.y0 >= a.y0 + 6.0 + 16.0 - 1e-9 }, "children start below the header")
        assertTrue(kids.all { it.x0 >= a.x0 + 6.0 - 1e-9 && it.x1 <= a.x1 - 6.0 + 1e-9 }, "and inside the outer padding")
        for (p in kids) for (q in kids) if (p !== q) {
            val gapX = max(q.x0 - p.x1, p.x0 - q.x1)
            val gapY = max(q.y0 - p.y1, p.y0 - q.y1)
            assertTrue(max(gapX, gapY) >= 4.0 - 1e-9, "siblings ${p.node.name} and ${q.node.name} are at least paddingInner apart")
        }
    }

    @Test fun levelOfDetailOpensTheLargestCellsFirstWithinTheBudget() {
        // A wide tree: 40 branches of 40 leaves, sized so a few branches are much bigger than the rest.
        val root = treemapTree { repeat(40) { b -> branch("b$b") { repeat(40) { leaf("l$it", (40.0 - b) * (40.0 - b)) } } } }
        val cells = layoutTreemap(root, 0.0, 0.0, 800.0, 500.0, TreemapLayoutOptions(maxCells = 300, minCellSize = 0.0))
        assertTrue(cells.size <= 300, "${cells.size} cells within a budget of 300")
        val opened = cells.filter { it.open && it.depth == 1 }.map { it.node.name }
        assertTrue("b0" in opened, "the biggest branch is opened: $opened")
        assertFalse("b39" in opened, "the smallest is not")
        // Too-small cells are not opened either.
        val coarse = layoutTreemap(root, 0.0, 0.0, 800.0, 500.0, TreemapLayoutOptions(minCellSize = 60.0))
        assertTrue(coarse.filter { it.open }.all { it.w >= 120.0 && it.h >= 120.0 }, "coarse opens only big cells")
        // Zoomed in on the top-left corner of a deeper tree, cells off screen stay closed and those on it open further.
        val deep = treemapTree { repeat(40) { b -> branch("b$b") { repeat(10) { m -> branch("m$m") { repeat(10) { leaf("l$it", (40.0 - b) * (40.0 - b)) } } } } } }
        val coarseDeep = layoutTreemap(deep, 0.0, 0.0, 800.0, 500.0, TreemapLayoutOptions(minCellSize = 60.0))
        val zoomed = layoutTreemap(deep, 0.0, 0.0, 800.0, 500.0, TreemapLayoutOptions(minCellSize = 60.0), pixelsPerUnit = 8.0,
            visible = TreemapCell(deep, 0.0, 0.0, 100.0, 60.0, 0, false))
        assertTrue(zoomed.filter { it.open && it.node !== deep }.all { it.x0 <= 100.0 && it.y0 <= 60.0 }, "only visible cells open")
        fun onScreen(cells: List<TreemapCell>) = cells.count { it.node.isLeaf && it.x0 < 100.0 && it.y0 < 60.0 }
        assertTrue(onScreen(zoomed) > onScreen(coarseDeep), "zoomed shows ${onScreen(zoomed)} leaves where coarse shows ${onScreen(coarseDeep)}")
    }

    @Test fun keysAreStablePathsAndValuesSum() {
        val root = sample()
        assertEquals(30.0, root.sum())
        assertEquals("root/a/a2", root.find("root/a/a2")!!.key)
        assertEquals(listOf("root", "b", "b3"), root.find("root/b/b3")!!.path.map { it.name })
        val moved = root.find("root/b/b3")!!
        root.find("root/a")!!.add(moved)
        assertEquals("root/a/b3", moved.key)
        assertEquals(16.0 + 2.0, root.find("root/a")!!.also { root.sum() }.value)
        assertFailsWith<IllegalArgumentException> { TreemapNode("bad", -1.0) }
    }

    @Test fun treesFromMapsObjectsAndFiles() {
        val fromMap = treemapTreeOfMap(mapOf("x" to 3, "y" to mapOf("p" to 1.5, "q" to listOf(1, 2))))
        assertEquals(7.5, fromMap.sum())
        assertEquals("root/y/q/1", fromMap.find("root/y/q/1")!!.key)

        data class Dir(val name: String, val kids: List<Dir> = emptyList(), val bytes: Double = 0.0)
        val fromObjects = treemapTreeOf(Dir("top", listOf(Dir("a", bytes = 4.0), Dir("b", listOf(Dir("c", bytes = 2.0))))),
            children = { it.kids }, weight = { it.bytes }, name = { it.name })
        assertEquals(6.0, fromObjects.sum())
        assertEquals("c", (fromObjects.find("top/b/c")!!.data as Dir).name)

        val dir = kotlin.io.path.createTempDirectory("treemap").toFile()
        try {
            dir.resolve("one.txt").writeText("12345")
            dir.resolve("sub").mkdirs()
            dir.resolve("sub/two.txt").writeText("123")
            dir.resolve(".hidden").writeText("ignored")
            val files = treemapTreeOfFiles(dir)
            assertEquals(8.0, files.sum())
            assertNotNull(files.find("${dir.name}/sub/two.txt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theDrawingIsKeyedCaptionedAndCheapToClick() {
        val b = box()
        val map = b.treemap(sample(), w = 600.0, h = 400.0)
        val svg = shown.last()
        assertTrue("data-afield-transition=\"0.45\"" in svg, "the display asks for a morph")
        assertTrue("data-fline-key=\"root/a/a1\"" in svg)
        assertTrue(Regex("<text[^>]*>a1").containsMatchIn(svg), "a large cell is labelled")
        assertFalse("data-fline-elements" in svg, "cells skip the per-segment hit records")
        assertTrue("data-afield-view=" in svg, "the treemap listens for pans and zooms")
        // Each line is keyed, so a redraw replaces rather than adds callbacks.
        val lines = map.toFLines()
        assertEquals(lines.size, lines.mapNotNull { it.key }.toSet().size, "keys are unique")
    }

    @Test fun clicksSelectAndDoubleClicksZoom() {
        val b = box()
        var picked: List<String> = emptyList()
        val map = b.treemap(sample(), channel = "t", w = 600.0, h = 400.0) { onSelect = { s -> picked = s.map { it.key } } }
        fun click(key: String, shift: Boolean = false, meta: Boolean = false, count: Int = 1, alt: Boolean = false) {
            val line = map.toFLines().first { it.key == key }
            line.onClick!!(FLineIntersection(-1, vec(0.0, 0.0, 0.0), key = key, shift = shift, meta = meta, alt = alt, clickCount = count))
        }
        click("root/a/a1")
        assertEquals(listOf("root/a/a1"), picked)
        click("root/b/b2", shift = true)
        assertEquals(listOf("root/a/a1", "root/b/b2"), picked)
        click("root/a/a1", meta = true)
        assertEquals(listOf("root/b/b2"), picked)
        assertTrue("treemap:selected:root/b/b2" in shown.last(), "the selection is outlined")

        click("root/b/b2", count = 2)
        assertEquals("root/b", map.focus.key, "a double click zooms one level toward the cell")
        assertTrue(map.cells.none { it.node.key.startsWith("root/a") }, "outside the focus is gone")
        assertTrue(map.cells.first().w > 590.0, "the focus fills the treemap")
        click("root/b/b2", alt = true)
        assertEquals("root", map.focus.key, "Alt-click zooms out")

        // A rerun keeps focus and selection, because they live by key, not by object.
        map.zoomTo("root/a")
        val again = b.treemap(sample(), channel = "t", w = 600.0, h = 400.0)
        assertEquals("root/a", again.focus.key)
        assertEquals(listOf("root/b/b2"), again.selection.map { it.key })
    }

    @Test fun anEditedTreeRedrawsAsAnEditOfTheOldOne() {
        val b = box()
        val tree = sample()
        val map = b.treemap(tree, channel = "e", w = 600.0, h = 400.0) { tiling = TreemapTiling.Resquarify() }
        val before = map.cells.first { it.node.key == "root/c" }.area
        map.update { tree.find("root/c")!!.weight = 12.0 }
        val after = map.cells.first { it.node.key == "root/c" }.area
        assertTrue(after > before * 1.4, "c grew from $before to $after")
        map.update { tree.find("root/b")!!.leaf("b5", 5.0) }
        assertTrue("data-fline-key=\"root/b/b5\"" in shown.last())
    }

    @Test fun aTreeDisplaysItselfAsAValue() {
        val html = sample().toHTML()
        assertTrue("afield-lines-output" in html && "data-fline-key=\"root/a/a1\"" in html)
        assertFalse("data-fline-callback-id" in html, "a value has no box to call back to")
    }

    @Test fun compactNumbersStayShort() {
        assertEquals("1.23k", compactNumber(1234.0))
        assertEquals("5.6M", compactNumber(5_600_000.0))
        assertEquals("42", compactNumber(42.0))
        assertEquals("0", compactNumber(0.0))
    }

    private fun min(a: Double, b: Double) = kotlin.math.min(a, b)
    private fun max(a: Double, b: Double) = kotlin.math.max(a, b)

    @Suppress("unused")
    private fun FLine.captionText() = caption?.text
}

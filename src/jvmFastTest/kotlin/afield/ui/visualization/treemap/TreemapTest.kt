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

    /** The frames a treemap's viewer is sent, decoded: description and arrays. */
    private class Sent(val message: TreemapFrameMessage, val arrays: Map<String, java.nio.ByteBuffer>) {
        fun floats(name: String) = arrays.getValue(name).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        fun ints(name: String) = arrays.getValue(name).asIntBuffer().let { b -> IntArray(b.remaining()).also { b.get(it) } }
        fun bytes(name: String) = arrays.getValue(name).let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
        fun names(): List<String> {
            val offsets = ints("nameOffsets")
            val text = bytes("names")
            return (0 until message.count).map { String(text, offsets[it], offsets[it + 1] - offsets[it], Charsets.UTF_8) }
        }
        fun hash(name: String) = message.buffers.entries[message.arrays.indexOf(name)].hash
    }

    private fun viewOf(b: Box, channel: String = "treemap") = treemapViewFor(treemapViewerUid(b, channel))

    private fun decode(view: TreemapView, description: ByteArray): Sent {
        val message = treemapJson.decodeFromString(TreemapFrameMessage.serializer(), description.decodeToString())
        val all = java.nio.ByteBuffer.wrap(view.buffers(message.generation)!!.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val arrays = message.arrays.withIndex().associate { (i, name) ->
            val e = message.buffers.entries[i]
            val width = when (e.type) { "Float32Array", "Uint32Array", "Int32Array" -> 4; else -> 1 }
            name to all.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN).position(e.offset.toInt()).limit(e.offset.toInt() + e.count * width)
                .slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }
        return Sent(message, arrays)
    }

    private fun recording(b: Box, channel: String = "treemap"): MutableList<ByteArray> {
        val sent = mutableListOf<ByteArray>()
        viewOf(b, channel).onBroadcast = { sent += it }
        return sent
    }

    @Test fun aFrameCarriesEveryCellAsTypedArrays() {
        val b = box()
        val sent = recording(b)
        val map = b.treemap(sample(), w = 600.0, h = 400.0)
        assertTrue("/treemap?uid=" in shown.last(), "the box shows the viewer")
        val frame = decode(viewOf(b), sent.last())
        assertEquals(map.cells.size, frame.message.count)
        assertEquals(FRAME_ARRAYS, frame.message.arrays)
        val rects = frame.floats("rects")
        map.cells.forEachIndexed { i, c ->
            assertEquals(c.x0, rects[4 * i].toDouble(), 1e-3); assertEquals(c.y1, rects[4 * i + 3].toDouble(), 1e-3)
        }
        val names = frame.names()
        assertEquals("a1\t6", names[map.cells.indexOfFirst { it.node.key == "root/a/a1" }], "name and formatted value, tab apart")
        val parents = frame.ints("parents")
        map.cells.forEachIndexed { i, c -> assertEquals(map.cells.indexOfFirst { it.node === c.node.parent }, parents[i], "parent of ${c.node.key}") }
        val flags = frame.bytes("flags")
        assertTrue(flags[0].toInt() and CELL_OPEN != 0, "the focus is open")
        assertTrue(flags[map.cells.indexOfFirst { it.node.key == "root/c" }].toInt() and CELL_LEAF != 0)
        assertEquals(listOf("root"), frame.message.crumbs.map { it.name })
    }

    @Test fun aSelectionSendsOnlyTheFlagsAndIdsAreStable() {
        val b = box()
        val sent = recording(b, "s")
        val map = b.treemap(sample(), channel = "s", w = 600.0, h = 400.0)
        val view = viewOf(b, "s")
        val before = decode(view, sent.last())
        map.select(map.root.find("root/b/b2")!!)
        val after = decode(view, sent.last())
        val changed = FRAME_ARRAYS.filter { before.hash(it) != after.hash(it) }
        assertEquals(listOf("flags"), changed, "a selection changes the flags array alone, so the viewer fetches only that")
        val i = map.cells.indexOfFirst { it.node.key == "root/b/b2" }
        assertTrue(after.bytes("flags")[i].toInt() and CELL_SELECTED != 0)
        // A node keeps its id when the tree is rebuilt and the box runs again, which is what the viewer morphs by.
        val idBefore = after.ints("ids")[i]
        val again = b.treemap(sample(), channel = "s", w = 600.0, h = 400.0)
        val rerun = decode(view, sent.last())
        assertEquals(idBefore, rerun.ints("ids")[again.cells.indexOfFirst { it.node.key == "root/b/b2" }])
        assertEquals(rerun.ints("ids").size, rerun.ints("ids").toSet().size, "ids are unique within a frame")
    }

    @Test fun viewerMessagesReachTheNodesTheyName() {
        val b = box()
        val sent = recording(b, "v")
        var picked: List<String> = emptyList()
        val map = b.treemap(sample(), channel = "v", w = 600.0, h = 400.0) {
            onSelect = { s -> picked = s.map { it.key } }
            infoChannel = "about"
            colorings = linkedMapOf("branch" to TreemapColors.byBranch(), "depth" to TreemapColors.byDepth())
        }
        val view = viewOf(b, "v")
        fun gen() = decode(view, sent.last()).message.generation
        fun say(type: String, vararg fields: Pair<String, Any>) = map.receiveFromViewer(view, type, kotlinx.serialization.json.buildJsonObject {
            fields.forEach { (k, v) -> when (v) { is Number -> put(k, kotlinx.serialization.json.JsonPrimitive(v)); is Boolean -> put(k, kotlinx.serialization.json.JsonPrimitive(v)); else -> put(k, kotlinx.serialization.json.JsonPrimitive(v.toString())) } }
        })
        fun index(key: String) = map.cells.indexOfFirst { it.node.key == key }
        say("click", "generation" to gen(), "index" to index("root/a/a2"))
        assertEquals(listOf("root/a/a2"), picked)
        say("click", "generation" to gen(), "index" to index("root/c"), "shift" to true)
        assertEquals(listOf("root/a/a2", "root/c"), picked)
        say("click", "generation" to gen(), "index" to -1)
        assertEquals(emptyList(), picked, "a click on the background clears the selection")
        say("hover", "generation" to gen(), "index" to index("root/b"))
        assertEquals("root/b", map.hovered?.key)
        assertTrue(shown.any { "root" in it && "children" in it }, "the hovered branch is described in its channel")
        say("click", "generation" to gen(), "index" to index("root/b/b3"), "clickCount" to 2)
        assertEquals("root/b", map.focus.key)
        val zoomed = decode(view, sent.last())
        assertTrue(zoomed.message.refit, "a zoom tells the viewer to refit its camera")
        assertEquals(listOf("root", "b"), zoomed.message.crumbs.map { it.name })
        say("crumb", "id" to zoomed.message.crumbs[0].id)
        assertEquals("root", map.focus.key, "a breadcrumb zooms out to it")
        val colouredBefore = decode(view, sent.last())
        say("coloring", "name" to "depth")
        val colouredAfter = decode(view, sent.last())
        assertEquals("depth", colouredAfter.message.coloring)
        assertEquals(listOf("colors"), FRAME_ARRAYS.filter { colouredBefore.hash(it) != colouredAfter.hash(it) }, "a new colouring sends only the colours")
        // A click naming a superseded generation, or nothing, is ignored rather than misread.
        say("click", "generation" to "no-such-generation", "index" to 3)
        assertEquals("root", map.focus.key)
    }

    @Test fun legendsGiveSharesOfTheFocus() {
        val b = box()
        val sent = recording(b, "l")
        val map = b.treemap(sample(), channel = "l", w = 600.0, h = 400.0) {
            colors = TreemapColors.byCategory("parity") { n -> if (n.name.last().digitToIntOrNull()?.rem(2) == 0) "even" else "odd" }
        }
        val legend = decode(viewOf(b, "l"), sent.last()).message.legend
        assertEquals(1.0, legend.sumOf { it.share ?: 0.0 }, 1e-9)
        assertEquals(setOf("even", "odd"), legend.map { it.label }.toSet())
        map.colors = TreemapColors.byBranch()
        map.zoomTo("root/b")
        val zoomed = decode(viewOf(b, "l"), sent.last()).message.legend
        assertEquals(listOf("b"), zoomed.map { it.label }, "zoomed into b, the branch legend lists only b")
        assertEquals(1.0, zoomed.single().share!!, 1e-9)
    }

    @Test fun aHundredThousandCellsPackSmall() {
        val tree = treemapTree("lib") { repeat(40) { a -> branch("a$a") { repeat(25) { c -> branch("d$c") { repeat(100) { leaf("m$it", 1.0 + (a + c + it) % 17) } } } } } }
        val b = box()
        val sent = recording(b, "big")
        val map = b.treemap(tree, channel = "big", w = 1400.0, h = 900.0)
        val frame = decode(viewOf(b, "big"), sent.last())
        assertTrue(frame.message.count > 100_000, "${frame.message.count} cells")
        val perCell = frame.message.buffers.bytes.toDouble() / frame.message.count
        assertTrue(perCell < 60, "$perCell bytes a cell")
        assertTrue(sent.last().size < 8_000, "the description stays small: ${sent.last().size} bytes")
        assertEquals(map.cells.size, frame.message.count)
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
        assertTrue(map.toFLines().any { it.key == "treemap:selected:root/b/b2" }, "the selection is outlined in the FLine drawing")

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
        assertTrue(map.cells.any { it.node.key == "root/b/b5" }, "the new leaf is laid out and sent")
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
        assertEquals("520", compactNumber(520.0), "whole numbers keep their zeros")
        assertEquals("600", compactNumber(600.0))
        assertEquals("1k", compactNumber(1000.0))
        assertEquals("1.5", compactNumber(1.5))
        assertEquals("0", compactNumber(0.0))
    }

    private fun min(a: Double, b: Double) = kotlin.math.min(a, b)
    private fun max(a: Double, b: Double) = kotlin.math.max(a, b)

    @Suppress("unused")
    private fun FLine.captionText() = caption?.text
}

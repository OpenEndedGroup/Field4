package afield.runtime.macros

import afield.app.server.SessionInformation
import afield.app.server.thisSessionInfo
import afield.document.graph.Box
import afield.document.graph.needsResync
import afield.document.graph.preamble
import afield.graphics.shader.normalizePreambleText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [gatherPreambleUpwards] keeps imports in the order it meets them, so writing its result back into
 * a box's preamble (as `checkPreambleAndExecute` does on every launch) changes nothing.
 *
 * It used to prepend each import, which reversed a box's own imports; written back, they flipped on
 * every launch, and the K2 engine's K1 completion delegate, keyed on that text, was rebuilt on every
 * edit of a launched box (and, before it was disposed properly, leaked ~60 MB each time).
 */
class GatheredImportOrderTest {

    private val boxes = mutableListOf<Box>()

    private fun session(): SessionInformation {
        val root = Box("import-order-root")
        val s = SessionInformation(filename = "<import-order>", documentName = "import-order", root = root, ephemeral = true)
        root.inside = s
        s.loading = false
        thisSessionInfo.set(s)
        boxes += root
        return s
    }

    private fun boxIn(s: SessionInformation, id: String) =
        Box(id).also { it.inside = s; s.boxes[id] = it; boxes += it }

    @AfterTest
    fun cleanUp() {
        val mine = boxes.toSet()
        needsResync.removeAll { (box, _) -> box in mine }
        boxes.clear()
        thisSessionInfo.remove()
    }

    private val ownImports = listOf("import afield.graphics.scene.*", "import afield.graphics.shader.*", "import arrow.core.left")

    @Test
    fun aBoxsOwnImportsComeBackInTheirOrder() {
        val box = boxIn(session(), "box")
        box.preamble = (ownImports + "val local = 1").joinToString("\n")

        val gathered = gatherPreambleUpwards(box).lines().filter { it.startsWith("import") }
        assertEquals(ownImports, gathered)
    }

    @Test
    fun writingTheGatheredPreambleBackIsAFixedPoint() {
        val s = session()
        val parent = boxIn(s, "parent").also { it.preamble = "import afield.document.context.*\nimport afield.graphics.render.*" }
        val child = boxIn(s, "child")
        parent.connect(child)
        child.preamble = (ownImports + "val local = 1").joinToString("\n")

        val once = normalizePreambleText(gatherPreambleUpwards(child))
        child.preamble = once
        val twice = normalizePreambleText(gatherPreambleUpwards(child))
        assertEquals(once, twice)
    }
}

package afield.document.properties

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.graph.METAROOT_ID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * N1023-2: a box with one parent, under a document root under metaroot, was told nothing up "any of
 * its 3 parent boxes" had the property. The root and metaroot are above every box and are not what a
 * person counts as parents; the walk still searches them, and the count leaves them out.
 */
class MissingInheritedPropertyCountTest {

    private fun inOneDocument(vararg boxes: Box) {
        val root = boxes.first { it.id == "root" }
        val info = SessionInformation(filename = "<count>", documentName = "count", root = root, ephemeral = true)
        info.loading = false
        boxes.forEach { it.inside = info; info.boxes[it.id] = it }
    }

    @Test
    fun rootAndMetarootAreNotCountedAsParentBoxes() {
        val metaroot = Box(METAROOT_ID)
        val root = Box("root")
        val studio = Box("count-studio").apply { name = "Studio" }
        val flower = Box("count-flower").apply { name = "Flower" }
        inOneDocument(metaroot, root, studio, flower)
        metaroot.connect(root)
        root.connect(studio)
        studio.connect(flower)

        val (found, count) = inheritedPropertyWalk(flower, "petalsThatNobodyWrote")
        assertEquals(null, found)
        assertEquals(1, count, "only Studio is a parent box")
        val message = missingInheritedPropertyMessage(flower, "petalsThatNobodyWrote", count)
        assertTrue("the 1 box above it" in message && "document root was searched" in message, message)
    }

    @Test
    fun aBoxDirectlyUnderTheRootHasNoParentBoxes() {
        val root = Box("root")
        val lone = Box("count-lone").apply { name = "Lone" }
        inOneDocument(root, lone)
        root.connect(lone)
        val (_, count) = inheritedPropertyWalk(lone, "petalsThatNobodyWrote")
        assertEquals(0, count)
        assertTrue("no boxes above it" in missingInheritedPropertyMessage(lone, "x", count))
    }
}

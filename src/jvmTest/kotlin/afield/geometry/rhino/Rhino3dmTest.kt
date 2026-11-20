package afield.geometry.rhino

import afield.geometry.path.FLine
import afield.graphics.render.filled
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The .3dm reader against a file the wrapper writes itself ([Rhino3dm.writeSample]), so no Rhino
 * file is committed and each kind is checked against values set in one place, the C++ fixture.
 */
class Rhino3dmTest {

    /** Optional like TetGen: without the wrapper the tests say so and pass. */
    private fun available(): Boolean {
        if (!Rhino3dm.isAvailable) {
            println("SKIPPED .3dm tests: ${Rhino3dm.describe()}")
            return false
        }
        return true
    }

    private fun sample(): RhinoModel {
        val dir = Files.createTempDirectory("rhino3dm-test").toFile()
        try {
            return Rhino3dm.load(Rhino3dm.writeSample(File(dir, "sample.3dm")))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun RhinoModel.named(name: String): RhinoObject = objects.single { it.name == name }

    @Test fun missingLibraryIsReportedNotThrownAtClassLoad() {
        // Touching the object must not link anything; that is what makes the wrapper optional.
        assertTrue(OpenNurbsLibrary.fileName.contains("field_opennurbs_jvm"))
        assertTrue(OpenNurbsLibrary.searchedPaths.isNotEmpty())
        assertTrue(OpenNurbsLibrary.HOW_TO_BUILD.contains("buildOpenNurbsNative"))
    }

    @Test fun layersKeepTheirTreeColourAndVisibility() {
        if (!available()) return
        val model = sample()
        assertEquals("millimeters", model.unitName)
        assertEquals(0.001, model.metersPerUnit, 1e-12)
        assertTrue(model.archiveVersion >= 70, "archive version ${model.archiveVersion}")
        val glass = model.layer("Hull::Glass")
        assertEquals("Glass", glass.name)
        assertEquals(model.layer("Hull"), glass.parent)
        assertEquals(listOf("Hull", "Glass"), glass.pathParts)
        assertFalse(glass.isVisible)
        assertTrue(model.layer("Hull").isVisible)
        assertEquals(200 / 255f, model.layer("Hull").color.x, 1e-6f)
        assertFailsWith<NoSuchElementException> { model.layer("Keel") }
    }

    @Test fun meshesSplitQuadsAndBrepsUseTheirRenderMeshes() {
        if (!available()) return
        val model = sample()
        val plate = model.named("plate") as RhinoMesh
        assertEquals(5, plate.mesh.vertexCount)
        assertEquals(3, plate.mesh.triangleCount, "a quad and a triangle")
        assertNull(plate.edges, "a plain mesh has no Brep edges")

        val box = model.named("shaded box") as RhinoMesh
        assertEquals("ON_Brep", box.type)
        assertEquals(12, box.mesh.triangleCount, "six cached quads")
        assertEquals(12, box.edges!!.nodes.count { it is FLine.MoveTo }, "a box has twelve edges")
        assertNull(box.note)
    }

    @Test fun aBrepWithoutARenderMeshIsSkippedButKeepsItsWireframe() {
        if (!available()) return
        val bare = sample().named("bare box") as RhinoSkipped
        assertEquals("ON_Brep", bare.type)
        assertTrue("render mesh" in bare.reason, bare.reason)
        assertNotNull(bare.edges)
        assertFalse(bare.isVisible, "it is on the hidden Glass layer")
    }

    @Test fun curvesAreExactWhereTheyCanBeAndCloseWhereTheyShould() {
        if (!available()) return
        val model = sample()
        val square = model.named("square") as RhinoCurve
        assertTrue(square.closed)
        assertFalse(square.approximated)
        assertEquals(5, square.line.nodes.size)
        assertEquals(1f, square.color.x, 1e-6f, "colour by object (orange), not by layer (green)")

        val wiggle = model.named("wiggle") as RhinoCurve
        assertFalse(wiggle.approximated, "a polynomial cubic is converted exactly")
        // Six control points of a cubic make three spans (knots 0 0 0 1 2 3 3 3).
        assertEquals(1 + 3, wiggle.line.nodes.size, "a move and one cubic per non-empty span")

        val circle = model.named("circle") as RhinoCurve
        assertTrue(circle.closed)
        assertTrue(circle.approximated, "a rational arc becomes cubics")
        assertWithinRadius(circle.line, 40.0, 40.0, 5.0, 5.0 * 1e-4)

        val slot = model.named("slot") as RhinoCurve
        assertEquals(1, slot.line.nodes.count { it is FLine.MoveTo }, "a polycurve is one connected path")
    }

    @Test fun textComesWithItsPlaneAndHeight() {
        if (!available()) return
        val text = sample().named("greeting") as RhinoText
        assertEquals("Hello Field", text.text)
        assertEquals(2.5, text.height, 1e-9)
        assertEquals(60f, text.origin.y, 1e-6f)
        assertEquals(1f, text.xAxis.x, 1e-6f)
        val glyphs = text.toFLines()
        assertEquals("HelloField".length, glyphs.size, "one outline per inked glyph")
        val ys = glyphs.flatMap { g -> g.nodes.map { it.to.y.toDouble() } }
        // The H's top sits about one cap height above the baseline, wherever the baseline went.
        val span = ys.max() - ys.min()
        assertTrue(span in 2.5..4.0, "glyph height span $span for a 2.5 cap height with descenders")
    }

    @Test fun pointsAndHatches() {
        if (!available()) return
        val model = sample()
        val marker = model.named("marker") as RhinoPoints
        assertEquals(listOf(1f, 2f, 3f), marker.points.single().let { listOf(it.x, it.y, it.z) })
        val fill = model.named("fill") as RhinoCurve
        assertTrue(fill.isHatch)
        assertTrue(fill.line.filled)
    }

    @Test fun blockInstancesAreFlattenedAndMoved() {
        if (!available()) return
        val model = sample()
        val skins = model.meshes.filter { it.block == "Fin" }
        assertEquals(2, skins.size, "two instances of a block holding one mesh")
        val xs = skins.map { s -> s.mesh.positions.filterIndexed { i, _ -> i % 3 == 0 }.min() }.sorted()
        assertEquals(listOf(100f, 120f), xs)
        assertEquals(2, model.curves.count { it.block == "Fin" })
        assertTrue(model.objects.none { it.name == "fin skin" && it.block == null }, "definition members are not drawn on their own")
    }

    @Test fun summaryNamesWhatWasSkipped() {
        if (!available()) return
        val summary = sample().toString()
        assertTrue("sample.3dm" in summary, summary)
        assertTrue("skipped 1 × ON_Brep" in summary, summary)
    }

    @Test fun aFileThatIsNot3dmIsRefusedWithAReason() {
        if (!available()) return
        val file = Files.createTempFile("not-rhino", ".3dm").toFile()
        try {
            file.writeText("solid nothing\nendsolid\n")
            val e = assertFailsWith<RhinoException> { Rhino3dm.load(file) }
            assertTrue(file.name in e.message!!, e.message)
        } finally {
            file.delete()
        }
    }

    private fun assertWithinRadius(line: FLine, cx: Double, cy: Double, r: Double, tolerance: Double) {
        // Sample inside each cubic, not just its ends, which lie on the circle by construction.
        var cubics = 0
        for (i in 1 until line.nodes.size) {
            val node = line.nodes[i] as? FLine.CubicTo ?: continue
            cubics++
            val p0 = line.endPointOf(i - 1)
            for (k in 1..9) {
                val u = k / 10.0
                val s = 1 - u
                fun axis(a: Float, b: Float, c: Float, d: Float) = s * s * s * a + 3 * s * s * u * b + 3 * s * u * u * c + u * u * u * d
                val x = axis(p0.x, node.c1.x, node.c2.x, node.to.x)
                val y = axis(p0.y, node.c1.y, node.c2.y, node.to.y)
                val off = abs(hypot(x - cx, y - cy) - r)
                assertTrue(off <= tolerance, "cubic $i at $u is $off off the circle")
            }
        }
        assertTrue(cubics >= 4, "a circle as at least four cubics, got $cubics")
    }
}

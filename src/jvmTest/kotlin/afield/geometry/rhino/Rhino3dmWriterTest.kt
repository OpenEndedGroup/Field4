package afield.geometry.rhino

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.math.linalg.vec
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [Rhino3dm.save] checked by reading every file back with [Rhino3dm.load]. */
class Rhino3dmWriterTest {

    /** Reports the test SKIPPED, not passed, when the native library is missing. */
    private fun assumeAvailable() =
        org.junit.Assume.assumeTrue("${javaClass.simpleName}: native library not available", available())

    private fun available(): Boolean {
        if (!Rhino3dm.isAvailable) {
            println("SKIPPED .3dm writer tests: ${Rhino3dm.describe()}")
            return false
        }
        return true
    }

    private fun <T> inTemp(body: (File) -> T): T {
        val dir = Files.createTempDirectory("rhino3dm-writer-test").toFile()
        try {
            return body(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private val red = vec(0.8, 0.2, 0.2, 1.0)
    private val blue = vec(0.2, 0.4, 1.0, 1.0)
    private val orange = vec(1.0, 0.5, 0.0, 1.0)

    private fun mixed() = FLine().apply {
        moveTo(0.0, 0.0, 0.0)
        lineTo(10.0, 0.0, 0.0)
        lineTo(10.0, 5.0, 0.5)
        cubicTo(vec(8.0, 9.0, 1.0), vec(3.0, 9.25, 1.5), vec(1.0, 6.125, 0.0))
        close()
    }

    private fun zigzag() = FLine().apply {
        moveTo(0.0, 20.0, 0.0)
        for (i in 1..6) lineTo(i * 3.0, 20.0 + (i % 2) * 2.0, 0.0)
        moveTo(0.0, 30.0, 0.0)
        cubicTo(vec(1.0, 33.0, 0.0), vec(2.0, 27.0, 0.0), vec(3.0, 30.0, 0.0))
    }

    private fun plate() = TriMesh(
        floatArrayOf(0f, 0f, 0f, 4f, 0f, 0f, 4f, 3f, 0f, 0f, 3f, 0f),
        intArrayOf(0, 1, 2, 0, 2, 3),
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f),
    )

    private fun write(file: File) = Rhino3dm.save(file, units = "inches") {
        layer("Hull", red)
        layer("Hull::Glass", blue, visible = false)
        mesh(plate(), layer = "Hull", name = "plate")
        curve(mixed(), layer = "Hull::Glass", name = "mixed", color = orange)
        assertEquals(2, curve(zigzag(), layer = "Notes", name = "zigzag"))
        nurbs(
            3,
            listOf(vec(0.0, 40.0, 0.0), vec(5.0, 50.0, 0.0), vec(10.0, 40.0, 0.0), vec(15.0, 50.0, 0.0), vec(20.0, 40.0, 0.0)),
            listOf(0.0, 0.0, 0.0, 1.0, 2.0, 2.0, 2.0),
            layer = "Notes",
            name = "wiggle",
        )
        points(listOf(vec(1.0, 2.0, 3.0), vec(4.0, 5.0, 6.0), vec(7.0, 8.0, 9.0)), layer = "Notes", name = "cloud")
        point(vec(-1.0, -2.0, -3.0), layer = "Notes", name = "marker")
        text("Hello Rhino", vec(0.0, 60.0, 0.0), height = 2.5, layer = "Notes", name = "greeting")
    }

    private fun assertSameNodes(expected: FLine, actual: FLine) {
        assertEquals(expected.nodes.map { it.javaClass }, actual.nodes.map { it.javaClass }, "node kinds")
        expected.nodes.zip(actual.nodes).forEach { (e, a) ->
            assertEquals(e.storage.size, a.storage.size)
            e.storage.zip(a.storage).forEach { (p, q) ->
                assertEquals(0.0, p.distance(q).toDouble(), 1e-12, "$e vs $a")
            }
        }
    }

    @Test fun layersComeBackWithTheirTreeColourAndVisibility() {
        assumeAvailable()
        val model = inTemp { Rhino3dm.load(write(File(it, "out.3dm"))) }
        assertEquals("", model.log.trim(), "openNURBS log")
        assertEquals("inches", model.unitName)
        assertEquals(0.0254, model.metersPerUnit, 1e-12)
        assertEquals(listOf("Hull", "Hull::Glass", "Notes"), model.layers.map { it.path })
        val glass = model.layer("Hull::Glass")
        assertEquals(model.layer("Hull"), glass.parent)
        assertFalse(glass.isVisible)
        assertEquals(RhinoWriter.argb(red), RhinoWriter.argb(model.layer("Hull").color))
        assertEquals(RhinoWriter.argb(blue), RhinoWriter.argb(glass.color))
    }

    @Test fun everyObjectComesBack() {
        assumeAvailable()
        val model = inTemp { Rhino3dm.load(write(File(it, "out.3dm"))) }
        assertEquals(1, model.meshes.size)
        assertEquals(4, model.curves.size)  // mixed, two zigzag subpaths, wiggle
        assertEquals(2, model.points.size)
        assertEquals(1, model.texts.size)
        assertTrue(model.skipped.isEmpty(), "${model.skipped}")

        val plate = model.meshes.single()
        assertEquals("plate", plate.name)
        assertEquals("Hull", plate.layer?.path)
        assertTrue(plate.mesh.positions.contentEquals(plate().positions))
        assertEquals(2, plate.mesh.triangleCount)
        assertEquals(RhinoWriter.argb(red), RhinoWriter.argb(plate.color), "by-layer colour")

        val text = model.texts.single()
        assertEquals("Hello Rhino", text.text)
        assertEquals(2.5, text.height, 1e-9)

        val cloud = model.points.single { it.name == "cloud" }
        assertEquals(listOf(vec(1.0, 2.0, 3.0), vec(4.0, 5.0, 6.0), vec(7.0, 8.0, 9.0)), cloud.points)
        assertEquals(listOf(vec(-1.0, -2.0, -3.0)), model.points.single { it.name == "marker" }.points)
    }

    @Test fun curvesComeBackWithIdenticalControlPoints() {
        assumeAvailable()
        val model = inTemp { Rhino3dm.load(write(File(it, "out.3dm"))) }

        val mixed = model.curves.single { it.name == "mixed" }
        assertEquals(RhinoWriter.argb(orange), RhinoWriter.argb(mixed.color), "object colour")
        assertTrue(mixed.closed)
        assertFalse(mixed.approximated)
        // close() comes back as the line it draws.
        val expected = mixed().apply { nodes.removeLast(); lineTo(0.0, 0.0, 0.0) }
        assertSameNodes(expected, mixed.line)

        val zig = model.curves.filter { it.name == "zigzag" }
        assertEquals(2, zig.size)
        val parts = zigzag()
        assertSameNodes(FLine().apply { nodes.addAll(parts.nodes.take(7)) }, zig[0].line)
        assertSameNodes(FLine().apply { nodes.addAll(parts.nodes.drop(7)) }, zig[1].line)

        // Two cubic spans; the first starts at the first control point and the last ends at the last.
        val wiggle = model.curves.single { it.name == "wiggle" }.line
        assertEquals(3, wiggle.nodes.size)
        assertEquals(vec(0.0, 40.0, 0.0), wiggle.nodes.first().to)
        assertEquals(vec(20.0, 40.0, 0.0), wiggle.nodes.last().to)
    }

    @Test fun aReadModelWritesBackIntact() {
        assumeAvailable()
        inTemp { dir ->
            val sample = Rhino3dm.load(Rhino3dm.writeSample(File(dir, "sample.3dm")))
            val again = Rhino3dm.load(sample.save(File(dir, "again.3dm")))
            assertEquals("", again.log.trim())
            assertEquals(sample.unitName, again.unitName)
            assertEquals(sample.layers.map { it.path to it.ownVisibility }, again.layers.map { it.path to it.ownVisibility })
            assertEquals(sample.meshes.size, again.meshes.size)
            assertEquals(sample.curves.size, again.curves.size)
            assertEquals(sample.texts.map { it.text }, again.texts.map { it.text })
            assertEquals(sample.points.size, again.points.size)
            assertTrue(again.skipped.isEmpty())
            for ((a, b) in sample.curves.zip(again.curves)) {
                assertEquals(a.name, b.name)
                assertEquals(RhinoWriter.argb(a.color), RhinoWriter.argb(b.color), a.name)
                assertSameNodes(a.line, b.line)
            }
            for ((a, b) in sample.meshes.zip(again.meshes)) assertTrue(a.mesh.positions.contentEquals(b.mesh.positions), a.name)
        }
    }

    @Test fun badInputIsRefused() {
        assumeAvailable()
        inTemp { dir ->
            assertFailsWith<IllegalArgumentException> { Rhino3dm.save(File(dir, "x.3dm"), units = "furlongs") {} }
            assertFailsWith<IllegalArgumentException> {
                Rhino3dm.save(File(dir, "x.3dm")) { nurbs(3, listOf(vec(0.0, 0.0, 0.0), vec(1.0, 0.0, 0.0)), listOf(0.0, 1.0)) }
            }
        }
    }
}

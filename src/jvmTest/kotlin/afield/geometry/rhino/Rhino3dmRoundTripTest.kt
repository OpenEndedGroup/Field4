package afield.geometry.rhino

import afield.geometry.mesh.TriMesh
import afield.geometry.path.FLine
import afield.math.linalg.vec
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One scene of every kind [RhinoWriter] writes, saved with [Rhino3dm.save], read back with
 * [Rhino3dm.load] and measured: what survives exactly, what survives to the tessellation tolerance,
 * and what changes kind on the way.
 */
class Rhino3dmRoundTripTest {

    private val orange = vec(0.9, 0.45, 0.1, 1.0)

    private fun square(x: Double, y: Double, size: Double) = FLine().apply {
        moveTo(x, y, 0.0); lineTo(x + size, y, 0.0); lineTo(x + size, y + size, 0.0); lineTo(x, y + size, 0.0); close()
    }

    /** A 4 x 3 quad with one linear colour a corner: 0, 0.2, 0.5 and 1 in red. */
    private fun colouredQuad() = TriMesh(
        floatArrayOf(60f, 0f, 0f, 64f, 0f, 0f, 64f, 3f, 0f, 60f, 3f, 0f),
        intArrayOf(0, 1, 2, 0, 2, 3),
        colors = floatArrayOf(0f, 0f, 0f, 0.2f, 0.1f, 0.05f, 0.5f, 0.5f, 0.5f, 1f, 1f, 1f),
    )

    private fun write(file: File) = Rhino3dm.save(file, units = "millimeters", version = 7) {
        layer("Solids", vec(0.27, 0.51, 0.78, 1.0))
        layer("Solids::Trimmed", vec(0.78, 0.47, 0.16, 1.0))
        layer("SubD", vec(0.35, 0.67, 0.35, 1.0))
        layer("Flat", vec(0.6, 0.6, 0.6, 1.0))
        brep(RhinoBreps.sphere(vec(0.0, 0.0, 0.0), 10.0), layer = "Solids", name = "sphere")
        brep(RhinoBreps.cylinder(vec(30.0, 0.0, -10.0), 5.0, 20.0), layer = "Solids", name = "cylinder", color = orange)
        brep(RhinoBreps.box(vec(-30.0, -5.0, -5.0), vec(-20.0, 5.0, 5.0)), layer = "Solids", name = "box")
        brep(RhinoBreps.plate(vec(0.0, -30.0, 0.0), 20.0, listOf(Triple(0.0, -30.0, 5.0))), layer = "Solids::Trimmed", name = "plate with hole")
        val (points, faces, creases) = RhinoBreps.subdBox(vec(25.0, -35.0, -5.0), vec(35.0, -25.0, 5.0), 2, false)
        subd(points, faces, creases, layer = "SubD", name = "subd")
        mesh(colouredQuad(), layer = "Flat", name = "coloured quad")
        curve(square(-35.0, -40.0, 10.0), layer = "Flat", name = "outline")
        hatch(FLine().apply { square(-35.0, 20.0, 10.0).nodes.let(nodes::addAll); square(-32.0, 23.0, 4.0).nodes.let(nodes::addAll) }, layer = "Flat", name = "hatch")
        block("fin") { mesh(TriMesh(floatArrayOf(0f, 0f, 0f, 4f, 0f, 0f, 0f, 0f, 6f), intArrayOf(0, 1, 2)), layer = "Flat", name = "fin skin") }
        instance("fin", vec(50.0, 20.0, 0.0), layer = "Flat", name = "fin A")
        instance("fin", vec(56.0, 20.0, 0.0), layer = "Flat", name = "fin B")
    }

    private fun area(m: TriMesh): Double {
        var a = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val i = m.indices[3 * t] * 3; val j = m.indices[3 * t + 1] * 3; val k = m.indices[3 * t + 2] * 3
            val ex = (p[j] - p[i]).toDouble(); val ey = (p[j + 1] - p[i + 1]).toDouble(); val ez = (p[j + 2] - p[i + 2]).toDouble()
            val fx = (p[k] - p[i]).toDouble(); val fy = (p[k + 1] - p[i + 1]).toDouble(); val fz = (p[k + 2] - p[i + 2]).toDouble()
            val cx = ey * fz - ez * fy; val cy = ez * fx - ex * fz; val cz = ex * fy - ey * fx
            a += 0.5 * sqrt(cx * cx + cy * cy + cz * cz)
        }
        return a
    }

    private fun volume(m: TriMesh): Double {
        var v = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val i = m.indices[3 * t] * 3; val j = m.indices[3 * t + 1] * 3; val k = m.indices[3 * t + 2] * 3
            v += p[i].toDouble() * (p[j + 1].toDouble() * p[k + 2] - p[j + 2].toDouble() * p[k + 1]) -
                p[i + 1].toDouble() * (p[j].toDouble() * p[k + 2] - p[j + 2].toDouble() * p[k]) +
                p[i + 2].toDouble() * (p[j].toDouble() * p[k + 1] - p[j + 1].toDouble() * p[k])
        }
        return v / 6
    }

    /** Edges used by exactly one triangle once vertices at the same position are joined. */
    private fun openEdges(m: TriMesh): Int {
        val id = HashMap<Triple<Float, Float, Float>, Int>()
        val ids = IntArray(m.vertexCount) { id.getOrPut(Triple(m.positions[3 * it], m.positions[3 * it + 1], m.positions[3 * it + 2])) { id.size } }
        val count = HashMap<Pair<Int, Int>, Int>()
        for (t in 0 until m.triangleCount) for (e in 0..2) {
            val a = ids[m.indices[3 * t + e]]; val b = ids[m.indices[3 * t + (e + 1) % 3]]
            count.merge(minOf(a, b) to maxOf(a, b), 1, Int::plus)
        }
        return count.values.count { it == 1 }
    }

    private fun close(expected: Double, actual: Double, rel: Double, what: String) =
        assertTrue(abs(actual - expected) <= rel * abs(expected), "$what: expected $expected within ${rel * 100}%, got $actual")

    @Test fun writtenSceneReadsBackMeasured() {
        if (!Rhino3dm.isAvailable) { println("SKIPPED round trip: ${Rhino3dm.describe()}"); return }
        val dir = Files.createTempDirectory("rhino-roundtrip").toFile()
        try {
            val model = Rhino3dm.load(write(File(dir, "roundtrip.3dm")), meshing = RhinoBrepMeshing.EVALUATE)
            assertEquals("", model.log.trim(), "openNURBS log")
            assertEquals(listOf("Solids", "Solids::Trimmed", "SubD", "Flat"), model.layers.map { it.path })
            assertTrue(model.skipped.isEmpty(), "${model.skipped.map { it.label + ": " + it.reason }}")
            val byName = model.objects.groupBy { it.name }
            println("ROUNDTRIP " + model.objects.joinToString { "${it.name}:${it::class.simpleName}" })

            fun solid(name: String) = (byName.getValue(name).single() as RhinoMesh)
            // Solids: exact area and volume to the tessellation's thousandth-of-size chord tolerance.
            for ((name, a, v) in listOf(
                Triple("sphere", 4 * PI * 100, 4.0 / 3 * PI * 1000),
                Triple("cylinder", 2 * PI * 5 * 20 + 2 * PI * 25, PI * 25 * 20),
                Triple("box", 600.0, 1000.0),
            )) {
                val m = solid(name).mesh
                println("ROUNDTRIP $name: ${m.triangleCount} triangles, area ${area(m)} (exact $a), volume ${volume(m)} (exact $v), open edges ${openEdges(m)}")
                close(a, area(m), 0.01, "$name area")
                close(v, volume(m), 0.01, "$name volume")
                assertEquals(0, openEdges(m), "$name is closed")
            }
            val plate = solid("plate with hole").mesh
            println("ROUNDTRIP plate: ${plate.triangleCount} triangles, area ${area(plate)} (exact ${400 - 25 * PI}), open edges ${openEdges(plate)}")
            close(400 - 25 * PI, area(plate), 0.01, "trimmed plate area")
            assertEquals("Solids::Trimmed", solid("plate with hole").layer?.path)

            val subd = solid("subd")
            assertEquals(3, subd.subdLevel)
            println("ROUNDTRIP subd: ${subd.mesh.triangleCount} triangles, area ${area(subd.mesh)}, volume ${volume(subd.mesh)}, open edges ${openEdges(subd.mesh)}")
            assertEquals(0, openEdges(subd.mesh), "SubD limit mesh is closed")
            assertTrue(volume(subd.mesh) in 500.0..1000.0, "smooth SubD box is smaller than its 10 mm cage: ${volume(subd.mesh)}")

            // Object colour over layer colour.
            assertEquals(RhinoWriter.argb(orange), RhinoWriter.argb(solid("cylinder").color))
            assertEquals(RhinoWriter.argb(model.layer("Solids").color), RhinoWriter.argb(solid("sphere").color))

            // Vertex colours: linear in, sRGB bytes in the file, linear out to within one byte.
            val quad = solid("coloured quad").mesh
            val colours = assertNotNull(quad.colors, "vertex colours survive")
            colouredQuad().colors!!.zip(colours.toList()).forEach { (e, a) -> assertEquals(e.toDouble(), a.toDouble(), 0.01) }

            // Curves and hatches.
            val outline = byName.getValue("outline").single() as RhinoCurve
            assertTrue(outline.closed)
            assertEquals(5, outline.line.nodes.size)
            val hatch = byName.getValue("hatch").single() as RhinoCurve
            assertTrue(hatch.isHatch)
            assertEquals(2, hatch.line.nodes.count { it is afield.geometry.path.FLine.MoveTo }, "outline and one hole")

            // A block placed twice comes back as its member twice, moved.
            val fins = model.meshes.filter { it.block == "fin" }
            assertEquals(2, fins.size)
            assertEquals(listOf(50f, 56f), fins.map { it.mesh.positions[0] }.sorted())
            // What does not survive: an instance's own name. The placed member keeps the name it has in the block.
            assertEquals(listOf("fin skin", "fin skin"), fins.map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }
}

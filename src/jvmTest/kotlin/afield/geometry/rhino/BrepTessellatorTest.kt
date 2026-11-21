package afield.geometry.rhino

import afield.geometry.mesh.TriMesh
import java.io.File
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Trimmed NURBS Breps saved without render meshes, tessellated by [BrepTessellator], measured
 * against the analytic shapes the C++ fixture builds (`field_opennurbs_write_brep_fixture`).
 */
class BrepTessellatorTest {

    private fun available(): Boolean {
        if (!Rhino3dm.isAvailable || !OpenNurbsBrepNative.isAvailable) {
            println("SKIPPED Brep tessellation tests: ${Rhino3dm.describe()}")
            return false
        }
        return true
    }

    private fun sample(meshing: RhinoBrepMeshing = RhinoBrepMeshing.SAVED_ELSE_EVALUATE, subdLevel: Int = 3): RhinoModel {
        val dir = Files.createTempDirectory("rhino3dm-nurbs-test").toFile()
        try {
            return Rhino3dm.load(Rhino3dm.writeNurbsSample(File(dir, "nurbs.3dm")), meshing, subdLevel = subdLevel)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun RhinoModel.mesh(name: String): RhinoMesh = assertIs<RhinoMesh>(objects.single { it.name == name }, name)

    private fun area(m: TriMesh): Double {
        var a = 0.0
        for (t in 0 until m.triangleCount) {
            val i = m.indices[3 * t] * 3; val j = m.indices[3 * t + 1] * 3; val k = m.indices[3 * t + 2] * 3
            val p = m.positions
            val ex = (p[j] - p[i]).toDouble(); val ey = (p[j + 1] - p[i + 1]).toDouble(); val ez = (p[j + 2] - p[i + 2]).toDouble()
            val fx = (p[k] - p[i]).toDouble(); val fy = (p[k + 1] - p[i + 1]).toDouble(); val fz = (p[k + 2] - p[i + 2]).toDouble()
            a += 0.5 * sqrt((ey * fz - ez * fy).let { it * it } + (ez * fx - ex * fz).let { it * it } + (ex * fy - ey * fx).let { it * it })
        }
        return a
    }

    /** Volume by the divergence theorem: positive when every triangle faces outwards. */
    private fun volume(m: TriMesh): Double {
        var v = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val i = m.indices[3 * t] * 3; val j = m.indices[3 * t + 1] * 3; val k = m.indices[3 * t + 2] * 3
            v += (p[i] * (p[j + 1] * p[k + 2] - p[j + 2] * p[k + 1]) -
                p[i + 1] * (p[j] * p[k + 2] - p[j + 2] * p[k]) +
                p[i + 2] * (p[j] * p[k + 1] - p[j + 1] * p[k])).toDouble() / 6.0
        }
        return v
    }

    @Test fun saveSmallBrepsComeBackAsMeshes() {
        if (!available()) return
        val model = sample()
        assertTrue(model.skipped.isEmpty(), model.toString())
        assertEquals(7, model.meshes.size, model.toString())
        assertEquals(0, model.mesh("box, saved mesh").evaluatedFaces)
        assertEquals(6, model.mesh("box, no mesh").evaluatedFaces)
        assertTrue("evaluated" in model.toString(), model.toString())
        for (o in model.meshes) println("${o.name}: ${o.mesh.triangleCount} triangles, area ${area(o.mesh)}, volume ${volume(o.mesh)}")
        // SAVED keeps the old behaviour.
        assertEquals(6, sample(RhinoBrepMeshing.SAVED).skipped.size)
    }

    @Test fun sphereIsOnTheSurfaceWithTheRightAreaAndFacingOut() {
        if (!available()) return
        val m = sample().mesh("sphere").mesh
        var worst = 0.0
        for (v in 0 until m.vertexCount) {
            val r = sqrt((m.x(v) * m.x(v) + m.positions[3 * v + 1] * m.positions[3 * v + 1] + m.positions[3 * v + 2] * m.positions[3 * v + 2]).toDouble())
            worst = max(worst, abs(r - 10.0))
        }
        assertTrue(worst < 1e-4, "vertex off the sphere by $worst")
        val a = area(m); val exact = 4 * PI * 100
        assertTrue(abs(a - exact) / exact < 0.01, "sphere area $a vs $exact (${m.triangleCount} triangles)")
        val vol = volume(m); val exactVolume = 4.0 / 3.0 * PI * 1000
        assertTrue(abs(vol - exactVolume) / exactVolume < 0.01, "sphere volume $vol vs $exactVolume: inside out or holed")
        // Normals point outwards.
        val n = m.normals!!
        for (v in 0 until m.vertexCount) {
            val dot = n[3 * v] * m.positions[3 * v] + n[3 * v + 1] * m.positions[3 * v + 1] + n[3 * v + 2] * m.positions[3 * v + 2]
            assertTrue(dot > 0, "normal $v points in")
        }
    }

    @Test fun cappedCylinderIsClosedWithTheRightArea() {
        if (!available()) return
        val m = sample().mesh("capped cylinder").mesh
        for (v in 0 until m.vertexCount) {
            val dx = m.positions[3 * v] - 30.0; val dy = m.positions[3 * v + 1] - 20.0; val z = m.positions[3 * v + 2]
            val r = hypot(dx, dy)
            val onSide = abs(r - 5.0) < 1e-4 && z >= -10 - 1e-4 && z <= 10 + 1e-4
            val onCap = r <= 5.0 + 1e-4 && (abs(z + 10) < 1e-4 || abs(z - 10) < 1e-4)
            assertTrue(onSide || onCap, "vertex $v at r=$r z=$z is on neither the side nor a cap")
        }
        val a = area(m); val exact = 2 * PI * 5 * 20 + 2 * PI * 25
        assertTrue(abs(a - exact) / exact < 0.01, "cylinder area $a vs $exact")
        val vol = volume(m); val exactVolume = PI * 25 * 20
        assertTrue(abs(vol - exactVolume) / exactVolume < 0.01, "cylinder volume $vol vs $exactVolume")
    }

    @Test fun planarFaceKeepsItsHole() {
        if (!available()) return
        val m = sample().mesh("plate with hole").mesh
        val a = area(m); val exact = 400 - PI * 25
        assertTrue(abs(a - exact) / exact < 0.005, "plate area $a vs $exact")
        for (t in 0 until m.triangleCount) {
            var cx = 0.0; var cy = 0.0
            for (c in 0..2) { cx += m.positions[3 * m.indices[3 * t + c]] / 3.0; cy += m.positions[3 * m.indices[3 * t + c] + 1] / 3.0 }
            assertTrue(hypot(cx - 30, cy + 10) > 4.9, "triangle $t sits in the hole")
        }
    }

    @Test fun evaluatedBoxMatchesTheSavedOne() {
        if (!available()) return
        val model = sample(RhinoBrepMeshing.EVALUATE)
        val saved = sample().mesh("box, saved mesh").mesh
        val evaluated = model.mesh("box, saved mesh")
        assertEquals(6, evaluated.evaluatedFaces)
        assertEquals(area(saved), area(evaluated.mesh), 1e-3)
        assertEquals(600.0, area(model.mesh("box, no mesh").mesh), 1e-3)
        assertEquals(1000.0, volume(model.mesh("box, no mesh").mesh), 1e-2)
    }

    @Test fun creasedSubDBoxLimitsToTheBoxItself() {
        if (!available()) return
        val o = sample().mesh("creased SubD")
        assertEquals(3, o.subdLevel)
        val m = o.mesh
        assertEquals(600.0, area(m), 1e-2)
        assertEquals(1000.0, volume(m), 1e-1)
        for (v in 0 until m.vertexCount) {
            val x = m.positions[3 * v] - 20.0; val y = m.positions[3 * v + 1] + 40.0; val z = m.positions[3 * v + 2].toDouble()
            val face = max(abs(x), max(abs(y), abs(z)))
            assertEquals(5.0, face, 1e-4, "vertex $v is off the box")
        }
    }

    @Test fun smoothSubDConvergesInsideItsCage() {
        if (!available()) return
        val levels = (1..4).map { sample(subdLevel = it).mesh("smooth SubD").mesh }
        val areas = levels.map(::area)
        println("smooth SubD areas by level: $areas, volumes ${levels.map(::volume)}, triangles ${levels.map { it.triangleCount }}")
        for (k in 1 until areas.size - 1) {
            assertTrue(abs(areas[k + 1] - areas[k]) < abs(areas[k] - areas[k - 1]), "areas do not converge: $areas")
        }
        val m = levels.last()
        assertTrue(volume(m) in 100.0..1000.0, "volume ${volume(m)}: inside out, open, or outside its cage")
        for (v in 0 until m.vertexCount) {
            val x = m.positions[3 * v] - 0.0; val y = m.positions[3 * v + 1] + 40.0; val z = m.positions[3 * v + 2].toDouble()
            assertTrue(abs(x) <= 5.0 + 1e-4 && abs(y) <= 5.0 + 1e-4 && abs(z) <= 5.0 + 1e-4, "vertex $v outside the cage")
        }
    }

    /** Edges used by one triangle once vertices at the same float position are joined. */
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

    @Test fun closedBrepsFromTheFileMeshClosed() {
        if (!available()) return
        val model = sample(RhinoBrepMeshing.EVALUATE)
        for (name in listOf("sphere", "capped cylinder", "box, no mesh", "box, saved mesh")) {
            val m = model.mesh(name).mesh
            println("WATERTIGHT file $name: ${m.triangleCount} triangles, ${openEdges(m)} open edges")
            assertEquals(0, openEdges(m), "$name has open edges")
        }
        // The same faces straight from the reader, for the stitch's before/after counts.
        val dir = Files.createTempDirectory("rhino3dm-nurbs-test").toFile()
        try {
            val contents = OpenNurbsBrepNative.read(Rhino3dm.writeNurbsSample(File(dir, "nurbs.3dm")), -1)
            for ((id, faces) in contents.breps) {
                val t = BrepTessellator()
                val m = t.meshFaces(faces) ?: continue
                println("WATERTIGHT reader $id (${faces.size} faces): ${t.lastStitch}, ${m.triangleCount} triangles")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun bilinearPatchEvaluatesExactly() {
        val s = NurbsSurface(
            1, 1, 2, 2, doubleArrayOf(0.0, 0.0, 1.0, 1.0), doubleArrayOf(0.0, 0.0, 1.0, 1.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 2.0, 0.0, 1.0, 3.0, 0.0, 0.0, 1.0, 3.0, 2.0, 0.0, 1.0),
        )
        val out = DoubleArray(6)
        s.evaluate(0.5, 0.25, out)
        assertEquals(1.5, out[0], 1e-12); assertEquals(0.5, out[1], 1e-12)
        assertTrue(out[5] > 0)
    }
}

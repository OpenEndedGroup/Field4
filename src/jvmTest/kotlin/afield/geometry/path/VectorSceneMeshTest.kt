package afield.geometry.path

import afield.geometry.mesh.colouredBy
import afield.geometry.mesh.coordinateField
import afield.geometry.mesh.icosphereMesh
import afield.geometry.mesh.torusMesh
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [VectorSceneBuilder.mesh] and [VectorSceneBuilder.polyline]: a whole `TriMesh` and 3D strokes in
 * one depth-ordered scene, ordered correctly against the per-pixel reference and fast enough at
 * twenty thousand faces.
 */
class VectorSceneMeshTest {

    private val white = vec(1.0, 1.0, 1.0, 1.0)

    @Test
    fun closedMeshCullsAboutHalfItsTriangles() {
        val ball = icosphereMesh(subdivisions = 3, radius = 100.0)
        val s = vectorScene { mesh(ball) }
        val faces = s.project(0.4, -0.3)
        assertTrue(faces.size in ball.triangleCount * 4 / 10..ball.triangleCount * 6 / 10, "kept ${faces.size} of ${ball.triangleCount}")
        val open = vectorScene { mesh(ball, solid = false) }.project(0.4, -0.3)
        assertTrue(open.size > faces.size * 3 / 2, "an open mesh keeps the far side")
    }

    @Test
    fun vertexColoursBecomeFaceColours() {
        val ball = icosphereMesh(subdivisions = 2, radius = 1.0)
        val tinted = ball.colouredBy(ball.coordinateField(1))
        val cols = vectorScene { mesh(tinted) }.facets.map { it.color }.toSet()
        assertTrue(cols.size > 20, "expected the ramp to give many face colours, got ${cols.size}")
        assertEquals(setOf(white), vectorScene { mesh(tinted, color = white) }.facets.map { it.color }.toSet())
    }

    @Test
    fun torusOrdersAgainstItself() {
        // a torus seen from above at an angle hides its own far ring: the hard case for a painter
        val s = vectorScene { mesh(torusMesh(48, 24, 100.0, 35.0)) }
        var c = 0L; var p = 0L; var cov = 0L
        for (k in 0 until 4) {
            val yaw = k * PI / 4
            val (wc, n) = s.wrongOrderShare(yaw, -0.5, DepthOrder.CENTROID, 160, 160)
            val (wp, _) = s.wrongOrderShare(yaw, -0.5, DepthOrder.PAIRWISE, 160, 160)
            c += wc; p += wp; cov += n
        }
        println("torus wrong-order share: centroid ${"%.3f".format(100.0 * c / cov)}%, pairwise ${"%.3f".format(100.0 * p / cov)}%")
        assertTrue(p <= c, "pairwise ($p) should not be worse than centroid ($c)")
        assertTrue(p.toDouble() / cov < 0.005, "pairwise wrong share ${100.0 * p / cov}% too high")
    }

    @Test
    fun ringOnSphereShowsFrontAndHidesBack() {
        val r = 100.0
        val ring = (0..96).map { k -> val a = 2 * PI * k / 96; Vec3((r * cos(a)).toFloat(), 0f, (r * sin(a)).toFloat()) }
        val s = vectorScene {
            mesh(icosphereMesh(subdivisions = 4, radius = r))
            polyline(ring, width = 3.0, color = vec(0.9, 0.1, 0.1, 1.0))
        }
        // looking slightly down: the near half of the equator is in front, the far half behind
        val faces = s.project(0.0, -0.3)
        val tris = faces.filter { it.facet.radius == 0.0 }
        var shown = 0; var hidden = 0
        for (st in faces.filter { it.facet.radius > 0 }) {
            val x = st.core.sumOf { it[0] } / 2; val y = st.core.sumOf { it[1] } / 2
            val d = st.depthAt(x, y)
            val under = tris.filter { inside(it.footprint, x, y) }.minOfOrNull { it.depthAt(x, y) }
            if (under == null || d < under) shown++ else hidden++
        }
        assertTrue(shown > 20 && hidden > 20, "expected the ring half shown, half hidden: $shown shown, $hidden hidden")
        val (wp, cov) = s.wrongOrderShare(0.0, -0.3, DepthOrder.PAIRWISE, 200, 200)
        println("ring on sphere wrong-order share: pairwise ${"%.3f".format(100.0 * wp / cov)}%")
        assertTrue(wp.toDouble() / cov < 0.005, "ring + sphere wrong share ${100.0 * wp / cov}%")
    }

    @Test
    fun pairwiseScalesToTwentyThousandFaces() {
        for (sub in 3..5) {
            // about 1k, 5k and 20k triangles facing the camera
            val ball = icosphereMesh(subdivisions = sub, radius = 100.0)
            val bumpy = torusMesh(4 * (1 shl sub), 2 * (1 shl sub), 160.0, 40.0)
            val s = vectorScene { mesh(ball); mesh(bumpy) }
            val faces = s.project(0.3, -0.6)
            val t0 = System.nanoTime()
            val (order, breaks) = VectorScene.pairwiseOrder(faces)
            val ms = (System.nanoTime() - t0) / 1e6
            assertEquals(faces.size, order.size)
            println("pairwise order: ${ball.triangleCount + bumpy.triangleCount} triangles, ${faces.size} front-facing, ${"%.0f".format(ms)} ms, $breaks cycle breaks")
            assertTrue(ms < 20_000, "ordering took $ms ms")
        }
    }
}

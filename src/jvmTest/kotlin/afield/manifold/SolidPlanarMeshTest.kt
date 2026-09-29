package afield.manifold

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolidPlanarMeshTest {

    private fun maximumFaceDeviation(mesh: SolidPlanarMesh, face: SolidPlanarFace): Double {
        fun point(vertex: Int): DoubleArray {
            val at = vertex * 3
            return doubleArrayOf(mesh.positions[at], mesh.positions[at + 1], mesh.positions[at + 2])
        }
        var origin: DoubleArray? = null
        var normal: DoubleArray? = null
        var bestArea2 = 0.0
        face.triangles.forEach { triangle ->
            val a = point(mesh.indices[triangle * 3])
            val b = point(mesh.indices[triangle * 3 + 1])
            val c = point(mesh.indices[triangle * 3 + 2])
            val ab = doubleArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
            val ac = doubleArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
            val cross = doubleArrayOf(
                ab[1] * ac[2] - ab[2] * ac[1],
                ab[2] * ac[0] - ab[0] * ac[2],
                ab[0] * ac[1] - ab[1] * ac[0],
            )
            val area2 = kotlin.math.sqrt(cross.sumOf { it * it })
            if (area2 > bestArea2) {
                bestArea2 = area2
                origin = a
                normal = DoubleArray(3) { cross[it] / area2 }
            }
        }
        assertTrue(bestArea2 > 0.0, "fabrication face ${face.faceId} has no area")
        val planeOrigin = requireNotNull(origin)
        val planeNormal = requireNotNull(normal)
        var maximum = 0.0
        face.triangles.forEach { triangle ->
            repeat(3) { corner ->
                val p = point(mesh.indices[triangle * 3 + corner])
                maximum = max(
                    maximum,
                    abs(
                        planeNormal[0] * (p[0] - planeOrigin[0]) +
                            planeNormal[1] * (p[1] - planeOrigin[1]) +
                            planeNormal[2] * (p[2] - planeOrigin[2]),
                    ),
                )
            }
        }
        return maximum
    }

    @Test
    fun cubeExportsSixConnectedPlanarFabricationFaces() {
        Solid.cube(10.0, 5.0, 0.25).use { solid ->
            val mesh = solid.toPlanarMesh()
            assertEquals(6, mesh.faces.size, "front, back and all four sides must remain distinct faces")
            assertTrue(mesh.faces.all { maximumFaceDeviation(mesh, it) <= 1e-10 })
        }
    }

    @Test
    fun disconnectedComponentsNeverShareAProvenanceFace() {
        Solid.cube(2.0).use { first ->
            Solid.cube(2.0).translated(10.0, 0.0, 0.0).use { second ->
                Solid.compose(listOf(first, second)).use { composed ->
                    val mesh = composed.toPlanarMesh()
                    assertEquals(12, mesh.faces.size, "two boxes need twelve connected physical faces")
                    assertTrue(mesh.faces.all { maximumFaceDeviation(mesh, it) <= 1e-10 })
                }
            }
        }
    }

    @Test
    fun mesh64RoundTripKeepsPanelDimensionsAtLargeWorldCoordinates() {
        Solid.cube(10.0, 5.0, 0.25).translated(1.0e9, -1.0e9, 1.0e9).use { farPanel ->
            val exported = farPanel.toPlanarMesh()
            exported.toSolid().use { roundTripped ->
                val mesh = roundTripped.toPlanarMesh()
                fun extent(axis: Int): Double {
                    val values = (0 until mesh.vertexCount).map { mesh.positions[it * 3 + axis] }
                    return values.max() - values.min()
                }
                assertTrue(abs(extent(0) - 10.0) <= 1e-6, "x extent was ${extent(0)}")
                assertTrue(abs(extent(1) - 5.0) <= 1e-6, "y extent was ${extent(1)}")
                assertTrue(abs(extent(2) - 0.25) <= 1e-6, "z extent was ${extent(2)}")
                assertEquals(6, mesh.faces.size)
                assertTrue(mesh.faces.all { maximumFaceDeviation(mesh, it) <= 1e-9 })
            }
        }
    }
}

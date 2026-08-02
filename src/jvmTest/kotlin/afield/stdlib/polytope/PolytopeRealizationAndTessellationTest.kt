package afield.stdlib.polytope

import afield.stdlib.Vec3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PolytopeRealizationAndTessellationTest {
    @Test
    fun realizationSolverRepairsPerturbedPlanarityAndEdgeLengths() {
        val source = cube()
        val coordinates = assertNotNull(source.realization).vertexCoordinates.mapIndexed { index, point ->
            if (index == 0) Vec3(point).add(0.28f, -0.17f, 0.31f) else point
        }
        val perturbed = source.copy(realization = Realization3(coordinates))
        val before = perturbed.geometryDiagnostics()
        val solved = perturbed.solveRealization()
        val after = solved.finalDiagnostics

        assertSame(perturbed.topology, solved.polyhedron.topology)
        assertEquals(perturbed.attributes, solved.polyhedron.attributes)
        assertTrue(after.maxFacePlanarityError < before.maxFacePlanarityError * 0.1, "$before -> $after")
        assertTrue(after.edgeLengthRmsDeviation < before.edgeLengthRmsDeviation * 0.1, "$before -> $after")
        assertTrue(after.degenerateFaces.isEmpty())
        assertTrue(solved.converged, "solver stopped after ${solved.iterations} iterations: ${solved.reason}")
        assertTrue(solved.rmsResidual.isFinite())
        assertOutward(solved.polyhedron)
    }

    @Test
    fun defaultDisplayTriangulatorHandlesConcaveFacesAndRetainsFaceMapping() {
        val concave = concavePrism()
        val geometry = concave.triangulate()
        assertEquals(20, geometry.triangleCount)
        val largeFaces = concave.topology.elements(2).filter { concave.topology.faceCycle(it.id).size == 6 }
        assertEquals(2, largeFaces.size)

        for (face in largeFaces) {
            val triangles = geometry.triangleFaceIds.indices.filter { geometry.sourceFace(it) == face.id }
            assertEquals(4, triangles.size)
            val area = triangles.sumOf { triangle ->
                val (a, b, c) = geometry.mesh.triangle(triangle)
                abs(
                    ((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)).toDouble(),
                ) * 0.5
            }
            assertTrue(abs(area - 3.0) < 1e-5, "concave face ${face.id.value} area was $area")
        }
        for (triangle in 0 until geometry.triangleCount) {
            assertTrue(
                geometry.mesh.faceNormal(triangle).dot(concave.faceNormal(geometry.sourceFace(triangle))) > 0.9999f,
                "triangle $triangle lost source-face winding",
            )
        }

        concave.toFaceFLines().forEachIndexed { index, line ->
            var triangles = 0
            line.tessellate { _, indices -> triangles += indices.size / 3 }
            val face = concave.topology.elements(2)[index]
            assertEquals(
                concave.topology.faceCycle(face.id).size - 2,
                triangles,
                "3D FLine tessellation for face ${face.id.value}",
            )
        }
    }

    @Test
    fun earcutProjectsConcaveFacesInAllThreePrincipalPlanes() {
        val polygons = listOf(
            listOf(Vec3(-1f, -1f, 2f), Vec3(1f, -1f, 2f), Vec3(1f, 0f, 2f), Vec3(0f, 0f, 2f), Vec3(0f, 1f, 2f), Vec3(-1f, 1f, 2f)),
            listOf(Vec3(2f, -1f, -1f), Vec3(2f, 1f, -1f), Vec3(2f, 1f, 0f), Vec3(2f, 0f, 0f), Vec3(2f, 0f, 1f), Vec3(2f, -1f, 1f)),
            listOf(Vec3(-1f, 2f, -1f), Vec3(-1f, 2f, 1f), Vec3(0f, 2f, 1f), Vec3(0f, 2f, 0f), Vec3(1f, 2f, 0f), Vec3(1f, 2f, -1f)),
        )
        for (polygon in polygons) {
            val indices = EarcutFaceTriangulator.triangulate(polygon.indices.toList().toIntArray(), Realization3(polygon))
            assertEquals(12, indices.size)
            assertTrue(indices.all { it in polygon.indices })
        }
    }

    private fun concavePrism(): PolyhedralObject {
        val outline = listOf(
            -1f to -1f,
            1f to -1f,
            1f to 0f,
            0f to 0f,
            0f to 1f,
            -1f to 1f,
        )
        val coordinates = outline.map { (x, y) -> Vec3(x, y, -0.5f) } +
            outline.map { (x, y) -> Vec3(x, y, 0.5f) }
        val n = outline.size
        val faces = mutableListOf<List<Int>>()
        faces += (0 until n).toList()
        faces += (n until 2 * n).toList()
        for (i in 0 until n) {
            val next = (i + 1) % n
            faces += listOf(i, next, next + n, i + n)
        }
        return realizedPolyhedron(coordinates, faces)
    }

    private fun assertOutward(polyhedron: PolyhedralObject) {
        val realization = assertNotNull(polyhedron.realization)
        val center = Vec3()
        realization.vertexCoordinates.forEach(center::add)
        center.mul(1f / realization.size)
        for (face in polyhedron.topology.elements(2)) {
            val faceCenter = Vec3()
            polyhedron.topology.faceCycle(face.id).forEach { vertex ->
                faceCenter.add(realization[polyhedron.realizationIndex(ElementId(vertex))])
            }
            faceCenter.mul(1f / polyhedron.topology.faceCycle(face.id).size).sub(center)
            assertTrue(polyhedron.faceNormal(face.id).dot(faceCenter) > 0f, "face ${face.id.value} is inward")
        }
    }
}

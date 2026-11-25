package afield.geometry.rhino

import afield.graphics.scene.fabricated.PathTraceScene
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [PathTraceScene.rhino] on the Save Small fixture: the scene holds what [RhinoModel.mesh] holds, kept apart by object. */
class RhinoPathTraceTest {

    private fun saveSmall(): RhinoModel {
        org.junit.Assume.assumeTrue(
            "native library not available: ${Rhino3dm.describe()}",
            Rhino3dm.isAvailable && OpenNurbsBrepNative.isAvailable,
        )
        val dir = Files.createTempDirectory("rhino-path-trace-test").toFile()
        try {
            return Rhino3dm.load(Rhino3dm.writeNurbsSample(File(dir, "save_small.3dm")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun objectAndTriangleCountsMatchTheMergedMesh() {
        val model = saveSmall()
        val merged = model.mesh()
        val scene = PathTraceScene()
        val added = scene.rhino(model)
        assertEquals(model.meshes.count { it.isVisible }, added.objects)
        assertEquals(merged.triangleCount, added.triangleCount + added.degenerateCount)
        assertEquals(added.triangleCount, scene.triangles.size)
        // One material per distinct display colour, not per object.
        val colours = model.meshes.filter { it.isVisible }.map { Triple(it.color.x, it.color.y, it.color.z) }.toSet()
        assertEquals(colours.size, scene.materials.size)
        assertEquals(colours.size, added.materials.values.toSet().size)
    }

    @Test fun zUpTurnsRhinoZIntoSceneY() {
        val model = saveSmall()
        val merged = model.mesh()
        var zMin = Float.MAX_VALUE; var zMax = -Float.MAX_VALUE
        for (v in 0 until merged.vertexCount) {
            zMin = minOf(zMin, merged.positions[3 * v + 2]); zMax = maxOf(zMax, merged.positions[3 * v + 2])
        }
        val added = PathTraceScene().rhino(model, scale = 0.5f)
        assertTrue(kotlin.math.abs(added.boundsMin.y - zMin * 0.5f) < 1e-3f, "${added.boundsMin} vs z $zMin")
        assertTrue(kotlin.math.abs(added.boundsMax.y - zMax * 0.5f) < 1e-3f, "${added.boundsMax} vs z $zMax")
    }
}

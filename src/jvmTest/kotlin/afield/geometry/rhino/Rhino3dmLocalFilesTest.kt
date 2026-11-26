package afield.geometry.rhino

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The reader against real Rhino files that cannot be committed — a client's model, a file under
 * someone else's licence. Name them in `AFIELD_RHINO_3DM_FILES`, separated by the platform's path
 * separator (`:` on macOS and Linux), and run
 *
 *     AFIELD_RHINO_3DM_FILES=~/Downloads/model.3dm ./gradlew jvmTest --tests 'afield.geometry.rhino.Rhino3dmLocalFilesTest'
 *
 * Without the variable it does nothing. It asserts only what must hold of any file, so it carries
 * no names, counts or coordinates from the models it reads; what it found goes to the test's
 * standard output as each model's summary.
 */
class Rhino3dmLocalFilesTest {

    private fun files(): List<File> {
        val listed = System.getenv("AFIELD_RHINO_3DM_FILES")?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listed.split(File.pathSeparator).filter { it.isNotBlank() }.map {
            File(it.trim().replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home")))
        }
    }

    @Test fun everyNamedFileReadsAndHoldsTogether() {
        val files = files()
        if (files.isEmpty()) throw org.junit.AssumptionViolatedException("SKIPPED local .3dm files: set AFIELD_RHINO_3DM_FILES to check your own")
        if (!Rhino3dm.isAvailable) fail("AFIELD_RHINO_3DM_FILES is set but ${Rhino3dm.describe()}")
        for (file in files) {
            assertTrue(file.isFile, "AFIELD_RHINO_3DM_FILES names $file, which is not a file")
            val model = Rhino3dm.load(file)
            println(model)
            check(model)
        }
    }

    private fun check(model: RhinoModel) {
        val name = model.file.name
        assertTrue(model.layers.isNotEmpty(), "$name: no layers")
        assertTrue(model.objects.isNotEmpty(), "$name: no objects")
        assertTrue(model.layers.map { it.index } == model.layers.indices.toList(), "$name: layer indices out of order")
        for (layer in model.layers) {
            var depth = 0
            var at = layer.parent
            while (at != null) {
                at = at.parent
                assertTrue(++depth < 64, "$name: layer ${layer.path} has a parent cycle")
            }
            assertTrue(layer.path.endsWith(layer.name), "$name: layer path ${layer.path} does not end in its name ${layer.name}")
        }
        for (o in model.objects) {
            val what = "$name: ${o.label}"
            assertTrue(o.layer != null, "$what has no layer")
            when (o) {
                is RhinoMesh -> {
                    assertTrue(o.mesh.triangleCount > 0, "$what is a mesh with no triangles")
                    assertTrue(o.mesh.positions.all { it.isFinite() }, "$what has a non-finite vertex")
                    o.mesh.normals?.let { n -> assertTrue(n.all { it.isFinite() }, "$what has a non-finite normal") }
                }
                is RhinoCurve -> {
                    assertTrue(o.line.nodes.size >= 2, "$what is a curve with fewer than two nodes")
                    assertTrue(o.line.nodes.all { it is afield.geometry.path.FLine.Close || it.to.isFinite }, "$what has a non-finite point")
                }
                is RhinoText -> {
                    assertTrue(o.height > 0 && o.height.isFinite(), "$what has text height ${o.height}")
                    assertTrue(o.text.isNotEmpty(), "$what is text with no string")
                    val length = Math.sqrt((o.xAxis.x * o.xAxis.x + o.xAxis.y * o.xAxis.y + o.xAxis.z * o.xAxis.z).toDouble())
                    assertTrue(Math.abs(length - 1) < 1e-3, "$what has a text x axis of length $length")
                }
                is RhinoPoints -> assertTrue(o.points.isNotEmpty(), "$what is a point set with no points")
                is RhinoSkipped -> assertTrue(o.reason.isNotBlank(), "$what was skipped without a reason")
            }
        }
    }

    private val afield.math.linalg.Vec3.isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()
}

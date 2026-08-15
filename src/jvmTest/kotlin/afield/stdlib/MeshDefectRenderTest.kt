package afield.stdlib

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The defect overlay actually draws the defects — verified by counting the pixels it painted, not by
 * checking that the call returned.
 *
 * Renders into a temp directory (never into the repo) and prints the path, so a person — or an agent
 * with an image reader — can go and look at the picture the assertions are about. The assertions
 * themselves are on the raster: a mesh with a hole in it must produce measurably more red pixels than
 * the same mesh without one, which is a claim about what was drawn rather than about what was called.
 */
class MeshDefectRenderTest {

    /** Pixels where red clearly dominates — the boundary-loop colour. */
    private fun redness(png: ByteArray): Int = count(png) { r, g, b -> r > 110 && r > g * 2 && r > b * 2 }

    /** Pixels that are orange rather than red — the non-manifold-edge colour. */
    private fun orangeness(png: ByteArray): Int =
        count(png) { r, g, b -> r > 120 && g > r / 3 && g < r * 0.8 && b < g * 0.7 }

    private fun count(png: ByteArray, p: (Int, Int, Int) -> Boolean): Int {
        val img = javax.imageio.ImageIO.read(png.inputStream())
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            if (p((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) n++
        }
        return n
    }

    /**
     * A camera aimed at one vertex.
     *
     * Needed because the first version of this test rendered a non-manifold fin that happened to be on
     * the far side of the sphere, saved a picture of an apparently perfect icosphere, and asserted
     * nothing about it. A capture that shows none of the thing it is captioned with is worse than no
     * capture, so the view is now computed from the defect's own position.
     */
    private fun viewFacing(mesh: TriMesh, vertex: Int, width: Int, height: Int): MeshView {
        val p = mesh.position(vertex)
        val yaw = Math.toDegrees(kotlin.math.atan2(p.x.toDouble(), p.z.toDouble()))
        val len = kotlin.math.sqrt((p.x * p.x + p.y * p.y + p.z * p.z).toDouble()).coerceAtLeast(1e-9)
        val pitch = Math.toDegrees(kotlin.math.asin((p.y / len).coerceIn(-1.0, 1.0)))
        return MeshView(yawDegrees = yaw, pitchDegrees = pitch, width = width, height = height)
    }

    @Test
    fun theDefectOverlayPaintsTheDefects() {
        val out = File(System.getProperty("java.io.tmpdir"), "field-mesh-defects")
        out.mkdirs()
        val view = MeshView(width = 520, height = 400)

        val whole = cubeMesh(1.0)
        val holed = MeshDamage.deleteFaces(whole, 0, 2)   // one whole face of the cube
        val finned = MeshDamage.nonManifoldFin(icosphereMesh(2, 1.0), 0)

        // The fin's tip is the last vertex MeshDamage added, so point the camera straight at it.
        val finView = viewFacing(finned, finned.vertexCount - 1, view.width, view.height)
        val cleanIco = icosphereMesh(2, 1.0)

        val wholePng = whole.withDefectColors().renderPng(view)
        val holedPng = holed.withDefectColors().renderPng(view)
        val finnedPng = finned.withDefectColors().renderPng(finView)
        val cleanIcoPng = cleanIco.withDefectColors().renderPng(finView)
        File(out, "cube_ok.png").writeBytes(wholePng)
        File(out, "cube_holed.png").writeBytes(holedPng)
        File(out, "ico_fin.png").writeBytes(finnedPng)
        File(out, "ico_ok.png").writeBytes(cleanIcoPng)

        val ok = redness(wholePng)
        val bad = redness(holedPng)
        val finOrange = orangeness(finnedPng)
        val cleanOrange = orangeness(cleanIcoPng)
        println("defect overlay rasters in $out")
        println("  clean cube $ok red px, holed cube $bad red px")
        println("  clean icosphere $cleanOrange orange px, finned icosphere $finOrange orange px (view $finView)")

        assertTrue(ok < 200, "an undamaged cube should paint essentially no red, got $ok pixels")
        assertTrue(bad > 2000, "the holed cube's boundary loop should be plainly visible, got $bad pixels")
        assertTrue(bad > ok * 10, "and it should dominate the clean render: $bad vs $ok")
        assertTrue(cleanOrange < 100, "a clean icosphere should paint no orange, got $cleanOrange pixels")
        assertTrue(
            finOrange > 500,
            "the non-manifold fin should be visibly orange from a camera pointed at it, got $finOrange pixels",
        )

        // The FLine route must find the same defects the colouring did.
        val lines = holed.defectFLines()
        assertTrue(lines.size == 1, "one hole, one boundary FLine, got ${lines.size}")
        assertTrue(
            lines[0].properties["color"] != null,
            "a defect line must be coloured, or it is indistinguishable from the wireframe",
        )
        val finLines = finned.defectFLines()
        assertTrue(finLines.isNotEmpty(), "the non-manifold fin must produce a line")
    }
}

package afield.solidsl

import afield.manifold.ManifoldLibrary
import afield.proofDir
import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.renderPixels
import afield.stdlib.renderPng
import afield.stdlib.signedVolume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders each lowering of the same source and **inspects the pixels**, because "the volumes agree"
 * is not the same claim as "the picture is a solid".
 *
 * A closed mesh wound inside-out has the right volume magnitude, the right area, the right genus and
 * a perfectly normal-looking render — `meshThumbnailHtml`'s KDoc says so, and says the tell is the
 * *sign* of the volume. A shape rendered from the wrong side of the camera is black. A mesh that came
 * out empty is a background-coloured rectangle that looks like a deliberate blank. None of those show
 * up in a tolerance on a number, so this test looks at the raster:
 *
 *  - a real fraction of the frame is covered by something that is not the background,
 *  - the covered pixels are actually lit rather than black,
 *  - the two lowerings cover **the same silhouette**, to within a few percent of the frame — which is
 *    the pixel-space version of the whole cross-validation, and the check a mirrored lowering fails,
 *  - and the signed volume is positive, so the solid is not inside-out.
 *
 * The PNGs go to [proofDir], which is a throwaway temp directory unless `AFIELD_PROOF_DIR` is set —
 * so the assertions run everywhere and nothing is left in the repository. Point it somewhere to
 * collect them:
 *
 * ```
 * AFIELD_PROOF_DIR=/tmp/solidsl ./gradlew jvmTest --tests 'afield.solidsl.SolidSLRenderProofTest'
 * ```
 */
class SolidSLRenderProofTest {

    private val view = MeshView(yawDegrees = 34.0, pitchDegrees = 24.0, width = 620, height = 460)

    @Test
    fun `both lowerings render as the same lit solid`() {
        if (!ManifoldLibrary.isAvailable) {
            println("SolidSLRenderProofTest: skipping — ${ManifoldLibrary.describe()}")
            return
        }
        val dir = proofDir("solidsl")
        val q = SolidQuality(segments = 192, edgeLengthFraction = 1.0 / 110.0)

        val subjects = listOf<Pair<String, SolidExpr>>(
            "bracket" to (
                (cuboid(4.0, 2.6, 0.7) + cylinder(1.4, 0.75, segments = 192).at(0.0, 0.0, 0.0)) -
                    unionOf(
                        listOf(-1.5 to 0.0, 1.5 to 0.0).map { (x, _) ->
                            cylinder(3.0, 0.3, segments = 192).at(x, 0.0, 0.0)
                        } + cylinder(3.0, 0.38, segments = 192),
                    )
                ),
            "starTower" to Profile.star(6, 1.0, 0.5).extruded(2.2, twistDegrees = 60.0, slices = 96).at(0.0, 0.0, -1.1),
            "ring" to torus(1.1, 0.4, segments = 192),
            "wedge" to Profile.rect(0.7, 0.7).translated(1.2, 0.0).revolved(270.0, segments = 192),
        )

        println("%-11s %9s %9s %9s %9s".format("subject", "exact%", "march%", "|diff|%", "volume"))
        subjects.forEach { (name, expr) ->
            val exact = expr.exactSolid(q).orThrow().use { it.toTriMesh(normals = true) }
            val marched = expr.marchedMesh(q)

            write(dir, "${name}_exact.png", exact, "$name — exact CSG (Manifold)")
            write(dir, "${name}_marched.png", marched, "$name — marching cubes on the distance field")

            val a = coverage(exact)
            val b = coverage(marched)
            println(
                "%-11s %8.2f%% %8.2f%% %8.2f%% %9.4f".format(
                    name, 100 * a.covered, 100 * b.covered, 100 * abs(a.covered - b.covered), marched.signedVolume(),
                ),
            )

            // Something is there. A tenth of the frame is a low bar that an empty mesh, a mesh behind
            // the camera and a mesh scaled to a speck all fail.
            assertTrue(a.covered > 0.10, "$name: the exact render covers only ${a.covered} of the frame")
            assertTrue(b.covered > 0.10, "$name: the marched render covers only ${b.covered} of the frame")
            // It is lit. An all-black silhouette is what a mesh with degenerate or inward normals
            // renders as, and it passes a coverage check.
            assertTrue(a.meanLuma > 0.08, "$name: the exact render is covered but nearly black (luma ${a.meanLuma})")
            assertTrue(b.meanLuma > 0.08, "$name: the marched render is covered but nearly black (luma ${b.meanLuma})")
            assertTrue(a.brightFraction > 0.02, "$name: the exact render has almost no lit pixels")
            // The two silhouettes are the same size. This is the cross-validation in pixel space: a
            // mirrored or transposed lowering keeps its volume and fails here.
            assertTrue(
                abs(a.covered - b.covered) < 0.02,
                "$name: the two lowerings cover different fractions of the frame — ${a.covered} vs ${b.covered}",
            )
            // Not inside-out. `signedVolume` is the tell the render itself cannot give.
            assertTrue(marched.signedVolume() > 0.0, "$name: the marched solid is inside-out")
        }
        println("wrote ${subjects.size * 2} PNGs to $dir")
    }

    private fun write(dir: java.io.File, name: String, mesh: TriMesh, title: String) {
        java.io.File(dir, name).writeBytes(mesh.renderPng(view, title = title))
    }

    private data class Coverage(val covered: Double, val meanLuma: Double, val brightFraction: Double)

    /** What is actually in the frame: how much is not background, how lit it is, how much is bright. */
    private fun coverage(mesh: TriMesh): Coverage {
        val raster = mesh.renderPixels(view)
        val background = 0xFF101318.toInt()
        var covered = 0
        var lumaSum = 0.0
        var bright = 0
        for (p in raster.pixels) {
            if (p == background) continue
            covered++
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            lumaSum += luma
            if (luma > 0.35) bright++
        }
        val n = raster.pixels.size.toDouble()
        return Coverage(covered / n, if (covered == 0) 0.0 else lumaSum / covered, bright / n)
    }
}

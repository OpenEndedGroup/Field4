package afield.stdlib

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import afield.stdlib.linalg.Vector3f as Vec3

/**
 * The inline visualizer, verified to pixels.
 *
 * "It compiles" and "the HTML contains a base64 blob" are both worthless for something whose whole job
 * is to be looked at: an all-black 300x225 PNG passes them. So every test here **decodes the PNG back out
 * of the emitted HTML** and reads samples, and each one checks a specific claim
 * [afield.stdlib.meshThumbnailHtml]'s documentation makes about what you are seeing.
 */
class MeshVisualizerPixelTest {

    private class Decoded(val width: Int, val height: Int, val argb: IntArray) {
        fun at(x: Int, y: Int): Int = argb[y * width + x]
        fun red(x: Int, y: Int): Int = (at(x, y) shr 16) and 0xFF
        fun green(x: Int, y: Int): Int = (at(x, y) shr 8) and 0xFF
        fun blue(x: Int, y: Int): Int = at(x, y) and 0xFF
        fun luminance(x: Int, y: Int): Double =
            0.2126 * red(x, y) + 0.7152 * green(x, y) + 0.0722 * blue(x, y)

        fun paintedFraction(background: Int): Double =
            argb.count { it != background }.toDouble() / argb.size
    }

    /** Pull the one inline PNG out of a `toHTML()` card and decode it. */
    private fun decodeCard(html: String): Decoded {
        val marker = "data:image/png;base64,"
        val start = html.indexOf(marker)
        assertTrue(start >= 0, "the card must embed an inline PNG; got:\n$html")
        val from = start + marker.length
        // Whichever quote closes the attribute, not "a single quote if one exists anywhere later":
        // the card now contains single-quoted styles further down, so the old form ran past the
        // payload and swallowed half the table.
        val end = listOfNotNull(
            html.indexOf('\'', from).takeIf { it >= 0 },
            html.indexOf('"', from).takeIf { it >= 0 },
        ).minOrNull() ?: -1
        assertTrue(end > from, "the base64 payload must be delimited; got:\n${html.take(400)}")
        val bytes = Base64.getDecoder().decode(html.substring(from, end))

        // PNG magic, before trusting the decoder to tell us anything.
        assertEquals(0x89.toByte(), bytes[0], "PNG magic")
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])

        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertTrue(image != null, "ImageIO could not decode the card's PNG (${bytes.size} bytes)")
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        return Decoded(image.width, image.height, argb)
    }

    @Test
    fun theCardsPngIsTheDeclaredSizeWithTheDeclaredBackground() {
        val card = decodeCard(icosphereMesh(3).toHTML())
        assertEquals(MESH_THUMBNAIL_WIDTH, card.width, "the render is the width the card's <img> claims")
        assertEquals(MESH_THUMBNAIL_HEIGHT, card.height)

        // The four corners of a centred, framed mesh are background, and it is the colour the code says.
        for ((x, y) in listOf(
            0 to 0,
            card.width - 1 to 0,
            0 to card.height - 1,
            card.width - 1 to card.height - 1,
        )) {
            assertEquals(
                MESH_THUMBNAIL_BACKGROUND, card.at(x, y),
                "($x, $y) should be the declared background, not %08x".format(card.at(x, y)),
            )
        }
    }

    @Test
    fun theSilhouetteIsRealAndTheSurfaceIsLit() {
        val card = decodeCard(icosphereMesh(4).withColor(0.30, 0.62, 1.0).toHTML())
        val painted = card.paintedFraction(MESH_THUMBNAIL_BACKGROUND)

        // A sphere framed at 2.7 bounding-sphere radii fills a real part of the frame — and not all of it,
        // which would mean the camera was inside the mesh or the buffer was never cleared.
        assertTrue(painted > 0.15, "only %.1f%% of the frame was painted".format(painted * 100))
        assertTrue(painted < 0.85, "%.1f%% painted — the frame is not framing anything".format(painted * 100))

        // The middle of the frame is the front of the sphere: lit, and much brighter than the background.
        val cx = card.width / 2
        val cy = card.height / 2
        val backgroundLuminance = 0.2126 * 0x10 + 0.7152 * 0x13 + 0.0722 * 0x18
        assertTrue(
            card.luminance(cx, cy) > backgroundLuminance + 40,
            "the centre of a lit sphere should be bright, got %.1f against a background of %.1f"
                .format(card.luminance(cx, cy), backgroundLuminance),
        )

        // And the shading carries the mesh's own colour rather than replacing it: this sphere is blue.
        assertTrue(
            card.blue(cx, cy) > card.red(cx, cy) + 20,
            "a blue sphere should read blue at the centre: r=${card.red(cx, cy)} b=${card.blue(cx, cy)}",
        )

        // The top of a sphere lit from above is brighter than the bottom, so the render really is shaded
        // and not a flat silhouette fill.
        val quarter = card.height / 4
        assertTrue(
            card.luminance(cx, quarter) > card.luminance(cx, card.height - quarter) + 5,
            "lambert shading should make the top brighter: %.1f vs %.1f"
                .format(card.luminance(cx, quarter), card.luminance(cx, card.height - quarter)),
        )
    }

    @Test
    fun anOpenMeshWoundInsideOutLosesItsSurface() {
        // The card's documentation claims back faces are culled as a GPU would cull them, so that an
        // inside-out mesh shows it. For an open mesh that means the surface facing you simply is not there.
        val patch = planeMesh(6, 6)
        val outward = decodeCard(patch.toHTML()).paintedFraction(MESH_THUMBNAIL_BACKGROUND)
        val inverted = decodeCard(patch.flipped().toHTML()).paintedFraction(MESH_THUMBNAIL_BACKGROUND)
        assertTrue(outward > 0.10, "the patch should be plainly visible, painted %.3f".format(outward))
        assertTrue(
            inverted < outward * 0.4,
            "flipped, it should mostly vanish: %.3f against %.3f".format(inverted, outward),
        )
    }

    @Test
    fun aClosedMeshWoundInsideOutIsInvisibleInThePictureButNotInTheNumbers() {
        // The honest version of the claim above, which took a failing test to find. A closed mesh keeps its
        // whole silhouette when it is inverted, because culling the front faces only reveals the far inner
        // wall; and it keeps its shading too, because the rasterizer deliberately flips an inward normal
        // rather than painting it black (VisionGeometryPreview.shade). So the *picture* cannot tell you.
        val sphere = icosphereMesh(3)
        val outward = decodeCard(sphere.toHTML())
        val inverted = decodeCard(sphere.flipped().toHTML())
        val cx = outward.width / 2
        val cy = outward.height / 2
        assertEquals(
            outward.paintedFraction(MESH_THUMBNAIL_BACKGROUND),
            inverted.paintedFraction(MESH_THUMBNAIL_BACKGROUND),
            0.02,
            "a closed mesh keeps its silhouette when inverted",
        )
        assertEquals(
            outward.luminance(cx, cy), inverted.luminance(cx, cy), 12.0,
            "and its shading, because the rasterizer flips inward normals rather than going black",
        )

        // The numbers are where it shows: the card's volume is the *signed* volume.
        assertTrue(boxMesh().toHTML().contains("1.0000"), "an outward cube reports +1")
        val flipped = boxMesh().flipped().toHTML()
        assertTrue(flipped.contains("-1.0000"), "an inverted cube must report a negative volume: $flipped")
    }

    @Test
    fun theCardReportsTheNumbersItClaimsTo() {
        val cube = boxMesh().toHTML()
        assertTrue(cube.contains("watertight"), cube)
        assertTrue(cube.contains("χ=2"), "a closed genus-0 solid has chi 2: $cube")
        assertTrue(cube.contains("genus 0"), cube)
        assertTrue(cube.contains("6.0000"), "a unit cube has area 6: $cube")
        assertTrue(cube.contains("1.0000"), "and volume 1: $cube")
        assertTrue(cube.contains("8 verts · 12 tris"), cube)

        // A torus is genus 1 with chi 0, and an open patch reports its boundary rather than a volume.
        val torus = torusMesh(24, 12).toHTML()
        assertTrue(torus.contains("χ=0") && torus.contains("genus 1"), torus)
        val patch = planeMesh(4, 4).toHTML()
        assertTrue(patch.contains("boundary loop"), patch)
        assertTrue(!patch.contains("volume"), "an open mesh must not claim a volume: $patch")
    }

    @Test
    fun anEmptyMeshDegradesGracefullyRatherThanEmittingABrokenTag() {
        val html = TriMesh.empty.toHTML()
        assertTrue(html.contains("empty"), html)
        assertTrue(!html.contains("<img"), "no image tag at all beats an image tag with no image: $html")
        assertTrue(!html.contains("base64,\"") && !html.contains("base64,'"), "no empty payload: $html")
        // And a mesh with vertices but no triangles — a point cloud, which nothing can rasterise — takes
        // the same path rather than throwing.
        val pointsOnly = TriMeshBuilder().also { b -> repeat(4) { b.vertex(it.toFloat(), 0f, 0f) } }.build()
        assertTrue(pointsOnly.toHTML().contains("empty"), pointsOnly.toHTML())
    }

    @Test
    fun aHugeMeshRendersFromADecimationAndSaysSo() {
        // The cheapness claim: above 40k triangles the picture is a decimation, and the card admits it
        // while still reporting the real mesh's counts.
        val big = marchingCubes(sdfSphere(1.0), boundsAround(radius = 1.3), 128)
        assertTrue(big.triangleCount > 40_000, "expected a big marched sphere, got ${big.triangleCount}")
        val html = big.toHTML()
        assertTrue(html.contains("picture decimated to"), html.take(600))
        assertTrue(html.contains("${big.triangleCount} tris"), "the counts must still be the real ones")
        // And it still draws: the decimated sphere is a sphere.
        val card = decodeCard(html)
        assertTrue(
            card.paintedFraction(MESH_THUMBNAIL_BACKGROUND) > 0.15,
            "the decimated render should still fill the frame",
        )
    }

    @Test
    fun theVoronoiBridgeCardShowsAClosedCell() {
        // An end-to-end check that the fixed clipper, the bridge and the card agree: an interior cell of a
        // random lattice is a closed convex polyhedron, and its card says so and draws it.
        val random = kotlin.random.Random(31)
        val sites = List(40) {
            Vec3(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1)
        }
        val cell = voronoi3d(sites).cells.first { it.bounded }
        val html = cell.toTriMesh(shrink = 1.0, healToConvexHull = false).weld(1e-5).toHTML()
        assertTrue(html.contains("watertight") && html.contains("χ=2"), html.take(600))
        val card = decodeCard(html)
        assertTrue(card.paintedFraction(MESH_THUMBNAIL_BACKGROUND) > 0.05, "the cell should be visible")
    }
}

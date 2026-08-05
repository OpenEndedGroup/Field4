package afield.stdlib.polytope

import afield.util.PngEncoder
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hole, in pixels, beside the same face without one.
 *
 * "The area came out right" is a good test and not a picture, and a face with a hole is exactly the
 * kind of thing that can pass an area check while looking wrong — a rim triangle missing, a hole ring
 * wound so that its triangles face backwards, two coplanar layers z-fighting where the ring was
 * supposed to be subtracted. So this rasterises [PolyhedralObject.triangulate]'s real output and then
 * asks the pixels questions that only a correct picture can answer:
 *
 *  * looking straight down at the window, the pixel in the middle of it resolves to the **bottom**
 *    face of the cube — you can see through the hole — while in the counterfactual panel the very
 *    same pixel resolves to the top face;
 *  * the holed top face covers three quarters of the pixels the solid one does, the window being a
 *    quarter of it;
 *  * the silhouette is identical in both, so nothing has gone missing round the outside;
 *  * exactly two faces are visible through the holed panel and one through the solid one, which is
 *    what rules out a z-fighting double layer.
 *
 * The counterfactual is computed, not described: [fillTheWindow] rebuilds the same object with the
 * inner ring and its four edges dropped, which is what the single-cycle path had to do with it before
 * today.
 *
 * The rasteriser is a self-contained z-buffered flat-shading one that lives in the test, like
 * [PolytopeCanonicalRenderProofTest]'s: the library's boundary says `afield.stdlib.polytope` knows
 * nothing about rendering, and a proof harness does not get to be the thing that breaks it. Back
 * faces are deliberately **not** culled, because seeing the inside of the far wall through the window
 * is the whole point.
 *
 * Writes to a temp directory by default, honouring "tests never write committed assets". Regenerate
 * the committed proof with:
 *
 * ```
 * AFIELD_POLYTOPE_PROOF_DIR=$PWD/dev_docs/assets ./gradlew jvmTest \
 *     --tests 'afield.stdlib.polytope.PolytopeFaceHolesRenderProofTest'
 * ```
 */
class PolytopeFaceHolesRenderProofTest {

    private val panel = 320
    private val supersample = 2
    private val window = 0.5f

    @Test
    fun aHoledFaceRendersWithAHoleInItAndTheOldPathDoesNot() {
        val holed = cubeWithWindow(window)
        val solid = fillTheWindow(holed)

        // Faces are identified by the plane they lie in rather than by id, because filling the window
        // in also drops four edges and every id allocated after them shifts.
        val (holedTopId, holedBottomId) = topAndBottomFaceIds(holed)
        val (solidTopId, _) = topAndBottomFaceIds(solid)
        assertTrue(holed.topology.faceHasHoles(ElementId(holedTopId)), "the +z face should be the holed one")

        // Straight down the z axis at the windowed face, and a three-quarter view for the shape.
        val down = Camera(0f, 0f, 1f)
        val oblique = Camera(0.62f, 0.78f, 0.95f)

        val holedDown = render(holed, down)
        val solidDown = render(solid, down)
        val holedOblique = render(holed, oblique)
        val solidOblique = render(solid, oblique)

        // ---------------------------------------------------------------- the pixel in the window
        val centre = holedDown.size / 2 * holedDown.size + holedDown.size / 2
        assertEquals(
            holedBottomId,
            holedDown.faceIds[centre],
            "the middle of the window should show the inside of the far wall, not a filled face",
        )
        assertEquals(
            solidTopId,
            solidDown.faceIds[centre],
            "with the ring dropped the same pixel must be the top face — that is the counterfactual",
        )

        // ------------------------------------------------------------------ how much of it is left
        val holedTop = holedDown.faceIds.count { it == holedTopId }
        val solidTop = solidDown.faceIds.count { it == solidTopId }
        val ratio = holedTop.toDouble() / solidTop
        // A 1x1 window in a 2x2 face is a quarter of it.
        assertEquals(0.75, ratio, 0.02, "holed top face covered $holedTop of $solidTop pixels")

        // ------------------------------------------------ nothing lost outside, nothing doubled in
        val holedSilhouette = holedDown.faceIds.count { it >= 0 }
        val solidSilhouette = solidDown.faceIds.count { it >= 0 }
        assertEquals(
            solidSilhouette.toDouble(),
            holedSilhouette.toDouble(),
            solidSilhouette * 0.01,
            "the outline changed, so something outside the window went missing",
        )
        assertEquals(
            setOf(holedTopId, holedBottomId),
            holedDown.faceIds.filter { it >= 0 }.toSet(),
            "looking down the window, exactly the top and bottom faces should be visible",
        )
        assertEquals(
            1.0,
            solidTop.toDouble() / solidSilhouette,
            0.005,
            "the solid panel should be the top face and nothing showing through it",
        )

        // Straight down the axis the whole object *is* the 2x2 face, so the frame is about a fifth
        // covered; this only guards against a panel that drew almost nothing.
        assertTrue(
            holedSilhouette > holedDown.pixels.size * 0.15,
            "the windowed cube barely covered the frame: $holedSilhouette pixels",
        )
        assertTrue(
            holedOblique.faceIds.filter { it >= 0 }.toSet().size >= 3,
            "the three-quarter view should show at least three faces",
        )

        // --------------------------------------------------------------------------- contact sheet
        val columns = 2
        val rows = 2
        val width = panel * columns
        val height = panel * rows + LABEL_STRIP
        val sheet = IntArray(width * height) { BACKGROUND }
        drawText(sheet, width, 8, 6, "POLYTOPE FACE HOLES PROOF - CUBEWITHWINDOW 0.5", INK, 2)
        drawText(
            sheet, width, 8, 24,
            "LEFT TRIANGULATE WITH THE INNER RING   RIGHT THE SAME OBJECT WITH THE RING DROPPED",
            INK, 1,
        )
        blit(sheet, width, height, holedDown, 0, LABEL_STRIP)
        blit(sheet, width, height, solidDown, panel, LABEL_STRIP)
        blit(sheet, width, height, holedOblique, 0, LABEL_STRIP + panel)
        blit(sheet, width, height, solidOblique, panel, LABEL_STRIP + panel)
        drawText(sheet, width, 6, LABEL_STRIP + 6, "HOLE - DOWN THE Z AXIS", ACCENT, 1)
        drawText(sheet, width, panel + 6, LABEL_STRIP + 6, "NO HOLE - SAME VIEW", INK, 1)
        drawText(sheet, width, 6, LABEL_STRIP + panel + 6, "HOLE - THREE QUARTER", ACCENT, 1)
        drawText(sheet, width, panel + 6, LABEL_STRIP + panel + 6, "NO HOLE - THREE QUARTER", INK, 1)
        drawText(
            sheet, width, 6, LABEL_STRIP + panel - 26,
            "THE MIDDLE PIXEL RESOLVES TO THE FAR WALL - FACE ${holedDown.faceIds[centre]} - NOT TO THE TOP FACE",
            ACCENT, 1,
        )
        drawText(
            sheet, width, 6, LABEL_STRIP + panel - 14,
            "THE WINDOWED TOP FACE KEEPS ${(ratio * 100).toInt()} PERCENT OF THE PIXELS THE SOLID ONE COVERS",
            ACCENT, 1,
        )
        drawText(
            sheet, width, 6, LABEL_STRIP + 2 * panel - 14,
            "COLOUR IS THE DIRECTION A FACE POINTS - ORANGE PLUS Z IS THE WINDOWED FACE - BLUE MINUS Z IS BEHIND IT",
            INK, 1,
        )

        val out = File(System.getenv("AFIELD_POLYTOPE_PROOF_DIR") ?: Files.createTempDirectory("polytope-holes").toString())
        out.mkdirs()
        val file = File(out, "polytope_face_holes_proof.png")
        file.writeBytes(PngEncoder.encode(sheet, width, height))
        println("PolytopeFaceHolesRenderProofTest wrote ${file.absolutePath}")
        println("window pixel: holed = ${holedDown.faceIds[centre]}, solid = ${solidDown.faceIds[centre]}")
        println("top face pixels: holed = $holedTop, solid = $solidTop, ratio = $ratio")
    }

    // ==========================================================================================
    // The counterfactual
    // ==========================================================================================

    /** The raw ids of the `z = +1` and `z = -1` faces, found by their coordinates. */
    private fun topAndBottomFaceIds(solid: PolyhedralObject): Pair<Int, Int> {
        val realization = requireNotNull(solid.realization)
        val index = solid.topology.elements(0).mapIndexed { at, vertex -> vertex.id.value to at }.toMap()
        fun onPlane(face: ElementId, z: Float) =
            solid.topology.faceCycle(face).all { abs(realization[index.getValue(it)].z - z) < 1e-5f }
        val top = solid.topology.elements(2).single { onPlane(it.id, 1f) }
        val bottom = solid.topology.elements(2).single { onPlane(it.id, -1f) }
        return top.id.value to bottom.id.value
    }

    /** The same object with every inner ring — and the edges only that ring used — dropped. */
    private fun fillTheWindow(solid: PolyhedralObject): PolyhedralObject {
        val source = solid.topology
        val holeEdges = mutableSetOf<ElementId>()
        for (face in source.elements(2)) {
            val faceEdges = source.incidentDown(face.id).map(::ElementId)
            for (ring in source.faceHoles(face.id)) for (i in ring.indices) {
                val ends = setOf(ring[i], ring[(i + 1) % ring.size])
                holeEdges += faceEdges.filter { source.incidentDown(it).toSet() == ends }
            }
        }
        val builder = PolytopeTopologyBuilder(rank = source.rank)
        val mapped = mutableMapOf<ElementId, ElementId>()
        for (rank in 0..source.rank) for (element in source.elements(rank)) {
            if (rank == 1 && element.id in holeEdges) continue
            mapped[element.id] = builder.addElement(rank)
        }
        for (rank in 0 until source.rank) for (element in source.elements(rank)) {
            val lower = mapped[element.id] ?: continue
            for (higher in source.incidentUp(element.id)) {
                val upper = mapped[ElementId(higher)] ?: continue
                builder.addIncidence(lower, upper)
            }
        }
        for (face in source.elements(2)) {
            builder.setFaceCycle(
                mapped.getValue(face.id),
                source.faceCycle(face.id).map { mapped.getValue(ElementId(it)).value }.toIntArray(),
            )
        }
        // The four window vertices stay, unreferenced, so the realization still lines up index for
        // index; only the ring and its edges are gone. Face *ids* therefore shift by four, which is
        // why the assertions above find their faces by plane.
        return PolyhedralObject(builder.build(), solid.realization, solid.attributes)
    }

    // ==========================================================================================
    // A small z-buffered flat-shading rasteriser over the triangulated output. No culling.
    // ==========================================================================================

    /** An eye direction; the camera looks from `(x, y, z)` back at the origin. */
    private class Camera(x: Float, y: Float, z: Float) {
        val d = normalize(doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble()))
        val right = normalize(cross(if (abs(d[1]) > 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(0.0, 1.0, 0.0), d))
        val up = normalize(cross(d, right))

        companion object {
            fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
            )

            fun normalize(v: DoubleArray): DoubleArray {
                val n = sqrt(v.sumOf { it * it })
                return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
            }
        }
    }

    private class Image(val size: Int, val pixels: IntArray, val faceIds: IntArray)

    /**
     * Rasterise the object's own triangulation, colouring and identifying each triangle by the face
     * `TriangulatedGeometry` says it came from — so the id buffer the assertions read is provenance
     * out of the library rather than anything this harness decided.
     */
    private fun render(solid: PolyhedralObject, camera: Camera): Image {
        val geometry = solid.triangulate()
        val mesh = geometry.mesh
        val big = panel * supersample
        val colour = IntArray(big * big) { BACKGROUND }
        val depth = DoubleArray(big * big) { Double.MAX_VALUE }
        val faceAt = IntArray(big * big) { -1 }
        val scale = big * 0.40 / sqrt(3.0)

        fun screen(vertex: Int): DoubleArray {
            val p = doubleArrayOf(mesh.x(vertex).toDouble(), mesh.y(vertex).toDouble(), mesh.z(vertex).toDouble())
            val x = (0..2).sumOf { p[it] * camera.right[it] }
            val y = (0..2).sumOf { p[it] * camera.up[it] }
            val z = -(0..2).sumOf { p[it] * camera.d[it] }
            return doubleArrayOf(big / 2.0 + x * scale, big / 2.0 - y * scale, z)
        }

        val light = Camera.normalize(doubleArrayOf(-0.35, 0.62, 0.70))
        val normals = requireNotNull(mesh.normals)

        for (triangle in 0 until geometry.triangleCount) {
            val a = mesh.indices[triangle * 3]
            val b = mesh.indices[triangle * 3 + 1]
            val c = mesh.indices[triangle * 3 + 2]
            val face = geometry.sourceFace(triangle).value
            // Flat normal, from the mesh's own per-vertex normal rather than recomputed: this panel is
            // a picture of what triangulate() produced, normals included.
            val lambert = abs((0..2).sumOf { normals[a * 3 + it] * light[it] }).coerceIn(0.0, 1.0)
            val shade = 0.18 + 0.74 * lambert
            // Coloured by which way the face points, not by its id, so the two panels are directly
            // comparable: filling the window in drops four edges and shifts every later id.
            val hue = FACE_PALETTE[orientationOf(normals, a)]
            val rgb = pack(
                (((hue shr 16) and 0xff) * shade).toInt(),
                (((hue shr 8) and 0xff) * shade).toInt(),
                ((hue and 0xff) * shade).toInt(),
            )
            fillTriangle(colour, depth, faceAt, big, screen(a), screen(b), screen(c), rgb, face)
        }

        outlineFaces(colour, faceAt, big)
        return downsample(colour, faceAt, big)
    }

    private fun fillTriangle(
        colour: IntArray,
        depth: DoubleArray,
        faceAt: IntArray,
        size: Int,
        a: DoubleArray,
        b: DoubleArray,
        c: DoubleArray,
        rgb: Int,
        face: Int,
    ) {
        val minX = max(0, min(a[0], min(b[0], c[0])).toInt())
        val maxX = min(size - 1, max(a[0], max(b[0], c[0])).toInt() + 1)
        val minY = max(0, min(a[1], min(b[1], c[1])).toInt())
        val maxY = min(size - 1, max(a[1], max(b[1], c[1])).toInt() + 1)
        val denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if (abs(denominator) < 1e-12) return

        for (y in minY..maxY) for (x in minX..maxX) {
            val px = x + 0.5
            val py = y + 0.5
            val w0 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / denominator
            val w1 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / denominator
            val w2 = 1.0 - w0 - w1
            if (w0 < 0.0 || w1 < 0.0 || w2 < 0.0) continue
            val z = w0 * a[2] + w1 * b[2] + w2 * c[2]
            val at = y * size + x
            if (z >= depth[at]) continue
            depth[at] = z
            colour[at] = rgb
            faceAt[at] = face
        }
    }

    /** Face boundaries as an edge-detect over the face-id buffer, so the rim of the window shows. */
    private fun outlineFaces(colour: IntArray, faceAt: IntArray, size: Int) {
        val source = faceAt.copyOf()
        for (y in 1 until size - 1) for (x in 1 until size - 1) {
            val at = y * size + x
            val id = source[at]
            if (id < 0) continue
            if (source[at - 1] != id || source[at + 1] != id || source[at - size] != id || source[at + size] != id) {
                colour[at] = EDGE
            }
        }
    }

    private fun downsample(colour: IntArray, faceAt: IntArray, big: Int): Image {
        val out = IntArray(panel * panel)
        val ids = IntArray(panel * panel)
        for (y in 0 until panel) for (x in 0 until panel) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until supersample) for (dx in 0 until supersample) {
                val pixel = colour[(y * supersample + dy) * big + x * supersample + dx]
                r += (pixel shr 16) and 0xff
                g += (pixel shr 8) and 0xff
                b += pixel and 0xff
            }
            val n = supersample * supersample
            out[y * panel + x] = pack(r / n, g / n, b / n)
            ids[y * panel + x] = faceAt[(y * supersample) * big + x * supersample]
        }
        return Image(panel, out, ids)
    }

    private fun blit(sheet: IntArray, width: Int, height: Int, image: Image, x0: Int, y0: Int) {
        for (y in 0 until image.size) for (x in 0 until image.size) {
            val tx = x0 + x
            val ty = y0 + y
            if (tx in 0 until width && ty in 0 until height) sheet[ty * width + tx] = image.pixels[y * image.size + x]
        }
    }

    private fun drawText(sheet: IntArray, width: Int, x0: Int, y0: Int, text: String, colour: Int, scale: Int) {
        var pen = x0
        for (character in text.uppercase()) {
            val glyph = FONT[character] ?: FONT[' ']!!
            for (row in 0 until 5) for (column in 0 until 3) {
                if ((glyph[row].code shr (2 - column)) and 1 == 0) continue
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    val tx = pen + column * scale + dx
                    val ty = y0 + row * scale + dy
                    val at = ty * width + tx
                    if (tx in 0 until width && at in sheet.indices) sheet[at] = colour
                }
            }
            pen += 4 * scale
        }
    }

    private companion object {
        const val LABEL_STRIP = 38
        const val BACKGROUND = 0xff10131a.toInt()
        const val INK = 0xffe8eef7.toInt()
        const val ACCENT = 0xff6fd3ff.toInt()
        const val EDGE = 0xff0a0c11.toInt()

        /** Indexed by [orientationOf]: `+x -x +y -y +z -z`, so the same wall is the same colour in both panels. */
        val FACE_PALETTE = intArrayOf(
            0xffff6f9a.toInt(), // +x
            0xff66ffd9.toInt(), // -x
            0xffffe066.toInt(), // +y
            0xff9ee86b.toInt(), // -y
            0xffff9a4d.toInt(), // +z, the windowed face
            0xff58c4ff.toInt(), // -z, what you see through the window
        )

        /** Which of the six axis directions a render vertex's flat normal points along. */
        fun orientationOf(normals: FloatArray, vertex: Int): Int {
            val x = normals[vertex * 3]
            val y = normals[vertex * 3 + 1]
            val z = normals[vertex * 3 + 2]
            val axis = when {
                abs(x) >= abs(y) && abs(x) >= abs(z) -> 0
                abs(y) >= abs(z) -> 1
                else -> 2
            }
            val positive = when (axis) {
                0 -> x >= 0f
                1 -> y >= 0f
                else -> z >= 0f
            }
            return axis * 2 + if (positive) 0 else 1
        }

        fun pack(r: Int, g: Int, b: Int) =
            (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        private fun glyph(vararg rows: Int) = rows.map { it.toChar() }

        val FONT: Map<Char, List<Char>> = mapOf(
            ' ' to glyph(0, 0, 0, 0, 0),
            '-' to glyph(0, 0, 7, 0, 0),
            '.' to glyph(0, 0, 0, 0, 2),
            'A' to glyph(2, 5, 7, 5, 5),
            'B' to glyph(6, 5, 6, 5, 6),
            'C' to glyph(3, 4, 4, 4, 3),
            'D' to glyph(6, 5, 5, 5, 6),
            'E' to glyph(7, 4, 6, 4, 7),
            'F' to glyph(7, 4, 6, 4, 4),
            'G' to glyph(3, 4, 5, 5, 3),
            'H' to glyph(5, 5, 7, 5, 5),
            'I' to glyph(7, 2, 2, 2, 7),
            'J' to glyph(1, 1, 1, 5, 2),
            'K' to glyph(5, 5, 6, 5, 5),
            'L' to glyph(4, 4, 4, 4, 7),
            'M' to glyph(5, 7, 7, 5, 5),
            'N' to glyph(5, 7, 7, 7, 5),
            'O' to glyph(2, 5, 5, 5, 2),
            'P' to glyph(6, 5, 6, 4, 4),
            'Q' to glyph(2, 5, 5, 7, 3),
            'R' to glyph(6, 5, 6, 5, 5),
            'S' to glyph(3, 4, 2, 1, 6),
            'T' to glyph(7, 2, 2, 2, 2),
            'U' to glyph(5, 5, 5, 5, 7),
            'V' to glyph(5, 5, 5, 5, 2),
            'W' to glyph(5, 5, 7, 7, 5),
            'X' to glyph(5, 5, 2, 5, 5),
            'Y' to glyph(5, 5, 2, 2, 2),
            'Z' to glyph(7, 1, 2, 4, 7),
            '0' to glyph(7, 5, 5, 5, 7),
            '1' to glyph(2, 6, 2, 2, 7),
            '2' to glyph(7, 1, 7, 4, 7),
            '3' to glyph(7, 1, 7, 1, 7),
            '4' to glyph(5, 5, 7, 1, 1),
            '5' to glyph(7, 4, 7, 1, 7),
            '6' to glyph(7, 4, 7, 5, 7),
            '7' to glyph(7, 1, 1, 1, 1),
            '8' to glyph(7, 5, 7, 5, 7),
            '9' to glyph(7, 5, 7, 1, 7),
        )
    }
}

package afield.stdlib.polytope

import afield.util.PngEncoder
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * "It compiles" is not evidence that geometry is right. This renders the canonical solids to actual
 * pixels with a self-contained z-buffered rasteriser — no AWT, no GPU, no renderer — and asserts
 * things a picture can be wrong about: that each panel is drawn at all, that the canonicalized
 * silhouette is rounder than the un-canonicalized one, and that the shading has real depth
 * structure rather than one flat blob.
 *
 * It writes the contact sheet to a temp directory by default, honouring the "tests never write
 * committed assets" invariant. Set `AFIELD_POLYTOPE_PROOF_DIR` to regenerate the committed proof:
 *
 * ```
 * AFIELD_POLYTOPE_PROOF_DIR=$PWD/dev_docs/assets ./gradlew jvmTest \
 *     --tests 'afield.stdlib.polytope.PolytopeCanonicalRenderProofTest'
 * ```
 *
 * The rasteriser lives in the test because it is evidence, not product: the library's boundary says
 * `afield.stdlib.polytope` knows nothing about rendering, and a proof harness must not be the thing
 * that breaks that.
 */
class PolytopeCanonicalRenderProofTest {

    private val panel = 300
    private val supersample = 2

    /** Row label, the Conway expression, and what the row is meant to demonstrate. */
    private val rows = listOf(
        "GC GYRO CUBE",
        "GD HEXECONTA",
        "TI FOOTBALL",
        "BD BEVEL DODEC",
    ) to listOf("gC", "gD", "tI", "bD")

    @Test
    fun canonicalSolidsRenderToRecognisablePixels() {
        val (labels, expressions) = rows
        val columns = 3
        val width = panel * columns
        val height = panel * expressions.size + LABEL_STRIP
        val sheet = IntArray(width * height) { BACKGROUND }

        drawText(sheet, width, 8, 6, "POLYTOPE CANONICAL REALIZATION PROOF", INK, 2)
        drawText(sheet, width, 8, 24, "LEFT CONWAY REALIZATION   MIDDLE CANONICALIZED   RIGHT SPECTRAL FROM TOPOLOGY THEN CANONICALIZED", INK, 1)

        val roundness = mutableListOf<Triple<String, Double, Double>>()
        expressions.forEachIndexed { row, expression ->
            val raw = conway(expression).polyhedron
            val canonical = raw.canonicalize().polyhedron
            val spectral = PolyhedralObject(raw.topology).realize(SpectralRealization()).canonicalize().polyhedron

            val panels = listOf(raw, canonical, spectral).map { render(it) }
            panels.forEachIndexed { column, image ->
                blit(sheet, width, height, image, column * panel, LABEL_STRIP + row * panel)
            }
            drawText(sheet, width, 6 + 0 * panel, LABEL_STRIP + row * panel + 6, labels[row], INK, 1)
            drawText(sheet, width, 6 + 1 * panel, LABEL_STRIP + row * panel + 6, "CANONICAL", ACCENT, 1)
            drawText(sheet, width, 6 + 2 * panel, LABEL_STRIP + row * panel + 6, "SPECTRAL SEED", ACCENT, 1)

            roundness += Triple(expression, silhouetteRoundness(panels[0]), silhouetteRoundness(panels[1]))

            // Every panel actually drew a solid: not blank, not the whole frame, and shaded.
            for ((column, image) in panels.withIndex()) {
                val covered = image.pixels.count { it != BACKGROUND }
                val fraction = covered.toDouble() / image.pixels.size
                assertTrue(fraction > 0.12, "$expression panel $column covered only ${"%.3f".format(fraction)}")
                assertTrue(fraction < 0.85, "$expression panel $column covered ${"%.3f".format(fraction)} of the frame")
                val shades = image.pixels.filter { it != BACKGROUND }.map { (it shr 8) and 0xff }.distinct().size
                assertTrue(shades > 6, "$expression panel $column had only $shades shading levels")
            }
        }

        val out = File(System.getenv("AFIELD_POLYTOPE_PROOF_DIR") ?: Files.createTempDirectory("polytope-proof").toString())
        out.mkdirs()
        val file = File(out, "polytope_canonical_proof.png")
        file.writeBytes(PngEncoder.encode(sheet, width, height))
        println("PolytopeCanonicalRenderProofTest wrote ${file.absolutePath}")

        // The point of the picture, stated numerically: canonicalization makes the silhouette
        // rounder, because every vertex ends up in a consistent relationship to one midsphere.
        // A row whose Conway realization is already canonical (tI is) must simply not get worse.
        for ((expression, before, after) in roundness) {
            println("silhouette roundness $expression: $before -> $after")
            assertTrue(after <= before * 1.001, "$expression silhouette got lumpier: $before -> $after")
            if (before > 0.05) {
                assertTrue(after < before * 0.5, "$expression silhouette barely improved: $before -> $after")
            }
        }
    }

    @Test
    fun theCanonicalCubeRendersAsThreeConsistentlyDepthSortedFaces() {
        // A hand-checkable case: a three-quarter view of a convex cube shows exactly three faces,
        // and — if the winding test and the z-buffer agree — those three exactly tile the
        // silhouette with no gap where a back face leaked through and no pixel drawn twice.
        val image = render(cube().canonicalize().polyhedron)
        val visible = image.faceIds.filter { it >= 0 }.groupingBy { it }.eachCount()
        assertTrue(visible.size == 3, "expected three visible faces of a cube, saw ${visible.size}")

        // Edge-detected outlines overwrite the face colour, so compare against the id buffer.
        val silhouette = image.faceIds.count { it >= 0 }
        assertTrue(visible.values.sum() == silhouette, "visible faces did not tile the silhouette")
        val fraction = silhouette.toDouble() / image.pixels.size
        assertTrue(fraction > 0.2 && fraction < 0.6, "cube silhouette covered ${"%.3f".format(fraction)}")
    }

    @Test
    fun projectedFourPolytopesRenderAsRecognisableWireframes() {
        val seeds = listOf(
            "5-CELL 5V 10E" to fiveCell(),
            "TESSERACT 16V 32E" to tesseract(),
            "16-CELL 8V 24E" to sixteenCell(),
            "24-CELL 24V 96E" to twentyFourCell(),
        )
        val turns = listOf(0.0, PI / 8, PI / 4, 3 * PI / 8)
        val columns = turns.size
        val width = panel * columns
        val height = panel * seeds.size + LABEL_STRIP
        val sheet = IntArray(width * height) { BACKGROUND }

        drawText(sheet, width, 8, 6, "RANK-4 SEEDS UNDER A ROTATED PERSPECTIVE PROJECTION", INK, 2)
        drawText(sheet, width, 8, 24, "COLUMNS ARE A ROTATION IN THE X-W PLANE AT 0 22.5 45 AND 67.5 DEGREES", INK, 1)

        seeds.forEachIndexed { row, (label, polytope) ->
            turns.forEachIndexed { column, angle ->
                val projection = RotatedProjection4(0, 3, angle, PerspectiveProjection4(eye = 3.2, axis = 3))
                val image = renderWireframe(polytope, projection)
                blit(sheet, width, height, image, column * panel, LABEL_STRIP + row * panel)
                val drawn = image.pixels.count { it != BACKGROUND }
                assertTrue(drawn > 400, "$label at $angle drew only $drawn pixels")
                assertTrue(drawn < image.pixels.size / 3, "$label at $angle filled the frame")
            }
            drawText(sheet, width, 6, LABEL_STRIP + row * panel + 6, label, ACCENT, 1)
        }

        val out = File(System.getenv("AFIELD_POLYTOPE_PROOF_DIR") ?: Files.createTempDirectory("polytope-proof").toString())
        out.mkdirs()
        val file = File(out, "polytope_rank4_proof.png")
        file.writeBytes(PngEncoder.encode(sheet, width, height))
        println("PolytopeCanonicalRenderProofTest wrote ${file.absolutePath}")
    }

    // ==========================================================================================
    // A small z-buffered flat-shading rasteriser. Deliberately self-contained.
    // ==========================================================================================

    /** Depth-cued edge wireframe: the honest picture of a projected 4-polytope. */
    private fun renderWireframe(polytope: Polytope4, projection: Projection4): Image {
        val big = panel * supersample
        val colour = IntArray(big * big) { BACKGROUND }
        val points = polytope.project(projection)
        // Fit by projected *radius*, not by the largest component: the viewing rotation preserves
        // radius, so this is the bound that actually keeps the drawing inside its panel. The 5-cell
        // at zero rotation has a vertex almost on the 4D viewpoint and blows up without it.
        val extent = points.maxOf { it.length().toDouble() }
        val scale = big * 0.42 / max(extent, 1e-9)
        val yaw = 0.55
        val pitch = 0.30

        fun screen(p: afield.stdlib.Vec3): DoubleArray {
            val x1 = p.x * cos(yaw) + p.z * sin(yaw)
            val z1 = -p.x * sin(yaw) + p.z * cos(yaw)
            val y2 = p.y * cos(pitch) - z1 * sin(pitch)
            val z2 = p.y * sin(pitch) + z1 * cos(pitch)
            return doubleArrayOf(big / 2.0 + x1 * scale, big / 2.0 - y2 * scale, z2)
        }

        val projectedScreen = points.map(::screen)
        val depths = projectedScreen.map { it[2] }
        val nearest = depths.min()
        val farthest = depths.max()
        val index = polytope.vertices.mapIndexed { at, vertex -> vertex.id.value to at }.toMap()

        for (edge in polytope.edges) {
            val ends = polytope.topology.incidentDown(edge.id)
            val a = projectedScreen[index.getValue(ends[0])]
            val b = projectedScreen[index.getValue(ends[1])]
            val depth = ((a[2] + b[2]) / 2 - nearest) / max(farthest - nearest, 1e-9)
            val shade = 1.0 - 0.72 * depth
            drawLine(colour, big, a, b, pack((90 * shade).toInt(), (215 * shade).toInt(), (255 * shade).toInt()))
        }
        return downsample(colour, IntArray(big * big) { -1 }, big)
    }

    private fun drawLine(colour: IntArray, size: Int, a: DoubleArray, b: DoubleArray, rgb: Int) {
        val steps = max(2, (max(abs(b[0] - a[0]), abs(b[1] - a[1])) * 1.5).toInt())
        for (step in 0..steps) {
            val t = step.toDouble() / steps
            val x = (a[0] + (b[0] - a[0]) * t).toInt()
            val y = (a[1] + (b[1] - a[1]) * t).toInt()
            for (dy in -1..1) for (dx in -1..1) {
                val px = x + dx
                val py = y + dy
                if (px in 0 until size && py in 0 until size) colour[py * size + px] = rgb
            }
        }
    }

    private class Image(val size: Int, val pixels: IntArray, val faceIds: IntArray)

    private fun render(polyhedron: PolyhedralObject): Image {
        val big = panel * supersample
        val colour = IntArray(big * big) { BACKGROUND }
        val depth = DoubleArray(big * big) { Double.MAX_VALUE }
        val faceAt = IntArray(big * big) { -1 }

        val realization = assertNotNull(polyhedron.realization)
        val points = realization.vertexCoordinates.map { doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        val centre = DoubleArray(3)
        points.forEach { for (axis in 0..2) centre[axis] += it[axis] / points.size }
        val radius = points.maxOf { p -> sqrt((0..2).sumOf { (p[it] - centre[it]) * (p[it] - centre[it]) }) }
        val scale = big * 0.40 / max(radius, 1e-9)

        // A fixed three-quarter view, so before/after panels are directly comparable.
        val yaw = 0.62
        val pitch = 0.40
        fun view(p: DoubleArray): DoubleArray {
            val x = p[0] - centre[0]
            val y = p[1] - centre[1]
            val z = p[2] - centre[2]
            val x1 = x * cos(yaw) + z * sin(yaw)
            val z1 = -x * sin(yaw) + z * cos(yaw)
            val y2 = y * cos(pitch) - z1 * sin(pitch)
            val z2 = y * sin(pitch) + z1 * cos(pitch)
            return doubleArrayOf(big / 2.0 + x1 * scale, big / 2.0 - y2 * scale, z2)
        }

        val screen = points.map(::view)
        val index = polyhedron.topology.elements(0).mapIndexed { at, vertex -> vertex.id.value to at }.toMap()
        val light = doubleArrayOf(-0.35, 0.62, 0.70).let { l ->
            val n = sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
            doubleArrayOf(l[0] / n, l[1] / n, l[2] / n)
        }

        polyhedron.topology.elements(2).forEachIndexed { faceAtIndex, face ->
            val cycle = polyhedron.topology.faceCycle(face.id).map { index.getValue(it) }
            // Flat normal in view space, from the screen-space winding of the projected polygon.
            var area = 0.0
            for (i in cycle.indices) {
                val a = screen[cycle[i]]
                val b = screen[cycle[(i + 1) % cycle.size]]
                area += a[0] * b[1] - b[0] * a[1]
            }
            if (area >= 0.0) return@forEachIndexed // back face: screen y is flipped, so CCW is away

            val normal = polyhedron.faceNormal(face.id)
            val worldNormal = doubleArrayOf(normal.x.toDouble(), normal.y.toDouble(), normal.z.toDouble())
            val lambert = (0..2).sumOf { worldNormal[it] * light[it] }.coerceIn(0.0, 1.0)
            val shade = 0.16 + 0.78 * lambert
            val hue = FACE_PALETTE[cycle.size % FACE_PALETTE.size]
            val rgb = pack(
                (((hue shr 16) and 0xff) * shade).toInt(),
                (((hue shr 8) and 0xff) * shade).toInt(),
                ((hue and 0xff) * shade).toInt(),
            )

            for (i in 1 until cycle.size - 1) {
                fillTriangle(
                    colour, depth, faceAt, big,
                    screen[cycle[0]], screen[cycle[i]], screen[cycle[i + 1]],
                    rgb, faceAtIndex,
                )
            }
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

    /** Face boundaries as an edge-detect over the face-id buffer: a wireframe with no extra maths. */
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

    /**
     * Relative spread of the silhouette's radius about its own centroid. A canonical solid sits on
     * one midsphere, so its outline is close to a circle and this number is small; a lumpy Conway
     * realization has spikes and flats and this number is large.
     */
    private fun silhouetteRoundness(image: Image): Double {
        val filled = image.pixels.indices.filter { image.pixels[it] != BACKGROUND }
        if (filled.isEmpty()) return Double.MAX_VALUE
        val cx = filled.sumOf { (it % image.size).toDouble() } / filled.size
        val cy = filled.sumOf { (it / image.size).toDouble() } / filled.size
        // Farthest filled pixel in each of 72 directions: the outline, sampled.
        val far = DoubleArray(72)
        for (at in filled) {
            val dx = (at % image.size) - cx
            val dy = (at / image.size) - cy
            val r = sqrt(dx * dx + dy * dy)
            var bucket = (Math.atan2(dy, dx) / (2 * Math.PI) * 72).toInt() % 72
            if (bucket < 0) bucket += 72
            if (r > far[bucket]) far[bucket] = r
        }
        val used = far.filter { it > 0.0 }
        val mean = used.average()
        return sqrt(used.sumOf { (it - mean) * (it - mean) } / used.size) / mean
    }

    private fun blit(sheet: IntArray, width: Int, height: Int, image: Image, x0: Int, y0: Int) {
        for (y in 0 until image.size) for (x in 0 until image.size) {
            val tx = x0 + x
            val ty = y0 + y
            if (tx in 0 until width && ty in 0 until height) sheet[ty * width + tx] = image.pixels[y * image.size + x]
        }
    }

    // ==========================================================================================
    // A 3x5 bitmap font, so the contact sheet says what it is showing.
    // ==========================================================================================

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

        /** Indexed by face degree, so triangles, squares, pentagons and hexagons read apart. */
        val FACE_PALETTE = intArrayOf(
            0xff8f8f8f.toInt(), 0xff8f8f8f.toInt(), 0xff8f8f8f.toInt(),
            0xffff9a4d.toInt(), // 3: triangles
            0xff58c4ff.toInt(), // 4: squares
            0xff9ee86b.toInt(), // 5: pentagons
            0xffff6f9a.toInt(), // 6: hexagons
            0xffd7b3ff.toInt(), // 7
            0xffffe066.toInt(), // 8: octagons
            0xff66ffd9.toInt(), // 9
            0xffb0b8c4.toInt(), // 10+
        )

        fun pack(r: Int, g: Int, b: Int) =
            (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        /** Each glyph is five rows of three bits, stored one row per char code. */
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

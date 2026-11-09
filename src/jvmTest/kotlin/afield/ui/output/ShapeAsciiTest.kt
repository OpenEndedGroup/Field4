package afield.ui.output

import afield.document.graph.Box
import afield.examples.four.ShapeAsciiGpuSmoke
import afield.graphics.shader.Introspection
import afield.graphics.shader.ShaderKind
import afield.media.image.Img
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shape-aware ASCII matcher: glyph vectors, the cache, the contrast steps and edge choice. */
class ShapeAsciiTest {
    private val ascii = ShapeAscii()

    /** Black stays a space and white becomes a dense glyph, in both modes. */
    @Test
    fun blackIsSpaceWhiteIsDense() {
        val black = Img.zeros(64, 64)
        val white = Img.filled(64, 64, 1, 1f)
        for (shape in listOf(true, false)) {
            assertEquals(' ', ascii.convert(black, 4, shape = shape).char(0, 0))
            val c = ascii.convert(white, 4, shape = shape).char(0, 0)
            assertTrue(ascii.glyphCoverage(ascii.glyphs.indexOf(c)) > 0.5, "white gave '$c' (shape=$shape)")
        }
    }

    /** Each glyph's own raster vector maps back to a glyph with the same vector. */
    @Test
    fun glyphVectorsRoundTrip() {
        for (i in ascii.glyphs.indices) {
            val j = ascii.bruteNearest(ascii.glyphVector(i))
            assertTrue(ascii.glyphVector(j).contentEquals(ascii.glyphVector(i)), "'${ascii.glyphs[i]}' -> '${ascii.glyphs[j]}'")
        }
    }

    /** A light upper half picks a top-heavy glyph in shape mode, where brightness picks a mid-ramp one. */
    @Test
    fun shapeSeesWhereInkIs() {
        val cw = ascii.cellWidth; val ch = ascii.cellHeight
        val top = Img.generate(cw, ch) { _, y -> if (y < ch / 2) 1f else 0f }
        val c = ascii.convert(top, 1, rows = 1).char(0, 0)
        val v = ascii.glyphVector(ascii.glyphs.indexOf(c))
        assertTrue(v[0] + v[1] > v[4] + v[5], "top-half cell gave '$c' ${v.toList()}")
        val bottom = Img.generate(cw, ch) { _, y -> if (y >= ch / 2) 1f else 0f }
        assertTrue(ascii.convert(bottom, 1, rows = 1).char(0, 0) != c)
    }

    /** The contrast steps leave the largest component alone and push the others down. */
    @Test
    fun contrastSharpens() {
        val v = doubleArrayOf(0.8, 0.4, 0.4, 0.2, 0.1, 0.0)
        ascii.enhance(v, DoubleArray(6), contrast = 2.0, directional = 1.0)
        assertEquals(0.8, v[0], 1e-9)
        assertEquals(0.2, v[1], 1e-9)
        val d = doubleArrayOf(0.5, 0.5, 0.5, 0.5, 0.5, 0.5)
        ascii.enhance(d, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0), contrast = 1.0, directional = 2.0)
        assertEquals(0.25, d[0], 1e-9)
        assertEquals(0.5, d[1], 1e-9)
    }

    /** The quantised cache agrees with the exhaustive search on most random vectors. */
    @Test
    fun cacheMostlyAgrees() {
        val r = java.util.Random(1)
        var agree = 0
        repeat(500) {
            val v = DoubleArray(6) { r.nextDouble() }
            if (ascii.nearestShape(v) == ascii.bruteNearest(v)) agree++
        }
        assertTrue(agree > 400, "cache agreed on $agree/500")
    }

    /** Six inner circles inside the cell, six outer ones pushed outwards, all of one radius. */
    @Test
    fun samplingCirclesSitInsideThenOutside() {
        val circles = ascii.samplingCircles()
        assertEquals(12, circles.size)
        val cx = ascii.cellWidth / 2.0
        val cy = ascii.cellHeight / 2.0
        val r = circles[0].third
        assertTrue(circles.all { it.third == r }, "one radius for every circle")
        for (k in 0 until 6) {
            val (ix, iy, _) = circles[k]
            val (ox, oy, _) = circles[k + 6]
            assertTrue(ix in 0.0..ascii.cellWidth.toDouble() && iy in 0.0..ascii.cellHeight.toDouble(), "inner $k inside the cell")
            assertTrue(Math.hypot(ox - cx, oy - cy) > Math.hypot(ix - cx, iy - cy), "outer $k further from the centre than inner $k")
        }
    }

    /** drawAscii writes each matched character into the grid at its own cell. */
    @Test
    fun drawAsciiWritesTheFrameIntoAGrid() {
        val img = Img.generate(4 * ascii.cellWidth, 2 * ascii.cellHeight) { x, _ -> if (x < 2 * ascii.cellWidth) 1f else 0f }
        val frame = ascii.convert(img, 4, rows = 2)
        val grid = CharGridBuffer(frame.cols, frame.rows, ascii.glyphs)
        CharGridFrame(grid, CharGridSounds(emptyList(), null, cells = grid.cols * grid.rows) { _, _ -> }).drawAscii(frame)
        for (y in 0 until frame.rows) for (x in 0 until frame.cols)
            assertEquals(grid.glyphIndex(frame.char(x, y).toString()), grid.get(x, y), "cell ($x, $y)")
        assertEquals(' ', frame.char(3, 0), "the black half stays blank")
    }

    /**
     * `exact` is the exhaustive search: on every glyph's own raster as a one-cell image it agrees with
     * a fresh converter, whose empty cache has to search too, and over a whole picture it can differ
     * from the cached default.
     */
    @Test
    fun exactConvertIsTheExhaustiveSearch() {
        for (i in ascii.glyphs.indices) {
            val cell = ascii.glyphImg(i)
            val fresh = ShapeAscii().convert(cell, 1, rows = 1, contrast = 2.0)
            val exact = ascii.convert(cell, 1, rows = 1, contrast = 2.0, exact = true)
            assertEquals(fresh.char(0, 0), exact.char(0, 0), "'${ascii.glyphs[i]}'")
        }
        val r = java.util.Random(3)
        val noise = Img.generate(40 * ascii.cellWidth, 20 * ascii.cellHeight) { _, _ -> r.nextFloat() }
        val exact = ascii.convert(noise, 40, rows = 20, exact = true)
        val cached = ascii.convert(noise, 40, rows = 20)
        assertEquals(1.0, exact.agreement(exact))
        assertTrue(exact.agreement(cached) > 0.8, "cache and exhaustive search agree on ${exact.agreement(cached)} of noise cells")
    }

    /** Each circle's pixel list is what samplingCircles describes: centres inside its radius, about pi r^2 of them. */
    @Test
    fun circlePixelsFillTheirCircles() {
        val circles = ascii.samplingCircles()
        for (k in 0 until 12) {
            val (cx, cy, r) = circles[k]
            val px = ascii.circlePixels(k)
            val count = px.size / 2
            for (i in px.indices step 2)
                assertTrue(Math.hypot(px[i] + 0.5 - cx, px[i + 1] + 0.5 - cy) <= r + 1e-9, "circle $k pixel (${px[i]}, ${px[i + 1]})")
            assertTrue(count > 0.6 * Math.PI * r * r && count < 1.6 * Math.PI * r * r, "circle $k has $count pixels for r = $r")
        }
    }

    /** agreement is the share of identical cells. */
    @Test
    fun agreementCountsIdenticalCells() {
        val left = Img.generate(4 * ascii.cellWidth, ascii.cellHeight) { x, _ -> if (x < 2 * ascii.cellWidth) 1f else 0f }
        val dark = Img.zeros(4 * ascii.cellWidth, ascii.cellHeight)
        val a = ascii.convert(left, 4, rows = 1)
        assertEquals(0.5, a.agreement(ascii.convert(dark, 4, rows = 1)), 1e-12)
    }

    /**
     * The GPU boxes' shaders in the starting-point transcript compile, and declare the push constants,
     * samplers and inputs their box code binds by name. A rename on either side would otherwise show
     * only on a machine with a GPU, and `ascii.shape-gpu.smoke` reads these same shaders.
     */
    @Test
    fun gpuBoxShadersCompileAndDeclareWhatTheirCodeBinds() {
        fun introspect(box: String, property: String, kind: ShaderKind): Introspection {
            val i = Introspection(ShapeAsciiGpuSmoke.boxProperties(box).getValue(property), kind, sourceName = "$box/$property")
            assertTrue(i.valid, "$box/$property did not compile")
            return i
        }
        fun push(i: Introspection) = i.pushConstants.flatMap { b -> b.members.map { it.name } }.toSet()
        val knot = introspect("ascii_shape_gpu_scene", "vertex", ShaderKind.VERTEX_SHADER)
        assertEquals(setOf("pos", "normal"), knot.inputs.map { it.name }.toSet())
        assertEquals(setOf("time", "aspect"), push(knot))
        introspect("ascii_shape_gpu_scene", "fragment", ShaderKind.FRAGMENT_SHADER)
        for (box in listOf("ascii_shape_gpu_match", "ascii_shape_gpu_draw"))
            assertEquals(listOf("pos"), introspect(box, "vertex", ShaderKind.VERTEX_SHADER).inputs.map { it.name })
        val match = introspect("ascii_shape_gpu_match", "fragment", ShaderKind.FRAGMENT_SHADER)
        assertEquals(setOf("cell", "contrast", "directional"), push(match))
        assertEquals(setOf("sceneTex", "glyphVectors"), match.textures.map { it.name }.toSet())
        val draw = introspect("ascii_shape_gpu_draw", "fragment", ShaderKind.FRAGMENT_SHADER)
        assertEquals(setOf("cell", "ink", "background"), push(draw))
        assertEquals(setOf("cellsTex", "atlasTex", "sceneTex"), draw.textures.map { it.name }.toSet())
    }

    /** showAscii puts every panel up as one display payload on the asked channel. */
    @Test
    fun showAsciiEmitsOnePayload() {
        val box = Box("shape-ascii-test")
        val seen = mutableListOf<Pair<String, Any?>>()
        box.output.displayProxy = { channel, content -> seen += channel to content }
        val img = Img.generate(64, 64) { x, y -> if ((x - 32) * (x - 32) + (y - 32) * (y - 32) < 400) 1f else 0f }
        box.showAscii("brightness" to ascii.convert(img, 8, shape = false), "shape" to ascii.convert(img, 8), channel = "ascii")
        assertEquals(1, seen.size, "one payload for both panels")
        assertEquals("ascii", seen[0].first)
    }
}

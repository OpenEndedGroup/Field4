package afield.stacksl.cpu

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackSLIntervalTest {

    private val root = File(System.getProperty("user.dir"))

    private fun still(program: String) = StackSLCpu.expandStackAuto(StackSLCpu.stillTemplate(), program)

    /**
     * **The contract**, over the whole gallery: for random rectangles of a 64×64 render, every pixel
     * the `Float` reference path produces lies inside the rectangle's interval bound. Programs the
     * interval rules cannot follow are refused, and counted by reason, never bounded by a guess.
     */
    @Test
    fun everyGalleryPixelLiesInsideItsRegionBound() {
        val dim = 64
        val r = Random(20261001)
        var regions = 0
        var pixels = 0
        var violations = 0
        var first: String? = null
        val refusals = sortedMapOf<String, MutableList<String>>()
        val certifiedPrograms = mutableListOf<String>()
        for (entry in StackSLCpuGallery.load(root).filter { !it.feedback }) {
            val uniforms = if (entry.animate) mapOf("pc.params" to GlslValue(GlslType.VEC4, FloatArray(4))) else emptyMap()
            val glsl = StackSLCpu.expandStackAuto(entry.fragmentTemplate, entry.program)
            val raster = StackSLCpu.renderFragment(glsl, dim, dim, uniforms, parallel = false)
            var anyCertified = false
            for (size in listOf(1, 3, 8, 21, 64)) repeat(3) {
                val x0 = r.nextInt(dim - size + 1)
                val y0 = r.nextInt(dim - size + 1)
                val bound = StackSLIntervals.enclosePixels(glsl, x0, y0, x0 + size - 1, y0 + size - 1, dim, dim, uniforms)
                regions++
                if (!bound.certified) {
                    refusals.getOrPut(bound.refusal!!.take(90)) { mutableListOf() }.let { if (entry.name !in it) it += entry.name }
                    return@repeat
                }
                anyCertified = true
                for (y in y0 until y0 + size) for (x in x0 until x0 + size) {
                    val px = FloatArray(4) { raster.at(x, y, it) }
                    pixels++
                    if (!bound.contains(px)) {
                        violations++
                        if (first == null) first = "${entry.name} [$x0,$y0 +$size] pixel ($x,$y) = ${px.toList()} outside $bound"
                    }
                }
            }
            if (anyCertified) certifiedPrograms += entry.name
        }
        println("STACKSL INTERVAL CONTAINMENT: $regions regions, $pixels pixels, $violations violations; " +
            "${certifiedPrograms.size} programs certified somewhere: $certifiedPrograms")
        refusals.forEach { (reason, names) -> println("  refused (${names.size}): $reason — $names") }
        assertEquals(0, violations, first)
        assertTrue(pixels > 10_000, "containment population shrank to $pixels pixels")
        assertTrue(certifiedPrograms.size >= 5, "only ${certifiedPrograms.size} gallery programs could be bounded")
    }

    /** A single pixel is a point: its bound is the reference path's own value, exactly. */
    @Test
    fun aOnePixelRegionOfASmoothProgramIsThatPixel() {
        val ring = StackSLCpuGallery.load(root).single { it.name == "soft-ring" }
        val glsl = StackSLCpu.expandStackAuto(ring.fragmentTemplate, ring.program)
        val raster = StackSLCpu.renderFragment(glsl, 32, 32, parallel = false)
        val bound = StackSLIntervals.enclosePixels(glsl, 5, 7, 5, 7, 32, 32)
        assertTrue(bound.certified, bound.toString())
        for (ch in 0 until 3) assertTrue(bound.hi[ch] - bound.lo[ch] < 1e-6f, "channel $ch is $bound")
        assertTrue(bound.contains(FloatArray(4) { raster.at(5, 7, it) }))
    }

    /** The three-valued branch, on hand-written GLSL: both arms run, and their writes are joined. */
    @Test
    fun anUndecidedIfJoinsBothArmsAndADecidedOneTakesOne() {
        val glsl = """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            void main() {
                float v = 0.25;
                if (fragUV.x < 0.5) v = 0.75;
                outColor = vec4(v, fragUV.x < 0.5 ? 1.0 : 0.0, 0.0, 1.0);
            }
        """.trimIndent()
        val straddle = StackSLIntervals.enclosePixels(glsl, 0, 0, 63, 0, 64, 64)
        assertEquals(0.25f, straddle.lo[0]); assertEquals(0.75f, straddle.hi[0])
        assertEquals(0f, straddle.lo[1]); assertEquals(1f, straddle.hi[1])
        val left = StackSLIntervals.enclosePixels(glsl, 0, 0, 20, 63, 64, 64)
        assertEquals(0.75f, left.lo[0]); assertEquals(0.75f, left.hi[0])
    }

    /** Arms that leave by different routes cannot be joined, so the run is refused, by name. */
    @Test
    fun anUndecidedBranchThatReturnsFromOneArmOnlyIsRefused() {
        val glsl = """
            #version 450
            layout(location = 0) in vec2 fragUV;
            layout(location = 0) out vec4 outColor;
            float f(float x) { if (x < 0.5) return 1.0; x = x * 2.0; return x; }
            void main() { outColor = vec4(f(fragUV.x)); }
        """.trimIndent()
        val bound = StackSLIntervals.enclosePixels(glsl, 0, 0, 63, 0, 64, 64)
        assertFalse(bound.certified)
        assertTrue("two routes" in bound.refusal!!, bound.refusal)
    }

    /** A flat program settles in one tile; a gradient needs a quadtree, and every tile is a proof. */
    @Test
    fun certifiedTilesSettleFlatRegionsAndProveThem() {
        val flat = StackSLIntervals.certifiedTiles(still("0.3 gray"), 64)
        assertEquals(1, flat.size)
        assertTrue(flat.single().settled)

        val ring = StackSLCpuGallery.load(root).single { it.name == "soft-ring" }
        val glsl = StackSLCpu.expandStackAuto(ring.fragmentTemplate, ring.program)
        val tiles = StackSLIntervals.certifiedTiles(glsl, 64, tolerance = 4f / 255f)
        val raster = StackSLCpu.renderFragment(glsl, 64, 64, parallel = false)
        assertTrue(tiles.size > 4, "${tiles.size} tiles; first ${tiles.first().bound}")
        assertEquals(64 * 64, tiles.sumOf { it.size * it.size }, "the tiles must cover the image exactly once")
        for (t in tiles) for (y in t.y until t.y + t.size) for (x in t.x until t.x + t.size) {
            assertTrue(t.bound.contains(FloatArray(4) { raster.at(x, y, it) }), "tile ${t.x},${t.y} +${t.size}: ${t.bound}")
        }
        File(afield.proofDir("stacksl-intervals"), "tiles.png").writeBytes(StackSLIntervals.tilePng(tiles, 64, 4))
    }

    /**
     * The docs page's pictures, as numbers: each is a proof that covers the image exactly once, and
     * the counts printed here are the ones `public/docs/stacksl_intervals.md` describes.
     */
    @Test
    fun tilePicturesCoverTheImageAndReportTheirWork() {
        val dir = afield.proofDir("stacksl-tile-pictures")
        for (program in listOf(
            "p length 0.5 0.08 pulse white *",
            "uv x 18 * sin 0.5 + 0.5 * palette saturate",
            "uv 6 * noise palette saturate",
            "uv 5 * fbm 0.45 threshold orange * saturate",
        )) {
            val picture = StackSLIntervals.tilePicture(program, 128)
            assertEquals(128 * 128, picture.tiles.sumOf { it.size * it.size }, program)
            println("STACKSL TILES: '$program' ${picture.tiles.size} tiles, ${picture.tiles.count { it.settled }} settled, " +
                "${picture.provenByRegion} px proven by a region, ${picture.enclosures} runs, ${picture.millis} ms, refusal ${picture.refusal}")
            val stem = program.replace(Regex("[^a-z0-9]+"), "_")
            File(dir, "$stem.html").writeText(picture.toHTML())
            File(dir, "$stem.png").writeBytes(StackSLIntervals.tilePng(picture.tiles, 128, 2))
        }
    }
}

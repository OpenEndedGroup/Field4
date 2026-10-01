package afield.stdlib

import afield.intervalPicture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FnIntervalPictureTest {

    /**
     * The picture is drawn from the proof, not beside it: every recorded search piece is a real
     * enclosure (each sampled value in its x span lies inside it), both searches are recorded, and the
     * SVG carries one box per drawn piece. Written to a proof dir for a look, never into the tree.
     */
    @Test
    fun thePictureDrawsTheSearchItRecordedAndEveryPieceIsAnEnclosure() = with(Functions) {
        val f = saw(0.7, amplitude = 0.4) + gaussian(2.31, 0.0005, 0.6, Functions.Domain.ALL)
        val picture = f.intervalPicture(0.0, 6.0, title = "saw + a 0.5 ms bump")
        assertTrue(picture.range.certified, picture.range.toString())
        assertTrue(picture.pieces.any { it.side == FnSearchSide.MAX } && picture.pieces.any { it.side == FnSearchSide.MIN })
        // The bump is a tenth of the 1200-sample grid spacing wide; the proof finds it anyway.
        assertEquals(2.31, picture.range.max!!.witness, 0.01)
        for (p in picture.pieces) for (i in 0..8) {
            val x = p.lo + (p.hi - p.lo) * i / 8.0
            assertTrue(f.eval(x) in p.range, "f($x) = ${f.eval(x)} outside piece $p")
        }
        val svg = picture.svg()
        val drawn = picture.pieces.take(picture.maxBoxes).count { !it.range.isEmpty }
        assertTrue(Regex("<rect [^>]*stroke-width='0.6'").findAll(svg).count() == drawn)
        File(afield.proofDir("fn-interval-picture"), "saw_bump.svg").writeText(svg)
    }

    @Test
    fun aRefusedFunctionSaysSoAndDrawsNoBand() = with(Functions) {
        val f = fn(0.0..1.0, "opaque") { x -> x * x }
        val svg = f.intervalPicture(0.0, 1.0).svg()
        assertTrue("not certified" in svg)
        assertTrue("<polygon" !in svg, "a refused function must not get a band")
    }
}

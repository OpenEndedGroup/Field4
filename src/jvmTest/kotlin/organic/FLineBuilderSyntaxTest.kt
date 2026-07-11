package organic

import afield.graphics.FLine
import afield.stdlib.fline
import afield.stdlib.lineTo
import afield.stdlib.pointSize
import afield.stdlib.rangeTo
import afield.stdlib.vec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Exercises the terser FLine construction surface: variadic `data`, the builder-scoped
 * `+`/`-` pen operators, collection-literal `of`, the `..` segment operator with `+=`,
 * and node-scoped styling lambdas.
 */
class FLineBuilderSyntaxTest {

    private fun FLine.xy(i: Int): Pair<Float, Float> = nodes[i].to.let { it.x to it.y }

    @Test
    fun variadicDataAvoidsHandRolledList() {
        val f = FLine().data("m l l z", 0, 0, 1, 0, 1, 1)

        assertEquals(4, f.nodes.size)
        assertIs<FLine.MoveTo>(f.nodes[0])
        assertIs<FLine.LineTo>(f.nodes[1])
        assertIs<FLine.LineTo>(f.nodes[2])
        assertIs<FLine.Close>(f.nodes[3])
        assertEquals(1f to 1f, f.xy(2))
    }

    @Test
    fun penOperatorsDrawAndJump() {
        val f = fline {
            -vec(0, 0)   // empty -> promoted to moveTo
            -vec(1, 0)   // lineTo (draw)
            -vec(1, 1)   // lineTo
            +vec(5, 5)   // moveTo (new subpath, pen up)
            -vec(6, 5)   // lineTo
        }

        assertEquals(5, f.nodes.size)
        assertIs<FLine.MoveTo>(f.nodes[0])
        assertIs<FLine.LineTo>(f.nodes[1])
        assertIs<FLine.LineTo>(f.nodes[2])
        assertIs<FLine.MoveTo>(f.nodes[3])
        assertIs<FLine.LineTo>(f.nodes[4])
        assertEquals(5f to 5f, f.xy(3))
    }

    @Test
    fun collectionLiteralFromVectors() {
        val tri: FLine = [vec(0, 0, 0), vec(1, 0, 0), vec(0.5, 1, 0)]

        assertEquals(3, tri.nodes.size)
        assertIs<FLine.MoveTo>(tri.nodes[0])
        assertIs<FLine.LineTo>(tri.nodes[1])
        assertIs<FLine.LineTo>(tri.nodes[2])
        assertEquals(0.5f to 1f, tri.xy(2))
    }

    @Test
    fun collectionLiteralFromScalars() {
        val square: FLine = [0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f]

        assertEquals(4, square.nodes.size)
        assertIs<FLine.MoveTo>(square.nodes[0])
        assertEquals(0f to 0f, square.xy(0))
        assertEquals(0f to 1f, square.xy(3))
    }

    @Test
    fun rangeMakesSegmentAndPlusAssignExtends() {
        val r = vec(0, 0, 0)..vec(1, 1, 0)
        assertEquals(2, r.nodes.size)
        assertIs<FLine.MoveTo>(r.nodes[0])
        assertIs<FLine.LineTo>(r.nodes[1])

        r += vec(2, 0, 0)            // append a point (lineTo)
        assertEquals(3, r.nodes.size)
        assertEquals(2f to 0f, r.xy(2))

        r += vec(4, 0, 0)..vec(5, 5, 0) // append another segment (existing plusAssign(FLine))
        assertEquals(5, r.nodes.size)
    }

    @Test
    fun nodeStyleLambdaSetsPerNodeProperties() {
        val f = fline {
            moveTo(vec(0, 0, 0))
            lineTo(vec(1, 0, 0)) { pointSize = 4.0 }
        }

        assertEquals(2, f.nodes.size)
        assertEquals(4.0, f.nodes[1].pointSize)
    }
}

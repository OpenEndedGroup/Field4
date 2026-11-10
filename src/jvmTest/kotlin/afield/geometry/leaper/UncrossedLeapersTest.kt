package afield.geometry.leaper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The uncrossed-path search against OEIS A003192 and A157416, and the crossing test on wrapped boards. */
class UncrossedLeapersTest {
    /** Exhaustive search reproduces A003192 (0, 0, 2, 5, 10, 17, 24) for n = 1..7, and every path it returns checks. */
    @Test
    fun knightPathsMatchA003192() {
        for (n in 1..7) {
            val board = LeaperBoard(n, n)
            val r = board.longestUncrossedPath()
            println(r)
            assertTrue(r.exhaustive, "n = $n search finished")
            assertEquals(UNCROSSED_KNIGHT_PATH_MAXIMA[n - 1], r.length, "longest uncrossed knight path on $n×$n")
            r.best?.let { assertNull(board.check(it.cells), "returned path on $n×$n is legal") }
        }
    }

    /** Exhaustive search reproduces A157416 (0, 0, 0, 4, 8, 12, 24) for n = 1..7. */
    @Test
    fun knightCyclesMatchA157416() {
        for (n in 1..7) {
            val board = LeaperBoard(n, n)
            val r = board.longestUncrossedPath(closed = true)
            println(r)
            assertTrue(r.exhaustive, "n = $n cycle search finished")
            assertEquals(UNCROSSED_KNIGHT_CYCLE_MAXIMA[n - 1], r.length, "longest uncrossed knight cycle on $n×$n")
            r.best?.let { assertNull(board.check(it.cells, closed = true), "returned cycle on $n×$n is legal") }
        }
    }

    /** Two knight moves that cross only where a cylinder's edges are glued conflict there and not on the plain board. */
    @Test
    fun crossingIsMeasuredOnTheCylinder() {
        val cylinder = LeaperBoard(5, 5, Leaper.KNIGHT, BoardTopology.CYLINDER)
        fun cell(x: Int, y: Int) = y * 5 + x
        // (4,0)→(0,2) leaves through the right edge; (0,0)→(4,2) through the left. They cross at x = 4.5.
        val a = cylinder.moves.first { it.from == cell(0, 2) && it.to == cell(4, 0) || it.from == cell(4, 0) && it.to == cell(0, 2) }
        val b = cylinder.moves.first { it.from == cell(0, 0) && it.to == cell(4, 2) || it.from == cell(4, 2) && it.to == cell(0, 0) }
        assertTrue(cylinder.movesConflict(a, b), "moves crossing at the glued edge conflict on the cylinder")
        assertTrue(b.id in cylinder.conflicts[a.id])
        // The same cells as a path: 0,2 → 4,0 is legal alone; adding the crossing move is not.
        assertNull(cylinder.check(intArrayOf(cell(0, 2), cell(4, 0))))
        assertNotNull(cylinder.check(intArrayOf(cell(0, 2), cell(4, 0), cell(3, 2), cell(4, 4), cell(0, 2 + 0))), "revisits are refused")
        val rectangle = LeaperBoard(5, 5)
        assertNotNull(rectangle.check(intArrayOf(cell(0, 2), cell(4, 0))), "not a knight move without wrapping")
    }

    /** A wrapping move is drawn as two pieces that stay on the board. */
    @Test
    fun wrappedMovesAreCutAtTheGluedEdge() {
        val torus = LeaperBoard(6, 6, Leaper.KNIGHT, BoardTopology.TORUS)
        val pieces = torus.wrapSegment(5.0, 5.0, 6.0, 7.0)
        assertTrue(pieces.size >= 2, "a corner-wrapping move has at least two pieces: ${pieces.map { it.toList() }}")
        for (p in pieces) for (k in 0 until 4) assertTrue(p[k] in -0.5 - 1e-9..5.5 + 1e-9, "piece stays on the board: ${p.toList()}")
        // Leaving through the left and top edges: the pieces outside must be shifted back too.
        val backwards = torus.wrapSegment(0.0, 1.0, -1.0, -1.0)
        assertTrue(backwards.size >= 2, "a move off the top-left corner is cut: ${backwards.map { it.toList() }}")
        for (p in backwards) for (k in 0 until 4) assertTrue(p[k] in -0.5 - 1e-9..5.5 + 1e-9, "piece stays on the board: ${p.toList()}")
        assertEquals(1, LeaperBoard(6, 6).wrapSegment(0.0, 0.0, 1.0, 2.0).size, "rectangles never cut")
    }

    /** Segment test basics: a proper crossing, a shared end, a T, and a collinear overlap. */
    @Test
    fun segmentConflicts() {
        assertTrue(segmentsConflict(0, 0, 2, 2, 0, 2, 2, 0))
        assertFalse(segmentsConflict(0, 0, 1, 2, 1, 2, 3, 3))
        assertTrue(segmentsConflict(0, 0, 4, 0, 2, 0, 2, 3), "an end on the other's interior is a touch")
        assertTrue(segmentsConflict(0, 0, 4, 0, 2, 0, 6, 0), "collinear overlap")
        assertFalse(segmentsConflict(0, 0, 2, 0, 2, 0, 4, 0), "collinear but meeting at one end")
    }

    /** The other leapers have far fewer uncrossed options; these values pin the gallery's sweep. */
    @Test
    fun otherLeapersOnSixBySix() {
        val expected = mapOf(Leaper.CAMEL to 7, Leaper.ZEBRA to 7, Leaper.GIRAFFE to 7, Leaper.ANTELOPE to 4)
        for ((leaper, length) in expected) {
            val r = LeaperBoard(6, 6, leaper).longestUncrossedPath()
            println(r)
            assertTrue(r.exhaustive)
            assertEquals(length, r.length, "6×6 $leaper")
        }
    }

    /** A drawn polyline that passes over four cell centres snaps to those cells and checks as legal. */
    @Test
    fun drawnPolylineSnapsToCells() {
        val board = LeaperBoard(5, 5)
        val centres = listOf(0 to 0, 1 to 2, 3 to 3, 4 to 1)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (k in 0 until centres.size - 1) for (s in 0..20) {
            val (x0, y0) = centres[k]
            val (x1, y1) = centres[k + 1]
            xs += ((x0 + (x1 - x0) * s / 20.0) + 0.5) * 10.0 + 0.4
            ys += ((y0 + (y1 - y0) * s / 20.0) + 0.5) * 10.0 - 0.3
        }
        val cells = board.readDrawnPath(xs.toDoubleArray(), ys.toDoubleArray(), cell = 10.0)
        assertEquals(centres.map { (x, y) -> y * 5 + x }, cells.toList())
        assertNull(board.check(cells))
    }

    /**
     * An independent check of the wrapped crossing test: draw each result on the flat board as
     * [LeaperBoard.drawnPieces] cuts it, and look for a proper crossing between any two pieces of
     * different moves with floating-point geometry.
     */
    @Test
    fun wrappedResultsHaveNoDrawnCrossings() {
        fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) =
            Math.signum((bx - ax) * (cy - ay) - (by - ay) * (cx - ax))
        for (topology in BoardTopology.entries) for (leaper in listOf(Leaper.KNIGHT, Leaper.ZEBRA)) for (n in 4..6) {
            val board = LeaperBoard(n, n, leaper, topology)
            val path = board.longestUncrossedPath(nodeBudget = 2_000_000).best ?: continue
            val pieces = board.drawnPieces(path).flatMapIndexed { k, list -> list.map { k to it } }
            for (i in pieces.indices) for (j in i + 1 until pieces.size) {
                val (ki, s) = pieces[i]
                val (kj, t) = pieces[j]
                if (ki == kj) continue
                val crosses = orient(s[0], s[1], s[2], s[3], t[0], t[1]) * orient(s[0], s[1], s[2], s[3], t[2], t[3]) < 0 &&
                    orient(t[0], t[1], t[2], t[3], s[0], s[1]) * orient(t[0], t[1], t[2], t[3], s[2], s[3]) < 0
                assertFalse(crosses, "$board: moves ${ki + 1} and ${kj + 1} cross as drawn")
            }
        }
    }

    /**
     * Ruin and recreate reaches the proved 8×8 optimum, 35, from seed 1 in a few seconds; the
     * exhaustive proof of 35 takes 336,975,557 search steps and is not run here.
     */
    @Test
    fun ruinAndRecreateReaches35OnEightByEight() {
        val board = LeaperBoard(8, 8)
        val r = board.longUncrossedPath(rounds = 1000, nodesPerRound = 4_000, random = kotlin.random.Random(1))
        println(r)
        assertEquals(UNCROSSED_KNIGHT_PATH_MAXIMA[7], r.length)
        assertNull(board.check(r.best!!.cells, moveIds = r.best.moves))
        assertFalse(r.exhaustive)
    }

    /** A path, its mirror image and its reverse share a shape key; a different path does not. */
    @Test
    fun shapeKeyIgnoresSymmetryAndDirection() {
        val board = LeaperBoard(5, 5)
        fun cell(x: Int, y: Int) = y * 5 + x
        val path = board.checkPath(intArrayOf(cell(0, 0), cell(1, 2), cell(3, 3))).first!!
        val mirrored = board.checkPath(intArrayOf(cell(4, 0), cell(3, 2), cell(1, 3))).first!!
        val reversed = board.checkPath(intArrayOf(cell(3, 3), cell(1, 2), cell(0, 0))).first!!
        val other = board.checkPath(intArrayOf(cell(0, 0), cell(1, 2), cell(2, 0))).first!!
        assertEquals(path.shapeKey(), mirrored.shapeKey())
        assertEquals(path.shapeKey(), reversed.shapeKey())
        assertTrue(path.shapeKey() != other.shapeKey())
    }
}

package afield.graphics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What actually decides which ring [FloatEarcut] treats as the outer one — because the tree said
 * something else, in five places, and the something else was load-bearing in an argument.
 *
 * The claim, from `f531b1804`'s commit message and repeated into
 * `dev_docs/fline_fill_orientation.md`, `polytope/FieldAdapters.kt` and
 * `EarcutFaceTriangulatorHolesTest`, was that *"`FloatEarcut` decides which ring is the outer one from
 * the sign of its projected area, so a mirrored frame inverts the hole-versus-outer decision itself
 * and no later flip repairs it"*. It was offered as the reason the two triangulators **had** to be
 * unified: that one of the two conventions could not carry holes at all.
 *
 * It is false, and the code says so plainly:
 *
 *  * `earcut` slices the flat vertex array by **array position** — `outerLen = holeIndices[0] * dim`
 *    (`FloatEarcut.kt:19`), the outer ring is `[0, outerLen)` (`:21`), and each hole is the next slice
 *    (`:484-492`). No area is consulted to make that decision;
 *  * `linkedList` (`:705`) reads `signedArea` to choose a **traversal direction**, against a target
 *    handed to it by the caller: `true` for the outer ring (`:21`) and `false` for every hole (`:492`).
 *    So it *normalises* winding — outer one way, holes the other — whatever the caller authored.
 *
 * A mirror flips every ring's area sign *equally*, so it cannot reorder outer and hole. What it does
 * change is the handedness of the frame, and therefore the facing of every emitted triangle — which is
 * the true and sufficient reason to have one convention, and is what `PlanarTessellation` already says
 * correctly at its "why the frame decides the facing" note.
 *
 * Each of the three assertions below is one sentence of that, measured.
 */
class FloatEarcutOuterRingTest {

    private fun square(centre: Float, half: Float, clockwise: Boolean): List<Pair<Float, Float>> {
        val corners = listOf(
            (centre - half) to (centre - half),
            (centre + half) to (centre - half),
            (centre + half) to (centre + half),
            (centre - half) to (centre + half),
        )
        return if (clockwise) corners.reversed() else corners
    }

    private fun flatten(vararg rings: List<Pair<Float, Float>>): Pair<FloatArray, IntArray?> {
        val data = ArrayList<Float>()
        val holes = ArrayList<Int>()
        rings.forEachIndexed { at, ring ->
            if (at > 0) holes.add(data.size / 2)
            ring.forEach { data.add(it.first); data.add(it.second) }
        }
        return data.toFloatArray() to if (holes.isEmpty()) null else holes.toIntArray()
    }

    /** Signed areas of every emitted triangle, in the order they came back. */
    private fun signedAreas(data: FloatArray, triangles: List<Int>): List<Float> =
        triangles.chunked(3).map { (a, b, c) ->
            0.5f * (
                    (data[b * 2] - data[a * 2]) * (data[c * 2 + 1] - data[a * 2 + 1]) -
                            (data[c * 2] - data[a * 2]) * (data[b * 2 + 1] - data[a * 2 + 1])
                    )
        }

    private fun totalArea(data: FloatArray, triangles: List<Int>): Float =
        signedAreas(data, triangles).sumOf { abs(it).toDouble() }.toFloat()

    /**
     * All four windings of an outer square and its hole produce the **same** annulus.
     *
     * `100 x 100` minus `40 x 40` is `8400` however the two rings are wound — including the two
     * combinations where both rings have the *same* area sign, which is precisely the case an
     * area-sign rule could not resolve.
     */
    @Test
    fun everyCombinationOfWindingsProducesTheSameHoledTriangulation() {
        val answers = LinkedHashMap<String, Pair<Int, Float>>()
        for (outerClockwise in listOf(false, true)) {
            for (holeClockwise in listOf(false, true)) {
                val (data, holes) = flatten(square(50f, 50f, outerClockwise), square(50f, 20f, holeClockwise))
                val triangles = FloatEarcut.earcut(data, holes, 2)
                answers["outer=$outerClockwise hole=$holeClockwise"] =
                    triangles.size / 3 to totalArea(data, triangles)
            }
        }
        answers.forEach { (winding, answer) ->
            assertEquals(8, answer.first, "$winding: a quad with a quad hole is eight triangles")
            assertEquals(8400f, answer.second, 1f, "$winding: the hole was not subtracted")
        }
        assertEquals(4, answers.size)
    }

    /**
     * Array position is what decides, and reversing it is visible.
     *
     * Handing the small ring first makes *it* the outer ring and the big one its hole. That is a
     * geometrically nonsensical request, and earcut answers it as asked rather than noticing the areas
     * and rearranging — which is the point.
     */
    @Test
    fun arrayPositionIsWhatDecidesWhichRingIsOuter() {
        val (big, bigHoles) = flatten(square(50f, 50f, false), square(50f, 20f, true))
        val (small, smallHoles) = flatten(square(50f, 20f, false), square(50f, 50f, true))

        val asAnnulus = FloatEarcut.earcut(big, bigHoles, 2)
        val reversed = FloatEarcut.earcut(small, smallHoles, 2)

        assertEquals(8400f, totalArea(big, asAnnulus), 1f)
        assertNotEquals(
            totalArea(big, asAnnulus),
            totalArea(small, reversed),
            "if an area sign decided outer-versus-hole, swapping the array order would change nothing",
        )
    }

    /**
     * A mirrored frame flips the **facing** of every triangle and subtracts the same hole.
     *
     * This is the assertion that retires the false claim. If a mirror inverted the hole-versus-outer
     * decision, the mirrored triangulation could not still measure `8400`; it does, and every
     * triangle's signed area has simply changed sign.
     */
    @Test
    fun mirroringTheProjectedFrameFlipsFacingAndKeepsTheHoleAHole() {
        val (upright, holes) = flatten(square(50f, 50f, false), square(50f, 20f, true))
        val mirrored = FloatArray(upright.size) { at ->
            if (at % 2 == 0) upright[at + 1] else upright[at - 1]
        }

        val fromUpright = FloatEarcut.earcut(upright, holes, 2)
        val fromMirrored = FloatEarcut.earcut(mirrored, holes, 2)

        val a = signedAreas(upright, fromUpright)
        val b = signedAreas(mirrored, fromMirrored)

        assertEquals(8, a.size)
        assertEquals(8, b.size, "the mirror did not change how many triangles the annulus needs")
        assertEquals(
            8400f,
            b.sumOf { abs(it).toDouble() }.toFloat(),
            1f,
            "the mirrored frame still subtracted the hole, so it cannot have inverted outer-versus-hole",
        )

        // Measured in *earcut's own* frame the two answers have the same sign, and that is the
        // normalisation rather than a coincidence: whatever it is handed, it re-links the outer ring so
        // the triangles it cuts wind one fixed way in the coordinates it was given.
        assertTrue(a.all { it > 0f } || a.all { it < 0f }, "earcut normalised the winding: $a")
        assertTrue(b.all { it > 0f } || b.all { it < 0f }, "earcut normalised the winding: $b")
        assertEquals(a.first() > 0f, b.first() > 0f, "earcut's own frame always comes out the same way")

        // Which is exactly why the frame decides the facing. Read the mirrored triangulation back in
        // the *original* coordinates — the vertex indices are the same, only the frame moved — and
        // every triangle has turned over.
        val mirroredReadUpright = signedAreas(upright, fromMirrored)
        assertTrue(
            mirroredReadUpright.all { it > 0f } || mirroredReadUpright.all { it < 0f },
            "the mirrored triangulation should be consistent in 3D too: $mirroredReadUpright",
        )
        assertNotEquals(
            a.first() > 0f,
            mirroredReadUpright.first() > 0f,
            "the mirror should have flipped the facing, which is the real consequence",
        )
        assertEquals(
            8400f,
            mirroredReadUpright.sumOf { abs(it).toDouble() }.toFloat(),
            1f,
            "and it is still an annulus when read in the original frame",
        )
    }

    /**
     * And the normalisation itself, on a single ring with no holes: the same ring wound either way
     * comes back with the same facing. That is why "which two coordinates do I keep?" is a statement
     * about the surface normal rather than an implementation detail.
     */
    @Test
    fun earcutNormalisesTheWindingOfARingHandedToItEitherWay() {
        val (counterClockwise, _) = flatten(square(50f, 50f, false))
        val (clockwise, _) = flatten(square(50f, 50f, true))
        val a = signedAreas(counterClockwise, FloatEarcut.earcut(counterClockwise, null, 2))
        val b = signedAreas(clockwise, FloatEarcut.earcut(clockwise, null, 2))
        assertEquals(2, a.size)
        assertEquals(2, b.size)
        assertEquals(a.all { it > 0f }, b.all { it > 0f }, "the caller's winding survived: $a versus $b")
    }
}

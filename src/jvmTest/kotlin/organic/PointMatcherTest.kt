package organic

import org.joml.Vector2f
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PointMatcherTest {
    @Test
    fun exactMatchFindsPermutation() {
        val left = listOf(Vector2f(0.0f, 0.0f), Vector2f(10.0f, 0.0f), Vector2f(0.0f, 10.0f))
        val right = listOf(Vector2f(10.0f, 0.1f), Vector2f(0.2f, 10.0f), Vector2f(0.1f, 0.0f))

        val result = PointMatcher(costScale = 1_000.0).match(left, right)

        assertTrue(result.exact)
        assertContentEquals(intArrayOf(2, 0, 1), result.assignment)
        assertEquals(3, result.matches.size)
    }

    @Test
    fun candidateModeFallsBackWhenTooSparse() {
        val left = listOf(Vector2f(0.0f, 0.0f), Vector2f(0.0f, 0.1f), Vector2f(10.0f, 0.0f))
        val right = listOf(Vector2f(0.0f, 0.0f), Vector2f(9.9f, 0.0f), Vector2f(10.1f, 0.0f))

        val result = PointMatcher(costScale = 1_000.0, candidateCount = 1).match(left, right)

        assertTrue(result.exact)
        assertEquals(null, result.candidateCount)
        assertContentEquals(intArrayOf(0, 1, 2), result.assignment)
    }

    @Test
    fun candidateModeCanReturnSparseOptimalResult() {
        val left = listOf(Vector2f(0.0f, 0.0f), Vector2f(10.0f, 0.0f), Vector2f(20.0f, 0.0f))
        val right = listOf(Vector2f(0.1f, 0.0f), Vector2f(10.1f, 0.0f), Vector2f(20.1f, 0.0f))

        val result = PointMatcher(costScale = 1_000.0, candidateCount = 1).match(left, right)

        assertFalse(result.exact)
        assertEquals(1, result.candidateCount)
        assertContentEquals(intArrayOf(0, 1, 2), result.assignment)
    }
}

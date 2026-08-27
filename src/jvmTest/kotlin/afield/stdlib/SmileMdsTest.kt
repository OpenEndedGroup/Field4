package afield.stdlib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SmileMdsTest {
    @Test
    fun projectsVectorsWithoutChangingRowOrderOrShape() {
        val vectors = Array(12) { row ->
            DoubleArray(6) { column ->
                kotlin.math.sin((row + 1.0) * (column + 1.0) * 0.31) +
                    kotlin.math.cos(row * 0.17 + column * 0.43)
            }
        }
        val projection = smileMds(vectors, dimensions = 4)

        assertEquals(12, projection.coordinates.size)
        assertTrue(projection.coordinates.all { it.size == 4 })
        assertTrue(projection.coordinates.all { row -> row.all(Double::isFinite) })
        assertTrue(projection.scores.isNotEmpty())
        assertEquals(SmileMdsDistance.COSINE, projection.distance)

        assertTrue(projection.coordinates.any { row -> row.any { kotlin.math.abs(it) > 1e-6 } })
    }

    @Test
    fun rejectsAZeroVectorForCosineDistance() {
        assertFailsWith<IllegalArgumentException> {
            smileMds(arrayOf(doubleArrayOf(1.0, 0.0), doubleArrayOf(0.0, 0.0)))
        }
    }

    @Test
    fun tsneIsFiniteAndReproducibleForAFixedSeed() {
        val vectors = Array(30) { row ->
            DoubleArray(8) { column ->
                kotlin.math.sin(row * 0.19 + column * 0.37) +
                    kotlin.math.cos((row + 1.0) * (column + 1.0) * 0.07)
            }
        }
        fun fit() = smileTsne(
            vectors,
            dimensions = 4,
            perplexity = 5.0,
            maxIterations = 300,
            seed = 17L,
        )

        val first = fit()
        val second = fit()
        assertEquals(30, first.coordinates.size)
        assertTrue(first.coordinates.all { row -> row.size == 4 && row.all(Double::isFinite) })
        assertTrue(first.cost.isFinite())
        first.coordinates.indices.forEach { row ->
            first.coordinates[row].indices.forEach { column ->
                assertEquals(first.coordinates[row][column], second.coordinates[row][column], 1e-12)
            }
        }
    }
}

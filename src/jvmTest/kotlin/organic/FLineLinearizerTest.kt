package organic

import afield.graphics.FLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FLineLinearizerTest {
    @Test
    fun linearizesMoveAndLineInstructionsIntoVertexRecords() {
        val line = FLine().apply {
            moveTo(0, 0, 0)
            lineTo(3, 4, 0)
            lineTo(3, 4, 0)
            moveTo(3.00001, 4.00001, 0)
            lineTo(6, 4, 0)
        }

        val result = line.linearize(prefix = 100, epsilon = 1e-3f)

        assertEquals(3 * FLINE_LINEAR_VERTEX_STRIDE, result.size)
        assertRecord(
            result,
            0,
            100f, 0f, 0f, 0f,
            101f to 5f
        )
        assertRecord(
            result,
            1,
            101f, 3f, 4f, 0f,
            100f to 5f,
            102f to 3f
        )
        assertRecord(
            result,
            2,
            102f, 6f, 4f, 0f,
            101f to 3f
        )
    }

    @Test
    fun keepsOnlyTheFirstFourEdgesForARecord() {
        val line = FLine().apply {
            for (i in 1..5) {
                moveTo(0, 0, 0)
                lineTo(i, 0, 0)
            }
        }

        val result = linearizeFLine(line, prefix = 10)

        assertEquals(6 * FLINE_LINEAR_VERTEX_STRIDE, result.size)
        assertRecord(
            result,
            0,
            10f, 0f, 0f, 0f,
            11f to 1f,
            12f to 2f,
            13f to 3f,
            14f to 4f
        )
        assertRecord(
            result,
            5,
            15f, 5f, 0f, 0f
        )
    }

    @Test
    fun lineToWithoutCurrentPointCreatesAFreeVertex() {
        val line = FLine().apply {
            lineTo(1, 2, 3)
        }

        val result = line.linearize(prefix = 7)

        assertRecord(result, 0, 7f, 1f, 2f, 3f)
    }

    private fun assertRecord(
        data: FloatArray,
        index: Int,
        id: Float,
        x: Float,
        y: Float,
        z: Float,
        vararg edges: Pair<Float, Float>
    ) {
        val expected = FloatArray(FLINE_LINEAR_VERTEX_STRIDE) { -1f }
        expected[0] = id
        expected[1] = x
        expected[2] = y
        expected[3] = z
        edges.forEachIndexed { edgeIndex, edge ->
            expected[4 + edgeIndex * 2] = edge.first
            expected[5 + edgeIndex * 2] = edge.second
        }

        val actual = data.copyOfRange(
            index * FLINE_LINEAR_VERTEX_STRIDE,
            (index + 1) * FLINE_LINEAR_VERTEX_STRIDE
        )
        assertContentEquals(expected, actual)
    }
}

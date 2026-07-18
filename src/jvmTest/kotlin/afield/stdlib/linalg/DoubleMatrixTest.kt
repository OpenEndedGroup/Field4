package afield.stdlib.linalg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * Numeric coverage for the generated double-precision matrix family (`Matrix2d`, `Matrix4d`),
 * with a `Matrix3d` sanity pass. All storage is column-major (JOML): `set(...)` takes columns.
 * Tolerances are f64-scale; the point of these types is that they never fall back to `float`.
 */
class DoubleMatrixTest {

    // ----- Matrix2d -----------------------------------------------------------------------------

    @Test
    fun matrix2dMultipliesAgainstKnownProduct() {
        // Column-major set(m00,m01, m10,m11): matrix rows are (m00,m10) and (m01,m11).
        // A = [[1,3],[2,4]], B = [[5,7],[6,8]]  ->  A*B = [[23,31],[34,46]].
        val a = Matrix2d(1.0, 2.0, 3.0, 4.0)
        val b = Matrix2d(5.0, 6.0, 7.0, 8.0)
        val p = Matrix2d(a).mul(b)
        assertEquals(23.0, p.m00(), 1e-12); assertEquals(34.0, p.m01(), 1e-12)
        assertEquals(31.0, p.m10(), 1e-12); assertEquals(46.0, p.m11(), 1e-12)
    }

    @Test
    fun matrix2dDeterminantAndInverse() {
        val a = Matrix2d(1.0, 2.0, 3.0, 4.0) // det = 1*4 - 3*2 = -2
        assertEquals(-2.0, a.determinant(), 1e-12)

        val identity = Matrix2d(a).mul(Matrix2d(a).invert())
        assertEquals(1.0, identity.m00(), 1e-12); assertEquals(0.0, identity.m01(), 1e-12)
        assertEquals(0.0, identity.m10(), 1e-12); assertEquals(1.0, identity.m11(), 1e-12)
    }

    @Test
    fun matrix2dTransposeReflectsWithoutMutatingSource() {
        val a = Matrix2d(1.0, 2.0, 3.0, 4.0)
        val dest = Matrix2d()
        assertSame(dest, a.transpose(dest))
        assertEquals(a.m10(), dest.m01()); assertEquals(a.m01(), dest.m10())
        assertEquals(2.0, a.m01(), "transpose(dest) must not mutate the source")
    }

    // ----- Matrix4d -----------------------------------------------------------------------------

    @Test
    fun matrix4dIdentityMultiplyIsNoOp() {
        val m = Matrix4d().scale(2.0, 3.0, 4.0).translate(5.0, 6.0, 7.0)
        val viaLeft = Matrix4d().mul(m)
        val viaRight = Matrix4d(m).mul(Matrix4d())
        for (i in 0 until 16) {
            assertEquals(m[i], viaLeft[i], 1e-12, "identity * m [$i]")
            assertEquals(m[i], viaRight[i], 1e-12, "m * identity [$i]")
        }
    }

    @Test
    fun matrix4dDeterminantOfScaleIsProductOfScales() {
        // A pure scale has det = sx*sy*sz; a subsequent translate must not change it.
        val m = Matrix4d().scale(2.0, 3.0, 4.0).translate(5.0, 6.0, 7.0)
        assertEquals(24.0, m.determinant(), 1e-9)
    }

    @Test
    fun matrix4dInverseTimesSelfIsIdentity() {
        val m = Matrix4d().scale(2.0, 3.0, 4.0).translate(5.0, 6.0, 7.0)
        val identity = Matrix4d(m).mul(Matrix4d(m).invert())
        for (column in 0 until 4) for (row in 0 until 4) {
            val expected = if (column == row) 1.0 else 0.0
            assertEquals(expected, identity[column, row], 1e-9, "identity[$column,$row]")
        }
    }

    @Test
    fun matrix4dTransposeTwiceIsOriginalAndReflects() {
        val m = Matrix4d().scale(2.0, 3.0, 4.0).translate(5.0, 6.0, 7.0)
        val t = Matrix4d(m).transpose()
        // Translation lives in column 3 (m30,m31,m32); transpose moves it into row 3.
        assertEquals(m.m30(), t.m03()); assertEquals(m.m31(), t.m13()); assertEquals(m.m32(), t.m23())
        val twice = Matrix4d(m).transpose().transpose()
        for (i in 0 until 16) assertEquals(m[i], twice[i], 1e-12, "transpose twice [$i]")
    }

    @Test
    fun matrix4dPreservesValuesWithNoExactFloatForm() {
        val third = 1.0 / 3.0
        assertEquals(third, Matrix4d().m00(third).m00())
        assertNotEquals(third, third.toFloat().toDouble())
    }

    // ----- Matrix3d sanity (already covered by GeneratedLinalgTest; guards the shared model) -----

    @Test
    fun matrix3dInverseTimesSelfIsIdentity() {
        val a = Matrix3d().set(
            2.0, 0.0, 1.0,
            1.0, 3.0, 0.0,
            0.0, 1.0, 4.0,
        )
        assertEquals(25.0, a.determinant(), 1e-12)
        val identity = Matrix3d(a).mul(Matrix3d(a).invert())
        for (column in 0 until 3) for (row in 0 until 3) {
            val expected = if (column == row) 1.0 else 0.0
            val actual = when (column * 3 + row) {
                0 -> identity.m00(); 1 -> identity.m01(); 2 -> identity.m02()
                3 -> identity.m10(); 4 -> identity.m11(); 5 -> identity.m12()
                6 -> identity.m20(); 7 -> identity.m21(); else -> identity.m22()
            }
            assertEquals(expected, actual, 1e-9, "identity[$column,$row]")
        }
    }
}

package afield.stdlib.linalg

import kotlin.test.*

class NormalizedVectorsTest {
    @Test fun generatedDimensionsReturnNewValuesAndSupportAliasedDestinations() {
        val f2=Vector2f(3f,4f);val f3=Vector3f(2f,3f,6f);val f4=Vector4f(1f,2f,2f,4f)
        val d2=Vector2d(3.0,4.0);val d3=Vector3d(2.0,3.0,6.0);val d4=Vector4d(1.0,2.0,2.0,4.0)
        assertNotSame(f2,f2.normalized());assertEquals(Vector2f(3f,4f),f2)
        assertEquals(1f,f2.normalized().length(),1e-6f)
        assertEquals(1f,f3.normalized().length(),1e-6f)
        assertEquals(1f,f4.normalized().length(),1e-6f)
        assertEquals(1.0,d2.normalized().length(),1e-12)
        assertEquals(1.0,d3.normalized().length(),1e-12)
        assertEquals(1.0,d4.normalized().length(),1e-12)
        assertSame(d2,d2.normalized(d2));assertEquals(.6,d2.x,1e-12);assertEquals(.8,d2.y,1e-12)
        val half=Vector8h(1f,1f,1f,1f,1f,1f,1f,1f)
        assertEquals(1f,half.normalized().length(),.001f)
    }

    @Test fun zeroThresholdAndExtremeFiniteMagnitudesAreDefined() {
        assertEquals(Vector2d(),Vector2d().normalized())
        assertEquals(Vector3f(),Vector3f(1e-8f,0f,0f).normalized(1e-6f))
        assertEquals(Vector2d(1.0,0.0),Vector2d(Double.MIN_VALUE,0.0).normalized())
        assertEquals(Vector2f(1f,0f),Vector2f(Float.MIN_VALUE,0f).normalized())
        assertEquals(1.0,Vector2d(Double.MAX_VALUE,Double.MAX_VALUE).normalized().length(),1e-12)
        assertEquals(1f,Vector3f(Float.MAX_VALUE,Float.MAX_VALUE,Float.MAX_VALUE).normalized().length(),1e-6f)
        assertFailsWith<IllegalArgumentException> { Vector2f(Float.NaN,0f).normalized() }
        assertFailsWith<IllegalArgumentException> { Vector2d(Double.POSITIVE_INFINITY,0.0).normalized() }
        assertFailsWith<IllegalArgumentException> { Vector2d().normalized(-1.0) }
        // Existing in-place normalize semantics are unchanged.
        assertFalse(Vector2d().normalize().isFinite())
    }
}

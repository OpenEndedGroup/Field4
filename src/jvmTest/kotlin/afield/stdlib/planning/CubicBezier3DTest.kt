package afield.stdlib.planning

import kotlin.math.*
import kotlin.test.*

class CubicBezier3DTest {
    private val k=4.0*(sqrt(2.0)-1)/3.0
    private val arc=CubicBezier3D(Point3D(1000.0,0.0,0.0), Point3D(1000.0,k*1000,0.0),
        Point3D(k*1000,1000.0,0.0), Point3D(0.0,1000.0,0.0))

    @Test fun boundAcceptsBroadTurnAndRejectsTooTightTurn() {
        val bound=arc.minimumRadiusBound(950.0)
        assertTrue(bound in 950.0..1000.0)
        assertFailsWith<IllegalArgumentException> { arc.minimumRadiusBound(1100.0) }
        // A cubic approximation of a circle is not a circular arc: use its true derivatives.
        for(i in 0..1000) {
            val t=i/1000.0;val d=arc.tangentAt(t)
            val e=(arc.control2-arc.control1*2.0+arc.start)*(6*(1-t))+
                (arc.end-arc.control2*2.0+arc.control1)*(6*t)
            assertTrue(bound<=d.length.pow(3)/(d cross e).length+1e-8)
        }
    }

    @Test fun flattenedCurveRespectsFiniteChordEnvelope() {
        val points=arc.flatten(.01)
        assertEquals(arc.start,points.first());assertEquals(arc.end,points.last())
        for(i in 0..2000) {
            val q=arc.valueAt(i/2000.0)
            val distance=points.zipWithNext().minOf { (a,b) ->
                val d=b-a; q.distanceTo(a+d*(((q-a) dot d)/(d dot d)).coerceIn(0.0,1.0))
            }
            assertTrue(distance<=.01+1e-8)
        }
    }

    @Test fun straightRunHasTwoPointsAndAcceptsLargeRadius() {
        val line=CubicBezier3D(Point3D.ORIGIN,Point3D(1.0,0.0,0.0),Point3D(2.0,0.0,0.0),Point3D(3.0,0.0,0.0))
        assertEquals(listOf(line.start,line.end),line.flatten(.001))
        assertTrue(line.minimumRadiusBound(1e6)>=1e6)
    }

    @Test fun translationRotationAndUnitChangesPreserveTheContract() {
        fun move(p:Point3D)=Point3D(-p.z+25,p.x-100,p.y+77)*.001
        val moved=CubicBezier3D(move(arc.start),move(arc.control1),move(arc.control2),move(arc.end))
        assertEquals(arc.minimumRadiusBound(950.0)*.001,moved.minimumRadiusBound(.950),1e-10)
        val a=arc.flatten(.01).map(::move);val b=moved.flatten(.00001)
        assertEquals(a.size,b.size);a.zip(b).forEach{(p,q)->assertTrue(p.distanceTo(q)<1e-12)}
    }

    @Test fun cuspDegenerateCurveAndInvalidRequestsFailClosed() {
        val cusp=CubicBezier3D(Point3D.ORIGIN,Point3D(1.0,0.0,0.0),Point3D(-1.0,0.0,0.0),Point3D(.1,0.0,0.0))
        assertFailsWith<IllegalArgumentException>{cusp.minimumRadiusBound(1.0)}
        val zero=CubicBezier3D(Point3D.ORIGIN,Point3D.ORIGIN,Point3D.ORIGIN,Point3D.ORIGIN)
        assertFailsWith<IllegalArgumentException>{zero.minimumRadiusBound(1.0)}
        assertFailsWith<IllegalArgumentException>{zero.flatten(.01)}
        for(x in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>{arc.minimumRadiusBound(x)}
            assertFailsWith<IllegalArgumentException>{arc.flatten(x)}
        }
        assertFailsWith<IllegalArgumentException>{arc.minimumRadiusBound(950.0,0)}
        assertFailsWith<IllegalArgumentException>{arc.flatten(.01,0)}
        assertFailsWith<IllegalArgumentException>{arc.valueAt(1.01)}
    }
}

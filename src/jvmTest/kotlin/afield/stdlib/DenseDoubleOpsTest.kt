package afield.stdlib

import kotlin.math.*
import kotlin.test.*

class DenseDoubleOpsTest {
    @Test fun tailsAliasingAndReductions() {
        for(n in listOf(0,1,2,3,7,16,31,50097)) {
            val a=DoubleArray(n) {sin(it.toDouble())};val b=DoubleArray(n) {cos(it*.73)}
            val ref=a.indices.sumOf {a[it]*b[it]}
            assertEquals(ref,DenseDoubleOps.dot(a,b),1e-10)
            assertEquals(a.sumOf {it*it},DenseDoubleOps.dot(a,a),1e-8)
            val expected=DoubleArray(n) {a[it]-.3*b[it]}
            val out=DoubleArray(n);DenseDoubleOps.combine(out,a,-.3,b)
            assertTrue(out.contentEquals(expected))
            val left=a.copyOf();DenseDoubleOps.combine(left,left,-.3,b);assertTrue(left.contentEquals(expected))
            val right=b.copyOf();DenseDoubleOps.combine(right,a,-.3,right);assertTrue(right.contentEquals(expected))
            DenseDoubleOps.scale(out,2.0);assertTrue(out.contentEquals(DoubleArray(n){2*expected[it]}))
        }
        assertFailsWith<IllegalArgumentException> {DenseDoubleOps.dot(doubleArrayOf(1.0),doubleArrayOf())}
    }
}

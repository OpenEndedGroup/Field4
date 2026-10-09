package afield.graphics

import afield.stdlib.*
import kotlin.test.*

class FLineGaussianProcessTest {
    @Test fun `planar curve keeps z and exposes nonzero uncertainty between pins`() {
        val curve=gpCurve(doubleArrayOf(0.0,1.0),listOf(vec(0,0,7),vec(1,1,7)),dimensions=2)
        assertEquals(0.0,curve.variance(0.5).z.toDouble())
        assertTrue(curve.variance(0.5).x>0)
        val joint=curve.posterior(doubleArrayOf(0.0,0.5,1.0))
        for(p in joint.samplePoints(52))assertEquals(7.0,p.z.toDouble())
        val (a,b)=joint.normalBand()
        assertEquals(3,a.nodes.size);assertEquals(3,b.nodes.size)
        assertEquals(3,joint.sampleLine(52).nodes.size)
    }
    @Test fun `correlated 3d curve keeps full covariance and projected variance`() {
        val b=arrayOf(doubleArrayOf(1.0,0.4,0.2),doubleArrayOf(0.4,1.0,0.0),doubleArrayOf(0.2,0.0,1.0))
        val c=gpCurve(doubleArrayOf(0.0),listOf(vec(1,2,3)),outputCovariance=b)
        val j=c.posterior(doubleArrayOf(0.5));val cov=j.covarianceAt(0)
        assertTrue(cov[0][1]>0)
        assertEquals(cov[0][0]+cov[1][1]+2*cov[0][1],j.projectedVariance(0,vec(1,1,0)),1e-9)
        assertFailsWith<IllegalArgumentException>{j.normalBand()}
    }
    @Test fun `FLine wrapper uses normalized arc length and rejects unrelated subpaths`() {
        val line=FLine().moveTo(0,0).lineTo(30,0).lineTo(30,10)
        val model=line.gaussianProcess(trainingSamples=10,dimensions=2,noiseStd=0.01)
        assertEquals(0.0,model.mean(0.0).x.toDouble(),0.02)
        assertEquals(30.0,model.mean(1.0).x.toDouble(),0.02)
        assertEquals(10.0,model.mean(1.0).y.toDouble(),0.02)
        assertFailsWith<IllegalArgumentException>{FLine().moveTo(0,0).lineTo(1,1).moveTo(5,5).lineTo(6,6).gaussianProcess()}
    }
    @Test fun `scalar FLine helpers use variance square roots and same joint samples`() {
        val x=doubleArrayOf(0.0,0.5,1.0)
        val j=GaussianProcess.prior(kernel=GpKernels.constant(4.0)).posterior(x)
        val (low,high)=j.intervalFLines(x,2.0)
        for(n in low.nodes)assertEquals(-4.0,n.storage[0].y.toDouble())
        for(n in high.nodes)assertEquals(4.0,n.storage[0].y.toDouble())
        val sampled=j.sampleFLine(x,5)
        assertTrue(sampled.nodes.map{it.storage[0].y}.distinct().size==1)
    }
    @Test fun `legacy FLine GP now draws coherent independent coordinate processes`() {
        val source=FLine().moveTo(0,0,0).lineTo(1,0,0)
        val a=source.byGaussianProcessInterpolating(trainingSamples=2,samples=25,lengthScale=0.4f,drawSamples=true,seed=14)
        val b=source.byGaussianProcessInterpolating(trainingSamples=2,samples=25,lengthScale=0.4f,drawSamples=true,seed=14)
        assertEquals(a.nodes.map{it.storage[0].toString()},b.nodes.map{it.storage[0].toString()})
        assertTrue(a.nodes.any{it.storage[0].y!=it.storage[0].z})
    }
    @Test fun `Vec2 and Vec3 factories accept heteroscedastic observations`() {
        val p2=listOf(vec(0,0),vec(1,1));val p3=listOf(vec(0,0,0),vec(1,1,1))
        val y=doubleArrayOf(1.0,-1.0);val noise=doubleArrayOf(0.1,0.3)
        assertTrue(gp2D(p2,y,noise).predict(vec(0.4,0.4)).variance>0)
        assertTrue(gp3D(p3,y,noise).predict(vec(0.4,0.4,0.4)).variance>0)
    }
}
